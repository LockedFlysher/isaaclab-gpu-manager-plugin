package org.jetbrains.plugins.template.toolWindow

import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import org.jetbrains.plugins.template.gpu.GpuManagerPanel
import java.awt.BorderLayout
import javax.swing.BorderFactory

class MyToolWindowFactory : ToolWindowFactory {

    init {
        thisLogger().info("IsaacLab Assistant toolwindow factory initialized")
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("IsaacLabAssistant")
                .createNotification("IsaacLab Assistant loaded", NotificationType.INFORMATION)
                .notify(project)
        } catch (e: Exception) {
            thisLogger().warn("notification failed: ${e.message}")
        }
        try {
            val panel = GpuManagerPanel(project)
            val content = ContentFactory.getInstance().createContent(panel, null, false)
            toolWindow.contentManager.addContent(content)
        } catch (t: Throwable) {
            thisLogger().error("Failed to create IsaacLab Assistant panel", t)
            val root = javax.swing.JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            }
            root.add(JBLabel("IsaacLab Assistant failed to initialize. Check IDE logs for details.").apply {
                foreground = JBColor.RED
            }, BorderLayout.NORTH)
            val area = JBTextArea().apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                text = buildString {
                    append(t.javaClass.name).append(": ").append(t.message ?: "").append("\n\n")
                    append(t.stackTraceToString())
                }
            }
            root.add(JBScrollPane(area), BorderLayout.CENTER)
            val content = ContentFactory.getInstance().createContent(root, null, false)
            toolWindow.contentManager.addContent(content)
        }
    }

    override fun shouldBeAvailable(project: Project) = true
}
