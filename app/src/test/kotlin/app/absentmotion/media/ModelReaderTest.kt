package app.absentmotion.media

import app.absentmotion.R
import app.absentmotion.i18n.AppText
import app.absentmotion.model.Scene
import app.absentmotion.model.imageUris
import app.absentmotion.model.mediaUris
import app.absentmotion.model.modelUris
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sqrt

class ModelReaderTest {
    private val cube = """
        mtllib box.mtl
        v -1 -1 -1
        v 1 -1 -1
        v 1 1 -1
        v -1 1 -1
        v -1 -1 1
        v 1 -1 1
        v 1 1 1
        v -1 1 1
        vt 0 0
        vt 1 0
        vt 1 1
        vt 0 1
        usemtl painted
        f 5/1 6/2 7/3 8/4
        f 2/1 1/2 4/3 3/4
        usemtl plain
        f 4 8 7 3
        f -8 -7 -3 -4
        f 2 3 7 6
        f 1 5 8 4
    """.trimIndent()
    private val materials = """
        newmtl painted
        Kd 1 0 0
        map_Kd -s 1 1 1 paint.png
        newmtl plain
        Kd 0 0 1
        d 0.5
    """.trimIndent()
    private val png = byteArrayOf(1, 2, 3)

    private fun files(name: String) = when (name) { "box.mtl" -> materials.toByteArray(); "paint.png" -> png; else -> null }

    private fun normal(mesh: Mesh, triangle: Int): FloatArray {
        val p = mesh.positions
        val (a, b, c) = (0..2).map { mesh.triangles[triangle * 3 + it] * 3 }
        val u = FloatArray(3) { p[b + it] - p[a + it] }
        val v = FloatArray(3) { p[c + it] - p[a + it] }
        val n = floatArrayOf(u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
        val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        return FloatArray(3) { n[it] / l }
    }

    private fun centre(mesh: Mesh, triangle: Int) = FloatArray(3) { i -> (0..2).sumOf { mesh.positions[mesh.triangles[triangle * 3 + it] * 3 + i].toDouble() }.toFloat() / 3 }

    @Test fun anObjCubeReadsItsFacesMaterialsAndTexture() {
        val mesh = readModel("box.obj", cube.toByteArray(), ::files)
        assertEquals(12, mesh.triangleCount)
        assertEquals(1, mesh.textures.size)
        assertArrayEquals(png, mesh.textures[0])
        assertEquals(listOf(0, 0, 0, 0) + List(8) { -1 }, mesh.slots.toList())
        val colours = mesh.triangles.map { mesh.colors[it] }.toSet()
        assertEquals(setOf(0xffff0000.toInt(), 0x800000ff.toInt()), colours)
        val radius = (0 until mesh.vertexCount).maxOf { v -> sqrt((0..2).sumOf { (mesh.positions[v * 3 + it] * mesh.positions[v * 3 + it]).toDouble() }) }
        assertEquals(1.0, radius, 1e-5)
        for (t in 0 until mesh.triangleCount) {
            val n = normal(mesh, t)
            val c = centre(mesh, t)
            assertTrue("triangle $t faces outwards", n[0] * c[0] + n[1] * c[1] + n[2] * c[2] > 0)
        }
    }

    @Test fun objUpIsDrawnUpAndTexturesStartAtTheTop() {
        val mesh = readModel("box.obj", cube.toByteArray(), ::files)
        val top = (0 until mesh.triangleCount).first { mesh.slots[it] == -1 && normal(mesh, it)[1] < -0.9f }
        val v = mesh.triangles[top * 3]
        assertTrue(mesh.positions[v * 3 + 1] < 0)
        val front = (0 until mesh.vertexCount).first { mesh.uvs[it * 2] == 0f && mesh.uvs[it * 2 + 1] == 1f && mesh.colors[it] == 0xffff0000.toInt() }
        assertTrue("the bottom of the picture is at the bottom of the model", mesh.positions[front * 3 + 1] > 0)
    }

    @Test fun missingNormalsAreWorkedOutAndPointOutwards() {
        val mesh = readModel("box.obj", cube.toByteArray(), ::files)
        for (v in 0 until mesh.vertexCount) {
            val n = FloatArray(3) { mesh.normals[v * 3 + it] }
            assertEquals(1f, sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]), 1e-4f)
            assertTrue(n[0] * mesh.positions[v * 3] + n[1] * mesh.positions[v * 3 + 1] + n[2] * mesh.positions[v * 3 + 2] > 0)
        }
    }

    private fun floats(vararg v: Float) = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()
    private fun shorts(vararg v: Int) = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putShort(it.toShort()) } }.array()

    private val triangle = floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f) + floats(0f, 0f, 1f, 0f, 0f, 1f) + shorts(0, 1, 2, 0)

    private fun gltf(buffer: String, node: String = """{"mesh":0,"translation":[5,0,0],"scale":[2,2,2]}""", texture: Boolean = false) = """
        {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[$node],
         "meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1},"indices":2,"material":0}]}],
         "materials":[{"doubleSided":true,"pbrMetallicRoughness":{"baseColorFactor":[0,1,0,1]${if (texture) ""","baseColorTexture":{"index":0}""" else ""}}}],
         ${if (texture) """"textures":[{"source":0}],"images":[{"bufferView":3,"mimeType":"image/png"}],""" else ""}
         "buffers":[$buffer],
         "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":24},{"buffer":0,"byteOffset":60,"byteLength":6}${if (texture) """,{"buffer":0,"byteOffset":68,"byteLength":3}""" else ""}],
         "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5126,"count":3,"type":"VEC2"},
                      {"bufferView":2,"componentType":5123,"count":3,"type":"SCALAR"}]}
    """.trimIndent()

    private fun gltfPrimitive(mode: Int): ByteArray {
        val positions = floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f)
        val indices = shorts(0, 1, 2, 3)
        val data = positions + indices
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(data)
        return """
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1,"mode":$mode}]}],
             "buffers":[{"byteLength":${data.size},"uri":"$uri"}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":48},{"buffer":0,"byteOffset":48,"byteLength":8}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":4,"type":"VEC3"},
                          {"bufferView":1,"componentType":5123,"count":4,"type":"SCALAR"}]}
        """.trimIndent().toByteArray()
    }

    @Test fun gltfTriangleStripsAndFansBecomeTriangles() {
        assertEquals(2, readModel("strip.gltf", gltfPrimitive(5)) { null }.triangleCount)
        assertEquals(2, readModel("fan.gltf", gltfPrimitive(6)) { null }.triangleCount)
    }

    @Test fun aGltfWithAnEmbeddedBufferReadsGeometryMaterialAndUvs() {
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(triangle)
        val mesh = readModel("tri.gltf", gltf("""{"byteLength":${triangle.size},"uri":"$uri"}""").toByteArray()) { null }
        assertEquals(1, mesh.triangleCount)
        assertTrue(mesh.twoSided)
        assertEquals(setOf(0xff00ff00.toInt()), mesh.colors.toSet())
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), mesh.uvs, 0f)
        val n = normal(mesh, 0)
        assertArrayEquals(floatArrayOf(0f, 0f, -1f), n, 1e-5f)
    }

    @Test fun aGltfCanReadItsBufferFromAFileNextToIt() {
        val mesh = readModel("tri.gltf", gltf("""{"byteLength":${triangle.size},"uri":"tri%20data.bin"}""").toByteArray()) { if (it == "tri data.bin") triangle else null }
        assertEquals(1, mesh.triangleCount)
    }

    @Test fun aGltfPreservesLiteralPlusSignsInCompanionFileNames() {
        for (name in listOf("tri+data.bin", "tri%2Bdata.bin")) {
            val opened = ArrayList<String>()
            val mesh = readModel("tri.gltf", gltf("""{"byteLength":${triangle.size},"uri":"$name"}""").toByteArray()) {
                opened += it
                if (it == "tri+data.bin") triangle else null
            }
            assertEquals(listOf("tri+data.bin"), opened)
            assertEquals(1, mesh.triangleCount)
        }
    }

    @Test fun pickedModelCompanionsKeepTheNamesThatTheModelReferences() {
        for (name in listOf("tri+data.bin", "tri data.bin", "tri_数据.bin", ".mesh.bin")) {
            assertEquals(name, modelFileName(name))
        }
        assertEquals("mesh.bin", modelFileName("folder/mesh.bin"))
        assertEquals("mesh.bin", modelFileName("folder\\mesh.bin"))
        assertEquals("model", modelFileName(".."))
    }

    private fun timed(seconds: Long, work: () -> Unit) {
        val failure = arrayOfNulls<Throwable>(1)
        val worker = Thread { try { work() } catch (t: Throwable) { failure[0] = t } }.apply { isDaemon = true; start() }
        worker.join(seconds * 1000)
        assertTrue("still reading after $seconds s", !worker.isAlive)
        failure[0]?.let { throw it }
    }

    @Test fun sharedAndCyclicNodeChildrenAreReadOnceInsteadOfForever() {
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(triangle)
        val buffer = """{"byteLength":${triangle.size},"uri":"$uri"}"""
        val fanOut = (0 until 48).joinToString(",") { i -> if (i < 47) """{"children":[${i + 1},${i + 1}]}""" else """{"mesh":0}""" }
        timed(10) { assertEquals(1, readModel("fan.gltf", gltf(buffer, fanOut).toByteArray()) { null }.triangleCount) }
        timed(10) { assertEquals(1, readModel("loop.gltf", gltf(buffer, """{"children":[1]},{"children":[0],"mesh":0}""").toByteArray()) { null }.triangleCount) }
        val many = (0 until 200_000).joinToString(",") { """{"children":[]}""" }
        val noScene = gltf(buffer, many).replace(""""scene":0,"scenes":[{"nodes":[0]}],""", "")
        timed(10) { runCatching { GltfReader.read(noScene.toByteArray(), { null }) } }
    }

    @Test fun instancingOneMeshTooOftenIsRefusedAsTooComplex() {
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(triangle)
        val buffer = """{"byteLength":${triangle.size},"uri":"$uri"}"""
        val nodes = (0 until 50).joinToString(",") { if (it == 0) """{"children":[${(1 until 50).joinToString(",")}],"mesh":0}""" else """{"mesh":0}""" }
        assertEquals(50, GltfReader.read(gltf(buffer, nodes).toByteArray(), { null }, limit = 300).triangleCount)
        try {
            GltfReader.read(gltf(buffer, nodes).toByteArray(), { null }, limit = 299)
            fail("read")
        } catch (e: IllegalArgumentException) {
            assertEquals(AppText.get(R.string.model_err_too_complex), e.message)
        }
        val huge = gltf(buffer).replace(""""count":3,"type":"VEC3"""", """"count":2000000000,"type":"VEC3"""")
        try {
            GltfReader.read(huge.toByteArray(), { null })
            fail("read")
        } catch (e: IllegalArgumentException) {
            assertEquals(AppText.get(R.string.model_err_too_complex), e.message)
        }
    }

    @Test(expected = IllegalArgumentException::class) fun aGltfWhoseBufferFileIsMissingIsRefused() {
        readModel("tri.gltf", gltf("""{"byteLength":${triangle.size},"uri":"gone.bin"}""").toByteArray()) { null }
    }

    @Test fun aMirroredNodeKeepsItsTrianglesFacingOut() {
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(triangle)
        val mesh = readModel("tri.gltf", gltf("""{"byteLength":${triangle.size},"uri":"$uri"}""", """{"mesh":0,"scale":[-1,1,1]}""").toByteArray()) { null }
        assertArrayEquals(floatArrayOf(0f, 0f, -1f), normal(mesh, 0), 1e-5f)
    }

    @Test fun aGlbReadsItsBinaryChunkAndEmbeddedPicture() {
        val bin = triangle + byteArrayOf(9, 8, 7, 0)
        val json = gltf("""{"byteLength":${bin.size}}""", texture = true).toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { 32 } }
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        int(0x46546C67); int(2); int(12 + 8 + json.size + 8 + bin.size)
        int(json.size); int(0x4E4F534A); out.write(json)
        int(bin.size); int(0x004E4942); out.write(bin)
        val mesh = readModel("tri.glb", out.toByteArray()) { null }
        assertEquals(1, mesh.triangleCount)
        assertEquals(listOf(0), mesh.slots.toList())
        assertArrayEquals(byteArrayOf(9, 8, 7), mesh.textures.single())
    }

    @Test fun aModelLayerIsALayerThatUsesItsFile() {
        val scene = Scene.parse("""<scene width="10" height="10"><model id="1" src="file:/m/a.glb"/><shape id="2" fillImage="file:/p.png"/></scene>""")
        assertEquals(listOf("model", "shape"), scene.layers.map { it.kind })
        assertEquals(setOf("file:/m/a.glb"), scene.node.modelUris())
        assertTrue("file:/m/a.glb" in scene.node.mediaUris())
        assertTrue("file:/m/a.glb" !in scene.node.imageUris())
    }

    @Test fun aDenseModelIsReducedToTheBudget() {
        val rows = 120
        val text = StringBuilder()
        for (i in 0..rows) for (j in 0..rows) text.append("v ${i / rows.toFloat()} ${j / rows.toFloat()} ${((i * 7 + j * 3) % 5) / 50f}\n")
        for (i in 0 until rows) for (j in 0 until rows) {
            val a = i * (rows + 1) + j + 1
            text.append("f $a ${a + rows + 1} ${a + rows + 2} ${a + 1}\n")
        }
        val full = ObjReader.read(text.toString(), { null })
        assertEquals(2 * rows * rows, full.triangleCount)
        val small = decimated(full, 2000)
        assertTrue(small.triangleCount in 1..2000)
        assertTrue(small.triangles.all { it in 0 until small.vertexCount })
        assertEquals(small.triangleCount, small.slots.size)
        val big = readModel("dense.obj", text.toString().toByteArray()) { null }
        assertTrue(big.triangleCount <= MODEL_TRIANGLE_BUDGET)
    }

    @Test fun accessorsWithoutDataCannotMakeATinyFileAllocateHugeArrays() {
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(triangle)
        val buffer = """{"byteLength":${triangle.size},"uri":"$uri"}"""
        val noData = """{"componentType":5126,"count":15000000,"type":"VEC3"}"""
        val emptyPositions = gltf(buffer).replace("""{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}""", noData)
        timed(5) { assertEquals(0, GltfReader.read(emptyPositions.toByteArray(), { null }).triangleCount) }
        val hugeNormals = gltf(buffer).replace(""""TEXCOORD_0":1""", """"TEXCOORD_0":1,"NORMAL":3""")
            .replace("""{"bufferView":2,"componentType":5123,"count":3,"type":"SCALAR"}""", """{"bufferView":2,"componentType":5123,"count":3,"type":"SCALAR"},$noData""")
            .replace(""""meshes":[{"primitives":[""", """"meshes":[{"primitives":[""" + List(40) { """{"attributes":{"POSITION":0,"NORMAL":3},"indices":2},""" }.joinToString(""))
        timed(5) { assertEquals(41, GltfReader.read(hugeNormals.toByteArray(), { null }).triangleCount) }
    }

    @Test fun anObjTextureNameKeepsItsSpaces() {
        val obj = "mtllib stone.mtl\nv 0 0 0\nv 1 0 0\nv 0 1 0\nusemtl stone\nf 1 2 3\n"
        val mtl = "newmtl stone\nmap_Kd marble stone.png\n"
        fun spaced(name: String) = when (name) { "stone.mtl" -> mtl.toByteArray(); "marble stone.png" -> png; else -> null }
        val mesh = readModel("stone.obj", obj.toByteArray(), ::spaced)
        assertEquals(1, mesh.textures.size)
        assertArrayEquals(png, mesh.textures[0])
        assertEquals(listOf(0), mesh.slots.toList())
    }

    @Test fun anObjWithTooMuchWorkIsRefused() {
        assertEquals(12, ObjReader.read(cube, ::files, limit = 36).triangleCount)
        try {
            ObjReader.read(cube, ::files, limit = 35)
            fail("read")
        } catch (e: IllegalArgumentException) {
            assertEquals(AppText.get(R.string.model_err_too_complex), e.message)
        }
    }
}
