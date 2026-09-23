package com.zuoqirun.lyricscompanion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetTextI18n")
public final class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CUSTOM_FONT = 2417;
    private static final int REQUEST_LOCAL_LYRIC_DIRECTORY = 2419;
    private static final int REQUEST_LOCAL_LYRIC_STORAGE = 2421;
    private static final String STATE_SELECTED_SECTION = "selected_section";
    private static final String[] SECTION_LABELS = {"总览", "显示", "歌词", "高级"};
    private static final String[] SECTION_TITLES = {"设置总览", "显示与外观", "歌词来源与校准", "高级与维护"};
    private static final String[] SECTION_DESCRIPTIONS = {
            "先完成必要权限，打开悬浮歌词，再通过实时预览确认效果",
            "查找尺寸、位置与主题设置",
            "管理词库优先级、本地歌词和匹配修正",
            "管理启动与交互和数据"
    };
    private static final ExecutorService SHARED_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final long LISTENER_HEALTH_MAX_AGE_MS = 3_000L;
    private static final long LISTENER_INITIAL_RECONNECT_DELAY_MS = 2_500L;
    private static final long LISTENER_RECONNECT_INTERVAL_MS = 1_000L;
    private static final long LISTENER_RECONNECT_WINDOW_MS = 30_000L;
    private static final int PERMISSION_CHECK_NOTIFICATION = 1;
    private static final int PERMISSION_CHECK_OVERLAY = 1 << 1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView permissionStatus;
    private TextView musicStatus;
    private MaterialSwitch mainOverlaySwitch;
    private MaterialSwitch touchThroughSwitch;
    private MaterialSwitch launchOverlaySwitch;
    private MaterialSwitch autoStartSwitch;
    private LyricsPanelView previewPanel;
    private TextView globalFontSummary;
    private MaterialButton baseColorButton;
    private MaterialButton currentColorButton;
    private TextView sectionHeading;
    private TextView sectionDescription;
    private ScrollView mainScroll;
    private final List<View> sectionPages = new ArrayList<>();
    private final List<MaterialButton> sectionButtons = new ArrayList<>();
    private int selectedSection;
    private boolean bindingUi;
    private boolean activityResumed;
    private boolean stoppingAndExiting;
    private int pendingPermissionFaqCheck;
    private boolean permissionFaqDialogVisible;
    private boolean listenerReconnectScheduled;
    private long listenerReconnectDeadlineElapsedMs;
    private boolean launcherDispatch;

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
        // The companion's dense control surface is intentionally a stable dark workspace.
        // Overlay lyrics can still use the separately selected light/dark environment.
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            selectedSection = savedInstanceState.getInt(STATE_SELECTED_SECTION, 0);
        }
        boolean launcherIntent = isLauncherIntent();
        if (launcherIntent) {
            AppPreferences.get(this).edit().remove("launch_overlay_target").apply();
            AppPreferences.setServiceStoppedByUser(this, false);
        }
        if (launcherIntent && dispatchLauncherOverlay()) {
            launcherDispatch = true;
            finish();
            return;
        }
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFF07111F);
            getWindow().setNavigationBarColor(0xFF07111F);
        }
        setContentView(buildContent());
        CustomFontStore.applyToViewTree(this, getWindow().getDecorView());
        MusicStateStore.initialize(this);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_SELECTED_SECTION, selectedSection);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onResume() {
        super.onResume();
        if (launcherDispatch) return;
        activityResumed = true;
        ensureNotificationListenerConnected();
        bindPreferences();
        refreshPreview();
        LyricsDisplayService.startOrRefresh(this);
        LyricsDisplayService.setSettingsVisible(this, true);
        handler.removeCallbacks(statusRefresh);
        handler.post(statusRefresh);
        // Some ROMs update the permission state a moment after their Settings page closes.
        // Check after that hand-off so a newly granted switch never produces a false warning.
        handler.postDelayed(this::promptPermissionFaqIfStillMissing, 350L);
    }

    @Override protected void onPause() {
        if (launcherDispatch) {
            super.onPause();
            return;
        }
        activityResumed = false;
        listenerReconnectScheduled = false;
        handler.removeCallbacks(listenerReconnect);
        handler.removeCallbacks(statusRefresh);
        if (!stoppingAndExiting) LyricsDisplayService.setSettingsVisible(this, false);
        super.onPause();
    }

    private boolean isLauncherIntent() {
        Intent intent = getIntent();
        return intent != null && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_LAUNCHER);
    }

    private boolean dispatchLauncherOverlay() {
        if (!AppPreferences.launchOverlayOnIcon(this)) return false;
        long now = SystemClock.elapsedRealtime();
        android.content.SharedPreferences preferences = AppPreferences.get(this);
        long last = preferences.getLong(AppPreferences.KEY_LAUNCH_OVERLAY_LAST_AT, 0L);
        if (last > 0L && now >= last && now - last <= 30_000L) {
            preferences.edit().remove(AppPreferences.KEY_LAUNCH_OVERLAY_LAST_AT).apply();
            return false;
        }
        if (!LyricsDisplayService.startRememberedFromLauncher(this)) return false;
        preferences.edit().putLong(AppPreferences.KEY_LAUNCH_OVERLAY_LAST_AT, now).apply();
        return true;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_LOCAL_LYRIC_DIRECTORY) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) { }
            AppPreferences.get(this).edit().putString(
                    AppPreferences.KEY_LOCAL_LYRIC_DIRECTORY_URI, uri.toString()).apply();
            MusicStateStore.reloadLyrics(this);
            SafeToast.show(this, "已授权本地歌词目录", Toast.LENGTH_SHORT);
            return;
        }
        if (requestCode != REQUEST_CUSTOM_FONT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            String name = CustomFontStore.importFont(this, uri);
            AppPreferences.changed(this);
            SafeToast.show(this, "已全局应用字体：" + name, Toast.LENGTH_SHORT);
            recreate();
        } catch (Exception error) {
            SafeToast.show(this, error.getMessage() == null ? "导入字体失败" : error.getMessage(),
                    Toast.LENGTH_LONG);
        }
    }

    private View buildContent() {
        sectionPages.clear();
        sectionButtons.clear();
        View shell = getLayoutInflater().inflate(R.layout.activity_main, null, false);
        LinearLayout root = shell.findViewById(R.id.main_content);
        LinearLayout pageHost = shell.findViewById(R.id.main_page_host);
        mainScroll = shell.findViewById(R.id.main_scroll);
        MaterialToolbar toolbar = shell.findViewById(R.id.main_toolbar);
        toolbar.setTitle("歌词伴侣");
        toolbar.setSubtitle("主屏悬浮歌词");
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setSubtitleTextColor(0xFFA9B6C8);
        sectionHeading = shell.findViewById(R.id.main_section_heading);
        sectionDescription = shell.findViewById(R.id.main_section_description);
        sectionDescription.setLineSpacing(0f, 1.18f);

        LinearLayout previewCard = card();
        TextView previewLabel = sectionLabel("实时预览");
        previewCard.addView(previewLabel);
        previewPanel = new LyricsPanelView(this, false);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1,
                previewHeightPx());
        previewLp.topMargin = dp(10);
        previewCard.addView(previewPanel, previewLp);

        LinearLayout accessCard = card();
        accessCard.addView(sectionLabel("使用权限"));
        permissionStatus = text("", 14, 0xFFD8E1EE, false);
        permissionStatus.setPadding(0, dp(8), 0, dp(12));
        accessCard.addView(permissionStatus);
        addPermissionRow(accessCard, "音乐读取权限", true, v -> openNotificationAccess(),
                "悬浮窗权限", false, v -> openOverlayPermission());
        addPermissionRow(accessCard, "使用情况访问", false, v -> openUsageAccessSettings(),
                "通知显示权限", false, v -> requestPostNotificationPermission());
        addPermissionRow(accessCard, "应用权限 / 自启动", false, v -> openApplicationDetails(),
                null, false, null);

        LinearLayout lyricCard = card();
        lyricCard.addView(sectionLabel("歌词匹配"));
        MaterialSwitch playerCatalogFallback = toggle("回退到播放器同源词库",
                "手动选择的词库无结果时，再尝试从应用名称识别出的播放器词库");
        addLyricCatalogSelector(lyricCard, playerCatalogFallback);
        playerCatalogFallback.setChecked(AppPreferences.playerCatalogFallback(this));
        playerCatalogFallback.setOnCheckedChangeListener((button, checked) -> {
            AppPreferences.get(this).edit()
                    .putBoolean(AppPreferences.KEY_PLAYER_CATALOG_FALLBACK, checked).apply();
            MusicStateStore.reloadLyrics(this);
        });
        lyricCard.addView(playerCatalogFallback);
        MaterialSwitch localLyrics = toggle("优先匹配本地歌词（.lrc / 内嵌标签）",
                "先在音频文件旁查找同名 .lrc；没有时直接读取文件内嵌歌词（FLAC / MP3 / M4A / OGG），"
                        + "最后才在已授权的音乐目录里按文件名、歌名或“歌手 - 歌名”搜索");
        localLyrics.setChecked(AppPreferences.localLyricEnabled(this));
        localLyrics.setOnCheckedChangeListener((button, checked) -> {
            AppPreferences.get(this).edit()
                    .putBoolean(AppPreferences.KEY_LOCAL_LYRIC_ENABLED, checked).apply();
            MusicStateStore.reloadLyrics(this);
        });
        lyricCard.addView(localLyrics);
        MaterialButton localLyricDirectory = button("选择本地音乐目录", false);
        localLyricDirectory.setOnClickListener(v -> openLocalLyricDirectoryPicker());
        LinearLayout.LayoutParams localDirectoryParams = new LinearLayout.LayoutParams(-1, dp(48));
        localDirectoryParams.topMargin = dp(8);
        lyricCard.addView(localLyricDirectory, localDirectoryParams);
        MaterialButton localLyricPath = button("手动填写歌词目录路径", false);
        localLyricPath.setOnClickListener(v -> editLocalLyricDirectoryPath());
        lyricCard.addView(localLyricPath, new LinearLayout.LayoutParams(-1, dp(48)));
        // issue #44：匹配到的歌词长时间不滚动时，改用播放器实时歌词。
        MaterialSwitch stuckFallback = toggle("匹配歌词长时间不滚动时改用播放器实时歌词",
                "偶尔会匹配到一条一直停在第一句的时间轴：当播放进度在推进、当前行超过 25 秒"
                        + "没有推进，且播放器的实时歌词一直在更新时，改用实时歌词；实时歌词本身不动时"
                        + "仍保留原歌词。默认开启");
        stuckFallback.setChecked(AppPreferences.stuckLyricFallback(this));
        stuckFallback.setOnCheckedChangeListener((button, checked) -> {
            AppPreferences.setStuckLyricFallback(this, checked);
            MusicStateStore.reloadLyrics(this);
        });
        lyricCard.addView(stuckFallback);
        MaterialSwitch compositeIdentity = toggle("从歌手栏综合识别歌名",
                "部分手机音乐 App 把实时歌词放进「歌名」栏、把「歌名 - 歌手」放进「歌手」栏，"
                        + "导致按歌名搜词库必然失败。开启后先从歌手栏解析歌名再匹配，歌名栏原文"
                        + "则作为实时歌词显示；解析不出时保持原样");
        compositeIdentity.setChecked(AppPreferences.compositeIdentityFromArtist(this));
        compositeIdentity.setOnCheckedChangeListener((button, checked) -> {
            AppPreferences.get(this).edit()
                    .putBoolean(AppPreferences.KEY_COMPOSITE_IDENTITY_FROM_ARTIST, checked).apply();
        });
        lyricCard.addView(compositeIdentity);

        LinearLayout outputCard = card();
        outputCard.addView(sectionLabel("歌词显示开关"));
        mainOverlaySwitch = toggle("主屏悬浮窗",
                "离开设置页后显示；可拖动，双击强制返回，长按弹出锁定位置与触摸穿透的菜单");
        mainOverlaySwitch.setOnCheckedChangeListener((button, checked) -> {
            if (bindingUi) return;
            AppPreferences.get(this).edit().putBoolean(AppPreferences.KEY_MAIN_OVERLAY, checked).apply();
            if (checked && !canDrawOverlays()) showPermissionHomeHint("悬浮窗");
            AppPreferences.changed(this);
            LyricsDisplayService.setSettingsVisible(this, true);
        });
        outputCard.addView(mainOverlaySwitch);
        touchThroughSwitch = toggle("触摸穿透",
                "歌词不再接收任何触摸，点击全部落到下面的应用；开启后只能回到这里关闭");
        touchThroughSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (bindingUi) return;
            AppPreferences.putOverlayTouchThrough(this, false, checked);
            AppPreferences.changed(this);
            LyricsDisplayService.setSettingsVisible(this, true);
        });
        outputCard.addView(touchThroughSwitch);

        LinearLayout startupCard = card();
        startupCard.addView(sectionLabel("启动与交互"));
        launchOverlaySwitch = toggle("点击图标启动悬浮窗",
                "开启后首次点击图标按已记忆的主屏和副屏恢复显示；30 秒内再次点击进入主界面");
        launchOverlaySwitch.setChecked(AppPreferences.launchOverlayOnIcon(this));
        launchOverlaySwitch.setOnCheckedChangeListener((button, checked) -> {
            if (bindingUi) return;
            AppPreferences.get(this).edit()
                    .putBoolean(AppPreferences.KEY_LAUNCH_OVERLAY_ON_ICON, checked)
                    .remove(AppPreferences.KEY_LAUNCH_OVERLAY_LAST_AT)
                    .apply();
        });
        startupCard.addView(launchOverlaySwitch);
        autoStartSwitch = toggle("开机 / 亮屏自启动悬浮窗",
                "在重启或每次亮屏时恢复已记忆的主屏和副屏歌词。关闭服务并退出不会改变此项。");
        autoStartSwitch.setChecked(AppPreferences.autoStartOverlays(this));
        autoStartSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (bindingUi) return;
            AppPreferences.get(this).edit()
                    .putBoolean(AppPreferences.KEY_AUTO_START_OVERLAYS, checked).apply();
            if (checked) {
                boolean addedDefaultTarget = AppPreferences.ensureAutoStartOverlayTarget(this);
                AppPreferences.setServiceStoppedByUser(this, false);
                if (addedDefaultTarget && mainOverlaySwitch != null) {
                    mainOverlaySwitch.setChecked(true);
                }
            }
            LyricsDisplayService.startOrRefresh(this);
        });
        startupCard.addView(autoStartSwitch);
        MaterialSwitch returnToPlayer = toggle("轻触悬浮窗返回播放器",
                "关闭时打开歌词伴侣；无法打开播放器时会自动回到歌词伴侣");
        returnToPlayer.setChecked(AppPreferences.tapOverlayReturnsToPlayer(this));
        returnToPlayer.setOnCheckedChangeListener((button, checked) -> AppPreferences.get(this)
                .edit().putBoolean(AppPreferences.KEY_TAP_OVERLAY_RETURNS_TO_PLAYER, checked)
                .apply());
        startupCard.addView(returnToPlayer);

        MaterialButton stopService = button("关闭服务并退出", false);
        stopService.setOnClickListener(v -> confirmStopServiceAndExit());
        LinearLayout.LayoutParams stopServiceParams = new LinearLayout.LayoutParams(-1, dp(48));
        stopServiceParams.topMargin = dp(12);
        startupCard.addView(stopService, stopServiceParams);

        LinearLayout appearanceCard = card();
        appearanceCard.addView(sectionLabel("字体"));
        addGlobalFontControls(appearanceCard);
        addFloatingColorControls(appearanceCard);

        LinearLayout resetCard = card();
        resetCard.addView(sectionLabel("数据与重置"));
        TextView resetSummary = text(
                "恢复显示、歌词、启动、本地目录和字体等默认设置。",
                12, 0xFF8392A8, false);
        resetSummary.setPadding(0, dp(9), 0, dp(10));
        resetCard.addView(resetSummary);
        MaterialButton resetSettings = button("恢复默认设置", false);
        resetSettings.setOnClickListener(v -> confirmResetSettings());
        resetCard.addView(resetSettings, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout stateCard = card();
        stateCard.addView(sectionLabel("音乐状态"));
        musicStatus = text("等待播放器…", 14, 0xFFD8E1EE, false);
        musicStatus.setLineSpacing(0f, 1.2f);
        musicStatus.setPadding(0, dp(9), 0, 0);
        stateCard.addView(musicStatus);
        MaterialButton rematchLyrics = button("修正歌曲信息并重新匹配", false);
        rematchLyrics.setOnClickListener(v -> showLyricRematchDialog());
        LinearLayout.LayoutParams rematchParams = new LinearLayout.LayoutParams(-1, dp(48));
        rematchParams.topMargin = dp(12);
        stateCard.addView(rematchLyrics, rematchParams);

        LinearLayout uiScaleCard = card();
        uiScaleCard.addView(sectionLabel("设置界面"));
        addSettingsUiScaleSelector(uiScaleCard);

        LinearLayout homePage = sectionPage();
        homePage.addView(uiScaleCard, cardMargins());
        homePage.addView(accessCard, cardMargins());
        homePage.addView(outputCard, cardMargins());
        homePage.addView(previewCard, cardMargins());

        LinearLayout displayPage = sectionPage();
        displayPage.addView(appearanceCard, cardMargins());

        LinearLayout lyricsPage = sectionPage();
        lyricsPage.addView(lyricCard, cardMargins());
        lyricsPage.addView(stateCard, cardMargins());

        LinearLayout systemPage = sectionPage();
        systemPage.addView(startupCard, cardMargins());
        systemPage.addView(resetCard, cardMargins());

        TextView footnote = text("提示：在线、本地和 U 盘播放器优先读取系统媒体信息，缺失时尝试识别音乐通知；通知未提供进度时无法精准自动滚动。匹配歌词优先复用本地缓存；文件名会自动清理路径、序号、扩展名和音质标记，仍不准确时可用“修正歌曲信息并重新匹配”。歌词伴侣不会向 iPhone CarPlay 仪表盘注入媒体信息。", 12,
                0xFF66788F, false);
        footnote.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams footnoteParams = new LinearLayout.LayoutParams(-1, -2);
        footnoteParams.topMargin = dp(16);
        systemPage.addView(footnote, footnoteParams);

        sectionPages.add(homePage);
        sectionPages.add(displayPage);
        sectionPages.add(lyricsPage);
        sectionPages.add(systemPage);
        for (View page : sectionPages) {
            pageHost.addView(page, new LinearLayout.LayoutParams(-1, -2));
        }

        bindSectionNavigation(shell);
        selectSection(Math.max(0, Math.min(selectedSection, sectionPages.size() - 1)));
        return shell;
    }

    private LinearLayout sectionPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        return page;
    }

    private void bindSectionNavigation(View shell) {
        int[] ids = {R.id.nav_home, R.id.nav_display, R.id.nav_lyrics, R.id.nav_system};
        for (int index = 0; index < ids.length; index++) {
            final int section = index;
            MaterialButton item = shell.findViewById(ids[index]);
            item.setText(navigationLabel(index));
            item.setTextSize(12f);
            item.setMinWidth(0);
            item.setMinimumWidth(0);
            item.setMinHeight(0);
            item.setMinimumHeight(0);
            item.setCornerRadius(dp(16));
            item.setContentDescription("打开" + navigationLabel(index) + "分类");
            item.setOnClickListener(v -> selectSection(section));
            sectionButtons.add(item);
        }
    }

    private void selectSection(int section) {
        if (section < 0 || section >= sectionPages.size()) return;
        boolean sectionChanged = selectedSection != section;
        selectedSection = section;
        for (int index = 0; index < sectionPages.size(); index++) {
            sectionPages.get(index).setVisibility(index == section ? View.VISIBLE : View.GONE);
        }
        for (int index = 0; index < sectionButtons.size(); index++) {
            boolean selected = index == section;
            MaterialButton item = sectionButtons.get(index);
            item.setSelected(selected);
            item.setTextColor(selected ? 0xFF07111F : 0xFFA9B6C8);
            item.setBackgroundTintList(ColorStateList.valueOf(
                    selected ? 0xFF6EE7F2 : Color.TRANSPARENT));
        }
        if (sectionHeading != null) sectionHeading.setText(sectionTitle(section));
        if (sectionDescription != null) {
            sectionDescription.setText(sectionDescription(section));
        }
        if (sectionChanged && mainScroll != null) {
            mainScroll.post(() -> mainScroll.scrollTo(0, 0));
        }
    }

    private String sectionTitle(int section) {
        return SECTION_TITLES[section];
    }

    private String navigationLabel(int section) {
        return SECTION_LABELS[section];
    }

    private String sectionDescription(int section) {
        return SECTION_DESCRIPTIONS[section];
    }

    private void confirmStopServiceAndExit() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("关闭歌词服务")
                .setMessage("将移除所有悬浮歌词并停止音乐监听。若保留开机/亮屏自启动，会留下最小启动待命服务以接收亮屏事件；关闭该选项才会完全停止所有服务。")
                .setNegativeButton("取消", null)
                .setPositiveButton("关闭并退出", (dialog, which) -> stopServiceAndExit())
                .show();
    }

    private void stopServiceAndExit() {
        stoppingAndExiting = true;
        LyricsDisplayService.stopAndRememberOverlays(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask();
        else finish();
    }

    private void addLyricCatalogSelector(LinearLayout parent,
                                         MaterialSwitch playerCatalogFallback) {
        TextView label = text("默认匹配词库", 14, 0xFFD7E1EE, true);
        label.setPadding(0, dp(14), 0, dp(6));
        parent.addView(label);
        String[] labels = {"自动识别播放器", "网易云音乐", "QQ 音乐", "酷狗音乐", "酷我音乐", "汽水音乐"};
        String[] values = {"auto", "netease", "qqmusic", "kugou", "kuwo", "soda"};
        Spinner spinner = new Spinner(this, Spinner.MODE_DIALOG);
        spinner.setPopupBackgroundDrawable(solid(0xFF132238, 14));
        spinner.setAdapter(new ThemedSpinnerAdapter<>(this, labels));
        String saved = AppPreferences.lyricCatalog(this);
        int selection = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(saved)) selection = i;
        spinner.setSelection(selection, false);
        updatePlayerCatalogFallbackEnabled(playerCatalogFallback, selection != 0);
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parentView,
                                                 View view, int position, long id) {
                updatePlayerCatalogFallbackEnabled(playerCatalogFallback, position != 0);
                if (values[position].equals(AppPreferences.lyricCatalog(MainActivity.this))) return;
                AppPreferences.get(MainActivity.this).edit()
                        .putString(AppPreferences.KEY_LYRIC_CATALOG, values[position]).apply();
                MusicStateStore.reloadLyrics(MainActivity.this);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parentView) { }
        });
        parent.addView(spinner, new LinearLayout.LayoutParams(-1, dp(52)));
        TextView help = text("这是未单独设置播放器时的默认规则。自动模式优先使用识别出的播放器同源词库；手动模式始终先尝试所选词库。当前词库无结果后才依次查询下一词库。",
                12, 0xFF74869D, false);
        help.setPadding(0, dp(5), 0, 0);
        parent.addView(help);
        MaterialButton rules = button("按词库强制匹配应用", false);
        rules.setOnClickListener(v -> showPlayerLyricCatalogRulesDialog());
        LinearLayout.LayoutParams rulesParams = new LinearLayout.LayoutParams(-1, dp(46));
        rulesParams.topMargin = dp(8);
        parent.addView(rules, rulesParams);
    }

    private static void updatePlayerCatalogFallbackEnabled(MaterialSwitch view,
                                                            boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.55f);
    }

    private void showPlayerLyricCatalogRulesDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), 0, dp(4), 0);
        TextView note = text("选择一个词库后，可从所有已安装应用中多选。被选中的应用将只从该词库匹配歌词；同一应用只能归属一个强制词库。未选择的应用继续使用上方默认规则。",
                13, 0xFF74869D, false);
        note.setLineSpacing(0f, 1.2f);
        content.addView(note);
        String[] labels = {"网易云音乐", "QQ 音乐", "酷狗音乐", "酷我音乐", "汽水音乐"};
        String[] catalogs = {"netease", "qqmusic", "kugou", "kuwo", "soda"};
        for (int i = 0; i < catalogs.length; i++) {
            final String catalog = catalogs[i];
            final String catalogLabel = labels[i];
            MaterialButton chooseApps = button(catalogLabel + " · 选择应用", false);
            chooseApps.setOnClickListener(v -> showCatalogAppPicker(catalog, catalogLabel));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
            params.topMargin = dp(10);
            content.addView(chooseApps, params);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new MaterialAlertDialogBuilder(this)
                .setTitle("按词库强制匹配应用")
                .setView(scroll)
                .setPositiveButton("完成", null)
                .show();
    }

    private void showCatalogAppPicker(String catalog, String catalogLabel) {
        LinearLayout loading = new LinearLayout(this);
        loading.setPadding(dp(24), dp(16), dp(24), dp(16));
        loading.addView(text("正在读取已安装应用…", 14, 0xFFD8E1EE, false));
        AlertDialog loadingDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(catalogLabel + "词库")
                .setView(loading)
                .setNegativeButton("取消", null)
                .show();
        SHARED_EXECUTOR.execute(() -> {
            List<InstalledAppListCache.AppChoice> apps = InstalledAppListCache.load(this,
                    AppPreferences.observedPlayerPackages(this));
            handler.post(() -> {
                if (loadingDialog.isShowing()) loadingDialog.dismiss();
                if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                showLoadedCatalogAppPicker(catalog, catalogLabel, apps);
            });
        });
    }

    private void showLoadedCatalogAppPicker(String catalog, String catalogLabel,
                                            List<InstalledAppListCache.AppChoice> apps) {
        Set<String> selected = new LinkedHashSet<>();
        for (InstalledAppListCache.AppChoice app : apps) {
            if (catalog.equals(AppPreferences.playerPackageLyricCatalogOverride(this,
                    app.packageName))) selected.add(app.packageName);
        }
        ListView list = new ListView(this);
        list.setDividerHeight(0);
        list.setBackgroundColor(0xFF101E31);
        AppChoiceListAdapter adapter = new AppChoiceListAdapter(this, apps, selected);
        list.setAdapter(adapter);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), 0, dp(4), 0);
        content.addView(appSearchField(adapter));
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(440)));
        new MaterialAlertDialogBuilder(this)
                .setTitle(catalogLabel + "词库 · 强制匹配")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    for (InstalledAppListCache.AppChoice app : apps) {
                        String current = AppPreferences.playerPackageLyricCatalogOverride(this,
                                app.packageName);
                        if (selected.contains(app.packageName)) {
                            AppPreferences.putPlayerPackageLyricCatalog(this, app.packageName,
                                    catalog);
                        } else if (catalog.equals(current)) {
                            AppPreferences.putPlayerPackageLyricCatalog(this, app.packageName, "");
                        }
                    }
                    AppPreferences.changed(this);
                    MusicStateStore.reloadLyrics(this);
                    refreshPreview();
                    SafeToast.show(this, "已保存 " + catalogLabel + " 强制匹配应用", Toast.LENGTH_SHORT);
                })
                .show();
    }

    private TextInputLayout appSearchField(AppChoiceListAdapter adapter) {
        TextInputLayout input = new TextInputLayout(this);
        input.setHint("搜索应用名称或包名");
        input.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        input.setBoxBackgroundColor(0xFF17263A);
        input.setBoxStrokeColor(0xFF6EE7F2);
        input.setHintTextColor(ColorStateList.valueOf(0xFFA9B6C8));
        TextInputEditText editor = new TextInputEditText(this);
        editor.setSingleLine(true);
        editor.setInputType(InputType.TYPE_CLASS_TEXT);
        editor.setTextColor(0xFFF3F7FC);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count,
                                                    int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before,
                                                int count) {
                adapter.setQuery(text == null ? "" : text.toString());
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        input.addView(editor, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(6), dp(4), dp(6), dp(8));
        input.setLayoutParams(params);
        return input;
    }

    private void bindPreferences() {
        bindingUi = true;
        mainOverlaySwitch.setChecked(AppPreferences.mainEnabled(this));
        touchThroughSwitch.setChecked(AppPreferences.overlayTouchThrough(this, false));
        launchOverlaySwitch.setChecked(AppPreferences.launchOverlayOnIcon(this));
        autoStartSwitch.setChecked(AppPreferences.autoStartOverlays(this));
        bindingUi = false;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                      int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            LyricsDisplayService.startOrRefresh(this);
            refreshPreview();
            refreshStatus();
        } else if (requestCode == REQUEST_LOCAL_LYRIC_STORAGE) {
            SafeToast.show(this, LocalLyricClient.canReadManualDirectory(this)
                    ? "已授予存储读取权限，正在重新读取本地歌词"
                    : LocalLyricClient.manualDirectorySaveMessage(this), Toast.LENGTH_LONG);
            MusicStateStore.reloadLyrics(this);
        }
    }

    private void refreshStatus() {
        boolean notificationAccess = hasNotificationAccess();
        boolean overlay = canDrawOverlays();
        boolean postNotifications = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        boolean usageAccess = ForegroundAppDetector.hasUsageAccess(this);
        String listenerState = listenerState(notificationAccess);
        permissionStatus.setText("通知读取  " + (notificationAccess ? "已授权" : "未授权")
                + "     监听器  " + listenerState
                + "     悬浮窗  " + permissionState(overlay)
                + "\n通知显示  " + permissionState(postNotifications)
                + "     使用情况  " + permissionState(usageAccess));
        permissionStatus.setTextColor(notificationAccess && overlay
                ? 0xFF6EE7F2 : 0xFFFFCA66);
        long lastRead = MusicNotificationListener.getLastSuccessfulSessionReadElapsedMs();
        String error = MusicNotificationListener.getLastSessionError();
        if (error == null || error.trim().isEmpty()) error = "无";
        else error = error.replace('\n', ' ').replace('\r', ' ').trim();
        if (error.length() > 160) error = error.substring(0, 160) + "…";
        musicStatus.setText(MusicStateStore.describe(this)
                + "\n通知读取：" + (notificationAccess ? "已授权" : "未授权")
                + "    监听器：" + listenerState
                + "    读取方式：" + backendDescription()
                + "\n最近成功读取会话：" + formatSessionReadAge(lastRead)
                + "    当前会话数量：" + MusicNotificationListener.getLastSessionCount()
                + "\n最近异常信息：" + error);
    }

    /** Enlarges controls on low-density automotive screens without affecting lyric typography. */
    private void addSettingsUiScaleSelector(LinearLayout parent) {
        Spinner spinner = new Spinner(this, Spinner.MODE_DIALOG);
        String[] labels = {"标准（100%）", "大号（150%）", "特大（200%）"};
        int[] values = {100, 150, 200};
        spinner.setPopupBackgroundDrawable(solid(0xFF132238, 14));
        spinner.setAdapter(new ThemedSpinnerAdapter<>(this, labels));
        int current = AppPreferences.settingsUiScale(this);
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                spinner.setSelection(i, false);
                break;
            }
        }
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parentView,
                                                  View view, int position, long id) {
                if (values[position] == AppPreferences.settingsUiScale(MainActivity.this)) return;
                AppPreferences.setSettingsUiScale(MainActivity.this, values[position]);
                recreate();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parentView) { }
        });
        parent.addView(spinner, new LinearLayout.LayoutParams(-1, dp(52)));
        TextView note = text("仅放大设置页面的文字与控件，不改变悬浮歌词字号。", 12,
                0xFF74869D, false);
        note.setPadding(0, dp(3), 0, dp(2));
        parent.addView(note);
    }

    private MaterialSwitch toggle(String title, String subtitle) {
        MaterialSwitch view = new MaterialSwitch(this);
        view.setText(title + "\n" + subtitle);
        view.setTextColor(0xFFF3F7FC);
        view.setTextSize(14f);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(0, dp(12), 0, dp(6));
        view.setLineSpacing(0f, 1.15f);
        return view;
    }

    private void showFaqPanel() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(4), dp(2), dp(4), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("常见问题")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .create();
        renderFaq(content, FaqClient.cached(this), "正在从服务器同步 FAQ…");
        dialog.show();
        FaqClient.fetchAsync(this, result -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || !dialog.isShowing()) return;
            renderFaq(content, result.document,
                    result.refreshed ? "已同步最新 FAQ" : result.document == null
                            ? "服务器暂时无法连接，暂无本地缓存" : "当前显示本地缓存，服务器暂时无法连接");
        }));
    }

    private void renderFaq(LinearLayout content, FaqClient.FaqDocument document, String status) {
        content.removeAllViews();
        TextView state = text(status + (document != null && !document.updatedAt.isEmpty()
                        ? "\n更新时间：" + document.updatedAt : ""),
                12, themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant,
                        0xFF8392A8), false);
        state.setLineSpacing(0f, 1.2f);
        state.setPadding(0, 0, 0, dp(12));
        content.addView(state);
        if (document == null) {
            TextView empty = text("暂时没有可显示的 FAQ，请稍后重试。", 14,
                    themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant,
                            0xFF8392A8), false);
            content.addView(empty);
            return;
        }
        for (FaqClient.Item item : document.items) {
            TextView question = text(item.question, 16,
                    themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFF2F6FB), true);
            question.setPadding(0, dp(8), 0, dp(6));
            content.addView(question);
            if (!item.answer.isEmpty()) {
                TextView answer = text(item.answer, 14,
                        themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFD8E1EE), false);
                answer.setLineSpacing(0f, 1.2f);
                content.addView(answer);
            }
            for (FaqClient.Instruction instruction : item.instructions) {
                TextView title = text(instruction.title, 13,
                        themeColor(com.google.android.material.R.attr.colorPrimary, 0xFF6EE7F2), true);
                title.setPadding(0, dp(10), 0, dp(4));
                content.addView(title);
                addFaqCommand(content, instruction.command);
            }
            if (!item.note.isEmpty()) {
                TextView note = text(item.note, 12,
                        themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant,
                                0xFF8392A8), false);
                note.setLineSpacing(0f, 1.2f);
                note.setPadding(0, dp(8), 0, dp(4));
                content.addView(note);
            }
        }
    }

    private void addFaqCommand(LinearLayout parent, String command) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView value = text(command, 12,
                themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFF2F6FB), false);
        value.setTextIsSelectable(true);
        value.setTypeface(android.graphics.Typeface.MONOSPACE);
        value.setLineSpacing(0f, 1.1f);
        value.setPadding(dp(10), dp(8), dp(10), dp(8));
        value.setBackground(solid(0xFF25364D, 8));
        row.addView(value, new LinearLayout.LayoutParams(0, -2, 1f));
        MaterialButton copy = button("复制", false);
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("FAQ 命令", command));
            SafeToast.show(this, "命令已复制", Toast.LENGTH_SHORT);
        });
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(dp(64), dp(44));
        copyParams.leftMargin = dp(8);
        row.addView(copy, copyParams);
        parent.addView(row);
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
        int selectedIndex = 0;
        for (int i = 0; i < catalogs.length; i++) {
            if (catalogs[i].equals(selected)) {
                selectedIndex = i;
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
        TextView catalogLabel = text("匹配词库", 13, 0xFFA9B6C8, false);
        LinearLayout.LayoutParams catalogLabelParams = new LinearLayout.LayoutParams(-1, -2);
        catalogLabelParams.topMargin = dp(12);
        content.addView(catalogLabel, catalogLabelParams);
        Spinner catalogSpinner = new Spinner(this);
        catalogSpinner.setAdapter(new ThemedSpinnerAdapter<>(this, labels));
        catalogSpinner.setSelection(selectedIndex);
        LinearLayout.LayoutParams catalogParams = new LinearLayout.LayoutParams(-1, dp(52));
        catalogParams.topMargin = dp(10);
        content.addView(catalogSpinner, catalogParams);
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
                            catalogs[catalogSpinner.getSelectedItemPosition()]);
                    refreshPreview();
                    SafeToast.show(this, "已按修正后的歌曲信息开始匹配",
                            Toast.LENGTH_SHORT);
                })
                .show();
    }

    private void refreshPreview() {
        if (previewPanel == null) return;
        previewPanel.reloadStyle();
        ViewGroup.LayoutParams params = previewPanel.getLayoutParams();
        if (params != null) {
            params.height = previewHeightPx();
            previewPanel.setLayoutParams(params);
        }
    }

    private int previewHeightPx() {
        float density = getResources().getDisplayMetrics().density;
        float screenWidthDp = getResources().getDisplayMetrics().widthPixels / density;
        float availableWidthDp = screenWidthDp - (useSideNavigation() ? 104f : 0f) - 72f;
        float aspectHeightDp = availableWidthDp * AppPreferences.panelHeightDp(this)
                / (float) AppPreferences.panelWidthDp(this);
        return dp(Math.max(AppPreferences.minimumPanelHeightDp(this),
                Math.min(420f, aspectHeightDp)));
    }

    private boolean useSideNavigation() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(16));
        MaterialShapeDrawable surface = new MaterialShapeDrawable();
        surface.setFillColor(android.content.res.ColorStateList.valueOf(
                themeColor(com.google.android.material.R.attr.colorSurfaceContainer, 0xFF101E31)));
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
                0xFF6EE7F2), true);
    }

    private void addGlobalFontControls(LinearLayout parent) {
        TextView label = sectionLabel("全局字体");
        label.setPadding(0, dp(16), 0, dp(3));
        parent.addView(label);
        globalFontSummary = text("当前：" + CustomFontStore.selectedFontLabel(this)
                + "（替换应用界面与全部歌词，支持 TTF / OTF / TTC）",
                12, 0xFF9EAFBF, false);
        globalFontSummary.setPadding(0, 0, 0, dp(6));
        parent.addView(globalFontSummary);
        LinearLayout row = new LinearLayout(this);
        MaterialButton importButton = button("导入全局字体", false);
        importButton.setOnClickListener(v -> openFontPicker());
        row.addView(importButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        MaterialButton resetButton = button("恢复系统字体", false);
        resetButton.setOnClickListener(v -> {
            CustomFontStore.clear(this);
            AppPreferences.changed(this);
            recreate();
        });
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        resetParams.leftMargin = dp(10);
        row.addView(resetButton, resetParams);
        parent.addView(row);
    }

    private void addFloatingColorControls(LinearLayout parent) {
        TextView label = sectionLabel("悬浮歌词颜色");
        label.setPadding(0, dp(16), 0, dp(3));
        parent.addView(label);

        baseColorButton = new MaterialButton(this);
        updateColorButton(baseColorButton, AppPreferences.lyricColor(this, false), "基础颜色");
        baseColorButton.setAllCaps(false);
        baseColorButton.setCornerRadius(dp(12));
        baseColorButton.setOnClickListener(v -> ColorPickerDialog.show(this,
                AppPreferences.lyricColor(this, false), picked -> {
                    AppPreferences.setLyricColor(this, false, picked);
                    updateColorButton(baseColorButton, picked, "基础颜色");
                    changed();
                }));
        parent.addView(baseColorButton, new LinearLayout.LayoutParams(-1, dp(48)));

        currentColorButton = new MaterialButton(this);
        updateColorButton(currentColorButton, AppPreferences.currentLyricColor(this, false), "当前歌词");
        currentColorButton.setAllCaps(false);
        currentColorButton.setCornerRadius(dp(12));
        currentColorButton.setOnClickListener(v -> ColorPickerDialog.show(this,
                AppPreferences.currentLyricColor(this, false), picked -> {
                    AppPreferences.setCurrentLyricColor(this, false, picked);
                    updateColorButton(currentColorButton, picked, "当前歌词");
                    changed();
                }));
        LinearLayout.LayoutParams currentParams = new LinearLayout.LayoutParams(-1, dp(48));
        currentParams.topMargin = dp(6);
        parent.addView(currentColorButton, currentParams);
    }

    private void updateColorButton(MaterialButton button, int color, String label) {
        int display = color == 0 ? 0xFFFFFFFF : color;
        button.setText(label + "  " + String.format(java.util.Locale.US, "#%06X", display & 0xFFFFFF));
        button.setTextSize(14f);
        button.setBackgroundColor(display);
        int r = (display >> 16) & 0xFF, g = (display >> 8) & 0xFF, b = display & 0xFF;
        button.setTextColor((r * 299 + g * 587 + b * 114) / 1000 < 128 ? 0xFFFFFFFF : 0xFF000000);
    }

    private void changed() {
        AppPreferences.changed(this);
        LyricsDisplayService.startOrRefresh(this);
    }

    private void openFontPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (startDocumentPicker(intent)) return;
        Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
        fallback.addCategory(Intent.CATEGORY_OPENABLE);
        fallback.setType("*/*");
        if (startDocumentPicker(fallback)) return;
        SafeToast.show(this, "此设备没有可用的文件选择器，请安装或启用系统文件管理器后重试。",
                Toast.LENGTH_LONG);
    }

    private void openLocalLyricDirectoryPicker() {
        if (Build.VERSION.SDK_INT < 21) {
            SafeToast.show(this, "Android 4.4 会直接尝试歌曲同目录；无需选择目录。",
                    Toast.LENGTH_LONG);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            if (intent.resolveActivity(getPackageManager()) == null) {
                SafeToast.show(this, "此设备没有目录选择器。将 .lrc 与歌曲放在同一目录即可直接匹配，无需授权。",
                        Toast.LENGTH_LONG);
                return;
            }
            startActivityForResult(intent, REQUEST_LOCAL_LYRIC_DIRECTORY);
        } catch (Throwable error) {
            SafeToast.show(this, "无法打开目录选择器。将 .lrc 与歌曲放在同一目录即可直接匹配，无需授权。",
                    Toast.LENGTH_LONG);
        }
    }

    private void editLocalLyricDirectoryPath() {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint("例如 /storage/XXXX-XXXX/Music");
        layout.setPadding(dp(20), 0, dp(20), 0);
        TextInputEditText input = new TextInputEditText(this);
        input.setSingleLine(true);
        input.setText(AppPreferences.localLyricDirectoryPath(this));
        layout.addView(input);
        new MaterialAlertDialogBuilder(this)
                .setTitle("手动填写本地歌词目录")
                .setMessage("用于没有系统目录选择器的车机。应用只在该目录及其子目录查找匹配的 .lrc；路径不会导出到配置分享码。"
                        + LocalLyricClient.manualDirectoryRequirementNote(this))
                .setView(layout)
                .setNegativeButton("取消", null)
                .setNeutralButton("清除", (dialog, which) -> {
                    AppPreferences.get(this).edit()
                            .remove(AppPreferences.KEY_LOCAL_LYRIC_DIRECTORY_PATH).apply();
                    MusicStateStore.reloadLyrics(this);
                })
                .setPositiveButton("保存", (dialog, which) -> {
                    String path = input.getText() == null ? "" : input.getText().toString().trim();
                    AppPreferences.get(this).edit()
                            .putString(AppPreferences.KEY_LOCAL_LYRIC_DIRECTORY_PATH, path).apply();
                    MusicStateStore.reloadLyrics(this);
                    if (path.isEmpty()) {
                        SafeToast.show(this, "已清除手动歌词目录", Toast.LENGTH_SHORT);
                    } else if (!LocalLyricClient.requestManualDirectoryAccess(this,
                            REQUEST_LOCAL_LYRIC_STORAGE)) {
                        // 无需申请或系统不再支持该权限时立刻给出结论；申请时由
                        // onRequestPermissionsResult 收尾，权限被拒绝也不会看起来还能用。
                        SafeToast.show(this, LocalLyricClient.manualDirectorySaveMessage(this),
                                Toast.LENGTH_LONG);
                    }
                }).show();
    }

    private boolean startDocumentPicker(Intent intent) {
        try {
            if (intent.resolveActivity(getPackageManager()) == null) return false;
            startActivityForResult(intent, REQUEST_CUSTOM_FONT);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
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
                ? themeColor(com.google.android.material.R.attr.colorOnPrimary, 0xFF07111F)
                : themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFF1F5FA));
        button.setAllCaps(false);
        button.setCornerRadius(dp(15));
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                primary ? themeColor(com.google.android.material.R.attr.colorPrimary, 0xFF6EE7F2)
                        // Keep secondary controls neutral on the deliberately dark home page;
                        // device DynamicColors can otherwise turn them lavender.
                        : 0xFF25364D));
        return button;
    }

    private void addPermissionRow(LinearLayout parent, String firstLabel, boolean firstPrimary,
                                  View.OnClickListener firstAction, String secondLabel,
                                  boolean secondPrimary, View.OnClickListener secondAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton first = button(firstLabel, firstPrimary);
        first.setOnClickListener(firstAction);
        row.addView(first, weightedButton());
        if (secondLabel != null) {
            MaterialButton second = button(secondLabel, secondPrimary);
            second.setOnClickListener(secondAction);
            LinearLayout.LayoutParams secondParams = weightedButton();
            secondParams.leftMargin = dp(10);
            row.addView(second, secondParams);
        }
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(48));
        if (parent.getChildCount() > 2) rowParams.topMargin = dp(8);
        parent.addView(row, rowParams);
    }

    private LinearLayout.LayoutParams weightedButton() {
        return new LinearLayout.LayoutParams(0, dp(48), 1f);
    }

    private GradientDrawable solid(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int themeColor(int attribute, int fallback) {
        TypedValue value = new TypedValue();
        return getTheme().resolveAttribute(attribute, value, true) ? value.data : fallback;
    }

    private int adaptiveTextColor(int requested) {
        int night = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) return requested;
        if (requested == 0xFF6EE7F2) {
            return themeColor(com.google.android.material.R.attr.colorPrimary, requested);
        }
        float luminance = (Color.red(requested) * 0.2126f + Color.green(requested) * 0.7152f
                + Color.blue(requested) * 0.0722f) / 255f;
        return luminance > 0.60f
                ? themeColor(com.google.android.material.R.attr.colorOnSurface, requested) : requested;
    }

    private void showConfigurationShareDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("配置分享")
                .setItems(new String[]{"生成分享码", "输入分享码导入"}, (dialog, which) -> {
                    if (which == 0) promptShareConfiguration();
                    else promptImportConfiguration();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmResetSettings() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("恢复默认设置？")
                .setMessage("将清除当前显示布局、颜色、歌词匹配规则、启动方式、本地歌词目录和自定义字体。")
                .setPositiveButton("恢复默认", (dialog, which) -> resetSettingsToDefaults())
                .setNegativeButton("取消", null)
                .show();
    }

    private void resetSettingsToDefaults() {
        String treeUri = AppPreferences.localLyricDirectoryUri(this);
        if (!treeUri.isEmpty()) {
            try {
                getContentResolver().releasePersistableUriPermission(Uri.parse(treeUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) { }
        }
        CustomFontStore.clear(this);
        int removed = AppPreferences.resetUserSettings(this);
        MusicStateStore.reloadLyrics(this);
        AppPreferences.changed(this);
        SafeToast.show(this, "已恢复默认设置（重置 " + removed + " 项）",
                Toast.LENGTH_SHORT);
        recreate();
    }

    private void promptShareConfiguration() {
        TextInputEditText input = new TextInputEditText(this);
        input.setHint("配置简介（可选，最多 200 字）");
        input.setSingleLine(false);
        input.setMaxLines(3);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});
        input.setPadding(dp(20), dp(12), dp(20), dp(12));
        new MaterialAlertDialogBuilder(this)
                .setTitle("生成配置分享码")
                .setMessage("只上传可分享的设置；不会上传歌曲、歌词、本地目录、字体文件或设备标识。")
                .setView(input)
                .setPositiveButton("上传", (dialog, which) -> {
                    String description = input.getText() == null ? "" : input.getText().toString();
                    ConfigurationShareClient.share(this, description, result -> runOnUiThread(() -> {
                        if (!result.success) {
                            SafeToast.show(this, "生成失败：" + result.error, Toast.LENGTH_LONG);
                            return;
                        }
                        String code = result.value.optString("code");
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("分享码已生成")
                                .setMessage(code + "\n\n有效期至：" + result.value.optString("expiresAt"))
                                .setPositiveButton("复制", (ignored, copyWhich) -> copyText("配置分享码", code))
                                .setNegativeButton("完成", null)
                                .show();
                    }));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptImportConfiguration() {
        TextInputEditText input = new TextInputEditText(this);
        input.setHint("8 位分享码");
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)});
        input.setPadding(dp(20), dp(12), dp(20), dp(12));
        new MaterialAlertDialogBuilder(this)
                .setTitle("导入配置")
                .setView(input)
                .setPositiveButton("查看", (dialog, which) -> {
                    String code = input.getText() == null ? "" : input.getText().toString();
                    ConfigurationShareClient.fetch(code, result -> runOnUiThread(() -> {
                        if (!result.success) {
                            SafeToast.show(this, "读取失败：" + result.error, Toast.LENGTH_LONG);
                            return;
                        }
                        String description = result.value.optString("description", "未填写简介");
                        if (description.trim().isEmpty()) description = "未填写简介";
                        String finalDescription = description;
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("确认导入 " + result.value.optString("code"))
                                .setMessage("配置简介：\n" + finalDescription
                                        + "\n\n导入会覆盖分享码中包含的设置，本机私密数据不受影响。")
                                .setPositiveButton("导入", (ignored, importWhich) -> {
                                    try {
                                        int count = ConfigurationCodec.importConfiguration(this,
                                                result.value.getJSONObject("config"));
                                        AppPreferences.changed(this);
                                        LyricsDisplayService.startOrRefresh(this);
                                        SafeToast.show(this, "已导入 " + count + " 项设置",
                                                Toast.LENGTH_SHORT);
                                        recreate();
                                    } catch (Throwable error) {
                                        SafeToast.show(this, "导入失败：" + error.getMessage(),
                                                Toast.LENGTH_LONG);
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyText(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        SafeToast.show(this, "已复制", Toast.LENGTH_SHORT);
    }

    private void openUrl(String address) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address))); }
        catch (Throwable error) {
            SafeToast.show(this, "无法打开链接：" + address, Toast.LENGTH_LONG);
        }
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

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
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
        SafeToast.show(this, "\u65e0\u6cd5\u6253\u5f00\u7cfb\u7edf\u7684\u901a\u77e5\u8bfb\u53d6\u8bbe\u7f6e\uff0c\u8bf7\u5728\u7cfb\u7edf\u8bbe\u7f6e\u4e2d\u624b\u52a8\u5f00\u542f\u3002",
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
        pendingPermissionFaqCheck |= permissionType;
        return true;
    }

    private void promptPermissionFaqIfStillMissing() {
        if (pendingPermissionFaqCheck == 0 || permissionFaqDialogVisible || isFinishing()) return;
        int pending = pendingPermissionFaqCheck;
        pendingPermissionFaqCheck = 0;
        boolean notificationMissing = (pending & PERMISSION_CHECK_NOTIFICATION) != 0
                && !hasNotificationAccess();
        boolean overlayMissing = (pending & PERMISSION_CHECK_OVERLAY) != 0
                && !canDrawOverlays();
        if (!notificationMissing && !overlayMissing) return;

        String missing;
        if (notificationMissing && overlayMissing) {
            missing = "音乐读取权限和悬浮窗权限";
        } else if (notificationMissing) {
            missing = "音乐读取权限";
        } else {
            missing = "悬浮窗权限";
        }
        permissionFaqDialogVisible = true;
        new MaterialAlertDialogBuilder(this)
                .setTitle("权限仍未生效")
                .setMessage("检测到“" + missing + "”仍未授权。不同系统可能将开关放在额外的安全、通知或应用管理页面，可在常见问题中查看对应解决方法。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("查看常见问题", (dialog, which) -> showFaqPanel())
                .setOnDismissListener(dialog -> permissionFaqDialogVisible = false)
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

    private static String formatSessionReadAge(long lastReadElapsedMs) {
        if (lastReadElapsedMs <= 0L) return "从未";
        long ageMs = Math.max(0L, SystemClock.elapsedRealtime() - lastReadElapsedMs);
        if (ageMs < 1_000L) return "不到 1 秒前";
        return ageMs / 1_000L + " 秒前";
    }

    private void openOverlayPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            if (!startPermissionSettingsActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())), PERMISSION_CHECK_OVERLAY)) {
                SafeToast.show(this, "无法打开系统应用设置，请在系统设置中手动开启悬浮窗权限。",
                        Toast.LENGTH_LONG);
            }
            return;
        }
        Intent intent = new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION",
                Uri.parse("package:" + getPackageName()));
        if (startPermissionSettingsActivity(intent, PERMISSION_CHECK_OVERLAY)) return;
        if (startPermissionSettingsActivity(
                new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION"), PERMISSION_CHECK_OVERLAY)) {
            return;
        }
        if (startPermissionSettingsActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())), PERMISSION_CHECK_OVERLAY)) {
            return;
        }
        SafeToast.show(this, "无法打开系统悬浮窗权限设置，请在系统设置中手动开启。", Toast.LENGTH_LONG);
    }

    private static String permissionState(boolean granted) {
        return granted ? "已授权" : "未授权";
    }

    private void showPermissionHomeHint(String permission) {
        SafeToast.show(this, "请在首页“使用权限”中授予" + permission + "权限",
                Toast.LENGTH_LONG);
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

    private void openUsageAccessSettings() {
        if (Build.VERSION.SDK_INT < 21) {
            SafeToast.show(this, "当前系统无需使用情况访问权限", Toast.LENGTH_SHORT);
            return;
        }
        Intent direct = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        if (startSettingsActivity(direct)) return;
        if (startSettingsActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))) return;
        openApplicationDetails();
    }

    private void openDefaultAppsSettings() {
        if (startSettingsActivity(new Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"))) {
            return;
        }
        if (!startSettingsActivity(new Intent(Settings.ACTION_SETTINGS))) {
            SafeToast.show(this, "无法打开默认应用设置，请在系统设置中手动进入。",
                    Toast.LENGTH_LONG);
        }
    }

    private void openApplicationDetails() {
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        if (!startSettingsActivity(details)) {
            startSettingsActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

}
