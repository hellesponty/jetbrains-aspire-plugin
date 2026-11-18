package io.github.hellesponty.aspire.services

import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.services.ServiceEventListener
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.github.hellesponty.aspire.cli.AspireCli
import io.github.hellesponty.aspire.cli.AspireCliNotFoundException
import io.github.hellesponty.aspire.cli.AspireConfig
import io.github.hellesponty.aspire.cli.AspireJson
import io.github.hellesponty.aspire.cli.ResourceInfo
import io.github.hellesponty.aspire.cli.RunningAppHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

enum class AppHostPhase { STOPPED, STARTING, RUNNING, STOPPING }

/**
 * Per-project state for the AppHost declared in `aspire.config.json`.
 *
 * Everything goes through the `aspire` CLI, so the same code drives TypeScript and C# AppHosts:
 *  - `aspire ps --follow` tells us whether our AppHost is running (also when started from a terminal),
 *  - `aspire describe --follow` streams resource state,
 *  - `aspire logs --follow` streams console logs for all resources,
 *  - `aspire start|stop` and `aspire resource <name> <command>` change things.
 */
@Service(Service.Level.PROJECT)
class AspireAppHostService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    @Volatile var config: AspireConfig? = null
        private set
    @Volatile var phase: AppHostPhase = AppHostPhase.STOPPED
        private set
    @Volatile var appHost: RunningAppHost? = null
        private set

    private val resourcesLock = Any()
    private val resources = LinkedHashMap<String, ResourceInfo>()

    /** Logs per resource display name; replicas of one resource share a console. */
    private val sinks = ConcurrentHashMap<String, LogSink>()
    val appHostSink: LogSink by lazy { LogSink(project, this) }

    /** Toolbars must get the same action instances on every update, so groups are built once per node. */
    val actionGroups = ConcurrentHashMap<String, com.intellij.openapi.actionSystem.ActionGroup>()

    private var psJob: Job? = null
    private var connectedPid: Long? = null
    private var connectionJobs: List<Job> = emptyList()
    @Volatile private var lastConnectAt = 0L
    private val changes = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var logLines = 0

    init {
        scope.launch {
            for (u in changes) {
                delay(150)
                project.messageBus.syncPublisher(ServiceEventListener.TOPIC)
                    .handle(ServiceEventListener.ServiceEvent.createResetEvent(AspireServiceViewContributor::class.java))
            }
        }
    }

    fun cli(): AspireCli = AspireCli(config?.repoRoot ?: Path.of(project.basePath ?: "."))

    private fun appHostArgs(): Array<String> = config?.let { arrayOf("--apphost", it.appHostPath.toString()) } ?: emptyArray()

    // region detection

    /** Re-reads `aspire.config.json` and (re)starts watching for a running AppHost. */
    @Synchronized
    fun reload() {
        val newConfig = AspireConfig.find(project)
        val changed = newConfig != config
        config = newConfig
        LOG.info("aspire.config.json: ${newConfig?.let { "${it.configFile} -> ${it.appHostPath} (${it.language})" } ?: "not found"}")
        if (changed) {
            psJob?.cancel(null)
            psJob = null
            disconnect()
        }
        if (newConfig != null && psJob == null) psJob = watchAppHosts()
        fireChanged()
    }

    private fun watchAppHosts(): Job = scope.launch {
        while (true) {
            try {
                checkRunningOnce()
                val job = cli().stream(this, "ps", "--format", "Json", "--follow") { line ->
                    val json = AspireJson.parseOrNull(line) ?: return@stream
                    val all = AspireJson.parseAppHosts(json)
                    val mine = all.firstOrNull { isOurs(it.appHostPath) }
                    when {
                        mine != null -> onAppHostSeen(mine)
                        json.isJsonArray -> onAppHostGone() // a full snapshot without us
                    }
                }
                job.join()
            } catch (e: AspireCliNotFoundException) {
                appHostSink.system(e.message ?: "Aspire CLI not found")
                return@launch
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LOG.warn("aspire ps --follow failed", e)
            }
            delay(5_000)
        }
    }

    /** One-shot `aspire ps`, used at startup and whenever we suspect the AppHost went away. */
    suspend fun checkRunningOnce() {
        val result = try {
            cli().run("ps", "--format", "Json")
        } catch (e: AspireCliNotFoundException) {
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LOG.info("aspire ps failed", e)
            return
        }
        val json = AspireJson.parseOrNull(result.stdout.substringFrom('['))
        val mine = json?.let(AspireJson::parseAppHosts)?.firstOrNull { isOurs(it.appHostPath) }
        if (mine != null) onAppHostSeen(mine) else if (result.ok) onAppHostGone()
    }

    private fun isOurs(path: String): Boolean {
        val ours = config?.appHostPath ?: return false
        val theirs = Path.of(path).normalize()
        if (theirs == ours) return true
        // Config may point at a directory; the CLI reports the entry file.
        return Files.isDirectory(ours) && theirs.startsWith(ours) ||
            runCatching { theirs.toRealPath() == ours.toRealPath() }.getOrDefault(false)
    }

    @Synchronized
    private fun onAppHostSeen(host: RunningAppHost) {
        if (!host.isRunning) return onAppHostGone()
        appHost = host
        if (phase != AppHostPhase.STOPPING) phase = AppHostPhase.RUNNING
        if (connectedPid != host.appHostPid || connectionJobs.any { !it.isActive }) connect(host)
        fireChanged()
    }

    @Synchronized
    private fun onAppHostGone() {
        if (phase == AppHostPhase.STARTING) return // `aspire start` is still building
        val wasConnected = connectedPid != null
        appHost = null
        phase = AppHostPhase.STOPPED
        disconnect()
        if (wasConnected) {
            LOG.info("AppHost no longer running")
            appHostSink.system("AppHost stopped.")
        }
        fireChanged()
    }

    // endregion

    // region streaming

    private fun connect(host: RunningAppHost) {
        LOG.info("Connecting to AppHost ${host.appHostPath} pid=${host.appHostPid}")
        lastConnectAt = System.currentTimeMillis()
        logLines = 0
        val samePid = connectedPid == host.appHostPid
        disconnect()
        connectedPid = host.appHostPid
        // `logs --follow` replays history first, so start from clean consoles.
        // For a fresh AppHost keep the combined console: it holds the `aspire start` output.
        if (samePid) appHostSink.clear()
        sinks.values.forEach { it.clear() }
        appHostSink.system("Connected to AppHost ${host.appHostPath} (pid ${host.appHostPid})")
        host.dashboardUrl?.let { appHostSink.system("Dashboard: $it") }

        val cli = cli()
        val describe = cli.stream(scope, "describe", *appHostArgs(), "--format", "Json", "--follow",
            onExit = { streamEnded("describe", it) }) { line ->
            val json = AspireJson.parseOrNull(line) ?: return@stream
            val updated = AspireJson.parseResources(json)
            if (updated.isEmpty()) return@stream
            var changed = false
            synchronized(resourcesLock) {
                for (r in updated) {
                    val prev = resources.put(r.name, r)
                    // describe --follow also emits for changes the tree doesn't show (env, properties).
                    if (prev != r) changed = true
                    if (prev?.state != r.state) LOG.info("Resource ${r.displayName}: ${prev?.state ?: "new"} -> ${r.state}")
                }
            }
            if (changed) fireChanged()
        }
        val logs = cli.stream(scope, "logs", *appHostArgs(), "--format", "Json", "--follow", "--timestamps",
            onExit = { streamEnded("logs", it) }) { line ->
            val json = AspireJson.parseOrNull(line) ?: return@stream
            val parsed = AspireJson.parseLogs(json)
            logLines += parsed.size
            if (logLines in 1..parsed.size || logLines % 500 < parsed.size) LOG.info("Log lines received: $logLines")
            for (log in parsed) {
                val display = displayNameFor(log.resourceName)
                val type = if (log.isError) ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT
                val ts = formatTimestamp(log.timestamp)
                sinkFor(display).apply {
                    if (ts != null) print("$ts ", ProcessOutputTypes.SYSTEM)
                    print(log.content + "\n", type)
                }
                appHostSink.apply {
                    print("${ts?.let { "$it " } ?: ""}[$display] ", ProcessOutputTypes.SYSTEM)
                    print(log.content + "\n", type)
                }
            }
        }
        connectionJobs = listOf(describe, logs)
    }

    /**
     * A `--follow` stream ended by itself: either the AppHost stopped or the CLI failed.
     * Re-check, which reconnects if the AppHost is still up. At most one reconnect per 5 seconds,
     * so a stream that keeps failing doesn't spin.
     */
    private fun streamEnded(name: String, exitCode: Int?) {
        LOG.info("aspire $name --follow ended (exit $exitCode)")
        val wait = maxOf(1_000L, RECONNECT_INTERVAL_MS - (System.currentTimeMillis() - lastConnectAt))
        scope.launch {
            delay(wait)
            checkRunningOnce()
        }
    }

    private fun disconnect() {
        connectionJobs.forEach { it.cancel(null) }
        connectionJobs = emptyList()
        connectedPid = null
        synchronized(resourcesLock) { resources.clear() }
    }

    private fun displayNameFor(logName: String): String = synchronized(resourcesLock) {
        resources[logName]?.displayName ?: logName
    }

    fun sinkFor(displayName: String): LogSink = sinks.computeIfAbsent(displayName) { LogSink(project, this) }

    fun resources(): List<ResourceInfo> = synchronized(resourcesLock) { resources.values.toList() }

    fun resource(name: String): ResourceInfo? = synchronized(resourcesLock) { resources[name] }

    // endregion

    // region commands

    fun startAppHost() {
        val cfg = config ?: return
        synchronized(this) {
            if (phase == AppHostPhase.STARTING || phase == AppHostPhase.RUNNING) return
            phase = AppHostPhase.STARTING
        }
        fireChanged()
        appHostSink.clear()
        appHostSink.system("> aspire start --apphost ${cfg.appHostPath}")
        scope.launch {
            try {
                val result = cli().run("start", *appHostArgs()) { line, isErr ->
                    appHostSink.print(line + "\n", if (isErr) ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT)
                }
                // The watcher may already have seen the AppHost come up; otherwise the check below will.
                synchronized(this@AspireAppHostService) {
                    phase = if (result.ok && appHost != null) AppHostPhase.RUNNING else AppHostPhase.STOPPED
                }
                if (!result.ok) {
                    appHostSink.system("aspire start exited with code ${result.exitCode}")
                    notifyError("Failed to start AppHost", result.message.lines().takeLast(5).joinToString("\n"))
                }
                checkRunningOnce()
            } catch (e: Exception) {
                synchronized(this@AspireAppHostService) { phase = AppHostPhase.STOPPED }
                handleFailure("Failed to start AppHost", e)
            } finally {
                fireChanged()
            }
        }
    }

    fun stopAppHost() {
        if (config == null) return
        synchronized(this) {
            if (phase != AppHostPhase.RUNNING && phase != AppHostPhase.STARTING) return
            phase = AppHostPhase.STOPPING
        }
        fireChanged()
        appHostSink.system("> aspire stop")
        scope.launch {
            try {
                val result = cli().run("stop", *appHostArgs())
                if (!result.ok) notifyError("Failed to stop AppHost", result.message)
            } catch (e: Exception) {
                handleFailure("Failed to stop AppHost", e)
            } finally {
                synchronized(this@AspireAppHostService) { phase = AppHostPhase.STOPPED }
                checkRunningOnce()
                fireChanged()
            }
        }
    }

    fun restartAppHost() {
        synchronized(this) {
            if (phase != AppHostPhase.RUNNING) return
            phase = AppHostPhase.STOPPING
        }
        fireChanged()
        appHostSink.system("> aspire stop")
        scope.launch {
            val stopped = try {
                val result = cli().run("stop", *appHostArgs())
                if (!result.ok) notifyError("Failed to stop AppHost for restart", result.message)
                result.ok
            } catch (e: Exception) {
                handleFailure("Failed to stop AppHost for restart", e)
                false
            }
            synchronized(this@AspireAppHostService) { phase = AppHostPhase.STOPPED }
            if (stopped) {
                onAppHostGone()
                startAppHost()
            } else {
                checkRunningOnce()
                fireChanged()
            }
        }
    }

    /** Regenerates the TypeScript SDK (`.aspire/modules`) after integrations change. */
    fun restore() {
        appHostSink.system("> aspire restore")
        scope.launch {
            try {
                val result = cli().run("restore", *appHostArgs()) { line, isErr ->
                    appHostSink.print(line + "\n", if (isErr) ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT)
                }
                if (!result.ok) notifyError("aspire restore failed", result.message.lines().takeLast(5).joinToString("\n"))
                else config?.appHostPath?.parent?.let { refreshVfs(it) }
            } catch (e: Exception) {
                handleFailure("aspire restore failed", e)
            }
        }
    }

    fun executeResourceCommand(resource: ResourceInfo, command: String) {
        val sink = sinkFor(resource.displayName)
        sink.system("> aspire resource ${resource.name} $command")
        scope.launch {
            try {
                val result = cli().run("resource", resource.name, command, *appHostArgs())
                if (!result.ok) {
                    sink.system(result.message)
                    notifyError("Failed to $command ${resource.displayName}", result.message.lines().firstOrNull { it.contains("❌") } ?: result.message)
                }
            } catch (e: Exception) {
                handleFailure("Failed to $command ${resource.displayName}", e)
            }
        }
    }

    // endregion

    private fun refreshVfs(dir: Path) {
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshNioFiles(listOf(dir), true, true, null)
    }

    private fun handleFailure(title: String, e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        LOG.warn(title, e)
        appHostSink.system("$title: ${e.message}")
        notifyError(title, e.message ?: e.toString())
    }

    private fun notifyError(title: String, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Aspire")
            .createNotification(title, content, NotificationType.ERROR)
            .notify(project)
    }

    fun fireChanged() {
        changes.trySend(Unit)
    }

    override fun dispose() {
        psJob?.cancel(null)
        disconnect()
        changes.close(null)
    }

    companion object {
        private val LOG = logger<AspireAppHostService>()
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private const val RECONNECT_INTERVAL_MS = 5_000L

        fun getInstance(project: Project): AspireAppHostService = project.service()

        private fun formatTimestamp(ts: String?): String? = ts?.let {
            runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).format(TIME) }.getOrNull()
        }

        private fun String.substringFrom(c: Char): String = indexOf(c).let { if (it < 0) this else substring(it) }
    }
}
