package app.absentmotion.transfer

import android.content.Context

internal object QrScanner {
    const val AVAILABLE = false

    @Suppress("UNUSED_PARAMETER")
    fun scan(context: Context, onResult: (String) -> Unit, onError: (String?) -> Unit) = onError("Use your camera app to scan the code, or paste the link.")
}
