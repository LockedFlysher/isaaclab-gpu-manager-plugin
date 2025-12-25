package org.jetbrains.plugins.template.gpu

import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.WeakHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Run shell commands on the current IDE "project target" (local IDE => local machine; Gateway => remote backend)
 * using the IntelliJ Platform EEL (Execution Environment Layer).
 *
 * This works in Remote Dev without installing any backend plugin.
 */
object EelBash {

    private val eelCache = WeakHashMap<Project, Any>()

    @Synchronized
    private fun eel(project: Project): Any {
        return eelCache[project] ?: run {
            val api = runCatching { toEelApiBlocking(getEelDescriptor(project)) }.getOrElse { e ->
                throw IllegalStateException("EEL unavailable: ${e.message}", e)
            }
            eelCache[project] = api
            api
        }
    }

    fun describeTarget(project: Project): String {
        return runCatching {
            val d = getEelDescriptor(project)
            d.javaClass.methods.firstOrNull { it.name == "getUserReadableDescription" && it.parameterCount == 0 }
                ?.invoke(d)
                ?.toString()
        }.getOrNull() ?: "IDE target"
    }

    fun run(project: Project, bashScript: String, timeoutSec: Long): Triple<Int, String, String> {
        val api = runCatching { eel(project) }.getOrElse { e -> return Triple(1, "", e.message ?: "EEL unavailable") }

        val exec = runCatching { api.javaClass.methods.first { it.name == "getExec" && it.parameterCount == 0 }.invoke(api) }
            .getOrElse { e -> return Triple(1, "", e.message ?: "EEL exec unavailable") }

        val builder = runCatching { eelExecuteBuilder(exec, "bash") }.getOrElse { e ->
            return Triple(1, "", e.message ?: "EEL execute unavailable")
        }

        // builder.args("-lc", bashScript)
        runCatching {
            builder.javaClass.methods.firstOrNull { it.name == "args" && it.parameterCount == 2 && it.parameterTypes.all { t -> t == String::class.java } }
                ?.invoke(builder, "-lc", bashScript)
        }

        val eelResult = runSuspend(timeoutSec) { cont ->
            val m = builder.javaClass.methods.first { it.name == "eelIt" && it.parameterCount == 1 }
            m.invoke(builder, cont)
        } ?: return Triple(124, "", "command timed out")

        val clsOk = runCatching { Class.forName("com.intellij.platform.eel.EelResult\$Ok") }.getOrNull()
        val clsErr = runCatching { Class.forName("com.intellij.platform.eel.EelResult\$Error") }.getOrNull()
        if (clsErr != null && clsErr.isInstance(eelResult)) {
            val err = runCatching { clsErr.methods.first { it.name == "getError" && it.parameterCount == 0 }.invoke(eelResult) }.getOrNull()
            return Triple(1, "", (err ?: "execute failed").toString())
        }
        val okCls = clsOk ?: return Triple(1, "", "execute failed")
        if (!okCls.isInstance(eelResult)) return Triple(1, "", "execute failed")

        val eelProcess = runCatching { okCls.methods.first { it.name == "getValue" && it.parameterCount == 0 }.invoke(eelResult) }.getOrNull()
            ?: return Triple(1, "", "execute failed")

        val javaProc = runCatching {
            eelProcess.javaClass.methods.first { it.name == "convertToJavaProcess" && it.parameterCount == 0 }.invoke(eelProcess) as Process
        }.getOrNull() ?: return Triple(1, "", "execute failed (no java process)")

        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val tOut = Thread { outBuf.append(javaProc.inputStream.readBytes().toString(StandardCharsets.UTF_8)) }.apply { isDaemon = true }
        val tErr = Thread { errBuf.append(javaProc.errorStream.readBytes().toString(StandardCharsets.UTF_8)) }.apply { isDaemon = true }
        tOut.start()
        tErr.start()

        val ok = javaProc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!ok) {
            runCatching { javaProc.destroyForcibly() }
            return Triple(124, outBuf.toString(), errBuf.toString().ifBlank { "command timed out" })
        }
        tOut.join(200)
        tErr.join(200)
        return Triple(javaProc.exitValue(), outBuf.toString(), errBuf.toString())
    }

    private fun getEelDescriptor(project: Project): Any {
        val cls = Class.forName("com.intellij.platform.eel.provider.EelProviderUtil")
        val m = cls.methods.first { it.name == "getEelDescriptor" && it.parameterCount == 1 && it.parameterTypes[0].name == Project::class.java.name }
        return m.invoke(null, project)
    }

    private fun toEelApiBlocking(descriptor: Any): Any {
        val cls = Class.forName("com.intellij.platform.eel.provider.EelProviderUtil")
        val eelDescriptorCls = Class.forName("com.intellij.platform.eel.EelDescriptor")
        val m = cls.methods.first { it.name == "toEelApiBlocking" && it.parameterCount == 1 && it.parameterTypes[0].name == eelDescriptorCls.name }
        return m.invoke(null, descriptor)
    }

    private fun eelExecuteBuilder(execApi: Any, exe: String): Any {
        val cls = Class.forName("com.intellij.platform.eel.EelExecApiHelpersKt")
        val eelExecApiCls = Class.forName("com.intellij.platform.eel.EelExecApi")
        val m = cls.methods.first { it.name == "execute" && it.parameterCount == 2 && it.parameterTypes[0].name == eelExecApiCls.name && it.parameterTypes[1] == String::class.java }
        return m.invoke(null, execApi, exe)
    }

    private fun <T> runSuspend(timeoutSec: Long, call: (Continuation<T>) -> Any?): T? {
        var out: Result<T>? = null
        val latch = CountDownLatch(1)
        val cont = object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                out = result
                latch.countDown()
            }
        }
        try {
            call(cont)
        } catch (e: Throwable) {
            return null
        }
        val ok = latch.await(timeoutSec, TimeUnit.SECONDS)
        if (!ok) return null
        return out?.getOrNull()
    }
}
