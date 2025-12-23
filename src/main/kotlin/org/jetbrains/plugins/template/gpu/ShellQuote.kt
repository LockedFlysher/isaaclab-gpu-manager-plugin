package org.jetbrains.plugins.template.gpu

/**
 * POSIX shell single-quote escaping.
 * Produces a string safe to embed as one shell word.
 */
fun shQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

