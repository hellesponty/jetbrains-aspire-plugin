package io.github.hellesponty.aspire.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil

class AspireSettingsState : BaseState() {
    var cliPath by string("")
}

@Service(Service.Level.APP)
@State(name = "AspireAppHostSettings", storages = [Storage("aspire-apphost.xml")])
class AspireSettings : SimplePersistentStateComponent<AspireSettingsState>(AspireSettingsState()) {
    companion object {
        fun getInstance(): AspireSettings = service()
    }
}

/*
 * Built only from APIs that are stable from 2024.3 on. Kotlin compiles calls that omit default
 * arguments of *interface* members (e.g. UI DSL `Row.comment(...)`, `Job.cancel()`) into calls to
 * synthetic `$default` methods whose presence differs between IDE versions.
 */
class AspireSettingsConfigurable : BoundConfigurable("Aspire") {
    override fun createPanel(): DialogPanel {
        val state = AspireSettings.getInstance().state
        val field = TextFieldWithBrowseButton()
        field.addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            val current = LocalFileSystem.getInstance().findFileByPath(field.text)
            FileChooser.chooseFile(descriptor, null, current) { file -> field.text = file.path }
        }
        val hint = JBLabel("Leave empty to use aspire from PATH or ~/.aspire/bin/aspire.").apply {
            componentStyle = UIUtil.ComponentStyle.SMALL
            fontColor = UIUtil.FontColor.BRIGHTER
        }
        return panel {
            row("Aspire CLI:") {
                cell(field)
                    .bindText({ state.cliPath ?: "" }, { state.cliPath = it.trim() })
                    .align(AlignX.FILL)
            }
            row("") { cell(hint) }
        }
    }
}
