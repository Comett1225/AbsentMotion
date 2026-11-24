package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.engine.EngineThread
import app.absentmotion.media.ModelFactsCache
import app.absentmotion.media.decimated
import app.absentmotion.media.modelLooks
import app.absentmotion.media.modelPictureName
import app.absentmotion.media.modelPictureShown
import app.absentmotion.media.modelSkinned
import app.absentmotion.media.modelSource
import app.absentmotion.media.parseModel
import app.absentmotion.model.Scene
import app.absentmotion.model.material
import app.absentmotion.model.materialTint
import app.absentmotion.model.modelKey
import app.absentmotion.model.modelUris
import app.absentmotion.model.posed
import app.absentmotion.model.withMaterialPicture
import app.absentmotion.model.withMaterialTint
import app.absentmotion.model.withoutPose
import app.absentmotion.ui.editor.panels.missingPictureMatches
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.atan2

internal fun riggedGltf(rows: Int = 3): String {
    val n = rows * 2
    val bytes = ByteBuffer.allocate(n * 44 + (rows - 1) * 12 + 4 + 128).order(ByteOrder.LITTLE_ENDIAN)
    val y = FloatArray(rows) { 2f * it / (rows - 1) }
    for (r in 0 until rows) for (x in listOf(-0.2f, 0.2f)) { bytes.putFloat(x); bytes.putFloat(y[r]); bytes.putFloat(0f) }
    repeat(n) { bytes.putFloat(0f); bytes.putFloat(0f); bytes.putFloat(1f) }
    repeat(n) { bytes.put(byteArrayOf(0, 1, 0, 0)) }
    val jointsAt = n * 24
    for (r in 0 until rows) repeat(2) { val tip = (y[r] - 0.5f).coerceIn(0f, 1f); bytes.putFloat(1 - tip); bytes.putFloat(tip); bytes.putFloat(0f); bytes.putFloat(0f) }
    val indicesAt = n * 44
    for (r in 0 until rows - 1) intArrayOf(2 * r, 2 * r + 1, 2 * r + 3, 2 * r, 2 * r + 3, 2 * r + 2).forEach { bytes.putShort(it.toShort()) }
    val inverseAt = indicesAt + ((rows - 1) * 12 + 3) / 4 * 4
    bytes.position(inverseAt)
    for (j in 0..1) for (i in 0..15) bytes.putFloat(if (i % 5 == 0) 1f else if (i == 13 && j == 1) -1f else 0f)
    val data = bytes.array().copyOf(inverseAt + 128)
    val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(data)
    return """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0,2]}],
        "nodes":[{"name":"Root","children":[1]},{"name":"Tip","translation":[0,1,0]},{"mesh":0,"skin":0}],
        "skins":[{"joints":[0,1],"inverseBindMatrices":5}],
        "materials":[{"name":"Skin","doubleSided":true}],
        "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"JOINTS_0":2,"WEIGHTS_0":3},"indices":4,"material":0}]}],
        "buffers":[{"byteLength":${data.size},"uri":"$uri"}],
        "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":${n * 12}},{"buffer":0,"byteOffset":${n * 12},"byteLength":${n * 12}},
          {"buffer":0,"byteOffset":$jointsAt,"byteLength":${n * 4}},{"buffer":0,"byteOffset":${jointsAt + n * 4},"byteLength":${n * 16}},
          {"buffer":0,"byteOffset":$indicesAt,"byteLength":${(rows - 1) * 12}},{"buffer":0,"byteOffset":$inverseAt,"byteLength":128}],
        "accessors":[{"bufferView":0,"componentType":5126,"count":$n,"type":"VEC3"},{"bufferView":1,"componentType":5126,"count":$n,"type":"VEC3"},
          {"bufferView":2,"componentType":5121,"count":$n,"type":"VEC4"},{"bufferView":3,"componentType":5126,"count":$n,"type":"VEC4"},
          {"bufferView":4,"componentType":5123,"count":${(rows - 1) * 6},"type":"SCALAR"},{"bufferView":5,"componentType":5126,"count":2,"type":"MAT4"}]}"""
}

class DeskModelPoseTest {
    private val out = File("build/model-pose").canonicalFile

    private val quad = "mtllib Model.mtl\nv -1 -1 0\nv 1 -1 0\nv 1 1 0\nv -1 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nvn 0 0 1\nusemtl Part1Mtl\nf 1/1/1 2/2/1 3/3/1\nf 1/1/1 3/3/1 4/4/1\n"

    private fun png(file: File, colour: Color) {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = colour; fillRect(0, 0, 8, 8); dispose() }
        ImageIO.write(image, "png", file)
    }

    private fun folder(name: String, withPicture: Boolean): File {
        val dir = File(out, name).apply { deleteRecursively(); mkdirs() }
        File(dir, "Model.obj").writeText(quad)
        File(dir, "Model.mtl").writeText("newmtl Part1Mtl\nKd 1 1 1\nmap_Kd Model_tex.png\n")
        if (withPicture) png(File(dir, "Model_tex.png"), Color(30, 200, 40))
        png(File(dir, "texture-red.png"), Color(255, 0, 0))
        return File(dir, "Model.obj")
    }

    private fun opener(dir: File): (String) -> ByteArray? = { name -> File(dir, name).takeIf { it.isFile }?.readBytes() }

    private fun uri(file: File) = file.toURI().toString().replaceFirst("file:/", "file:///")

    @Test fun materialsSayWhichPictureIsMissingAndOverridesReplaceOrRemoveIt() {
        val file = folder("materials", withPicture = false)
        val open = opener(file.parentFile)
        val plain = parseModel("Model.obj", file.readBytes(), open)
        val m = plain.materials.single()
        assertEquals(listOf(0, "Part1Mtl", "Model_tex.png", false, false, -1), listOf(m.index, m.name, m.picture, m.embedded, m.found, m.texture))
        assertEquals(listOf(0, 0), plain.parts.toList())
        val replaced = parseModel("Model.obj", file.readBytes(), open, looks = mapOf(0 to "texture-red.png"))
        assertEquals(1, replaced.textures.size)
        assertEquals(listOf(0, 0), replaced.slots.toList())
        assertEquals(0, replaced.materials.single().texture)
        assertFalse(replaced.materials.single().found)
        png(File(file.parentFile, "Model_tex.png"), Color.GREEN)
        val removed = parseModel("Model.obj", file.readBytes(), open, looks = mapOf(0 to ""))
        assertEquals(listOf(-1, -1), removed.slots.toList())
        assertTrue(removed.materials.single().found)
        val gltf = parseModel("rig.gltf", riggedGltf().toByteArray(), { null })
        assertEquals(listOf("Skin"), gltf.materials.map { it.name })
        assertNull(gltf.materials.single().picture)
        assertTrue(gltf.parts.all { it == 0 })
    }

    @Test fun texturesAndPosesAreSavedInTheProjectAndKeyTheModel() {
        val scene = Scene.parse("""<scene title="m" width="100" height="100" fps="30" totalTime="999">
<model id="1" startTime="0" endTime="999" src="file:///m/a.glb" pose="skin"><property name="size" type="float" value="80"/></model></scene>""")
        var layer = scene.layers.single()
        assertEquals("skin:file:///m/a.glb", layer.node.modelKey())
        layer = layer.withMaterialPicture(2, "texture-red.png").withMaterialTint(2, 0xff336699.toInt()).withMaterialPicture(0, "")
            .withProp("bone.4.z", "float", "30").withProp("bone.4.x", "float", "-10")
        val key = layer.node.modelKey()!!
        assertEquals("file:///m/a.glb", modelSource(key))
        assertTrue(modelSkinned(key))
        assertEquals(mapOf(2 to "texture-red.png", 0 to ""), modelLooks(key))
        assertEquals(key, layer.withMaterialTint(2, 0xff000000.toInt()).node.modelKey())
        val saved = Scene.parse(scene.update(1) { layer }.toXml()).layers.single()
        assertEquals(0xff336699.toInt(), saved.materialTint(2))
        assertEquals("texture-red.png", saved.material(2)?.get("picture"))
        assertEquals("30", saved.propText("bone.4.z")?.let { "%.0f".format(it.toFloat()) })
        assertEquals(setOf(key), Scene(saved.node.let { n -> scene.node.copy(children = listOf(n)) }).node.modelUris())
        assertTrue(saved.posed)
        assertFalse(saved.withoutPose().posed)
        assertEquals("80", saved.withoutPose().propText("size")?.let { "%.0f".format(it.toFloat()) })
        assertNull(saved.withMaterialTint(2, -1).withMaterialPicture(2, null).material(2))
        assertEquals(-1, saved.materialTint(7))
    }

    @Test fun pickedPicturesMatchTheMissingNamesThenFillInOrder() {
        assertEquals(listOf(1 to 1, 0 to 0), missingPictureMatches(listOf(0 to "a.png", 1 to "Body_Tex.PNG"), listOf("x.png", "body_tex (1).png")).sortedByDescending { it.first })
        assertEquals(listOf(0 to 0, 1 to 0), missingPictureMatches(listOf(0 to "shared.png", 1 to "shared.png"), listOf("shared.png")))
        assertEquals("texture-My_pic.png", modelPictureName("C:\\Users\\me\\My pic.png", listOf("Model.obj")))
        assertEquals("texture-2~a.png", modelPictureName("a.png", listOf("TEXTURE-a.png")))
        assertEquals("a.png", modelPictureShown(modelPictureName("a.png", listOf("texture-a.png"))))
        assertEquals("3-photo.png", modelPictureShown(modelPictureName("3-photo.png", emptyList())))
    }

    @Test fun bonesBelowTheSkinJointsAreListedToo() {
        val gltf = riggedGltf(3).replace("""{"name":"Tip","translation":[0,1,0]}""", """{"name":"Tip","translation":[0,1,0],"children":[3]}""")
            .replace(""",{"mesh":0,"skin":0}]""", """,{"mesh":0,"skin":0},{"name":"End","translation":[0,0.5,0]}]""")
        val skin = parseModel("rig.gltf", gltf.toByteArray(), { null }, skin = true).skin!!
        assertEquals(listOf("Root" to -1, "Tip" to 0, "End" to 1), skin.bones.map { it.name to it.parent })
        assertEquals(listOf(0, 1), skin.jointBones.toList())
    }

    @Test fun aRiggedGltfKeepsItsBonesAndWeightsThroughSimplifying() {
        val mesh = parseModel("rig.gltf", riggedGltf(9).toByteArray(), { null }, skin = true)
        val skin = mesh.skin!!
        assertEquals(listOf("Root" to -1, "Tip" to 0), skin.bones.map { it.name to it.parent })
        assertEquals(listOf(0, 1), skin.bones.map { it.node })
        assertEquals(mesh.vertexCount * 4, skin.joints.size)
        for (v in 0 until mesh.vertexCount) assertEquals(1f, (0..3).sumOf { skin.weights[v * 4 + it].toDouble() }.toFloat(), 1e-4f)
        assertNull(parseModel("rig.gltf", riggedGltf(9).toByteArray(), { null }).skin)
        val small = decimated(mesh, 6)
        assertTrue(small.triangleCount <= 6)
        val s = small.skin!!
        assertEquals(small.vertexCount * 3, s.bind.size)
        assertEquals(small.vertexCount * 4, s.weights.size)
        assertTrue(s.joints.all { it in -1..1 })
        assertEquals(small.triangleCount, small.parts.size)
        assertEquals(skin.ints().size - mesh.vertexCount * 4 + small.vertexCount * 4, s.ints().size)
    }

    private fun scene(src: String, extra: String) = Scene.parse("""<scene title="m" width="400" height="400" fps="30" totalTime="999" bgcolor="#ff000000">
<model id="1" startTime="0" endTime="999" src="$src" pose="skin"><transform><location value="200,200,0"/></transform>
<property name="size" type="float" value="100"/><property name="ambient" type="float" value="1"/><property name="intensity" type="float" value="0"/>$extra</model></scene>""")

    private fun <T> withEngine(block: suspend (EngineThread) -> T): T = runBlocking {
        System.setProperty("am.data", out.resolve("data").path)
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        try {
            engine.effects.first { it.definitions.isNotEmpty() }
            block(engine)
        } finally {
            engine.close()
        }
    }

    private suspend fun EngineThread.joints(s: Scene, timeMs: Int): FloatArray {
        setScene(s)
        awaitMedia()
        return checkNotNull(modelJoints(1, timeMs))
    }

    private suspend fun EngineThread.picture(s: Scene, timeMs: Int = 0): (Int, Int) -> Int {
        val pixels = checkNotNull(exportSession(s.toXml()) { still(timeMs, 400, 400) })
        return { x, y -> (pixels[(y * 400 + x) * 4].toInt() and 255) + (pixels[(y * 400 + x) * 4 + 1].toInt() and 255) + (pixels[(y * 400 + x) * 4 + 2].toInt() and 255) }
    }

    @Test fun turningABoneMovesItsChildJointsAndBendsTheMeshInPreviewAndExport() = withEngine { engine ->
        val file = File(out, "rig/model-1/rig.gltf").apply { parentFile.deleteRecursively(); parentFile.mkdirs(); writeText(riggedGltf(9)) }
        val src = uri(file)
        val rest = engine.joints(scene(src, ""), 0)
        val r = 100f / kotlin.math.sqrt(1.04f)
        assertArrayEquals(floatArrayOf(200f, 200f + r, 200f, 200f), rest, 1.5f)
        val turned = engine.joints(scene(src, """<property name="bone.0.z" type="float" value="90"/>"""), 0)
        assertArrayEquals(floatArrayOf(200f, 200f + r, 200f - r, 200f + r), turned, 1.5f)
        val keyed = scene(src, """<property name="bone.0.z" type="float"><kf t="0" v="0"/><kf t="1" v="90"/></property>""")
        val half = engine.joints(keyed, 500)
        assertEquals(45.0, Math.toDegrees(atan2((half[1] - half[3]).toDouble(), (half[0] - half[2]).toDouble())), 1.0)
        assertArrayEquals(rest, engine.joints(keyed, 0), 1.5f)
        assertEquals(listOf("Root", "Tip"), ModelFactsCache.known.value["skin:$src"]?.bones?.map { it.name })

        val still = engine.picture(scene(src, ""))
        assertTrue(still(200, 150) > 600)
        assertTrue(still(120, 290) < 30)
        val root = engine.picture(scene(src, """<property name="bone.0.z" type="float" value="90"/>"""))
        assertTrue(root(200, 150) < 30)
        assertTrue(root(120, 290) > 600)
        val bent = engine.picture(scene(src, """<property name="bone.1.z" type="float" value="90"/>"""))
        assertTrue(bent(200, 250) > 600)
        assertTrue(bent(200, 130) < 30)
        assertTrue(bent(150, 200) > 600)
    }

    @Test fun replacedTintedAndRemovedPicturesRenderInExport() = withEngine { engine ->
        val file = folder("render", withPicture = true)
        val src = uri(file)
        fun rgb(extra: String): IntArray = runBlocking {
            val s = Scene.parse(scene(src, extra).toXml().replace(" pose=\"skin\"", ""))
            val pixels = checkNotNull(engine.exportSession(s.toXml()) { still(0, 400, 400) })
            IntArray(3) { pixels[(200 * 400 + 200) * 4 + it].toInt() and 255 }
        }
        val own = rgb("")
        assertTrue(own.toList().toString(), own[1] > 150 && own[0] < 80)
        val red = rgb("""<material index="0" picture="texture-red.png"/>""")
        assertTrue(red.toList().toString(), red[0] > 230 && red[1] < 30 && red[2] < 30)
        val dim = rgb("""<material index="0" picture="texture-red.png" tint="#ff808080"/>""")
        assertEquals(dim.toList().toString(), 128f, dim[0].toFloat(), 4f)
        val blue = rgb("""<material index="0" picture="" tint="#ff0000ff"/>""")
        assertTrue(blue.toList().toString(), blue[2] > 230 && blue[0] < 30 && blue[1] < 30)
        val facts = ModelFactsCache.known.value[uri(file) + "\u001e0\u001ftexture-red.png"]
        assertEquals(listOf(true), facts?.materials?.map { it.found })
        assertNotNull(facts?.pictures?.single())
        assertEquals(8, facts!!.pictures.single()!!.width)
    }
}
