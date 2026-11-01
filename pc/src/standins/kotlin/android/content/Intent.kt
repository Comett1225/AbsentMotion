package android.content

import android.net.Uri

open class Intent() {
    var action: String? = null
    var data: Uri? = null
    var type: String? = null
    var flags = 0
    private val extras = HashMap<String, Any?>()
    val categories = HashSet<String>()
    var clipData: ClipData? = null
    constructor(action: String?) : this() { this.action = action }
    constructor(action: String?, uri: Uri?) : this() { this.action = action; data = uri }
    constructor(context: Context?, cls: Class<*>?) : this()
    fun putExtra(key: String, value: Any?): Intent = apply { extras[key] = value }
    fun putParcelableArrayListExtra(key: String, value: ArrayList<*>?): Intent = putExtra(key, value)
    fun putStringArrayListExtra(key: String, value: ArrayList<String>?): Intent = putExtra(key, value)
    fun getStringExtra(key: String): String? = extras[key] as? String
    fun getIntExtra(key: String, default: Int): Int = extras[key] as? Int ?: default
    fun getBooleanExtra(key: String, default: Boolean): Boolean = extras[key] as? Boolean ?: default
    fun getLongExtra(key: String, default: Long): Long = extras[key] as? Long ?: default
    fun hasExtra(key: String) = extras.containsKey(key)
    fun setType(t: String?): Intent = apply { type = t }
    fun setData(u: Uri?): Intent = apply { data = u }
    fun setDataAndType(u: Uri?, t: String?): Intent = apply { data = u; type = t }
    fun setAction(a: String?): Intent = apply { action = a }
    fun setPackage(p: String?): Intent = this
    fun setClassName(p: String, c: String): Intent = this
    fun addFlags(f: Int): Intent = apply { flags = flags or f }
    fun setFlags(f: Int): Intent = apply { flags = f }
    fun addCategory(c: String): Intent = apply { categories += c }
    fun resolveActivity(pm: android.content.pm.PackageManager): ComponentName? = null
    companion object {
        const val ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED"; const val ACTION_VIEW = "android.intent.action.VIEW"; const val ACTION_SEND = "android.intent.action.SEND"; const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
        const val ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT"; const val ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT"; const val ACTION_MAIN = "android.intent.action.MAIN"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"; const val EXTRA_TEXT = "android.intent.extra.TEXT"; const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"; const val EXTRA_MIME_TYPES = "android.intent.extra.MIME_TYPES"
        const val CATEGORY_OPENABLE = "android.intent.category.OPENABLE"; const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
        const val FLAG_GRANT_READ_URI_PERMISSION = 1; const val FLAG_GRANT_WRITE_URI_PERMISSION = 2; const val FLAG_ACTIVITY_NEW_TASK = 0x10000000; const val FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 64
        @JvmStatic fun createChooser(target: Intent, title: CharSequence?): Intent = target
    }
}

class ComponentName(val packageName: String, val className: String)

class ClipData private constructor(val label: CharSequence?, val items: MutableList<Item>) {
    class Item(val text: CharSequence? = null, val uri: Uri? = null) { constructor(uri: Uri) : this(null, uri); fun coerceToText(context: Context?): CharSequence = text ?: uri?.toString() ?: "" }
    val itemCount: Int get() = items.size
    fun getItemAt(i: Int) = items[i]
    fun addItem(item: Item) { items += item }
    companion object {
        @JvmStatic fun newPlainText(label: CharSequence?, text: CharSequence?) = ClipData(label, mutableListOf(Item(text)))
        @JvmStatic fun newRawUri(label: CharSequence?, uri: Uri) = ClipData(label, mutableListOf(Item(uri)))
    }
}

class IntentFilter(vararg val actions: String)

abstract class BroadcastReceiver {
    abstract fun onReceive(context: Context, intent: Intent)
}
