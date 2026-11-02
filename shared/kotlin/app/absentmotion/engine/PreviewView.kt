package app.absentmotion.engine

data class RenderedView(val serial: Long, val timeMs: Int)

data class SnapGuide(val vertical: Boolean, val at: Float, val wide: Boolean)

data class PreviewView(
    val timeMs: Int, val scale: Float, val dx: Float, val dy: Float, val selectedId: Long = 0, val handles: Int = 8,
    val pixels: Boolean = false, val divisor: Int = 1, val gridSpacing: Float = 0f, val solo: Int = 0,
    val soloIds: List<Long> = emptyList(), val camera: Boolean = true, val guides: List<SnapGuide> = emptyList(),
    val refPx: Float = 0f, val accurateBlur: Boolean = false,
)

