package app.tvessentials.core.version

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateDecisionTest {
    @Test fun `version codes win when both are known`() {
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, UpdateDecision.decide("1.0.0", 1062, "1.1.0-beta.5", 1068))
        assertEquals(UpdateStatus.UP_TO_DATE, UpdateDecision.decide("1.0.0", 1062, "1.0.0", 1062))
        assertEquals(UpdateStatus.INSTALLED_IS_NEWER, UpdateDecision.decide("1.1.0-beta.5", 1068, "1.0.0", 1062))
    }

    @Test fun `names are compared numerically when codes are unknown`() {
        assertEquals(UpdateStatus.UPDATE_AVAILABLE, UpdateDecision.decide("32.7", 2400, "32.56", null))
        assertEquals(UpdateStatus.UP_TO_DATE, UpdateDecision.decide("32.56", 2446, "32.56", null))
        assertEquals(UpdateStatus.INSTALLED_IS_NEWER, UpdateDecision.decide("32.56", 2446, "32.47", null))
    }

    @Test fun `a beta installed while following stable is never offered a downgrade`() {
        assertEquals(UpdateStatus.INSTALLED_IS_NEWER, UpdateDecision.decide("1.1.0-beta.5", null, "1.0.0", null))
    }

    @Test fun `unknown or incomparable versions are reported as unknown, never guessed`() {
        assertEquals(UpdateStatus.UNKNOWN, UpdateDecision.decide("4.1.5", 400, null, null))
        assertEquals(UpdateStatus.UNKNOWN, UpdateDecision.decide("weird", null, "1.0", null))
        assertEquals(UpdateStatus.UNKNOWN, UpdateDecision.decide(null, null, "1.0", null))
    }
}
