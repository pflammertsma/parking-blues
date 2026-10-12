package dev.lammertsma.parkingblues.shared

import kotlin.test.Test
import kotlin.test.assertFalse

class CrashReporterTest {
    @Test
    fun withoutAnSdkNothingIsAvailableAndNothingGetsEnabled() {
        val reporter: CrashReporter = NoCrashReporter
        assertFalse(reporter.isAvailable)
        reporter.isEnabled = true
        assertFalse(reporter.isEnabled, "a reporter that is not available stays off")
    }
}
