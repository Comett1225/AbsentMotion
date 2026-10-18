package app.absentmotion.transfer

import android.content.Context
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

internal object QrScanner {
    const val AVAILABLE = true

    fun scan(context: Context, onResult: (String) -> Unit, onError: (String?) -> Unit) {
        GmsBarcodeScanning.getClient(context).startScan()
            .addOnSuccessListener { onResult(it.rawValue.orEmpty()) }
            .addOnFailureListener { onError(it.message) }
    }
}
