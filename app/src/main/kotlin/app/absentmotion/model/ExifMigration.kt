package app.absentmotion.model

internal fun Scene.withLegacyExifOrientation(uri: String, tag: Int): Scene {
    val record = media(uri) ?: return this
    if (record.orientation != 0 || tag !in setOf(5, 7)) return this
    val degrees = exifOrientation(tag).first
    fun rotated(value: String?) = ((value?.finiteFloat() ?: 0f) + degrees).toString()
    fun walk(node: XmlNode): XmlNode {
        var changed = node
        if (node["fillImage"] == uri) {
            val transform = node.child("transform") ?: XmlNode("transform")
            val rotation = transform.child("rotation") ?: XmlNode("rotation")
            val updated = rotation.with("value", rotated(rotation["value"])).copy(children = rotation.children.map {
                if (it.name == "kf") it.with("v", rotated(it["v"])) else it
            })
            changed = node.withChild("transform", transform.withChild("rotation", updated))
        }
        if (node.name == "media" && node["uri"] == uri) changed = changed.with("orientation", "$degrees")
        return changed.copy(children = changed.children.map(::walk))
    }
    return Scene(walk(node))
}
