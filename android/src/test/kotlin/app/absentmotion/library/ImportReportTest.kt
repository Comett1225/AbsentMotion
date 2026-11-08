package app.absentmotion.library

import app.absentmotion.R
import app.absentmotion.i18n.txt
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportReportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun settingsTheAppWritesItselfAreNotReportedAsUnknown() {
        val xml = """<scene width="100" height="100" fps="30" totalTime="1000" depthOrder="true">
            <shape id="1" startTime="0" endTime="1000" fillType="color" s=".rect" scaleLinked="false" threeD="true" mixPieces="false" pathLayer="2"/>
            <shape id="2" startTime="0" endTime="1000" fillType="media" fillVideo="x://clip" loop="true" loopIn="0" noiseId="1" matteFrame="10,10" matteEdge="1"/>
            <embedScene id="3" startTime="0" endTime="1000" retimeLength="1000"><scene width="100" height="100" fps="30" totalTime="1000"/></embedScene>
            <model id="4" startTime="0" endTime="1000" src="x://phone" scaleLinked="false"/>
        </scene>"""
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                for ((name, data) in listOf("project.xml" to xml, "manifest.json" to """{"project":"project.xml"}""")) { zip.putNextEntry(ZipEntry(name)); zip.write(data.toByteArray()); zip.closeEntry() }
            }
        }.toByteArray()
        val report = Importer(ProjectStore(tmp.newFolder())).import { bytes.inputStream() }.single()
        assertEquals(null, report.sections.firstOrNull { it.first == txt(R.string.library_report_unknown) }?.second)
    }
}
