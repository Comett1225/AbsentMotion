package app.absentmotion.model

fun String.finiteFloat(): Float? = toFloatOrNull()?.takeIf { it.isFinite() }

fun String.finiteDouble(): Double? = toDoubleOrNull()?.takeIf { it.isFinite() }
