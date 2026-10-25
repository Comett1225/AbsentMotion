package app.absentmotion.ai

import android.content.Context
import androidx.annotation.StringRes
import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.ui.home.HOME_PAGE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal const val CUTOUT_PARAM_768 = "absent-cutout-768.param"
internal const val CUTOUT_PARAM_1024 = "absent-cutout-1024.param"

internal class ModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)

internal const val MODEL_RELEASE = "$HOME_PAGE/releases/download/ai-models-1/"
internal const val MODEL_RELEASE_2 = "$HOME_PAGE/releases/download/ai-models-2/"
private const val RIFE_SOURCE = "https://raw.githubusercontent.com/TNTwise/rife-ncnn-vulkan/13338e38debe2e400b3eeecf6792312d01a692f9/models/"

private fun rife(folder: String, paramBytes: Long, paramSha: String, binBytes: Long, binSha: String) = listOf(
    ModelFile("flownet.param", "$RIFE_SOURCE$folder/flownet.param", paramBytes, paramSha),
    ModelFile("flownet.bin", "$RIFE_SOURCE$folder/flownet.bin", binBytes, binSha),
)

private fun hosted(name: String, bytes: Long, sha: String, release: String = MODEL_RELEASE) = ModelFile(name, release + name, bytes, sha)

internal enum class AiModel(val folder: String, @StringRes val title: Int, val licence: String, val gmacPerMegapixel: Double, val files: List<ModelFile>) {
    SmoothLow("rife-v4.25-lite", R.string.ai_model_smooth_low, "RIFE 4.25 lite (MIT)", 10.7, rife("rife-v4.25-lite",
        35_830, "e55b76f197b89637dc824ce89e64878df419214d3cf6a10aea0283c61b93a908",
        11_276_252, "350a15e464bea5ad378e06c0fb43996e90a0d35653d5a6ef6bc980d832538fb7")),
    SmoothMedium("rife-v4.25", R.string.ai_model_smooth_medium, "RIFE 4.25 (MIT)", 22.2, rife("rife-v4.25",
        31_289, "6ba231fb00e4ae82b120f938d9b2df91db32fbf322bd110f29450efaf61848d6",
        11_360_684, "10de487a095e61cb2971c39e3b5e17005a70fba6201c77fb96e063f4423b583f")),
    SmoothHigh("rife-v4.26-large", R.string.ai_model_smooth_high, "RIFE 4.26 large (MIT)", 25.3, rife("rife-v4.26-large",
        35_833, "5b26d92a5d67f95f8d95dc2d3360c30ec4576128dc7505a5475127a022234e86",
        11_483_660, "6b2606352ff5c7da1c0affcea3a572e651c02ead1d2514b9670f1d67769cbdd2")),
    Detail("realesr-general-x4v3", R.string.ai_model_detail, "Real-ESRGAN general x4v3 (BSD-3-Clause)", 1209.0, listOf(
        hosted("realesr-general-x4v3.param", 5_585, "88b2c9de3a4702dd1f3d6c73a55ef9539e3d955891e5cd33a9d300d623a1de86"),
        hosted("realesr-general-x4v3.bin", 2_435_272, "01450f4a79b81c0f1f3eeefc31121167886125c25e41a6d4773e8ec8062528a1"))),
    Grain("realesr-general-wdn-x4v3", R.string.ai_model_grain, "Real-ESRGAN general wdn x4v3 (BSD-3-Clause)", 1209.0, listOf(
        hosted("realesr-general-wdn-x4v3.bin", 2_435_272, "bdd5bdc7da2c83411ced54c37a6646aebfe1b3f780bb7e6f877dd8934fbd3cc5"))),
    Cartoon("realesr-animevideov3", R.string.ai_model_cartoon, "Real-ESRGAN animevideov3 (BSD-3-Clause)", 619.2, listOf(
        hosted("realesr-animevideov3-x4.param", 3_077, "850a248e7c14c27e5bd8cf7265113a9441036a7db63963bb8aa5169d788a435e"),
        hosted("realesr-animevideov3-x4.bin", 1_247_368, "548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d"))),
    Cutout("cutout-1", R.string.ai_model_cutout, "AbsentMotion cut-out (Apache-2.0)", 79.0, listOf(
        hosted(CUTOUT_PARAM_768, 19_350, "f5f6b00c7d7e313590c4150252bce38d292b51a77cc46f887175afe2a8577c48", MODEL_RELEASE_2),
        hosted(CUTOUT_PARAM_1024, 19_356, "12cbc7eb86afccbee2dbcd79bee799171ee4adcf4d59f1ff0e9278341885b262", MODEL_RELEASE_2),
        hosted("absent-cutout.bin", 64_875_088, "c597b55b1a0c7013b0ece7a9917914016ef3462d3b832993678dfd339ed0c503", MODEL_RELEASE_2)));

    val bytes: Long get() = files.sumOf { it.bytes }
    val param: String? get() = files.firstOrNull { it.name.endsWith(".param") }?.name
    val bin: String get() = files.first { it.name.endsWith(".bin") }.name

    fun dir(context: Context) = File(context.filesDir, "models/ai/$folder")
    fun file(context: Context, name: String) = File(dir(context), name)
    fun installed(context: Context) = files.all { file(context, it.name).length() == it.bytes }
    fun onDisk(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun delete(context: Context) {
        dir(context).deleteRecursively()
        stamps(context).edit().apply { files.forEach { remove(file(context, it.name).path) } }.apply()
    }

    fun verify(context: Context) {
        val known = stamps(context)
        for (f in files) {
            val target = file(context, f.name)
            val stamp = "${target.length()}:${target.lastModified()}"
            if (known.getString(target.path, null) == stamp) continue
            val intact = target.length() == f.bytes && target.inputStream().use(CaptionModel::sha256) == f.sha256
            if (!intact) target.delete()
            check(intact) { AppText.get(R.string.ai_model_broken) }
            known.edit().putString(target.path, stamp).apply()
        }
    }

    suspend fun download(context: Context, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dir = dir(context)
        check(dir.isDirectory || dir.mkdirs()) { AppText.get(R.string.ai_cannot_create_model_storage) }
        val total = bytes.toFloat()
        var before = 0L
        for (f in files) {
            val target = file(context, f.name)
            if (target.length() != f.bytes || target.inputStream().use(CaptionModel::sha256) != f.sha256) {
                target.delete()
                try {
                    fetch(f, target, { ensureActive() }) { progress((before + it * f.bytes) / total) }
                } catch (_: IOException) {
                    error(AppText.get(R.string.ai_model_download_failed))
                }
            }
            before += f.bytes
            progress(before / total)
        }
    }

    companion object {
        private fun stamps(context: Context) = context.getSharedPreferences("ai_models", Context.MODE_PRIVATE)

        fun downloaded(context: Context) = entries.filter { it.onDisk(context) > 0 }
    }
}

internal fun fetch(file: ModelFile, target: File, active: () -> Unit, progress: (Float) -> Unit) {
    val part = File(target.path + ".part")
    if (part.length() >= file.bytes) part.delete()
    val have = part.length()
    val connection = URL(file.url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
        val code = connection.responseCode
        val resume = have > 0 && code == HttpURLConnection.HTTP_PARTIAL
        check(code == HttpURLConnection.HTTP_OK || resume) { AppText.get(R.string.ai_download_failed_http, code.toString()) }
        connection.inputStream.use { CaptionModel.receive(it, part, target, file.bytes, file.sha256, active, progress, resume, R.string.ai_model_download_wrong) }
    } finally {
        connection.disconnect()
    }
}
