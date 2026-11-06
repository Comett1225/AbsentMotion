package android.content

import android.net.Uri
import app.absentmotion.desk.Strings
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

open class Context {
    open val filesDir: File get() = Desk.dataDir.resolve("files").also { it.mkdirs() }
    open val cacheDir: File get() = Desk.dataDir.resolve("cache").also { it.mkdirs() }
    open val noBackupFilesDir: File get() = filesDir
    open val externalCacheDir: File? get() = cacheDir
    open fun getExternalFilesDir(type: String?): File? = Desk.dataDir.resolve("external").resolve(type ?: "").also { it.mkdirs() }
    open fun getDir(name: String, mode: Int): File = Desk.dataDir.resolve("app_$name").also { it.mkdirs() }
    open val applicationContext: Context get() = this
    open val packageName: String get() = "app.absentmotion"
    open val assets: AssetManager get() = AssetManager
    open val contentResolver: ContentResolver get() = ContentResolver
    open val resources: android.content.res.Resources get() = android.content.res.Resources
    open fun getString(id: Int): String = Strings.get(id)
    open fun getString(id: Int, vararg args: Any?): String = Strings.get(id, *args)
    open fun getText(id: Int): CharSequence = Strings.get(id)
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = SharedPreferences.named(name)
    open fun deleteSharedPreferences(name: String): Boolean = SharedPreferences.named(name).edit().clear().commit()
    open val packageManager: android.content.pm.PackageManager get() = android.content.pm.PackageManager.Desktop
    open fun getSystemService(name: String): Any? = null
    @Suppress("UNCHECKED_CAST") open fun <T> getSystemService(type: Class<T>): T = (if (type == android.app.ActivityManager::class.java) android.app.ActivityManager.Desktop else null) as T
    open val mainExecutor: java.util.concurrent.Executor get() = java.util.concurrent.Executor { javax.swing.SwingUtilities.invokeLater(it) }
    open fun createConfigurationContext(config: android.content.res.Configuration): Context = this
    open fun startActivity(intent: android.content.Intent) {}
    open fun startActivity(intent: android.content.Intent, options: android.os.Bundle?) {}
    open fun sendBroadcast(intent: android.content.Intent) {}
    open fun checkSelfPermission(permission: String): Int = 0
    open fun getColor(id: Int): Int = 0
    open fun getDatabasePath(name: String): File = Desk.dataDir.resolve("databases/$name")
    open fun getFileStreamPath(name: String): File = filesDir.resolve(name)
    open fun openFileInput(name: String) = filesDir.resolve(name).inputStream()
    open fun openFileOutput(name: String, mode: Int) = filesDir.resolve(name).outputStream()
    open fun deleteFile(name: String) = filesDir.resolve(name).delete()
    open val applicationInfo: android.content.pm.ApplicationInfo get() = android.content.pm.ApplicationInfo.Desktop
    open val mainLooper: android.os.Looper get() = android.os.Looper.getMainLooper()
    open val theme: Any? get() = null
    open fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter): android.content.Intent? = null
    open fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter, flags: Int): android.content.Intent? = null
    open fun unregisterReceiver(receiver: android.content.BroadcastReceiver) {}
    open fun startForegroundService(intent: android.content.Intent): android.content.ComponentName? = null
    open fun startService(intent: android.content.Intent): android.content.ComponentName? = null
    open fun stopService(intent: android.content.Intent): Boolean = false
    open fun grantUriPermission(pkg: String, uri: Uri, flags: Int) {}
    open fun revokeUriPermission(uri: Uri, flags: Int) {}

    companion object {
        const val MODE_PRIVATE = 0
    }
}

object Desk {
    val dataDir: File get() = (System.getProperty("am.data")?.let(::File) ?: app.absentmotion.desk.DeskOs.dataDir).also { it.mkdirs() }
    val context = Context()
}

object AssetManager {
    fun root(): File = System.getProperty("am.assets")?.let(::File) ?: File(System.getProperty("compose.application.resources.dir") ?: ".", "assets")
    fun open(path: String): InputStream = root().resolve(path).takeIf { it.isFile }?.inputStream() ?: throw java.io.FileNotFoundException(path)
    fun list(path: String): Array<String> = root().resolve(path).list()?.sorted()?.toTypedArray() ?: emptyArray()
    fun openFd(path: String) = root().resolve(path)
}

object ResourcesUnused {
    fun getIdentifier(name: String, type: String, pkg: String): Int = 0
    fun getText(id: Int): CharSequence = Strings.get(id)
    fun getString(id: Int): String = Strings.get(id)
    fun getString(id: Int, vararg args: Any?): String = Strings.get(id, *args)
    fun getQuantityString(id: Int, count: Int, vararg args: Any?): String = Strings.plural(id, count, *args)
    val displayMetrics = DisplayMetrics()
    val configuration = android.content.res.Configuration()
    fun getSystem() = this
}

class DisplayMetrics { var density = 2f; var widthPixels = 2560; var heightPixels = 1600; var densityDpi = 320 }

object ContentResolver {
    fun file(uri: Uri): File? = uri.toFile()
    fun openInputStream(uri: Uri): InputStream? = file(uri)?.takeIf { it.isFile }?.inputStream()
    fun openOutputStream(uri: Uri, mode: String = "w"): OutputStream? = file(uri)?.let { it.parentFile?.mkdirs(); java.io.FileOutputStream(it, mode.contains("a")) }
    fun getType(uri: Uri): String? = file(uri)?.let { java.net.URLConnection.guessContentTypeFromName(it.name) ?: Desk.mime(it.name) }
    fun delete(uri: Uri, where: String?, args: Array<String>?): Int = if (file(uri)?.delete() == true) 1 else 0
    fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): android.database.Cursor? = app.absentmotion.desk.DeskGallery.query(uri, projection, selection, args) ?: fileQuery(uri, projection)
    private fun fileQuery(uri: Uri, projection: Array<String>?): android.database.Cursor? {
        val f = file(uri)?.takeIf { it.isFile } ?: return null
        val columns = projection ?: arrayOf("_display_name", "_size")
        return android.database.DeskCursor(columns, listOf(Array(columns.size) { i -> when (columns[i]) { "_display_name" -> f.name; "_size" -> f.length(); "mime_type" -> Desk.mime(f.name); else -> null } }))
    }
    fun query(uri: Uri, projection: Array<String>?, extras: android.os.Bundle?, signal: android.os.CancellationSignal?): android.database.Cursor? = null
    fun openFileDescriptor(uri: Uri, mode: String): android.os.ParcelFileDescriptor? = null
    fun openAssetFileDescriptor(uri: Uri, mode: String): android.content.res.AssetFileDescriptor? = file(uri)?.takeIf { it.isFile }?.let { android.content.res.AssetFileDescriptor(it) }
    private val observers = java.util.concurrent.CopyOnWriteArrayList<android.database.ContentObserver>()
    fun registerContentObserver(uri: Uri, descendants: Boolean, observer: android.database.ContentObserver) { observers += observer }
    fun unregisterContentObserver(observer: android.database.ContentObserver) { observers -= observer }
    fun notifyLibraryChanged() = observers.forEach { runCatching { it.onChange(true) } }
    fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
    fun update(uri: Uri, values: android.content.ContentValues?, where: String?, args: Array<String>?): Int = 0
    fun notifyChange(uri: Uri, observer: android.database.ContentObserver?) {}
    fun takePersistableUriPermission(uri: Uri, flags: Int) {}
    fun loadThumbnail(uri: Uri, size: android.util.Size, signal: android.os.CancellationSignal?): android.graphics.Bitmap {
        val f = file(uri)?.takeIf { it.isFile } ?: throw java.io.IOException("No thumbnail")
        val side = maxOf(size.width, size.height)
        val video = Desk.mime(f.name)?.startsWith("video/") == true
        val img = if (video) app.absentmotion.desk.DeskMedia.frame(f, 0, side)
            else android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 })?.let { android.graphics.Bitmap.createScaledBitmap(it, side, (side * it.height / it.width.coerceAtLeast(1)).coerceAtLeast(1), true).image }
        return android.graphics.Bitmap((img ?: throw java.io.IOException("No thumbnail")).let { src -> java.awt.image.BufferedImage(src.width, src.height, java.awt.image.BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(src, 0, 0, null); dispose() } } })
    }
    fun releasePersistableUriPermission(uri: Uri, flags: Int) {}
    val persistedUriPermissions: List<Any> get() = emptyList()
}

fun Desk.mime(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"; "mov" -> "video/quicktime"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"
    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"
    "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "m4a", "aac" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; "flac" -> "audio/flac"
    "xml" -> "text/xml"; "json" -> "application/json"; "zip" -> "application/zip"
    else -> null
}

class SharedPreferences private constructor(private val file: File) {
    private val props = Properties().apply { if (file.isFile) runCatching { file.inputStream().use(::load) } }

    fun getString(key: String, default: String?): String? = synchronized(props) { props.getProperty(key) ?: default }
    fun getInt(key: String, default: Int): Int = getString(key, null)?.toIntOrNull() ?: default
    fun getLong(key: String, default: Long): Long = getString(key, null)?.toLongOrNull() ?: default
    fun getFloat(key: String, default: Float): Float = getString(key, null)?.toFloatOrNull() ?: default
    fun getBoolean(key: String, default: Boolean): Boolean = getString(key, null)?.toBooleanStrictOrNull() ?: default
    fun getStringSet(key: String, default: Set<String>?): Set<String>? = getString(key, null)?.let { s -> if (s.isEmpty()) emptySet() else s.split('\u0001').toSet() } ?: default
    fun contains(key: String): Boolean = synchronized(props) { props.containsKey(key) }
    val all: Map<String, *> get() = synchronized(props) { props.stringPropertyNames().associateWith { props.getProperty(it) } }
    fun edit() = Editor()
    fun registerOnSharedPreferenceChangeListener(listener: Any) {}
    fun unregisterOnSharedPreferenceChangeListener(listener: Any) {}

    inner class Editor {
        private val puts = LinkedHashMap<String, String?>()
        private var clear = false
        fun putString(key: String, value: String?) = apply { puts[key] = value }
        fun putInt(key: String, value: Int) = apply { puts[key] = value.toString() }
        fun putLong(key: String, value: Long) = apply { puts[key] = value.toString() }
        fun putFloat(key: String, value: Float) = apply { puts[key] = value.toString() }
        fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value.toString() }
        fun putStringSet(key: String, value: Set<String>?) = apply { puts[key] = value?.joinToString("\u0001") }
        fun remove(key: String) = apply { puts[key] = null }
        fun clear() = apply { clear = true }
        fun commit(): Boolean = synchronized(props) {
            if (clear) props.clear()
            puts.forEach { (k, v) -> if (v == null) props.remove(k) else props.setProperty(k, v) }
            runCatching {
                file.parentFile.mkdirs()
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.outputStream().use { props.store(it, null) }
                java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }.isSuccess
        }
        fun apply() { commit() }
    }

    companion object {
        private val open = HashMap<String, SharedPreferences>()
        fun named(name: String): SharedPreferences = Desk.dataDir.resolve("prefs/$name.properties").let { file -> synchronized(open) { open.getOrPut(file.path) { SharedPreferences(file) } } }
    }
}
