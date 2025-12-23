package org.jetbrains.plugins.template.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.template.MyBundle
import java.io.BufferedReader
import java.io.InputStreamReader

@Service(Service.Level.PROJECT)
class MyProjectService(project: Project) {

    init {
        thisLogger().info(MyBundle.message("projectService", project.name))
        thisLogger().warn("Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.")
    }

    /**
     * Returns a human-readable summary of GPU usage using `nvidia-smi` if available.
     * Falls back to an explanatory message when the command is not found or fails.
     */
    fun getGpuUsageText(): String {
        val query = "nvidia-smi --query-gpu=index,name,memory.total,memory.used,utilization.gpu --format=csv,noheader,nounits"
        // Run via a shell so the user's PATH is respected (esp. conda/docker envs)
        val command = listOf("bash", "-lc", query)
        return try {
            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val output = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val exitCode = p.waitFor()
            if (exitCode != 0) {
                return "nvidia-smi exited with code $exitCode. Output:\n$output"
            }

            val lines = output
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()

            if (lines.isEmpty()) {
                return "No GPUs detected or empty output from nvidia-smi."
            }

            val header = "GPU Usage (index | name | util% | used/total MiB)\n"
            val body = lines.joinToString(separator = "\n") { line ->
                // Expected: index,name,memory.total,memory.used,utilization.gpu
                val parts = line.split(',').map { it.trim() }
                if (parts.size < 5) return@joinToString line
                val index = parts[0]
                val name = parts[1]
                val total = parts[2]
                val used = parts[3]
                val util = parts[4]
                "#%s | %s | %s%% | %s/%s".format(index, name, util, used, total)
            }
            header + body
        } catch (e: Exception) {
            "Failed to run nvidia-smi: ${e.message}. Is NVIDIA driver installed and `nvidia-smi` in PATH?"
        }
    }
}
