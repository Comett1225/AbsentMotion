package app.absentmotion.desk

import androidx.compose.runtime.mutableIntStateOf
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

object DeskOpen {
    val projectExtensions = setOf("absproj", "amproj", "xml", "fla", "xfl")
    val arrived = mutableIntStateOf(0)
    val raised = mutableIntStateOf(0)
    private val queue = ConcurrentLinkedQueue<File>()

    fun offer(files: List<File>) {
        val usable = files.filter { it.isFile }
        if (usable.isEmpty()) return
        queue.addAll(usable)
        javax.swing.SwingUtilities.invokeLater { arrived.intValue++ }
    }

    fun raise() = javax.swing.SwingUtilities.invokeLater {
        raised.intValue++
        DeskFiles.window?.let { if (it.extendedState and java.awt.Frame.ICONIFIED != 0) it.extendedState = it.extendedState and java.awt.Frame.ICONIFIED.inv(); it.toFront() }
    }

    fun take(): List<File> = generateSequence { queue.poll() }.toList()

    fun isProject(file: File) = file.extension.lowercase() in projectExtensions

    fun xflZip(uri: android.net.Uri): File? {
        val file = uri.toFile()?.takeIf { it.extension.equals("xfl", ignoreCase = true) && it.isFile } ?: return null
        val folder = file.absoluteFile.parentFile?.toPath() ?: return null
        val zip = File.createTempFile("am-xfl", ".zip")
        try {
            java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { out ->
                java.nio.file.Files.walk(folder).use { paths ->
                    paths.filter { java.nio.file.Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.limit(4000).forEach { path ->
                        out.putNextEntry(java.util.zip.ZipEntry(folder.relativize(path).joinToString("/")))
                        java.nio.file.Files.copy(path, out)
                        out.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            zip.delete()
            throw e
        }
        return zip
    }

    fun fromArguments(args: Array<String>): List<File> = args.filter { !it.startsWith("--") && it.isNotBlank() }.map(::File)

    fun listenForFiles() {
        val desktop = runCatching { java.awt.Desktop.getDesktop().takeIf { java.awt.Desktop.isDesktopSupported() && it.isSupported(java.awt.Desktop.Action.APP_OPEN_FILE) } }.getOrNull()
        desktop?.setOpenFileHandler { event -> offer(event.files) }
    }
}

object DeskInstance {
    private val file get() = (System.getProperty("am.data")?.let(::File) ?: DeskOs.dataDir).resolve("instance")
    private var server: ServerSocket? = null

    fun forward(files: List<File>): Boolean {
        val lines = runCatching { file.readLines() }.getOrNull()?.takeIf { it.size >= 2 } ?: return false
        val port = lines[0].toIntOrNull() ?: return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 400)
                socket.soTimeout = 1500
                val out = socket.getOutputStream().bufferedWriter()
                out.write(lines[1] + "\n")
                files.forEach { out.write(it.absolutePath + "\n") }
                out.flush()
                socket.shutdownOutput()
                socket.getInputStream().bufferedReader().readLine() == "ok"
            }
        }.getOrDefault(false)
    }

    fun serve(onFiles: (List<File>) -> Unit) {
        if (DeskOs.mac || forward(emptyList())) return
        runCatching {
            val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            val token = java.util.UUID.randomUUID().toString()
            file.parentFile.mkdirs()
            file.writeText("${socket.localPort}\n$token\n")
            runCatching { file.setReadable(false, false); file.setReadable(true, true); file.setWritable(false, false); file.setWritable(true, true) }
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { if (file.readLines().getOrNull(1) == token) file.delete() } })
            server = socket
            Thread({
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching {
                        client.soTimeout = 1500
                        val lines = client.getInputStream().bufferedReader().lineSequence().toList()
                        if (lines.firstOrNull() == token) {
                            onFiles(lines.drop(1).filter { it.isNotBlank() }.map(::File))
                            client.getOutputStream().write("ok\n".toByteArray())
                        }
                    }
                    runCatching { client.close() }
                }
            }, "am-open").apply { isDaemon = true }.start()
        }
    }
}
