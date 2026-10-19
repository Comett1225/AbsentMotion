package app.absentmotion.library

import app.absentmotion.platform.Context
import app.absentmotion.ui.theme.Am
import app.absentmotion.ui.theme.Palette
import app.absentmotion.ui.theme.Themes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class AppSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private var lowQualityPreviewState by mutableStateOf(prefs.getBoolean("lowQualityPreview", false))
    private var defaultLayerDurationState by mutableIntStateOf(prefs.getInt("defaultLayerDuration", 2))
    private var sortByState by mutableStateOf(prefs.getString("sortBy", "date")!!)
    private var audioResamplingState by mutableIntStateOf(prefs.getInt("audioResampling", 2))
    private var demoModeState by mutableStateOf(prefs.getBoolean("demoMode", false))
    private var loopingPlayState by mutableStateOf(prefs.getBoolean("loopingPlay", false))
    private var xmlUploadState by mutableStateOf(prefs.getBoolean("xmlUpload", false))
    private var trimLimitVibrationState by mutableStateOf(prefs.getBoolean("trimLimitVibration", true))

    private var themeState by mutableStateOf(prefs.getString("theme", Themes.DEFAULT)!!)
    private var customThemeState by mutableStateOf(Themes.custom(context)?.let { it.name ?: "" })

    init { Am.palette = Themes.palette(context, themeState) }

    private var savedColoursState by mutableStateOf(
        prefs.getString("savedColours", null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: listOf(0x264653, 0x2A9D8F, 0xE9C46A, 0xE76F51),
    )

    var lowQualityPreview: Boolean
        get() = lowQualityPreviewState
        set(value) { lowQualityPreviewState = value; prefs.edit().putBoolean("lowQualityPreview", value).apply() }

    var defaultLayerDuration: Int
        get() = defaultLayerDurationState
        set(value) { defaultLayerDurationState = value; prefs.edit().putInt("defaultLayerDuration", value).apply() }

    var audioResampling: Int
        get() = audioResamplingState
        set(value) { audioResamplingState = value; prefs.edit().putInt("audioResampling", value).apply() }

    var demoMode: Boolean
        get() = demoModeState
        set(value) { demoModeState = value; prefs.edit().putBoolean("demoMode", value).apply() }

    var loopingPlay: Boolean
        get() = loopingPlayState
        set(value) { loopingPlayState = value; prefs.edit().putBoolean("loopingPlay", value).apply() }

    var xmlUpload: Boolean
        get() = xmlUploadState
        set(value) { xmlUploadState = value; prefs.edit().putBoolean("xmlUpload", value).apply() }

    var trimLimitVibration: Boolean
        get() = trimLimitVibrationState
        set(value) { trimLimitVibrationState = value; prefs.edit().putBoolean("trimLimitVibration", value).apply() }

    var theme: String
        get() = themeState
        set(value) {
            themeState = value
            prefs.edit().putString("theme", value).apply()
            Am.palette = Themes.palette(context, value)
        }

    val themes: List<Pair<String, String>>
        get() = Palette.BuiltIn.map { it.first to it.first } +
            listOfNotNull(customThemeState?.let { Themes.CUSTOM to if (it.isEmpty()) "Custom" else "Custom ($it)" })

    val themeLabel: String get() = themes.firstOrNull { it.first == themeState }?.second ?: Themes.DEFAULT

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

data class NewProjectChoice(
    val aspect: String,
    val resolution: Int,
    val customWidth: Int,
    val customHeight: Int,
    val fps: Int,
    val background: Int,
)
