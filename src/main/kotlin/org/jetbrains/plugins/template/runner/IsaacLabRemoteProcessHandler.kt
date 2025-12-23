package org.jetbrains.plugins.template.runner

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import org.jetbrains.plugins.template.gpu.IsaacLabRunner
import org.jetbrains.plugins.template.gpu.IsaacLabRunnerSpec
import org.jetbrains.plugins.template.gpu.SshExec
import org.jetbrains.plugins.template.gpu.SshParams
import java.util.concurrent.atomic.AtomicBoolean

class IsaacLabRemoteProcessHandler(
    private val ssh: SshParams,
    private val spec: IsaacLabRunnerSpec,
) : ProcessHandler() {

    private val stopped = AtomicBoolean(false)
    private val exec = SshExec(ssh)

    override fun startNotify() {
        super.startNotify()
        Thread({ runRemote() }, "isaaclab-runner").apply { isDaemon = true }.start()
    }

    private fun runRemote() {
        try {
            notifyTextAvailable("[isaaclab] connecting to ${ssh.username ?: ""}${if (!ssh.username.isNullOrBlank()) "@" else ""}${ssh.host}:${ssh.port}\n", ProcessOutputTypes.SYSTEM)

            val preview = IsaacLabRunner.buildPreviewCommands(spec)
            notifyTextAvailable("[isaaclab] preview:\n" + preview.joinToString("\n") + "\n", ProcessOutputTypes.SYSTEM)

            if (stopped.get()) return

            val runLine = preview.firstOrNull().orEmpty()
            if (runLine.isNotBlank()) {
                val (rc, out, _) = exec.run(runLine)
                if (out.isNotBlank()) notifyTextAvailable(out.trimEnd() + "\n", ProcessOutputTypes.STDOUT)
                notifyTextAvailable("[isaaclab] finished rc=$rc\n", ProcessOutputTypes.SYSTEM)
            }

            notifyProcessTerminated(0)
        } catch (t: Throwable) {
            notifyTextAvailable("[isaaclab] error: ${t.message ?: t.javaClass.simpleName}\n", ProcessOutputTypes.STDERR)
            notifyProcessTerminated(1)
        } finally {
            exec.close()
        }
    }

    override fun destroyProcessImpl() {
        stopped.set(true)
        exec.close()
        notifyProcessTerminated(0)
    }

    override fun detachProcessImpl() {
        stopped.set(true)
        exec.close()
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): java.io.OutputStream? = null
}
