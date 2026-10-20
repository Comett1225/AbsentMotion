package app.absentmotion.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingTest {
    @Test fun aNewInstallStartsWithTheLanguageScreen() {
        assertEquals(OnboardingStep.Language, FirstRun.start(0))
    }

    @Test fun aChosenLanguageResumesAtThePhoneCheck() {
        assertEquals(OnboardingStep.Check, FirstRun.start(ONBOARDING_LANGUAGE_CHOSEN))
    }

    @Test fun finishedOnboardingNeverShowsAgain() {
        assertNull(FirstRun.start(ONBOARDING_DONE))
        assertNull(FirstRun.start(ONBOARDING_DONE + 1))
    }

    @Test fun exportSizesUseShortNames() {
        assertEquals("4K", sizeLabel(2160))
        assertEquals("1440p", sizeLabel(1440))
        assertEquals("1080p", sizeLabel(1080))
        assertEquals("720p", sizeLabel(720))
    }
}
