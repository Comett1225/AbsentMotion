package app.absentmotion.ui.editor.panels

private val Ignored = setOf('\u066C', '\u00A0', '\u202F', ' ', '\u200F', '\u200E', '\u061C')

fun keypadChar(c: Char): Char? = when (c) {
    '.', ',', '\u066B' -> '.'
    '-', '\u2212', '\u2012', '\u2013' -> '-'
    else -> c.digitToIntOrNull()?.let { '0' + it }
}

fun keypadNumber(text: String): Float? {
    val plain = text.trim().filterNot { it in Ignored }.map { keypadChar(it) ?: return null }.joinToString("")
    if (plain.isEmpty() || plain == "-" || plain.count { it == '.' } > 1 || plain.lastIndexOf('-') > 0) return null
    return plain.toFloatOrNull()?.takeIf { it.isFinite() }
}

fun keypadValue(text: String, fallback: Float, min: Float, max: Float): Float = (keypadNumber(text) ?: fallback).coerceIn(min, max)

fun keypadOutside(text: String, min: Float, max: Float): Boolean = keypadNumber(text)?.let { it < min || it > max } == true

fun keypadRange(min: Float, max: Float, unit: String): String? =
    if (min <= -Float.MAX_VALUE / 2 || max >= Float.MAX_VALUE / 2 || min > max) null else "${formatPlain(min)}\u2013${formatPlain(max)}$unit"
