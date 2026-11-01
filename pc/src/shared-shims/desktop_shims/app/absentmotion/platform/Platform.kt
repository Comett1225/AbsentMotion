package app.absentmotion.platform

import org.xmlpull.v1.XmlPullParserFactory
import java.io.File

fun xmlParser() = XmlPullParserFactory.newInstance().newPullParser()
class Context(val assets: Assets)
class Assets(private val root: File) {
    fun open(path: String) = root.resolve(path).inputStream()
}
