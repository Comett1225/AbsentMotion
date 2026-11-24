package app.absentmotion.pc

import android.content.Desk
import app.absentmotion.engine.EngineThread
import app.absentmotion.media.companionName
import app.absentmotion.media.modelFiles
import app.absentmotion.media.parseModel
import app.absentmotion.model.Scene
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class DeskModelTextureTest {
    private val out = File("build/model-texture").canonicalFile

    private fun obj(mtllib: String) = """# Roblox exported OBJ
mtllib $mtllib
v -1 -1 0
v 1 -1 0
v 1 1 0
v -1 1 0
vt 0 0
vt 1 0
vt 1 1
vt 0 1
vn 0 0 1
usemtl Part1Mtl
f 1/1/1 2/2/1 3/3/1
f 1/1/1 3/3/1 4/4/1
"""

    private fun mtl(map: String, keyword: String = "map_Kd") =
        "\uFEFFnewmtl Part1Mtl\nKa 1 1 1\nKd 1.000000 1.000000 1.000000\nKs 0 0 0\nillum 2\n$keyword $map\nmap_d $map\nd 1.000000\n"

    private fun folder(name: String, mtllib: String, mtlFile: String?, mtlText: String, texture: String?): File {
        val dir = File(out, name).apply { deleteRecursively(); mkdirs() }
        File(dir, "Model.obj").writeText(obj(mtllib))
        mtlFile?.let { File(dir, it).writeText(mtlText) }
        texture?.let {
            val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply { color = Color(30, 200, 40); fillRect(0, 0, 8, 8); dispose() }
            ImageIO.write(image, "png", File(dir, it))
        }
        return File(dir, "Model.obj")
    }

    private fun textures(file: File): Int {
        val (bytes, open) = modelFiles(Desk.context.contentResolver, file.toURI().toString())
        return parseModel(file.name, bytes, open).textures.size
    }

    @Test fun companionFilesAreFoundTheWayExportersAndPickersNameThem() {
        val names = listOf("Model.obj", "export.mtl", "Model_Tex.PNG", "stone (1).png", "e8f0d0a8bc6c3d0b.png")
        assertEquals("Model_Tex.PNG", companionName("model_tex.png", names))
        assertEquals("Model_Tex.PNG", companionName("C:\\Users\\me\\My Stuff\\Model_Tex.png", names))
        assertEquals("Model_Tex.PNG", companionName("\"textures/Model_Tex.PNG\"", names))
        assertEquals("stone (1).png", companionName("stone.png", names))
        assertEquals("export.mtl", companionName("Model.mtl", names))
        assertEquals("e8f0d0a8bc6c3d0b.png", companionName("e8f0d0a8bc6c3d0b.png", names))
        assertNull(companionName("missing.png", names))
        assertNull(companionName("Model.mtl", names + "other.mtl"))
        assertEquals("Model.mtl", companionName("Model.mtl", names + "Model.mtl"))
    }

    @Test fun robloxStyleObjTexturesLoadWhenTheNamesDoNotMatchExactly() {
        assertEquals(1, textures(folder("plain", "Model.mtl", "Model.mtl", mtl("Model_tex.png"), "Model_tex.png")))
        assertEquals(1, textures(folder("quoted", "\"Model.mtl\"", "Model.mtl", mtl("\"My tex.png\""), "My tex.png")))
        assertEquals(1, textures(folder("windows", "C:\\Users\\me\\Desktop\\Model.mtl", "Model.mtl", mtl("C:\\Users\\me\\My Stuff\\Model tex.png"), "Model tex.png")))
        assertEquals(1, textures(folder("keyword", "Model.mtl", "Model.mtl", mtl("Model_tex.png", "map_kd"), "Model_tex.png")))
        assertEquals(1, textures(folder("renamed", "Model.mtl", "Model (1).mtl", mtl("Model_tex.png"), "Model_tex (1).png")))
        assertEquals(0, textures(folder("missing", "Model.mtl", "Model.mtl", mtl("Model_tex.png"), null)))
    }

    @Test fun aGltfWithoutItsBinFileSaysWhichFileIsMissing() {
        val dir = File(out, "gltf").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "Box.gltf").apply {
            writeText("""{"asset":{"version":"2.0"},"buffers":[{"uri":"Box%20Data.bin","byteLength":36}],"bufferViews":[{"buffer":0,"byteLength":36}],
                "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}],"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],"nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}]}""")
        }
        try {
            textures(file)
            fail("a missing .bin must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message.orEmpty().contains("Box Data.bin"))
        }
    }

    @Test fun aTexturedObjIsDrawnWithItsPictureNotWhite() = runBlocking {
        System.setProperty("am.data", out.resolve("data").path)
        val textured = folder("render", "Model.mtl", "export.MTL", mtl("MODEL_TEX.png"), "Model_tex.png").toURI().toString().replaceFirst("file:/", "file:///")
        val engine = EngineThread(0xff000000.toInt(), Desk.context)
        try {
            engine.effects.first { it.definitions.isNotEmpty() }
            val s = Scene.parse("""<scene title="m" width="200" height="200" fps="30" totalTime="999" bgcolor="#ff000000">
<media uri="$textured" filename="Model.obj" title="Model.obj" type="model/obj" />
<model id="1" startTime="0" endTime="999" src="$textured"><transform><location value="100,100,0"/></transform>
<property name="size" type="float" value="80"/><property name="ambient" type="float" value="1"/></model></scene>""")
            val pixels = checkNotNull(engine.exportSession(s.toXml()) { still(s.frameStart(5), 200, 200) })
            fun at(x: Int, y: Int, c: Int) = pixels[(y * 200 + x) * 4 + c].toInt() and 255
            assertTrue("centre is the green picture: ${(0..2).map { at(100, 100, it) }}", at(100, 100, 1) > 120 && at(100, 100, 0) < 100 && at(100, 100, 2) < 100)
        } finally {
            engine.close()
        }
    }
}
