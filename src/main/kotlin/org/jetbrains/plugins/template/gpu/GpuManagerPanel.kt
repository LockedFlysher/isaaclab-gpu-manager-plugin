package org.jetbrains.plugins.template.gpu

import com.intellij.openapi.ui.Messages
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
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
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.ScrollPaneConstants
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener

class GpuManagerPanel(private val project: com.intellij.openapi.project.Project) : JBPanel<GpuManagerPanel>(BorderLayout()) {

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
    private val entryScriptField = JBTextField(40).apply { toolTipText = "Python entry script path for `./isaaclab.sh -p`" }
    private val taskField = JBTextField(18)
    private val numEnvsField = JBTextField(10).apply {
        toolTipText = "Value for --num_envs (type a number, e.g. 8192)"
        text = "1"
    }
    private val headlessCb = JCheckBox("--headless").apply {
        toolTipText = "Run without UI rendering (recommended for remote/headless training)."
    }
    private val resumeCb = JCheckBox("--resume").apply {
        toolTipText = "Resume training from a previous run (requires experiment_name/load_run/checkpoint)."
    }
    private val livestreamCb = JCheckBox("LIVESTREAM=2").apply {
        toolTipText = "Enable IsaacLab livestream (sets env var LIVESTREAM=2)."
    }
    private val experimentNameField = JBTextField(18)
    private val loadRunField = JBTextField(18)
    private val checkpointField = JBTextField(18)
    private val gpuBoxesPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
    private val gpuBoxesScroll = JBScrollPane(gpuBoxesPanel).apply {
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        border = BorderFactory.createEmptyBorder()
    }
    @Volatile private var runnerGpuBoxes: List<JCheckBox> = emptyList()
    private val resumeDetailsPanel = JPanel(GridBagLayout())
    private val resumeSectionPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Options for --resume"),
            BorderFactory.createEmptyBorder(4, 6, 6, 6),
        )
    }
    private val paramsModel = object : DefaultTableModel(arrayOf("Key", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int) = true
    }
    private val envModel = object : DefaultTableModel(arrayOf("Key", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int) = true
    }
    private val paramsTable = JTable(paramsModel).apply { rowHeight = 22 }
    private val envTable = JTable(envModel).apply { rowHeight = 22 }
    private val addParamBtn = JButton("+")
    private val delParamBtn = JButton("–")
    private val addEnvBtn = JButton("+")
    private val delEnvBtn = JButton("–")
    private val previewArea = javax.swing.JTextArea(5, 80).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val copyPreviewBtn = JButton("Copy")
    private val saveRunConfigBtn = JButton("Save as Run Config")

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
        entryScriptField.horizontalAlignment = JTextField.LEFT
        taskField.horizontalAlignment = JTextField.LEFT
        numEnvsField.horizontalAlignment = JTextField.LEFT
        experimentNameField.horizontalAlignment = JTextField.LEFT
        loadRunField.horizontalAlignment = JTextField.LEFT
        checkpointField.horizontalAlignment = JTextField.LEFT
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

        runnerPanel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val runnerTop = JPanel(GridBagLayout())
        fun gbc(x: Int, y: Int) = GridBagConstraints().apply {
            gridx = x; gridy = y
            insets = Insets(2, 4, 2, 4)
        }

        fun allowShrinkField(tf: JTextField) {
            tf.minimumSize = Dimension(0, tf.preferredSize.height)
        }
        allowShrinkField(entryScriptField)
        allowShrinkField(taskField)
        allowShrinkField(experimentNameField)
        allowShrinkField(loadRunField)
        allowShrinkField(checkpointField)

        // Row 0: Entry (-p) full width
        entryScriptField.columns = 40
        runnerTop.add(JLabel("Entry (-p)"), gbc(0, 0).apply { anchor = GridBagConstraints.LINE_END })
        runnerTop.add(entryScriptField, gbc(1, 0).apply { gridwidth = 3; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })

        // Row 1: task
        taskField.columns = 26
        runnerTop.add(JLabel("--task"), gbc(0, 1).apply { anchor = GridBagConstraints.LINE_END })
        runnerTop.add(taskField, gbc(1, 1).apply { gridwidth = 3; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })

        // Row 2: num_envs (text input, no +/- buttons)
        runnerTop.add(JLabel("--num_envs"), gbc(0, 2).apply { anchor = GridBagConstraints.LINE_END })
        runnerTop.add(numEnvsField, gbc(1, 2).apply { weightx = 0.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.LINE_START })

        // Row 3: flags split into multiple lines; keep `--resume` last.
        val flagsRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder()
        }
        val flagsLine1 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(headlessCb)
        }
        val flagsLine2 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(livestreamCb)
        }
        val flagsLine3 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(resumeCb)
        }
        flagsRow.add(flagsLine1)
        flagsRow.add(flagsLine2)
        flagsRow.add(flagsLine3)

        // Keep flags always visually left-aligned; use the remaining space to the right for resume details.
        val flagsAndResume = JPanel(BorderLayout(16, 0)).apply { border = BorderFactory.createEmptyBorder() }
        flagsAndResume.add(flagsRow, BorderLayout.WEST)
        flagsAndResume.add(resumeSectionPanel, BorderLayout.CENTER)
        runnerTop.add(JLabel("Flags"), gbc(0, 3).apply { anchor = GridBagConstraints.LINE_END })
        runnerTop.add(flagsAndResume, gbc(1, 3).apply { gridwidth = 3; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })

        // Resume details stacked vertically; shown only when resume is enabled
        resumeDetailsPanel.removeAll()
        fun rbc(y: Int, x: Int) = GridBagConstraints().apply {
            gridx = x; gridy = y; insets = Insets(2, 0, 2, 8); anchor = GridBagConstraints.LINE_END
        }
        experimentNameField.columns = 26
        loadRunField.columns = 26
        checkpointField.columns = 26
        resumeDetailsPanel.add(JLabel("--experiment_name"), rbc(0, 0))
        resumeDetailsPanel.add(experimentNameField, rbc(0, 1).apply { weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.LINE_START })
        resumeDetailsPanel.add(JLabel("--load_run"), rbc(1, 0))
        resumeDetailsPanel.add(loadRunField, rbc(1, 1).apply { weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.LINE_START })
        resumeDetailsPanel.add(JLabel("--checkpoint"), rbc(2, 0))
        resumeDetailsPanel.add(checkpointField, rbc(2, 1).apply { weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.LINE_START })
        resumeSectionPanel.removeAll()
        resumeSectionPanel.add(resumeDetailsPanel, BorderLayout.CENTER)

        val gpuRow = JPanel(BorderLayout(6, 0)).apply {
            add(gpuBoxesScroll, BorderLayout.CENTER)
        }
        runnerTop.add(JLabel("GPUs"), gbc(0, 4).apply { anchor = GridBagConstraints.LINE_END })
        runnerTop.add(gpuRow, gbc(1, 4).apply { gridwidth = 3; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })

        val paramsPanel = JPanel(BorderLayout(0, 4)).apply {
            val top = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JLabel("Additional Params"))
                add(addParamBtn)
                add(delParamBtn)
            }
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(paramsTable), BorderLayout.CENTER)
        }
        val envPanel = JPanel(BorderLayout(0, 4)).apply {
            val top = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JLabel("Additional Env"))
                add(addEnvBtn)
                add(delEnvBtn)
            }
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(envTable), BorderLayout.CENTER)
        }
        val midSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, paramsPanel, envPanel).apply {
            resizeWeight = 0.5
            isOneTouchExpandable = true
        }

        val runnerBottom = JPanel(BorderLayout(0, 6)).apply {
            val top = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JLabel("Preview"))
                add(copyPreviewBtn)
                add(saveRunConfigBtn)
            }
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(previewArea), BorderLayout.CENTER)
        }

        runnerPanel.add(runnerTop, BorderLayout.NORTH)
        runnerPanel.add(midSplit, BorderLayout.CENTER)
        runnerPanel.add(runnerBottom, BorderLayout.SOUTH)

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
        entryScriptField.document.addDocumentListener(docListener(updatePreview))
        taskField.document.addDocumentListener(docListener(updatePreview))
        numEnvsField.document.addDocumentListener(docListener(updatePreview))
        experimentNameField.document.addDocumentListener(docListener(updatePreview))
        loadRunField.document.addDocumentListener(docListener(updatePreview))
        checkpointField.document.addDocumentListener(docListener(updatePreview))
        headlessCb.addChangeListener { updateRunnerPreview() }
        livestreamCb.addChangeListener { updateRunnerPreview() }
        resumeCb.addChangeListener {
            val on = resumeCb.isSelected
            experimentNameField.isEnabled = on
            loadRunField.isEnabled = on
            checkpointField.isEnabled = on
            resumeSectionPanel.isVisible = on
            updateRunnerPreview()
            runnerPanel.revalidate()
            runnerPanel.repaint()
        }
        copyPreviewBtn.addActionListener { copyPreviewToClipboard() }
        saveRunConfigBtn.addActionListener { saveAsRunConfiguration() }
        addParamBtn.addActionListener { addTableRow(paramsTable, paramsModel) }
        delParamBtn.addActionListener { deleteSelectedRows(paramsTable, paramsModel) }
        addEnvBtn.addActionListener { addTableRow(envTable, envModel) }
        delEnvBtn.addActionListener { deleteSelectedRows(envTable, envModel) }

        paramsModel.addTableModelListener { updateRunnerPreview() }
        envModel.addTableModelListener { updateRunnerPreview() }

        // Default resume detail fields disabled until resume is enabled
        experimentNameField.isEnabled = false
        loadRunField.isEnabled = false
        checkpointField.isEnabled = false
        resumeSectionPanel.isVisible = false

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

    private fun getRunnerSelectedGpus(): List<Int> {
        val out = ArrayList<Int>()
        for ((i, cb) in runnerGpuBoxes.withIndex()) {
            if (cb.isSelected) out += i
        }
        return out
    }

    private fun ensureRunnerGpuBoxes(n: Int) {
        val count = n.coerceAtLeast(0)
        val prev = getRunnerSelectedGpus().toSet()
        if (runnerGpuBoxes.size == count) return
        gpuBoxesPanel.removeAll()
        val boxes = ArrayList<JCheckBox>(count)
        for (i in 0 until count) {
            val cb = JCheckBox(i.toString())
            cb.isSelected = prev.contains(i)
            cb.addChangeListener { updateRunnerPreview() }
            boxes += cb
            gpuBoxesPanel.add(cb)
            if (i != count - 1) gpuBoxesPanel.add(Box.createHorizontalStrut(6))
        }
        runnerGpuBoxes = boxes
        gpuBoxesPanel.revalidate()
        gpuBoxesPanel.repaint()
    }

    private fun collectRunner(): IsaacLabRunnerSpec {
        val task = taskField.text.trim()
        val numEnvs = numEnvsField.text.trim().toIntOrNull() ?: 0
        return IsaacLabRunnerSpec(
            entryScript = entryScriptField.text.trim(),
            task = task,
            numEnvs = numEnvs,
            headless = headlessCb.isSelected,
            resume = resumeCb.isSelected,
            experimentName = experimentNameField.text.trim(),
            loadRun = loadRunField.text.trim(),
            checkpoint = checkpointField.text.trim(),
            livestream = livestreamCb.isSelected,
            extraParams = collectParamsFromTable(),
            extraEnv = collectEnvFromTable(),
            gpuList = getRunnerSelectedGpus(),
        )
    }

    private fun updateRunnerPreview() {
        try {
            val r = collectRunner()
            val cmds = IsaacLabRunner.buildPreviewCommands(r)
            previewArea.text = cmds.joinToString("\n")
            copyPreviewBtn.isEnabled = true
            saveRunConfigBtn.isEnabled = true
        } catch (e: Exception) {
            previewArea.text = "preview error: ${e.message ?: e.javaClass.simpleName}"
            copyPreviewBtn.isEnabled = false
            saveRunConfigBtn.isEnabled = false
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

    private fun saveAsRunConfiguration() {
        try {
            val runner = collectRunner()
            val ssh = formParams()
            val type = ConfigurationTypeUtil.findConfigurationType(org.jetbrains.plugins.template.runner.IsaacLabRunConfigurationType::class.java)
            val factory = type.configurationFactories.first()
            val name = buildString {
                append("IsaacLab")
                val task = runner.task.trim()
                if (task.isNotEmpty()) append(": ").append(task)
            }
            val rm = RunManager.getInstance(project)
            val settings = rm.createConfiguration(name, factory)
            val cfg = settings.configuration as? org.jetbrains.plugins.template.runner.IsaacLabRunConfiguration
                ?: throw IllegalStateException("unexpected configuration type")
            val st = cfg.state
            st.host = ssh.host
            st.port = ssh.port
            st.username = ssh.username
            st.identityFile = ssh.identityFile
            st.script = null
            st.task = runner.task
            st.numEnvs = runner.numEnvs ?: 1
            st.gpuList = runner.gpuList.joinToString(",").ifEmpty { null }
            st.entryScript = runner.entryScript
            st.headless = runner.headless
            st.resume = runner.resume
            st.experimentName = runner.experimentName
            st.loadRun = runner.loadRun
            st.checkpoint = runner.checkpoint
            st.livestream = runner.livestream
            st.extraParamsText = paramsToText()
            st.extraEnvText = envToText()
            rm.addConfiguration(settings)
            rm.selectedConfiguration = settings
            setStatus("Saved Run Configuration: $name")
        } catch (e: Exception) {
            edt { Messages.showWarningDialog(this, e.message ?: e.javaClass.simpleName, "Save Run Configuration Failed") }
        }
    }

    private fun addTableRow(table: JTable, model: DefaultTableModel) {
        model.addRow(arrayOf("", ""))
        val row = model.rowCount - 1
        if (row >= 0) {
            table.editCellAt(row, 0)
            table.changeSelection(row, 0, false, false)
        }
    }

    private fun deleteSelectedRows(table: JTable, model: DefaultTableModel) {
        val rows = table.selectedRows ?: return
        if (rows.isEmpty()) return
        for (r in rows.sortedDescending()) {
            if (r >= 0 && r < model.rowCount) model.removeRow(r)
        }
    }

    private fun collectParamsFromTable(): List<Pair<String, String?>> {
        val out = ArrayList<Pair<String, String?>>()
        for (r in 0 until paramsModel.rowCount) {
            val k = (paramsModel.getValueAt(r, 0)?.toString() ?: "").trim()
            val v = (paramsModel.getValueAt(r, 1)?.toString() ?: "").trim()
            if (k.isEmpty()) continue
            out += Pair(k, v.ifEmpty { null })
        }
        return out
    }

    private fun collectEnvFromTable(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (r in 0 until envModel.rowCount) {
            val k = (envModel.getValueAt(r, 0)?.toString() ?: "").trim()
            val v = (envModel.getValueAt(r, 1)?.toString() ?: "").trim()
            if (k.isEmpty()) continue
            out += Pair(k, v)
        }
        return out
    }

    private fun paramsToText(): String {
        return buildString {
            for ((k, v) in collectParamsFromTable()) {
                if (v.isNullOrEmpty()) append(k) else append(k).append('=').append(v)
                append('\n')
            }
        }.trimEnd()
    }

    private fun envToText(): String {
        return buildString {
            for ((k, v) in collectEnvFromTable()) {
                append(k).append('=').append(v)
                append('\n')
            }
        }.trimEnd()
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
                    ensureRunnerGpuBoxes(show.gpus.size)
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
        // Load password from Password Safe off-EDT (PasswordSafe may perform slow IO)
        if (st.rememberPassword && st.usePassword) {
            val host = st.host.orEmpty().trim()
            val port = st.port
            val user = st.username
            if (host.isNotEmpty()) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    val pw = runCatching { GpuManagerState.getInstance().loadPassword(host, port, user) }.getOrNull()
                    if (!pw.isNullOrEmpty()) {
                        edt {
                            val stillSame =
                                hostField.text.trim() == host &&
                                    (portSpin.value as? Number)?.toInt() == port &&
                                    userField.text.trim() == (user ?: "")
                            if (stillSame && usePasswordCb.isSelected && rememberPwCb.isSelected) {
                                passField.text = pw
                            }
                        }
                    }
                }
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
        val host = hostField.text.trim()
        val port = (portSpin.value as Number).toInt()
        val username = userField.text.trim()
        s.host = host
        s.port = port
        s.username = username
        s.identity = keyField.text.trim()
        s.usePassword = usePasswordCb.isSelected
        s.rememberPassword = rememberPwCb.isSelected
        s.intervalSec = (intervalSpin.value as Number).toDouble()
        s.nvidiaSmiPath = nvidiaSmiPathField.text.trim().ifEmpty { null }
        // PasswordSafe writes must not happen on EDT (SlowOperations)
        val shouldStorePw = s.rememberPassword && s.usePassword
        val pw = if (shouldStorePw) String(passField.password) else null
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { GpuManagerState.getInstance().savePassword(host, port, username, pw) }
        }
    }

}
