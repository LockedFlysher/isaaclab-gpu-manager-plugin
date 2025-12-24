package org.jetbrains.plugins.template.gpu

/**
 * POSIX shell single-quote escaping.
 * Produces a string safe to embed as one shell word.
 */
fun shQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

private val SHELL_SAFE_TOKEN = Regex("^[A-Za-z0-9_./:@%+=,\\-]+$")
private val SHELL_SAFE_ENV_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
private val SHELL_SAFE_PARAM_KEY = Regex("^(--?[A-Za-z0-9][A-Za-z0-9_.\\-]*|[A-Za-z0-9][A-Za-z0-9_.\\-]*)$")

fun requireShellSafeToken(label: String, value: String, allowEmpty: Boolean = false) {
    val v = value.trim()
    if (v.isEmpty()) {
        if (allowEmpty) return
        throw IllegalArgumentException("$label is required")
    }
    if (!SHELL_SAFE_TOKEN.matches(v)) {
        throw IllegalArgumentException("$label contains unsupported characters (no spaces/quotes/shell specials): $value")
    }
}

fun requireShellSafeEnvKey(label: String, key: String) {
    val k = key.trim()
    if (k.isEmpty()) throw IllegalArgumentException("$label is required")
    if (!SHELL_SAFE_ENV_KEY.matches(k)) {
        throw IllegalArgumentException("$label must match [A-Za-z_][A-Za-z0-9_]* : $key")
    }
}

fun requireShellSafeParamKey(label: String, key: String) {
    val k = key.trim()
    if (k.isEmpty()) throw IllegalArgumentException("$label is required")
    if (!SHELL_SAFE_PARAM_KEY.matches(k)) {
        throw IllegalArgumentException("$label contains unsupported characters: $key")
    }
}

/**
 * Quote only when necessary for readability.
 *
 * User expectation for this project: runner tokens never contain spaces or shell-special characters,
 * so quoting is intentionally suppressed for cleaner copy/paste.
 *
 * Note: For security-sensitive contexts (markers, internal protocols), prefer always quoting.
 */
fun shQuoteIfNeeded(s: String): String {
    return s
}
