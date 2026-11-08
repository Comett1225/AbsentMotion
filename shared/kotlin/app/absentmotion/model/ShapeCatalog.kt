package app.absentmotion.model

import androidx.annotation.StringRes
import app.absentmotion.R
import app.absentmotion.engine.NativeEngine
import app.absentmotion.platform.Context
import app.absentmotion.model.ShapeParam.Kind.Angle
import app.absentmotion.model.ShapeParam.Kind.Count
import app.absentmotion.model.ShapeParam.Kind.Number
import app.absentmotion.model.ShapeParam.Kind.Percent
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
    val outline: String = "",
)

data class ShapeParam(
    @StringRes val label: Int, val prop: String, val kind: Kind, val default: String, val decimals: Int = 0,
    val min: Float? = null, val max: Float? = null, val advanced: Boolean = false,
) {
    enum class Kind(val type: String) {
        Size("vec2"),
        Point("vec2"),
        Number("float"),
        Angle("float"),
        Count("float"),
        Percent("float"),
        Toggle("bool"),
    }

    fun bounded(value: Float): Float {
        val kept = value.coerceIn(min ?: -Float.MAX_VALUE, max ?: Float.MAX_VALUE)
        return if (kind == Kind.Count) Math.round(kept).toFloat() else kept
    }
}

val Layer.outlineShape: String? get() = node["outlineShape"]

val Layer.isShapeTemplate: Boolean get() = kind == "shape" && shape.isEmpty() && ShapeCatalog.isTemplate(outlineShape) && outlineIntact

private val Layer.outlineIntact: Boolean get() = node.child("path")?.let { path -> path["d"]?.let(::outlineMark) == node["outlineMark"] } ?: true

internal fun outlineMark(d: String): String {
    var h = 0x811c9dc5.toInt()
    for (b in d.toByteArray()) h = (h xor (b.toInt() and 0xff)) * 0x01000193
    return Integer.toHexString(h).padStart(8, '0')
}

object ShapeOutlines {
    var native: (scene: String, t: Float, rounded: Boolean) -> String? = { scene, t, rounded -> NativeEngine.shapeOutline(scene, t, rounded) }

    fun svg(layer: Layer, rounded: Boolean): String? {
        val inputs = XmlNode("shape", layer.node.attrs.filterKeys { it in INPUTS }, layer.node.children.filter { it.name == "property" })
        return native(XmlNode("scene", mapOf("width" to "1", "height" to "1"), listOf(inputs)).toXml(), layer.t, rounded)
    }

    private val INPUTS = setOf("id", "s", "outlineShape", "startTime", "endTime")
}

fun Layer.withFinishedOutline(): Layer {
    if (!isShapeTemplate) return this
    val svg = ShapeOutlines.svg(Layer(node), rounded = true) ?: return this
    return copy(node = node.with("outlineMark", outlineMark(svg)).withChild("path", XmlNode("path", linkedMapOf("d" to svg, "fillRule" to "evenodd"))))
}

fun Scene.withOutlinesFollowing(before: Scene): Scene {
    val earlier = before.node.children.associateBy { it["id"] }
    var changed = false
    val children = node.children.map { child ->
        val old = earlier[child["id"]]
        if (old === child) return@map child
        val next = when {
            child.name == "embedScene" -> child.child("scene")?.let { inner ->
                val followed = Scene(inner).withOutlinesFollowing(Scene(old?.child("scene") ?: XmlNode("scene")))
                if (followed.node === inner) child else child.withChild("scene", followed.node)
            } ?: child
            child.name == "shape" && child["outlineShape"] != null && (old == null || old.outlineInputs() != child.outlineInputs()) ->
                Layer(child).withFinishedOutline().node
            else -> child
        }
        if (next !== child) changed = true
        next
    }
    return if (changed) Scene(node.copy(children = children)) else this
}

private fun XmlNode.outlineInputs() = Triple(this["outlineShape"], children.filter { it.name == "property" }, child("path") == null)

val Layer.pointsLoseAnimation: Boolean
    get() = kind == "shape" && (shape.isNotEmpty() || outlineShape != null) &&
        node.children.any { it.name == "property" && (it["name"] != "roundCorners" || bakesRounding || !isShapeTemplate && shape.isEmpty()) && it.isKeyframed }

private val Layer.bakesRounding: Boolean get() = outlineShape == "phone" || outlineShape == "panel"

fun Layer.asPoints(): Layer? {
    if (kind != "shape" || shape.isEmpty() && outlineShape == null) return null
    val live = shape.isNotEmpty() || isShapeTemplate
    val outline = if (live) parseSvgPath(ShapeOutlines.svg(this, rounded = false) ?: return null).withHoles(evenOdd = true) else contours ?: return null
    if (outline.none { it.knots.size > 1 }) return null
    val kept = node.children.filter { it.name != "path" && (it.name != "property" || live && it["name"] == "roundCorners" && !bakesRounding) }
    return copy(node = node.copy(attrs = node.attrs - setOf("s", "outlineShape", "outlineMark"), children = kept)).withContours(outline)
}

object ShapeCatalog {
    var all: List<ShapeDef> = emptyList()
        private set

    fun load(context: Context) {
        if (all.isNotEmpty()) return
        val root = XmlNode.parse(context.assets.open("shapes.xml").bufferedReader().use { it.readText() })
        all = root.children.map { shape ->
            val id = shape["s"] ?: ""
            val outline = shape["outlineShape"] ?: ""
            val params = if (id.isNotEmpty()) parametric(id) else OUTLINES[outline].orEmpty()
            ShapeDef(id, shape["name"] ?: "Shape", shape["fillType"] ?: "color", shape.children, params, TITLES[shape["name"]] ?: R.string.shape_name_shape, outline)
        }
    }

    fun byId(id: String): ShapeDef? = all.firstOrNull { it.id == id && id.isNotEmpty() }

    fun isTemplate(outline: String?): Boolean = outline != null && outline in OUTLINES

    val templates: Set<String> get() = OUTLINES.keys

    fun paramsFor(layer: Layer): List<ShapeParam> = when {
        layer.kind != "shape" -> emptyList()
        layer.shape.isNotEmpty() -> parametric(layer.shape)
        layer.outlineShape != null -> if (layer.isShapeTemplate) OUTLINES[layer.outlineShape].orEmpty() else emptyList()
        layer.node.child("path") != null -> listOf(rounding(0))
        else -> emptyList()
    }

    private fun parametric(id: String): List<ShapeParam> {
        val params = PARAMS[id] ?: return emptyList()
        val own = params.firstOrNull { it.prop == "cornerRadius" }
        return when {
            own != null -> listOf(own.copy(label = R.string.shape_param_round_corners, min = 0f)) + (params - own)
            id == ".circle" || id == ".line" -> params
            else -> listOf(rounding(0)) + params
        }
    }

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
        "Phone" to R.string.shape_name_phone,
        "Panel" to R.string.shape_name_panel,
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

    private fun rounding(default: Int) = ShapeParam(R.string.shape_param_round_corners, "roundCorners", Number, "$default", min = 0f)

    private fun template(round: Int, width: Int, height: Int, vararg options: ShapeParam) =
        listOf(rounding(round), ShapeParam(R.string.shape_param_size, "size", Size, "$width,$height", min = 0f)) + options

    private fun percent(@StringRes label: Int, prop: String, default: Int, min: Int = 0, max: Int = 100, advanced: Boolean = false) =
        ShapeParam(label, prop, Percent, "$default", min = min.toFloat(), max = max.toFloat(), advanced = advanced)

    private fun count(@StringRes label: Int, prop: String, default: Int, min: Int, max: Int, advanced: Boolean = false) =
        ShapeParam(label, prop, Count, "$default", min = min.toFloat(), max = max.toFloat(), advanced = advanced)

    private fun angle(@StringRes label: Int, prop: String, default: Int, min: Float? = null, max: Float? = null, advanced: Boolean = false) =
        ShapeParam(label, prop, Angle, "$default", min = min, max = max, advanced = advanced)

    private fun toggle(@StringRes label: Int, prop: String, on: Boolean, advanced: Boolean = false) = ShapeParam(label, prop, Toggle, "$on", advanced = advanced)

    private fun turn() = angle(R.string.shape_param_angle, "offsetAngle", 0, advanced = true)

    private val OUTLINES: Map<String, List<ShapeParam>> = mapOf(
        "heart" to template(0, 100, 90, percent(R.string.shape_param_notch, "notch", 37, max = 90), percent(R.string.shape_param_point, "point", 50)),
        "cloud" to template(0, 100, 48, count(R.string.shape_param_bumps, "bumps", 4, 2, 10), toggle(R.string.shape_param_flat_bottom, "flatBottom", true, advanced = true)),
        "lightning-bolt" to template(3, 54, 100),
        "check-mark" to template(19, 100, 80, percent(R.string.shape_param_thickness, "thickness", 24, 1), percent(R.string.shape_param_short_arm, "shortArm", 41, 5, advanced = true)),
        "x-mark" to template(4, 100, 100, percent(R.string.shape_param_thickness, "thickness", 15, 1)),
        "ring" to template(
            0, 100, 100, percent(R.string.shape_param_thickness, "thickness", 38, 1), angle(R.string.shape_param_start, "startAngle", 90, advanced = true),
            angle(R.string.shape_param_sweep, "sweep", 360, 0f, 360f, advanced = true),
        ),
        "speech-bubble" to template(0, 89, 89, ShapeParam(R.string.shape_param_tail, "tail", Point, "-69,111"), angle(R.string.shape_param_tail_width, "tailWidth", 34, 1f, 180f)),
        "shield" to template(0, 86, 100),
        "house" to template(
            3, 100, 96, percent(R.string.shape_param_roof, "roof", 48, 5, 95), percent(R.string.shape_param_door, "door", 20, max = 75),
            percent(R.string.shape_param_door_height, "doorHeight", 35, 5, 95, advanced = true),
        ),
        "bookmark" to template(8, 60, 100, percent(R.string.shape_param_notch, "notch", 24, max = 95)),
        "flag" to template(0, 82, 100, percent(R.string.shape_param_wave, "wave", 100, -200, 200)),
        "crescent-and-star" to template(0, 100, 97),
        "sun" to template(
            3, 100, 100, count(R.string.shape_param_rays, "rays", 8, 3, 32), percent(R.string.shape_param_ray_length, "rayLength", 35, 1),
            percent(R.string.shape_param_disc, "disc", 45), percent(R.string.shape_param_ray_width, "rayWidth", 22, 1, advanced = true), turn(),
        ),
        "leaf" to template(0, 91, 100),
        "seal-burst" to template(0, 100, 100, count(R.string.shape_param_points, "pointCount", 12, 3, 64), percent(R.string.shape_param_inner_radius, "innerRadius", 84, 1), turn()),
        "hexagon-outline" to template(0, 100, 100, count(R.string.shape_param_sides, "sideCount", 6, 3, 32), percent(R.string.shape_param_thickness, "thickness", 32, 1), turn()),
        "star-outline" to template(
            0, 100, 100, count(R.string.shape_param_points, "pointCount", 5, 3, 32), percent(R.string.shape_param_inner_radius, "innerRadius", 40, 1),
            percent(R.string.shape_param_thickness, "thickness", 44, 1), turn(),
        ),
        "chevron" to template(5, 58, 100, percent(R.string.shape_param_thickness, "thickness", 34, 1)),
        "double-arrow" to template(3, 100, 58, percent(R.string.shape_param_shaft, "shaft", 38, 1), percent(R.string.shape_param_head_length, "headLength", 29, 1, 50)),
        "curved-arrow" to template(
            0, 100, 75, percent(R.string.shape_param_thickness, "thickness", 43, 5, 95), angle(R.string.shape_param_sweep, "sweep", 180, 10f, 340f, advanced = true),
            percent(R.string.shape_param_head_size, "headSize", 100, 10, 300, advanced = true),
        ),
        "parallelogram" to template(8, 100, 62, percent(R.string.shape_param_slant, "slant", 25, -100, 100)),
        "trapezoid" to template(8, 100, 61, percent(R.string.shape_param_top_width, "topWidth", 55, max = 200)),
        "ticket" to template(10, 100, 60, percent(R.string.shape_param_notch, "notch", 30), count(R.string.shape_param_holes, "holes", 5, 0, 20)),
        "tag" to template(8, 100, 64, percent(R.string.shape_param_point, "point", 28), percent(R.string.shape_param_hole, "hole", 22, max = 90)),
        "crown" to template(
            4, 100, 88, count(R.string.shape_param_points, "pointCount", 3, 2, 9), percent(R.string.shape_param_depth, "depth", 46),
            percent(R.string.shape_param_balls, "balls", 100, max = 200), toggle(R.string.shape_param_jewels, "jewels", true, advanced = true),
        ),
        "eye" to template(0, 100, 56, percent(R.string.shape_param_iris, "iris", 76), percent(R.string.shape_param_pupil, "pupil", 45)),
        "umbrella" to template(0, 99, 100),
        "music-note" to template(0, 69, 100),
        "droplet-pair" to template(0, 100, 94),
        "key" to template(2, 100, 42),
        "location-pin" to template(0, 65, 100, percent(R.string.shape_param_hole, "hole", 39, max = 95)),
        "rounded-play" to template(24, 93, 100),
        "pause" to template(15, 84, 100, percent(R.string.shape_param_gap, "gap", 26, max = 95), count(R.string.shape_param_bars, "bars", 2, 1, 8, advanced = true)),
        "record" to template(0, 100, 100, percent(R.string.shape_param_ring, "ring", 16, 1), percent(R.string.shape_param_dot, "dot", 60)),
        "gear" to template(
            0, 100, 100, count(R.string.shape_param_teeth, "teeth", 8, 3, 64), percent(R.string.shape_param_tooth_depth, "toothDepth", 22, 1, 90),
            percent(R.string.shape_param_tooth_width, "toothWidth", 40, 1), percent(R.string.shape_param_hole, "hole", 30, max = 95),
            percent(R.string.shape_param_tooth_base, "toothBase", 62, 1, advanced = true),
        ),
        "phone" to template(
            14, 56, 100, percent(R.string.shape_param_border, "border", 7), toggle(R.string.shape_param_camera, "camera", true),
            toggle(R.string.shape_param_notch, "notch", false, advanced = true), toggle(R.string.shape_param_side_buttons, "buttons", false, advanced = true),
        ),
        "panel" to template(
            10, 100, 70, percent(R.string.shape_param_border, "border", 5), percent(R.string.shape_param_header, "header", 15, max = 90),
            count(R.string.shape_param_columns, "columns", 1, 1, 8, advanced = true), count(R.string.shape_param_rows, "rows", 1, 1, 8, advanced = true),
        ),
    )
}
