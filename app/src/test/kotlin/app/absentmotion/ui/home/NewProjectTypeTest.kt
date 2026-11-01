package app.absentmotion.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewProjectTypeTest {
    @Test fun newProjectFollowsTheVisibleLibraryTab() {
        assertFalse(newProjectIsAsset(0, 0))
        assertTrue(newProjectIsAsset(0, 1))
        assertFalse(newProjectIsAsset(1, 1))
        assertFalse(newProjectIsAsset(2, 1))
    }
}
