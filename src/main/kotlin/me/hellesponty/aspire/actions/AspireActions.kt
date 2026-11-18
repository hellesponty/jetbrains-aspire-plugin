package me.hellesponty.aspire.actions

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import me.hellesponty.aspire.cli.DashboardLinks
import me.hellesponty.aspire.cli.ResourceInfo
import me.hellesponty.aspire.services.AppHostPhase
import me.hellesponty.aspire.services.AspireAppHostService
import javax.swing.Icon

private abstract class BgtAction(text: String, description: String?, icon: Icon?) : DumbAwareAction(text, description, icon) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

private fun simple(text: String, icon: Icon?, enabled: () -> Boolean = { true }, perform: () -> Unit): AnAction =
    object : BgtAction(text, null, icon) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }
        override fun actionPerformed(e: AnActionEvent) = perform()
    }

object AppHostActions {
    fun toolbar(project: Project): ActionGroup {
        val s = AspireAppHostService.getInstance(project)
        return s.actionGroups.computeIfAbsent("apphost") { create(project, s) }
    }

    private fun create(project: Project, s: AspireAppHostService): ActionGroup {
        return DefaultActionGroup(
            simple("Start AppHost", AllIcons.Actions.Execute, { s.config != null && s.phase == AppHostPhase.STOPPED }) { s.startAppHost() },
            simple("Stop AppHost", AllIcons.Actions.Suspend, { s.phase == AppHostPhase.RUNNING || s.phase == AppHostPhase.STARTING }) { s.stopAppHost() },
            simple("Restart AppHost", AllIcons.Actions.Restart, { s.phase == AppHostPhase.RUNNING }) { s.restartAppHost() },
            Separator.create(),
            simple("Open Dashboard", AllIcons.General.Web, { s.appHost?.dashboardUrl != null }) {
                s.appHost?.dashboardUrl?.let(BrowserUtil::browse)
            },
            object : BgtAction("Restore TypeScript SDK", "Run `aspire restore` to regenerate .aspire/modules", AllIcons.Actions.Download) {
                override fun update(e: AnActionEvent) {
                    e.presentation.isVisible = s.config?.isTypeScript == true
                    e.presentation.isEnabled = s.phase != AppHostPhase.STARTING
                }
                override fun actionPerformed(e: AnActionEvent) = s.restore()
            },
            simple("Open aspire.config.json", AllIcons.General.Settings, { s.config != null }) {
                val file = s.config?.configFile?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }
                if (file != null) OpenFileDescriptor(project, file).navigate(true)
            },
            simple("Refresh", AllIcons.Actions.Refresh) { s.reload() },
        )
    }
}

object ResourceActions {
    private val BUILT_IN = setOf("start", "stop", "restart")

    fun toolbar(project: Project, name: String): ActionGroup {
        val s = AspireAppHostService.getInstance(project)
        return s.actionGroups.computeIfAbsent("resource:$name") { create(s, name) }
    }

    private fun create(s: AspireAppHostService, name: String): ActionGroup {
        fun r(): ResourceInfo? = s.resource(name)
        fun command(id: String, text: String, icon: Icon) =
            object : BgtAction(text, null, icon) {
                override fun update(e: AnActionEvent) {
                    val c = r()?.command(id)
                    // Aspire only lists start for stopped resources and stop for running ones.
                    e.presentation.isEnabled = c?.enabled == true
                    c?.description?.let { e.presentation.description = it }
                }
                override fun actionPerformed(e: AnActionEvent) {
                    r()?.let { s.executeResourceCommand(it, id) }
                }
            }

        return DefaultActionGroup(
            command("start", "Start", AllIcons.Actions.Execute),
            command("stop", "Stop", AllIcons.Actions.Suspend),
            command("restart", "Restart", AllIcons.Actions.Restart),
            Separator.create(),
            UrlsGroup { r() },
            simple("Open in Dashboard", AllIcons.Actions.OpenNewTab, { r()?.dashboardUrl != null }) {
                r()?.dashboardUrl?.let { BrowserUtil.browse(DashboardLinks.withLogin(it, s.appHost?.dashboardUrl)) }
            },
            CustomCommandsGroup({ r() }) { res, cmd -> s.executeResourceCommand(res, cmd) },
        )
    }

    /** Endpoints of the resource; opens directly when there is a single http(s) URL. */
    private class UrlsGroup(private val resource: () -> ResourceInfo?) :
        ActionGroup("Open URL", "Open an endpoint of this resource", AllIcons.General.Web) {
        init { isPopup = true }
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = resource()?.urls?.isNotEmpty() == true
        }
        override fun getChildren(e: AnActionEvent?): Array<AnAction> =
            resource()?.urls.orEmpty().map { u ->
                simple("${u.name}: ${u.url}", null) { BrowserUtil.browse(u.url) }
            }.toTypedArray()
    }

    /** Commands a resource defines beyond start/stop/restart (e.g. via `withCommand`). */
    private class CustomCommandsGroup(
        private val resource: () -> ResourceInfo?,
        private val run: (ResourceInfo, String) -> Unit,
    ) : ActionGroup("More Commands", "Commands defined by this resource", AllIcons.Actions.More) {
        init { isPopup = true }
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        private fun custom() = resource()?.commands.orEmpty().filter { it.name.lowercase() !in BUILT_IN }
        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = custom().isNotEmpty()
        }
        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val res = resource() ?: return emptyArray()
            return custom().map { c ->
                simple(c.displayName, null, { c.enabled }) { run(res, c.name) }
            }.toTypedArray()
        }
    }
}
