package io.github.hellesponty.aspire.services

import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.ServiceViewDescriptor
import com.intellij.execution.services.SimpleServiceViewDescriptor
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.pom.Navigatable
import com.intellij.ui.SimpleTextAttributes
import io.github.hellesponty.aspire.AspireIcons
import io.github.hellesponty.aspire.actions.AppHostActions
import io.github.hellesponty.aspire.actions.ResourceActions
import io.github.hellesponty.aspire.cli.ResourceInfo
import javax.swing.Icon
import javax.swing.JComponent

/** Root of the "Aspire" node in the Services tool window. */
class AspireServiceViewContributor : ServiceViewContributor<AppHostNode> {
    override fun getViewDescriptor(project: Project): ServiceViewDescriptor =
        SimpleServiceViewDescriptor("Aspire", AspireIcons.Aspire)

    override fun getServices(project: Project): List<AppHostNode> {
        val config = AspireAppHostService.getInstance(project).config ?: return emptyList()
        return listOf(AppHostNode(config.appHostPath.toString()))
    }

    override fun getServiceDescriptor(project: Project, service: AppHostNode): ServiceViewDescriptor =
        AppHostDescriptor(project)
}

/** The AppHost; its children are the top-level resources. */
data class AppHostNode(val path: String) : ServiceViewContributor<ResourceNode> {
    override fun getViewDescriptor(project: Project): ServiceViewDescriptor = AppHostDescriptor(project)

    override fun getServices(project: Project): List<ResourceNode> {
        val all = AspireAppHostService.getInstance(project).resources()
        return childrenOf(all, parent = null)
    }

    override fun getServiceDescriptor(project: Project, service: ResourceNode): ServiceViewDescriptor =
        ResourceDescriptor(project, service.name)
}

/** A resource. Resources with children (e.g. a Postgres server and its databases) are themselves contributors. */
sealed interface ResourceNode {
    val name: String
}

data class LeafResourceNode(override val name: String) : ResourceNode

data class ParentResourceNode(override val name: String) : ResourceNode, ServiceViewContributor<ResourceNode> {
    override fun getViewDescriptor(project: Project): ServiceViewDescriptor = ResourceDescriptor(project, name)

    override fun getServices(project: Project): List<ResourceNode> =
        childrenOf(AspireAppHostService.getInstance(project).resources(), parent = name)

    override fun getServiceDescriptor(project: Project, service: ResourceNode): ServiceViewDescriptor =
        ResourceDescriptor(project, service.name)
}

private fun childrenOf(all: List<ResourceInfo>, parent: String?): List<ResourceNode> {
    val names = all.mapTo(HashSet()) { it.name }
    val parentsWithChildren = all.mapNotNullTo(HashSet()) { r -> r.parentName?.takeIf { it in names } }
    return all
        .filter { r -> if (parent == null) r.parentName == null || r.parentName !in names else r.parentName == parent }
        .sortedBy { it.displayName.lowercase() }
        .map { if (it.name in parentsWithChildren) ParentResourceNode(it.name) else LeafResourceNode(it.name) }
}

private class AppHostDescriptor(private val project: Project) : ServiceViewDescriptor {
    private val service get() = AspireAppHostService.getInstance(project)

    override fun getId(): String = "aspire.apphost"

    override fun getPresentation(): ItemPresentation {
        val config = service.config
        val name = config?.displayName ?: "AppHost"
        val (status, icon) = when (service.phase) {
            AppHostPhase.RUNNING -> "Running" to AspireIcons.withStatus(AspireIcons.Aspire, AllIcons.RunConfigurations.TestPassed)
            AppHostPhase.STARTING -> "Starting…" to AspireIcons.Aspire
            AppHostPhase.STOPPING -> "Stopping…" to AspireIcons.Aspire
            AppHostPhase.STOPPED -> "Not running" to AspireIcons.Aspire
        }
        val lang = when {
            config == null -> null
            config.isTypeScript -> "TypeScript"
            else -> config.language
        }
        return PresentationData(name, null, icon, null).apply {
            addText(name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            addText("  " + listOfNotNull(status, lang).joinToString(" · "), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    override fun getContentComponent(): JComponent = service.appHostSink.component()

    override fun getToolbarActions(): ActionGroup = AppHostActions.toolbar(project)

    override fun getPopupActions(): ActionGroup = AppHostActions.toolbar(project)

    override fun getNavigatable(): Navigatable? {
        val path = service.config?.appHostPath ?: return null
        val file = LocalFileSystem.getInstance().findFileByNioFile(path)?.takeIf { !it.isDirectory } ?: return null
        return OpenFileDescriptor(project, file)
    }
}

private class ResourceDescriptor(private val project: Project, private val name: String) : ServiceViewDescriptor {
    private val service get() = AspireAppHostService.getInstance(project)
    private val resource get() = service.resource(name)

    override fun getId(): String = "aspire.resource.$name"

    override fun getPresentation(): ItemPresentation {
        val r = resource ?: return PresentationData(name, null, AllIcons.RunConfigurations.TestNotRan, null)
        val status = statusText(r)
        val url = r.urls.firstOrNull { it.url.startsWith("http") }?.url
        return PresentationData(r.displayName, null, statusIcon(r), null).apply {
            addText(r.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            addText("  $status", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (url != null) addText("  $url", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            tooltip = "${r.resourceType}${r.source?.let { " · $it" } ?: ""}"
        }
    }

    override fun getContentComponent(): JComponent {
        val display = resource?.displayName ?: name
        return service.sinkFor(display).component()
    }

    override fun getToolbarActions(): ActionGroup = ResourceActions.toolbar(project, name)

    override fun getPopupActions(): ActionGroup = ResourceActions.toolbar(project, name)

    override fun getNavigatable(): Navigatable? {
        val dir = resource?.workDir ?: return null
        val file = LocalFileSystem.getInstance().findFileByPath(dir) ?: return null
        return object : Navigatable {
            override fun navigate(requestFocus: Boolean) {
                com.intellij.ide.projectView.ProjectView.getInstance(project).select(null, file, requestFocus)
            }
            override fun canNavigate() = true
            override fun canNavigateToSource() = false
        }
    }

    private fun statusText(r: ResourceInfo): String {
        val state = r.state ?: "Unknown"
        return when {
            state == "Running" && r.healthStatus != null && r.healthStatus != "Healthy" -> "Running (${r.healthStatus})"
            (state == "Exited" || state == "Finished") && r.exitCode != null -> "$state (code ${r.exitCode})"
            else -> state.splitCamel()
        }
    }

    private fun statusIcon(r: ResourceInfo): Icon = when (r.state) {
        "Running" -> when (r.healthStatus) {
            null, "Healthy" -> AllIcons.RunConfigurations.TestPassed
            else -> AllIcons.General.Warning
        }
        "FailedToStart", "RuntimeUnhealthy" -> AllIcons.RunConfigurations.TestError
        "Exited", "Finished" ->
            if (r.exitCode != null && r.exitCode != 0) AllIcons.RunConfigurations.TestFailed
            else AllIcons.RunConfigurations.TestIgnored
        else -> AllIcons.RunConfigurations.TestNotRan
    }

    private fun String.splitCamel(): String = replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase().replaceFirstChar { it.uppercase() }
}
