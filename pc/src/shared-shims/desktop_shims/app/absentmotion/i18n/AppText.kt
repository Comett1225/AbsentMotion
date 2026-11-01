package app.absentmotion.i18n

object AppText {
    fun get(id: Int, vararg args: Any?): String = "Text $id" + args.joinToString(" ", prefix = " ")
}

data class UiText(val value: String)
fun txt(id: Int, vararg args: Any?): UiText = UiText(AppText.get(id, *args))
