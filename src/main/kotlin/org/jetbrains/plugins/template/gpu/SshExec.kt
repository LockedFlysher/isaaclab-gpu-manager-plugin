package org.jetbrains.plugins.template.gpu

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.BufferedReader
import java.io.OutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.TimeUnit

data class SshParams(
    val host: String?,
    val port: Int = 22,
    val username: String? = null,
    val identityFile: String? = null,
    val password: String? = null,
    val timeoutSec: Long = 10,
) {
    fun isLocal(): Boolean {
        val h = (host ?: "").trim()
        return h.isEmpty() || h == "localhost" || h == "127.0.0.1"
    }
}

class SshExec(private val params: SshParams) {

    @Volatile private var jsch: JSch? = null
    @Volatile private var session: Session? = null
    @Volatile private var shell: ChannelShell? = null
    @Volatile private var shellIn: OutputStream? = null
    @Volatile private var shellOut: java.io.InputStream? = null

    fun close() {
        try { shell?.disconnect() } catch (_: Throwable) {}
        shell = null
        shellIn = null
        shellOut = null
        try { session?.disconnect() } catch (_: Throwable) {}
        session = null
    }

    fun run(remoteCmd: String): Triple<Int, String, String> {
        return if (params.isLocal()) runLocal(remoteCmd) else runViaSsh(remoteCmd)
    }

    private fun runLocal(cmd: String): Triple<Int, String, String> {
        val full = listOf("bash", "-lc", cmd)
        return runProcess(full)
    }

    private fun runViaSsh(remoteCmd: String): Triple<Int, String, String> {
        // Always use JSch with an interactive shell channel (PTY) to match behavior of an interactive SSH session.
        return runViaJschShell(remoteCmd)
    }

    private fun ensureShell(): Pair<OutputStream, java.io.InputStream> {
        val existingShell = shell
        val existingSession = session
        if (existingSession != null && existingSession.isConnected && existingShell != null && existingShell.isConnected) {
            val i = shellIn
            val o = shellOut
            if (i != null && o != null) return Pair(i, o)
        }

        close()

        val host = (params.host ?: "").trim()
        if (host.isEmpty()) throw IllegalStateException("host is required")
        val user = (params.username ?: "").trim().ifEmpty { System.getProperty("user.name") ?: "" }
        if (user.isEmpty()) throw IllegalStateException("username is required")

        val j = jsch ?: JSch().also { jsch = it }
        if (!params.identityFile.isNullOrBlank()) {
            try { j.addIdentity(params.identityFile) } catch (_: Throwable) {}
        }

        val sess = j.getSession(user, host, params.port)
        if (!params.password.isNullOrEmpty()) sess.setPassword(params.password)
        val cfg = Properties()
        cfg["StrictHostKeyChecking"] = "no"
        cfg["PreferredAuthentications"] = "publickey,password,keyboard-interactive"
        sess.setConfig(cfg)
        val to = (params.timeoutSec * 1000).toInt()
        sess.timeout = to
        sess.connect(to)
        session = sess

        val sh = sess.openChannel("shell") as ChannelShell
        sh.setPty(true)
        try { sh.setPtyType("dumb", 120, 30, 0, 0) } catch (_: Throwable) {}
        sh.connect(to)
        shell = sh
        val input = sh.outputStream
        val output = sh.inputStream
        shellIn = input
        shellOut = output
        return Pair(input, output)
    }

    private fun runViaJschShell(remoteCmd: String): Triple<Int, String, String> {
        return try {
            val (input, output) = ensureShell()
            // Drain any pending prompt/banner output so markers are unambiguous.
            try {
                val drainBuf = ByteArray(4096)
                while (output.available() > 0) {
                    val n = output.read(drainBuf)
                    if (n < 0) break
                }
            } catch (_: Throwable) {}

            val token = "__GPU_MGR_${System.nanoTime()}__"
            val begin = "$token:BEGIN"
            val end = "$token:END:"
            // ChannelShell doesn't provide a clean stdout/stderr split; capture a marker-delimited transcript.
            val wrapped = (
                "printf %s\\\\n ${shQuote(begin)}; " +
                "{ $remoteCmd; RC=${'$'}?; } 2>&1; " +
                "printf %s%s\\\\n ${shQuote(end)} ${'$'}RC"
            )
            input.write((wrapped + "\n").toByteArray(StandardCharsets.UTF_8))
            input.flush()

            val sb = StringBuilder()
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + params.timeoutSec * 1000
            while (System.currentTimeMillis() < deadline) {
                while (output.available() > 0) {
                    val n = output.read(buf)
                    if (n < 0) break
                    sb.append(String(buf, 0, n, StandardCharsets.UTF_8))
                }
                val text = sb.toString()
                val bi = text.indexOf(begin)
                if (bi >= 0) {
                    val ei = text.indexOf(end, bi + begin.length)
                    if (ei >= 0) {
                        val lf = text.indexOf('\n', ei)
                        val afterEnd = if (lf >= 0) lf else text.length
                        if (afterEnd > ei) {
                            val rcStr = text.substring(ei + end.length, afterEnd).trim().trimEnd('\r')
                            val rc = rcStr.toIntOrNull() ?: 0
                            val bodyStart = text.indexOf('\n', bi)
                            val body = if (bodyStart >= 0 && bodyStart + 1 <= ei) {
                                text.substring(bodyStart + 1, ei).trimEnd()
                            } else ""
                            return Triple(rc, body, "")
                        }
                    }
                }
                Thread.sleep(20)
            }
            Triple(124, "", "command timed out")
        } catch (e: Exception) {
            close()
            Triple(1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun runProcess(cmd: List<String>): Triple<Int, String, String> {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(false)
            val p = pb.start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val err = BufferedReader(InputStreamReader(p.errorStream)).use { it.readText() }
            if (!p.waitFor(params.timeoutSec, TimeUnit.SECONDS)) {
                try { p.destroyForcibly() } catch (_: Throwable) {}
                Triple(124, out, if (err.isNotBlank()) err else "command timed out")
            } else {
                Triple(p.exitValue(), out, err)
            }
        } catch (e: Exception) {
            Triple(1, "", e.message ?: e.javaClass.simpleName)
        }
    }
}
