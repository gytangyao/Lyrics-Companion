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
    /** PC-side WeSing watcher (wesing_watcher.js) announces itself with this deviceId. */
    static final String WATCHER_DEVICE_ID = "wesing-watcher-pc";
    /** A watcher heartbeat older than this no longer suppresses our WeSing publishing. */
    private static final long WATCHER_TIMEOUT_MS = 8_000L;
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
    /** Last heartbeat from the PC watcher; fresh means it owns WeSing publishing. */
    private volatile long watcherLastSeenElapsedMs;
    /** True from connect() until onOpen/onClose settles the handshake attempt. */
    private volatile boolean connecting;
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
        // A socket mid-handshake is already on its way to the right room; closing it
        // here would churn connections every time a lifecycle event re-applies the barId.
        boolean rightRoom = current != null && (current.isOpen() || connecting)
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
        AppLog.i(TAG, "connecting: " + url);
        try {
            WebSocketClient fresh = new WebSocketClient(URI.create(url)) {
                @Override public void onOpen(ServerHandshake handshake) {
                    connecting = false;
                    if (stopped) {
                        close();
                        return;
                    }
                    AppLog.i("WebSync", "cloud room joined: " + barId);
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
                    connecting = false;
                    if (stopped) return;
                    AppLog.w(TAG, "closed code=" + code + " remote=" + remote
                            + " reason=" + reason + "; retry in " + RECONNECT_MS + "ms");
                    handler.postDelayed(CloudSyncClient.this::syncConnection, RECONNECT_MS);
                }

                @Override public void onError(Exception error) {
                    AppLog.w(TAG, "ws error: " + error);
                    Log.d(TAG, "cloud error: " + error);
                }
            };
            if (Build.VERSION.SDK_INT == 19) {
                try {
                    fresh.setSocketFactory(HttpCompat.tls12Factory());
                } catch (Throwable ignored) { }
            }
            client = fresh;
            connecting = true;
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
            AppLog.w(TAG, "zombie link (no echo for "
                    + (SystemClock.elapsedRealtime() - lastInboundElapsedMs) + "ms); recycling");
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

    /** True for the PC watcher's heartbeat: another phone-side source in the room. */
    static boolean isWatcherOnline(WebProtocolCodec.Inbound inbound) {
        return "online".equals(inbound.type) && SENDER_PHONE.equals(inbound.sender)
                && inbound.data != null
                && WATCHER_DEVICE_ID.equals(inbound.data.optString("deviceId", ""));
    }

    /** Whether the PC watcher heart-beated recently enough to own WeSing publishing. */
    boolean watcherHeartbeatFresh() {
        long last = watcherLastSeenElapsedMs;
        return last > 0L && SystemClock.elapsedRealtime() - last < WATCHER_TIMEOUT_MS;
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
        if (isWatcherOnline(inbound)) {
            watcherLastSeenElapsedMs = SystemClock.elapsedRealtime();
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
                // Null = another source (the PC watcher) owns the current song; the
                // watcher answers joining displays with its own snapshot.
                String track = hooks.trackJson();
                if (track != null) send(track);
                String state = hooks.stateJson();
                if (state != null) send(state);
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
