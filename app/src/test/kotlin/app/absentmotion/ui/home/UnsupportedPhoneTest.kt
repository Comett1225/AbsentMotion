package app.absentmotion.ui.home

import app.absentmotion.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsupportedPhoneTest {
    @Test fun engineErrorsBecomePlainWords() {
        assertEquals(R.string.unsupported_old_vulkan, engineProblemText("Vulkan 1.1 device check failed (-9)"))
        assertEquals(R.string.unsupported_no_vulkan, engineProblemText("vkCreateInstance failed (-9)"))
        assertEquals(R.string.unsupported_driver_features, engineProblemText("required device extensions failed (-7)"))
        assertEquals(R.string.unsupported_skia, engineProblemText("Graphite context creation failed"))
        assertEquals(R.string.unsupported_unknown, engineProblemText("dlopen failed: library \"libamengine.so\" not found"))
    }

    @Test fun detailsNameThePhoneAndTheProblem() {
        val details = engineProblemDetails("3.2.0", PhoneFacts("Maker Tab", "11 (API 30)", null, "1.0"), "Vulkan 1.1 check failed (-9)")
        assertTrue(details.contains("Android: 11 (API 30)"))
        assertTrue(details.contains("Graphics chip: unknown"))
        assertTrue(details.contains("Vulkan: 1.0"))
        assertTrue(details.endsWith("Problem: Vulkan 1.1 check failed (-9)"))
    }
}
