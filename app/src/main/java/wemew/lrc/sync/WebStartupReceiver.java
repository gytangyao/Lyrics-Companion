package wemew.lrc.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restarts the web server after a reboot or an app update when the user left it enabled. The
 * listener re-attach path ({@link MusicNotificationListener#onListenerConnected}) is the other
 * entry point, so a receiver miss never strands the service for longer than one track change.
 */
public final class WebStartupReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean restore = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        if (!restore) return;
        // Binding 0.0.0.0 needs no interface to be up yet, so start immediately.
        WebSyncService.start(context);
    }
}
