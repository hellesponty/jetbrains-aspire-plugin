package me.hellesponty.aspire.services

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Collects log output for one resource (or the whole AppHost) and shows it in a console.
 * Output that arrives before anyone opens the console is buffered and replayed.
 */
class LogSink(private val project: Project, private val parent: Disposable) {
    private val lock = Any()
    private var console: ConsoleView? = null
    private var panel: JComponent? = null
    private val pending = ArrayDeque<Pair<String, Key<*>>>()
    private val decoder = AnsiEscapeDecoder()

    /** Prints [text] (which should end in a newline). ANSI colors from the process are kept. */
    fun print(text: String, type: Key<*> = ProcessOutputTypes.STDOUT) {
        synchronized(lock) {
            val c = console
            if (c == null) {
                pending.addLast(text to type)
                while (pending.size > MAX_PENDING) pending.removeFirst()
            } else {
                printTo(c, text, type)
            }
        }
    }

    fun system(text: String) = print(text.ensureNewline(), ProcessOutputTypes.SYSTEM)

    fun clear() {
        synchronized(lock) {
            pending.clear()
            console?.clear()
        }
    }

    /** Must be called on the EDT. */
    fun component(): JComponent {
        panel?.let { return it }
        val c = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
        Disposer.register(parent, c)
        val consoleComponent = c.component
        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.SERVICES_TOOLBAR, DefaultActionGroup(*c.createConsoleActions()), false)
        toolbar.targetComponent = consoleComponent
        val p = JPanel(BorderLayout()).apply {
            add(consoleComponent, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.WEST)
        }
        synchronized(lock) {
            console = c
            while (pending.isNotEmpty()) {
                val (text, type) = pending.removeFirst()
                printTo(c, text, type)
            }
        }
        panel = p
        return p
    }

    private fun printTo(c: ConsoleView, text: String, type: Key<*>) {
        if (type == ProcessOutputTypes.SYSTEM) {
            c.print(text, ConsoleViewContentType.SYSTEM_OUTPUT)
            return
        }
        decoder.escapeText(text, type) { chunk, attrs -> c.print(chunk, ConsoleViewContentType.getConsoleViewType(attrs)) }
    }

    companion object {
        private const val MAX_PENDING = 20_000
        private fun String.ensureNewline() = if (endsWith("\n")) this else this + "\n"
    }
}
