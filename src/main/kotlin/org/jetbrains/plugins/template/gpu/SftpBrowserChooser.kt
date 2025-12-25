package org.jetbrains.plugins.template.gpu

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

/**
 * Opens JetBrains' built-in SSH SFTP browser (tree UI) via reflection.
 *
 * This is the same tree-style remote browser users see in the IDE, but we can
 * drive it from a ToolWindow without relying on local filesystem choosers.
 */
object SftpBrowserChooser {

    enum class Mode { Directory, PtFile }

    fun choose(
        project: Project,
        params: SshParams,
        initialPath: String,
        mode: Mode,
        onDebug: ((String) -> Unit)? = null,
    ): String? {
        if (params.isLocal()) return null
        val host = params.host?.trim().orEmpty()
        if (host.isEmpty()) return null

        val user = params.username?.trim().orEmpty()
        // ConnectionBuilder host parsing does NOT reliably support the ":port" suffix.
        // We must pass "user@host" and patch the port via withSshConnectionConfig.
        val userHost = buildString {
            if (user.isNotEmpty()) append(user).append("@")
            append(host)
        }
        val titleHostPort = "$userHost:${params.port}"

        val password = params.password?.trim().orEmpty()
        fun dbg(msg: String) { runCatching { onDebug?.invoke(msg) } }
        dbg("open: mode=$mode target=$titleHostPort initial='$initialPath' pw=${if (password.isEmpty()) "empty" else "set"}")

        val cbCls = Class.forName("com.intellij.ssh.ConnectionBuilder")
        val builder = cbCls.getConstructor(String::class.java).newInstance(userHost)

        // Prefer IDE ssh-agent / OpenSSH config when password is empty.
        runCatching {
            cbCls.methods.firstOrNull { it.name == "withParsingOpenSSHConfig" && it.parameterCount == 1 }
                ?.invoke(builder, true)
        }

        // Provide password if user typed one (else return empty so auth can proceed via agent).
        val pwProvider = makePasswordProvider(password)
        runCatching {
            cbCls.methods.firstOrNull { it.name == "withSshPasswordProvider" && it.parameterCount == 1 }
                ?.invoke(builder, pwProvider)
        }
        // Force correct port/user/host (Gateway often uses non-22 ports like 50031).
        runCatching {
            patchPortUserHost(cbCls, builder, host = host, user = user.ifEmpty { null }, port = params.port, dbg = ::dbg)
        }.onFailure {
            dbg("cfg patch failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        runCatching {
            cbCls.methods.firstOrNull { it.name == "withConnectionTimeout" && it.parameterCount == 2 }
                ?.invoke(builder, 10L, TimeUnit.SECONDS)
        }

        // Quick config diag for debugging.
        runCatching {
            val cfg = cbCls.methods.firstOrNull { it.name == "buildConnectionConfig" && it.parameterCount == 0 }?.invoke(builder)
            if (cfg != null) {
                val cfgCls = cfg.javaClass
                val chost = cfgCls.methods.firstOrNull { it.name == "getHost" && it.parameterCount == 0 }?.invoke(cfg) as? String
                val cuser = cfgCls.methods.firstOrNull { it.name == "getUser" && it.parameterCount == 0 }?.invoke(cfg) as? String
                val cport = cfgCls.methods.firstOrNull { it.name == "getPort" && it.parameterCount == 0 }?.invoke(cfg) as? Int
                dbg("cfg: user=${cuser.orEmpty()} host=${chost.orEmpty()} port=${cport ?: -1}")
            }
        }

        val sftpChannel = runCatching {
            cbCls.methods.first { it.name == "openFailSafeSftpChannel" && it.parameterCount == 0 }
                .invoke(builder)
        }.getOrElse {
            dbg("openFailSafeSftpChannel failed: ${it.javaClass.simpleName}: ${it.message}")
            return null
        }

        try {
            val sftpHome = runCatching {
                sftpChannel.javaClass.methods.firstOrNull { it.name == "getHome" && it.parameterCount == 0 }?.invoke(sftpChannel) as? String
            }.getOrNull().orEmpty().trim()

            var effectiveInitial = initialPath.trim()
            if (effectiveInitial.isEmpty()) effectiveInitial = sftpHome.ifEmpty { "/" }
            effectiveInitial = normalizeInitial(effectiveInitial)

            // Canonicalize to match server's view (helps when SFTP has a different root/cwd).
            val canonical = runCatching {
                sftpChannel.javaClass.methods.firstOrNull { it.name == "canonicalize" && it.parameterCount == 1 }
                    ?.invoke(sftpChannel, effectiveInitial) as? String
            }.getOrNull().orEmpty().trim()
            if (canonical.isNotEmpty()) effectiveInitial = normalizeInitial(canonical)

            dbg("sftp: home='${sftpHome.ifEmpty { "<empty>" }}' initialEffective='$effectiveInitial'")

            // Warm up / validate the initial directory so the tree doesn't render empty.
            fun tryLs(path: String): Boolean {
                val p = normalizeInitial(path)
                return runCatching {
                    val ls = sftpChannel.javaClass.methods.firstOrNull { it.name == "ls" && it.parameterCount == 1 }
                        ?.invoke(sftpChannel, p) as? List<*>
                    dbg("sftp: ls('$p') -> ${ls?.size ?: -1}")
                    true
                }.getOrElse {
                    val root = (it as? InvocationTargetException)?.targetException ?: it
                    dbg("sftp: ls('$p') failed: ${root.javaClass.simpleName}: ${root.message}")
                    false
                }
            }

            val okInitial = tryLs(effectiveInitial)
            if (!okInitial && sftpHome.isNotEmpty()) {
                if (tryLs(sftpHome)) effectiveInitial = normalizeInitial(sftpHome)
            }

            val provider = runCatching {
                val providerCls = Class.forName("com.intellij.ssh.ui.sftpBrowser.SftpRemoteBrowserProvider")
                providerCls.getConstructor(Class.forName("com.intellij.ssh.channels.SftpChannel")).newInstance(sftpChannel)
            }.getOrElse {
                dbg("SftpRemoteBrowserProvider init failed: ${it.javaClass.simpleName}: ${it.message}")
                return null
            }

            val dlgCls = runCatching {
                Class.forName("com.intellij.ssh.ui.sftpBrowser.RemoteBrowserDialog")
            }.getOrElse {
                dbg("RemoteBrowserDialog class not found: ${it.javaClass.simpleName}: ${it.message}")
                return null
            }

            // IMPORTANT: pick the real 6-arg ctor, not the synthetic (8-arg) Kotlin default-args ctor.
            val ctor = dlgCls.constructors.firstOrNull { it.parameterCount == 6 } ?: run {
                dbg("RemoteBrowserDialog ctor(6) not found; ctors=${dlgCls.constructors.joinToString { it.parameterCount.toString() }}")
                return null
            }

            // Signature from javap:
            // RemoteBrowserDialog(RemoteBrowserProvider, Project, boolean, hostName, initialPath, boolean)
            // - hostName is shown in the title (SshBundle)
            // - initialPath is passed to RemoteBrowser(...) as the initial directory
            val directoriesOnly = mode == Mode.Directory
            val allowCreate = mode == Mode.Directory
            val dialog = ctor.newInstance(provider, project, directoriesOnly, titleHostPort, effectiveInitial, allowCreate)

            val showAndGet = dlgCls.methods.first { it.name == "showAndGet" && it.parameterCount == 0 }
            val ok = runCatching { (showAndGet.invoke(dialog) as? Boolean) == true }.getOrDefault(false)
            if (!ok) return null

            val getResult = dlgCls.methods.first { it.name == "getResult" && it.parameterCount == 0 }
            val result = (getResult.invoke(dialog) as? String)?.trim().orEmpty()
            if (result.isEmpty()) return null
            dbg("picked: '$result'")
            return result
        } finally {
            runCatching { (sftpChannel as? java.io.Closeable)?.close() }
            runCatching { sftpChannel.javaClass.methods.firstOrNull { it.name == "close" && it.parameterCount == 0 }?.invoke(sftpChannel) }
        }
    }

    private fun makePasswordProvider(password: String): Any {
        val cls = Class.forName("com.intellij.ssh.interaction.SshPasswordProvider")
        return Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { _, method, args ->
            when (method.name) {
                "getUnixPassword" -> password.ifEmpty { null }
                "getKeyPassphrase" -> password.ifEmpty { null }
                "getKeyboardInteractive" -> {
                    if (password.isEmpty()) return@newProxyInstance null
                    val prompts = (args?.getOrNull(2) as? Array<*>)?.size ?: 1
                    Array(prompts.coerceAtLeast(1)) { password }
                }
                else -> null
            }
        }
    }

    private fun patchPortUserHost(
        cbCls: Class<*>,
        builder: Any,
        host: String,
        user: String?,
        port: Int,
        dbg: (String) -> Unit,
    ) {
        val fn1 = Class.forName("kotlin.jvm.functions.Function1")
        val mod = Proxy.newProxyInstance(fn1.classLoader, arrayOf(fn1)) { _, method, args ->
            if (method.name != "invoke" || args.isNullOrEmpty()) return@newProxyInstance null
            val cfg = args[0] ?: return@newProxyInstance null
            val cfgCls = cfg.javaClass
            val get = { name: String -> cfgCls.methods.first { it.name == name && it.parameterCount == 0 }.invoke(cfg) }
            val copy = cfgCls.methods.firstOrNull { it.name == "copy" && it.parameterCount == 19 }
                ?: return@newProxyInstance cfg

            val authMethods = get("getAuthMethods")
            val ciphers = get("getCiphers")
            val compression = get("getCompression")
            val connectTimeout = get("getConnectTimeout")
            val forwardAgent = get("getForwardAgent")
            val envVars = get("getEnvironmentVariables")
            val hostKeyAlgs = get("getHostKeyAlgorithms")
            val hostKeyVerifier = get("getHostKeyVerifier")
            val identityAgent = get("getIdentityAgent")
            val initialLocalTcpForwardings = get("getInitialLocalTcpForwardings")
            val initialRemoteTcpForwardings = get("getInitialRemoteTcpForwardings")
            val kexAlgorithms = get("getKexAlgorithms")
            val macs = get("getMacs")
            val proxyConfig = get("getProxyConfig")
            val serverAlive = get("getServerAlive")
            val x11Forwarding = get("getX11Forwarding")
            val userFinal = user ?: (get("getUser") as? String)
            val hostFinal = host
            val portFinal = port

            val patched = copy.invoke(
                cfg,
                authMethods,
                ciphers,
                compression,
                connectTimeout,
                forwardAgent,
                envVars,
                hostFinal,
                hostKeyAlgs,
                hostKeyVerifier,
                identityAgent,
                initialLocalTcpForwardings,
                initialRemoteTcpForwardings,
                kexAlgorithms,
                macs,
                portFinal,
                proxyConfig,
                serverAlive,
                userFinal,
                x11Forwarding,
            )
            patched
        }

        cbCls.methods.firstOrNull { it.name == "withSshConnectionConfig" && it.parameterCount == 1 }
            ?.invoke(builder, mod)
            ?: dbg("withSshConnectionConfig not found; cannot patch port")
    }

    private fun normalizeInitial(path: String): String {
        val p = path.trim().ifEmpty { "/" }
        return if (p.startsWith("/")) p else "/$p"
    }
}
