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
import android.os.IBinder;
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
    private static final String ACTION_STOP = "wemew.lrc.sync.action.STOP";

    private static volatile WebSyncService instance;

    private WebSocketClients clients;
    private CoverRegistry covers;
    private WebStatePublisher publisher;
    private WebDisplayServer server;

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

    static int clientCount() {
        WebSyncService service = instance;
        return service == null || service.clients == null ? 0 : service.clients.size();
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
        startServer();
        startInForeground();
        publisher.start();
        DiagnosticLog.record(this, "WebSync", "server started port="
                + (server == null ? -1 : server.getListeningPort()));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        instance = null;
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
        int preferred = AppPreferences.webPort(this);
        int first = preferred > 0 ? preferred : WebProtocol.DEFAULT_PORT;
        for (int candidate = first; candidate < first + 10; candidate++) {
            WebDisplayServer candidateServer = new WebDisplayServer(candidate, this, clients,
                    publisher, publisher);
            try {
                candidateServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
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
        List<String> urls = lanUrls();
        String text = urls.isEmpty() ? getString(R.string.service_notification_title)
                : urls.get(0);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, WebSyncService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, "停止", stopIntent)
                .build();
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
