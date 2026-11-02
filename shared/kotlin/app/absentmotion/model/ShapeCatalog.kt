package app.absentmotion.model

import androidx.annotation.StringRes
import app.absentmotion.R
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
    @StringRes val title: Int,
)

data class ShapeParam(@StringRes val label: Int, val prop: String, val kind: Kind, val default: String, val decimals: Int = 0) {
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
            ShapeDef(id, shape["name"] ?: "Shape", shape["fillType"] ?: "color", shape.children, PARAMS[id] ?: emptyList(), TITLES[shape["name"]] ?: R.string.shape_name_shape)
        }
    }

    fun byId(id: String): ShapeDef? = all.firstOrNull { it.id == id && id.isNotEmpty() }

    private val TITLES: Map<String, Int> = mapOf(
        "Circle" to R.string.shape_name_circle,
        "Rounded Rectangle" to R.string.shape_name_rounded_rectangle,
        "Plus" to R.string.shape_name_plus,
        "Arc" to R.string.shape_name_arc,
        "Triangle" to R.string.shape_name_triangle,
        "Callout" to R.string.shape_name_callout,
        "Pie" to R.string.shape_name_pie,
        "Polygon" to R.string.shape_name_polygon,
        "Multifoil" to R.string.shape_name_multifoil,
        "Arrow" to R.string.shape_name_arrow,
        "Quad" to R.string.shape_name_quad,
        "Moon" to R.string.shape_name_moon,
        "Rectangle" to R.string.shape_name_rectangle,
        "Star" to R.string.shape_name_star,
        "Line" to R.string.shape_name_line,
        "Wide Line" to R.string.shape_name_wide_line,
        "Irregular Pentagon" to R.string.shape_name_irregular_pentagon,
        "Stamp" to R.string.shape_name_stamp,
        "Teardrop" to R.string.shape_name_teardrop,
        "Heart" to R.string.shape_name_heart,
        "Cloud" to R.string.shape_name_cloud,
        "Lightning Bolt" to R.string.shape_name_lightning_bolt,
        "Check Mark" to R.string.shape_name_check_mark,
        "X Mark" to R.string.shape_name_x_mark,
        "Ring" to R.string.shape_name_ring,
        "Speech Bubble" to R.string.shape_name_speech_bubble,
        "Shield" to R.string.shape_name_shield,
        "House" to R.string.shape_name_house,
        "Bookmark" to R.string.shape_name_bookmark,
        "Flag" to R.string.shape_name_flag,
        "Crescent and Star" to R.string.shape_name_crescent_and_star,
        "Sun" to R.string.shape_name_sun,
        "Leaf" to R.string.shape_name_leaf,
        "Seal Burst" to R.string.shape_name_seal_burst,
        "Hexagon Outline" to R.string.shape_name_hexagon_outline,
        "Star Outline" to R.string.shape_name_star_outline,
        "Chevron" to R.string.shape_name_chevron,
        "Double Arrow" to R.string.shape_name_double_arrow,
        "Curved Arrow" to R.string.shape_name_curved_arrow,
        "Parallelogram" to R.string.shape_name_parallelogram,
        "Trapezoid" to R.string.shape_name_trapezoid,
        "Ticket" to R.string.shape_name_ticket,
        "Tag" to R.string.shape_name_tag,
        "Crown" to R.string.shape_name_crown,
        "Eye" to R.string.shape_name_eye,
        "Umbrella" to R.string.shape_name_umbrella,
        "Music Note" to R.string.shape_name_music_note,
        "Droplet Pair" to R.string.shape_name_droplet_pair,
        "Key" to R.string.shape_name_key,
        "Location Pin" to R.string.shape_name_location_pin,
        "Rounded Play" to R.string.shape_name_rounded_play,
        "Pause" to R.string.shape_name_pause,
        "Record" to R.string.shape_name_record,
        "Gear" to R.string.shape_name_gear,
    )

    private val size = ShapeParam(R.string.shape_param_size, "size", Size, "100,100")
    private val PARAMS: Map<String, List<ShapeParam>> = mapOf(
        ".circle" to listOf(size),
        ".rect" to listOf(size),
        ".roundrect" to listOf(size, ShapeParam(R.string.shape_param_radius, "cornerRadius", Number, "25")),
        ".plus" to listOf(size, ShapeParam(R.string.shape_param_width, "stemSize", Number, "50")),
        ".arc" to listOf(
            ShapeParam(R.string.shape_param_start, "startAngle", Angle, "0"), ShapeParam(R.string.shape_param_end, "endAngle", Angle, "90"),
            ShapeParam(R.string.shape_param_radius, "radius", Number, "100"), ShapeParam(R.string.shape_param_closed, "closed", Toggle, "false"),
        ),
        ".triangle" to listOf(
            ShapeParam(R.string.shape_param_point_1, "p1", Point, "-100,100"), ShapeParam(R.string.shape_param_point_2, "p2", Point, "0,-100"),
            ShapeParam(R.string.shape_param_point_3, "p3", Point, "100,100"), ShapeParam(R.string.shape_param_closed, "closed", Toggle, "true"),
        ),
        ".callout.roundrect" to listOf(
            size, ShapeParam(R.string.shape_param_tail, "tail", Point, "-50,150"), ShapeParam(R.string.shape_param_radius, "cornerRadius", Number, "25"),
            ShapeParam(R.string.shape_param_tail_width, "tailWidth", Number, "40"),
        ),
        ".pie" to listOf(
            ShapeParam(R.string.shape_param_start, "startAngle", Angle, "45"), ShapeParam(R.string.shape_param_end, "endAngle", Angle, "90"),
            ShapeParam(R.string.shape_param_radius, "radius", Number, "100"),
        ),
        ".poly" to listOf(
            ShapeParam(R.string.shape_param_sides, "sideCount", Count, "6"), ShapeParam(R.string.shape_param_radius, "radius", Number, "100"),
            ShapeParam(R.string.shape_param_angle, "offsetAngle", Angle, "0"),
        ),
        ".multifoil" to starLike(),
        ".star" to starLike(),
        ".arrow" to listOf(
            ShapeParam(R.string.shape_param_start, "start", Point, "0,0"), ShapeParam(R.string.shape_param_end, "end", Point, "250,-250"),
            ShapeParam(R.string.shape_param_tail_width, "lineWidth", Number, "40"), ShapeParam(R.string.shape_param_head_width, "headWidth", Number, "120"),
            ShapeParam(R.string.shape_param_head_length, "headLength", Number, "130"),
        ),
        ".quad" to listOf(
            ShapeParam(R.string.shape_param_point_1, "p1", Point, "-100,-100"), ShapeParam(R.string.shape_param_point_2, "p2", Point, "50,-100"),
            ShapeParam(R.string.shape_param_point_3, "p3", Point, "100,100"), ShapeParam(R.string.shape_param_point_4, "p4", Point, "-100,100"),
            ShapeParam(R.string.shape_param_closed, "closed", Toggle, "true"),
        ),
        ".moon" to listOf(ShapeParam(R.string.shape_param_radius, "radius", Number, "100"), ShapeParam(R.string.shape_param_offset, "offset", Number, "250")),
        ".line" to listOf(ShapeParam(R.string.shape_param_point_1, "p1", Point, "-100,100"), ShapeParam(R.string.shape_param_point_2, "p2", Point, "100,-100")),
        ".wideline" to listOf(
            ShapeParam(R.string.shape_param_start, "start", Point, "0,0"), ShapeParam(R.string.shape_param_end, "end", Point, "250,-250"),
            ShapeParam(R.string.shape_param_width, "lineWidth", Number, "40"),
        ),
        ".penta" to listOf(
            ShapeParam(R.string.shape_param_point_1, "p1", Point, "-100,-100"), ShapeParam(R.string.shape_param_point_2, "p2", Point, "0,-100"),
            ShapeParam(R.string.shape_param_point_3, "p3", Point, "0,0"), ShapeParam(R.string.shape_param_point_4, "p4", Point, "100,100"),
            ShapeParam(R.string.shape_param_point_5, "p5", Point, "-100,100"), ShapeParam(R.string.shape_param_closed, "closed", Toggle, "true"),
        ),
        ".stamp" to listOf(
            ShapeParam(R.string.shape_param_size, "size", Size, "150,150"), ShapeParam(R.string.shape_param_radius, "cornerRadius", Number, "5"),
            ShapeParam(R.string.shape_param_perforation_size, "perforationSize", Number, "40"), ShapeParam(R.string.shape_param_spacing, "perforationSpacing", Number, "20"),
        ),
        ".teardrop" to listOf(
            ShapeParam(R.string.shape_param_radius, "radius", Number, "100"), ShapeParam(R.string.shape_param_tail, "tail", Number, "200"),
            ShapeParam(R.string.shape_param_squeeze, "squeeze", Number, "2.5", decimals = 2), ShapeParam(R.string.shape_param_width, "tailWidth", Number, "0", decimals = 1),
        ),
    )

    private fun starLike() = listOf(
        ShapeParam(R.string.shape_param_points, "pointCount", Count, "5"), ShapeParam(R.string.shape_param_outer_radius, "outerRadius", Number, "100"),
        ShapeParam(R.string.shape_param_inner_radius, "innerRadius", Number, "50"), ShapeParam(R.string.shape_param_angle, "offsetAngle", Angle, "0"),
    )
}
