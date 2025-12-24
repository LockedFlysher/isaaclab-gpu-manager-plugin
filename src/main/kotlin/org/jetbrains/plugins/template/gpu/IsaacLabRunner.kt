package org.jetbrains.plugins.template.gpu

data class IsaacLabRunnerSpec(
    val entryScript: String = "",
    val task: String = "",
    val numEnvs: Int? = null,
    val headless: Boolean = false,
    val resume: Boolean = false,
    val experimentName: String = "",
    val loadRun: String = "",
    val checkpoint: String = "",
    val livestream: Boolean = false,
    val extraParams: List<Pair<String, String?>> = emptyList(),
    val extraEnv: List<Pair<String, String>> = emptyList(),
    val gpuList: List<Int> = emptyList(),
)

object IsaacLabRunner {

    fun parseParams(text: String): List<Pair<String, String?>> {
        val out = ArrayList<Pair<String, String?>>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('=')
            if (eq < 0) {
                out += Pair(line, null)
            } else {
                val k = line.substring(0, eq).trim()
                val v = line.substring(eq + 1).trim()
                if (k.isNotEmpty()) out += Pair(k, v)
            }
        }
        return out
    }

    fun parseEnv(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('=')
            if (eq < 0) return@forEach
            val k = line.substring(0, eq).trim()
            val v = line.substring(eq + 1).trim()
            if (k.isNotEmpty()) out += Pair(k, v)
        }
        return out
    }

    fun buildPythonCmd(spec: IsaacLabRunnerSpec): String {
        val nproc = spec.gpuList.size
        val useTorchrun = nproc > 1

        val (pValue, extraNoP) = run {
            var v: String? = null
            val rest = ArrayList<Pair<String, String?>>()
            for ((k0, v0) in spec.extraParams) {
                val k = k0.trim()
                if (k == "-p") {
                    if (v == null) v = v0?.trim()
                    continue
                }
                rest += Pair(k0, v0)
            }
            val fromField = spec.entryScript.trim()
            Pair(if (fromField.isNotEmpty()) fromField else v.orEmpty(), rest)
        }

        // Validate "known" fields (we intentionally avoid quoting in previews)
        requireShellSafeToken("-p", pValue, allowEmpty = false)
        if (spec.task.isNotBlank()) requireShellSafeToken("--task", spec.task)
        val nenv = spec.numEnvs
        if (nenv != null && nenv > 0) requireShellSafeToken("--num_envs", nenv.toString())
        if (spec.resume) {
            if (spec.experimentName.isNotBlank()) requireShellSafeToken("--experiment_name", spec.experimentName)
            if (spec.loadRun.isNotBlank()) requireShellSafeToken("--load_run", spec.loadRun)
            if (spec.checkpoint.isNotBlank()) requireShellSafeToken("--checkpoint", spec.checkpoint)
        }

        val parts = ArrayList<String>()
        parts += "./isaaclab.sh"
        parts += "-p"
        if (useTorchrun) {
            parts += listOf("-m", "torch.distributed.run", "--nnodes=1", "--nproc_per_node=${maxOf(1, nproc)}")
        }
        parts += if (pValue.isNotBlank()) shQuoteIfNeeded(pValue) else "<python_entry>"

        val preset = ArrayList<Pair<String, String?>>()
        if (spec.task.isNotBlank()) preset += Pair("task", spec.task.trim())
        if (nenv != null && nenv > 0) preset += Pair("num_envs", nenv.toString())
        if (spec.headless) preset += Pair("headless", null)
        if (spec.resume) {
            preset += Pair("resume", null)
            if (spec.experimentName.isNotBlank()) preset += Pair("experiment_name", spec.experimentName.trim())
            if (spec.loadRun.isNotBlank()) preset += Pair("load_run", spec.loadRun.trim())
            if (spec.checkpoint.isNotBlank()) preset += Pair("checkpoint", spec.checkpoint.trim())
        }

        val filteredExtra = extraNoP.filterNot { (k, _) ->
            val kk = k.trim().removePrefix("--")
            kk == "task" ||
                kk == "num_envs" ||
                kk == "headless" ||
                kk == "resume" ||
                kk == "experiment_name" ||
                kk == "load_run" ||
                kk == "checkpoint"
        }
        val merged = preset + filteredExtra

        var haveDistributed = false
        for ((k0, v0) in merged) {
            val key0 = k0.trim()
            if (key0.isEmpty()) continue
            if (key0 == "distributed" || key0 == "--distributed") haveDistributed = true
            requireShellSafeParamKey("param key", key0)
            val isShortOpt = key0.startsWith("-") && !key0.startsWith("--")
            val key = when {
                key0.startsWith("--") -> key0
                isShortOpt -> key0
                else -> "--$key0"
            }
            if (v0.isNullOrEmpty()) {
                parts += key
            } else if (isShortOpt) {
                requireShellSafeToken("param value", v0)
                parts += key
                parts += shQuoteIfNeeded(v0)
            } else {
                requireShellSafeToken("param value", v0)
                parts += "$key=${shQuoteIfNeeded(v0)}"
            }
        }
        if (useTorchrun && !haveDistributed) parts += "--distributed"
        return parts.joinToString(" ")
    }

    fun buildPreviewCommands(spec: IsaacLabRunnerSpec): List<String> {
        val envDict = LinkedHashMap<String, String>()

        // Overlay CUDA_VISIBLE_DEVICES based on selected GPUs
        if (spec.gpuList.isNotEmpty()) {
            val cuda = spec.gpuList.joinToString(",")
            requireShellSafeToken("CUDA_VISIBLE_DEVICES", cuda)
            envDict["CUDA_VISIBLE_DEVICES"] = cuda
        }
        if (spec.livestream) {
            envDict["LIVESTREAM"] = "2"
        }
        for ((k, v) in spec.extraEnv) {
            val kk = k.trim()
            if (kk.isEmpty()) continue
            if (kk.equals("CUDA_VISIBLE_DEVICES", ignoreCase = true)) continue
            if (kk.equals("LIVESTREAM", ignoreCase = true) && spec.livestream) continue
            requireShellSafeEnvKey("env key", kk)
            requireShellSafeToken("env value", v, allowEmpty = true)
            envDict[kk] = v
        }

        fun envInlinePrefix(): String {
            val pairs = ArrayList<String>()
            for ((k, v) in envDict) {
                val kk = k.trim()
                if (kk.isEmpty()) continue
                pairs += "${kk}=${shQuoteIfNeeded(v)}"
            }
            return pairs.joinToString(" ")
        }

        val basePy = buildPythonCmd(spec)
        val envPrefix = envInlinePrefix()
        val pyLine = if (envPrefix.isNotEmpty()) "$envPrefix $basePy" else basePy
        // IDE/interpreter should manage env activation (conda/docker); we only output the run command.
        return listOf(pyLine)
    }
}
