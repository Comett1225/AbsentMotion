package app.absentmotion.library

import app.absentmotion.platform.readInto
import app.absentmotion.export.safeName
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.replaceMedia
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays
import java.util.concurrent.CancellationException

class MediaKeeper(
    private val store: ProjectStore,
    private val open: (String) -> InputStream?,
    private val free: (File) -> Long = { it.usableSpace },
) {
    class NoSpace(val needed: Long) : IOException("Not enough free storage")

    data class Collected(val scene: Scene, val copied: Int, val unreadable: Int, val noSpace: Boolean)

    fun keep(id: String, media: MediaDeclaration, scene: Scene? = null, cancelled: () -> Boolean = { false }): MediaDeclaration {
        if (!collectable(media.uri)) return media
        scene?.media?.firstOrNull { media.size > 0 && it.size == media.size && it.filename == media.filename && store.inMediaFolder(it.uri) && same(media.uri, it.uri) }
            ?.let { return MediaDeclaration(media.node.with("uri", it.uri).with("missing", null)) }
        val dir = store.mediaDir(id).apply { mkdirs() }
        val base = safeName(media.filename.ifEmpty { media.uri.substringAfterLast('/') })
        earlierCopy(dir, base, media)?.let { return MediaDeclaration(media.node.with("uri", it.toURI().toString()).with("missing", null)) }
        val needed = maxOf(media.size, 0) + RESERVE
        if (free(dir) < needed) throw NoSpace(needed)
        val temp = File.createTempFile("keep-", ".part", dir)
        try {
            (open(media.uri) ?: throw FileNotFoundException(media.uri)).use { input -> temp.outputStream().use { copy(input, it, cancelled) } }
            if (media.size > 0 && temp.length() < media.size) throw IOException("Copied ${temp.length()} of ${media.size} bytes of $base")
            val target = synchronized(Naming) {
                val name = (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "${it}_$base" }).first { !File(dir, it).exists() }
                File(dir, name).also { if (!temp.renameTo(it)) throw IOException("Cannot keep $name") }
            }
            return MediaDeclaration(media.node.with("uri", target.toURI().toString()).with("missing", null))
        } catch (e: IOException) {
            if (free(dir) < RESERVE) throw NoSpace(needed)
            throw e
        } finally {
            temp.delete()
        }
    }

    private fun earlierCopy(dir: File, base: String, media: MediaDeclaration): File? {
        if (media.size <= 0) return null
        val numbered = Regex("[0-9]+_" + Regex.escape(base))
        return dir.listFiles { f -> f.isFile && f.length() == media.size && (f.name == base || numbered.matches(f.name)) }.orEmpty()
            .sortedBy { it.name }.firstOrNull { same(media.uri, it.toURI().toString()) }
    }

    private fun copy(input: InputStream, out: OutputStream, cancelled: () -> Boolean) {
        val buffer = ByteArray(1 shl 16)
        while (true) {
            if (cancelled()) throw CancellationException("Copy cancelled")
            val n = input.read(buffer)
            if (n < 0) return
            out.write(buffer, 0, n)
        }
    }

    fun collect(id: String, scene: Scene): Collected {
        var result = scene
        var copied = 0
        var unreadable = 0
        for (media in scene.media.filter { collectable(it.uri) }) {
            try {
                result = result.replaceMedia(media.uri, keep(id, media, result))
                copied++
            } catch (e: NoSpace) {
                return Collected(result, copied, unreadable, true)
            } catch (e: IOException) {
                unreadable++
            } catch (e: SecurityException) {
                unreadable++
            }
        }
        return Collected(result, copied, unreadable, false)
    }

    private fun same(a: String, b: String): Boolean = runCatching {
        (open(a) ?: return false).use { x -> (open(b) ?: return false).use { y -> equal(x, y) } }
    }.getOrDefault(false)

    private fun equal(x: InputStream, y: InputStream): Boolean {
        val one = ByteArray(1 shl 16)
        val two = ByteArray(1 shl 16)
        while (true) {
            val n = x.readInto(one, 0, one.size)
            val m = y.readInto(two, 0, two.size)
            if (n != m || !Arrays.equals(one, 0, n, two, 0, m)) return false
            if (n < one.size) return true
        }
    }

    private fun collectable(uri: String) = (uri.startsWith("content:") || uri.startsWith("file:")) && !store.inMediaFolder(uri)

    companion object {
        private val Naming = Any()

        const val RESERVE = 200L shl 20
    }
}
