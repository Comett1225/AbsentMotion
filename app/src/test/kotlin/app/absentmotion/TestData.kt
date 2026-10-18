package app.absentmotion

import java.io.File

object TestData {
    val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "testdata") }.firstOrNull { File(it, "scenes").isDirectory }
        ?: error("No testdata/scenes above ${System.getProperty("user.dir")}")

    val project: File get() = root.parentFile

    fun scene(name: String) = File(root, "scenes/$name.xml")

    val scenes: List<File> get() = File(root, "scenes").listFiles { f -> f.extension == "xml" }!!.sortedBy { it.name }
}
