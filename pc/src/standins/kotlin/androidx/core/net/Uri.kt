package androidx.core.net

fun String.toUri(): android.net.Uri = android.net.Uri.parse(this)
fun java.io.File.toUri(): android.net.Uri = android.net.Uri.fromFile(this)
fun android.net.Uri.toFile(): java.io.File = toFile() ?: throw IllegalArgumentException("Not a file: $this")
