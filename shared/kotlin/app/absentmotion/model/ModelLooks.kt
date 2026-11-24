package app.absentmotion.model

fun Layer.material(index: Int): XmlNode? = node.children.lastOrNull { it.name == "material" && it["index"] == "$index" }

fun Layer.withMaterial(index: Int, change: (XmlNode) -> XmlNode): Layer {
    val old = material(index)
    val next = change(old ?: XmlNode("material", linkedMapOf("index" to "$index"))).takeIf { it.attrs.keys.any { key -> key != "index" } }
    val children = when {
        old == null -> if (next == null) node.children else node.children + next
        next == null -> node.children.filterNot { it === old }
        else -> node.children.map { if (it === old) next else it }
    }
    return copy(node = node.copy(children = children))
}

fun Layer.withMaterialPicture(index: Int, picture: String?): Layer = withMaterial(index) { it.with("picture", picture) }

fun Layer.withMaterialTint(index: Int, argb: Int): Layer = withMaterial(index) { it.with("tint", if (argb == -1) null else formatColor(argb)) }

fun Layer.materialTint(index: Int): Int = parseColor(material(index)?.get("tint")) ?: -1

val Layer.posed: Boolean get() = node.children.any { it.isBoneTurn }

fun Layer.withoutPose(): Layer = copy(node = node.copy(children = node.children.filterNot { it.isBoneTurn }))

private val XmlNode.isBoneTurn: Boolean get() = name == "property" && this["name"]?.startsWith("bone.") == true
