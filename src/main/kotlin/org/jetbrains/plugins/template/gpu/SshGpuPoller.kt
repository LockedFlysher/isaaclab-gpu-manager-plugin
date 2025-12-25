package org.jetbrains.plugins.template.gpu

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background poller that fetches GPU info over SSH (or locally) and builds Snapshots.
 *
 * Important: all remote commands run through [SshExec] using an interactive PTY-backed SSH shell,
 * matching a manual SSH session so `nvidia-smi` is available when it would be available manually.
 */
class SshGpuPoller(
    private val params: SshParams,
    private val project: com.intellij.openapi.project.Project?,
    private val intervalSec: Double = 5.0,
    private val listener: Listener,
) : Thread("gpu-poller") {

    interface Listener {
        fun onSnapshot(s: Snapshot)
        fun onError(msg: String)
        fun onDebug(msg: String)
    }

    private val stopFlag = AtomicBoolean(false)
    private val exec = SshExec(params, project, onDebug = { listener.onDebug(it) })
    @Volatile private var baselineCache: List<GpuL> = emptyList()

    fun requestStop() { stopFlag.set(true) }

    private fun preview(s: String, limit: Int = 240): String {
        val t = s.trimEnd()
        val oneLine = t.replace("\r", "\\r").replace("\n", "\\n")
        return if (oneLine.length <= limit) oneLine else oneLine.take(limit) + "…"
    }

    private fun runRemote(cmd: String): Triple<Int, String, String> {
        listener.onDebug("$ $cmd")
        val (rc, out, err) = exec.run(cmd)
        val se = err.trimEnd()
        val so = out.trimEnd()
        if (so.isNotBlank()) listener.onDebug("stdout: ${preview(so)}")
        if (se.isNotBlank()) listener.onDebug("stderr: ${preview(se)}")
        listener.onDebug("rc=$rc bytes=${out.length}")
        return Triple(rc, out, err)
    }

    private fun fetchOnce(): Snapshot {
        val errors = mutableListOf<String>()
        val smi = "nvidia-smi"

        val (rcList, outList, errList) = runRemote("LC_ALL=C LANG=C $smi -L")
        if (rcList != 0) errors += ("nvidia-smi -L failed (rc=$rcList): " + errList.trim().ifBlank { outList.trim() }.take(300))
        val parsedBase = parseGpuListL(outList)
        if (parsedBase.isNotEmpty()) baselineCache = parsedBase
        val baseline = if (baselineCache.isNotEmpty()) baselineCache else parsedBase

        val (rc1, outGpus, err1) = runRemote(
            "LC_ALL=C LANG=C $smi --query-gpu=index,name,uuid,utilization.gpu,memory.total,memory.used --format=csv,noheader,nounits"
        )
        if (rc1 != 0) errors += ("nvidia-smi query failed (rc=$rc1): " + err1.trim().ifBlank { outGpus.trim() }.take(300))

        val gpusParsed = parseGpuCsv(outGpus)
        var snap = Snapshot(gpusParsed, emptyList(), emptyMap(), emptyMap(), errors)

        // If baseline detected and metrics returned fewer GPUs, pad to baseline.
        if (baseline.isNotEmpty()) {
            val byIndex = snap.gpus.associateBy { it.index }
            if (byIndex.size < baseline.size) {
                val padded = baseline.sortedBy { it.index }.map { b ->
                    byIndex[b.index] ?: GpuInfo(
                        index = b.index,
                        name = b.name,
                        uuid = b.uuid.ifBlank { "${b.index}" },
                        utilPercent = 0,
                        memTotalMiB = 0,
                        memUsedMiB = 0,
                    )
                }
                snap = snap.copy(gpus = padded)
            }
        }

        if (snap.gpus.isEmpty() && errors.isEmpty()) {
            errors += "no GPUs detected (empty nvidia-smi output)"
            snap = snap.copy(errors = errors)
        }

        return snap
    }

    override fun run() {
        // Warm-up: try a few quick cycles to get baseline populated fast.
        var warmTries = 0
        while (warmTries < 3 && !stopFlag.get()) {
            try {
                val s = fetchOnce()
                if (s.errors.isNotEmpty()) listener.onError(s.errors.joinToString("; "))
                listener.onSnapshot(s)
                if (s.gpus.isNotEmpty() && baselineCache.isNotEmpty()) break
            } catch (e: Exception) {
                listener.onError(e.message ?: e.javaClass.simpleName)
            }
            warmTries++
            try { sleep(800) } catch (_: InterruptedException) {}
        }


        while (!stopFlag.get()) {
            try {
                val s = fetchOnce()
                if (s.errors.isNotEmpty()) listener.onError(s.errors.joinToString("; "))
                listener.onSnapshot(s)
            } catch (e: Exception) {
                listener.onError(e.message ?: e.javaClass.simpleName)
            }
            val sleepMs = (intervalSec * 1000.0).toLong().coerceAtLeast(200L)
            var elapsed = 0L
            while (elapsed < sleepMs && !stopFlag.get()) {
                try { sleep(100) } catch (_: InterruptedException) {}
                elapsed += 100
            }
        }
        exec.close()
    }
}
