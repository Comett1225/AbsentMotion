package app.absentmotion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import app.absentmotion.ai.BackgroundRemoval
import app.absentmotion.engine.EngineThread
import app.absentmotion.library.ProjectStore
import app.absentmotion.library.TemplateStore
import app.absentmotion.model.Scene
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal suspend fun checkDevice(context: Context) {
    val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "device-check-").toFile()
    val engine = EngineThread(Color.BLACK, context)
    try {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        for (y in 0 until 20) for (x in 0 until 40) bitmap.setPixel(x, y, if (x < 20) Color.RED else Color.BLUE)
        val image = File(root, "picture.jpg")
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        if (BackgroundRemoval.AVAILABLE) {
            val decode = BackgroundRemoval.javaClass.getDeclaredMethod("decode", Context::class.java, Uri::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            for (tag in listOf(2, 5)) {
                ExifInterface(image).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "$tag"); saveAttributes() }
                val decoded = withContext(Dispatchers.IO) { decode.invoke(BackgroundRemoval, context, Uri.fromFile(image), 256) as Bitmap }
                try {
                    check(Color.blue(decoded.getPixel(10, 10)) > 240 && Color.red(decoded.getPixel(30, 10)) > 240) { "Background-removal EXIF $tag input was not mirrored" }
                } finally { decoded.recycle() }
                Log.i("AMDevice", "ok background-removal input EXIF $tag")
            }
        }
        val projects = ProjectStore(File(root, "library"))
        val media = File(projects.mediaDir("source").apply { mkdirs() }, "picture.jpg")
        image.copyTo(media)
        val scene = Scene.parse("""<scene width="200" height="200" totalTime="999"><shape id="1" startTime="0" endTime="999" fillType="media" fillImage="${media.toURI()}" s=".rect"><transform><location value="100,100,0"/></transform><property name="size" type="vec2" value="40,20"/></shape></scene>""")
        projects.save("source", scene)
        val blocked = File(root, "blocked").apply { writeText("fixture") }
        check(TemplateStore(blocked, context.contentResolver).adopt(projects, "source", engine) == null)
        check(projects.load("source") != null && media.isFile)
        val templates = TemplateStore(File(root, "templates"), context.contentResolver)
        val template = checkNotNull(templates.adopt(projects, "source", engine))
        check(projects.load("source") == null && templates.load(template) != null)
        Log.i("AMDevice", "ok template adoption preserves the project on a filesystem write failure and succeeds on retry")
        val fonts = checkNotNull(engine.fonts)
        val source = checkNotNull(fonts.localFile("googlefonts?name=Droid Sans Mono"))
        val fontFile = File(root, "device-check-${System.nanoTime()}.ttf")
        source.copyTo(fontFile)
        fontFile.appendBytes(byteArrayOf(0, 1, 2, 3))
        val name = "imported?name=${fontFile.name}"
        val text = """<scene width="300" height="200" totalTime="999"><embedScene id="1" startTime="0" endTime="999"><transform><location value="150,100,0"/></transform><scene width="300" height="200" fps="30" totalTime="999"><text id="2" font="$name" fillType="color" size="25" wrapWidth="250" startTime="0" endTime="999"><transform><location value="10,40,0"/></transform><content>WiWi mmii</content><fillColor value="#ffffffff"/></text></scene></embedScene></scene>"""
        engine.setScene(Scene.parse(text), text)
        delay(200)
        val before = checkNotNull(engine.previewForTest(300, 200))
        val existing = fonts.importedFonts().map { it.xml }.toSet()
        val imported = fonts.import(Uri.fromFile(fontFile))
        try {
            check(imported.xml == name)
            delay(200)
            val after = checkNotNull(engine.previewForTest(300, 200))
            check(!before.contentEquals(after)) { "Font import did not redraw nested text" }
            check(after.indices.any { it % 4 != 3 && after[it].toInt() != 0 })
            Log.i("AMDevice", "ok font import redraws nested preview text")
        } finally { if (imported.xml !in existing) fonts.deleteImported(imported.xml) }
        Log.i("AMDevice", "done")
    } finally {
        engine.close()
        root.deleteRecursively()
    }
}
