package org.jetbrains.plugins.template.toolWindow

import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import org.jetbrains.plugins.template.gpu.GpuManagerPanel

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
        val panel = GpuManagerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
