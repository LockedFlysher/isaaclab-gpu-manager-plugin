package org.jetbrains.plugins.template.gpu

// Minimal parser/DTOs mirroring gpu_manager_gui/nvidia_parser.py

data class GpuInfo(
    val index: Int,
    val name: String,
    val uuid: String,
    val utilPercent: Int,
    val memTotalMiB: Int,
    val memUsedMiB: Int,
)

data class ComputeApp(
    val gpuUuid: String,
    val pid: Int,
    val processName: String,
    val usedMemoryMiB: Int,
)

data class Snapshot(
    val gpus: List<GpuInfo>,
    val apps: List<ComputeApp>,
    val pidUserMap: Map<Int, String>,
    val userVramMiB: Map<String, Int>,
    val errors: List<String> = emptyList(),
)

private fun toIntSafe(s: String): Int {
    val t = s.trim()
    t.toIntOrNull()?.let { return it }
    // handle values like "N/A", "0 %", "123 MiB"
    val digits = t.filter { it.isDigit() || it == '-' }
    return digits.toIntOrNull() ?: 0
}

fun parseGpuCsv(csv: String): List<GpuInfo> {
    if (csv.isBlank()) return emptyList()
    val out = ArrayList<GpuInfo>()
    csv.lineSequence().forEach { line ->
        val ln = line.trim()
        if (ln.isEmpty()) return@forEach
        val parts = ln.split(',').map { it.trim() }
        if (parts.size < 6) return@forEach
        val idx = parts.getOrNull(0)?.toIntOrNull() ?: return@forEach
        val name = parts.getOrNull(1) ?: return@forEach
        val uuid = parts.getOrNull(2) ?: return@forEach
        val util = toIntSafe(parts.getOrNull(3) ?: "0")
        val total = toIntSafe(parts.getOrNull(4) ?: "0")
        val used = toIntSafe(parts.getOrNull(5) ?: "0")
        out += GpuInfo(
            index = idx,
            name = name,
            uuid = uuid,
            utilPercent = util.coerceIn(0, 100),
            memTotalMiB = if (total < 0) 0 else total,
            memUsedMiB = if (used < 0) 0 else used,
        )
    }
    return out
}

fun parseComputeAppsCsv(csv: String): List<ComputeApp> {
    if (csv.isBlank()) return emptyList()
    val out = ArrayList<ComputeApp>()
    csv.lineSequence().forEach { line ->
        val ln = line.trim()
        if (ln.isEmpty()) return@forEach
        val parts = ln.split(',').map { it.trim() }
        if (parts.size < 4) return@forEach
        val gpuUuid = parts.getOrNull(0) ?: return@forEach
        val pid = parts.getOrNull(1)?.toIntOrNull() ?: return@forEach
        val proc = parts.getOrNull(2) ?: ""
        val mem = toIntSafe(parts.getOrNull(3) ?: "0")
        out += ComputeApp(gpuUuid = gpuUuid, pid = pid, processName = proc, usedMemoryMiB = if (mem < 0) 0 else mem)
    }
    return out
}

fun parsePsPidUser(ps: String): Map<Int, String> {
    if (ps.isBlank()) return emptyMap()
    val out = LinkedHashMap<Int, String>()
    ps.lineSequence().forEach { line ->
        val ln = line.trim()
        if (ln.isEmpty()) return@forEach
        val parts = ln.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return@forEach
        val pid = parts[0].toIntOrNull() ?: return@forEach
        val user = parts.last()
        out[pid] = user
    }
    return out
}

fun aggregateUserVram(apps: List<ComputeApp>, pidUser: Map<Int, String>): Map<String, Int> {
    if (apps.isEmpty()) return emptyMap()
    val out = HashMap<String, Int>()
    for (a in apps) {
        val user = pidUser[a.pid] ?: "unknown"
        out[user] = (out[user] ?: 0) + a.usedMemoryMiB
    }
    return out
}

fun summarize(gpuCsv: String, appsCsv: String, psText: String): Snapshot {
    val gpus = parseGpuCsv(gpuCsv)
    val apps = parseComputeAppsCsv(appsCsv)
    val pidUser = parsePsPidUser(psText)
    val totals = aggregateUserVram(apps, pidUser)
    return Snapshot(gpus = gpus, apps = apps, pidUserMap = pidUser, userVramMiB = totals, errors = emptyList())
}

// Very small helper to parse `nvidia-smi pmon -c 1` for fallback when compute-apps is empty.
// Returns list of (gpuIndex, pid, processName, fbMiB)
data class PmonRow(val gpuIndex: Int, val pid: Int, val processName: String, val fbMiB: Int)

fun parsePmon(text: String): List<PmonRow> {
    if (text.isBlank()) return emptyList()
    val rows = ArrayList<PmonRow>()
    var header: List<String>? = null
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@forEach
        if (line.startsWith("#")) {
            header = line.removePrefix("#").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return@forEach
        }
        val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts[0] == "#" || parts.getOrNull(1) == "-") return@forEach
        val gpuIdx = parts[0].toIntOrNull() ?: return@forEach
        val pid = parts[1].toIntOrNull() ?: return@forEach
        var name = parts.lastOrNull() ?: ""
        var fb = 0
        val h = header
        if (h != null) {
            val fbIdx = h.indexOf("fb")
            if (fbIdx >= 0 && fbIdx < parts.size) {
                fb = parts[fbIdx].toIntOrNull() ?: 0
            } else {
                val memIdx = h.indexOf("mem")
                if (memIdx >= 0 && memIdx < parts.size) fb = parts[memIdx].toIntOrNull() ?: 0
            }
        }
        rows += PmonRow(gpuIndex = gpuIdx, pid = pid, processName = name, fbMiB = if (fb < 0) 0 else fb)
    }
    return rows
}

// Parse `nvidia-smi -L` baseline list
data class GpuL(val index: Int, val name: String, val uuid: String)

fun parseGpuListL(text: String): List<GpuL> {
    if (text.isBlank()) return emptyList()
    val out = ArrayList<GpuL>()
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        // Format: GPU 0: Tesla V100-SXM2-16GB (UUID: GPU-xxxx)
        if (!line.startsWith("GPU ")) return@forEach
        val idxColon = line.indexOf(':')
        if (idxColon <= 4) return@forEach
        val idxStr = line.substring(4, idxColon).trim()
        val rest = line.substring(idxColon + 1).trim()
        val idx = idxStr.toIntOrNull() ?: return@forEach
        val name: String
        val uuid: String
        val uuidStart = rest.indexOf("(UUID:")
        if (uuidStart >= 0) {
            name = rest.substring(0, uuidStart).trim()
            val uuidEnd = rest.indexOf(')', uuidStart)
            val uuidPart = if (uuidEnd > uuidStart) rest.substring(uuidStart + 6, uuidEnd) else rest.substring(uuidStart + 6)
            var u = uuidPart.replace("UUID:", "").trim()
            if (!u.startsWith("GPU-")) u = "GPU-" + u
            uuid = u
        } else {
            name = rest
            uuid = ""
        }
        out += GpuL(index = idx, name = name, uuid = uuid)
    }
    return out
}
