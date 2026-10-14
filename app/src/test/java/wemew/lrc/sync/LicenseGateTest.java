package wemew.lrc.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LicenseGateTest {

    private static final long DAY = 86_400_000L;

    @org.junit.Test public void freshTrialIsActive() {
        assertTrue(LicenseGate.trialActive(System.currentTimeMillis() - 1000L,
                System.currentTimeMillis(), null, 0L, false));
    }

    @org.junit.Test public void trialExpiresAfterSevenDays() {
        long start = 1_000_000L;
        assertTrue(LicenseGate.trialActive(start, start + 7L * DAY - 1, null, 0L, false));
        assertFalse(LicenseGate.trialActive(start, start + 7L * DAY, null, 0L, false));
        assertFalse(LicenseGate.trialActive(start, start + 30L * DAY, null, 0L, false));
    }

    @org.junit.Test public void tamperKillsTheTrialImmediately() {
        assertFalse(LicenseGate.trialActive(System.currentTimeMillis(),
                System.currentTimeMillis(), null, 0L, true));
    }

    @org.junit.Test public void offlineBeyondGraceExpires() {
        long start = System.currentTimeMillis() - DAY;
        long lastContact = System.currentTimeMillis() - (49L * 3_600_000L);
        assertFalse(LicenseGate.trialActive(start, System.currentTimeMillis(),
                lastContact, lastContact, false));
        long recentContact = System.currentTimeMillis() - 3_600_000L;
        assertTrue(LicenseGate.trialActive(start, System.currentTimeMillis(),
                recentContact, recentContact, false));
    }
}
