package app.absentmotion.export

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.SecureRandom

class ExportName(private val projectTitle: String) {
    var value by mutableStateOf(projectTitle)
    var random by mutableStateOf(false)

    fun next(kind: ExportKind): String = if (random && kind.randomNameAllowed) {
        buildString {
            append(nameRandom.nextInt(9) + 1)
            repeat(15) { append(nameRandom.nextInt(10)) }
        }
    } else clean(value).ifEmpty { clean(projectTitle).ifEmpty { "Project" } }

    private fun clean(value: String): String = buildString {
        var bytes = 0
        for (c in value) {
            if (!c.isLetterOrDigit() && c !in "_-.+, ") continue
            val size = when { c.code < 0x80 -> 1; c.code < 0x800 -> 2; else -> 3 }
            if (bytes + size > 180 || length == 120) break
            append(c)
            bytes += size
        }
    }.trim(' ', '.')
}

val ExportKind.randomNameAllowed: Boolean
    get() = this == ExportKind.Video || this == ExportKind.Frame || this == ExportKind.FrameAs || this == ExportKind.Gif || this == ExportKind.Sequence

private val nameRandom by lazy { SecureRandom() }
