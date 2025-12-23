package org.jetbrains.plugins.template.runner

import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import org.jetbrains.plugins.template.gpu.IsaacLabRunner
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.SpinnerNumberModel
import javax.swing.table.DefaultTableModel

class IsaacLabRunConfigurationEditor(private val project: Project) : SettingsEditor<IsaacLabRunConfiguration>() {

    private val hostField = JBTextField(24)
    private val portSpin = JSpinner(SpinnerNumberModel(22, 1, 65535, 1))
    private val userField = JBTextField(18)
    private val identityField = JBTextField(32)

    private val modeCombo = JComboBox(arrayOf("Conda", "Docker"))
    private val condaEnvField = JBTextField(18)
    private val dockerContainerField = JBTextField(18)

    private val taskField = JBTextField(18)
    private val numEnvsSpin = JSpinner(SpinnerNumberModel(1, 1, 65535, 1))
    private val gpuListField = JBTextField(18)

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

    override fun createEditor(): JComponent {
        val root = JPanel(BorderLayout())
        val form = JPanel(GridBagLayout())
        var y = 0
        fun row(label: String, comp: JComponent) {
            form.add(JLabel(label), GridBagConstraints().apply {
                gridx = 0; gridy = y; anchor = GridBagConstraints.LINE_END; insets = Insets(2, 4, 2, 8)
            })
            form.add(comp, GridBagConstraints().apply {
                gridx = 1; gridy = y; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(2, 0, 2, 4)
            })
            y++
        }
        row("Host", hostField)
        row("Port", portSpin)
        row("User", userField)
        row("Identity", identityField)
        row("Mode", modeCombo)
        row("Conda env", condaEnvField)
        row("Docker container", dockerContainerField)
        row("--task", taskField)
        row("--num_envs", numEnvsSpin)
        row("GPUs", gpuListField)
        row("Params", tablePanel(paramsTable, addParamBtn, delParamBtn))
        row("Env", tablePanel(envTable, addEnvBtn, delEnvBtn))

        addParamBtn.addActionListener { addRow(paramsTable, paramsModel) }
        delParamBtn.addActionListener { deleteRows(paramsTable, paramsModel) }
        addEnvBtn.addActionListener { addRow(envTable, envModel) }
        delEnvBtn.addActionListener { deleteRows(envTable, envModel) }

        root.add(form, BorderLayout.NORTH)
        return root
    }

    override fun resetEditorFrom(s: IsaacLabRunConfiguration) {
        val st = s.state
        hostField.text = st.host.orEmpty()
        portSpin.value = st.port
        userField.text = st.username.orEmpty()
        identityField.text = st.identityFile.orEmpty()
        modeCombo.selectedItem = if (st.mode.equals("Docker", ignoreCase = true)) "Docker" else "Conda"
        condaEnvField.text = st.condaEnv.orEmpty()
        dockerContainerField.text = st.dockerContainer.orEmpty()
        taskField.text = st.task.orEmpty()
        numEnvsSpin.value = st.numEnvs
        gpuListField.text = st.gpuList.orEmpty()

        paramsModel.rowCount = 0
        val params = IsaacLabRunner.parseParams(st.extraParamsText.orEmpty()).toMutableList()
        val legacyScript = st.script?.trim().orEmpty()
        if (legacyScript.isNotEmpty() && params.none { (k, _) -> k.trim() == "-p" }) {
            params.add(0, Pair("-p", legacyScript))
        }
        if (params.none { (k, _) -> k.trim() == "-p" }) {
            params.add(0, Pair("-p", ""))
        }
        for ((k, v) in params) {
            paramsModel.addRow(arrayOf(k, v.orEmpty()))
        }

        envModel.rowCount = 0
        for ((k, v) in IsaacLabRunner.parseEnv(st.extraEnvText.orEmpty())) {
            envModel.addRow(arrayOf(k, v))
        }
    }

    override fun applyEditorTo(s: IsaacLabRunConfiguration) {
        val st = s.state
        st.host = hostField.text.trim().ifEmpty { null }
        st.port = (portSpin.value as Number).toInt()
        st.username = userField.text.trim().ifEmpty { null }
        st.identityFile = identityField.text.trim().ifEmpty { null }
        st.mode = (modeCombo.selectedItem?.toString() ?: "Conda")
        st.condaEnv = condaEnvField.text.trim().ifEmpty { null }
        st.dockerContainer = dockerContainerField.text.trim().ifEmpty { null }
        // Migration: this is now represented as `-p=<script>` in Params.
        st.script = null
        st.task = taskField.text.trim().ifEmpty { null }
        st.numEnvs = (numEnvsSpin.value as Number).toInt()
        st.gpuList = gpuListField.text.trim().ifEmpty { null }
        st.extraParamsText = paramsToText()
        st.extraEnvText = envToText()
    }

    private fun tablePanel(table: JTable, addBtn: JButton, delBtn: JButton): JComponent {
        val panel = JPanel(BorderLayout(0, 4))
        val top = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(addBtn)
            add(delBtn)
        }
        panel.add(top, BorderLayout.NORTH)
        panel.add(JScrollPane(table), BorderLayout.CENTER)
        return panel
    }

    private fun addRow(table: JTable, model: DefaultTableModel) {
        model.addRow(arrayOf("", ""))
        val row = model.rowCount - 1
        if (row >= 0) {
            table.editCellAt(row, 0)
            table.changeSelection(row, 0, false, false)
        }
    }

    private fun deleteRows(table: JTable, model: DefaultTableModel) {
        val rows = table.selectedRows ?: return
        if (rows.isEmpty()) return
        for (r in rows.sortedDescending()) {
            if (r >= 0 && r < model.rowCount) model.removeRow(r)
        }
    }

    private fun paramsToText(): String {
        return buildString {
            for (r in 0 until paramsModel.rowCount) {
                val k = (paramsModel.getValueAt(r, 0)?.toString() ?: "").trim()
                val v = (paramsModel.getValueAt(r, 1)?.toString() ?: "").trim()
                if (k.isEmpty()) continue
                if (v.isEmpty()) append(k) else append(k).append('=').append(v)
                append('\n')
            }
        }.trimEnd()
    }

    private fun envToText(): String {
        return buildString {
            for (r in 0 until envModel.rowCount) {
                val k = (envModel.getValueAt(r, 0)?.toString() ?: "").trim()
                val v = (envModel.getValueAt(r, 1)?.toString() ?: "").trim()
                if (k.isEmpty()) continue
                append(k).append('=').append(v)
                append('\n')
            }
        }.trimEnd()
    }
}
