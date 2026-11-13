package app.absentmotion.desk

import app.absentmotion.media.MediaEngine
import java.awt.image.BufferedImage
import java.io.File

object DeskMedia {
    fun probe(file: File): Map<String, String> = runCatching {
        val json = org.json.JSONObject(MediaEngine.current.probe(file, "stream=codec_type,width,height:stream_tags=rotate:stream_side_data=rotation:format=duration:format_tags=title,artist,album"))
        val out = HashMap<String, String>()
        val streams = json.optJSONArray("streams")
        for (i in 0 until (streams?.length() ?: 0)) {
            val s = streams!!.getJSONObject(i)
            if (s.optString("codec_type") == "video" && "width" !in out) { out["width"] = "${s.optInt("width")}"; out["height"] = "${s.optInt("height")}"; s.optJSONObject("tags")?.optString("rotate")?.takeIf { it.isNotEmpty() }?.let { out["rotation"] = it }
                s.optJSONArray("side_data_list")?.let { list -> (0 until list.length()).firstNotNullOfOrNull { list.getJSONObject(it).optDouble("rotation").takeIf { r -> r.isFinite() } } }
                    ?.let { out["rotation"] = "${((-Math.round(it / 90.0).toInt() * 90) % 360 + 360) % 360}" } }
            if (s.optString("codec_type") == "audio") out["audio"] = "1"
        }
        json.optJSONObject("format")?.let { f ->
            f.optString("duration").toDoubleOrNull()?.let { out["duration"] = "${(it * 1000).toLong()}" }
            f.optJSONObject("tags")?.let { t -> listOf("title", "artist", "album").forEach { k -> t.optString(k).takeIf { it.isNotEmpty() }?.let { out[k] = it } } }
        }
        out
    }.getOrDefault(emptyMap())

    fun frame(file: File, timeMs: Long, maxSide: Int): BufferedImage? = runCatching { MediaEngine.current.still(file, timeMs, maxSide) }.getOrNull()
}
