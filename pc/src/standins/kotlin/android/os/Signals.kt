package android.os

class CancellationSignal {
    @Volatile var isCanceled = false
        private set
    private var listener: (() -> Unit)? = null
    fun cancel() { isCanceled = true; listener?.invoke() }
    fun setOnCancelListener(l: OnCancelListener?) { listener = l?.let { { it.onCancel() } } }
    fun throwIfCanceled() { if (isCanceled) throw OperationCanceledException() }
    fun interface OnCancelListener { fun onCancel() }
}

class OperationCanceledException : RuntimeException()
