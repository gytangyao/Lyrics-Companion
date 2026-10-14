package wemew.lrc.sync;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Cloud leg of the sync transport: joins the wemew room relay at
 * {@code wss://socket.wemew.com/websocket/bluetooth_box/{barId}} as the phone side
 * ({@code control_box_phone}) and pushes the same protocol-v1 frames the LAN server serves —
 * the cloud relays member frames verbatim to the whole room, so display clients join the same
 * room as {@code control_box} and need nothing else. This sidesteps the phone's ever-changing
 * LAN address entirely; without a bound barId there is no room, so unbound devices simply stay
 * disconnected (which is why there is no local trial gate).
 */
final class CloudSyncClient {
    private static final String TAG = "CloudSync";
    private static final String ROOM_BASE = "wss://socket.wemew.com/websocket/bluetooth_box/";
    /** The phone is the lyric source; the display client is control_box (nbox naming). */
    static final String SENDER_PHONE = "control_box_phone";
    static final String SENDER_DISPLAY = "control_box";
    private static final long HEARTBEAT_MS = 2_000L;
    private static final long RECONNECT_MS = 5_000L;
    /** A display that stops heart-beating for this long is considered gone. */
    private static final long DISPLAY_TIMEOUT_MS = 10_000L;
    /** Snapshot rate limit while displays keep heart-beating every 2 s. */
    private static final long SNAPSHOT_MIN_INTERVAL_MS = 1_500L;

    /** Fresh frames for a display that just joined the room. */
    interface SnapshotSource {
        String trackJson();

        String stateJson();
    }

    private final Context context;
    private final SnapshotSource hooks;
    private final HandlerThread thread;
    private final Handler handler;
    private final Map<String, Long> displayLastSeenElapsed = new HashMap<>();
    private volatile String barId = "";
    private volatile WebSocketClient client;
    private volatile boolean stopped;
    private volatile long lastSnapshotPushElapsed;
    /** Any inbound frame proves the relay still sees us; heartbeats echo back in ~RTT. */
    private volatile long lastInboundElapsedMs;
    /**
     * A half-open TCP looks alive locally (writes buffer silently), so a connection that
     * stops echoing within this window is treated as dead and recycled.
     */
    private static final long ZOMBIE_TIMEOUT_MS = 15_000L;

    CloudSyncClient(Context context, SnapshotSource hooks) {
        this.context = context.getApplicationContext();
        this.hooks = hooks;
        thread = new HandlerThread("CloudSync");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** Connects to the bound store's room; an empty barId closes any open connection. */
    void applyBarId(String boundBarId) {
        barId = boundBarId == null ? "" : boundBarId.trim();
        displayLastSeenElapsed.clear();
        handler.post(this::syncConnection);
    }

    boolean connected() {
        WebSocketClient current = client;
        return current != null && current.isOpen();
    }

    /** Displays seen heart-beating in the room within the timeout window. */
    int onlineDisplays() {
        long now = SystemClock.elapsedRealtime();
        int count = 0;
        synchronized (displayLastSeenElapsed) {
            for (Long seen : displayLastSeenElapsed.values()) {
                if (now - seen < DISPLAY_TIMEOUT_MS) count++;
            }
        }
        return count;
    }

    /** Frame fan-out from the publisher; cloud writes never block the publisher tick. */
    void send(String json) {
        if (json == null || stopped) return;
        handler.post(() -> {
            WebSocketClient current = client;
            if (current == null || !current.isOpen()) return;
            try {
                current.send(json);
            } catch (Throwable error) {
                Log.d(TAG, "cloud send failed: " + error);
            }
        });
    }

    void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
        WebSocketClient current = client;
        if (current != null) {
            try {
                current.closeBlocking();
            } catch (Throwable ignored) { }
        }
    }

    private void syncConnection() {
        if (stopped) return;
        WebSocketClient current = client;
        boolean rightRoom = current != null && current.isOpen()
                && current.getURI().toString().endsWith(barId);
        if (barId.isEmpty()) {
            closeClient();
            return;
        }
        if (rightRoom) return;
        closeClient();
        connect();
    }

    private void closeClient() {
        WebSocketClient current = client;
        client = null;
        if (current != null) {
            try {
                current.close();
            } catch (Throwable ignored) { }
        }
    }

    private void connect() {
        final String url = ROOM_BASE + barId;
        try {
            WebSocketClient fresh = new WebSocketClient(URI.create(url)) {
                @Override public void onOpen(ServerHandshake handshake) {
                    if (stopped) {
                        close();
                        return;
                    }
                    DiagnosticLog.record(context, "WebSync", "cloud room joined: " + barId);
                    lastInboundElapsedMs = SystemClock.elapsedRealtime();
                    handler.post(() -> {
                        sendOnline();
                        scheduleHeartbeat();
                    });
                }

                @Override public void onMessage(String message) {
                    handler.post(() -> handleInbound(message));
                }

                @Override public void onClose(int code, String reason, boolean remote) {
                    if (stopped) return;
                    handler.postDelayed(CloudSyncClient.this::syncConnection, RECONNECT_MS);
                }

                @Override public void onError(Exception error) {
                    Log.d(TAG, "cloud error: " + error);
                }
            };
            if (Build.VERSION.SDK_INT == 19) {
                try {
                    fresh.setSocketFactory(HttpCompat.tls12Factory());
                } catch (Throwable ignored) { }
            }
            client = fresh;
            fresh.connect();
        } catch (Throwable error) {
            Log.w(TAG, "cloud connect failed", error);
            handler.postDelayed(this::syncConnection, RECONNECT_MS);
        }
    }

    private void scheduleHeartbeat() {
        if (stopped) return;
        handler.postDelayed(this::heartbeat, HEARTBEAT_MS);
    }

    private void heartbeat() {
        if (stopped) return;
        // Self-liveness: the relay echoes our heartbeat back, so silence means a zombie link.
        if (lastInboundElapsedMs > 0L
                && SystemClock.elapsedRealtime() - lastInboundElapsedMs > ZOMBIE_TIMEOUT_MS) {
            DiagnosticLog.record(context, "WebSync",
                    "cloud zombie link detected; recycling (" + barId + ")");
            closeClient();
        }
        sendOnline();
        scheduleHeartbeat();
    }

    private void sendOnline() {
        try {
            org.json.JSONObject data = new org.json.JSONObject();
            WebProtocolCodec.put(data, "deviceId", StoreBindingClient.deviceId(context));
            WebProtocolCodec.put(data, "timestamp", System.currentTimeMillis());
            org.json.JSONObject frame = new org.json.JSONObject();
            WebProtocolCodec.put(frame, "type", "online");
            WebProtocolCodec.put(frame, "sender", SENDER_PHONE);
            WebProtocolCodec.put(frame, "data", data);
            send(frame.toString());
        } catch (Throwable error) {
            Log.w(TAG, "heartbeat build failed", error);
        }
    }

    /** True for a display's clock-sync ping (our v1 envelope with a ts). */
    static boolean isDisplayPing(WebProtocolCodec.Inbound inbound) {
        return WebProtocol.TYPE_PING.equals(inbound.type) && inbound.ts != 0L;
    }

    /** True for a room heartbeat from a display (the only online frame we react to). */
    static boolean isDisplayOnline(WebProtocolCodec.Inbound inbound) {
        return "online".equals(inbound.type) && SENDER_DISPLAY.equals(inbound.sender)
                && inbound.data != null
                && !inbound.data.optString("deviceId", "").isEmpty();
    }

    /**
     * The cloud echoes every member's frames back to the whole room (including us), so the
     * classification here ignores our own traffic and only reacts to displays and their pings.
     */
    private void handleInbound(String raw) {
        if (raw == null || "ping".equals(raw.trim())) return;
        lastInboundElapsedMs = SystemClock.elapsedRealtime();
        WebProtocolCodec.Inbound inbound = WebProtocolCodec.parseInbound(raw);
        if (inbound.type.isEmpty()) return;
        if (isDisplayPing(inbound)) {
            // A display's clock-sync ping; the relayed pong reaches every room member.
            send(WebProtocolCodec.pong(inbound.ts, System.currentTimeMillis()));
            return;
        }
        if (isDisplayOnline(inbound)) {
            String deviceId = inbound.data.optString("deviceId", "");
            boolean known;
            synchronized (displayLastSeenElapsed) {
                known = displayLastSeenElapsed.containsKey(deviceId);
                displayLastSeenElapsed.put(deviceId, SystemClock.elapsedRealtime());
                pruneDisplaysLocked();
            }
            // Catch a display up quickly; rate-limited because heartbeats repeat every 2 s.
            long now = SystemClock.elapsedRealtime();
            if (!known || now - lastSnapshotPushElapsed >= SNAPSHOT_MIN_INTERVAL_MS) {
                lastSnapshotPushElapsed = now;
                send(hooks.trackJson());
                send(hooks.stateJson());
            }
        }
    }

    private void pruneDisplaysLocked() {
        long now = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, Long>> entries = displayLastSeenElapsed.entrySet().iterator();
        while (entries.hasNext()) {
            if (now - entries.next().getValue() >= DISPLAY_TIMEOUT_MS) entries.remove();
        }
    }
}
