package app.absentmotion.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.absentmotion.fonts.FontCatalog
import app.absentmotion.fonts.FontLibrary
import app.absentmotion.fonts.FontName
import app.absentmotion.library.AppSettings
import app.absentmotion.platform.LocalContext
import java.io.File

object UiFonts {
    fun bundled(): List<FontName> = FontCatalog.entries.filter { it.bundled && it.name.weight == 400 && !it.name.italic }.map { it.name }.distinctBy { it.family }

    fun files(font: FontName?, resolve: (FontName) -> File?): List<Pair<File, Int>> {
        if (font == null) return emptyList()
        val regular = resolve(font.copy(weight = 400, italic = false)) ?: return emptyList()
        val bold = if (font.source == "imported") null else resolve(font.copy(weight = 700, italic = false))?.takeIf { it != regular }
        return listOf(regular to 400) + listOfNotNull(bold?.let { it to 700 })
    }

    fun family(files: List<Pair<File, Int>>): FontFamily? =
        if (files.isEmpty()) null else runCatching { FontFamily(files.map { (file, weight) -> Font(file, FontWeight(weight)) }) }.getOrNull()

    fun typography(base: Typography, family: FontFamily): Typography = base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = family), displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family), headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family), headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family), titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family), bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family), bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family), labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}

val LocalUiFont = staticCompositionLocalOf<FontFamily?> { null }

@Composable
fun TextStyle.withUiFont(): TextStyle = LocalUiFont.current?.takeIf { fontFamily == null }?.let { copy(fontFamily = it) } ?: this

@Composable
fun UiFontHost(settings: AppSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val name = settings.uiFont
    val family = remember(name) {
        name?.let { chosen -> val library = FontLibrary(context); UiFonts.family(UiFonts.files(FontName.parse(chosen)) { library.renderFile(it.xml) }) }
    }
    UiFontTheme(family, content)
}

@Composable
fun UiFontTheme(family: FontFamily?, content: @Composable () -> Unit) {
    val indication = LocalIndication.current
    val selection = LocalTextSelectionColors.current
    val style = LocalTextStyle.current
    val base = MaterialTheme.typography
    val typography = remember(base, family) { if (family == null) base else UiFonts.typography(base, family) }
    MaterialTheme(typography = typography) {
        CompositionLocalProvider(
            LocalIndication provides indication,
            LocalTextSelectionColors provides selection,
            LocalTextStyle provides if (family == null) style else style.copy(fontFamily = family),
            LocalUiFont provides family,
            content = content,
        )
    }
}
