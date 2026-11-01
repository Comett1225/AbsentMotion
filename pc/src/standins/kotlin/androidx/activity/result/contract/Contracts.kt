package androidx.activity.result.contract

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import app.absentmotion.desk.DeskFiles

abstract class ActivityResultContract<I, O> {
    abstract fun desktop(input: I): O
}

object ActivityResultContracts {
    class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun desktop(input: Array<String>): Uri? = DeskFiles.chooseFiles(media = input.all { it.startsWith("image/") || it.startsWith("video/") || it.startsWith("audio/") }, multiple = false).firstOrNull()?.let(Uri::fromFile)
    }
    class OpenMultipleDocuments : ActivityResultContract<Array<String>, List<Uri>>() {
        override fun desktop(input: Array<String>): List<Uri> = DeskFiles.chooseFiles(media = false, multiple = true).map(Uri::fromFile)
    }
    class GetContent : ActivityResultContract<String, Uri?>() {
        override fun desktop(input: String): Uri? = DeskFiles.chooseFiles(media = input.startsWith("image/") || input.startsWith("video/") || input.startsWith("audio/"), multiple = false).firstOrNull()?.let(Uri::fromFile)
    }
    class CreateDocument(private val mime: String = "*/*") : ActivityResultContract<String, Uri?>() {
        override fun desktop(input: String): Uri? = DeskFiles.chooseSave(input)?.let(Uri::fromFile)
    }
    class PickVisualMedia : ActivityResultContract<PickVisualMediaRequest, Uri?>() {
        override fun desktop(input: PickVisualMediaRequest): Uri? = DeskFiles.chooseFiles(media = true, multiple = false).firstOrNull()?.let(Uri::fromFile)
        interface VisualMediaType
        object ImageAndVideo : VisualMediaType
        object ImageOnly : VisualMediaType
        object VideoOnly : VisualMediaType
        class SingleMimeType(val mimeType: String) : VisualMediaType
    }
    class PickMultipleVisualMedia(val max: Int = 100) : ActivityResultContract<PickVisualMediaRequest, List<Uri>>() {
        override fun desktop(input: PickVisualMediaRequest): List<Uri> = DeskFiles.chooseFiles(media = true, multiple = true).map(Uri::fromFile)
    }
    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun desktop(input: String): Boolean = true
    }
    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
        override fun desktop(input: Array<String>): Map<String, Boolean> = input.associateWith { true }
    }
    class StartActivityForResult : ActivityResultContract<android.content.Intent, Any?>() {
        override fun desktop(input: android.content.Intent): Any? = null
    }
}
