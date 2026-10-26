package app.absentmotion.media

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.model.finiteFloat
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Mesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val colors: IntArray,
    val triangles: IntArray,
    val slots: IntArray,
    val textures: List<ByteArray>,
    val twoSided: Boolean,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = triangles.size / 3
}

const val MODEL_TRIANGLE_BUDGET = 50_000

internal const val MODEL_WORK_LIMIT = 16_000_000L

internal fun modelFileName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\').replace('\u0000', '_')
    .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "model"

fun parseModel(name: String, bytes: ByteArray, open: (String) -> ByteArray?): Mesh {
    val mesh = if (name.substringAfterLast('.').lowercase() == "obj") ObjReader.read(bytes.toString(Charsets.UTF_8), open) else GltfReader.read(bytes, open)
    require(mesh.triangleCount > 0) { AppText.get(R.string.model_err_no_triangles) }
    return mesh
}

fun readModel(name: String, bytes: ByteArray, open: (String) -> ByteArray?): Mesh = decimated(parseModel(name, bytes, open), MODEL_TRIANGLE_BUDGET)

private class Floats {
    var data = FloatArray(4096)
    var size = 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun array() = data.copyOf(size)
}

private class Ints {
    var data = IntArray(4096)
    var size = 0
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun array() = data.copyOf(size)
}

private class MeshBuilder {
    val positions = Floats()
    val normals = Floats()
    val uvs = Floats()
    val colors = Ints()
    val triangles = Ints()
    val slots = Ints()
    private val missing = Ints()
    val count get() = colors.size

    fun vertex(x: Float, y: Float, z: Float, normal: FloatArray?, u: Float, v: Float, color: Int): Int {
        positions.add(x); positions.add(-y); positions.add(-z)
        if (normal == null) { missing.add(count); repeat(3) { normals.add(0f) } }
        else { normals.add(normal[0]); normals.add(-normal[1]); normals.add(-normal[2]) }
        uvs.add(u); uvs.add(v)
        colors.add(color)
        return count - 1
    }

    fun triangle(a: Int, b: Int, c: Int, slot: Int) {
        if (a == b || b == c || a == c) return
        triangles.add(a); triangles.add(b); triangles.add(c)
        slots.add(slot)
    }

    fun build(textures: List<ByteArray>, twoSided: Boolean): Mesh {
        if (missing.size > 0) flatten()
        val remap = IntArray(count) { -1 }
        var used = 0
        for (k in 0 until triangles.size) {
            val v = triangles.data[k]
            if (remap[v] < 0) remap[v] = used++
            triangles.data[k] = remap[v]
        }
        val keep = IntArray(used).also { k -> remap.forEachIndexed { v, to -> if (to >= 0) k[to] = v } }
        fun pick(source: Floats, n: Int) = FloatArray(used * n) { source.data[keep[it / n] * n + it % n] }
        val p = pick(positions, 3)
        val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in p.indices) { lo[i % 3] = min(lo[i % 3], p[i]); hi[i % 3] = max(hi[i % 3], p[i]) }
        val centre = FloatArray(3) { (lo[it] + hi[it]) / 2 }
        var radius = 0f
        for (i in p.indices step 3) {
            val dx = p[i] - centre[0]; val dy = p[i + 1] - centre[1]; val dz = p[i + 2] - centre[2]
            radius = max(radius, dx * dx + dy * dy + dz * dz)
        }
        radius = sqrt(radius).takeIf { it > 0f } ?: 1f
        for (i in p.indices) p[i] = (p[i] - centre[i % 3]) / radius
        return Mesh(p, pick(normals, 3), pick(uvs, 2), IntArray(used) { colors.data[keep[it]] }, triangles.array(), slots.array(), textures, twoSided)
    }

    private fun flatten() {
        val lacks = BooleanArray(count)
        for (k in 0 until missing.size) lacks[missing.data[k]] = true
        for (k in 0 until triangles.size step 3) {
            val corner = IntArray(3) { triangles.data[k + it] }
            if (corner.none { lacks[it] }) continue
            val p = positions.data
            val (a, b, c) = corner.map { it * 3 }
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
            val f = floatArrayOf(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
            val l = sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]).takeIf { it > 0f } ?: continue
            for (i in 0..2) {
                if (!lacks[corner[i]]) continue
                val v = corner[i]
                for (j in 0..2) { positions.add(positions.data[v * 3 + j]); normals.add(f[j] / l) }
                uvs.add(uvs.data[v * 2]); uvs.add(uvs.data[v * 2 + 1])
                colors.add(colors.data[v])
                triangles.data[k + i] = count - 1
            }
        }
    }
}

private fun argb(r: Float, g: Float, b: Float, a: Float): Int {
    fun c(v: Float) = (v.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
    return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
}

private fun tokens(line: String): List<String> = line.split(' ', '\t').filter { it.isNotEmpty() }

object ObjReader {
    private const val LIMIT = (1 shl 21) - 1

    private class Material(var color: FloatArray = floatArrayOf(1f, 1f, 1f, 1f), var texture: String? = null)

    fun read(text: String, open: (String) -> ByteArray?, limit: Long = MODEL_WORK_LIMIT): Mesh {
        var work = 0L
        val vs = Floats()
        val vcs = Floats()
        val vts = Floats()
        val vns = Floats()
        val materials = HashMap<String, Material>()
        val textures = ArrayList<ByteArray>()
        val textureSlots = HashMap<String, Int>()
        val builder = MeshBuilder()
        val seen = HashMap<Long, Int>()
        var material = Material()
        var slot = -1
        fun resolve(index: Int, count: Int) = if (index < 0) count + index else index - 1
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val t = tokens(line)
            if (t[0] == "v" || t[0] == "vt" || t[0] == "vn" || t[0] == "f") {
                work += if (t[0] == "f") t.size - 1L else 1L
                require(work <= limit) { AppText.get(R.string.model_err_too_complex) }
            }
            when (t[0]) {
                "v" -> {
                    for (i in 1..3) vs.add(t.getOrNull(i)?.finiteFloat() ?: 0f)
                    for (i in 4..6) vcs.add(t.getOrNull(i)?.finiteFloat() ?: 1f)
                }
                "vt" -> { vts.add(t.getOrNull(1)?.finiteFloat() ?: 0f); vts.add(t.getOrNull(2)?.finiteFloat() ?: 0f) }
                "vn" -> for (i in 1..3) vns.add(t.getOrNull(i)?.finiteFloat() ?: 0f)
                "mtllib" -> open(line.substringAfter("mtllib").trim())?.let { readMaterials(it.toString(Charsets.UTF_8), materials) }
                "usemtl" -> {
                    material = materials[line.substringAfter("usemtl").trim()] ?: Material()
                    seen.clear()
                    slot = material.texture?.let { name ->
                        textureSlots.getOrPut(name) { open(name)?.let { textures += it; textures.size - 1 } ?: -1 }
                    } ?: -1
                }
                "f" -> {
                    val corners = IntArray(t.size - 1)
                    for (k in 1 until t.size) {
                        val parts = t[k].split('/')
                        val v = resolve(parts[0].toIntOrNull() ?: 0, vs.size / 3)
                        val vt = parts.getOrNull(1)?.toIntOrNull()?.let { resolve(it, vts.size / 2) } ?: -1
                        val vn = parts.getOrNull(2)?.toIntOrNull()?.let { resolve(it, vns.size / 3) } ?: -1
                        require(v in 0 until vs.size / 3) { AppText.get(R.string.model_err_missing_vertex) }
                        require(v < LIMIT && vt < LIMIT && vn < LIMIT) { AppText.get(R.string.model_err_too_large) }
                        val key = (v.toLong() shl 42) or ((vt + 1).toLong() shl 21) or (vn + 1).toLong()
                        corners[k - 1] = seen.getOrPut(key) {
                            val c = material.color
                            val normal = if (vn in 0 until vns.size / 3) floatArrayOf(vns.data[vn * 3], vns.data[vn * 3 + 1], vns.data[vn * 3 + 2]) else null
                            val hasUv = vt in 0 until vts.size / 2
                            builder.vertex(vs.data[v * 3], vs.data[v * 3 + 1], vs.data[v * 3 + 2], normal,
                                if (hasUv) vts.data[vt * 2] else 0f, if (hasUv) 1f - vts.data[vt * 2 + 1] else 0f,
                                argb(c[0] * vcs.data[v * 3], c[1] * vcs.data[v * 3 + 1], c[2] * vcs.data[v * 3 + 2], c[3]))
                        }
                    }
                    for (k in 1 until corners.size - 1) builder.triangle(corners[0], corners[k], corners[k + 1], slot)
                }
            }
        }
        return builder.build(textures, false)
    }

    private fun readMaterials(text: String, into: HashMap<String, Material>) {
        var current: Material? = null
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val t = tokens(line)
            when (t[0]) {
                "newmtl" -> current = Material().also { into[line.substringAfter("newmtl").trim()] = it }
                "Kd" -> current?.let { m -> for (i in 0..2) m.color[i] = t.getOrNull(i + 1)?.finiteFloat() ?: 1f }
                "d" -> current?.let { m -> m.color[3] = t.getOrNull(1)?.finiteFloat() ?: 1f }
                "Tr" -> current?.let { m -> m.color[3] = 1f - (t.getOrNull(1)?.finiteFloat() ?: 0f) }
                "map_Kd" -> current?.texture = t.last().takeIf { t.size > 1 }
            }
        }
    }
}

object GltfReader {
    private const val GLB = 0x46546C67
    private const val JSON = 0x4E4F534A
    private const val BIN = 0x004E4942

    fun read(bytes: ByteArray, open: (String) -> ByteArray?, limit: Long = MODEL_WORK_LIMIT): Mesh {
        var bin: ByteBuffer? = null
        val json = if (bytes.size >= 12 && le(bytes, 0) == GLB) {
            var at = 12
            var text: String? = null
            while (at + 8 <= bytes.size) {
                val length = le(bytes, at)
                val type = le(bytes, at + 4)
                val start = at + 8
                require(length >= 0 && start + length <= bytes.size) { AppText.get(R.string.model_err_truncated) }
                if (type == JSON && text == null) text = String(bytes, start, length, Charsets.UTF_8)
                else if (type == BIN && bin == null) bin = ByteBuffer.wrap(bytes, start, length).slice()
                at = start + ((length + 3) and 3.inv())
            }
            JSONObject(requireNotNull(text) { "The model has no description" })
        } else JSONObject(bytes.toString(Charsets.UTF_8))
        val buffers = json.optJSONArray("buffers").objects().map { b -> b.optString("uri").let { uri -> if (uri.isEmpty()) bin else load(uri, open)?.let(ByteBuffer::wrap) } }
        val views = json.optJSONArray("bufferViews").objects()
        val accessors = json.optJSONArray("accessors").objects()
        val meshes = json.optJSONArray("meshes").objects()
        val nodes = json.optJSONArray("nodes").objects()
        val materials = json.optJSONArray("materials").objects()
        val textureList = json.optJSONArray("textures").objects()
        val images = json.optJSONArray("images").objects()
        val textures = ArrayList<ByteArray>()
        val imageSlots = HashMap<Int, Int>()
        var twoSided = false
        val builder = MeshBuilder()

        fun slice(viewIndex: Int): Triple<ByteBuffer, Int, Int>? {
            val view = views.getOrNull(viewIndex) ?: return null
            val data = buffers.getOrNull(view.optInt("buffer", -1)) ?: return null
            val offset = view.optInt("byteOffset", 0)
            val length = view.optInt("byteLength", 0)
            require(offset >= 0 && length >= 0 && offset.toLong() + length <= data.capacity()) { AppText.get(R.string.model_err_buffer_view) }
            return Triple(data.duplicate().apply { limit(offset + length) }.order(ByteOrder.LITTLE_ENDIAN), offset, view.optInt("byteStride", 0))
        }

        fun read(index: Int, normalizeInts: Boolean = true, expect: Int = -1): Pair<FloatArray, Int>? {
            val a = accessors.getOrNull(index) ?: return null
            val count = a.optInt("count", 0)
            require(count <= limit) { AppText.get(R.string.model_err_too_complex) }
            if (expect >= 0 && count != expect) return null
            val comps = when (a.optString("type")) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> return null }
            val type = a.optInt("componentType")
            val size = when (type) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> return null }
            if (!a.has("bufferView")) return if (expect >= 0) FloatArray(count * comps) to comps else null
            val (buffer, start, viewStride) = slice(a.optInt("bufferView", -1)) ?: return null
            val stride = if (viewStride > 0) viewStride else comps * size
            val base = start + a.optInt("byteOffset", 0).toLong()
            val scaled = normalizeInts && (a.optBoolean("normalized") || type == 5121 || type == 5123)
            require(count >= 0 && base >= 0 && (count == 0 || base + (count - 1L) * stride + comps * size <= buffer.limit())) { AppText.get(R.string.model_err_accessor) }
            val out = FloatArray(count * comps)
            for (e in 0 until count) for (c in 0 until comps) {
                val at = (base + e.toLong() * stride + c * size).toInt()
                out[e * comps + c] = when (type) {
                    5126 -> buffer.getFloat(at)
                    5121 -> (buffer.get(at).toInt() and 0xff).let { if (scaled) it / 255f else it.toFloat() }
                    5123 -> (buffer.getShort(at).toInt() and 0xffff).let { if (scaled) it / 65535f else it.toFloat() }
                    5125 -> (buffer.getInt(at).toLong() and 0xffffffffL).toFloat()
                    5120 -> buffer.get(at).toFloat().let { if (scaled) max(it / 127f, -1f) else it }
                    else -> buffer.getShort(at).toFloat().let { if (scaled) max(it / 32767f, -1f) else it }
                }
            }
            return out to comps
        }

        fun image(index: Int): ByteArray? {
            val img = images.getOrNull(index) ?: return null
            val uri = img.optString("uri")
            if (uri.isNotEmpty()) return load(uri, open)
            val (buffer, start, _) = slice(img.optInt("bufferView", -1)) ?: return null
            return ByteArray(buffer.limit() - start).also { buffer.position(start); buffer.get(it) }
        }

        fun indices(primitive: JSONObject, count: Int): IntArray {
            if (accessors.getOrNull(primitive.optInt("indices", -1))?.has("bufferView") == false) return IntArray(0)
            val (values, _) = (if (primitive.has("indices")) read(primitive.getInt("indices"), false) else null) ?: return IntArray(count) { it }
            return IntArray(values.size) { values[it].toInt() }
        }

        var work = 0L
        fun charge(accessor: Int) {
            work += accessors.getOrNull(accessor)?.optInt("count", 0)?.coerceAtLeast(0) ?: 0
            require(work <= limit) { AppText.get(R.string.model_err_too_complex) }
        }

        fun addMesh(meshIndex: Int, world: FloatArray) {
            val normalMatrix = normalMatrix(world)
            val flip = determinant(world) < 0
            for (primitive in meshes.getOrNull(meshIndex)?.optJSONArray("primitives").objects()) {
                if (primitive.optInt("mode", 4) != 4) continue
                val attributes = primitive.optJSONObject("attributes") ?: continue
                charge(attributes.optInt("POSITION", -1))
                charge(primitive.optInt("indices", -1))
                val (positions, pc) = read(attributes.optInt("POSITION", -1)) ?: continue
                if (pc != 3) continue
                val count = positions.size / 3
                val normals = read(attributes.optInt("NORMAL", -1), expect = count)?.first?.takeIf { it.size == count * 3 }
                val uvs = read(attributes.optInt("TEXCOORD_0", -1), expect = count)?.first?.takeIf { it.size == count * 2 }
                val colours = read(attributes.optInt("COLOR_0", -1), expect = count)?.takeIf { it.first.size == count * it.second && it.second >= 3 }
                val material = materials.getOrNull(primitive.optInt("material", -1))
                twoSided = twoSided || material?.optBoolean("doubleSided") == true
                val pbr = material?.optJSONObject("pbrMetallicRoughness")
                val factor = pbr?.optJSONArray("baseColorFactor")?.let { f -> FloatArray(4) { f.optDouble(it, 1.0).toFloat() } } ?: floatArrayOf(1f, 1f, 1f, 1f)
                val slot = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1)?.let { textureList.getOrNull(it) }?.optInt("source", -1)
                    ?.takeIf { it >= 0 }?.let { src -> imageSlots.getOrPut(src) { image(src)?.let { textures += it; textures.size - 1 } ?: -1 } } ?: -1
                val first = builder.count
                for (v in 0 until count) {
                    val p = transform(world, positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2])
                    val n = normals?.let { transformNormal(normalMatrix, it[v * 3], it[v * 3 + 1], it[v * 3 + 2]) }
                    val c = colours?.let { (values, comps) -> FloatArray(4) { i -> if (i < comps) values[v * comps + i] else 1f } } ?: floatArrayOf(1f, 1f, 1f, 1f)
                    builder.vertex(p[0], p[1], p[2], n, uvs?.get(v * 2) ?: 0f, uvs?.get(v * 2 + 1) ?: 0f,
                        argb(factor[0] * c[0], factor[1] * c[1], factor[2] * c[2], factor[3] * c[3]))
                }
                val list = indices(primitive, count)
                for (k in 0 until list.size - 2 step 3) {
                    val a = list[k]; val b = list[k + 1]; val c = list[k + 2]
                    if (a !in 0 until count || b !in 0 until count || c !in 0 until count) continue
                    if (flip) builder.triangle(first + a, first + c, first + b, slot) else builder.triangle(first + a, first + b, first + c, slot)
                }
            }
        }

        val visited = BooleanArray(nodes.size)
        fun visit(index: Int, parent: FloatArray, depth: Int) {
            val node = nodes.getOrNull(index) ?: return
            if (depth > 64 || visited[index]) return
            visited[index] = true
            val world = multiply(parent, localMatrix(node))
            if (node.has("mesh")) addMesh(node.getInt("mesh"), world)
            node.optJSONArray("children")?.let { c -> for (i in 0 until c.length()) visit(c.optInt(i, -1), world, depth + 1) }
        }

        val identity = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
        if (nodes.isEmpty()) meshes.indices.forEach { addMesh(it, identity) }
        else {
            val scenes = json.optJSONArray("scenes").objects()
            val children = nodes.flatMap { n -> n.optJSONArray("children")?.let { c -> List(c.length()) { c.optInt(it, -1) } }.orEmpty() }.toSet()
            val roots = scenes.getOrNull(json.optInt("scene", 0))?.optJSONArray("nodes")?.let { r -> List(r.length()) { r.optInt(it, -1) } }
                ?: nodes.indices.filter { it !in children }
            roots.forEach { visit(it, identity, 0) }
        }
        return builder.build(textures, twoSided)
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { optJSONObject(it) ?: JSONObject() }

    private fun le(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
        ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun load(uri: String, open: (String) -> ByteArray?): ByteArray? =
        if (uri.startsWith("data:")) runCatching { Base64.getDecoder().decode(uri.substringAfter(',')) }.getOrNull()
        else open(java.net.URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8"))

    private fun localMatrix(node: JSONObject): FloatArray {
        node.optJSONArray("matrix")?.takeIf { it.length() == 16 }?.let { m -> return FloatArray(16) { m.optDouble(it, 0.0).toFloat() } }
        val t = node.optJSONArray("translation")
        val r = node.optJSONArray("rotation")
        val s = node.optJSONArray("scale")
        val x = r?.optDouble(0, 0.0)?.toFloat() ?: 0f; val y = r?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val z = r?.optDouble(2, 0.0)?.toFloat() ?: 0f; val w = r?.optDouble(3, 1.0)?.toFloat() ?: 1f
        val sx = s?.optDouble(0, 1.0)?.toFloat() ?: 1f; val sy = s?.optDouble(1, 1.0)?.toFloat() ?: 1f; val sz = s?.optDouble(2, 1.0)?.toFloat() ?: 1f
        return floatArrayOf(
            (1 - 2 * (y * y + z * z)) * sx, (2 * (x * y + z * w)) * sx, (2 * (x * z - y * w)) * sx, 0f,
            (2 * (x * y - z * w)) * sy, (1 - 2 * (x * x + z * z)) * sy, (2 * (y * z + x * w)) * sy, 0f,
            (2 * (x * z + y * w)) * sz, (2 * (y * z - x * w)) * sz, (1 - 2 * (x * x + y * y)) * sz, 0f,
            t?.optDouble(0, 0.0)?.toFloat() ?: 0f, t?.optDouble(1, 0.0)?.toFloat() ?: 0f, t?.optDouble(2, 0.0)?.toFloat() ?: 0f, 1f,
        )
    }

    private fun multiply(a: FloatArray, b: FloatArray) = FloatArray(16) { i ->
        val col = i / 4; val row = i % 4
        (0..3).sumOf { k -> (a[k * 4 + row] * b[col * 4 + k]).toDouble() }.toFloat()
    }

    private fun transform(m: FloatArray, x: Float, y: Float, z: Float) =
        floatArrayOf(m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14])

    private fun determinant(m: FloatArray) =
        m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

    private fun normalMatrix(m: FloatArray): FloatArray {
        val s = if (determinant(m) < 0) -1f else 1f
        return floatArrayOf(
            m[5] * m[10] - m[6] * m[9], m[6] * m[8] - m[4] * m[10], m[4] * m[9] - m[5] * m[8],
            m[2] * m[9] - m[1] * m[10], m[0] * m[10] - m[2] * m[8], m[1] * m[8] - m[0] * m[9],
            m[1] * m[6] - m[2] * m[5], m[2] * m[4] - m[0] * m[6], m[0] * m[5] - m[1] * m[4],
        ).also { c -> for (i in c.indices) c[i] *= s }
    }

    private fun transformNormal(c: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val n = floatArrayOf(c[0] * x + c[3] * y + c[6] * z, c[1] * x + c[4] * y + c[7] * z, c[2] * x + c[5] * y + c[8] * z)
        val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        return if (l > 0f) floatArrayOf(n[0] / l, n[1] / l, n[2] / l) else floatArrayOf(0f, 0f, 1f)
    }
}

fun decimated(mesh: Mesh, budget: Int): Mesh {
    if (mesh.triangleCount <= budget) return mesh
    var cells = 256
    while (true) {
        val result = clustered(mesh, cells)
        if (result.triangleCount <= budget || cells <= 4) return result
        cells = max(4, (cells * 0.8f).toInt())
    }
}

private fun clustered(mesh: Mesh, cells: Int): Mesh {
    val p = mesh.positions
    val remap = IntArray(mesh.vertexCount)
    val owner = HashMap<Long, Int>()
    val keep = Ints()
    fun cell(v: Float) = ((v + 1f) / 2f * cells).toInt().coerceIn(0, cells - 1).toLong()
    for (v in 0 until mesh.vertexCount) {
        val key = (cell(p[v * 3]) * cells + cell(p[v * 3 + 1])) * cells + cell(p[v * 3 + 2])
        remap[v] = owner.getOrPut(key) { keep.add(v); keep.size - 1 }
    }
    val triangles = Ints()
    val slots = Ints()
    val seen = HashSet<Triple<Int, Int, Int>>()
    val t = mesh.triangles
    for (k in 0 until mesh.triangleCount) {
        val a = remap[t[k * 3]]; val b = remap[t[k * 3 + 1]]; val c = remap[t[k * 3 + 2]]
        if (a == b || b == c || a == c) continue
        val first = min(a, min(b, c))
        val key = when (first) { a -> Triple(a, b, c); b -> Triple(b, c, a); else -> Triple(c, a, b) }
        if (!seen.add(key)) continue
        triangles.add(a); triangles.add(b); triangles.add(c)
        slots.add(mesh.slots[k])
    }
    val kept = keep.array()
    fun pick(source: FloatArray, n: Int) = FloatArray(kept.size * n) { source[kept[it / n] * n + it % n] }
    return Mesh(pick(mesh.positions, 3), pick(mesh.normals, 3), pick(mesh.uvs, 2), IntArray(kept.size) { mesh.colors[kept[it]] },
        triangles.array(), slots.array(), mesh.textures, mesh.twoSided)
}
