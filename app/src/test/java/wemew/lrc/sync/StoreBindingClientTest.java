package wemew.lrc.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Shapes verified against the live gateway (song.wemew.com): binding is a non-empty barId;
 * enable:false does not unbind, and unbound answers still return resultTrue:true.
 */
public class StoreBindingClientTest {

    @org.junit.Test public void boundResponseCarriesStoreIdentity() {
        StoreBindingClient.BindingInfo info = StoreBindingClient.parseBinding(
                "{\"resultMsg\":{\"mode\":\"server\",\"enable\":false,"
                        + "\"barName\":\"TOUCH.\","
                        + "\"barId\":\"f819730e-867e-4b62-8424-f353d494784e\"},"
                        + "\"code\":0,\"version\":1.0,\"resultTrue\":true}");
        assertTrue(info.bound);
        assertEquals("f819730e-867e-4b62-8424-f353d494784e", info.barId);
        assertEquals("TOUCH.", info.barName);
    }

    @org.junit.Test public void unboundStillReportsResultTrue() {
        StoreBindingClient.BindingInfo info = StoreBindingClient.parseBinding(
                "{\"resultMsg\":{\"mode\":null,\"enable\":false,\"barName\":null,"
                        + "\"barId\":null},\"code\":0,\"version\":1.0,\"resultTrue\":true}");
        assertFalse(info.bound);
    }

    @org.junit.Test public void stringifiedResultMsgIsParsed() {
        StoreBindingClient.BindingInfo info = StoreBindingClient.parseBinding(
                "{\"resultTrue\":true,\"resultMsg\":\"{\\\"enable\\\":false,"
                        + "\\\"barId\\\":\\\"B9\\\",\\\"barName\\\":\\\"门店九\\\"}\"}");
        assertTrue(info.bound);
        assertEquals("B9", info.barId);
        assertEquals("门店九", info.barName);
    }

    @org.junit.Test public void errorStringResultMsgMeansUnbound() {
        // resultMsg is polymorphic: failures carry the message as a plain string.
        assertFalse(StoreBindingClient.parseBinding(
                "{\"resultMsg\":\"酒吧未开通歌曲识别功能\",\"code\":0,"
                        + "\"version\":1.0,\"resultTrue\":false}").bound);
    }

    @org.junit.Test public void literalNullBarIdIsUnbound() {
        assertFalse(StoreBindingClient.parseBinding(
                "{\"resultTrue\":true,\"resultMsg\":{\"barId\":\"null\",\"barName\":\"x\"}}")
                .bound);
    }

    @org.junit.Test public void garbageNeverThrows() {
        assertFalse(StoreBindingClient.parseBinding(null).bound);
        assertFalse(StoreBindingClient.parseBinding("").bound);
        assertFalse(StoreBindingClient.parseBinding("not json").bound);
        assertFalse(StoreBindingClient.parseBinding("{\"unexpected\":1}").bound);
    }
}
