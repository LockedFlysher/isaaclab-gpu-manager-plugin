package org.jetbrains.plugins.template.gpu

import com.intellij.openapi.ui.Messages
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
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
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.ScrollPaneConstants
import javax.swing.JRadioButton
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.JComboBox
import javax.swing.JPasswordField
import javax.swing.Timer
import javax.swing.table.DefaultTableModel
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import org.jdom.Element
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener

class GpuManagerPanel(private val project: com.intellij.openapi.project.Project) : JBPanel<GpuManagerPanel>(BorderLayout()) {

    @Volatile private var gatewayTarget: DetectedGatewayTarget? = null
    private val sshConfigCombo = JComboBox<String>()
    @Volatile private var sshConfigTargets: List<DetectedGatewayTarget> = emptyList()
    private val sshPasswordField = JPasswordField(18)
    private val sshPasswordApplyTimer = Timer(450) {
        applySshPasswordToStoreAndRestart()
    }.apply { isRepeats = false }

    private val intervalSpin = JSpinner(SpinnerNumberModel(5.0, 1.0, 120.0, 1.0))
    private val settingsPanel = JPanel(GridBagLayout())
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
    private val connSummaryLabel = JBLabel("").apply { foreground = JBColor.GRAY }
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
    private val loadRunField = TextFieldWithBrowseButton(JBTextField(18)).apply {
        toolTipText = "Folder for --load_run (select a run directory)"
    }
    private val checkpointField = TextFieldWithBrowseButton(JBTextField(18)).apply {
        toolTipText = "Checkpoint file for --checkpoint (select a .pt file)"
    }
    private val gpuBoxesPanel = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4)).apply {
        border = BorderFactory.createEmptyBorder()
    }
    @Volatile private var runnerGpuButtons: List<JToggleButton> = emptyList()
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
    private val saveRunConfigBtn = JButton("Save as Python Run Config")

    private val gpuTableModel = object : DefaultTableModel(arrayOf("GPU", "Name", "Util", "Memory"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val gpuTable = JTable(gpuTableModel)
    private val gpuScroll = JBScrollPane(gpuTable)
    private val runnerPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    @Volatile private var poller: SshGpuPoller? = null
    @Volatile private var currentParams: SshParams? = null
    @Volatile private var lastSnapshot: Snapshot? = null
    @Volatile private var userResized: Boolean = false

    init {
        // Consistent left alignment for all input fields (including spinner editors)
        entryScriptField.horizontalAlignment = JTextField.LEFT
        taskField.horizontalAlignment = JTextField.LEFT
        numEnvsField.horizontalAlignment = JTextField.LEFT
        experimentNameField.horizontalAlignment = JTextField.LEFT
        try { loadRunField.textField.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}
        try { checkpointField.textField.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}
        try { (intervalSpin.editor as? JSpinner.DefaultEditor)?.textField?.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}
        try { sshPasswordField.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}

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
            val label = JLabel(lbl)
            settingsPanel.add(label, GridBagConstraints().apply {
                gridx = gx; gridy = gy; insets = Insets(2, 4, 2, 4); anchor = GridBagConstraints.LINE_END
            })
            val row = JPanel(java.awt.BorderLayout())
            row.add(comp, java.awt.BorderLayout.CENTER)
            settingsPanel.add(row, GridBagConstraints().apply {
                gridx = gx + 1; gridy = gy; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(2, 4, 2, 12)
            })
            gy += 1
        }

        add("Interval (s)", intervalSpin)
        add("OS", osLabel)

        // Use IDE-managed SSH configs (no manual host/port input).
        add("SSH Config", sshConfigCombo)
        add("SSH Password", sshPasswordField)

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
            add(settingsToggleBtn)
            add(debugToggleBtn)
        }
        // Buttons default to the top-right corner.
        val headerButtonsWrap = JPanel(BorderLayout()).apply {
            add(headerButtons, BorderLayout.NORTH)
        }
        header.add(headerLeft, BorderLayout.WEST)
        header.add(headerButtonsWrap, BorderLayout.EAST)

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
        allowShrinkField(loadRunField.textField)
        allowShrinkField(checkpointField.textField)

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
        loadRunField.textField.columns = 26
        checkpointField.textField.columns = 26
        resumeDetailsPanel.add(JLabel("--experiment_name"), rbc(0, 0))
        resumeDetailsPanel.add(experimentNameField, rbc(0, 1).apply { weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.LINE_START })
        resumeDetailsPanel.add(JLabel("--load_run"), rbc(1, 0))
        resumeDetailsPanel.add(loadRunField, rbc(1, 1).apply { weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.LINE_START })
        resumeDetailsPanel.add(JLabel("--checkpoint"), rbc(2, 0))
        resumeDetailsPanel.add(checkpointField, rbc(2, 1).apply { weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.LINE_START })
        resumeSectionPanel.removeAll()
        resumeSectionPanel.add(resumeDetailsPanel, BorderLayout.CENTER)

        val gpuRow = JPanel(BorderLayout(6, 0)).apply {
            add(gpuBoxesPanel, BorderLayout.CENTER)
        }
        runnerTop.add(JLabel("GPUs"), gbc(0, 4).apply { anchor = GridBagConstraints.LINE_END })
        runnerTop.add(gpuRow, gbc(1, 4).apply { gridwidth = 3; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })

        val paramsPanel = JPanel(BorderLayout(0, 4)).apply {
            add(makeAdditionalHeader("Additional Params", addParamBtn, delParamBtn), BorderLayout.NORTH)
            add(JBScrollPane(paramsTable), BorderLayout.CENTER)
        }
        val envPanel = JPanel(BorderLayout(0, 4)).apply {
            add(makeAdditionalHeader("Additional Env", addEnvBtn, delEnvBtn), BorderLayout.NORTH)
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

        fun updateAdditionalTablesHeights() {
            fun desiredTableHeight(table: JTable, rows: Int): Int {
                val headerH = table.tableHeader?.preferredSize?.height ?: 24
                return headerH + (table.rowHeight.coerceAtLeast(18) * rows)
            }

            val rowsParams = when {
                paramsModel.rowCount <= 0 -> 2
                paramsModel.rowCount <= 4 -> paramsModel.rowCount.coerceAtLeast(2)
                else -> 5
            }
            val rowsEnv = when {
                envModel.rowCount <= 0 -> 2
                envModel.rowCount <= 4 -> envModel.rowCount.coerceAtLeast(2)
                else -> 5
            }

            val h = maxOf(desiredTableHeight(paramsTable, rowsParams), desiredTableHeight(envTable, rowsEnv))
            paramsTable.preferredScrollableViewportSize = Dimension(400, h)
            envTable.preferredScrollableViewportSize = Dimension(400, h)

            // Keep the split pane compact when empty.
            val midH = (h + 42).coerceAtMost(260)
            midSplit.preferredSize = Dimension(0, midH)
            midSplit.maximumSize = Dimension(Int.MAX_VALUE, midH)
        }

        // Let Runner shrink/grow naturally; scroll when needed.
        runnerTop.alignmentX = Component.LEFT_ALIGNMENT
        midSplit.alignmentX = Component.LEFT_ALIGNMENT
        runnerBottom.alignmentX = Component.LEFT_ALIGNMENT
        runnerBottom.maximumSize = Dimension(Int.MAX_VALUE, 220)

        runnerPanel.add(runnerTop)
        runnerPanel.add(Box.createVerticalStrut(6))
        runnerPanel.add(midSplit)
        runnerPanel.add(Box.createVerticalStrut(8))
        runnerPanel.add(runnerBottom)

        updateAdditionalTablesHeights()
        paramsModel.addTableModelListener { updateAdditionalTablesHeights() }
        envModel.addTableModelListener { updateAdditionalTablesHeights() }

        val runnerScroll = JBScrollPane(runnerPanel).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            border = BorderFactory.createEmptyBorder()
        }

        val tabs = JBTabbedPane().apply {
            addTab("Monitor", gpuScroll)
            addTab("Runner", runnerScroll)
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
        intervalSpin.addChangeListener {
            saveUiToState()
            restartMonitor()
        }
        sshConfigCombo.addActionListener {
            applySelectedSshTargetToHeader()
            loadSavedPasswordForSelectedTarget()
            restartMonitor()
        }

        fun docListener(block: () -> Unit): DocumentListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = block()
            override fun removeUpdate(e: DocumentEvent?) = block()
            override fun changedUpdate(e: DocumentEvent?) = block()
        }
        val updatePreview = { updateRunnerPreview() }
        sshPasswordField.document.addDocumentListener(docListener { sshPasswordApplyTimer.restart() })
        entryScriptField.document.addDocumentListener(docListener(updatePreview))
        taskField.document.addDocumentListener(docListener(updatePreview))
        numEnvsField.document.addDocumentListener(docListener(updatePreview))
        experimentNameField.document.addDocumentListener(docListener(updatePreview))
        loadRunField.textField.document.addDocumentListener(docListener(updatePreview))
        checkpointField.textField.document.addDocumentListener(docListener(updatePreview))
        headlessCb.addChangeListener { updateRunnerPreview() }
        livestreamCb.addChangeListener { updateRunnerPreview() }
        resumeCb.addChangeListener {
            val on = resumeCb.isSelected
            experimentNameField.isEnabled = on
            loadRunField.isEnabled = on
            loadRunField.textField.isEnabled = on
            checkpointField.isEnabled = on
            checkpointField.textField.isEnabled = on
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
        loadRunField.textField.isEnabled = false
        checkpointField.isEnabled = false
        checkpointField.textField.isEnabled = false
        resumeSectionPanel.isVisible = false

        // Load persisted state
        loadStateToUi()

        // Runner tab: show a helpful placeholder until GPUs are detected.
        ensureRunnerGpuBoxes(0)

        // Initial banner so user sees something immediately
        appendDebug("IsaacLab Assistant panel initialized\n")
        loadIdeSshConfigs()
        applySelectedSshTargetToHeader()
        loadSavedPasswordForSelectedTarget()
        setStatus("UI ready")
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("IsaacLabAssistant")
                .createNotification("IsaacLab Assistant panel constructed", NotificationType.INFORMATION)
                .notify(null)
        } catch (_: Exception) {}

        updateRunnerPreview()
        installResumeFileChoosers()
        restartMonitor()
    }

    private fun makeAdditionalHeader(title: String, addBtn: JButton, delBtn: JButton): JPanel {
        // Keep +/- buttons visible even in a narrow toolwindow:
        // - label is on WEST and allowed to shrink
        // - buttons are on EAST and keep their preferred size
        val label = JBLabel(title).apply {
            foreground = JBColor.GRAY
            minimumSize = Dimension(0, preferredSize.height)
        }
        fun tighten(b: JButton) {
            b.margin = Insets(2, 6, 2, 6)
            b.isFocusable = false
        }
        tighten(addBtn)
        tighten(delBtn)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(addBtn)
            add(delBtn)
        }
        return JPanel(BorderLayout()).apply {
            add(label, BorderLayout.WEST)
            add(buttons, BorderLayout.EAST)
        }
    }

    private fun getRunnerSelectedGpus(): List<Int> {
        val out = ArrayList<Int>()
        for (b in runnerGpuButtons) {
            val idx = (b.actionCommand ?: "").toIntOrNull()
            if (idx != null && b.isSelected) out += idx
        }
        return out
    }

    private fun ensureRunnerGpuBoxes(n: Int) {
        val count = n.coerceAtLeast(0)
        val prev = getRunnerSelectedGpus().toSet()
        if (count == 0) {
            val msg = if (poller == null) "Waiting for GPU data…" else "No GPUs detected"
            val already =
                runnerGpuButtons.isEmpty() &&
                    gpuBoxesPanel.componentCount == 1 &&
                    (gpuBoxesPanel.getComponent(0) as? JBLabel)?.text == msg
            if (already) return
            gpuBoxesPanel.removeAll()
            runnerGpuButtons = emptyList()
            gpuBoxesPanel.add(JBLabel(msg).apply { foreground = JBColor.GRAY })
            gpuBoxesPanel.revalidate()
            gpuBoxesPanel.repaint()
            return
        }
        if (runnerGpuButtons.size == count) return
        gpuBoxesPanel.removeAll()
        val buttons = ArrayList<JToggleButton>(count)
        for (i in 0 until count) {
            val icon = GpuUsageIcon(i)
            val b = JToggleButton().apply {
                actionCommand = i.toString()
                isSelected = prev.contains(i)
                this.icon = icon
                toolTipText = "GPU $i"
                horizontalAlignment = SwingConstants.LEFT
                margin = Insets(0, 0, 0, 0)
                isFocusable = false
            }
            b.addChangeListener { updateRunnerPreview() }
            buttons += b
            gpuBoxesPanel.add(b)
        }
        runnerGpuButtons = buttons
        gpuBoxesPanel.revalidate()
        gpuBoxesPanel.repaint()
    }

    private fun updateRunnerGpuUsage(gpus: List<GpuInfo>) {
        if (runnerGpuButtons.isEmpty()) return
        val byIndex = gpus.associateBy { it.index }
        for (b in runnerGpuButtons) {
            val idx = (b.actionCommand ?: "").toIntOrNull() ?: continue
            val info = byIndex[idx]
            val icon = b.icon as? GpuUsageIcon ?: continue
            if (info != null) {
                icon.utilPercent = info.utilPercent
                icon.memUsedMiB = info.memUsedMiB
                icon.memTotalMiB = info.memTotalMiB
                b.toolTipText = "GPU ${info.index}: ${info.name} | util ${info.utilPercent}% | mem ${info.memUsedMiB} / ${info.memTotalMiB} MiB"
            } else {
                icon.utilPercent = 0
                icon.memUsedMiB = 0
                icon.memTotalMiB = 0
                b.toolTipText = "GPU $idx"
            }
            b.repaint()
        }
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

    private fun installResumeFileChoosers() {
        loadRunField.addActionListener {
            val start = initialRemoteBrowseDir()
            val dlg = RemotePathChooserDialog(
                project = project,
                sshParamsProvider = { formParams().copy(timeoutSec = 20) },
                mode = RemotePathChooserDialog.Mode.Directory,
                initialDirProvider = { start },
                onDebug = { appendDebug("[remote-chooser] $it\n") },
            )
            if (!dlg.showAndGet()) return@addActionListener
            val picked = dlg.selectedPath ?: return@addActionListener
            saveLastRemoteBrowseDir(picked)
            loadRunField.text = picked
            updateRunnerPreview()
        }
        checkpointField.addActionListener {
            val start = initialRemoteBrowseDir()
            val dlg = RemotePathChooserDialog(
                project = project,
                sshParamsProvider = { formParams().copy(timeoutSec = 20) },
                mode = RemotePathChooserDialog.Mode.PtFile,
                initialDirProvider = { start },
                onDebug = { appendDebug("[remote-chooser] $it\n") },
            )
            if (!dlg.showAndGet()) return@addActionListener
            val picked = dlg.selectedPath ?: return@addActionListener
            saveLastRemoteBrowseDir(parentDir(picked))
            checkpointField.text = picked
            updateRunnerPreview()
        }
    }

    private fun initialRemoteBrowseDir(): String {
        val saved = GpuManagerState.getInstance().state.lastRemoteBrowseDir?.trim().orEmpty()
        if (saved.isNotEmpty()) return saved
        val u = gatewayTarget?.user?.trim().orEmpty()
        if (u.isNotEmpty()) return "/home/$u"
        return "/home"
    }

    private fun saveLastRemoteBrowseDir(path: String) {
        val p = path.trim()
        if (p.isNotEmpty()) GpuManagerState.getInstance().state.lastRemoteBrowseDir = p
    }

    private fun parentDir(path: String): String {
        val p = path.trim().trimEnd('/')
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) "/" else p.substring(0, idx)
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
            val pyType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
                .firstOrNull { it.id == "PythonConfigurationType" }
                ?: throw IllegalStateException("Python run configuration type not found")
            val factory = pyType.configurationFactories.firstOrNull()
                ?: throw IllegalStateException("Python run configuration factory not found")
            val name = buildString {
                append("IsaacLab")
                val task = runner.task.trim()
                if (task.isNotEmpty()) append(": ").append(task)
            }
            val rm = RunManager.getInstance(project)
            val settings = rm.createConfiguration(name, factory)

            // Build a native PyCharm Python run configuration via its XML format.
            // Note: This is local/IDE-managed (interpreter, docker/conda, env vars, debug, etc).
            val moduleName = ModuleManager.getInstance(project).modules.firstOrNull()?.name.orEmpty()
            val script = runner.entryScript.trim()
            requireShellSafeToken("-p", script, allowEmpty = false)
            val scriptValue =
                if (script.startsWith("$") || script.startsWith("/")) script
                else "\$PROJECT_DIR\$/$script"

            val args = buildPythonParameters(runner, paramsToText())

            val element = Element("configuration")
            element.setAttribute("name", name)
            element.setAttribute("type", "PythonConfigurationType")
            element.setAttribute("factoryName", "Python")
            if (moduleName.isNotEmpty()) {
                element.addContent(Element("module").setAttribute("name", moduleName))
            }
            element.addContent(Element("option").setAttribute("name", "ENV_FILES").setAttribute("value", ""))
            element.addContent(Element("option").setAttribute("name", "INTERPRETER_OPTIONS").setAttribute("value", ""))
            element.addContent(Element("option").setAttribute("name", "PARENT_ENVS").setAttribute("value", "true"))
            element.addContent(Element("option").setAttribute("name", "SDK_HOME").setAttribute("value", ""))
            element.addContent(Element("option").setAttribute("name", "WORKING_DIRECTORY").setAttribute("value", "\$PROJECT_DIR\$"))
            element.addContent(Element("option").setAttribute("name", "IS_MODULE_SDK").setAttribute("value", "true"))
            element.addContent(Element("option").setAttribute("name", "ADD_CONTENT_ROOTS").setAttribute("value", "true"))
            element.addContent(Element("option").setAttribute("name", "ADD_SOURCE_ROOTS").setAttribute("value", "true"))
            element.addContent(Element("option").setAttribute("name", "SCRIPT_NAME").setAttribute("value", scriptValue))
            element.addContent(Element("option").setAttribute("name", "PARAMETERS").setAttribute("value", args))

            val envs = Element("envs")
            if (runner.gpuList.isNotEmpty()) {
                envs.addContent(Element("env").setAttribute("name", "CUDA_VISIBLE_DEVICES").setAttribute("value", runner.gpuList.joinToString(",")))
            }
            if (runner.livestream) {
                envs.addContent(Element("env").setAttribute("name", "LIVESTREAM").setAttribute("value", "2"))
            }
            // Merge additional env (skip duplicates that are controlled by UI)
            for ((k, v) in collectEnvFromTable()) {
                val kk = k.trim()
                if (kk.isEmpty()) continue
                if (kk.equals("CUDA_VISIBLE_DEVICES", ignoreCase = true)) continue
                if (kk.equals("LIVESTREAM", ignoreCase = true) && runner.livestream) continue
                requireShellSafeEnvKey("env key", kk)
                requireShellSafeToken("env value", v, allowEmpty = true)
                envs.addContent(Element("env").setAttribute("name", kk).setAttribute("value", v))
            }
            if (envs.children.isNotEmpty()) element.addContent(envs)

            settings.configuration.readExternal(element)
            rm.addConfiguration(settings)
            rm.selectedConfiguration = settings
            setStatus("Saved Run Configuration: $name")

            // Hint: PyCharm needs a Python interpreter (local or SSH) to show full Python Run Config UI and to debug.
            val sdk = ProjectRootManager.getInstance(project).projectSdk
            val isPythonSdk = sdk?.sdkType?.name?.contains("Python", ignoreCase = true) == true
            if (!isPythonSdk) {
                edt {
                    Messages.showWarningDialog(
                        this,
                        "Saved a Python Run Configuration, but this project has no Python interpreter configured.\n" +
                            "Configure one in Settings | Python Interpreter (local or SSH interpreter) to get the full Run/Debug panel and debugging support.",
                        "Python Interpreter Not Configured",
                    )
                }
            }
        } catch (e: Exception) {
            edt { Messages.showWarningDialog(this, e.message ?: e.javaClass.simpleName, "Save Run Configuration Failed") }
        }
    }

    private fun buildPythonParameters(runner: IsaacLabRunnerSpec, extraParamsText: String): String {
        val args = ArrayList<String>()
        val task = runner.task.trim()
        if (task.isNotEmpty()) {
            requireShellSafeToken("--task", task)
            args += "--task"
            args += task
        }
        val nenv = runner.numEnvs ?: 0
        if (nenv > 0) {
            requireShellSafeToken("--num_envs", nenv.toString())
            args += "--num_envs"
            args += nenv.toString()
        }
        if (runner.headless) args += "--headless"
        if (runner.resume) {
            args += "--resume"
            val en = runner.experimentName.trim()
            val lr = runner.loadRun.trim()
            val ck = runner.checkpoint.trim()
            if (en.isNotEmpty()) { requireShellSafeToken("--experiment_name", en); args += "--experiment_name"; args += en }
            if (lr.isNotEmpty()) { requireShellSafeToken("--load_run", lr); args += "--load_run"; args += lr }
            if (ck.isNotEmpty()) { requireShellSafeToken("--checkpoint", ck); args += "--checkpoint"; args += ck }
        }

        val extra = IsaacLabRunner.parseParams(extraParamsText)
        val drop = setOf("task", "num_envs", "headless", "resume", "experiment_name", "load_run", "checkpoint")
        for ((k0, v0) in extra) {
            val key0 = k0.trim()
            if (key0.isEmpty()) continue
            val kk = key0.removePrefix("--")
            if (drop.contains(kk)) continue
            requireShellSafeParamKey("param key", key0)
            if (v0.isNullOrEmpty()) {
                args += (if (key0.startsWith("-")) key0 else "--$key0")
            } else {
                requireShellSafeToken("param value", v0)
                val key = if (key0.startsWith("-")) key0 else "--$key0"
                args += key
                args += v0
            }
        }
        return args.joinToString(" ")
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

    private fun restartMonitor() {
        stopMonitor()
        val p = formParams()
        currentParams = p
        val local = p.isLocal()
        setStatus(if (local) "Monitoring IDE target …" else "Monitoring ${buildDest(p)}:${p.port} …")

        // Persist settings
        saveUiToState()
        // OS label async
        Thread {
        val cmd = if (local) "uname -a || echo unknown" else "(cat /etc/os-release 2>/dev/null | sed -n '1,3p') || uname -a || echo unknown"
            val (rc, out, err) = SshExec(p, project).run(cmd)
            val text = if (rc == 0) out.trim().lines().firstOrNull() ?: "" else (err.ifBlank { out })
            edt { osLabel.text = "OS: ${text}" }
        }.start()

        val poll = SshGpuPoller(p, project, (intervalSpin.value as Number).toDouble(), object : SshGpuPoller.Listener {
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
                    updateRunnerGpuUsage(show.gpus)
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
        revalidate(); repaint()

        val connText = if (local) EelBash.describeTarget(project) else "${buildDest(p)}:${p.port}"
        if (local) {
            connSummaryLabel.text = "Target: " + shortenMiddle(connText, 44)
            connSummaryLabel.toolTipText = connText
        } else {
            applySelectedSshTargetToHeader()
        }
    }

    private fun stopMonitor() {
        poller?.requestStop()
        poller = null
        revalidate(); repaint()
        setStatus("")
        lastSnapshot = null
        ensureRunnerGpuBoxes(0)
        updateRunnerPreview()
        // Keep target summary as-is.
    }

    private fun formParams(): SshParams {
        // Prefer Gateway-detected SSH target when available (supports non-22 ports like 50031).
        val gt = gatewayTarget
        if (gt != null) {
            val pw = String(sshPasswordField.password).trim().ifEmpty { null }
            return SshParams(
                host = gt.host,
                port = gt.port,
                username = gt.user,
                identityFile = null,
                password = pw,
                timeoutSec = 30,
            )
        }
        return SshParams(
            // Always execute on the IDE "project target" via EEL (Gateway => remote backend, local IDE => local machine).
            host = "",
            port = 22,
            username = null,
            identityFile = null,
            password = null,
            // EEL can be slower on first use, especially right after opening the project.
            timeoutSec = 30,
        )
    }
    private fun buildDest(p: SshParams): String {
        val user = (p.username ?: "").trim()
        val host = (p.host ?: "").trim()
        return if (user.isEmpty()) host else "$user@$host"
    }
    private fun loadIdeSshConfigs() {
        val targets = GatewayConnectionDetector.listTargets(project, onDebug = { appendDebug("[ssh] $it\n") })
        sshConfigTargets = targets
        sshConfigCombo.removeAllItems()
        if (targets.isEmpty()) {
            sshConfigCombo.addItem("No SSH configs found (configure in IDE)")
            sshConfigCombo.isEnabled = false
            gatewayTarget = null
            return
        }
        sshConfigCombo.isEnabled = true
        for (t in targets) {
            val label = buildString {
                val u = (t.user ?: "").trim()
                if (u.isNotEmpty()) append(u).append("@")
                append(t.host).append(":").append(t.port)
            }
            sshConfigCombo.addItem(label)
        }
        sshConfigCombo.selectedIndex = 0
        gatewayTarget = targets.first()
        // password field always enabled; empty means "use ssh-agent"
    }

    private fun applySelectedSshTargetToHeader() {
        val idx = sshConfigCombo.selectedIndex
        val targets = sshConfigTargets
        val t = if (idx in targets.indices) targets[idx] else null
        gatewayTarget = t
        if (t != null) {
            val dest = buildString {
                val u = (t.user ?: "").trim()
                if (u.isNotEmpty()) append(u).append("@")
                append(t.host).append(":").append(t.port)
            }
            connSummaryLabel.text = "SSH: " + shortenMiddle(dest, 52)
            connSummaryLabel.toolTipText = "${t.source}: $dest"
        } else {
            val target = EelBash.describeTarget(project)
            connSummaryLabel.text = "Target: " + shortenMiddle(target, 52)
            connSummaryLabel.toolTipText = target
        }
    }

    private fun loadSavedPasswordForSelectedTarget() {
        val t = gatewayTarget ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val pw = runCatching { SshPasswordStore.load(t.host, t.port, t.user) }.getOrNull().orEmpty()
            edt {
                // Do not overwrite what user is currently typing with a late async response.
                if (sshPasswordField.password.isEmpty()) {
                    sshPasswordField.text = pw
                }
            }
        }
    }

    private fun applySshPasswordToStoreAndRestart() {
        val t = gatewayTarget ?: run {
            restartMonitor()
            return
        }
        val pw = String(sshPasswordField.password).trim().ifEmpty { null }
        // PasswordSafe is a slow operation; never touch it on EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { SshPasswordStore.save(t.host, t.port, t.user, pw) }
        }
        restartMonitor()
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
        intervalSpin.value = st.intervalSec
    }

    private fun saveUiToState() {
        val st = GpuManagerState.getInstance()
        val s = st.state
        s.intervalSec = (intervalSpin.value as Number).toDouble()
    }

}
