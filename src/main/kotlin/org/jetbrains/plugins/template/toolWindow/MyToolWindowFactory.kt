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
        thisLogger().warn("Sample code present: replacing template with GPU Manager toolwindow UI")
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Notify visibly that the tool window content is being created
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("IsaacLabGPU")
                .createNotification("GPU Manager loaded", NotificationType.INFORMATION)
                .notify(project)
            println("[gpu-manager] createToolWindowContent")
        } catch (e: Exception) {
            thisLogger().warn("notification failed: ${e.message}")
        }
        val panel = GpuManagerPanel()
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
