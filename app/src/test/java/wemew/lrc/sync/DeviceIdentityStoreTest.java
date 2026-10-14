package wemew.lrc.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class DeviceIdentityStoreTest {

    private static final String SECRET = "unit-secret";

    @org.junit.Test public void blobRoundTripPreservesIdentity() {
        String blob = DeviceIdentityStore.buildBlob("AB12CD34EF56", 1000L, 2000L, SECRET);
        DeviceIdentityStore.Identity identity = DeviceIdentityStore.parseBlob(blob, SECRET);
        assertNotNull(identity);
        assertEquals("AB12CD34EF56", identity.id);
        assertEquals(1000L, identity.firstSeenMs);
        assertEquals(2000L, identity.trialStartMs);
    }

    @org.junit.Test public void tamperedBlobFailsTheSignature() {
        String blob = DeviceIdentityStore.buildBlob("AB12CD34EF56", 1000L, 2000L, SECRET);
        // Free-trial tampering: push the trial anchor into the future, signature stays old.
        String tampered = blob.replace("2000", "99999999999999");
        assertNull(DeviceIdentityStore.parseBlob(tampered, SECRET));
        // Identity swap.
        String swapped = blob.replace("AB12CD34EF56", "000000000000");
        assertNull(DeviceIdentityStore.parseBlob(swapped, SECRET));
    }

    @org.junit.Test public void wrongSecretIsRejected() {
        String blob = DeviceIdentityStore.buildBlob("AB12CD34EF56", 1000L, 2000L, SECRET);
        assertNull(DeviceIdentityStore.parseBlob(blob, "other-secret"));
    }

    @org.junit.Test public void malformedBlobIsNull() {
        assertNull(DeviceIdentityStore.parseBlob(null, SECRET));
        assertNull(DeviceIdentityStore.parseBlob("", SECRET));
        assertNull(DeviceIdentityStore.parseBlob("not json", SECRET));
        assertNull(DeviceIdentityStore.parseBlob("{}", SECRET));
        assertNull(DeviceIdentityStore.parseBlob("{\"id\":\"SHORT\",\"sig\":\"x\"}", SECRET));
    }
}
