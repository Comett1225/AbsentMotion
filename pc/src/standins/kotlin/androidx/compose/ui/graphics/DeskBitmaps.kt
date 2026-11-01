package androidx.compose.ui.graphics

fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap = image.toComposeImageBitmap()

fun ImageBitmap.asAndroidBitmap(): android.graphics.Bitmap = android.graphics.Bitmap(toAwtImage())
