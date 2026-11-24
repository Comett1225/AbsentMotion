package app.absentmotion.media

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.model.finiteFloat
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
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
    val parts: IntArray = IntArray(triangles.size / 3) { -1 },
    val materials: List<ModelMaterial> = emptyList(),
    val skin: MeshSkin? = null,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = triangles.size / 3
}

class ModelMaterial(val index: Int, val name: String, val picture: String?, val embedded: Boolean, val found: Boolean, val texture: Int)

class ModelBone(val node: Int, val name: String, val parent: Int)

class MeshSkin(
    val bones: List<ModelBone>,
    val locals: FloatArray,
    val jointBones: IntArray,
    val inverseBinds: FloatArray,
    val normalise: FloatArray,
    val bind: FloatArray,
    val bindNormals: FloatArray,
    val joints: IntArray,
    val weights: FloatArray,
) {
    fun ints(): IntArray = intArrayOf(bones.size, jointBones.size, joints.size / 4) + IntArray(bones.size) { bones[it].parent } +
        IntArray(bones.size) { bones[it].node } + jointBones + joints

    fun floats(): FloatArray = normalise + locals + inverseBinds + bind + bindNormals + weights
}

private class Skeleton(val bones: List<ModelBone>, val locals: FloatArray, val jointBones: IntArray, val inverseBinds: FloatArray)

fun boneProperty(node: Int, axis: Int): String = "bone.$node." + "xyz"[axis]

const val MODEL_TRIANGLE_BUDGET = 50_000
const val MODEL_HIGH_DETAIL_BUDGET = 100_000

fun triangles(count: Int): String = java.text.NumberFormat.getIntegerInstance().format(count)

object ModelDetail {
    val budget = MutableStateFlow(MODEL_TRIANGLE_BUDGET)
    val high: Boolean get() = budget.value > MODEL_TRIANGLE_BUDGET
}

internal const val MODEL_WORK_LIMIT = 16_000_000L

internal fun modelFileName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\').replace('\u0000', '_')
    .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "model"

const val MODEL_SKIN_POSE = "skin"
private const val MODEL_SKIN_KEY = "skin:"
private const val LOOKS = '\u001e'
private const val LOOK = '\u001d'
private const val FIELD = '\u001f'

fun modelKey(uri: String, pose: String?, looks: List<Pair<String, String>> = emptyList()): String =
    (if (pose == MODEL_SKIN_POSE) MODEL_SKIN_KEY + uri else uri) + if (looks.isEmpty()) "" else LOOKS + looks.joinToString("$LOOK") { "${it.first}$FIELD${it.second}" }
fun modelSource(key: String): String = key.removePrefix(MODEL_SKIN_KEY).substringBefore(LOOKS)
fun modelSkinned(key: String): Boolean = key.startsWith(MODEL_SKIN_KEY)
fun modelLooks(key: String): Map<Int, String> = key.substringAfter(LOOKS, "").split(LOOK).mapNotNull { look ->
    look.substringBefore(FIELD).toIntOrNull()?.takeIf { FIELD in look }?.let { it to look.substringAfter(FIELD) }
}.toMap()

fun parseModel(name: String, bytes: ByteArray, open: (String) -> ByteArray?, skin: Boolean = false, looks: Map<Int, String> = emptyMap()): Mesh {
    val mesh = if (name.substringAfterLast('.').lowercase() == "obj") ObjReader.read(bytes.toString(Charsets.UTF_8), open, looks = looks) else GltfReader.read(bytes, open, skin = skin, looks = looks)
    require(mesh.triangleCount > 0) { AppText.get(R.string.model_err_no_triangles) }
    return mesh
}

fun readModel(name: String, bytes: ByteArray, open: (String) -> ByteArray?): Mesh = decimated(parseModel(name, bytes, open), ModelDetail.budget.value)

private class Floats {
    var data = FloatArray(4096)
    var size = 0
    fun reserve(extra: Int) { if (size + extra > data.size) data = data.copyOf(size + extra) }
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun array() = data.copyOf(size)
}

private class Ints {
    var data = IntArray(4096)
    var size = 0
    fun reserve(extra: Int) { if (size + extra > data.size) data = data.copyOf(size + extra) }
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun array() = data.copyOf(size)
}

private class MeshBuilder(val skinned: Boolean = false) {
    val positions = Floats()
    val normals = Floats()
    val uvs = Floats()
    val colors = Ints()
    val triangles = Ints()
    val slots = Ints()
    val parts = Ints()
    val bind = Floats()
    val bindNormals = Floats()
    val joints = Ints()
    val weights = Floats()
    private val missing = Ints()
    val count get() = colors.size

    fun reserve(vertices: Int, corners: Int) {
        positions.reserve(vertices * 3); normals.reserve(vertices * 3); uvs.reserve(vertices * 2); colors.reserve(vertices)
        triangles.reserve(corners); slots.reserve(corners / 3); parts.reserve(corners / 3)
        if (skinned) { bind.reserve(vertices * 3); bindNormals.reserve(vertices * 3); joints.reserve(vertices * 4); weights.reserve(vertices * 4) }
    }

    fun vertex(x: Float, y: Float, z: Float, normal: FloatArray?, u: Float, v: Float, color: Int): Int {
        positions.add(x); positions.add(-y); positions.add(-z)
        if (normal == null) { missing.add(count); repeat(3) { normals.add(0f) } }
        else { normals.add(normal[0]); normals.add(-normal[1]); normals.add(-normal[2]) }
        uvs.add(u); uvs.add(v)
        colors.add(color)
        if (skinned) {
            for (i in 0..2) { bind.add(positions.data[positions.size - 3 + i]); bindNormals.add(normals.data[normals.size - 3 + i]) }
            repeat(4) { joints.add(-1); weights.add(0f) }
        }
        return count - 1
    }

    fun skin(v: Int, x: Float, y: Float, z: Float, normal: FloatArray?, joint: (Int) -> Int, weight: (Int) -> Float) {
        bind.data[v * 3] = x; bind.data[v * 3 + 1] = -y; bind.data[v * 3 + 2] = -z
        if (normal != null) { bindNormals.data[v * 3] = normal[0]; bindNormals.data[v * 3 + 1] = -normal[1]; bindNormals.data[v * 3 + 2] = -normal[2] }
        for (k in 0..3) { joints.data[v * 4 + k] = joint(k); weights.data[v * 4 + k] = weight(k) }
    }

    private fun copySkin(v: Int, flip: Float) {
        for (i in 0..2) { bind.add(bind.data[v * 3 + i]); bindNormals.add(bindNormals.data[v * 3 + i] * flip) }
        for (k in 0..3) { joints.add(joints.data[v * 4 + k]); weights.add(weights.data[v * 4 + k]) }
    }

    fun reversed(from: Int, n: Int, flat: Boolean): Int {
        val start = count
        reserve(n, 0)
        for (v in from until from + n) {
            for (i in 0..2) positions.add(positions.data[v * 3 + i])
            if (flat) missing.add(count)
            for (i in 0..2) normals.add(-normals.data[v * 3 + i])
            uvs.add(uvs.data[v * 2]); uvs.add(uvs.data[v * 2 + 1])
            colors.add(colors.data[v])
            if (skinned) copySkin(v, -1f)
        }
        return start
    }

    fun triangle(a: Int, b: Int, c: Int, slot: Int, part: Int = -1) {
        if (a == b || b == c || a == c) return
        triangles.add(a); triangles.add(b); triangles.add(c)
        slots.add(slot)
        parts.add(part)
    }

    fun build(textures: List<ByteArray>, twoSided: Boolean, materials: List<ModelMaterial> = emptyList(), skeleton: Skeleton? = null): Mesh {
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
        val pickedJoints = if (skinned) IntArray(used * 4) { joints.data[keep[it / 4] * 4 + it % 4] } else null
        val skin = if (skeleton == null || pickedJoints == null || pickedJoints.none { it >= 0 }) null else {
            val s = 1f / radius
            MeshSkin(skeleton.bones, skeleton.locals, skeleton.jointBones, skeleton.inverseBinds,
                floatArrayOf(s, 0f, 0f, 0f, 0f, -s, 0f, 0f, 0f, 0f, -s, 0f, -s * centre[0], -s * centre[1], -s * centre[2], 1f),
                pick(bind, 3), pick(bindNormals, 3), pickedJoints, pick(weights, 4))
        }
        return Mesh(p, pick(normals, 3), pick(uvs, 2), IntArray(used) { colors.data[keep[it]] }, triangles.array(), slots.array(), textures, twoSided,
            parts.array(), materials, skin)
    }

    private fun faceNormal(p: FloatArray, corner: IntArray): FloatArray? {
        val (a, b, c) = corner.map { it * 3 }
        val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
        val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
        val f = floatArrayOf(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx)
        val l = sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]).takeIf { it > 0f } ?: return null
        return FloatArray(3) { f[it] / l }
    }

    private fun flatten() {
        val lacks = BooleanArray(count)
        for (k in 0 until missing.size) lacks[missing.data[k]] = true
        for (k in 0 until triangles.size step 3) {
            val corner = IntArray(3) { triangles.data[k + it] }
            if (corner.none { lacks[it] }) continue
            val f = faceNormal(positions.data, corner) ?: continue
            val fb = if (skinned) faceNormal(bind.data, corner) ?: f else f
            for (i in 0..2) {
                if (!lacks[corner[i]]) continue
                val v = corner[i]
                for (j in 0..2) { positions.add(positions.data[v * 3 + j]); normals.add(f[j]) }
                uvs.add(uvs.data[v * 2]); uvs.add(uvs.data[v * 2 + 1])
                colors.add(colors.data[v])
                if (skinned) {
                    for (j in 0..2) { bind.add(bind.data[v * 3 + j]); bindNormals.add(fb[j]) }
                    for (j in 0..3) { joints.add(joints.data[v * 4 + j]); weights.add(weights.data[v * 4 + j]) }
                }
                triangles.data[k + i] = count - 1
            }
        }
    }
}

private fun argb(r: Float, g: Float, b: Float, a: Float): Int {
    fun c(v: Float) = (v.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
    return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
}

internal fun polygonTriangles(xs: FloatArray, ys: FloatArray, zs: FloatArray): IntArray {
    val n = xs.size
    val fan = IntArray(max(0, n - 2) * 3) { i -> if (i % 3 == 0) 0 else i / 3 + i % 3 }
    if (n <= 3 || n > 4096) return fan
    var nx = 0.0; var ny = 0.0; var nz = 0.0
    for (i in 0 until n) {
        val j = (i + 1) % n
        nx += (ys[i] - ys[j]).toDouble() * (zs[i] + zs[j])
        ny += (zs[i] - zs[j]).toDouble() * (xs[i] + xs[j])
        nz += (xs[i] - xs[j]).toDouble() * (ys[i] + ys[j])
    }
    val ax = abs(nx); val ay = abs(ny); val az = abs(nz)
    if (max(ax, max(ay, az)) == 0.0) return fan
    val u: FloatArray; val v: FloatArray; val sign: Double
    when {
        az >= ax && az >= ay -> { u = xs; v = ys; sign = nz }
        ax >= ay -> { u = ys; v = zs; sign = nx }
        else -> { u = zs; v = xs; sign = ny }
    }
    val flip = sign < 0
    fun cross(a: Int, b: Int, c: Int): Double {
        val r = (u[b] - u[a]).toDouble() * (v[c] - v[a]) - (v[b] - v[a]).toDouble() * (u[c] - u[a])
        return if (flip) -r else r
    }
    val left = ArrayList<Int>(n).apply { for (i in 0 until n) add(i) }
    val out = IntArray((n - 2) * 3)
    var written = 0
    var miss = 0
    var i = 0
    while (left.size > 3 && miss < left.size) {
        val m = left.size
        val a = left[(i + m - 1) % m]; val b = left[i % m]; val c = left[(i + 1) % m]
        var ear = cross(a, b, c) > 0
        if (ear) for (k in left) {
            if (k == a || k == b || k == c) continue
            if (cross(a, b, k) >= 0 && cross(b, c, k) >= 0 && cross(c, a, k) >= 0) { ear = false; break }
        }
        if (ear) {
            out[written++] = a; out[written++] = b; out[written++] = c
            left.removeAt(i % m)
            miss = 0
            if (i >= left.size) i = 0
        } else {
            miss++
            i = (i + 1) % m
        }
    }
    for (k in 1 until left.size - 1) { out[written++] = left[0]; out[written++] = left[k]; out[written++] = left[k + 1] }
    return out
}

private fun tokens(line: String): List<String> = line.split(' ', '\t').filter { it.isNotEmpty() }

private fun mapFileName(args: String): String? {
    fun split(s: String): Pair<String, String> { val w = s.takeWhile { !it.isWhitespace() }; return w to s.substring(w.length).trimStart() }
    var rest = args.trim()
    while (rest.startsWith("-")) {
        val (option, after) = split(rest)
        rest = after
        var count = when (option.lowercase()) { "-s", "-o", "-t" -> 3; "-mm" -> 2; else -> 1 }
        while (count-- > 0) {
            val (arg, tail) = split(rest)
            if (arg.toFloatOrNull() == null && arg.lowercase() !in setOf("on", "off", "r", "g", "b", "m", "l", "z")) break
            rest = tail
        }
    }
    return unquoted(rest).takeIf { it.isNotEmpty() }
}

private fun unquoted(name: String): String = name.trim().removeSurrounding("\"").trim()

private fun argument(line: String, keyword: String): String = unquoted(line.substring(keyword.length))

internal fun companionName(wanted: String, names: Collection<String>): String? {
    val base = unquoted(wanted).substringAfterLast('/').substringAfterLast('\\')
    val copy = Regex(""" \(\d+\)(?=\.[^.]*$|$)""")
    fun plain(name: String) = copy.replace(name.lowercase(), "")
    return names.firstOrNull { it == base } ?: names.firstOrNull { it.equals(base, ignoreCase = true) }
        ?: names.filter { plain(it) == plain(base) }.singleOrNull()
        ?: names.filter { it.endsWith(".mtl", ignoreCase = true) }.takeIf { base.endsWith(".mtl", ignoreCase = true) }?.singleOrNull()
}

object ObjReader {
    private const val LIMIT = (1 shl 21) - 1

    private class Material(var color: FloatArray = floatArrayOf(1f, 1f, 1f, 1f), var texture: String? = null)

    fun read(text: String, open: (String) -> ByteArray?, limit: Long = MODEL_WORK_LIMIT, looks: Map<Int, String> = emptyMap()): Mesh {
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
        var part = -1
        val used = HashMap<String, Int>()
        val info = ArrayList<ModelMaterial>()
        fun load(key: String, name: String = key) = textureSlots.getOrPut(key) { open(name)?.let { textures += it; textures.size - 1 } ?: -1 }
        fun resolve(index: Int, count: Int) = if (index < 0) count + index else index - 1
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val t = tokens(line)
            val key = t[0].lowercase()
            if (key == "v" || key == "vt" || key == "vn" || key == "f") {
                work += if (key == "f") t.size - 1L else 1L
                require(work <= limit) { AppText.get(R.string.model_err_too_complex) }
            }
            when (key) {
                "v" -> {
                    for (i in 1..3) vs.add(t.getOrNull(i)?.finiteFloat() ?: 0f)
                    for (i in 4..6) vcs.add(t.getOrNull(i)?.finiteFloat() ?: 1f)
                }
                "vt" -> { vts.add(t.getOrNull(1)?.finiteFloat() ?: 0f); vts.add(t.getOrNull(2)?.finiteFloat() ?: 0f) }
                "vn" -> for (i in 1..3) vns.add(t.getOrNull(i)?.finiteFloat() ?: 0f)
                "mtllib" -> {
                    val name = argument(line, t[0])
                    (open(name)?.let { listOf(it) } ?: tokens(name).map(::unquoted).mapNotNull(open)).forEach { readMaterials(it.toString(Charsets.UTF_8), materials) }
                }
                "usemtl" -> {
                    val name = argument(line, t[0])
                    material = materials[name] ?: Material()
                    seen.clear()
                    part = used.getOrPut(name) { used.size }
                    val look = looks[part]
                    val own = material.texture
                    slot = when {
                        look == null -> own?.let { load(it) } ?: -1
                        look.isEmpty() -> -1
                        else -> load("\u0000$look", look)
                    }
                    if (info.size == part) info += ModelMaterial(part, name, own?.let(::modelFileName), false,
                        own != null && (if (look == null) slot >= 0 else textureSlots[own]?.let { it >= 0 } ?: (open(own) != null)), slot)
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
                    if (corners.size == 3) builder.triangle(corners[0], corners[1], corners[2], slot, part)
                    else {
                        val at = { k: Int, axis: Int -> val v = resolve(t[k + 1].substringBefore('/').toIntOrNull() ?: 0, vs.size / 3); vs.data[v * 3 + axis] }
                        val order = polygonTriangles(FloatArray(corners.size) { at(it, 0) }, FloatArray(corners.size) { at(it, 1) }, FloatArray(corners.size) { at(it, 2) })
                        for (k in order.indices step 3) builder.triangle(corners[order[k]], corners[order[k + 1]], corners[order[k + 2]], slot, part)
                    }
                }
            }
        }
        return builder.build(textures, false, info)
    }

    private fun readMaterials(text: String, into: HashMap<String, Material>) {
        var current: Material? = null
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val t = tokens(line)
            when (t[0].lowercase()) {
                "newmtl" -> current = Material().also { into[argument(line, t[0])] = it }
                "kd" -> current?.let { m -> for (i in 0..2) m.color[i] = t.getOrNull(i + 1)?.finiteFloat() ?: 1f }
                "d" -> current?.let { m -> m.color[3] = t.getOrNull(1)?.finiteFloat() ?: 1f }
                "tr" -> current?.let { m -> m.color[3] = 1f - (t.getOrNull(1)?.finiteFloat() ?: 0f) }
                "map_kd" -> current?.texture = mapFileName(line.substring(t[0].length))
            }
        }
    }
}

object GltfReader {
    private const val GLB = 0x46546C67
    private const val JSON = 0x4E4F534A
    private const val BIN = 0x004E4942

    fun read(bytes: ByteArray, open: (String) -> ByteArray?, limit: Long = MODEL_WORK_LIMIT, skin: Boolean = false, looks: Map<Int, String> = emptyMap()): Mesh {
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
        val buffers = json.optJSONArray("buffers").objects().map { b ->
            b.optString("uri").let { uri -> if (uri.isEmpty()) bin else requireNotNull(load(uri, open)?.let(ByteBuffer::wrap)) { AppText.get(R.string.model_err_missing_file, modelFileName(decoded(uri))) } }
        }
        val views = json.optJSONArray("bufferViews").objects()
        val accessors = json.optJSONArray("accessors").objects()
        val meshes = json.optJSONArray("meshes").objects()
        val nodes = json.optJSONArray("nodes").objects()
        val materials = json.optJSONArray("materials").objects()
        val textureList = json.optJSONArray("textures").objects()
        val images = json.optJSONArray("images").objects()
        val textures = ArrayList<ByteArray>()
        val imageSlots = HashMap<Int, Int>()
        val lookSlots = HashMap<String, Int>()
        val info = java.util.TreeMap<Int, ModelMaterial>()
        fun doubleSided(primitive: JSONObject) = materials.getOrNull(primitive.optInt("material", -1))?.optBoolean("doubleSided") == true
        val primitives = meshes.flatMap { it.optJSONArray("primitives").objects() }
        val twoSided = primitives.isNotEmpty() && primitives.all(::doubleSided)
        val skins = json.optJSONArray("skins").objects()
        val builder = MeshBuilder(skin && nodes.any { it.has("mesh") && it.optInt("skin", -1) in skins.indices })

        fun slice(viewIndex: Int): Triple<ByteBuffer, Int, Int>? {
            val view = views.getOrNull(viewIndex) ?: return null
            val data = buffers.getOrNull(view.optInt("buffer", -1)) ?: return null
            val offset = view.optInt("byteOffset", 0)
            val length = view.optInt("byteLength", 0)
            require(offset >= 0 && length >= 0 && offset.toLong() + length <= data.capacity()) { AppText.get(R.string.model_err_buffer_view) }
            return Triple(data.duplicate().apply { limit(offset + length) }.order(ByteOrder.LITTLE_ENDIAN), offset, view.optInt("byteStride", 0))
        }

        fun read(index: Int, normalizeInts: Boolean = true, expect: Int = -1, matrices: Boolean = false, flaggedOnly: Boolean = false): Pair<FloatArray, Int>? {
            val a = accessors.getOrNull(index) ?: return null
            val count = a.optInt("count", 0)
            require(count <= limit) { AppText.get(R.string.model_err_too_complex) }
            if (expect >= 0 && count != expect) return null
            val comps = when (a.optString("type")) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> if (matrices) 16 else return null; else -> return null }
            val type = a.optInt("componentType")
            val size = when (type) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> return null }
            if (!a.has("bufferView")) return if (expect >= 0) FloatArray(count * comps) to comps else null
            val (buffer, start, viewStride) = slice(a.optInt("bufferView", -1)) ?: return null
            val stride = if (viewStride > 0) viewStride else comps * size
            val base = start + a.optInt("byteOffset", 0).toLong()
            val scaled = normalizeInts && (a.optBoolean("normalized") || !flaggedOnly && (type == 5121 || type == 5123))
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
            if (!primitive.has("indices")) return IntArray(count) { it }
            val accessor = accessors.getOrNull(primitive.optInt("indices", -1))
                ?: throw IllegalArgumentException(AppText.get(R.string.model_err_accessor))
            if (!accessor.has("bufferView")) return IntArray(0)
            val count = accessor.optInt("count", 0)
            val type = accessor.optInt("componentType")
            val size = when (type) { 5121 -> 1; 5123 -> 2; 5125 -> 4; else -> throw IllegalArgumentException(AppText.get(R.string.model_err_accessor)) }
            require(count <= limit && accessor.optString("type") == "SCALAR") { AppText.get(R.string.model_err_accessor) }
            val (buffer, start, viewStride) = slice(accessor.optInt("bufferView", -1)) ?: throw IllegalArgumentException(AppText.get(R.string.model_err_accessor))
            val stride = if (viewStride > 0) viewStride else size
            val base = start + accessor.optInt("byteOffset", 0).toLong()
            require(count >= 0 && base >= 0 && (count == 0 || base + (count - 1L) * stride + size <= buffer.limit())) { AppText.get(R.string.model_err_accessor) }
            return IntArray(count) { e ->
                val at = (base + e.toLong() * stride).toInt()
                when (type) {
                    5121 -> buffer.get(at).toInt() and 0xff
                    5123 -> buffer.getShort(at).toInt() and 0xffff
                    else -> buffer.getInt(at).let { if (it < 0) Int.MAX_VALUE else it }
                }
            }
        }

        var work = 0L
        fun charge(accessor: Int) {
            work += accessors.getOrNull(accessor)?.optInt("count", 0)?.coerceAtLeast(0) ?: 0
            require(work <= limit) { AppText.get(R.string.model_err_too_complex) }
        }

        val worlds = arrayOfNulls<FloatArray>(nodes.size)
        val poses = HashMap<Int, Array<FloatArray>?>()
        val boneOf = HashMap<Int, Int>()
        val skinBase = HashMap<Int, Int>()
        val tableBones = Ints()
        val tableInverse = Floats()

        fun pose(skinIndex: Int): Array<FloatArray>? = poses.getOrPut(skinIndex) {
            val joints = skins.getOrNull(skinIndex)?.optJSONArray("joints")?.takeIf { it.length() > 0 } ?: return@getOrPut null
            val inverse = skins[skinIndex].optInt("inverseBindMatrices", -1).takeIf { it >= 0 }?.let { accessor ->
                charge(accessor)
                read(accessor, expect = joints.length(), matrices = true)?.takeIf { it.second == 16 }?.first ?: return@getOrPut null
            }
            val matrices = Array(joints.length()) { j ->
                val world = worlds.getOrNull(joints.optInt(j, -1)) ?: return@getOrPut null
                if (inverse == null) world else multiply(world, inverse.copyOfRange(j * 16, j * 16 + 16))
            }
            if (builder.skinned && (0 until joints.length()).all { joints.optInt(it, -1) in boneOf }) {
                skinBase[skinIndex] = tableBones.size
                for (j in 0 until joints.length()) {
                    tableBones.add(boneOf.getValue(joints.optInt(j, -1)))
                    for (i in 0..15) tableInverse.add((inverse?.get(j * 16 + i) ?: if (i % 5 == 0) 1f else 0f) * if (i in 4..11) -1f else 1f)
                }
            }
            matrices
        }

        fun addMesh(meshIndex: Int, world: FloatArray, skinIndex: Int = -1) {
            val normalMatrix = normalMatrix(world)
            val mirrored = determinant(world) < 0
            val pose = if (skinIndex >= 0) pose(skinIndex) else null
            for (primitive in meshes.getOrNull(meshIndex)?.optJSONArray("primitives").objects()) {
                val mode = primitive.optInt("mode", 4)
                if (mode !in 4..6) continue
                val attributes = primitive.optJSONObject("attributes") ?: continue
                charge(attributes.optInt("POSITION", -1))
                charge(primitive.optInt("indices", -1))
                val (positions, pc) = read(attributes.optInt("POSITION", -1), flaggedOnly = true) ?: continue
                if (pc != 3) continue
                val count = positions.size / 3
                val normals = read(attributes.optInt("NORMAL", -1), expect = count)?.first?.takeIf { it.size == count * 3 }
                val uvs = read(attributes.optInt("TEXCOORD_0", -1), expect = count)?.first?.takeIf { it.size == count * 2 }
                val colours = read(attributes.optInt("COLOR_0", -1), expect = count)?.takeIf { it.first.size == count * it.second && it.second >= 3 }
                val part = primitive.optInt("material", -1).takeIf { it in materials.indices } ?: -1
                val material = materials.getOrNull(part)
                val pbr = material?.optJSONObject("pbrMetallicRoughness")
                val factor = pbr?.optJSONArray("baseColorFactor")?.let { f -> FloatArray(4) { f.optDouble(it, 1.0).toFloat() } } ?: floatArrayOf(1f, 1f, 1f, 1f)
                val source = pbr?.optJSONObject("baseColorTexture")?.optInt("index", -1)?.let { textureList.getOrNull(it) }?.optInt("source", -1)?.takeIf { it in images.indices }
                fun own() = source?.let { src -> imageSlots.getOrPut(src) { image(src)?.let { textures += it; textures.size - 1 } ?: -1 } } ?: -1
                val look = looks[part]
                val slot = when {
                    look == null -> own()
                    look.isEmpty() -> -1
                    else -> lookSlots.getOrPut(look) { open(look)?.let { textures += it; textures.size - 1 } ?: -1 }
                }
                if (part >= 0 && part !in info) {
                    val uri = source?.let { images[it].optString("uri") }.orEmpty()
                    val embedded = source != null && (uri.isEmpty() || uri.startsWith("data:"))
                    val found = source != null && (look == null && slot >= 0 || look != null && (if (uri.isEmpty()) images[source].has("bufferView") else load(uri, open) != null))
                    info[part] = ModelMaterial(part, material?.optString("name").orEmpty(), source?.let { if (embedded) images[it].optString("name") else modelFileName(decoded(uri)) },
                        embedded, found, slot)
                }
                val list = indices(primitive, count)
                val joints = if (pose == null) null else read(attributes.optInt("JOINTS_0", -1), normalizeInts = false, expect = count)
                    ?.takeIf { it.second == 4 && it.first.size == count * 4 }?.first
                val weights = if (joints == null) null else read(attributes.optInt("WEIGHTS_0", -1), expect = count)
                    ?.takeIf { it.second == 4 && it.first.size == count * 4 }?.first
                val skinned = pose != null && joints != null && weights != null && joints.all { it.toInt() in pose.indices }
                if (skinned) { charge(attributes.optInt("JOINTS_0", -1)); charge(attributes.optInt("WEIGHTS_0", -1)) }
                val base = if (skinned) skinBase[skinIndex] ?: -1 else -1
                val flip = if (skinned) determinant(pose[0]) < 0 else mirrored
                val blend = FloatArray(16)
                builder.reserve(count, list.size)
                val first = builder.count
                for (v in 0 until count) {
                    var matrix = world
                    var cofactors = normalMatrix
                    if (skinned) {
                        var total = 0f
                        for (k in 0..3) total += max(weights[v * 4 + k], 0f)
                        if (total > 0f) {
                            blend.fill(0f)
                            for (k in 0..3) {
                                val w = max(weights[v * 4 + k], 0f) / total
                                if (w <= 0f) continue
                                val joint = pose[joints[v * 4 + k].toInt()]
                                for (i in 0..15) blend[i] += joint[i] * w
                            }
                            matrix = blend
                            cofactors = normalMatrix(blend)
                        }
                    }
                    val p = transform(matrix, positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2])
                    val n = normals?.let { transformNormal(cofactors, it[v * 3], it[v * 3 + 1], it[v * 3 + 2]) }
                    val c = colours?.let { (values, comps) -> FloatArray(4) { i -> if (i < comps) values[v * comps + i] else 1f } } ?: floatArrayOf(1f, 1f, 1f, 1f)
                    val made = builder.vertex(p[0], p[1], p[2], n, uvs?.get(v * 2) ?: 0f, uvs?.get(v * 2 + 1) ?: 0f,
                        argb(factor[0] * c[0], factor[1] * c[1], factor[2] * c[2], factor[3] * c[3]))
                    if (base >= 0 && matrix === blend) {
                        var total = 0f
                        for (k in 0..3) total += max(weights!![v * 4 + k], 0f)
                        builder.skin(made, positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2],
                            normals?.let { floatArrayOf(it[v * 3], it[v * 3 + 1], it[v * 3 + 2]) },
                            { k -> if (weights!![v * 4 + k] > 0f) base + joints!![v * 4 + k].toInt() else -1 }, { k -> max(weights!![v * 4 + k], 0f) / total })
                    }
                }
                val back = if (!twoSided && doubleSided(primitive)) builder.reversed(first, count, normals == null) else -1
                fun emit(a: Int, b: Int, c: Int) {
                    if (a !in 0 until count || b !in 0 until count || c !in 0 until count) return
                    if (flip) builder.triangle(first + a, first + c, first + b, slot, part) else builder.triangle(first + a, first + b, first + c, slot, part)
                    if (back < 0) return
                    if (flip) builder.triangle(back + a, back + b, back + c, slot, part) else builder.triangle(back + a, back + c, back + b, slot, part)
                }
                when (mode) {
                    4 -> for (k in 0 until list.size - 2 step 3) emit(list[k], list[k + 1], list[k + 2])
                    5 -> for (k in 0 until list.size - 2) {
                        if (k % 2 == 0) emit(list[k], list[k + 1], list[k + 2]) else emit(list[k + 1], list[k], list[k + 2])
                    }
                    else -> for (k in 1 until list.size - 1) emit(list[0], list[k], list[k + 1])
                }
            }
        }

        val visited = BooleanArray(nodes.size)
        val holders = Ints()
        val parentOf = IntArray(nodes.size) { -1 }
        val order = Ints()
        fun visit(index: Int, parent: FloatArray, depth: Int, from: Int) {
            val node = nodes.getOrNull(index) ?: return
            if (depth > 64 || visited[index]) return
            visited[index] = true
            parentOf[index] = from
            order.add(index)
            val world = multiply(parent, localMatrix(node))
            worlds[index] = world
            if (node.has("mesh")) holders.add(index)
            node.optJSONArray("children")?.let { c -> for (i in 0 until c.length()) visit(c.optInt(i, -1), world, depth + 1, index) }
        }

        fun skeleton(): Skeleton? {
            if (!builder.skinned) return null
            val used = (0 until holders.size).map { nodes[holders.data[it]].optInt("skin", -1) }.filter { it in skins.indices }.toSet()
            val joints = used.flatMap { s -> skins[s].optJSONArray("joints")?.let { j -> List(j.length()) { j.optInt(it, -1) } }.orEmpty() }
                .filter { worlds.getOrNull(it) != null }.toHashSet()
            val under = BooleanArray(nodes.size)
            for (k in 0 until order.size) order.data[k].let { n -> val up = parentOf[n]; under[n] = up >= 0 && (up in joints || under[up]) }
            val bones = (0 until order.size).map { order.data[it] }.filter { it in joints || under[it] && !nodes[it].has("mesh") }
            if (bones.isEmpty()) return null
            bones.forEachIndexed { i, n -> boneOf[n] = i }
            val locals = FloatArray(bones.size * 16)
            val list = bones.mapIndexed { i, n ->
                var above = parentOf[n]
                while (above >= 0 && above !in boneOf) above = parentOf[above]
                var local = if (above < 0) worlds[n]!! else localMatrix(nodes[n])
                if (above >= 0) {
                    var up = parentOf[n]
                    while (up >= 0 && up != above) { local = multiply(localMatrix(nodes[up]), local); up = parentOf[up] }
                }
                local.copyInto(locals, i * 16)
                ModelBone(n, nodes[n].optString("name").trim(), boneOf[above] ?: -1)
            }
            return Skeleton(list, locals, IntArray(0), FloatArray(0))
        }

        val identity = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
        if (nodes.isEmpty()) meshes.indices.forEach { addMesh(it, identity) }
        else {
            val scenes = json.optJSONArray("scenes").objects()
            val children = nodes.flatMap { n -> n.optJSONArray("children")?.let { c -> List(c.length()) { c.optInt(it, -1) } }.orEmpty() }.toSet()
            val roots = scenes.getOrNull(json.optInt("scene", 0))?.optJSONArray("nodes")?.let { r -> List(r.length()) { r.optInt(it, -1) } }
                ?: nodes.indices.filter { it !in children }
            roots.forEach { visit(it, identity, 0, -1) }
            val frame = skeleton()
            for (k in 0 until holders.size) {
                val node = nodes[holders.data[k]]
                addMesh(node.getInt("mesh"), worlds[holders.data[k]]!!, if (skin) node.optInt("skin", -1) else -1)
            }
            if (frame != null) return builder.build(textures, twoSided, info.values.toList(), Skeleton(frame.bones, frame.locals, tableBones.array(), tableInverse.array()))
        }
        return builder.build(textures, twoSided, info.values.toList())
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { optJSONObject(it) ?: JSONObject() }

    private fun le(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
        ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun load(uri: String, open: (String) -> ByteArray?): ByteArray? =
        if (uri.startsWith("data:")) runCatching { Base64.getDecoder().decode(uri.substringAfter(',')) }.getOrNull()
        else open(decoded(uri))

    private fun decoded(uri: String) = runCatching { java.net.URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8") }.getOrDefault(uri)

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
    var best: Mesh? = null
    var low = 0
    var high = 4097
    var cells = 256
    repeat(12) {
        val result = clustered(mesh, cells)
        val count = result.triangleCount
        if (count <= budget) {
            if (best.let { it == null || count > it.triangleCount }) best = result
            if (count >= budget * 0.85) return result
            low = cells
        } else high = cells
        if (high - low <= 1) return best ?: clustered(mesh, 4)
        val guess = (cells * sqrt(budget * 0.95 / max(1, count))).toInt()
        cells = if (guess in low + 1 until high) guess else (low + high) / 2
    }
    return best ?: clustered(mesh, 4)
}

private const val UV_CELLS = 512

private fun clustered(mesh: Mesh, cells: Int): Mesh {
    val p = mesh.positions
    val group = IntArray(mesh.vertexCount)
    val groups = HashMap<Long, Int>()
    fun cell(v: Float) = ((v + 1f) / 2f * cells).toInt().coerceIn(0, cells - 1).toLong()
    for (v in 0 until mesh.vertexCount) {
        group[v] = groups.getOrPut((cell(p[v * 3]) * cells + cell(p[v * 3 + 1])) * cells + cell(p[v * 3 + 2])) { groups.size }
    }
    val centres = DoubleArray(groups.size * 3)
    val members = IntArray(groups.size)
    for (v in 0 until mesh.vertexCount) {
        members[group[v]]++
        for (i in 0..2) centres[group[v] * 3 + i] += p[v * 3 + i].toDouble()
    }
    val corners = Ints()
    val slots = Ints()
    val parts = Ints()
    val seen = HashSet<Triple<Int, Int, Int>>()
    val t = mesh.triangles
    for (k in 0 until mesh.triangleCount) {
        val a = group[t[k * 3]]; val b = group[t[k * 3 + 1]]; val c = group[t[k * 3 + 2]]
        if (a == b || b == c || a == c) continue
        val first = min(a, min(b, c))
        val key = when (first) { a -> Triple(a, b, c); b -> Triple(b, c, a); else -> Triple(c, a, b) }
        if (!seen.add(key)) continue
        for (i in 0..2) corners.add(t[k * 3 + i])
        slots.add(mesh.slots[k])
        parts.add(mesh.parts[k])
    }
    val output = HashMap<Pair<Long, Int>, Int>()
    fun facing(v: Int) = (0..2).fold(0) { bins, i -> bins * 5 + (kotlin.math.round(mesh.normals[v * 3 + i] * 2f).toInt().coerceIn(-2, 2) + 2) }
    val outGroup = Ints()
    val firsts = Ints()
    val sums = ArrayList<DoubleArray>()
    val triangles = IntArray(corners.size)
    fun quantised(v: Float) = (kotlin.math.floor(v.toDouble() * UV_CELLS).toLong() and 0xffff)
    for (k in 0 until corners.size) {
        val v = corners.data[k]
        val key = (group[v].toLong() shl 40) or (((slots.data[k / 3] + 1).toLong() and 0xff) shl 32) or
            (quantised(mesh.uvs[v * 2]) shl 16) or quantised(mesh.uvs[v * 2 + 1])
        val out = output.getOrPut(key to facing(v) + 125 * (parts.data[k / 3] + 1)) { outGroup.add(group[v]); firsts.add(v); sums.add(DoubleArray(10)); sums.size - 1 }
        triangles[k] = out
        val s = sums[out]
        for (i in 0..2) s[i] += mesh.normals[v * 3 + i].toDouble()
        s[3] += mesh.uvs[v * 2].toDouble(); s[4] += mesh.uvs[v * 2 + 1].toDouble()
        val colour = mesh.colors[v]
        for (i in 0..3) s[5 + i] += ((colour ushr (24 - 8 * i)) and 0xff).toDouble()
        s[9] += 1.0
    }
    val n = sums.size
    val positions = FloatArray(n * 3) { centres[outGroup.data[it / 3] * 3 + it % 3].toFloat() / members[outGroup.data[it / 3]] }
    val normals = FloatArray(n * 3)
    for (o in 0 until n) {
        val s = sums[o]
        val length = sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2])
        if (length > 0) for (i in 0..2) normals[o * 3 + i] = (s[i] / length).toFloat()
    }
    val uvs = FloatArray(n * 2) { (sums[it / 2][3 + it % 2] / sums[it / 2][9]).toFloat() }
    val colors = IntArray(n) { o -> (0..3).fold(0) { c, i -> (c shl 8) or (sums[o][5 + i] / sums[o][9] + 0.5).toInt().coerceIn(0, 255) } }
    val skin = mesh.skin?.let { s ->
        val binds = DoubleArray(groups.size * 3)
        for (v in 0 until mesh.vertexCount) for (i in 0..2) binds[group[v] * 3 + i] += s.bind[v * 3 + i].toDouble()
        MeshSkin(s.bones, s.locals, s.jointBones, s.inverseBinds, s.normalise,
            FloatArray(n * 3) { (binds[outGroup.data[it / 3] * 3 + it % 3] / members[outGroup.data[it / 3]]).toFloat() },
            FloatArray(n * 3) { s.bindNormals[firsts.data[it / 3] * 3 + it % 3] },
            IntArray(n * 4) { s.joints[firsts.data[it / 4] * 4 + it % 4] }, FloatArray(n * 4) { s.weights[firsts.data[it / 4] * 4 + it % 4] })
    }
    return Mesh(positions, normals, uvs, colors, triangles, slots.array(), mesh.textures, mesh.twoSided, parts.array(), mesh.materials, skin)
}
