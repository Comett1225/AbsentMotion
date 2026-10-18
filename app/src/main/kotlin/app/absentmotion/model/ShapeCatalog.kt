package app.absentmotion.model

import app.absentmotion.platform.Context
import app.absentmotion.model.ShapeParam.Kind.Angle
import app.absentmotion.model.ShapeParam.Kind.Count
import app.absentmotion.model.ShapeParam.Kind.Number
import app.absentmotion.model.ShapeParam.Kind.Point
import app.absentmotion.model.ShapeParam.Kind.Size
import app.absentmotion.model.ShapeParam.Kind.Toggle

data class ShapeDef(
    val id: String,
    val name: String,
    val fillType: String,
    val children: List<XmlNode>,
    val params: List<ShapeParam>,
)

data class ShapeParam(val label: String, val prop: String, val kind: Kind, val default: String, val decimals: Int = 0) {
    enum class Kind(val type: String) {
        Size("vec2"),
        Point("vec2"),
        Number("float"),
        Angle("float"),
        Count("float"),
        Toggle("bool"),
    }
}

object ShapeCatalog {
    var all: List<ShapeDef> = emptyList()
        private set

    fun load(context: Context) {
        if (all.isNotEmpty()) return
        val root = XmlNode.parse(context.assets.open("shapes.xml").bufferedReader().use { it.readText() })
        all = root.children.map { shape ->
            val id = shape["s"] ?: ""
            ShapeDef(id, shape["name"] ?: "Shape", shape["fillType"] ?: "color", shape.children, PARAMS[id] ?: emptyList())
        }
    }

    fun byId(id: String): ShapeDef? = all.firstOrNull { it.id == id && id.isNotEmpty() }

    private val size = ShapeParam("Size", "size", Size, "100,100")
    private val PARAMS: Map<String, List<ShapeParam>> = mapOf(
        ".circle" to listOf(size),
        ".rect" to listOf(size),
        ".roundrect" to listOf(size, ShapeParam("Radius", "cornerRadius", Number, "25")),
        ".plus" to listOf(size, ShapeParam("Width", "stemSize", Number, "50")),
        ".arc" to listOf(
            ShapeParam("Start", "startAngle", Angle, "0"), ShapeParam("End", "endAngle", Angle, "90"),
            ShapeParam("Radius", "radius", Number, "100"), ShapeParam("Closed", "closed", Toggle, "false"),
        ),
        ".triangle" to listOf(
            ShapeParam("Point 1", "p1", Point, "-100,100"), ShapeParam("Point 2", "p2", Point, "0,-100"),
            ShapeParam("Point 3", "p3", Point, "100,100"), ShapeParam("Closed", "closed", Toggle, "true"),
        ),
        ".callout.roundrect" to listOf(
            size, ShapeParam("Tail", "tail", Point, "-50,150"), ShapeParam("Radius", "cornerRadius", Number, "25"),
            ShapeParam("Tail Width", "tailWidth", Number, "40"),
        ),
        ".pie" to listOf(
            ShapeParam("Start", "startAngle", Angle, "45"), ShapeParam("End", "endAngle", Angle, "90"),
            ShapeParam("Radius", "radius", Number, "100"),
        ),
        ".poly" to listOf(
            ShapeParam("Sides", "sideCount", Count, "6"), ShapeParam("Radius", "radius", Number, "100"),
            ShapeParam("Angle", "offsetAngle", Angle, "0"),
        ),
        ".multifoil" to starLike(),
        ".star" to starLike(),
        ".arrow" to listOf(
            ShapeParam("Start", "start", Point, "0,0"), ShapeParam("End", "end", Point, "250,-250"),
            ShapeParam("Tail Width", "lineWidth", Number, "40"), ShapeParam("Head Width", "headWidth", Number, "120"),
            ShapeParam("Head Length", "headLength", Number, "130"),
        ),
        ".quad" to listOf(
            ShapeParam("Point 1", "p1", Point, "-100,-100"), ShapeParam("Point 2", "p2", Point, "50,-100"),
            ShapeParam("Point 3", "p3", Point, "100,100"), ShapeParam("Point 4", "p4", Point, "-100,100"),
            ShapeParam("Closed", "closed", Toggle, "true"),
        ),
        ".moon" to listOf(ShapeParam("Radius", "radius", Number, "100"), ShapeParam("Offset", "offset", Number, "250")),
        ".line" to listOf(ShapeParam("Point 1", "p1", Point, "-100,100"), ShapeParam("Point 2", "p2", Point, "100,-100")),
        ".wideline" to listOf(
            ShapeParam("Start", "start", Point, "0,0"), ShapeParam("End", "end", Point, "250,-250"),
            ShapeParam("Width", "lineWidth", Number, "40"),
        ),
        ".penta" to listOf(
            ShapeParam("Point 1", "p1", Point, "-100,-100"), ShapeParam("Point 2", "p2", Point, "0,-100"),
            ShapeParam("Point 3", "p3", Point, "0,0"), ShapeParam("Point 4", "p4", Point, "100,100"),
            ShapeParam("Point 5", "p5", Point, "-100,100"), ShapeParam("Closed", "closed", Toggle, "true"),
        ),
        ".stamp" to listOf(
            ShapeParam("Size", "size", Size, "150,150"), ShapeParam("Radius", "cornerRadius", Number, "5"),
            ShapeParam("Perforation Size", "perforationSize", Number, "40"), ShapeParam("Spacing", "perforationSpacing", Number, "20"),
        ),
        ".teardrop" to listOf(
            ShapeParam("Radius", "radius", Number, "100"), ShapeParam("Tail", "tail", Number, "200"),
            ShapeParam("Squeeze", "squeeze", Number, "2.5", decimals = 2), ShapeParam("Width", "tailWidth", Number, "0", decimals = 1),
        ),
    )

    private fun starLike() = listOf(
        ShapeParam("Points", "pointCount", Count, "5"), ShapeParam("Outer Radius", "outerRadius", Number, "100"),
        ShapeParam("Inner Radius", "innerRadius", Number, "50"), ShapeParam("Angle", "offsetAngle", Angle, "0"),
    )
}
