package app.absentmotion.library

import app.absentmotion.export.safeName
import app.absentmotion.model.MediaDeclaration
import app.absentmotion.model.Scene
import app.absentmotion.model.replaceMedia
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Arrays

class MediaKeeper(
    private val store: ProjectStore,
    private val open: (String) -> InputStream?,
    private val free: (File) -> Long = { it.usableSpace },
) {
    class NoSpace(val needed: Long) : IOException("Not enough free storage")

    data class Collected(val scene: Scene, val copied: Int, val unreadable: Int, val noSpace: Boolean)

    fun keep(id: String, media: MediaDeclaration, scene: Scene? = null): MediaDeclaration {
        if (!collectable(media.uri)) return media
        scene?.media?.firstOrNull { media.size > 0 && it.size == media.size && it.filename == media.filename && store.inMediaFolder(it.uri) && same(media.uri, it.uri) }
            ?.let { return MediaDeclaration(media.node.with("uri", it.uri).with("missing", null)) }
        val dir = store.mediaDir(id).apply { mkdirs() }
        val needed = maxOf(media.size, 0) + RESERVE
        if (free(dir) < needed) throw NoSpace(needed)
        val base = safeName(media.filename.ifEmpty { media.uri.substringAfterLast('/') })
        val name = (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "${it}_$base" }).first { !File(dir, it).exists() }
        val target = File(dir, name)
        val temp = File(dir, "$name.part")
        try {
            (open(media.uri) ?: throw FileNotFoundException(media.uri)).use { input -> temp.outputStream().use { input.copyTo(it) } }
            if (!temp.renameTo(target)) throw IOException("Cannot keep $name")
        } catch (e: IOException) {
            temp.delete()
            if (free(dir) < RESERVE) throw NoSpace(needed)
            throw e
        }
        return MediaDeclaration(media.node.with("uri", target.toURI().toString()).with("missing", null))
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
            val n = x.readNBytes(one, 0, one.size)
            val m = y.readNBytes(two, 0, two.size)
            if (n != m || !Arrays.equals(one, 0, n, two, 0, m)) return false
            if (n < one.size) return true
        }
    }

    private fun collectable(uri: String) = (uri.startsWith("content:") || uri.startsWith("file:")) && !store.inMediaFolder(uri)

    companion object {
        const val RESERVE = 200L shl 20
    }
}
