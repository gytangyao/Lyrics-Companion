package wemew.lrc.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.format.Formatter;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import fi.iki.elonen.NanoHTTPD;

/**
 * Foreground service hosting the LAN web server. Keeps the process alive on aggressive
 * automotive ROMs and shows the reachable URL so the user can type it into the head unit.
 */
public final class WebSyncService extends Service {
    private static final String CHANNEL_ID = "web_sync";
    private static final int NOTIFICATION_ID = 41;
    /** Binding-state changes ask the service to re-run the license gate. */
    static final String ACTION_REEVALUATE = "wemew.lrc.sync.action.REEVALUATE";
    /** NanoHTTPD socket read timeout; see {@link #startServer()} for why it is this long. */
    private static final int SOCKET_IDLE_TIMEOUT_MS = 10 * 60 * 1000;

    private static volatile WebSyncService instance;

    private WebSocketClients clients;
    private CoverRegistry covers;
    private WebStatePublisher publisher;
    private WebDisplayServer server;
    private StoreBindingClient bindingClient;
    private CloudSyncClient cloudSync;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    /** If a publish tick stalls this long the whole stack is rebuilt and the stacks logged. */
    private static final long WATCHDOG_STALL_MS = 20_000L;
    private static final long WATCHDOG_INTERVAL_MS = 15_000L;

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            WebStatePublisher current = publisher;
            if (current != null && server != null
                    && current.tickAgeMs() > WATCHDOG_STALL_MS) {
                recoverFromStall();
            }
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    static void start(Context context) {
        Intent intent = new Intent(context, WebSyncService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(context, intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, WebSyncService.class));
    }

    static boolean isRunning() {
        return instance != null;
    }

    /** Actual listening port, or 0 when the server is not running. */
    static int port() {
        WebSyncService service = instance;
        return service == null || service.server == null
                ? 0 : service.server.getListeningPort();
    }

    static int clientCount() {
        WebSyncService service = instance;
        return service == null || service.clients == null ? 0 : service.clients.size();
    }

    static java.util.List<WebSyncSocket> clientSockets() {
        WebSyncService service = instance;
        return service == null || service.clients == null
                ? java.util.Collections.emptyList() : service.clients.list();
    }

    static String pushSummary() {
        WebSyncService service = instance;
        return service == null || service.publisher == null
                ? "" : service.publisher.lastPushSummary();
    }

    static boolean cloudConnected() {
        WebSyncService service = instance;
        return service != null && service.cloudSync != null && service.cloudSync.connected();
    }

    static int cloudDisplayCount() {
        WebSyncService service = instance;
        return service == null || service.cloudSync == null ? 0
                : service.cloudSync.onlineDisplays();
    }

    /** Best-effort LAN URLs for the settings page; empty when the server is not running. */
    static List<String> lanUrls() {
        WebSyncService service = instance;
        if (service == null || service.server == null) return Collections.emptyList();
        int port = service.server.getListeningPort();
        List<String> urls = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    urls.add("http://" + address.getHostAddress() + ":" + port);
                }
            }
        } catch (Throwable ignored) { }
        if (urls.isEmpty()) {
            // Wi-Fi lock is not held; the manager still knows the last AP address on most ROMs.
            try {
                WifiManager wifi = (WifiManager) service.getApplicationContext()
                        .getSystemService(WIFI_SERVICE);
                if (wifi != null) {
                    int address = wifi.getConnectionInfo().getIpAddress();
                    if (address != 0) {
                        urls.add("http://" + Formatter.formatIpAddress(address) + ":" + port);
                    }
                }
            } catch (Throwable ignored) { }
        }
        return urls;
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        clients = new WebSocketClients();
        covers = new CoverRegistry();
        publisher = new WebStatePublisher(this, clients, covers, versionName());
        // Binding always runs; a new barId re-targets the cloud room.
        bindingClient = new StoreBindingClient(this);
        bindingClient.start();
        cloudSync = new CloudSyncClient(this, publisher);
        cloudSync.applyBarId(AppPreferences.boundBarId(this));
        startServer();
        publisher.start();
        publisher.setCloud(cloudSync);
        startInForeground();
        watchdogHandler.removeCallbacks(watchdog);
        watchdogHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
        DiagnosticLog.record(this, "WebSync", "service started port="
                + (server == null ? -1 : server.getListeningPort()));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_REEVALUATE.equals(intent.getAction())
                && cloudSync != null) {
            cloudSync.applyBarId(AppPreferences.boundBarId(this));
        }
        return START_STICKY;
    }

    /**
     * The publisher tick is the heartbeat of the whole stack. When it stalls past the
     * threshold, every client is already frozen; rather than staying wedged, capture every
     * thread stack (the root cause goes into the diagnostic log) and rebuild the server.
     */
    private void recoverFromStall() {
        try {
            StringBuilder stacks = new StringBuilder();
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                StackTraceElement[] frames = Thread.getAllStackTraces().get(thread);
                if (frames == null || frames.length == 0) continue;
                stacks.append(thread.getName()).append(": ");
                for (int i = 0; i < Math.min(6, frames.length); i++) {
                    stacks.append(frames[i].toString()).append(" | ");
                }
                stacks.append('\n');
            }
            DiagnosticLog.record(this, "WebSync",
                    "publisher stalled " + publisher.tickAgeMs() + "ms; rebuilding. stacks:\n"
                            + stacks);
        } catch (Throwable ignored) { }
        if (publisher != null) publisher.stop();
        if (server != null) {
            server.stop();
            server = null;
        }
        startServer();
        if (publisher != null) publisher.start();
    }

    @Override public void onDestroy() {
        instance = null;
        watchdogHandler.removeCallbacks(watchdog);
        if (cloudSync != null) cloudSync.stop();
        if (bindingClient != null) bindingClient.stop();
        if (publisher != null) publisher.stop();
        if (server != null) {
            server.stop();
            server = null;
        }
        DiagnosticLog.record(this, "WebSync", "server stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void startServer() {
        int first = WebProtocol.DEFAULT_PORT;
        for (int candidate = first; candidate < first + 10; candidate++) {
            WebDisplayServer candidateServer = new WebDisplayServer(candidate, this, clients,
                    publisher, publisher);
            try {
                // A web socket rides the HTTP session thread, so NanoHTTPD's socket read
                // timeout is also the web-socket idle limit. SOCKET_READ_TIMEOUT (5 s) killed
                // idle display clients every 5 seconds; 10 minutes lets the client's 5-second
                // application pings keep the read alive while still reaping dead HTTP peers.
                candidateServer.start(SOCKET_IDLE_TIMEOUT_MS, false);
                server = candidateServer;
                return;
            } catch (IOException error) {
                candidateServer.stop();
            }
        }
        DiagnosticLog.record(this, "WebSync", "no free port in " + first + ".." + (first + 9));
    }

    // The manifest declares android:foregroundServiceType="specialUse" on this service; lint's
    // ForegroundServiceType check cannot see through ServiceCompat, so the known-good call is
    // suppressed here.
    @android.annotation.SuppressLint("ForegroundServiceType")
    private void startInForeground() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 30) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        boolean cloudUp = cloudSync != null && cloudSync.connected();
        String text = cloudUp
                ? "云端已连接 · " + primaryUrl()
                : (AppPreferences.boundBarId(this).isEmpty()
                        ? "等待门店登记设备码" : primaryUrl());
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private String primaryUrl() {
        List<String> urls = lanUrls();
        return urls.isEmpty() ? getString(R.string.service_notification_title) : urls.get(0);
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "";
        }
    }
}
