package wemew.lrc.sync;

import org.json.JSONObject;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The cloud relay echoes the whole room to everyone; only these two inbound shapes matter. */
public class CloudSyncClientTest {

    @org.junit.Test public void displayOnlineHeartbeatIsRecognised() throws Exception {
        JSONObject data = new JSONObject();
        WebProtocolCodec.put(data, "deviceId", "DISPLAY1A2B3C4D");
        WebProtocolCodec.put(data, "timestamp", 123L);
        JSONObject frame = new JSONObject();
        WebProtocolCodec.put(frame, "type", "online");
        WebProtocolCodec.put(frame, "sender", "control_box");
        WebProtocolCodec.put(frame, "data", data);
        assertTrue(CloudSyncClient.isDisplayOnline(WebProtocolCodec.parseInbound(frame.toString())));
    }

    @org.junit.Test public void ownEchoAndAdminFramesAreIgnored() throws Exception {
        JSONObject data = new JSONObject();
        WebProtocolCodec.put(data, "deviceId", "968A7CEFDD0A");
        JSONObject own = new JSONObject();
        WebProtocolCodec.put(own, "type", "online");
        WebProtocolCodec.put(own, "sender", "control_box_phone");
        WebProtocolCodec.put(own, "data", data);
        assertFalse(CloudSyncClient.isDisplayOnline(WebProtocolCodec.parseInbound(own.toString())));

        JSONObject admin = new JSONObject();
        WebProtocolCodec.put(admin, "type", "online");
        WebProtocolCodec.put(admin, "sender", "control_box_phone_admin");
        WebProtocolCodec.put(admin, "data", data);
        assertFalse(CloudSyncClient.isDisplayOnline(WebProtocolCodec.parseInbound(admin.toString())));

        assertFalse(CloudSyncClient.isDisplayOnline(
                WebProtocolCodec.parseInbound("{\"type\":\"online\",\"sender\":\"control_box\"}")));
    }

    @org.junit.Test public void displayPingTriggersPong() throws Exception {
        JSONObject ping = new JSONObject();
        WebProtocolCodec.put(ping, "v", 1);
        WebProtocolCodec.put(ping, "type", "ping");
        WebProtocolCodec.put(ping, "ts", 12345L);
        assertTrue(CloudSyncClient.isDisplayPing(WebProtocolCodec.parseInbound(ping.toString())));
        // Missing ts cannot be echoed into a meaningful pong.
        JSONObject bare = new JSONObject();
        WebProtocolCodec.put(bare, "v", 1);
        WebProtocolCodec.put(bare, "type", "ping");
        assertFalse(CloudSyncClient.isDisplayPing(WebProtocolCodec.parseInbound(bare.toString())));
        // Track frames and heartbeats are not pings.
        assertFalse(CloudSyncClient.isDisplayPing(WebProtocolCodec.parseInbound(
                "{\"v\":1,\"type\":\"track\",\"ts\":1,\"data\":{}}")));
    }

    @org.junit.Test public void senderIsExposedByTheParser() {
        WebProtocolCodec.Inbound inbound = WebProtocolCodec.parseInbound(
                "{\"type\":\"online\",\"sender\":\"control_box\",\"data\":{}}");
        assertTrue("control_box".equals(inbound.sender));
    }
}
