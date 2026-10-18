package app.absentmotion.platform
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
class DrawPath {
    val path=Path()
    fun moveTo(x: Float,y: Float)=path.moveTo(x,y)
    fun lineTo(x: Float,y: Float)=path.lineTo(x,y)
    fun close()=path.close()
    fun addCircle(x: Float,y: Float,radius: Float,clockwise: Boolean) { path.addOval(Rect(x-radius,y-radius,x+radius,y+radius)) }
}
