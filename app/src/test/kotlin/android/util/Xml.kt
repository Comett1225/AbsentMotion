package android.util

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

object Xml {
    @JvmStatic fun newPullParser(): XmlPullParser = KXmlParser()
}
