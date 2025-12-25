package org.jetbrains.plugins.template.gpu

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Minimal remote filesystem chooser implemented over SSH.
 *
 * This avoids using local FileChooser (which shows macOS files on the frontend)
 * and works in Gateway when backend plugin installation isn't possible.
 */
class RemotePathChooserDialog(
    private val project: Project,
    private val sshParamsProvider: () -> SshParams,
    private val mode: Mode,
    private val initialDirProvider: () -> String,
    private val onDebug: ((String) -> Unit)? = null,
) : DialogWrapper(project) {

    enum class Mode { Directory, PtFile }

    private val pathField = JBTextField(48)
    private val listModel = DefaultListModel<String>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private val status = JBLabel("").apply { isEnabled = false }

    @Volatile private var currentDir: String = "/"

    var selectedPath: String? = null
        private set

    init {
        title = when (mode) {
            Mode.Directory -> "Select Remote Folder"
            Mode.PtFile -> "Select Remote .pt File"
        }
        setOKButtonText("Select")
        init()

        currentDir = normalizeDir(initialDirProvider())
        pathField.text = currentDir
        refresh()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(8, 8))
        root.preferredSize = Dimension(640, 420)

        val top = JPanel(BorderLayout(8, 8))
        top.add(JBLabel("Remote path:"), BorderLayout.WEST)
        top.add(pathField, BorderLayout.CENTER)
        root.add(top, BorderLayout.NORTH)

        val scroll = JBScrollPane(list)
        root.add(scroll, BorderLayout.CENTER)
        root.add(status, BorderLayout.SOUTH)

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    val sel = list.selectedValue ?: return
                    if (sel.endsWith("/")) {
                        cd(sel.removeSuffix("/"))
                    } else if (mode == Mode.PtFile) {
                        selectedPath = join(currentDir, sel)
                        close(OK_EXIT_CODE)
                    }
                }
            }
        })
        pathField.addActionListener {
            cd(pathField.text.trim())
        }

        return root
    }

    override fun doOKAction() {
        val sel = list.selectedValue
        selectedPath = when (mode) {
            Mode.Directory -> {
                if (sel != null && sel.endsWith("/")) join(currentDir, sel.removeSuffix("/")) else currentDir
            }
            Mode.PtFile -> {
                if (sel != null && !sel.endsWith("/")) join(currentDir, sel) else null
            }
        }
        if (selectedPath == null) return
        super.doOKAction()
    }

    private fun cd(dir: String) {
        val next = if (dir.startsWith("/")) dir else join(currentDir, dir)
        currentDir = normalizeDir(next)
        pathField.text = currentDir
        refresh()
    }

    private fun refresh() {
        status.text = "Loading…"
        listModel.clear()
        val p = sshParamsProvider()
        val exec = SshExec(p, project = null, onDebug = onDebug)

        val dirQ = shQuote(currentDir)
        val cmd = when (mode) {
            Mode.Directory ->
                "cd $dirQ 2>/dev/null || exit 2; ls -1p --color=never 2>/dev/null; printf \"__PWD__=%s\\n\" \"\$PWD\""
            Mode.PtFile ->
                "cd $dirQ 2>/dev/null || exit 2; ls -1p --color=never 2>/dev/null; printf \"__PWD__=%s\\n\" \"\$PWD\""
        }

        Thread {
            val (rc, out, err) = exec.run(cmd)
            val lines = out.lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() }
            val pwdLine = lines.lastOrNull { it.startsWith("__PWD__=") }
            val pwd = pwdLine?.removePrefix("__PWD__=")?.trim()
            val entries = lines.filterNot { it.startsWith("__PWD__=") }
            val filtered = when (mode) {
                Mode.Directory -> entries.filter { it.endsWith("/") }
                Mode.PtFile -> {
                    val dirs = entries.filter { it.endsWith("/") }
                    val pts = entries.filter { !it.endsWith("/") && it.endsWith(".pt") }
                    dirs + pts
                }
            }
            SwingUtilities.invokeLater {
                if (rc != 0) {
                    status.text = (err.ifBlank { out }).trim().ifBlank { "Failed to list remote dir (rc=$rc)" }
                    return@invokeLater
                }
                if (!pwd.isNullOrBlank()) {
                    currentDir = normalizeDir(pwd)
                    pathField.text = currentDir
                }
                for (e in filtered.sortedWith(compareBy<String> { !it.endsWith("/") }.thenBy { it.lowercase() })) {
                    listModel.addElement(e)
                }
                status.text = "Loaded ${listModel.size} item(s)"
            }
        }.start()
    }

    private fun normalizeDir(dir: String): String {
        val d = dir.trim().ifEmpty { "/" }
        return if (d == "/") d else d.trimEnd('/')
    }

    private fun join(base: String, child: String): String {
        val b = base.trimEnd('/')
        val c = child.trimStart('/')
        return if (b.isEmpty() || b == "/") "/$c" else "$b/$c"
    }
}
