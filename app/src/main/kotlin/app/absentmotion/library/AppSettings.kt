package app.absentmotion.library

import app.absentmotion.R
import app.absentmotion.i18n.UiText
import app.absentmotion.i18n.txt
import app.absentmotion.platform.Context
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Palette
import app.absentmotion.ui.theme.Themes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class AppSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val demoModeShown = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    private var previewQualityState by mutableStateOf(PreviewQuality.of(prefs.getString("previewQuality", null), prefs.getBoolean("lowQualityPreview", false)))
    private var defaultLayerDurationState by mutableIntStateOf(prefs.getInt("defaultLayerDuration", 2))
    private var sortByState by mutableStateOf(prefs.getString("sortBy", "date")!!)
    private var audioResamplingState by mutableIntStateOf(prefs.getInt("audioResampling", 2))
    private var demoModeState by mutableStateOf(prefs.getBoolean("demoMode", false))
    private var loopingPlayState by mutableStateOf(prefs.getBoolean("loopingPlay", false))
    private var xmlUploadState by mutableStateOf(prefs.getBoolean("xmlUpload", false))
    private var vibrationState by mutableStateOf(prefs.getBoolean("trimLimitVibration", true))
    private var basicTransformState by mutableStateOf(prefs.getBoolean("basicTransform", false))

    private var themeState by mutableStateOf(prefs.getString("theme", Themes.DEFAULT)!!)
    private var customThemeState by mutableStateOf(Themes.custom(context)?.let { it.name ?: "" })

    init { Am.palette = Themes.palette(context, themeState) }

    private var savedColoursState by mutableStateOf(
        prefs.getString("savedColours", null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: listOf(0x264653, 0x2A9D8F, 0xE9C46A, 0xE76F51),
    )

    var previewQuality: PreviewQuality
        get() = previewQualityState
        set(value) { previewQualityState = value; prefs.edit().putString("previewQuality", value.name).apply() }

    var defaultLayerDuration: Int
        get() = defaultLayerDurationState
        set(value) { defaultLayerDurationState = value; prefs.edit().putInt("defaultLayerDuration", value).apply() }

    var audioResampling: Int
        get() = audioResamplingState
        set(value) { audioResamplingState = value; prefs.edit().putInt("audioResampling", value).apply() }

    var demoMode: Boolean
        get() = demoModeState && demoModeShown
        set(value) { demoModeState = value; prefs.edit().putBoolean("demoMode", value).apply() }

    var loopingPlay: Boolean
        get() = loopingPlayState
        set(value) { loopingPlayState = value; prefs.edit().putBoolean("loopingPlay", value).apply() }

    var xmlUpload: Boolean
        get() = xmlUploadState
        set(value) { xmlUploadState = value; prefs.edit().putBoolean("xmlUpload", value).apply() }

    var basicTransform: Boolean
        get() = basicTransformState
        set(value) { basicTransformState = value; prefs.edit().putBoolean("basicTransform", value).apply() }

    var vibration: Boolean
        get() = vibrationState
        set(value) { vibrationState = value; prefs.edit().putBoolean("trimLimitVibration", value).apply() }

    private var transitionInPreviewState by mutableStateOf(prefs.getBoolean("transitionInPreview", false))

    var transitionInPreview: Boolean
        get() = transitionInPreviewState
        set(value) { transitionInPreviewState = value; prefs.edit().putBoolean("transitionInPreview", value).apply() }

    private var androidEmojiState by mutableStateOf(prefs.getBoolean(app.absentmotion.fonts.FontLibrary.ANDROID_EMOJI, false))

    var androidEmoji: Boolean
        get() = androidEmojiState
        set(value) { androidEmojiState = value; prefs.edit().putBoolean(app.absentmotion.fonts.FontLibrary.ANDROID_EMOJI, value).apply() }

    private var previewCopiesState by mutableStateOf(prefs.getBoolean(app.absentmotion.media.VideoProxies.SETTING, true))

    var previewCopies: Boolean
        get() = previewCopiesState
        set(value) {
            previewCopiesState = value
            prefs.edit().putBoolean(app.absentmotion.media.VideoProxies.SETTING, value).apply()
            app.absentmotion.media.VideoProxies.settingChanged()
        }

    private var fullMotionBlurState by mutableStateOf(prefs.getBoolean("fullMotionBlur", false))

    var fullMotionBlur: Boolean
        get() = fullMotionBlurState
        set(value) { fullMotionBlurState = value; prefs.edit().putBoolean("fullMotionBlur", value).apply() }

    private var previewCacheHintState by mutableStateOf(prefs.getBoolean("previewCacheHint", true))

    var previewCacheHint: Boolean
        get() = previewCacheHintState
        set(value) { previewCacheHintState = value; prefs.edit().putBoolean("previewCacheHint", value).apply() }

    private var keepMediaCopiesState by mutableStateOf(prefs.getBoolean("keepMediaCopies", true))

    var keepMediaCopies: Boolean
        get() = keepMediaCopiesState
        set(value) { keepMediaCopiesState = value; prefs.edit().putBoolean("keepMediaCopies", value).apply() }

    var soundView: app.absentmotion.audio.SoundView
        get() = app.absentmotion.audio.SoundView.parse(prefs.getString("soundView", null))
        set(value) { prefs.edit().putString("soundView", value.text).apply() }

    private var weeklyBackupState by mutableStateOf(prefs.getBoolean("weeklyBackup", false))

    var weeklyBackup: Boolean
        get() = weeklyBackupState
        set(value) { weeklyBackupState = value; prefs.edit().putBoolean("weeklyBackup", value).apply() }

    private var checkUpdatesState by mutableStateOf(prefs.getBoolean("checkUpdates", true))
    private var updatesAskedState by mutableStateOf(prefs.getBoolean("updatesAsked", false))

    val checkUpdates: Boolean get() = checkUpdatesState
    val updatesAsked: Boolean get() = updatesAskedState
    val autoUpdateCheck: Boolean get() = updatesAskedState && checkUpdatesState

    fun chooseUpdates(on: Boolean) {
        checkUpdatesState = on
        updatesAskedState = true
        prefs.edit().putBoolean("checkUpdates", on).putBoolean("updatesAsked", true).apply()
    }

    private var newestVersionState by mutableStateOf(prefs.getString("newestVersion", null))
    private var newestLinkState by mutableStateOf(prefs.getString("newestLink", null))

    val newestVersion: String? get() = newestVersionState
    val newestLink: String? get() = newestLinkState

    fun rememberNewest(version: String, link: String) {
        newestVersionState = version
        newestLinkState = link
        prefs.edit().putString("newestVersion", version).putString("newestLink", link).apply()
    }

    var lastAutoBackup: Long
        get() = prefs.getLong("lastAutoBackup", 0L)
        set(value) { prefs.edit().putLong("lastAutoBackup", value).apply() }

    var autoBackupUri: String?
        get() = prefs.getString("autoBackupUri", null)
        set(value) { prefs.edit().putString("autoBackupUri", value).apply() }

    var autoBackupFailed: Boolean
        get() = prefs.getBoolean("autoBackupFailed", false)
        set(value) { prefs.edit().putBoolean("autoBackupFailed", value).apply() }

    var theme: String
        get() = themeState
        set(value) {
            themeState = value
            prefs.edit().putString("theme", value).apply()
            Am.palette = Themes.palette(context, value)
        }

    val themes: List<Pair<String, UiText>>
        get() = Palette.BuiltIn.map { it.first to builtInThemeName(it.first) } +
            listOfNotNull(customThemeState?.let { Themes.CUSTOM to if (it.isEmpty()) txt(R.string.theme_custom) else txt(R.string.theme_custom_named, it) })

    val themeLabel: UiText get() = themes.firstOrNull { it.first == themeState }?.second ?: builtInThemeName(Themes.DEFAULT)

    private fun builtInThemeName(id: String) = when (id) {
        "Daylight" -> txt(R.string.theme_daylight)
        else -> txt(R.string.theme_absentmotion)
    }

    fun importTheme(text: String) {
        val imported = Themes.import(context, text)
        customThemeState = imported.name ?: ""
        theme = Themes.CUSTOM
    }

    var savedColours: List<Int>
        get() = savedColoursState
        set(value) { savedColoursState = value; prefs.edit().putString("savedColours", value.joinToString(",")).apply() }

    var templateHints: Int
        get() = prefs.getInt("templateHints", 0)
        set(value) { prefs.edit().putInt("templateHints", value).apply() }

    var onboarding: Int
        get() = prefs.getInt("onboarding", 0)
        set(value) { prefs.edit().putInt("onboarding", value).apply() }

    private var tutorialStepState by mutableStateOf(prefs.getString("tutorialStep", null))

    var tutorialStep: String?
        get() = tutorialStepState
        set(value) { tutorialStepState = value; prefs.edit().putString("tutorialStep", value).apply() }

    var tutorialProject: String?
        get() = prefs.getString("tutorialProject", null)
        set(value) { prefs.edit().putString("tutorialProject", value).apply() }

    var tutorialAsk: Int
        get() = prefs.getInt("tutorialAsk", 0)
        set(value) { prefs.edit().putInt("tutorialAsk", value).apply() }

    var sortBy: String
        get() = sortByState
        set(value) { sortByState = value; prefs.edit().putString("sortBy", value).apply() }

    var newProject: NewProjectChoice
        get() = NewProjectChoice(
            prefs.getString("np.aspect", "1:1")!!, prefs.getInt("np.resolution", 1080),
            prefs.getInt("np.customWidth", 1280), prefs.getInt("np.customHeight", 720),
            prefs.getInt("np.fps", 60), prefs.getInt("np.background", 0),
        )
        set(v) = prefs.edit().putString("np.aspect", v.aspect).putInt("np.resolution", v.resolution)
            .putInt("np.customWidth", v.customWidth).putInt("np.customHeight", v.customHeight)
            .putInt("np.fps", v.fps).putInt("np.background", v.background).apply()

    companion object {
        val LAYER_DURATIONS = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30, 45, 60)
    }
}

enum class PreviewQuality(val divisor: Int, @StringRes val label: Int) {
    Full(1, R.string.settings_preview_full), Half(2, R.string.settings_preview_half), Quarter(4, R.string.settings_preview_quarter);

    companion object {
        fun of(stored: String?, draft: Boolean): PreviewQuality = entries.firstOrNull { it.name == stored } ?: if (draft) Half else Full
    }
}

data class NewProjectChoice(
    val aspect: String,
    val resolution: Int,
    val customWidth: Int,
    val customHeight: Int,
    val fps: Int,
    val background: Int,
)
