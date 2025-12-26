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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
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
import javax.swing.JComponent
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.JComboBox
import javax.swing.JPasswordField
import javax.swing.Timer
import javax.swing.table.DefaultTableModel
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.extensions.PluginId
import org.jdom.Element
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener
import java.util.concurrent.atomic.AtomicBoolean

class GpuManagerPanel(private val project: com.intellij.openapi.project.Project) : JBPanel<GpuManagerPanel>(BorderLayout()) {

    companion object {
        // Used to verify which build is currently loaded in the IDE (shown in debug output).
        private const val BUILD_MARKER = "2.5.4-no-ide-target-v1"
    }

    @Volatile private var gatewayTarget: DetectedGatewayTarget? = null
    private enum class ConnectionMode { IDE_SSH, MANUAL_SSH }
    private val connectionModeCombo = JComboBox(arrayOf("IDE SSH", "Manual SSH"))
    private val applyConnectionBtn = JButton("Apply").apply {
        toolTipText = "Apply connection settings and restart GPU monitor"
    }
    private val sshConfigCombo = JComboBox<String>()
    @Volatile private var sshConfigTargets: List<DetectedGatewayTarget> = emptyList()
    private val sshPasswordField = JPasswordField(18)
    private val manualSshUserField = JBTextField(12)
    private val manualSshHostField = JBTextField(18)
    private val manualSshPortField = JBTextField(6)
    private val sshPasswordApplyTimer = Timer(450) {
        applySshPasswordToStoreAndRestart()
    }.apply { isRepeats = false }
    private val manualTargetApplyTimer = Timer(450) {
        applyManualTargetAndRestart()
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
    private val entryScriptField = TextFieldWithBrowseButton(JBTextField(40)).apply {
        toolTipText = "Python entry script path for `./isaaclab.sh -p` (select a .py file)"
    }
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
    private val gpuBoxesPanel = JPanel(WrapLayout(FlowLayout.LEFT, 2, 2)).apply {
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
    private val runConfigUnavailableHint = JBLabel("Python Run Config not available in this IDE instance.").apply {
        foreground = JBColor.GRAY
        isVisible = false
    }
    @Volatile private var pythonRunConfigAvailable: Boolean = false

    private val argsArea = javax.swing.JTextArea(3, 80).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val envArea = javax.swing.JTextArea(3, 80).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val copyArgsBtn = JButton("Copy Args").apply { toolTipText = "Copy one-line Parameters for PyCharm Run/Debug config" }
    private val copyEnvBtn = JButton("Copy Env").apply { toolTipText = "Copy one-line Environment variables for PyCharm Run/Debug config" }

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
    @Volatile private var pendingRunnerSelectedGpus: Set<Int> = emptySet()
    @Volatile private var loadingState: Boolean = false
    private data class SettingsRow(val label: JLabel, val row: JPanel)
    private lateinit var rowIdeSshConfig: SettingsRow
    private lateinit var rowManualUser: SettingsRow
    private lateinit var rowManualHost: SettingsRow
    private lateinit var rowManualPort: SettingsRow
    private lateinit var rowPassword: SettingsRow

    // Reverse tunnel (ssh -R) UI/state
    private val reverseBindPortSpin = JSpinner(SpinnerNumberModel(7897, 1, 65535, 1))
    private val reverseLocalPortSpin = JSpinner(SpinnerNumberModel(7897, 1, 65535, 1))
    private val reverseStartBtn = JButton("Start")
    private val reverseStopBtn = JButton("Stop").apply { isEnabled = false }
    private val reverseStatusLabel = JBLabel("Not running").apply { foreground = JBColor.GRAY }
    private val reverseStopFlag = AtomicBoolean(false)
    @Volatile private var reverseThread: Thread? = null
    @Volatile private var reverseSession: com.jcraft.jsch.Session? = null
    @Volatile private var reverseProc: Process? = null

    init {
        // Consistent left alignment for all input fields (including spinner editors)
        try { entryScriptField.textField.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}
        taskField.horizontalAlignment = JTextField.LEFT
        numEnvsField.horizontalAlignment = JTextField.LEFT
        experimentNameField.horizontalAlignment = JTextField.LEFT
        try { loadRunField.textField.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}
        try { checkpointField.textField.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}
        try { (intervalSpin.editor as? JSpinner.DefaultEditor)?.textField?.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}
        try { sshPasswordField.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}
        try { manualSshUserField.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}
        try { manualSshHostField.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}
        try { manualSshPortField.horizontalAlignment = JTextField.LEFT } catch (_: Throwable) {}

        // Top connection/settings panel (collapsible)
        settingsPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(8, 8, 8, 8),
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                BorderFactory.createEmptyBorder(8, 8, 8, 8),
            ),
        )
        var gx = 0; var gy = 0
        fun addRow(lbl: String, comp: java.awt.Component): SettingsRow {
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
            return SettingsRow(label, row)
        }

        addRow("Interval (s)", intervalSpin)
        addRow("OS", osLabel)

        // Use IDE-managed SSH configs (no manual host/port input).
        val connectionRow = JPanel(BorderLayout(6, 0)).apply {
            add(connectionModeCombo, BorderLayout.CENTER)
            add(applyConnectionBtn, BorderLayout.EAST)
        }
        addRow("Connection", connectionRow)
        rowIdeSshConfig = addRow("IDE SSH Config", sshConfigCombo)
        rowManualUser = addRow("Manual User", manualSshUserField)
        rowManualHost = addRow("Manual Host", manualSshHostField)
        rowManualPort = addRow("Manual Port", manualSshPortField)
        rowPassword = addRow("SSH Password", sshPasswordField)

        val header = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(6, 8, 2, 8)
        }
        val titleLabel = JBLabel("IsaacLab Assistant").apply {
            toolTipText = "Build: $BUILD_MARKER"
        }
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
        allowShrinkField(entryScriptField.textField)
        allowShrinkField(taskField)
        allowShrinkField(experimentNameField)
        allowShrinkField(loadRunField.textField)
        allowShrinkField(checkpointField.textField)

        // Row 0: Entry (-p) full width
        entryScriptField.textField.columns = 40
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
                add(copyArgsBtn)
                add(copyEnvBtn)
                add(saveRunConfigBtn)
                add(runConfigUnavailableHint)
            }
            add(top, BorderLayout.NORTH)
            val previewScroll = JBScrollPane(previewArea)
            val argsPanel = JPanel(BorderLayout(0, 2)).apply {
                add(JBLabel("Parameters (display)").apply { foreground = JBColor.GRAY }, BorderLayout.NORTH)
                add(JBScrollPane(argsArea).apply { horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }, BorderLayout.CENTER)
            }
            val envPanel = JPanel(BorderLayout(0, 2)).apply {
                add(JBLabel("Environment variables (display)").apply { foreground = JBColor.GRAY }, BorderLayout.NORTH)
                add(JBScrollPane(envArea).apply { horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }, BorderLayout.CENTER)
            }
            val mid = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, argsPanel, envPanel).apply {
                resizeWeight = 0.6
                isOneTouchExpandable = true
            }
            val bottom = JSplitPane(JSplitPane.VERTICAL_SPLIT, previewScroll, mid).apply {
                resizeWeight = 0.7
                isOneTouchExpandable = true
            }
            add(bottom, BorderLayout.CENTER)
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
            addTab("Proxy", makeProxyPanel())
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
        connectionModeCombo.addActionListener {
            updateConnectionModeUi()
            applySelectedSshTargetToHeader()
            loadSavedPasswordForSelectedTarget()
            saveUiToState()
            restartMonitor()
        }
        applyConnectionBtn.addActionListener {
            updateConnectionModeUi()
            applySelectedSshTargetToHeader()
            loadSavedPasswordForSelectedTarget()
            saveUiToState()
            restartMonitor()
        }
        reverseStartBtn.addActionListener { startReverseTunnel() }
        reverseStopBtn.addActionListener { stopReverseTunnel() }

        fun docListener(block: () -> Unit): DocumentListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = block()
            override fun removeUpdate(e: DocumentEvent?) = block()
            override fun changedUpdate(e: DocumentEvent?) = block()
        }
        val updatePreview = {
            updateRunnerPreview()
            saveUiToState()
        }
        sshPasswordField.document.addDocumentListener(docListener { sshPasswordApplyTimer.restart() })
        manualSshUserField.document.addDocumentListener(docListener { manualTargetApplyTimer.restart() })
        manualSshHostField.document.addDocumentListener(docListener { manualTargetApplyTimer.restart() })
        manualSshPortField.document.addDocumentListener(docListener { manualTargetApplyTimer.restart() })
        entryScriptField.textField.document.addDocumentListener(docListener(updatePreview))
        taskField.document.addDocumentListener(docListener(updatePreview))
        numEnvsField.document.addDocumentListener(docListener(updatePreview))
        experimentNameField.document.addDocumentListener(docListener(updatePreview))
        loadRunField.textField.document.addDocumentListener(docListener(updatePreview))
        checkpointField.textField.document.addDocumentListener(docListener(updatePreview))
        headlessCb.addChangeListener { updatePreview() }
        livestreamCb.addChangeListener { updatePreview() }
        resumeCb.addChangeListener {
            val on = resumeCb.isSelected
            experimentNameField.isEnabled = on
            loadRunField.isEnabled = on
            loadRunField.textField.isEnabled = on
            checkpointField.isEnabled = on
            checkpointField.textField.isEnabled = on
            resumeSectionPanel.isVisible = on
            updatePreview()
            runnerPanel.revalidate()
            runnerPanel.repaint()
        }
        copyPreviewBtn.addActionListener { copyPreviewToClipboard() }
        saveRunConfigBtn.addActionListener { saveAsRunConfiguration() }
        copyArgsBtn.addActionListener { copyArgsToClipboard() }
        copyEnvBtn.addActionListener { copyEnvToClipboard() }
        addParamBtn.addActionListener { addTableRow(paramsTable, paramsModel) }
        delParamBtn.addActionListener { deleteSelectedRows(paramsTable, paramsModel) }
        addEnvBtn.addActionListener { addTableRow(envTable, envModel) }
        delEnvBtn.addActionListener { deleteSelectedRows(envTable, envModel) }

        paramsModel.addTableModelListener { updatePreview() }
        envModel.addTableModelListener { updatePreview() }

        // Default resume detail fields disabled until resume is enabled
        experimentNameField.isEnabled = false
        loadRunField.isEnabled = false
        loadRunField.textField.isEnabled = false
        checkpointField.isEnabled = false
        checkpointField.textField.isEnabled = false
        resumeSectionPanel.isVisible = false

        // Load persisted state
        loadIdeSshConfigs()
        loadStateToUi()
        updateConnectionModeUi()

        // Runner tab: show a helpful placeholder until GPUs are detected.
        ensureRunnerGpuBoxes(0)

        // Initial banner so user sees something immediately
        appendDebug("IsaacLab Assistant panel initialized [$BUILD_MARKER]\n")
        try {
            val pd = PluginManagerCore.getPlugin(PluginId.getId("com.sunnypea.isaaclab.assistant"))
            val v = pd?.version ?: "unknown"
            appendDebug("plugin: com.sunnypea.isaaclab.assistant@$v\n")
            setStatus("Loaded IsaacLab Assistant $v ($BUILD_MARKER)")
        } catch (t: Throwable) {
            appendDebug("plugin: version detect failed: ${t.javaClass.simpleName}: ${t.message}\n")
        }
        applySelectedSshTargetToHeader()
        loadSavedPasswordForSelectedTarget()
        setStatus("UI ready")
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("IsaacLabAssistant")
                .createNotification("IsaacLab Assistant panel constructed", NotificationType.INFORMATION)
                .notify(null)
        } catch (_: Exception) {}

        pythonRunConfigAvailable = detectPythonRunConfigAvailability()
        saveRunConfigBtn.isVisible = true
        saveRunConfigBtn.isEnabled = true
        runConfigUnavailableHint.isVisible = false

        updateRunnerPreview()
        installResumeFileChoosers()
        restartMonitor()
    }

    private fun currentConnectionMode(): ConnectionMode {
        return when ((connectionModeCombo.selectedItem as? String).orEmpty()) {
            "Manual SSH" -> ConnectionMode.MANUAL_SSH
            else -> ConnectionMode.IDE_SSH
        }
    }

    private fun updateConnectionModeUi() {
        val mode = currentConnectionMode()
        val showIde = mode == ConnectionMode.IDE_SSH
        val showManual = mode == ConnectionMode.MANUAL_SSH
        rowIdeSshConfig.label.isVisible = showIde
        rowIdeSshConfig.row.isVisible = showIde
        rowManualUser.label.isVisible = showManual
        rowManualUser.row.isVisible = showManual
        rowManualHost.label.isVisible = showManual
        rowManualHost.row.isVisible = showManual
        rowManualPort.label.isVisible = showManual
        rowManualPort.row.isVisible = showManual
        rowPassword.label.isVisible = true
        rowPassword.row.isVisible = true
        settingsPanel.revalidate()
        settingsPanel.repaint()
    }

    private fun makeProxyPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }

        val form = JPanel(GridBagLayout())
        fun gbc(x: Int, y: Int): GridBagConstraints = GridBagConstraints().apply {
            gridx = x
            gridy = y
            insets = Insets(2, 4, 2, 4)
            anchor = GridBagConstraints.LINE_START
        }
        form.add(JLabel("Reverse Tunnel (ssh -R)"), gbc(0, 0).apply { gridwidth = 4; anchor = GridBagConstraints.LINE_START })
        form.add(JBLabel("Remote server binds a port and forwards to your local localhost port.").apply { foreground = JBColor.GRAY },
            gbc(0, 1).apply { gridwidth = 4 })

        form.add(JLabel("Remote bind port"), gbc(0, 2).apply { anchor = GridBagConstraints.LINE_END })
        form.add(reverseBindPortSpin, gbc(1, 2))
        form.add(JLabel("Local port"), gbc(2, 2).apply { anchor = GridBagConstraints.LINE_END })
        form.add(reverseLocalPortSpin, gbc(3, 2))

        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(reverseStartBtn)
            add(reverseStopBtn)
            add(reverseStatusLabel)
        }
        form.add(btnRow, gbc(0, 3).apply { gridwidth = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })

        reverseBindPortSpin.addChangeListener { saveUiToState() }
        reverseLocalPortSpin.addChangeListener { saveUiToState() }

        root.add(form, BorderLayout.NORTH)
        root.add(JBLabel("Tip: this requires SSH mode (IDE SSH / Manual SSH).").apply { foreground = JBColor.GRAY }, BorderLayout.SOUTH)
        return root
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
        val prev = (getRunnerSelectedGpus().toSet() + pendingRunnerSelectedGpus).toSet()
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
                icon.selected = isSelected
                toolTipText = "GPU $i"
                horizontalAlignment = SwingConstants.LEFT
                margin = Insets(0, 0, 0, 0)
                isFocusable = false
                isFocusPainted = false
                isContentAreaFilled = false
                isBorderPainted = false
                border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
            }
            b.addChangeListener {
                icon.selected = b.isSelected
                b.repaint()
                updateRunnerPreview()
                saveUiToState()
            }
            buttons += b
            gpuBoxesPanel.add(b)
        }
        runnerGpuButtons = buttons
        pendingRunnerSelectedGpus = emptySet()
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
        // This line is intentionally always printed so users can confirm they are running
        // a build that contains the remote SFTP browser fixes.
        appendDebug("[browse] hooks installed\n")

        fun chooseAndApply(kind: String, mode: SftpBrowserChooser.Mode) {
            appendDebug("[browse] click: $kind\n")
            val base = formParamsOrNull()
            if (base == null) {
                Messages.showWarningDialog(this, "Configure an SSH connection first.", "SSH Not Configured")
                appendDebug("[browse] result: <none> (no ssh target)\n")
                return
            }
            val picked = SftpBrowserChooser.choose(
                project = project,
                params = base.copy(timeoutSec = 20),
                initialPath = initialRemoteBrowseDir(),
                mode = mode,
                onDebug = { appendDebug("[browse] $it\n") },
            )
            if (picked.isNullOrBlank()) {
                appendDebug("[browse] result: <none>\n")
                return
            }

            when (mode) {
                SftpBrowserChooser.Mode.Directory -> {
                    saveLastRemoteBrowseDir(picked)
                    loadRunField.text = picked
                }
                SftpBrowserChooser.Mode.PtFile -> {
                    if (!picked.endsWith(".pt")) {
                        Messages.showWarningDialog(this, "Please select a .pt file", "Invalid Checkpoint")
                        appendDebug("[browse] result rejected (not .pt): '$picked'\n")
                        return
                    }
                    saveLastRemoteBrowseDir(parentDir(picked))
                    checkpointField.text = picked
                }
                SftpBrowserChooser.Mode.PyFile -> {
                    if (!picked.endsWith(".py")) {
                        Messages.showWarningDialog(this, "Please select a .py file", "Invalid Entry Script")
                        appendDebug("[browse] result rejected (not .py): '$picked'\n")
                        return
                    }
                    saveLastRemoteBrowseDir(parentDir(picked))
                    entryScriptField.text = picked
                }
            }
            appendDebug("[browse] result: '$picked'\n")
            updateRunnerPreview()
        }

        // TextFieldWithBrowseButton has slightly different event sources between IDE versions.
        // Hook both the browse button (addActionListener) and the text field (Enter key).
        entryScriptField.addActionListener { chooseAndApply("entry_script", SftpBrowserChooser.Mode.PyFile) }
        entryScriptField.textField.addActionListener { chooseAndApply("entry_script(text)", SftpBrowserChooser.Mode.PyFile) }

        loadRunField.addActionListener { chooseAndApply("load_run", SftpBrowserChooser.Mode.Directory) }
        loadRunField.textField.addActionListener { chooseAndApply("load_run(text)", SftpBrowserChooser.Mode.Directory) }

        checkpointField.addActionListener { chooseAndApply("checkpoint", SftpBrowserChooser.Mode.PtFile) }
        checkpointField.textField.addActionListener { chooseAndApply("checkpoint(text)", SftpBrowserChooser.Mode.PtFile) }
    }

    private fun initialRemoteBrowseDir(): String {
        val saved = GpuManagerState.getInstance().state.lastRemoteBrowseDir?.trim().orEmpty()
        if (saved.isNotEmpty()) return saved
        val u = effectiveSshUser().trim()
        if (u.isNotEmpty()) return "/home/$u"
        return "/home"
    }

    private fun saveLastRemoteBrowseDir(path: String) {
        val p0 = path.trim()
        val p = if (p0.startsWith("/")) p0 else "/$p0"
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
            val (argsOneLine, argsDisplay) = buildArgsStrings(r)
            val (envOneLine, envDisplay) = buildEnvStrings(r)
            argsArea.text = argsDisplay
            envArea.text = envDisplay
            copyPreviewBtn.isEnabled = true
            saveRunConfigBtn.isEnabled = true
            copyArgsBtn.isEnabled = argsOneLine.isNotEmpty()
            copyEnvBtn.isEnabled = envOneLine.isNotEmpty()
        } catch (e: Exception) {
            previewArea.text = "preview error: ${e.message ?: e.javaClass.simpleName}"
            argsArea.text = ""
            envArea.text = ""
            copyPreviewBtn.isEnabled = false
            saveRunConfigBtn.isEnabled = false
            copyArgsBtn.isEnabled = false
            copyEnvBtn.isEnabled = false
        }
    }

    private fun buildArgsStrings(runner: IsaacLabRunnerSpec): Pair<String, String> {
        val oneLine = buildPythonParameters(runner, paramsToText()).trim()
        val display = oneLine.replace(" --", "\n--").trim()
        return Pair(oneLine, display)
    }

    private fun buildEnvStrings(runner: IsaacLabRunnerSpec): Pair<String, String> {
        val env = linkedMapOf<String, String>()
        // Predefined
        if (runner.gpuList.isNotEmpty()) env["CUDA_VISIBLE_DEVICES"] = runner.gpuList.joinToString(",")
        if (runner.livestream) env["LIVESTREAM"] = "2"
        // Additional env table (UI wins)
        for ((k, v) in collectEnvFromTable()) {
            val kk = k.trim()
            if (kk.isEmpty()) continue
            env[kk] = v.trim()
        }
        val oneLine = env.entries
            .filter { it.key.isNotEmpty() }
            .joinToString(";") { "${it.key}=${it.value}" }
        val display = env.entries
            .filter { it.key.isNotEmpty() }
            .joinToString("\n") { "${it.key}=${it.value}" }
        return Pair(oneLine, display)
    }

    private fun copyArgsToClipboard() {
        val r = collectRunner()
        val (argsOneLine, _) = buildArgsStrings(r)
        if (argsOneLine.isBlank()) return
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(argsOneLine), null)
            setStatus("Copied Parameters")
        } catch (_: Exception) {}
    }

    private fun copyEnvToClipboard() {
        val r = collectRunner()
        val (envOneLine, _) = buildEnvStrings(r)
        if (envOneLine.isBlank()) return
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(envOneLine), null)
            setStatus("Copied Environment variables")
        } catch (_: Exception) {}
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
            val factory = findPythonConfigurationFactory()
            val name = buildString {
                append("IsaacLab")
                val task = runner.task.trim()
                if (task.isNotEmpty()) append(": ").append(task)
            }
            val effFactory = factory ?: findPythonFactoryViaRunManagerImpl()
            if (effFactory == null) {
                runConfigUnavailableHint.isVisible = true
                throw IllegalStateException(
                    "Python run configuration factory not found in this IDE instance.\n" +
                        "In JetBrains Gateway/Client, this usually means this plugin is running in the frontend (JetBrains Client) where Python run factories are not available.\n" +
                        "Fix: install/enable this plugin in the Remote IDE Backend (Gateway -> Manage IDE Plugins for the remote).",
                )
            }
            val rm = RunManager.getInstance(project)
            val settings = rm.createConfiguration(name, effFactory)

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
            // Match PyCharm's typical Python Run Configuration structure (see ref/.idea/workspace.xml).
            element.addContent(Element("EXTENSION").setAttribute("ID", "PythonCoverageRunConfigurationExtension").setAttribute("runner", "coverage.py"))
            element.addContent(Element("option").setAttribute("name", "RUN_TOOL").setAttribute("value", ""))
            element.addContent(Element("option").setAttribute("name", "SCRIPT_NAME").setAttribute("value", scriptValue))
            element.addContent(Element("option").setAttribute("name", "PARAMETERS").setAttribute("value", args))
            element.addContent(Element("option").setAttribute("name", "SHOW_COMMAND_LINE").setAttribute("value", "false"))
            element.addContent(Element("option").setAttribute("name", "EMULATE_TERMINAL").setAttribute("value", "false"))
            element.addContent(Element("option").setAttribute("name", "MODULE_MODE").setAttribute("value", "false"))
            element.addContent(Element("option").setAttribute("name", "REDIRECT_INPUT").setAttribute("value", "false"))
            element.addContent(Element("option").setAttribute("name", "INPUT_FILE").setAttribute("value", ""))

            val envs = Element("envs")
            // Default PyCharm env for better logs; let user override in Additional Env.
            val extraEnvKeys = collectEnvFromTable().map { it.first.trim() }.filter { it.isNotEmpty() }.toSet()
            if (!extraEnvKeys.any { it.equals("PYTHONUNBUFFERED", ignoreCase = true) }) {
                envs.addContent(Element("env").setAttribute("name", "PYTHONUNBUFFERED").setAttribute("value", "1"))
            }
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
            element.addContent(Element("method").setAttribute("v", "2"))

            settings.configuration.readExternal(element)
            rm.addConfiguration(settings)
            rm.selectedConfiguration = settings
            setStatus("Saved Run Configuration: $name")

            // In Gateway/Client, Python SDK types may not be present in the frontend classpath even if the backend
            // has an interpreter configured. Only show this warning when Python SDK infrastructure is available.
            val canCheckPythonSdk = runCatching { Class.forName("com.jetbrains.python.sdk.PythonSdkType") }.isSuccess
            if (canCheckPythonSdk) {
                val sdk = ProjectRootManager.getInstance(project).projectSdk
                val isPythonSdk = sdk?.sdkType?.name?.contains("Python", ignoreCase = true) == true
                if (!isPythonSdk) {
                    edt {
                        Messages.showWarningDialog(
                            this,
                            "Saved a Python Run Configuration, but this project has no Python interpreter configured.\n" +
                                "Configure one in Settings | Python Interpreter to get the full Run/Debug panel and debugging support.",
                            "Python Interpreter Not Configured",
                        )
                    }
                }
            } else {
                appendDebug("[runconfig] skip interpreter check (Python SDK classes not available in this IDE instance)\n")
            }
        } catch (e: Exception) {
            edt { Messages.showWarningDialog(this, e.message ?: e.javaClass.simpleName, "Save Run Configuration Failed") }
        }
    }

    private fun findPythonFactoryViaRunManagerImpl(): com.intellij.execution.configurations.ConfigurationFactory? {
        // Official-ish approach for Gateway/Client:
        // RunManagerImpl can resolve factories by typeId/factoryName even when the frontend doesn't have the type in EP list.
        return runCatching {
            val rmImplCls = Class.forName("com.intellij.execution.impl.RunManagerImpl")
            val getInstanceImpl = rmImplCls.methods.firstOrNull { it.name == "getInstanceImpl" && it.parameterCount == 1 }
                ?: return@runCatching null
            val rmImpl = getInstanceImpl.invoke(null, project) ?: return@runCatching null

            val getFactory = rmImplCls.methods.firstOrNull { it.name == "getFactory" && it.parameterCount == 3 }
                ?: return@runCatching null
            // First try without allowing UnknownConfigurationType.
            val factory = getFactory.invoke(rmImpl, "PythonConfigurationType", "Python", false)
            val f = factory as? com.intellij.execution.configurations.ConfigurationFactory
            if (f != null) {
                appendDebug("[runconfig] RunManagerImpl.getFactory ok: '${f.name}' (${f.javaClass.name})\n")
                return@runCatching f
            }

            // Diagnostic: if allowUnknown=true returns UnknownConfigurationType, it's not usable.
            val factory2 = getFactory.invoke(rmImpl, "PythonConfigurationType", "Python", true)
            val f2 = factory2 as? com.intellij.execution.configurations.ConfigurationFactory
            if (f2 != null) {
                appendDebug("[runconfig] RunManagerImpl.getFactory (allowUnknown) -> '${f2.name}' (${f2.javaClass.name})\n")
            } else {
                appendDebug("[runconfig] RunManagerImpl.getFactory returned null\n")
            }
            null
        }.onFailure {
            appendDebug("[runconfig] RunManagerImpl.getFactory failed: ${it.javaClass.simpleName}: ${it.message}\n")
        }.getOrNull()
    }

    private fun detectPythonRunConfigAvailability(): Boolean {
        // No debug spam: this is used to toggle UI visibility/state.
        runCatching {
            val cls = Class.forName("com.jetbrains.python.run.PythonConfigurationType")
            val inst = cls.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }?.invoke(null)
            val type = inst as? ConfigurationType
            return type?.configurationFactories?.isNotEmpty() == true
        }
        for (t in ConfigurationType.CONFIGURATION_TYPE_EP.extensionList) {
            val factories = t.configurationFactories ?: emptyArray()
            if (factories.isEmpty()) continue
            val id = (runCatching { t.id }.getOrNull() ?: "")
            val display = (runCatching { t.displayName }.getOrNull() ?: "")
            if (id == "PythonConfigurationType") return true
            if (id.contains("python", ignoreCase = true)) return true
            if (display.contains("python", ignoreCase = true)) return true
        }
        return false
    }

    private fun findPythonConfigurationFactory(): com.intellij.execution.configurations.ConfigurationFactory? {
        // Preferred: call PythonConfigurationType.getInstance() if available (no compile-time dependency).
        runCatching {
            val cls = Class.forName("com.jetbrains.python.run.PythonConfigurationType")
            val inst = cls.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }?.invoke(null)
            val type = inst as? ConfigurationType
            val f = type?.configurationFactories?.firstOrNull()
            if (f != null) {
                appendDebug("[runconfig] python type via reflection: ${type.id} factories=${type.configurationFactories.size}\n")
                return f
            }
        }.onFailure {
            appendDebug("[runconfig] reflection PythonConfigurationType failed: ${it.javaClass.simpleName}: ${it.message}\n")
        }

        // Fallback: scan all configuration types and pick the best match.
        val types = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
        data class Candidate(val score: Int, val type: ConfigurationType, val factory: com.intellij.execution.configurations.ConfigurationFactory)
        val candidates = ArrayList<Candidate>()
        for (t in types) {
            val factories = t.configurationFactories ?: emptyArray()
            if (factories.isEmpty()) continue
            val id = (runCatching { t.id }.getOrNull() ?: "").trim()
            val display = (runCatching { t.displayName }.getOrNull() ?: "").trim()
            val scoreBase = when {
                id == "PythonConfigurationType" -> 100
                display.contains("python", ignoreCase = true) -> 70
                id.contains("python", ignoreCase = true) -> 60
                else -> 0
            }
            if (scoreBase <= 0) continue
            for (f in factories) {
                val fn = (runCatching { f.name }.getOrNull() ?: "").trim()
                val score = scoreBase + if (fn.contains("python", ignoreCase = true) || fn == "Python") 10 else 0
                candidates += Candidate(score, t, f)
            }
        }
        val best = candidates.maxByOrNull { it.score }
        if (best != null) {
            appendDebug("[runconfig] python type via scan: ${best.type.id} (${best.type.displayName}) factory='${best.factory.name}'\n")
            return best.factory
        }

        // Diagnostic dump (short) to help users when running in an IDE without Python support.
        val sample = types.take(12).joinToString { "${it.id}:${it.displayName}(${it.configurationFactories.size})" }
        appendDebug("[runconfig] no python factories; sample types: $sample\n")
        return null
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
        val p = formParamsOrNull()
        if (p == null) {
            setStatus("SSH not configured")
            return
        }
        currentParams = p
        setStatus("Monitoring ${buildDest(p)}:${p.port} …")

        // Persist settings
        saveUiToState()
        // OS label async
        Thread {
            val cmd = "(cat /etc/os-release 2>/dev/null | sed -n '1,3p') || uname -a || echo unknown"
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

        applySelectedSshTargetToHeader()
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

    private fun effectiveSshUser(): String {
        return when (currentConnectionMode()) {
            ConnectionMode.MANUAL_SSH -> manualSshUserField.text.trim()
            ConnectionMode.IDE_SSH -> (gatewayTarget?.user ?: "").trim()
        }
    }

    private fun effectiveSshKeyOrNull(): Triple<String, Int, String?>? {
        return when (currentConnectionMode()) {
            ConnectionMode.IDE_SSH -> {
                val gt = gatewayTarget ?: return null
                Triple(gt.host, gt.port, gt.user)
            }
            ConnectionMode.MANUAL_SSH -> {
                val host = manualSshHostField.text.trim()
                val user = manualSshUserField.text.trim().ifEmpty { null }
                val port = manualSshPortField.text.trim().toIntOrNull() ?: 22
                if (host.isBlank()) return null
                Triple(host, port, user)
            }
        }
    }

    private fun formParamsOrNull(): SshParams? {
        val mode = currentConnectionMode()
        val pw = String(sshPasswordField.password).trim().ifEmpty { null }
        return when (mode) {
            ConnectionMode.IDE_SSH -> {
                val gt = gatewayTarget ?: return null
                SshParams(
                    host = gt.host,
                    port = gt.port,
                    username = gt.user,
                    identityFile = null,
                    password = pw,
                    timeoutSec = 30,
                )
            }
            ConnectionMode.MANUAL_SSH -> {
                val host = manualSshHostField.text.trim()
                val port = manualSshPortField.text.trim().toIntOrNull() ?: 22
                val user = manualSshUserField.text.trim()
                if (host.isBlank() || user.isBlank()) return null
                SshParams(
                    host = host,
                    port = port,
                    username = user,
                    identityFile = null,
                    password = pw,
                    timeoutSec = 30,
                )
            }
        }
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
        val mode = currentConnectionMode()
        if (mode == ConnectionMode.IDE_SSH) {
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
                connSummaryLabel.text = "SSH: <not selected>"
                connSummaryLabel.toolTipText = "No IDE SSH config selected"
            }
            return
        }
        if (mode == ConnectionMode.MANUAL_SSH) {
            val user = manualSshUserField.text.trim()
            val host = manualSshHostField.text.trim()
            val port = manualSshPortField.text.trim().toIntOrNull() ?: 22
            val dest = buildString {
                if (user.isNotEmpty()) append(user).append("@")
                append(host.ifEmpty { "<host>" }).append(":").append(port)
            }
            connSummaryLabel.text = "SSH: " + shortenMiddle(dest, 52)
            connSummaryLabel.toolTipText = "Manual: $dest"
            return
        }
    }

    private fun loadSavedPasswordForSelectedTarget() {
        val key = effectiveSshKeyOrNull() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val pw = runCatching { SshPasswordStore.load(key.first, key.second, key.third) }.getOrNull().orEmpty()
            edt {
                // Do not overwrite what user is currently typing with a late async response.
                if (sshPasswordField.password.isEmpty()) {
                    sshPasswordField.text = pw
                }
            }
        }
    }

    private fun applySshPasswordToStoreAndRestart() {
        val key = effectiveSshKeyOrNull() ?: run { restartMonitor(); return }
        val pw = String(sshPasswordField.password).trim().ifEmpty { null }
        // PasswordSafe is a slow operation; never touch it on EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { SshPasswordStore.save(key.first, key.second, key.third, pw) }
        }
        restartMonitor()
    }

    private fun applyManualTargetAndRestart() {
        if (currentConnectionMode() != ConnectionMode.MANUAL_SSH) return
        saveUiToState()
        applySelectedSshTargetToHeader()
        loadSavedPasswordForSelectedTarget()
        restartMonitor()
    }

    private fun startReverseTunnel() {
        if (reverseThread != null) return
        val base = formParamsOrNull()
        if (base == null || base.host.isNullOrBlank()) {
            Messages.showWarningDialog(this, "Configure an SSH target first.", "Reverse Tunnel")
            return
        }
        val bindPort = (reverseBindPortSpin.value as Number).toInt()
        val localPort = (reverseLocalPortSpin.value as Number).toInt()
        if (bindPort !in 1..65535 || localPort !in 1..65535) {
            Messages.showWarningDialog(this, "Invalid ports.", "Reverse Tunnel")
            return
        }

        reverseStopFlag.set(false)
        reverseStartBtn.isEnabled = false
        reverseStopBtn.isEnabled = true
        reverseStatusLabel.text = "Starting…"
        reverseStatusLabel.foreground = JBColor.GRAY

        val dest = buildDest(base) + ":" + base.port

        val t = Thread({
            try {
                appendDebug("[reverse-tunnel] target=$dest bind=127.0.0.1:$bindPort -> localhost:$localPort\n")
                val needAgent = base.password.isNullOrBlank() && base.identityFile.isNullOrBlank()
                if (needAgent) {
                    startReverseTunnelSystemSsh(base, bindPort, localPort)
                } else {
                    startReverseTunnelJsch(base, bindPort, localPort)
                }
            } catch (e: Exception) {
                appendDebug("[reverse-tunnel] error: ${e.message ?: e.javaClass.simpleName}\n")
                edt {
                    reverseStatusLabel.text = "Error: ${e.message ?: e.javaClass.simpleName}"
                    reverseStatusLabel.foreground = JBColor.RED
                    reverseStartBtn.isEnabled = true
                    reverseStopBtn.isEnabled = false
                }
            } finally {
                reverseSession = null
                reverseProc = null
                reverseThread = null
                reverseStopFlag.set(false)
            }
        }, "reverse-tunnel").apply { isDaemon = true }
        reverseThread = t
        t.start()
    }

    private fun startReverseTunnelJsch(base: SshParams, bindPort: Int, localPort: Int) {
        val host = base.host?.trim().orEmpty()
        val user = base.username?.trim().orEmpty()
        if (host.isEmpty() || user.isEmpty()) throw IllegalStateException("host/username required")
        val jsch = com.jcraft.jsch.JSch()
        val sess = jsch.getSession(user, host, base.port)
        if (!base.password.isNullOrBlank()) sess.setPassword(base.password)
        val cfg = java.util.Properties()
        cfg["StrictHostKeyChecking"] = "no"
        cfg["PreferredAuthentications"] = "publickey,password,keyboard-interactive"
        sess.setConfig(cfg)
        val to = (base.timeoutSec * 1000).toInt().coerceAtLeast(5_000)
        sess.timeout = to
        sess.connect(to)
        reverseSession = sess
        try {
            sess.setPortForwardingR("127.0.0.1", bindPort, "127.0.0.1", localPort)
        } catch (e: Exception) {
            try { sess.disconnect() } catch (_: Throwable) {}
            throw e
        }
        edt {
            reverseStatusLabel.text = "Running: $user@$host:${base.port}  -R 127.0.0.1:$bindPort -> localhost:$localPort"
            reverseStatusLabel.foreground = JBColor.foreground()
        }
        while (!reverseStopFlag.get() && sess.isConnected) {
            try { Thread.sleep(250) } catch (_: InterruptedException) {}
        }
        try {
            runCatching { sess.delPortForwardingR("127.0.0.1", bindPort) }
            runCatching { sess.delPortForwardingR(bindPort) }
        } catch (_: Throwable) {}
        try { sess.disconnect() } catch (_: Throwable) {}
        edt {
            reverseStatusLabel.text = "Stopped"
            reverseStatusLabel.foreground = JBColor.GRAY
            reverseStartBtn.isEnabled = true
            reverseStopBtn.isEnabled = false
        }
        appendDebug("[reverse-tunnel] stopped\n")
    }

    private fun startReverseTunnelSystemSsh(base: SshParams, bindPort: Int, localPort: Int) {
        val host = base.host?.trim().orEmpty()
        val user = base.username?.trim().orEmpty()
        if (host.isEmpty() || user.isEmpty()) throw IllegalStateException("host/username required")
        val dest = "$user@$host"
        val cmd = arrayListOf(
            "ssh",
            "-p", base.port.toString(),
            "-o", "ExitOnForwardFailure=yes",
            "-o", "ServerAliveInterval=30",
            "-o", "ServerAliveCountMax=3",
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "BatchMode=yes",
            "-N", "-T",
            "-R", "127.0.0.1:$bindPort:127.0.0.1:$localPort",
            dest,
        )
        appendDebug("[reverse-tunnel] cmd: ${cmd.joinToString(" ")}\n")
        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        val p = pb.start()
        reverseProc = p
        edt {
            reverseStatusLabel.text = "Running (system ssh): $dest:${base.port}  -R 127.0.0.1:$bindPort -> localhost:$localPort"
            reverseStatusLabel.foreground = JBColor.foreground()
        }
        BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8)).use { br ->
            while (!reverseStopFlag.get()) {
                val line = br.readLine() ?: break
                if (line.isNotBlank()) appendDebug("[reverse-tunnel] $line\n")
            }
        }
        if (reverseStopFlag.get()) {
            runCatching { p.destroy() }
        }
        runCatching { p.waitFor() }
        edt {
            reverseStatusLabel.text = "Stopped"
            reverseStatusLabel.foreground = JBColor.GRAY
            reverseStartBtn.isEnabled = true
            reverseStopBtn.isEnabled = false
        }
        appendDebug("[reverse-tunnel] stopped\n")
    }

    private fun stopReverseTunnel() {
        reverseStopFlag.set(true)
        runCatching { reverseProc?.destroy() }
        runCatching {
            val s = reverseSession
            if (s != null) {
                runCatching { s.delPortForwardingR("127.0.0.1", (reverseBindPortSpin.value as Number).toInt()) }
                runCatching { s.delPortForwardingR((reverseBindPortSpin.value as Number).toInt()) }
                runCatching { s.disconnect() }
            }
        }
        edt {
            reverseStatusLabel.text = "Stopping…"
            reverseStatusLabel.foreground = JBColor.GRAY
        }
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
        loadingState = true
        try {
            val st = GpuManagerState.getInstance().state
            intervalSpin.value = st.intervalSec

            val rst = IsaacLabAssistantProjectState.getInstance(project).state
            when (rst.connectionMode) {
                "MANUAL_SSH" -> connectionModeCombo.selectedItem = "Manual SSH"
                else -> connectionModeCombo.selectedItem = "IDE SSH"
            }
            manualSshUserField.text = rst.manualSshUser
            manualSshHostField.text = rst.manualSshHost
            manualSshPortField.text = rst.manualSshPort.toString()
            reverseBindPortSpin.value = rst.reverseTunnelBindPort
            reverseLocalPortSpin.value = rst.reverseTunnelLocalPort

            entryScriptField.text = rst.entryScript
            taskField.text = rst.task
            numEnvsField.text = rst.numEnvs
            headlessCb.isSelected = rst.headless
            livestreamCb.isSelected = rst.livestream
            resumeCb.isSelected = rst.resume
            experimentNameField.text = rst.experimentName
            loadRunField.text = rst.loadRun
            checkpointField.text = rst.checkpoint
            pendingRunnerSelectedGpus = rst.selectedGpus.toSet()

            paramsModel.rowCount = 0
            for (kv in rst.additionalParams) paramsModel.addRow(arrayOf(kv.key, kv.value))
            envModel.rowCount = 0
            for (kv in rst.additionalEnv) envModel.addRow(arrayOf(kv.key, kv.value))

            // Ensure resume detail panel matches state
            val on = resumeCb.isSelected
            experimentNameField.isEnabled = on
            loadRunField.isEnabled = on
            loadRunField.textField.isEnabled = on
            checkpointField.isEnabled = on
            checkpointField.textField.isEnabled = on
            resumeSectionPanel.isVisible = on
        } finally {
            loadingState = false
        }
    }

    private fun saveUiToState() {
        if (loadingState) return
        val st = GpuManagerState.getInstance()
        val s = st.state
        s.intervalSec = (intervalSpin.value as Number).toDouble()

        val rst = IsaacLabAssistantProjectState.getInstance(project)
        val rs = rst.state
        rs.connectionMode = when (currentConnectionMode()) {
            ConnectionMode.MANUAL_SSH -> "MANUAL_SSH"
            else -> "IDE_SSH"
        }
        rs.manualSshUser = manualSshUserField.text.orEmpty()
        rs.manualSshHost = manualSshHostField.text.orEmpty()
        rs.manualSshPort = manualSshPortField.text.trim().toIntOrNull() ?: 22
        rs.reverseTunnelBindPort = (reverseBindPortSpin.value as Number).toInt()
        rs.reverseTunnelLocalPort = (reverseLocalPortSpin.value as Number).toInt()

        rs.entryScript = entryScriptField.textField.text.orEmpty()
        rs.task = taskField.text.orEmpty()
        rs.numEnvs = numEnvsField.text.orEmpty()
        rs.headless = headlessCb.isSelected
        rs.livestream = livestreamCb.isSelected
        rs.resume = resumeCb.isSelected
        rs.experimentName = experimentNameField.text.orEmpty()
        rs.loadRun = loadRunField.textField.text.orEmpty()
        rs.checkpoint = checkpointField.textField.text.orEmpty()
        rs.selectedGpus = getRunnerSelectedGpus().toMutableList()

        rs.additionalParams = MutableList(paramsModel.rowCount) { idx ->
            IsaacLabAssistantProjectState.Kv(
                key = paramsModel.getValueAt(idx, 0)?.toString().orEmpty(),
                value = paramsModel.getValueAt(idx, 1)?.toString().orEmpty(),
            )
        }.filter { it.key.isNotBlank() || it.value.isNotBlank() }.toMutableList()
        rs.additionalEnv = MutableList(envModel.rowCount) { idx ->
            IsaacLabAssistantProjectState.Kv(
                key = envModel.getValueAt(idx, 0)?.toString().orEmpty(),
                value = envModel.getValueAt(idx, 1)?.toString().orEmpty(),
            )
        }.filter { it.key.isNotBlank() || it.value.isNotBlank() }.toMutableList()
    }

}
