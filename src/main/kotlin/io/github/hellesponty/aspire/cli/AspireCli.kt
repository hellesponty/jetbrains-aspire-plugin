package io.github.hellesponty.aspire.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import io.github.hellesponty.aspire.settings.AspireSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class AspireCliNotFoundException : Exception(
    "Aspire CLI not found. Install it (https://aspire.dev) or set its path in Settings | Tools | Aspire."
)

data class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
    /** The CLI reports failures on stdout with a ❌ prefix, so show both streams. */
    val message: String get() = (stdout + "\n" + stderr).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}

/** Thin wrapper over the `aspire` CLI, which does the actual work for every language of AppHost. */
class AspireCli(private val workDir: Path) {

    fun executable(): String {
        AspireSettings.getInstance().state.cliPath?.takeIf { it.isNotBlank() }?.let { return it }
        val exe = if (SystemInfo.isWindows) "aspire.exe" else "aspire"
        // Use the login-shell PATH: IDEs started from a desktop launcher often lack ~/.aspire/bin.
        val path = EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH") ?: ""
        path.split(File.pathSeparatorChar).filter { it.isNotBlank() }
            .map { File(it, exe) }.firstOrNull { it.canExecute() }
            ?.let { return it.absolutePath }
        val home = File(System.getProperty("user.home"), ".aspire/bin/$exe")
        if (home.canExecute()) return home.absolutePath
        throw AspireCliNotFoundException()
    }

    fun commandLine(vararg args: String): GeneralCommandLine =
        GeneralCommandLine(executable())
            .withParameters(*args)
            .withParameters("--nologo", "--non-interactive")
            .withWorkDirectory(workDir.toFile())
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

    /** Runs a short-lived command and collects its output. Cancelling the caller kills the process. */
    suspend fun run(vararg args: String, onLine: ((String, Boolean) -> Unit)? = null): CliResult =
        withContext(Dispatchers.IO) {
            val process = commandLine(*args).createProcess()
            killOnCancel(process) {
                val err = StringBuilder()
                val errThread = Thread {
                    process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine {
                        synchronized(err) { err.appendLine(it) }
                        onLine?.invoke(it, true)
                    }
                }.apply { isDaemon = true; start() }
                val out = StringBuilder()
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine {
                    out.appendLine(it)
                    onLine?.invoke(it, false)
                }
                val code = process.waitFor()
                errThread.join(2000)
                CliResult(code, out.toString(), synchronized(err) { err.toString() })
            }
        }

    /**
     * Starts a long-running command (`--follow`) and feeds each stdout line to [onLine].
     * Cancelling the returned job kills the process. [onExit] runs only when the process
     * ends on its own (not when the job is cancelled), with its exit code or null if it failed to run.
     */
    fun stream(scope: CoroutineScope, vararg args: String, onExit: (Int?) -> Unit = {}, onLine: (String) -> Unit): Job =
        scope.launch(Dispatchers.IO) {
            var exit: Int? = null
            try {
                val process = commandLine(*args).createProcess()
                killOnCancel(process) {
                    Thread { process.errorStream.bufferedReader().forEachLine { LOG.debug("aspire ${args.firstOrNull()}: $it") } }
                        .apply { isDaemon = true; start() }
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            if (!isActive) break
                            try {
                                onLine(line)
                            } catch (e: Exception) {
                                LOG.warn("Failed to handle line from aspire ${args.joinToString(" ")}: $line", e)
                            }
                        }
                    }
                    exit = process.waitFor()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                LOG.warn("aspire ${args.joinToString(" ")} failed", e)
            } finally {
                if (isActive) onExit(exit)
            }
        }

    /**
     * Runs the blocking [block] while a sibling coroutine waits for cancellation and kills [process].
     * Blocking stream reads don't react to coroutine cancellation, and `Job.invokeOnCompletion` only
     * fires after the job *completes*, which would never happen while a read is blocked.
     */
    private suspend fun <T> killOnCancel(process: Process, block: suspend CoroutineScope.() -> T): T = coroutineScope {
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                destroyTree(process)
            }
        }
        try {
            block()
        } finally {
            // destroyTree is a no-op once the process has exited, so processes `aspire start` leaves running survive.
            watcher.cancel(null)
        }
    }

    companion object {
        private val LOG = logger<AspireCli>()

        fun destroyTree(process: Process) {
            if (!process.isAlive) return
            process.toHandle().descendants().forEach { it.destroy() }
            process.destroy()
        }
    }
}
