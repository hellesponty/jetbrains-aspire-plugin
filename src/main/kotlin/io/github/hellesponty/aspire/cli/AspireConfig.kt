package io.github.hellesponty.aspire.cli

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The AppHost declared by `aspire.config.json` at the repository root:
 *
 * ```json
 * { "appHost": { "path": "aspire-apphost/apphost.mts", "language": "typescript/nodejs" } }
 * ```
 */
data class AspireConfig(
    val configFile: Path,
    val repoRoot: Path,
    val appHostPath: Path,
    val language: String?,
) {
    val isTypeScript: Boolean get() = language?.startsWith("typescript", ignoreCase = true) == true
    val displayName: String get() = appHostPath.parent?.fileName?.toString() ?: appHostPath.fileName.toString()

    companion object {
        const val FILE_NAME = "aspire.config.json"
        private val LOG = logger<AspireConfig>()

        fun find(project: Project): AspireConfig? {
            val roots = listOfNotNull(project.basePath, project.guessProjectDir()?.path).distinct()
            for (root in roots) {
                val file = Paths.get(root, FILE_NAME)
                if (Files.isRegularFile(file)) return parse(file)
            }
            return null
        }

        fun parse(file: Path): AspireConfig? = try {
            val json = JsonParser.parseString(Files.readString(file)).asJsonObject
            val appHost = json.getAsJsonObject("appHost")
            val rel = appHost?.get("path")?.asString
            val root = file.parent
            AspireConfig(
                configFile = file,
                repoRoot = root,
                // Without an explicit path the CLI searches the repo root itself.
                appHostPath = if (rel != null) root.resolve(rel).normalize() else root,
                language = appHost?.get("language")?.asString,
            )
        } catch (e: Exception) {
            LOG.warn("Could not read $file", e)
            null
        }
    }
}
