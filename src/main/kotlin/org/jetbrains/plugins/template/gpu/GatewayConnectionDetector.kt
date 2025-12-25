package org.jetbrains.plugins.template.gpu

import com.intellij.openapi.project.Project

data class DetectedGatewayTarget(
    val host: String,
    val port: Int,
    val user: String?,
    val source: String,
)

/**
 * Best-effort detector for JetBrains Gateway / Remote Dev SSH target from the frontend process,
 * using IDE's SSH config APIs (no direct reading of cached XML files).
 *
 * Why: In some setups EEL remains local (LocalEelDescriptor), so we fallback to system ssh (agent)
 * using the same host/port/user as your configured IDE SSH connection (e.g. non-22 port like 50031).
 */
object GatewayConnectionDetector {

    fun listTargets(project: Project, onDebug: ((String) -> Unit)? = null): List<DetectedGatewayTarget> {
        val dbg: (String) -> Unit = { msg -> try { onDebug?.invoke(msg) } catch (_: Throwable) {} }
        val cls = runCatching { Class.forName("com.intellij.ssh.config.unified.SshConfigManager") }.getOrNull()
        if (cls == null) {
            dbg("ssh-configs: class com.intellij.ssh.config.unified.SshConfigManager not found")
            return emptyList()
        }
        val mgr = runCatching {
            val m = cls.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 1 && it.parameterTypes[0].name == Project::class.java.name }
                ?: throw IllegalStateException("getInstance(Project) not found")
            m.invoke(null, project)
        }.getOrNull()
        if (mgr == null) {
            dbg("ssh-configs: SshConfigManager.getInstance(project) returned null")
            return emptyList()
        }
        val cfgs = runCatching {
            val m = mgr.javaClass.methods.firstOrNull { it.name == "getConfigs" && it.parameterCount == 0 }
                ?: throw IllegalStateException("getConfigs() not found")
            (m.invoke(mgr) as? List<*>) ?: emptyList<Any>()
        }.getOrNull() ?: emptyList()

        fun getStr(o: Any, method: String): String? =
            runCatching { o.javaClass.methods.firstOrNull { it.name == method && it.parameterCount == 0 }?.invoke(o) as? String }.getOrNull()

        fun getInt(o: Any, method: String): Int? =
            runCatching { o.javaClass.methods.firstOrNull { it.name == method && it.parameterCount == 0 }?.invoke(o) as? Int }.getOrNull()

        val out = ArrayList<DetectedGatewayTarget>()
        for (c in cfgs) {
            val obj = c ?: continue
            val host = (getStr(obj, "getHost") ?: "").trim()
            val port = getInt(obj, "getPort") ?: 0
            val user = (getStr(obj, "getUsername") ?: "").trim().ifEmpty { null }
            val name = (getStr(obj, "getPresentableFullName") ?: getStr(obj, "getName") ?: "").trim()
            if (host.isEmpty() || port !in 1..65535) continue
            out += DetectedGatewayTarget(
                host = host,
                port = port,
                user = user,
                source = if (name.isNotEmpty()) "IDE SSH config: $name" else "IDE SSH config",
            )
        }
        dbg("ssh-configs: usable=${out.size}")
        return out
    }
}
