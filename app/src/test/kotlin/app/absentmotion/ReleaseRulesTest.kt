package app.absentmotion

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseRulesTest {
    @Test fun theReleaseBuildKeepsTheStandardR8RulesSoSavedEnumsReadBackAfterAndroidClosesTheApp() {
        val release = File("build.gradle.kts").readText().substringAfter("release {").substringBefore("}")
        assertTrue(Regex("""getDefaultProguardFile\("proguard-android(-optimize)?\.txt"\)""").containsMatchIn(release))
    }
}
