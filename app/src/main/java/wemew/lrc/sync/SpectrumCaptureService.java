package wemew.lrc.sync;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service for the playback-capture path, ported from audio_flux's
 * AudioCaptureService. Android 9+ blocks background audio input, and Android 10+ additionally
 * requires a mediaProjection-typed foreground service before AudioPlaybackCapture may run.
 *
 * <p>The typed {@code startForeground} call happens <b>after</b> the user granted the
 * projection in settings and <b>before</b> the projection is rebuilt — the order Android 14
 * demands. The notification exists only while the capture path is actually in use.
 */
public final class SpectrumCaptureService extends Service {
    private static final String CHANNEL_ID = "spectrum_capture";
    private static final int NOTIFICATION_ID = 8801;

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT < SpectrumCaptureDecision.MIN_PLAYBACK_CAPTURE_API) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        if (!AudioSpectrumSource.startPlaybackCapture(this)) {
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        // The projection grant stays in memory: a later resume must not re-ask for consent.
        AudioSpectrumSource.onCaptureServiceDestroyed();
        super.onDestroy();
    }

    // Lint 8.7 does not associate the manifest's foregroundServiceType with these calls when
    // the service also keeps a pre-29 fallback; same situation as LyricsDisplayService.onCreate.
    @SuppressLint("ForegroundServiceType")
    private void startForegroundCompat() {
        createChannel();
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pendingIntent = launchIntent == null ? null : PendingIntent.getActivity(
                this, 0, launchIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        // The channel-scoped constructor only exists from Android 8; the pre-O builder keeps
        // old head units from crashing with NoSuchMethodError.
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle("歌词伴侣")
                .setContentText("频谱背景 · 应用内录 · 正在采集音频")
                .setSmallIcon(getApplicationInfo().icon)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
        // The typed overload needs API 29; the guard keeps lint's flow analysis happy because
        // this helper is reached only from the already-guarded onStartCommand.
        if (Build.VERSION.SDK_INT >= SpectrumCaptureDecision.MIN_PLAYBACK_CAPTURE_API) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        // STOP_FOREGROUND_REMOVE only exists from Android 7; the boolean form covers 5.x/6.x.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "频谱背景采集",
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    /** Starts the typed foreground service; only ever called on the Android 10+ path. */
    static void start(Context context) {
        Intent intent = new Intent(context, SpectrumCaptureService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable error) {
            android.util.Log.w("SpectrumCapture", "Unable to start capture service", error);
        }
    }

    /** StopService rather than a command intent: background startService is restricted on 8+. */
    static void requestStop(Context context) {
        try {
            context.stopService(new Intent(context, SpectrumCaptureService.class));
        } catch (Throwable ignored) { }
    }
}
