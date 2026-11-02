package app.absentmotion.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopBarNameTest {
    @Test fun anUntouchedNameIsNeverCommittedEvenWithSpacesAroundIt() {
        for (shown in listOf(" Hello", "Hello\n", "\n Hello \n", "Hello")) assertNull(typedName(shown, shown))
    }

    @Test fun anEditedNameIsTrimmedAndAnEmptyOrSameOneIsDropped() {
        assertEquals("Hi there", typedName("  Hi there ", "Hello"))
        assertNull(typedName("   ", "Hello"))
        assertNull(typedName("Hello ", "Hello"))
        assertEquals("Hello", typedName("Hello ", " Hello"))
    }
}
