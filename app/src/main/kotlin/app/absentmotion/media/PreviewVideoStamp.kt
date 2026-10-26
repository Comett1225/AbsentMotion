package app.absentmotion.media

import app.absentmotion.model.Scene

internal data class PreviewVideoStamp(val scene: Scene, val timeMs: Int, val playing: Boolean) {
    fun matches(scene: Scene, timeMs: Int, playing: Boolean): Boolean =
        this.scene == scene && if (playing) this.playing || this.timeMs == timeMs else this.timeMs == timeMs
}
