package io.github.hellesponty.aspire.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import io.github.hellesponty.aspire.cli.AspireConfig

/** Looks for `aspire.config.json` once the project is open. */
class AspireStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        AspireAppHostService.getInstance(project).reload()
    }
}

/** Picks up `aspire.config.json` being created, edited or deleted. */
class AspireConfigListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val base = project.basePath ?: return
        val relevant = events.any { e ->
            e.path.endsWith("/" + AspireConfig.FILE_NAME) && e.path.substringBeforeLast('/') == base
        }
        if (relevant) AspireAppHostService.getInstance(project).reload()
    }
}
