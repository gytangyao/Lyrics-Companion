package wemew.lrc.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Foreground service hosting the cloud sync stack: the lyric publisher fans protocol frames
 * into the wemew store room ({@link CloudSyncClient}), and the store binding poller keeps the
 * room address fresh. There is no local network listener — sync only ever goes through the
 * cloud relay, so display clients need nothing but the store's barId.
 */
public final class WebSyncService extends Service {
    private static final String CHANNEL_ID = "web_sync";
    private static final int NOTIFICATION_ID = 41;
    /** Binding-state changes ask the service to re-target the cloud room. */
    static final String ACTION_REEVALUATE = "wemew.lrc.sync.action.REEVALUATE";
    /** If a publish tick stalls this long the publisher is rebuilt and the stacks logged. */
    private static final long WATCHDOG_STALL_MS = 20_000L;
    private static final long WATCHDOG_INTERVAL_MS = 15_000L;

    private static volatile WebSyncService instance;

    private WebStatePublisher publisher;
    private StoreBindingClient bindingClient;
    private CloudSyncClient cloudSync;
    private KgeGattServer kgeServer;
    private KgeAdvertiser kgeAdvertiser;
    private volatile boolean kgeReady;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            WebStatePublisher current = publisher;
            if (current != null && current.tickAgeMs() > WATCHDOG_STALL_MS) {
                recoverFromStall();
            }
            // Restart the KGe BLE leg if Bluetooth came up after boot, and idle out old songs.
            if (kgeAdvertiser != null && !kgeReady) {
                kgeAdvertiser.start(WebSyncService.this,
                        StoreBindingClient.deviceId(WebSyncService.this));
                kgeServer.start();
                kgeReady = kgeAdvertiser.isAdvertising();
            }
            KgeLyricStore.maintain(WebSyncService.this);
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

    static boolean isRunning() {
        return instance != null;
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

    static String pushSummary() {
        WebSyncService service = instance;
        return service == null || service.publisher == null
                ? "" : service.publisher.lastPushSummary();
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        // Binding always runs; a new barId re-targets the cloud room.
        bindingClient = new StoreBindingClient(this);
        bindingClient.start();
        publisher = new WebStatePublisher(this);
        cloudSync = new CloudSyncClient(this, publisher);
        cloudSync.applyBarId(AppPreferences.boundBarId(this));
        publisher.start();
        publisher.setCloud(cloudSync);
        startKge();
        startInForeground();
        watchdogHandler.removeCallbacks(watchdog);
        watchdogHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
        DiagnosticLog.record(this, "WebSync", "cloud sync service started");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_REEVALUATE.equals(intent.getAction())
                && cloudSync != null) {
            cloudSync.applyBarId(AppPreferences.boundBarId(this));
        }
        return START_STICKY;
    }

    /** The KGe BLE leg: advertiser + GATT server + lyric accumulation into the store. */
    private void startKge() {
        if (kgeServer != null) return;
        kgeAdvertiser = new KgeAdvertiser(what ->
                DiagnosticLog.record(this, "Kge", what));
        kgeServer = new KgeGattServer(this, new KgeGattServer.Listener() {
            @Override public void onLyricEvent(String text, long startMs) {
                KgeLyricStore.onLyricEvent(WebSyncService.this, text, startMs);
            }

            @Override public void onNotice(String what) {
                DiagnosticLog.record(WebSyncService.this, "Kge", what);
            }
        });
        kgeAdvertiser.start(this, StoreBindingClient.deviceId(this));
        kgeServer.start();
        kgeReady = kgeAdvertiser.isAdvertising();
    }

    static boolean kgeAdvertising() {
        WebSyncService service = instance;
        return service != null && service.kgeAdvertiser != null
                && service.kgeAdvertiser.isAdvertising();
    }

    static boolean kgeConnected() {
        WebSyncService service = instance;
        return service != null && service.kgeServer != null && service.kgeServer.isConnected();
    }

    @Override public void onDestroy() {
        instance = null;
        watchdogHandler.removeCallbacks(watchdog);
        if (kgeServer != null) kgeServer.stop();
        if (kgeAdvertiser != null) kgeAdvertiser.stop();
        if (cloudSync != null) cloudSync.stop();
        if (bindingClient != null) bindingClient.stop();
        if (publisher != null) publisher.stop();
        DiagnosticLog.record(this, "WebSync", "cloud sync service stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * The publisher tick is the heartbeat of the sync stack. When it stalls, every display is
     * already frozen; capture every thread stack (the root cause goes into the diagnostic log)
     * and rebuild the publisher instead of staying wedged.
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
        if (publisher != null) publisher.start();
    }

    // The manifest declares the specialUse type; lint cannot see through ServiceCompat.
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
            androidx.core.app.ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        boolean cloudUp = cloudSync != null && cloudSync.connected();
        String text = cloudUp ? "云端已连接门店房间"
                : (AppPreferences.boundBarId(this).isEmpty()
                        ? "等待门店登记设备码" : "云端连接中…");
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
}
