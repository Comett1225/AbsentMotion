package android.media

import java.nio.ByteBuffer

class MediaFormat() {
    private val map = LinkedHashMap<String, Any>()

    constructor(other: MediaFormat) : this() { map.putAll(other.map) }

    val keys: Set<String> get() = map.keys
    fun containsKey(name: String) = name in map
    fun removeKey(name: String) { map.remove(name) }
    fun getInteger(name: String): Int = map[name] as? Int ?: throw if (name in map) ClassCastException(name) else NullPointerException(name)
    fun getInteger(name: String, default: Int): Int = if (name in map) getInteger(name) else default
    fun getLong(name: String): Long = map[name] as? Long ?: throw if (name in map) ClassCastException(name) else NullPointerException(name)
    fun getLong(name: String, default: Long): Long = if (name in map) getLong(name) else default
    fun getFloat(name: String): Float = map[name] as? Float ?: throw if (name in map) ClassCastException(name) else NullPointerException(name)
    fun getFloat(name: String, default: Float): Float = if (name in map) getFloat(name) else default
    fun getNumber(name: String): Number? = map[name] as? Number
    fun getNumber(name: String, default: Number): Number = getNumber(name) ?: default
    fun getString(name: String): String? = map[name] as? String
    fun getString(name: String, default: String): String = getString(name) ?: default
    fun getByteBuffer(name: String): ByteBuffer? = map[name] as? ByteBuffer
    fun setInteger(name: String, value: Int) { map[name] = value }
    fun setLong(name: String, value: Long) { map[name] = value }
    fun setFloat(name: String, value: Float) { map[name] = value }
    fun setString(name: String, value: String?) { if (value == null) map.remove(name) else map[name] = value }
    fun setByteBuffer(name: String, value: ByteBuffer?) { if (value == null) map.remove(name) else map[name] = value }
    override fun toString() = map.toString()

    companion object {
        @JvmStatic fun createAudioFormat(mime: String, sampleRate: Int, channelCount: Int) = MediaFormat().apply {
            setString(KEY_MIME, mime); setInteger(KEY_SAMPLE_RATE, sampleRate); setInteger(KEY_CHANNEL_COUNT, channelCount)
        }
        @JvmStatic fun createVideoFormat(mime: String, width: Int, height: Int) = MediaFormat().apply {
            setString(KEY_MIME, mime); setInteger(KEY_WIDTH, width); setInteger(KEY_HEIGHT, height)
        }
        const val COLOR_RANGE_FULL = 1
        const val COLOR_RANGE_LIMITED = 2
        const val COLOR_STANDARD_BT601_NTSC = 4
        const val COLOR_STANDARD_BT601_PAL = 2
        const val COLOR_STANDARD_BT709 = 1
        const val COLOR_TRANSFER_HLG = 7
        const val COLOR_TRANSFER_SDR_VIDEO = 3
        const val COLOR_TRANSFER_ST2084 = 6
        const val KEY_AAC_PROFILE = "aac-profile"
        const val KEY_BIT_RATE = "bitrate"
        const val KEY_CHANNEL_COUNT = "channel-count"
        const val KEY_CHANNEL_MASK = "channel-mask"
        const val KEY_COLOR_FORMAT = "color-format"
        const val KEY_COLOR_RANGE = "color-range"
        const val KEY_COLOR_STANDARD = "color-standard"
        const val KEY_COLOR_TRANSFER = "color-transfer"
        const val KEY_COLOR_TRANSFER_REQUEST = "color-transfer-request"
        const val KEY_DURATION = "durationUs"
        const val KEY_ENCODER_DELAY = "encoder-delay"
        const val KEY_FLAC_COMPRESSION_LEVEL = "flac-compression-level"
        const val KEY_FRAME_RATE = "frame-rate"
        const val KEY_HEIGHT = "height"
        const val KEY_I_FRAME_INTERVAL = "i-frame-interval"
        const val KEY_MAX_INPUT_SIZE = "max-input-size"
        const val KEY_MAX_OUTPUT_CHANNEL_COUNT = "max-output-channel-count"
        const val KEY_MIME = "mime"
        const val KEY_OPERATING_RATE = "operating-rate"
        const val KEY_PCM_ENCODING = "pcm-encoding"
        const val KEY_PRIORITY = "priority"
        const val KEY_PROFILE = "profile"
        const val KEY_ROTATION = "rotation-degrees"
        const val KEY_SAMPLE_RATE = "sample-rate"
        const val KEY_WIDTH = "width"
        const val MIMETYPE_AUDIO_AAC = "audio/mp4a-latm"
        const val MIMETYPE_AUDIO_AMR_NB = "audio/3gpp"
        const val MIMETYPE_AUDIO_AMR_WB = "audio/amr-wb"
        const val MIMETYPE_AUDIO_FLAC = "audio/flac"
        const val MIMETYPE_AUDIO_OPUS = "audio/opus"
        const val MIMETYPE_VIDEO_AV1 = "video/av01"
        const val MIMETYPE_VIDEO_AVC = "video/avc"
        const val MIMETYPE_VIDEO_HEVC = "video/hevc"
        const val MIMETYPE_VIDEO_VP9 = "video/x-vnd.on2.vp9"
    }
}
