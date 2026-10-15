package wemew.lrc.sync;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

/** Single-page status surface: store binding, sync state, lyric info and permissions. */
@SuppressLint("SetTextI18n")
public final class MainActivity extends AppCompatActivity {
    private static final long LISTENER_HEALTH_MAX_AGE_MS = 3_000L;
    private static final long LISTENER_INITIAL_RECONNECT_DELAY_MS = 2_500L;
    private static final long LISTENER_RECONNECT_INTERVAL_MS = 1_000L;
    private static final long LISTENER_RECONNECT_WINDOW_MS = 30_000L;
    private static final int PERMISSION_CHECK_NOTIFICATION = 1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView bindingHeadline;
    private TextView bindingStore;
    private TextView bindingDevice;
    private TextView bindingMeta;
    private TextView cloudStatus;
    private TextView cloudPush;
    private TextView wesingStatus;
    private TextView musicTitle;
    private TextView musicSource;
    private TextView lyricDetail;
    private MaterialButton outputToggle;
    private LinearLayout permissionRows;
    private boolean permissionDialogVisible;
    private int pendingPermissionCheck;
    private boolean activityResumed;
    private boolean listenerReconnectScheduled;
    private long listenerReconnectDeadlineElapsedMs;

    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 700L);
        }
    };

    private final Runnable listenerReconnect = new Runnable() {
        @Override public void run() {
            listenerReconnectScheduled = false;
            if (!activityResumed || !hasNotificationAccess()
                    || MusicNotificationListener.isHealthy(LISTENER_HEALTH_MAX_AGE_MS)) {
                return;
            }
            MusicNotificationListener.requestReconnect(MainActivity.this);
            if (SystemClock.elapsedRealtime() < listenerReconnectDeadlineElapsedMs) {
                listenerReconnectScheduled = true;
                handler.postDelayed(this, LISTENER_RECONNECT_INTERVAL_MS);
            } else {
                listenerReconnectScheduled = false;
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        // The status surface is intentionally a stable dark workspace.
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFF0B0E1C);
            getWindow().setNavigationBarColor(0xFF0B0E1C);
        }
        MusicStateStore.initialize(this);
        if (!AppPreferences.onboardingDone(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        setContentView(buildContent());
    }

    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
        // A package replace or an aggressive ROM kill leaves the service gone; the sync server
        // is always-on, so returning to the app brings it back.
        try {
            WebSyncService.start(this);
        } catch (Throwable ignored) { }
        ensureNotificationListenerConnected();
        handler.removeCallbacks(statusRefresh);
        handler.post(statusRefresh);
        // Some ROMs update the permission state a moment after their Settings page closes.
        // Check after that hand-off so a newly granted switch never produces a false warning.
        handler.postDelayed(this::promptPermissionIfStillMissing, 350L);
    }

    @Override protected void onPause() {
        activityResumed = false;
        listenerReconnectScheduled = false;
        handler.removeCallbacks(listenerReconnect);
        handler.removeCallbacks(statusRefresh);
        super.onPause();
    }

    private View buildContent() {
        View shell = getLayoutInflater().inflate(R.layout.activity_main, null, false);
        LinearLayout root = shell.findViewById(R.id.main_content);
        LinearLayout pageHost = shell.findViewById(R.id.main_page_host);

        // ---- 门店授权 ----
        LinearLayout bindingCard = card();
        bindingCard.addView(sectionLabel("门店授权"));
        bindingHeadline = text("", 16, 0xFFF3F7FC, true);
        bindingHeadline.setPadding(0, dp(8), 0, dp(4));
        bindingCard.addView(bindingHeadline);
        bindingStore = text("", 14, 0xFFF40861, false);
        bindingStore.setPadding(0, 0, 0, dp(6));
        bindingCard.addView(bindingStore);
        LinearLayout deviceRow = new LinearLayout(this);
        deviceRow.setOrientation(LinearLayout.HORIZONTAL);
        deviceRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView deviceLabel = text("设备码", 13, 0xFFADABF2, false);
        deviceRow.addView(deviceLabel, new LinearLayout.LayoutParams(-2, -2));
        bindingDevice = text("", 15, 0xFFF3F7FC, true);
        if (Build.VERSION.SDK_INT >= 21) bindingDevice.setLetterSpacing(0.12f);
        bindingDevice.setPadding(dp(10), 0, dp(10), 0);
        deviceRow.addView(bindingDevice, new LinearLayout.LayoutParams(0, -2, 1f));
        MaterialButton copyDevice = button("复制", false);
        copyDevice.setOnClickListener(v -> copyText("设备码",
                StoreBindingClient.deviceId(this)));
        copyDevice.setMinWidth(dp(72));
        deviceRow.addView(copyDevice,
                new LinearLayout.LayoutParams(-2, dp(42)));
        bindingCard.addView(deviceRow);
        TextView bindingHint = text("请把设备码登记到微喵门店后台完成授权；绑定后自动同步门店信息。",
                12, 0xFF7A7FA5, false);
        bindingHint.setLineSpacing(0f, 1.2f);
        bindingHint.setPadding(0, dp(6), 0, dp(2));
        bindingCard.addView(bindingHint);
        bindingMeta = text("", 12, 0xFF7A7FA5, false);
        bindingMeta.setPadding(0, dp(2), 0, 0);
        bindingCard.addView(bindingMeta);

        // ---- 同步状态 ----
        LinearLayout syncCard = card();
        syncCard.addView(sectionLabel("同步状态"));
        cloudStatus = text("", 14, 0xFFF40861, true);
        cloudStatus.setPadding(0, dp(8), 0, dp(2));
        syncCard.addView(cloudStatus);
        TextView cloudHint = text("同步经微喵云门店房间转发，显示端只需门店号即可连接，"
                + "显示页在仓库 web-test-client（?barId=门店号）。",
                12, 0xFF7A7FA5, false);
        cloudHint.setLineSpacing(0f, 1.2f);
        syncCard.addView(cloudHint);
        cloudPush = text("", 12, 0xFF7A7FA5, false);
        cloudPush.setPadding(0, dp(4), 0, 0);
        syncCard.addView(cloudPush);
        wesingStatus = text("", 13, 0xFFADABF2, false);
        wesingStatus.setPadding(0, dp(6), 0, 0);
        syncCard.addView(wesingStatus);
        // ---- 歌词信息 ----
        LinearLayout lyricCard = card();
        lyricCard.addView(sectionLabel("歌词信息"));
        musicTitle = text("等待播放器…", 16, 0xFFF3F7FC, true);
        musicTitle.setPadding(0, dp(8), 0, dp(2));
        lyricCard.addView(musicTitle);
        musicSource = text("", 13, 0xFFADABF2, false);
        musicSource.setPadding(0, 0, 0, dp(2));
        lyricCard.addView(musicSource);
        lyricDetail = text("", 13, 0xFF8B90B8, false);
        lyricDetail.setPadding(0, 0, 0, dp(6));
        lyricCard.addView(lyricDetail);
        outputToggle = button("停止大屏歌词（仅本曲）", true);
        outputToggle.setOnClickListener(v -> {
            String pausedSong = AppPreferences.pausedSongKey(this);
            if (pausedSong.isEmpty()) {
                MusicSnapshot snapshot =
                        MusicStateStore.snapshot(AppPreferences.lyricOffsetMs(this));
                AppPreferences.setPausedSongKey(this, WebStatePublisher.songKey(
                        snapshot.title, snapshot.artist,
                        MusicStateStore.activeSourceId()));
                SafeToast.show(this, "本曲大屏歌词已停止，切歌后自动恢复", Toast.LENGTH_SHORT);
            } else {
                AppPreferences.setPausedSongKey(this, "");
                SafeToast.show(this, "歌词输出已恢复", Toast.LENGTH_SHORT);
            }
            refreshLyric();
        });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-1, dp(46));
        lyricCard.addView(outputToggle, toggleParams);
        MaterialButton rematch = button("修正歌曲信息并重新匹配", false);
        rematch.setOnClickListener(v -> showLyricRematchDialog());
        LinearLayout.LayoutParams rematchParams = new LinearLayout.LayoutParams(-1, dp(46));
        rematchParams.topMargin = dp(8);
        lyricCard.addView(rematch, rematchParams);

        // ---- 权限 ----
        LinearLayout permissionCard = card();
        permissionCard.addView(sectionLabel("使用权限"));
        permissionRows = new LinearLayout(this);
        permissionRows.setOrientation(LinearLayout.VERTICAL);
        permissionCard.addView(permissionRows);

        pageHost.addView(bindingCard, cardMargins());
        pageHost.addView(syncCard, cardMargins());
        pageHost.addView(lyricCard, cardMargins());
        pageHost.addView(permissionCard, cardMargins());
        return shell;
    }

    private void refreshStatus() {
        refreshBinding();
        refreshSync();
        refreshLyric();
        refreshPermissions();
    }

    private void refreshBinding() {
        String barId = AppPreferences.boundBarId(this);
        String barName = AppPreferences.boundBarName(this);
        boolean bound = !barId.isEmpty();
        bindingHeadline.setText(bound ? "已绑定门店" : "未绑定");
        bindingHeadline.setTextColor(bound ? 0xFFF40861 : 0xFFF4E230);
        bindingStore.setText(bound ? barName + "（" + barId + "）" : "");
        bindingDevice.setText(StoreBindingClient.deviceId(this));
        long lastCheck = AppPreferences.bindingLastCheckMs(this);
        String error = AppPreferences.bindingLastError(this);
        StringBuilder meta = new StringBuilder();
        boolean checkFailed = error != null && !error.trim().isEmpty();
        if (lastCheck > 0L) {
            meta.append(checkFailed ? "检查失败" : "最近检查")
                    .append(" ").append(formatAge(lastCheck)).append("前");
        }
        if (checkFailed) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append("错误：").append(error);
        }
        bindingMeta.setText(meta.toString());
        bindingMeta.setTextColor(checkFailed ? 0xFFF4E230 : 0xFF7A7FA5);
    }

    private void refreshSync() {
        boolean cloudUp = WebSyncService.cloudConnected();
        String barId = AppPreferences.boundBarId(this);
        if (barId.isEmpty()) {
            cloudStatus.setText("云端：未绑定，等待门店登记设备码");
            cloudStatus.setTextColor(0xFFF4E230);
        } else if (cloudUp) {
            cloudStatus.setText("云端：已连接门店房间 · 显示端 " + WebSyncService.cloudDisplayCount()
                    + " 个");
            cloudStatus.setTextColor(0xFFF40861);
        } else {
            cloudStatus.setText("云端：连接中…（已绑定 " + barId + "）");
            cloudStatus.setTextColor(0xFFF4E230);
        }
        String push = WebSyncService.pushSummary();
        cloudPush.setText(push == null || push.isEmpty() ? "" : "最近推送：" + push);
        boolean wesingEnabled = WeSingLyricReader.enabled(this);
        boolean wesingReading = WeSingLyricReader.reading();
        String identity = WeSingLyricReader.lastIdentity();
        int collected = WeSingLyricReader.collectedLines();
        wesingStatus.setText(!wesingEnabled ? "全民K歌：未启用无障碍读取（权限卡可开启）"
                : wesingReading ? "全民K歌：读取中 · " + (identity.isEmpty() ? "" : identity + " · ")
                        + collected + " 行屏幕歌词"
                : "全民K歌：已启用，等待演唱");
        wesingStatus.setTextColor(wesingReading ? 0xFFF40861 : 0xFFADABF2);
    }

    private void refreshLyric() {
        MusicSnapshot snapshot = MusicStateStore.snapshot(AppPreferences.lyricOffsetMs(this));
        if (!snapshot.active || (snapshot.title.trim().isEmpty()
                && snapshot.artist.trim().isEmpty())) {
            musicTitle.setText("等待播放器…");
            musicSource.setText("在线、本地和 U 盘播放器优先读取系统媒体信息");
            lyricDetail.setText("");
            return;
        }
        musicTitle.setText(snapshot.title + (snapshot.artist.isEmpty() ? ""
                : " - " + snapshot.artist));
        String listenerState = listenerState(hasNotificationAccess());
        musicSource.setText("来源：" + snapshot.sourceName
                + "    读取方式：" + backendDescription()
                + "    监听器：" + listenerState);
        String matchState;
        if (snapshot.liveLyricActive) {
            matchState = "播放器实时歌词";
        } else if (snapshot.lyricAvailable) {
            matchState = "歌词已就绪" + (snapshot.lyricSourceName.isEmpty()
                    ? "" : " · " + snapshot.lyricSourceName);
        } else if (snapshot.lyricLoaded) {
            matchState = "未匹配到歌词";
        } else {
            matchState = "正在匹配歌词";
        }
        LrcTimeline timeline = MusicStateStore.currentTimeline();
        int total = timeline.lineCount();
        int wordTimed = 0;
        for (LrcTimeline.Line line : timeline.lines()) {
            if (line.words != null && line.words.size() > 1) wordTimed++;
        }
        String timing = total == 0 ? ""
                : "    逐字时间轴 " + wordTimed + "/" + total + " 行"
                        + (wordTimed > 0 ? "" : "（行级，整行渐变）");
        lyricDetail.setText("匹配：" + matchState + timing);
        boolean paused = !AppPreferences.pausedSongKey(this).isEmpty();
        if (paused) {
            lyricDetail.setText("本曲大屏显示已停止：识别有误时使用，切歌自动恢复，也可手动恢复");
            lyricDetail.setTextColor(0xFFF4E230);
        } else {
            lyricDetail.setTextColor(0xFF8B90B8);
        }
        outputToggle.setText(paused ? "恢复本曲歌词推送" : "停止大屏歌词（仅本曲）");
        outputToggle.setBackgroundTintList(ColorStateList.valueOf(paused
                ? 0xFFF4E230
                : themeColor(com.google.android.material.R.attr.colorPrimary, 0xFFF40861)));
        outputToggle.setTextColor(paused ? 0xFF0B0E1C
                : themeColor(com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF));
    }

    private void refreshPermissions() {
        permissionRows.removeAllViews();
        if (!hasNotificationAccess()) {
            addPermissionRow(permissionRows, "音乐读取权限", true, v -> openNotificationAccess());
        }
        if (!WeSingLyricReader.enabled(this)) {
            addPermissionRow(permissionRows, "全民K歌读取", false,
                    v -> openAccessibilitySettings());
        }
        if (!isIgnoringBatteryOptimizations()) {
            addPermissionRow(permissionRows, "后台不受限（电池白名单）", false,
                    v -> requestBatteryWhitelist());
        }
        boolean postNotifications = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        if (!postNotifications) {
            addPermissionRow(permissionRows, "通知显示权限", false,
                    v -> requestPostNotificationPermission());
        }
        addPermissionRow(permissionRows, "应用详情", false, v -> openApplicationDetails(),
                "重新运行引导", true, v -> {
                    Intent guide = new Intent(this, OnboardingActivity.class);
                    guide.putExtra("mode", AppPreferences.musicSourceMode(this));
                    startActivity(guide);
                });
        TextView status = text("通知读取 " + (hasNotificationAccess() ? "已授权" : "未授权")
                + "     监听器 " + listenerState(hasNotificationAccess()),
                12, 0xFF8B90B8, false);
        status.setPadding(0, dp(6), 0, 0);
        permissionRows.addView(status);
    }

    private void showLyricRematchDialog() {
        MusicSnapshot snapshot = MusicStateStore.snapshot(AppPreferences.lyricOffsetMs(this));
        if (!snapshot.active || snapshot.title.trim().isEmpty()) {
            SafeToast.show(this, "当前没有可重新匹配的曲目", Toast.LENGTH_SHORT);
            return;
        }
        String[] labels = {"自动识别", "网易云音乐", "QQ 音乐", "酷狗音乐", "酷我音乐", "汽水音乐"};
        String[] catalogs = {"auto", "netease", "qqmusic", "kugou", "kuwo", "soda"};
        String selected = AppPreferences.lyricCatalog(this, MusicStateStore.activeSourceId());
        final int[] selectedIndex = {0};
        for (int i = 0; i < catalogs.length; i++) {
            if (catalogs[i].equals(selected)) {
                selectedIndex[0] = i;
                break;
            }
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), 0, dp(4), 0);
        TextInputLayout titleLayout = new TextInputLayout(this);
        titleLayout.setHint("用于匹配的歌名");
        TextInputEditText titleInput = new TextInputEditText(titleLayout.getContext());
        titleInput.setSingleLine(true);
        titleInput.setText(snapshot.title);
        titleLayout.addView(titleInput, new LinearLayout.LayoutParams(-1, -2));
        content.addView(titleLayout, new LinearLayout.LayoutParams(-1, -2));
        TextInputLayout artistLayout = new TextInputLayout(this);
        artistLayout.setHint("用于匹配的歌手（可留空）");
        TextInputEditText artistInput = new TextInputEditText(artistLayout.getContext());
        artistInput.setSingleLine(true);
        artistInput.setText(snapshot.artist);
        artistLayout.addView(artistInput, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams artistParams = new LinearLayout.LayoutParams(-1, -2);
        artistParams.topMargin = dp(8);
        content.addView(artistLayout, artistParams);
        TextView catalogLabel = text("匹配词库（汽水 / 网易YRC / QQ / 酷狗多为逐字时间轴）", 13,
                0xFFADABF2, false);
        LinearLayout.LayoutParams catalogLabelParams = new LinearLayout.LayoutParams(-1, -2);
        catalogLabelParams.topMargin = dp(12);
        content.addView(catalogLabel, catalogLabelParams);
        LinearLayout catalogRow = new LinearLayout(this);
        for (int i = 0; i < catalogs.length; i++) {
            final int index = i;
            MaterialButton item = button(labels[i], i == selectedIndex[0]);
            item.setOnClickListener(v -> {
                selectedIndex[0] = index;
                for (int c = 0; c < catalogRow.getChildCount(); c++) {
                    highlightCatalogButton((MaterialButton) catalogRow.getChildAt(c),
                            c == selectedIndex[0]);
                }
            });
            highlightCatalogButton(item, i == selectedIndex[0]);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
            if (i > 0) params.leftMargin = dp(4);
            catalogRow.addView(item, params);
        }
        LinearLayout.LayoutParams catalogParams = new LinearLayout.LayoutParams(-1, -2);
        catalogParams.topMargin = dp(8);
        content.addView(catalogRow, catalogParams);
        new MaterialAlertDialogBuilder(this)
                .setTitle("修正歌曲信息并匹配歌词")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("重新匹配", (dialog, which) -> {
                    String requestedTitle = titleInput.getText() == null ? ""
                            : titleInput.getText().toString().trim();
                    String requestedArtist = artistInput.getText() == null ? ""
                            : artistInput.getText().toString().trim();
                    if (requestedTitle.isEmpty()) requestedTitle = snapshot.title;
                    MusicStateStore.reloadLyrics(this, requestedTitle, requestedArtist,
                            catalogs[selectedIndex[0]]);
                    SafeToast.show(this, "已按修正后的歌曲信息开始匹配",
                            Toast.LENGTH_SHORT);
                })
                .show();
    }

    private void highlightCatalogButton(MaterialButton button, boolean selected) {
        button.setBackgroundTintList(ColorStateList.valueOf(selected
                ? themeColor(com.google.android.material.R.attr.colorPrimary, 0xFFF40861)
                : 0xFF2A2C44));
        button.setTextColor(selected
                ? themeColor(com.google.android.material.R.attr.colorOnPrimary, 0xFF0B0E1C)
                : themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFF1F5FA));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(16));
        MaterialShapeDrawable surface = new MaterialShapeDrawable();
        surface.setFillColor(ColorStateList.valueOf(
                themeColor(com.google.android.material.R.attr.colorSurfaceContainer, 0xFF1C1D2C)));
        surface.setCornerSize(dp(20));
        surface.setElevation(dp(1));
        card.setBackground(surface);
        return card;
    }

    private LinearLayout.LayoutParams cardMargins() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(16);
        return params;
    }

    private TextView sectionLabel(String value) {
        return text(value, 13, themeColor(com.google.android.material.R.attr.colorPrimary,
                0xFFF40861), true);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(adaptiveTextColor(color));
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return view;
    }

    private MaterialButton button(String value, boolean primary) {
        MaterialButton button = new MaterialButton(this);
        button.setText(value);
        button.setTextSize(13f);
        button.setTextColor(primary
                ? themeColor(com.google.android.material.R.attr.colorOnPrimary, 0xFF0B0E1C)
                : themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFF1F5FA));
        button.setAllCaps(false);
        button.setCornerRadius(dp(15));
        button.setBackgroundTintList(ColorStateList.valueOf(
                primary ? themeColor(com.google.android.material.R.attr.colorPrimary, 0xFFF40861)
                        : 0xFF2A2C44));
        return button;
    }

    private void addPermissionRow(LinearLayout parent, String firstLabel, boolean firstPrimary,
                                  View.OnClickListener firstAction) {
        addPermissionRow(parent, firstLabel, firstPrimary, firstAction,
                null, false, null);
    }

    private void addPermissionRow(LinearLayout parent, String firstLabel, boolean firstPrimary,
                                  View.OnClickListener firstAction, String secondLabel,
                                  boolean secondPrimary, View.OnClickListener secondAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton first = button(firstLabel, firstPrimary);
        first.setOnClickListener(firstAction);
        row.addView(first, new LinearLayout.LayoutParams(0, dp(46), 1f));
        if (secondLabel != null) {
            MaterialButton second = button(secondLabel, secondPrimary);
            second.setOnClickListener(secondAction);
            LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
            secondParams.leftMargin = dp(10);
            row.addView(second, secondParams);
        }
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dp(8);
        parent.addView(row, rowParams);
    }

    private void copyText(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        SafeToast.show(this, "已复制", Toast.LENGTH_SHORT);
    }

    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (enabled == null) return false;
        ComponentName expected = new ComponentName(this, MusicNotificationListener.class);
        String[] entries = enabled.split(":");
        for (String entry : entries) {
            if (expected.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    private void openNotificationAccess() {
        if (startPermissionSettingsActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
                PERMISSION_CHECK_NOTIFICATION)) {
            return;
        }
        // Notification access exists on Android 4.4, but its public settings action was only
        // added in API 22. AOSP KitKat exposes this activity; vendor ROMs may not, so keep
        // every fallback resolve-checked.
        Intent kitKatNotificationAccess = new Intent().setComponent(new ComponentName(
                "com.android.settings", "com.android.settings.Settings$NotificationAccessSettingsActivity"));
        if (startPermissionSettingsActivity(kitKatNotificationAccess, PERMISSION_CHECK_NOTIFICATION)) return;
        if (startPermissionSettingsActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS),
                PERMISSION_CHECK_NOTIFICATION)) return;
        if (startPermissionSettingsActivity(new Intent(Settings.ACTION_SETTINGS),
                PERMISSION_CHECK_NOTIFICATION)) return;
        SafeToast.show(this, "无法打开系统的通知读取设置，请在系统设置中手动开启。",
                Toast.LENGTH_LONG);
    }

    private boolean startSettingsActivity(Intent intent) {
        try {
            if (intent.resolveActivity(getPackageManager()) == null) return false;
            startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean startPermissionSettingsActivity(Intent intent, int permissionType) {
        if (!startSettingsActivity(intent)) return false;
        pendingPermissionCheck |= permissionType;
        return true;
    }

    private void promptPermissionIfStillMissing() {
        if (pendingPermissionCheck == 0 || permissionDialogVisible || isFinishing()) return;
        int pending = pendingPermissionCheck;
        pendingPermissionCheck = 0;
        boolean notificationMissing = (pending & PERMISSION_CHECK_NOTIFICATION) != 0
                && !hasNotificationAccess();
        if (!notificationMissing) return;
        permissionDialogVisible = true;
        new MaterialAlertDialogBuilder(this)
                .setTitle("权限仍未生效")
                .setMessage("检测到“音乐读取权限”仍未授权。不同系统可能将开关放在额外的安全、通知或应用管理页面，请在系统设置中找到“通知使用权”并允许本应用。")
                .setPositiveButton("知道了", (dialog, which) -> { })
                .setOnDismissListener(dialog -> permissionDialogVisible = false)
                .show();
    }

    private void ensureNotificationListenerConnected() {
        handler.removeCallbacks(listenerReconnect);
        listenerReconnectScheduled = false;
        if (!hasNotificationAccess()
                || MusicNotificationListener.isHealthy(LISTENER_HEALTH_MAX_AGE_MS)) return;
        listenerReconnectDeadlineElapsedMs = SystemClock.elapsedRealtime()
                + LISTENER_RECONNECT_WINDOW_MS;
        listenerReconnectScheduled = true;
        // Let NotificationManager restore its listener first. Requesting a rebind immediately
        // after returning from Settings can race the platform's natural bind on Android 7+.
        handler.postDelayed(listenerReconnect, LISTENER_INITIAL_RECONNECT_DELAY_MS);
    }

    private String listenerState(boolean notificationAccess) {
        if (notificationAccess
                && MusicNotificationListener.isHealthy(LISTENER_HEALTH_MAX_AGE_MS)) {
            return "已连接";
        }
        if (notificationAccess && listenerReconnectScheduled) return "重连中";
        return "超时";
    }

    private static String backendDescription() {
        String active = MusicNotificationListener.getBackendName();
        if (active != null && !active.trim().isEmpty()) return active;
        return Build.VERSION.SDK_INT >= 21 ? "MediaSession" : "RemoteController";
    }

    private static String formatAge(long atMs) {
        long ageMs = Math.max(0L, System.currentTimeMillis() - atMs);
        if (ageMs < 60_000L) return (ageMs / 1000L) + " 秒";
        if (ageMs < 3_600_000L) return (ageMs / 60_000L) + " 分钟";
        return (ageMs / 3_600_000L) + " 小时";
    }

    private void requestPostNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            SafeToast.show(this, "当前系统无需单独授予通知显示权限", Toast.LENGTH_SHORT);
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            SafeToast.show(this, "通知显示权限已授权", Toast.LENGTH_SHORT);
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < 23) return true;
        android.os.PowerManager power = (android.os.PowerManager)
                getSystemService(POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestBatteryWhitelist() {
        if (Build.VERSION.SDK_INT < 23) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("允许后台运行")
                .setMessage("系统省电策略会冻结后台并断开云端同步。下一步的系统弹窗里选择「允许」。"
                        + "小米/红米手机还需在 设置 → 应用管理 → 微喵歌词同步 → 省电策略 选择「无限制」，"
                        + "并在最近任务中下拉锁定本应用。")
                .setPositiveButton("去设置", (dialog, which) -> {
                    try {
                        startActivity(new Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Throwable error) {
                        startSettingsActivity(new Intent(
                                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openAccessibilitySettings() {
        if (startSettingsActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) return;
        SafeToast.show(this, "无法打开无障碍设置，请手动开启「全民K歌歌词读取」", Toast.LENGTH_LONG);
    }

    private void openApplicationDetails() {
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        if (!startSettingsActivity(details)) {
            startSettingsActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private int themeColor(int attribute, int fallback) {
        android.util.TypedValue value = new android.util.TypedValue();
        return getTheme().resolveAttribute(attribute, value, true) ? value.data : fallback;
    }

    private int adaptiveTextColor(int requested) {
        int night = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) return requested;
        if (requested == 0xFFF40861) {
            return themeColor(com.google.android.material.R.attr.colorPrimary, requested);
        }
        float luminance = (Color.red(requested) * 0.2126f + Color.green(requested) * 0.7152f
                + Color.blue(requested) * 0.0722f) / 255f;
        return luminance > 0.60f
                ? themeColor(com.google.android.material.R.attr.colorOnSurface, requested) : requested;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

}
