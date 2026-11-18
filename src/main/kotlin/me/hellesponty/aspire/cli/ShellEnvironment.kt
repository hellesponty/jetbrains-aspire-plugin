package me.hellesponty.aspire.cli

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Starting an IDE from a desktop launcher (e.g. Toolbox on Linux) can inherit a bare system PATH, without
 * any shell config loaded. TypeScript AppHosts required npm at least. This is mainly compat functionality, since
 * the problem does not appear on MacOS ([EnvironmentUtil]); on Linux the problem is fixed by calling for
 * shell config once: `$SHELL -l -i -c env`.
 */
object ShellEnvironment {
    private val LOG = logger<ShellEnvironment>()
    private const val TIMEOUT_SECONDS = 20L
    private const val OUTPUT_VAR = "ASPIRE_PLUGIN_ENV_FILE"
    /** Shell bookkeeping that shouldn't leak into child processes. */
    private val IGNORED = setOf("_", "PWD", "OLDPWD", "SHLVL", OUTPUT_VAR)

    @Volatile
    private var cached: Map<String, String>? = null

    fun get(): Map<String, String> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val base = EnvironmentUtil.getEnvironmentMap()
            val env = if (SystemInfo.isLinux) base + (readShellEnvironment() ?: emptyMap()) else base
            cached = env
            return env
        }
    }

    fun path(): String = get()["PATH"] ?: System.getenv("PATH") ?: ""

    private fun readShellEnvironment(): Map<String, String>? {
        val shell = System.getenv("SHELL")?.takeIf { File(it).canExecute() } ?: return null
        val output = Files.createTempFile("aspire-shell-env", ".txt")
        try {
            // Write to a file rather than stdout: rc files may print banners or prompts.
            val builder = ProcessBuilder(shell, "-l", "-i", "-c", "env -0 > \"\$$OUTPUT_VAR\"")
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment()[OUTPUT_VAR] = output.toString()
            val process = builder.start()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                LOG.warn("Reading the environment of $shell timed out; using the IDE's environment")
                return null
            }
            val env = Files.readString(output, StandardCharsets.UTF_8).split('\u0000')
                .mapNotNull { entry ->
                    val eq = entry.indexOf('=')
                    if (eq <= 0) null else entry.substring(0, eq) to entry.substring(eq + 1)
                }
                .filter { (key, _) -> key !in IGNORED }
                .toMap()
            if (env.isEmpty()) {
                LOG.warn("$shell returned no environment (exit ${process.exitValue()}); using the IDE's environment")
                return null
            }
            LOG.info("Loaded the environment of $shell; PATH=${env["PATH"]}")
            return env
        } catch (e: Exception) {
            LOG.warn("Could not read the environment of $shell; using the IDE's environment", e)
            return null
        } finally {
            Files.deleteIfExists(output)
        }
    }
}
