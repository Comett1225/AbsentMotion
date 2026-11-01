package androidx.annotation

annotation class StringRes
annotation class PluralsRes
annotation class DrawableRes
annotation class RequiresApi(val value: Int = 0, val api: Int = 0)
annotation class VisibleForTesting(val otherwise: Int = 0)
annotation class MainThread
annotation class WorkerThread
annotation class Keep
