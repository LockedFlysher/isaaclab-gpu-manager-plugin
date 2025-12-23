package org.jetbrains.plugins.template.gpu

import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener

class GpuManagerPanel : JBPanel<GpuManagerPanel>(BorderLayout()) {

    private val hostField = JBTextField(24)
    private val portSpin = JSpinner(SpinnerNumberModel(22, 1, 65535, 1))
    private val userField = JBTextField(18)
    private val keyField = JBTextField(32)
    private val browseKeyBtn = JButton("Browse…")
    private val passField = JPasswordField(28)
    private val usePasswordCb = JCheckBox("Password")
    private val rememberPwCb = JCheckBox("Remember")
    private val intervalSpin = JSpinner(SpinnerNumberModel(5.0, 1.0, 120.0, 1.0))
    private val nvidiaSmiPathField = JBTextField(32)
    private val settingsPanel = JPanel(GridBagLayout())
    private val testBtn = JButton("Test")
    private val selfTestBtn = JButton("Self-Test")
    private val connectBtn = JButton("Connect")
    private val disconnectBtn = JButton("Disconnect").apply { isEnabled = false; isVisible = false }
    private val debugToggleBtn = JButton("Debug")
    @Volatile private var debugEnabled: Boolean = true
    @Volatile private var settingsCollapsed: Boolean = false
    private val debugArea = javax.swing.JTextArea(8, 80).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val debugPane = JBScrollPane(debugArea)

    private val osLabel = JBLabel("")
    private val statusLabel = JBLabel("")
    private val connSummaryLabel = JBLabel("Disconnected").apply { foreground = JBColor.GRAY }
    private val settingsToggleBtn = JButton("Settings").apply { toolTipText = "Hide settings" }

    // Runner / command preview (ported from gpu_manager_gui)
    private val scriptField = JBTextField(40)
    private val condaEnvField = JBTextField(18)
    private val useDockerCb = JCheckBox("Docker")
    private val dockerContainerField = JBTextField(18)
    private val forceTorchrunCb = JCheckBox("torchrun")
    private val paramsArea = javax.swing.JTextArea(4, 40).apply { lineWrap = true; wrapStyleWord = true }
    private val envArea = javax.swing.JTextArea(4, 40).apply { lineWrap = true; wrapStyleWord = true }
    private val previewArea = javax.swing.JTextArea(5, 80).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val copyPreviewBtn = JButton("Copy")

    private val gpuTableModel = object : DefaultTableModel(arrayOf("GPU", "Name", "Util", "Memory"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val gpuTable = JTable(gpuTableModel)
    private val gpuScroll = JBScrollPane(gpuTable)
    private val runnerPanel = JPanel(BorderLayout())

    @Volatile private var poller: SshGpuPoller? = null
    @Volatile private var currentParams: SshParams? = null
    @Volatile private var lastSnapshot: Snapshot? = null
    @Volatile private var userResized: Boolean = false

    init {
        // Consistent left alignment for all input fields (including spinner editors)
        hostField.horizontalAlignment = JTextField.LEFT
        userField.horizontalAlignment = JTextField.LEFT
        keyField.horizontalAlignment = JTextField.LEFT
        nvidiaSmiPathField.horizontalAlignment = JTextField.LEFT
        try { passField.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}
        try { (portSpin.editor as? JSpinner.DefaultEditor)?.textField?.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}
        try { (intervalSpin.editor as? JSpinner.DefaultEditor)?.textField?.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}

        // Top connection/settings panel (collapsible)
        settingsPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(8, 8, 8, 8),
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                BorderFactory.createEmptyBorder(8, 8, 8, 8),
            ),
        )
        var gx = 0; var gy = 0
        fun add(lbl: String, comp: java.awt.Component) {
            settingsPanel.add(JLabel(lbl), GridBagConstraints().apply {
                gridx = gx; gridy = gy; insets = Insets(2, 4, 2, 4); anchor = GridBagConstraints.LINE_END
            })
            val row = JPanel(java.awt.BorderLayout())
            row.add(comp, java.awt.BorderLayout.CENTER)
            settingsPanel.add(row, GridBagConstraints().apply {
                gridx = gx + 1; gridy = gy; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(2, 4, 2, 12)
            })
            gy += 1
        }
        add("Host", hostField)
        // Fix spinner width
        try { portSpin.preferredSize = Dimension(100, portSpin.preferredSize.height) } catch (_: Throwable) {}
        add("Port", portSpin)
        add("User", userField)
        val keyRow = JPanel(GridBagLayout())
        keyRow.add(keyField, GridBagConstraints().apply { gridx = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(0,0,0,4) })
        keyRow.add(browseKeyBtn, GridBagConstraints().apply { gridx = 1; weightx = 0.0; anchor = GridBagConstraints.LINE_END })
        add("Identity", keyRow)
        val pwRow = JPanel(GridBagLayout())
        pwRow.add(passField, GridBagConstraints().apply { gridx = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(0,0,0,4) })
        pwRow.add(usePasswordCb, GridBagConstraints().apply { gridx = 1; weightx = 0.0; insets = Insets(0,0,0,4) })
        pwRow.add(rememberPwCb, GridBagConstraints().apply { gridx = 2; weightx = 0.0 })
        add("Password", pwRow)
        add("Interval (s)", intervalSpin)
        add("nvidia-smi", nvidiaSmiPathField)
        add("OS", osLabel)

        // Put seldom-used actions inside Settings to avoid long-term header clutter.
        val actionsRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(testBtn)
            add(selfTestBtn)
        }
        add("Actions", actionsRow)

        val header = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(6, 8, 2, 8)
        }
        val titleLabel = JBLabel("IsaacLab Assistant")
        val headerLeft = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(titleLabel)
            add(connSummaryLabel)
        }
        val headerButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(connectBtn)
            add(disconnectBtn)
            add(settingsToggleBtn)
            add(debugToggleBtn)
        }
        // Separate rows to avoid overlap in narrow toolwindows.
        header.add(headerLeft, BorderLayout.CENTER)
        header.add(headerButtons, BorderLayout.SOUTH)

        val topContainer = JPanel(BorderLayout())
        topContainer.add(header, BorderLayout.NORTH)
        topContainer.add(settingsPanel, BorderLayout.CENTER)
        add(topContainer, BorderLayout.NORTH)

        // Hide debug by default; it is noisy during normal use.
        debugPane.isVisible = false
        debugEnabled = false
        settingsCollapsed = false
        settingsToggleBtn.toolTipText = "Hide settings"

        // Center: GPU table only; util and memory rendered as colored bars
        gpuTable.fillsViewportHeight = true
        gpuTable.rowHeight = 22
        // Let user control widths; show horizontal scrollbar when needed
        gpuTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
        gpuTable.selectionModel.selectionMode = javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        // Set renderers: util (col 2) and memory (col 3)
        gpuTable.columnModel.getColumn(2).cellRenderer = PercentBarRenderer()
        gpuTable.columnModel.getColumn(3).cellRenderer = MemoryBarRenderer()
        // Track manual resize so we don't override user's widths
        gpuTable.columnModel.addColumnModelListener(object : TableColumnModelListener {
            override fun columnMarginChanged(e: javax.swing.event.ChangeEvent?) { userResized = true }
            override fun columnMoved(e: TableColumnModelEvent?) { userResized = true }
            override fun columnAdded(e: TableColumnModelEvent?) {}
            override fun columnRemoved(e: TableColumnModelEvent?) {}
            override fun columnSelectionChanged(e: javax.swing.event.ListSelectionEvent?) {}
        })
        // Double-click header to auto-fit this column
        gpuTable.tableHeader.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val col = gpuTable.columnModel.getColumnIndexAtX(e.x)
                    if (col >= 0) adjustSingleColumn(col)
                }
            }
        })
        gpuScroll.viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                applyProportionalColumnWidths()
            }
        })

        // Runner tab UI (ported from gpu_manager_gui, but separated from connection settings)
        val runnerRow1 = JPanel(GridBagLayout()).apply {
            add(JLabel("Script"), GridBagConstraints().apply { gridx = 0; insets = Insets(0, 0, 0, 6); anchor = GridBagConstraints.LINE_START })
            add(scriptField, GridBagConstraints().apply { gridx = 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(0, 0, 0, 12) })
            add(JLabel("Conda"), GridBagConstraints().apply { gridx = 2; insets = Insets(0, 0, 0, 6) })
            add(condaEnvField, GridBagConstraints().apply { gridx = 3; insets = Insets(0, 0, 0, 12); fill = GridBagConstraints.HORIZONTAL })
            add(useDockerCb, GridBagConstraints().apply { gridx = 4; insets = Insets(0, 0, 0, 6) })
            add(dockerContainerField, GridBagConstraints().apply { gridx = 5; insets = Insets(0, 0, 0, 12); fill = GridBagConstraints.HORIZONTAL })
            add(forceTorchrunCb, GridBagConstraints().apply { gridx = 6; insets = Insets(0, 0, 0, 0) })
        }
        val runnerRow2 = JPanel(GridBagLayout()).apply {
            add(JLabel("Params"), GridBagConstraints().apply { gridx = 0; anchor = GridBagConstraints.FIRST_LINE_START; insets = Insets(0, 0, 0, 6) })
            add(JBScrollPane(paramsArea), GridBagConstraints().apply { gridx = 1; weightx = 1.0; fill = GridBagConstraints.BOTH; insets = Insets(0, 0, 0, 12) })
            add(JLabel("Env"), GridBagConstraints().apply { gridx = 2; anchor = GridBagConstraints.FIRST_LINE_START; insets = Insets(0, 0, 0, 6) })
            add(JBScrollPane(envArea), GridBagConstraints().apply { gridx = 3; weightx = 1.0; fill = GridBagConstraints.BOTH; insets = Insets(0, 0, 0, 0) })
        }
        val runnerRow3 = JPanel(BorderLayout()).apply {
            val top = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JLabel("Preview"))
                add(copyPreviewBtn)
                add(JLabel("GPU selection comes from table row selection"))
            }
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(previewArea), BorderLayout.CENTER)
        }
        runnerPanel.add(runnerRow1, BorderLayout.NORTH)
        runnerPanel.add(runnerRow2, BorderLayout.CENTER)
        runnerPanel.add(runnerRow3, BorderLayout.SOUTH)

        val tabs = JBTabbedPane().apply {
            addTab("Monitor", gpuScroll)
            addTab("Runner", runnerPanel)
        }
        add(tabs, BorderLayout.CENTER)

        // Bottom panel: debug (center) + status bar (south)
        val statusBar = JPanel(BorderLayout())
        statusLabel.foreground = JBColor.GRAY
        statusBar.add(statusLabel, BorderLayout.WEST)
        val bottom = JPanel(BorderLayout())
        bottom.add(debugPane, BorderLayout.CENTER)
        bottom.add(statusBar, BorderLayout.SOUTH)
        add(bottom, BorderLayout.SOUTH)

        browseKeyBtn.addActionListener {
            val fc = JFileChooser()
            fc.dialogTitle = "Choose private key file"
            val rc = fc.showOpenDialog(this)
            if (rc == JFileChooser.APPROVE_OPTION) {
                keyField.text = fc.selectedFile.absolutePath
            }
        }

        usePasswordCb.addChangeListener {
            val enabled = usePasswordCb.isSelected
            passField.isEnabled = enabled
            keyField.isEnabled = !enabled
            browseKeyBtn.isEnabled = !enabled
        }

        testBtn.addActionListener { doTest() }
        connectBtn.addActionListener { doConnect() }
        disconnectBtn.addActionListener { doDisconnect() }
        debugToggleBtn.addActionListener {
            debugPane.isVisible = !debugPane.isVisible
            debugEnabled = debugPane.isVisible
            revalidate(); repaint()
        }
        settingsToggleBtn.addActionListener {
            settingsCollapsed = !settingsCollapsed
            settingsPanel.isVisible = !settingsCollapsed
            settingsToggleBtn.toolTipText = if (settingsCollapsed) "Show settings" else "Hide settings"
            revalidate(); repaint()
        }
        selfTestBtn.addActionListener {
            appendDebug("[ui] panel alive @ " + java.time.LocalTime.now().toString() + "\n")
        }

        fun docListener(block: () -> Unit): DocumentListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = block()
            override fun removeUpdate(e: DocumentEvent?) = block()
            override fun changedUpdate(e: DocumentEvent?) = block()
        }
        val updatePreview = { updateRunnerPreview() }
        scriptField.document.addDocumentListener(docListener(updatePreview))
        condaEnvField.document.addDocumentListener(docListener(updatePreview))
        dockerContainerField.document.addDocumentListener(docListener(updatePreview))
        paramsArea.document.addDocumentListener(docListener(updatePreview))
        envArea.document.addDocumentListener(docListener(updatePreview))
        useDockerCb.addChangeListener { updateRunnerPreview() }
        forceTorchrunCb.addChangeListener { updateRunnerPreview() }
        gpuTable.selectionModel.addListSelectionListener(object : ListSelectionListener {
            override fun valueChanged(e: ListSelectionEvent?) {
                if (e != null && e.valueIsAdjusting) return
                updateRunnerPreview()
            }
        })
        copyPreviewBtn.addActionListener { copyPreviewToClipboard() }

        // Load persisted state
        loadStateToUi()

        // Initial banner so user sees something immediately
        appendDebug("IsaacLab Assistant panel initialized\n")
        setStatus("UI ready")
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("IsaacLabAssistant")
                .createNotification("IsaacLab Assistant panel constructed", NotificationType.INFORMATION)
                .notify(null)
        } catch (_: Exception) {}

        updateRunnerPreview()
    }

    private data class Runner(
        val condaEnv: String,
        val useDocker: Boolean,
        val dockerContainer: String,
        val script: String,
        val params: List<Pair<String, String?>>,
        val env: List<Pair<String, String>>,
        val useTorchrun: Boolean,
        val gpuList: List<Int>,
    )

    private fun selectedGpuIndices(): List<Int> {
        val rows = gpuTable.selectedRows ?: return emptyList()
        val idxs = ArrayList<Int>()
        for (r in rows) {
            val v = gpuTableModel.getValueAt(r, 0)
            val i = when (v) {
                is Number -> v.toInt()
                else -> v?.toString()?.trim()?.toIntOrNull()
            } ?: continue
            idxs += i
        }
        return idxs.distinct().sorted()
    }

    private fun parseParams(text: String): List<Pair<String, String?>> {
        val out = ArrayList<Pair<String, String?>>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('=')
            if (eq < 0) {
                out += Pair(line, null)
            } else {
                val k = line.substring(0, eq).trim()
                val v = line.substring(eq + 1).trim()
                if (k.isNotEmpty()) out += Pair(k, v)
            }
        }
        return out
    }

    private fun parseEnv(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('=')
            if (eq < 0) return@forEach
            val k = line.substring(0, eq).trim()
            val v = line.substring(eq + 1).trim()
            if (k.isNotEmpty()) out += Pair(k, v)
        }
        return out
    }

    private fun collectRunner(): Runner {
        val gsel = selectedGpuIndices()
        val baseEnv = parseEnv(envArea.text)
        val envList = ArrayList<Pair<String, String>>(baseEnv.size + 1)
        if (gsel.isNotEmpty()) {
            envList += Pair("CUDA_VISIBLE_DEVICES", gsel.joinToString(","))
        }
        for ((k, v) in baseEnv) {
            if (k.trim().equals("CUDA_VISIBLE_DEVICES", ignoreCase = true)) continue
            envList += Pair(k, v)
        }
        return Runner(
            condaEnv = condaEnvField.text.trim(),
            useDocker = useDockerCb.isSelected,
            dockerContainer = dockerContainerField.text.trim(),
            script = scriptField.text.trim(),
            params = parseParams(paramsArea.text),
            env = envList,
            useTorchrun = forceTorchrunCb.isSelected,
            gpuList = gsel,
        )
    }

    private fun buildPythonCmd(r: Runner): String {
        val nproc = r.gpuList.size
        val useTorchrun = (nproc > 1) || r.useTorchrun

        val parts = ArrayList<String>()
        parts += "./isaaclab.sh"
        parts += "-p"
        if (useTorchrun) {
            parts += listOf("-m", "torch.distributed.run", "--nnodes=1", "--nproc_per_node=${maxOf(1, nproc)}")
        }
        if (r.script.isNotEmpty()) parts += shQuote(r.script)

        var haveDistributed = false
        for ((k0, v0) in r.params) {
            val key0 = k0.trim()
            if (key0.isEmpty()) continue
            if (key0 == "distributed" || key0 == "--distributed") haveDistributed = true
            val key = if (key0.startsWith("--")) key0 else "--$key0"
            if (v0.isNullOrEmpty()) {
                parts += key
            } else {
                parts += "$key=${shQuote(v0)}"
            }
        }
        if (useTorchrun && !haveDistributed) parts += "--distributed"
        return parts.joinToString(" ")
    }

    private fun buildPreviewCommands(r: Runner): List<String> {
        val envDict = LinkedHashMap<String, String>()
        for ((k, v) in r.env) {
            val kk = k.trim()
            if (kk.isNotEmpty()) envDict[kk] = v
        }

        fun envInlinePrefix(): String {
            val pairs = ArrayList<String>()
            for ((k, v) in envDict) {
                val kk = k.trim()
                if (kk.isEmpty()) continue
                pairs += "${kk}=${shQuote(v)}"
            }
            return pairs.joinToString(" ")
        }

        fun condaLine(): String {
            val condaEnv = if (r.useDocker) "" else r.condaEnv.trim()
            if (condaEnv.isEmpty()) return ""
            val envQ = shQuote(condaEnv)
            return (
                "for p in \"\$HOME/miniconda3/etc/profile.d/conda.sh\" \"\$HOME/anaconda3/etc/profile.d/conda.sh\" /opt/conda/etc/profile.d/conda.sh; " +
                "do [ -f \"\$p\" ] && . \"\$p\" && break; done; " +
                "ENV=$envQ; case \"\$ENV\" in miniconda3|anaconda3|miniforge3|mambaforge|micromamba|\"\" ) ENV=base;; esac; " +
                "conda activate \"\$ENV\" 2>/dev/null || conda activate base 2>/dev/null"
            )
        }

        val cmds = ArrayList<String>()
        val basePy = buildPythonCmd(r)
        val envPrefix = envInlinePrefix()
        val pyLine = if (envPrefix.isNotEmpty()) "$envPrefix $basePy" else basePy

        if (r.useDocker) {
            val name = r.dockerContainer.trim()
            cmds += if (name.isNotEmpty()) "docker exec -it $name bash -l" else "docker exec -it <container> bash -l"
            cmds += pyLine
            return cmds
        }

        val cl = condaLine()
        if (cl.isNotEmpty()) cmds += cl
        cmds += pyLine
        return cmds
    }

    private fun updateRunnerPreview() {
        try {
            val r = collectRunner()
            val cmds = buildPreviewCommands(r)
            previewArea.text = cmds.joinToString("\n")
        } catch (e: Exception) {
            previewArea.text = "preview error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun copyPreviewToClipboard() {
        val s = previewArea.text.orEmpty().trim()
        if (s.isEmpty()) return
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(s), null)
            setStatus("Copied preview commands")
        } catch (_: Exception) {}
    }

    private fun snapshotToTables(s: Snapshot) {
        // GPU table only: index, name, util bar, memory bar
        gpuTableModel.rowCount = 0
        for (g in s.gpus) {
            val util = g.utilPercent
            val mem = Pair(g.memUsedMiB, g.memTotalMiB)
            gpuTableModel.addRow(arrayOf(g.index, g.name, util, mem))
        }
        applyProportionalColumnWidths()
    }

    private fun applyProportionalColumnWidths() {
        if (gpuTable.columnCount < 4) return
        val viewportW = gpuScroll.viewport.width
        if (viewportW <= 0) return
        // Ratio: 1 : 5 : 3 : 3
        val weights = intArrayOf(1, 5, 3, 3)
        val sum = weights.sum().coerceAtLeast(1)

        val available = (viewportW - 8).coerceAtLeast(1)
        val widths = IntArray(4) { i -> (available * weights[i] / sum).coerceAtLeast(1) }
        // Fix rounding mismatch by distributing remainder
        var delta = available - widths.sum()
        var guard = 0
        while (delta != 0 && guard++ < 200) {
            val idx = guard % 4
            val step = if (delta > 0) 1 else -1
            val next = widths[idx] + step
            if (next >= 1) {
                widths[idx] = next
                delta -= step
            }
        }
        for (c in 0..3) {
            val col = gpuTable.columnModel.getColumn(c)
            col.preferredWidth = widths[c]
        }
    }

    private fun adjustSingleColumn(col: Int) {
        if (col < 0 || col >= gpuTable.columnCount) return
        val table = gpuTable
        val column = table.columnModel.getColumn(col)
        var maxw = 0
        // header width
        val headerRenderer = table.tableHeader.defaultRenderer
        val headerComp = headerRenderer.getTableCellRendererComponent(table, column.headerValue, false, false, -1, col)
        maxw = maxOf(maxw, headerComp.preferredSize.width)
        // cell widths
        val rowCount = table.rowCount
        val margin = 16
        for (r in 0 until rowCount) {
            val comp = table.prepareRenderer(table.getCellRenderer(r, col), r, col)
            maxw = maxOf(maxw, comp.preferredSize.width)
        }
        // Reasonable bounds per column
        val minW = when (col) { 0 -> 50; 1 -> 120; 2 -> 80; 3 -> 140; else -> 60 }
        val maxW = when (col) { 1 -> 800; else -> 400 }
        val target = maxOf(minW, minOf(maxw + margin, maxW))
        column.preferredWidth = target
    }

    private fun doTest() {
        val p = formParams()
        val exec = SshExec(p)
        val smi = p.nvidiaSmiPath?.trim()?.takeIf { it.isNotEmpty() }?.let { shQuote(it) } ?: "nvidia-smi"
        val remoteCmd = (
            "set -e; " +
            "echo \"SHELL=${'$'}SHELL\"; " +
            "echo \"PATH=${'$'}PATH\"; " +
            "echo \"NVIDIA_SMI=${smi}\"; " +
            "LC_ALL=C LANG=C $smi -L || LC_ALL=C LANG=C $smi --query-gpu=index --format=csv,noheader,nounits"
        )
        debugPane.isVisible = true; revalidate(); repaint()
        Thread {
            appendDebug("[test] $ ${remoteCmd}\n")
            val (rc, out, err) = exec.run(remoteCmd)
            val cleanedOut = out.trim()
            val mode = if (!p.password.isNullOrEmpty()) "JSch(password)" else "system ssh"
            val summary = buildSshSummary(p, remoteCmd) + " [mode=$mode]"
            appendDebug("[test] rc=$rc\nstdout:\n$cleanedOut\n\nstderr:\n${err.trim()}\n\n$summary\n")
            if (rc == 0 && cleanedOut.isNotEmpty()) {
                edt { Messages.showInfoMessage(this, "SSH OK\n\n$cleanedOut\n\n$summary", "Test") }
            } else if (rc == 0 && cleanedOut.isEmpty()) {
                val msg = "SSH OK, but command produced no output.\n(see Debug for details)\n\n$summary"
                edt { Messages.showWarningDialog(this, msg, "Test") }
            } else {
                val msg = (err.ifBlank { out }).ifBlank { "ssh failed rc=$rc" } + "\n(see Debug for details)\n\n$summary"
                edt { Messages.showWarningDialog(this, msg, "Test Failed") }
            }
        }.start()
    }

    private fun doConnect() {
        doDisconnect()
        val p = formParams()
        currentParams = p
        setStatus("Connecting to ${buildDest(p)}:${p.port} …")
        connSummaryLabel.text = "Connecting…"

        // Persist settings
        saveUiToState()
        // OS label async
        Thread {
            val (rc, out, err) = SshExec(p).run(
                "(cat /etc/os-release 2>/dev/null | sed -n '1,3p') || uname -a || echo unknown"
            )
            val text = if (rc == 0) out.trim().lines().firstOrNull() ?: "" else (err.ifBlank { out })
            edt { osLabel.text = "OS: ${text}" }
        }.start()

        val poll = SshGpuPoller(p, (intervalSpin.value as Number).toDouble(), object : SshGpuPoller.Listener {
            override fun onSnapshot(s: Snapshot) {
                edt {
                    val prev = lastSnapshot
                    var show = s
                    if (prev != null) {
                        // Keep previous GPUs if current has fewer (likely parse/hiccup)
                        if (s.gpus.isEmpty() || s.gpus.size < prev.gpus.size) {
                            show = show.copy(gpus = prev.gpus)
                        }
                        // Keep previous apps if none reported this cycle
                        if (s.apps.isEmpty()) {
                            show = show.copy(apps = prev.apps, pidUserMap = prev.pidUserMap)
                        }
                    }
                    snapshotToTables(show)
                    lastSnapshot = show
                    val now = java.time.LocalTime.now().withNano(0).toString()
                    if (s.errors.isNotEmpty()) {
                        setStatus("Error @ $now: " + s.errors.joinToString("; "))
                    } else {
                        setStatus("Updated ${show.gpus.size} GPU(s) @ $now")
                    }
                }
            }
            override fun onError(msg: String) {
                if (msg.isNotBlank()) {
                    val now = java.time.LocalTime.now().withNano(0).toString()
                    edt { setStatus("Error @ $now: $msg") }
                }
            }
            override fun onDebug(msg: String) {
                if (debugEnabled) edt { appendDebug("[poller] $msg\n") }
            }
        })
        poll.isDaemon = true
        poll.start()
        poller = poll
        connectBtn.isEnabled = false
        disconnectBtn.isEnabled = true
        // Reduce header clutter after connecting.
        connectBtn.isVisible = false
        testBtn.isVisible = false
        disconnectBtn.isVisible = true
        revalidate(); repaint()

        val connText = "${buildDest(p)}:${p.port}"
        connSummaryLabel.text = "Connected: " + shortenMiddle(connText, 40)
        connSummaryLabel.toolTipText = connText
        // Hide settings after connect to keep the UI focused on the GPU table.
        settingsCollapsed = true
        settingsPanel.isVisible = false
        settingsToggleBtn.toolTipText = "Show settings"
    }

    private fun doDisconnect() {
        poller?.requestStop()
        poller = null
        connectBtn.isEnabled = true
        disconnectBtn.isEnabled = false
        // Restore header controls.
        connectBtn.isVisible = true
        testBtn.isVisible = true
        disconnectBtn.isVisible = false
        revalidate(); repaint()
        setStatus("")
        lastSnapshot = null
        connSummaryLabel.text = "Disconnected"
        connSummaryLabel.toolTipText = null
        settingsCollapsed = false
        // Show settings again so the user can adjust connection parameters.
        // Keep debug state unchanged.
        // Note: settingsPanel visibility is managed by settingsCollapsed.
    }

    private fun formParams(): SshParams {
        val host = hostField.text.trim()
        val user = userField.text.trim().ifEmpty { null }
        val id = keyField.text.trim().ifEmpty { null }
        val pw = String(passField.password).trim().ifEmpty { null }
        val usePw = usePasswordCb.isSelected
        return SshParams(
            host = host,
            port = (portSpin.value as Number).toInt(),
            username = user,
            identityFile = if (usePw) null else id,
            password = if (usePw) pw else null,
            nvidiaSmiPath = nvidiaSmiPathField.text.trim().ifEmpty { null },
            timeoutSec = 10,
        )
    }
    private fun buildDest(p: SshParams): String {
        val user = (p.username ?: "").trim()
        val host = (p.host ?: "").trim()
        return if (user.isEmpty()) host else "$user@$host"
    }
    private fun buildSshSummary(p: SshParams, cmd: String = "<cmd>"): String {
        val parts = mutableListOf("ssh", "-p", p.port.toString())
        if (!p.identityFile.isNullOrBlank()) parts += listOf("-i", p.identityFile!!)
        parts += listOf(buildDest(p), "--", "bash", "-lc", cmd)
        return parts.joinToString(" ")
    }

    private fun shortenMiddle(s: String, maxLen: Int): String {
        if (maxLen <= 8) return s.take(maxLen)
        if (s.length <= maxLen) return s
        val keep = maxLen - 1
        val left = (keep * 2) / 3
        val right = keep - left
        return s.take(left) + "…" + s.takeLast(right)
    }

    private fun setStatus(msg: String) {
        statusLabel.text = msg
    }

    private fun edt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    private fun appendDebug(s: String) {
        edt {
            try {
                // Keep logs bounded to avoid long-term noise / memory growth.
                val maxChars = 40_000
                val trimTo = 30_000
                if (debugArea.document.length > maxChars) {
                    debugArea.replaceRange("", 0, debugArea.document.length - trimTo)
                }
                debugArea.append(s)
                debugArea.caretPosition = debugArea.document.length
            } catch (_: Throwable) {}
        }
    }

    // --- Persist/restore ---
    private fun loadStateToUi() {
        val st = GpuManagerState.getInstance().state
        hostField.text = st.host.orEmpty()
        portSpin.value = st.port
        userField.text = st.username.orEmpty()
        keyField.text = st.identity.orEmpty()
        usePasswordCb.isSelected = st.usePassword
        rememberPwCb.isSelected = st.rememberPassword
        intervalSpin.value = st.intervalSec
        nvidiaSmiPathField.text = st.nvidiaSmiPath.orEmpty()
        // Load password from Password Safe if remembered
        if (st.rememberPassword && st.usePassword) {
            val host = st.host.orEmpty()
            if (host.isNotEmpty()) {
                val pw = GpuManagerState.getInstance().loadPassword(host, st.port, st.username)
                if (!pw.isNullOrEmpty()) passField.text = pw
            }
        }
        // Apply toggle
        passField.isEnabled = st.usePassword
        keyField.isEnabled = !st.usePassword
        browseKeyBtn.isEnabled = !st.usePassword
    }

    private fun saveUiToState() {
        val st = GpuManagerState.getInstance()
        val s = st.state
        s.host = hostField.text.trim()
        s.port = (portSpin.value as Number).toInt()
        s.username = userField.text.trim()
        s.identity = keyField.text.trim()
        s.usePassword = usePasswordCb.isSelected
        s.rememberPassword = rememberPwCb.isSelected
        s.intervalSec = (intervalSpin.value as Number).toDouble()
        s.nvidiaSmiPath = nvidiaSmiPathField.text.trim().ifEmpty { null }
        if (s.rememberPassword && s.usePassword) {
            val pw = String(passField.password)
            GpuManagerState.getInstance().savePassword(s.host.orEmpty(), s.port, s.username, pw)
        } else {
            GpuManagerState.getInstance().savePassword(s.host.orEmpty(), s.port, s.username, null)
        }
    }

}
