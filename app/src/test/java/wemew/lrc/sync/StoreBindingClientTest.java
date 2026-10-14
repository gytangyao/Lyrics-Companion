package wemew.lrc.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StoreBindingClientTest {

    @org.junit.Test public void boundResponseCarriesStoreIdentity() {
        StoreBindingClient.BindingInfo info = StoreBindingClient.parseBinding(
                "{\"resultTrue\":true,\"resultMsg\":"
                        + "{\"enable\":true,\"barId\":\"B1024\",\"barName\":\"微喵旗舰店\"}}");
        assertTrue(info.bound);
        assertEquals("B1024", info.barId);
        assertEquals("微喵旗舰店", info.barName);
    }

    @org.junit.Test public void unboundWhenResultFalse() {
        assertFalse(StoreBindingClient.parseBinding(
                "{\"resultTrue\":false,\"resultMsg\":null}").bound);
    }

    @org.junit.Test public void unboundWhenDisabled() {
        StoreBindingClient.BindingInfo info = StoreBindingClient.parseBinding(
                "{\"resultTrue\":true,\"resultMsg\":"
                        + "{\"enable\":false,\"barId\":\"B1\",\"barName\":\"x\"}}");
        assertFalse(info.bound);
    }

    @org.junit.Test public void unboundWhenBarIdEmpty() {
        assertFalse(StoreBindingClient.parseBinding(
                "{\"resultTrue\":true,\"resultMsg\":"
                        + "{\"enable\":true,\"barId\":\"\",\"barName\":\"x\"}}").bound);
    }

    @org.junit.Test public void garbageNeverThrows() {
        assertFalse(StoreBindingClient.parseBinding(null).bound);
        assertFalse(StoreBindingClient.parseBinding("").bound);
        assertFalse(StoreBindingClient.parseBinding("not json").bound);
        assertFalse(StoreBindingClient.parseBinding("{\"unexpected\":1}").bound);
    }

    @org.junit.Test public void isResultTrueAliasAccepted() {
        // Some wemew endpoints use code+isResultTrue instead of resultTrue.
        StoreBindingClient.BindingInfo info = StoreBindingClient.parseBinding(
                "{\"code\":200,\"isResultTrue\":true,\"resultMsg\":"
                        + "{\"enable\":true,\"barId\":\"B7\",\"barName\":\"门店七\"}}");
        assertTrue(info.bound);
        assertEquals("B7", info.barId);
    }
}
