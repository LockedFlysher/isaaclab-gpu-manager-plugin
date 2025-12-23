package org.jetbrains.plugins.template.gpu

enum class IsaacLabRunMode { Conda, Docker }

data class IsaacLabRunnerSpec(
    val mode: IsaacLabRunMode,
    val condaEnv: String = "",
    val dockerContainer: String = "",
    val task: String = "",
    val numEnvs: Int? = null,
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
            Pair(v.orEmpty(), rest)
        }

        val parts = ArrayList<String>()
        parts += "./isaaclab.sh"
        parts += "-p"
        if (useTorchrun) {
            parts += listOf("-m", "torch.distributed.run", "--nnodes=1", "--nproc_per_node=${maxOf(1, nproc)}")
        }
        parts += if (pValue.isNotBlank()) shQuote(pValue) else "<python_entry>"

        val preset = ArrayList<Pair<String, String?>>()
        if (spec.task.isNotBlank()) preset += Pair("task", spec.task.trim())
        val nenv = spec.numEnvs
        if (nenv != null && nenv > 0) preset += Pair("num_envs", nenv.toString())

        val filteredExtra = extraNoP.filterNot { (k, _) ->
            val kk = k.trim().removePrefix("--")
            kk == "task" || kk == "num_envs"
        }
        val merged = preset + filteredExtra

        var haveDistributed = false
        for ((k0, v0) in merged) {
            val key0 = k0.trim()
            if (key0.isEmpty()) continue
            if (key0 == "distributed" || key0 == "--distributed") haveDistributed = true
            val isShortOpt = key0.startsWith("-") && !key0.startsWith("--")
            val key = when {
                key0.startsWith("--") -> key0
                isShortOpt -> key0
                else -> "--$key0"
            }
            if (v0.isNullOrEmpty()) {
                parts += key
            } else if (isShortOpt) {
                parts += key
                parts += shQuote(v0)
            } else {
                parts += "$key=${shQuote(v0)}"
            }
        }
        if (useTorchrun && !haveDistributed) parts += "--distributed"
        return parts.joinToString(" ")
    }

    fun buildPreviewCommands(spec: IsaacLabRunnerSpec): List<String> {
        val envDict = LinkedHashMap<String, String>()

        // Overlay CUDA_VISIBLE_DEVICES based on selected GPUs
        if (spec.gpuList.isNotEmpty()) {
            envDict["CUDA_VISIBLE_DEVICES"] = spec.gpuList.joinToString(",")
        }
        for ((k, v) in spec.extraEnv) {
            val kk = k.trim()
            if (kk.isEmpty()) continue
            if (kk.equals("CUDA_VISIBLE_DEVICES", ignoreCase = true)) continue
            envDict[kk] = v
        }

        fun envInlinePrefix(): String {
            val pairs = ArrayList<String>()
            for ((k, v) in envDict) {
                val kk = k.trim()
                if (kk.isEmpty()) continue
                pairs += "${kk}=${shQuote(v)}"
            }
            return pairs.joinToString(" ")
        }

        fun condaLine(): String {
            val envQ = shQuote(spec.condaEnv.trim().ifEmpty { "base" })
            return (
                "for p in \"\$HOME/miniconda3/etc/profile.d/conda.sh\" \"\$HOME/anaconda3/etc/profile.d/conda.sh\" /opt/conda/etc/profile.d/conda.sh; " +
                "do [ -f \"\$p\" ] && . \"\$p\" && break; done; " +
                "ENV=$envQ; case \"\$ENV\" in miniconda3|anaconda3|miniforge3|mambaforge|micromamba|\"\" ) ENV=base;; esac; " +
                "conda activate \"\$ENV\" 2>/dev/null || conda activate base 2>/dev/null"
            )
        }

        val basePy = buildPythonCmd(spec)
        val envPrefix = envInlinePrefix()
        val pyLine = if (envPrefix.isNotEmpty()) "$envPrefix $basePy" else basePy

        return when (spec.mode) {
            IsaacLabRunMode.Docker -> {
                val name = spec.dockerContainer.trim()
                val enter = if (name.isNotEmpty()) "docker exec -it $name bash -l" else "docker exec -it <container> bash -l"
                listOf(enter, pyLine)
            }
            IsaacLabRunMode.Conda -> listOf(condaLine(), pyLine)
        }
    }
}
