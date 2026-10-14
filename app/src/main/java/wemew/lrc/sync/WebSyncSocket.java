package wemew.lrc.sync;

import android.util.Log;

import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

/**
 * One connected display client. On open it registers itself and immediately receives the
 * welcome frame plus a full snapshot (track + state), following the nbox pattern so a freshly
 * opened page never waits for the next event. Inbound frames only carry hello/ping; anything
 * else is ignored per the protocol's forward-compatibility rule.
 */
final class WebSyncSocket extends NanoWSD.WebSocket {
    private static final String TAG = "WebSyncSocket";

    /** Snapshot supplier implemented by {@link WebStatePublisher}; avoids a wiring cycle. */
    interface Hooks {
        String welcomeJson();

        String trackJson();

        String stateJson();
    }

    private final WebSocketClients clients;
    private final Hooks hooks;
    /** Name from the client's hello frame; blank until it introduces itself. */
    volatile String clientName = "";
    final long connectedAtElapsedMs = android.os.SystemClock.elapsedRealtime();
    volatile long lastActivityElapsedMs = connectedAtElapsedMs;
    volatile long sentFrames;

    WebSyncSocket(NanoHTTPD.IHTTPSession handshake, WebSocketClients clients, Hooks hooks) {
        super(handshake);
        this.clients = clients;
        this.hooks = hooks;
    }

    @Override protected void onOpen() {
        clients.add(this);
        trySend(hooks.welcomeJson());
        trySend(hooks.trackJson());
        trySend(hooks.stateJson());
    }

    @Override protected void onMessage(NanoWSD.WebSocketFrame message) {
        String text;
        try {
            text = message.getTextPayload();
        } catch (Throwable error) {
            return;
        }
        WebProtocolCodec.Inbound inbound = WebProtocolCodec.parseInbound(text);
        if (WebProtocol.TYPE_PING.equals(inbound.type)) {
            // The echoed t0 is the envelope ts of the ping, per the protocol doc.
            trySend(WebProtocolCodec.pong(inbound.ts, System.currentTimeMillis()));
        } else if (WebProtocol.TYPE_HELLO.equals(inbound.type) && inbound.data != null) {
            org.json.JSONObject client = inbound.data.optJSONObject("client");
            if (client != null) {
                String name = client.optString("name", "");
                if (name != null && !name.trim().isEmpty()) clientName = name.trim();
            }
        }
        // Unknown types are ignored per the protocol's compatibility rule.
    }

    @Override protected void onClose(NanoWSD.WebSocketFrame.CloseCode code, String reason,
                                     boolean initiatedByRemote) {
        clients.remove(this);
    }

    @Override protected void onPong(NanoWSD.WebSocketFrame pong) {
    }

    @Override protected void onException(IOException exception) {
        clients.remove(this);
        Log.d(TAG, "socket failed: " + exception);
    }

    /** Send that reaps the socket instead of throwing when the connection is gone. */
    boolean trySend(String message) {
        if (message == null) return false;
        try {
            send(message);
            sentFrames++;
            lastActivityElapsedMs = android.os.SystemClock.elapsedRealtime();
            return true;
        } catch (Throwable error) {
            clients.remove(this);
            try {
                close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "send failed", false);
            } catch (Throwable ignored) { }
            return false;
        }
    }
}
