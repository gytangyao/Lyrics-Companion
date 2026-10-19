package wemew.lrc.sync;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
import com.google.android.material.materialswitch.MaterialSwitch;
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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView storeNameValue;
    private TextView storeIdValue;
    private TextView deviceIdValue;
    private TextView versionValue;
    private TextView cloudStatus;
    private TextView cloudPush;
    private TextView musicTitle;
    private TextView musicSource;
    private TextView lyricDetail;
    private MaterialButton outputToggle;
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

        // ---- 基本信息（仅显示） ----
        LinearLayout basicCard = card();
        basicCard.addView(sectionLabel("基本信息"));
        storeNameValue = infoRow(basicCard, "门店名称");
        storeIdValue = infoRow(basicCard, "门店Id");
        deviceIdValue = infoRow(basicCard, "设备Id");
        versionValue = infoRow(basicCard, "软件版本");

        // ---- 系统运行状态 ----
        LinearLayout syncCard = card();
        syncCard.addView(sectionLabel("系统运行状态"));
        cloudStatus = text("", 14, 0xFFF40861, true);
        cloudStatus.setPadding(0, dp(8), 0, dp(2));
        syncCard.addView(cloudStatus);
        cloudPush = text("", 12, 0xFF7A7FA5, false);
        cloudPush.setPadding(0, dp(4), 0, 0);
        syncCard.addView(cloudPush);
        musicTitle = text("等待播放器…", 16, 0xFFF3F7FC, true);
        musicTitle.setPadding(0, dp(10), 0, dp(2));
        syncCard.addView(musicTitle);
        musicSource = text("", 13, 0xFFADABF2, false);
        musicSource.setPadding(0, 0, 0, dp(2));
        syncCard.addView(musicSource);
        lyricDetail = text("", 13, 0xFF8B90B8, false);
        lyricDetail.setPadding(0, 0, 0, dp(6));
        syncCard.addView(lyricDetail);

        // ---- 操作 ----
        LinearLayout actionCard = card();
        actionCard.addView(sectionLabel("操作"));
        MaterialSwitch ownCatalogSwitch = new MaterialSwitch(this);
        ownCatalogSwitch.setText("只匹配播放器自家词库");
        ownCatalogSwitch.setTextSize(15);
        ownCatalogSwitch.setTextColor(0xFFF3F7FC);
        ownCatalogSwitch.setChecked(AppPreferences.ownCatalogOnly(this));
        LinearLayout.LayoutParams ownSwitchParams = new LinearLayout.LayoutParams(-1, -2);
        ownSwitchParams.topMargin = dp(6);
        actionCard.addView(ownCatalogSwitch, ownSwitchParams);
        TextView ownSwitchHint = text("开启后：播放器有自家词库时只查它，未命中不再尝试其他词库；"
                + "关闭则按词库回退链匹配", 12, 0xFF7A7FA5, false);
        LinearLayout.LayoutParams ownHintParams = new LinearLayout.LayoutParams(-1, -2);
        ownHintParams.topMargin = dp(2);
        actionCard.addView(ownSwitchHint, ownHintParams);
        ownCatalogSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            AppPreferences.setOwnCatalogOnly(this, checked);
            MusicStateStore.reloadLyrics(this);
            SafeToast.show(this, checked
                    ? "已改为只匹配自家词库，正在重新匹配" : "已恢复词库回退链匹配，正在重新匹配",
                    Toast.LENGTH_SHORT);
        });
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
        actionCard.addView(outputToggle, toggleParams);
        MaterialButton rematch = button("修正歌曲信息并重新匹配", false);
        rematch.setOnClickListener(v -> showLyricRematchDialog());
        LinearLayout.LayoutParams rematchParams = new LinearLayout.LayoutParams(-1, dp(46));
        rematchParams.topMargin = dp(8);
        actionCard.addView(rematch, rematchParams);

        // ---- 系统维护 ----
        LinearLayout maintenanceCard = card();
        maintenanceCard.addView(sectionLabel("系统维护"));
        MaterialButton guide = button("重新引导", false);
        guide.setOnClickListener(v -> {
            Intent intent = new Intent(this, OnboardingActivity.class);
            intent.putExtra("mode", AppPreferences.musicSourceMode(this));
            startActivity(intent);
        });
        maintenanceCard.addView(guide, new LinearLayout.LayoutParams(-1, dp(46)));
        MaterialButton update = button("检测更新", false);
        update.setOnClickListener(v ->
                SafeToast.show(this, "检测更新即将上线", Toast.LENGTH_SHORT));
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(-1, dp(46));
        updateParams.topMargin = dp(8);
        maintenanceCard.addView(update, updateParams);
        MaterialButton logs = button("系统日志", false);
        logs.setOnClickListener(v ->
                SafeToast.show(this, "系统日志即将上线", Toast.LENGTH_SHORT));
        LinearLayout.LayoutParams logsParams = new LinearLayout.LayoutParams(-1, dp(46));
        logsParams.topMargin = dp(8);
        maintenanceCard.addView(logs, logsParams);

        pageHost.addView(basicCard, cardMargins());
        pageHost.addView(syncCard, cardMargins());
        pageHost.addView(actionCard, cardMargins());
        pageHost.addView(maintenanceCard, cardMargins());
        return shell;
    }

    /** A label:value display row inside the basic-info card. */
    private TextView infoRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelView = text(label, 13, 0xFF7A7FA5, false);
        labelView.setMinWidth(dp(72));
        row.addView(labelView, new LinearLayout.LayoutParams(-2, -2));
        TextView value = text("", 15, 0xFFF3F7FC, true);
        value.setPadding(dp(8), 0, 0, 0);
        row.addView(value, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        parent.addView(row, params);
        return value;
    }

    private void refreshStatus() {
        refreshBasicInfo();
        refreshSync();
        refreshLyric();
    }

    private void refreshBasicInfo() {
        String barId = AppPreferences.boundBarId(this);
        String barName = AppPreferences.boundBarName(this);
        boolean bound = !barId.isEmpty();
        storeNameValue.setText(bound ? barName : "未绑定");
        storeIdValue.setText(bound ? barId : "—");
        deviceIdValue.setText(StoreBindingClient.deviceId(this));
        versionValue.setText(appVersion());
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "unknown";
        }
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
                + "\n监听器：" + listenerState);
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
                : "\n逐字时间轴 " + wordTimed + "/" + total + " 行"
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
        // Three rows of two: MIUI dialogs are ~300dp of content width, so two columns
        // are the most the five-character catalog labels fit without clipping.
        LinearLayout catalogGrid = new LinearLayout(this);
        catalogGrid.setOrientation(LinearLayout.VERTICAL);
        for (int row = 0; row < 3; row++) {
            LinearLayout catalogRow = new LinearLayout(this);
            catalogRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 2; col++) {
                final int index = row * 2 + col;
                MaterialButton item = button(labels[index], index == selectedIndex[0]);
                item.setMinWidth(dp(120));
                item.setMinHeight(dp(44));
                item.setOnClickListener(v -> {
                    selectedIndex[0] = index;
                    for (int r = 0; r < catalogGrid.getChildCount(); r++) {
                        LinearLayout gridRow = (LinearLayout) catalogGrid.getChildAt(r);
                        for (int c = 0; c < gridRow.getChildCount(); c++) {
                            highlightCatalogButton((MaterialButton) gridRow.getChildAt(c),
                                    r * 2 + c == selectedIndex[0]);
                        }
                    }
                });
                highlightCatalogButton(item, index == selectedIndex[0]);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1f);
                if (col > 0) params.leftMargin = dp(8);
                catalogRow.addView(item, params);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            if (row > 0) rowParams.topMargin = dp(8);
            catalogGrid.addView(catalogRow, rowParams);
        }
        LinearLayout.LayoutParams catalogParams = new LinearLayout.LayoutParams(-1, -2);
        catalogParams.topMargin = dp(8);
        content.addView(catalogGrid, catalogParams);
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


    private boolean startSettingsActivity(Intent intent) {
        try {
            if (intent.resolveActivity(getPackageManager()) == null) return false;
            startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
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
