package wemew.lrc.sync;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
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
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
    private static final int REQUEST_LOCAL_LYRIC_DIRECTORY = 2419;
    private static final int REQUEST_LOCAL_LYRIC_STORAGE = 2421;
    private static final String STATE_SELECTED_SECTION = "selected_section";
    private static final String[] SECTION_LABELS = {"状态", "歌词"};
    private static final String[] SECTION_TITLES = {"状态与权限", "歌词来源与校准"};
    private static final String[] SECTION_DESCRIPTIONS = {
            "先完成音乐读取权限，再打开网页歌词同步",
            "管理词库优先级、本地歌词和匹配修正"
    };
    private static final ExecutorService SHARED_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final long LISTENER_HEALTH_MAX_AGE_MS = 3_000L;
    private static final long LISTENER_INITIAL_RECONNECT_DELAY_MS = 2_500L;
    private static final long LISTENER_RECONNECT_INTERVAL_MS = 1_000L;
    private static final long LISTENER_RECONNECT_WINDOW_MS = 30_000L;
    private static final int PERMISSION_CHECK_NOTIFICATION = 1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView permissionStatus;
    private TextView musicStatus;
    private TextView webStatus;
    private LinearLayout webUrlRows;
    private MaterialSwitch webSwitch;
    private boolean bindingSwitches;
    private TextView sectionHeading;
    private TextView sectionDescription;
    private ScrollView mainScroll;
    private final List<View> sectionPages = new ArrayList<>();
    private final List<MaterialButton> sectionButtons = new ArrayList<>();
    private int selectedSection;
    private boolean activityResumed;
    private boolean permissionDialogVisible;
    private int pendingPermissionCheck;
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
        // The settings surface is intentionally a stable dark workspace.
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            selectedSection = savedInstanceState.getInt(STATE_SELECTED_SECTION, 0);
        }
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFF07111F);
            getWindow().setNavigationBarColor(0xFF07111F);
        }
        setContentView(buildContent());
        applyUiScale(getWindow().getDecorView());
        MusicStateStore.initialize(this);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_SELECTED_SECTION, selectedSection);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onResume() {
        super.onResume();
        activityResumed = true;
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

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LOCAL_LYRIC_DIRECTORY) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) { }
        AppPreferences.setLocalLyricDirectoryUri(this, uri.toString());
        MusicStateStore.reloadLyrics(this);
        SafeToast.show(this, "已授权本地歌词目录", Toast.LENGTH_SHORT);
    }

    private View buildContent() {
        sectionPages.clear();
        sectionButtons.clear();
        View shell = getLayoutInflater().inflate(R.layout.activity_main, null, false);
        LinearLayout root = shell.findViewById(R.id.main_content);
        LinearLayout pageHost = shell.findViewById(R.id.main_page_host);
        mainScroll = shell.findViewById(R.id.main_scroll);
        MaterialToolbar toolbar = shell.findViewById(R.id.main_toolbar);
        toolbar.setTitle("微喵歌词同步");
        toolbar.setSubtitle("网页歌词同步");
        toolbar.setTitleTextColor(Color.WHITE);
        toolbar.setSubtitleTextColor(0xFFA9B6C8);
        sectionHeading = shell.findViewById(R.id.main_section_heading);
        sectionDescription = shell.findViewById(R.id.main_section_description);
        sectionDescription.setLineSpacing(0f, 1.18f);

        LinearLayout accessCard = card();
        accessCard.addView(sectionLabel("使用权限"));
        permissionStatus = text("", 14, 0xFFD8E1EE, false);
        permissionStatus.setPadding(0, dp(8), 0, dp(12));
        accessCard.addView(permissionStatus);
        addPermissionRow(accessCard, "音乐读取权限", true, v -> openNotificationAccess(),
                "通知显示权限", false, v -> requestPostNotificationPermission());
        addPermissionRow(accessCard, "应用权限 / 自启动", false, v -> openApplicationDetails(),
                null, false, null);

        LinearLayout webCard = card();
        webCard.addView(sectionLabel("网页歌词"));
        webSwitch = toggle("网页歌词同步服务",
                "在车机、平板或电脑的浏览器里打开下面任意地址即可显示歌词；页面端可自行调节字号、颜色与字体。开启后开机也会自动恢复");
        webSwitch.setChecked(AppPreferences.webEnabled(this));
        webSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (bindingSwitches) return;
            AppPreferences.setWebEnabled(this, checked);
            if (checked) WebSyncService.start(this);
            else WebSyncService.stop(this);
            refreshStatus();
        });
        webCard.addView(webSwitch);
        webStatus = text("", 13, 0xFF9EAFBF, false);
        webStatus.setLineSpacing(0f, 1.2f);
        webStatus.setPadding(0, dp(10), 0, dp(4));
        webCard.addView(webStatus);
        webUrlRows = new LinearLayout(this);
        webUrlRows.setOrientation(LinearLayout.VERTICAL);
        webCard.addView(webUrlRows);

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

        LinearLayout lyricCard = card();
        lyricCard.addView(sectionLabel("歌词匹配"));
        MaterialSwitch playerCatalogFallback = toggle("回退到播放器同源词库",
                "手动选择的词库无结果时，再尝试从应用名称识别出的播放器词库");
        addLyricCatalogSelector(lyricCard, playerCatalogFallback);
        playerCatalogFallback.setChecked(AppPreferences.playerCatalogFallback(this));
        playerCatalogFallback.setOnCheckedChangeListener((button, checked) -> {
            AppPreferences.setPlayerCatalogFallback(this, checked);
            MusicStateStore.reloadLyrics(this);
        });
        lyricCard.addView(playerCatalogFallback);
        MaterialSwitch localLyrics = toggle("优先匹配本地歌词（.lrc / 内嵌标签）",
                "先在音频文件旁查找同名 .lrc；没有时直接读取文件内嵌歌词（FLAC / MP3 / M4A / OGG），"
                        + "最后才在已授权的音乐目录里按文件名、歌名或“歌手 - 歌名”搜索");
        localLyrics.setChecked(AppPreferences.localLyricEnabled(this));
        localLyrics.setOnCheckedChangeListener((button, checked) -> {
            AppPreferences.setLocalLyricEnabled(this, checked);
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
        MaterialButton ignoredPlayers = button("忽略指定播放器", false);
        ignoredPlayers.setOnClickListener(v -> showIgnoredPlayerPicker());
        LinearLayout.LayoutParams ignoredPlayersParams = new LinearLayout.LayoutParams(-1, dp(48));
        ignoredPlayersParams.topMargin = dp(8);
        lyricCard.addView(ignoredPlayers, ignoredPlayersParams);
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
            AppPreferences.setCompositeIdentityFromArtist(this, checked);
        });
        lyricCard.addView(compositeIdentity);

        LinearLayout resetCard = card();
        resetCard.addView(sectionLabel("数据与重置"));
        TextView resetSummary = text(
                "恢复歌词匹配、本地目录和网页服务等默认设置。",
                12, 0xFF8392A8, false);
        resetSummary.setPadding(0, dp(9), 0, dp(10));
        resetCard.addView(resetSummary);
        MaterialButton resetSettings = button("恢复默认设置", false);
        resetSettings.setOnClickListener(v -> confirmResetSettings());
        resetCard.addView(resetSettings, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout uiScaleCard = card();
        uiScaleCard.addView(sectionLabel("设置界面"));
        addSettingsUiScaleSelector(uiScaleCard);

        LinearLayout homePage = sectionPage();
        homePage.addView(uiScaleCard, cardMargins());
        homePage.addView(accessCard, cardMargins());
        homePage.addView(webCard, cardMargins());
        homePage.addView(stateCard, cardMargins());

        LinearLayout lyricsPage = sectionPage();
        lyricsPage.addView(lyricCard, cardMargins());
        lyricsPage.addView(resetCard, cardMargins());

        TextView footnote = text("提示：在线、本地和 U 盘播放器优先读取系统媒体信息，缺失时尝试识别音乐通知；通知未提供进度时无法精准自动滚动。匹配歌词优先复用本地缓存；文件名会自动清理路径、序号、扩展名和音质标记，仍不准确时可用“修正歌曲信息并重新匹配”。", 12,
                0xFF66788F, false);
        footnote.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams footnoteParams = new LinearLayout.LayoutParams(-1, -2);
        footnoteParams.topMargin = dp(16);
        lyricsPage.addView(footnote, footnoteParams);

        sectionPages.add(homePage);
        sectionPages.add(lyricsPage);
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
        int[] ids = {R.id.nav_home, R.id.nav_lyrics};
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
                    MusicStateStore.reloadLyrics(this);
                    SafeToast.show(this, "已保存 " + catalogLabel + " 强制匹配应用", Toast.LENGTH_SHORT);
                })
                .show();
    }

    /** issue #35：车机自带的媒体中心、导航或蓝牙通道常常抢走歌词，把它们列进忽略名单。 */
    private void showIgnoredPlayerPicker() {
        LinearLayout loading = new LinearLayout(this);
        loading.setPadding(dp(24), dp(16), dp(24), dp(16));
        loading.addView(text("正在读取已安装应用…", 14, 0xFFD8E1EE, false));
        AlertDialog loadingDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("忽略指定播放器")
                .setView(loading)
                .setNegativeButton("取消", null)
                .show();
        SHARED_EXECUTOR.execute(() -> {
            List<InstalledAppListCache.AppChoice> apps = InstalledAppListCache.load(this,
                    AppPreferences.observedPlayerPackages(this));
            handler.post(() -> {
                if (loadingDialog.isShowing()) loadingDialog.dismiss();
                if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                showLoadedIgnoredPlayerPicker(apps);
            });
        });
    }

    private void showLoadedIgnoredPlayerPicker(List<InstalledAppListCache.AppChoice> apps) {
        Set<String> selected = AppPreferences.ignoredPlayerPackages(this);
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
                .setTitle("忽略指定播放器")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    AppPreferences.setIgnoredPlayerPackages(this, selected);
                    MusicStateStore.reloadLyrics(this);
                    SafeToast.show(this, "已保存忽略名单（" + selected.size() + " 个应用）",
                            Toast.LENGTH_SHORT);
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

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                      int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
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
        boolean postNotifications = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        String listenerState = listenerState(notificationAccess);
        permissionStatus.setText("通知读取  " + (notificationAccess ? "已授权" : "未授权")
                + "     监听器  " + listenerState
                + "\n通知显示  " + permissionState(postNotifications));
        permissionStatus.setTextColor(notificationAccess ? 0xFF6EE7F2 : 0xFFFFCA66);
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
        refreshWebStatus();
    }

    private void refreshWebStatus() {
        if (webStatus == null || webSwitch == null) return;
        bindingSwitches = true;
        webSwitch.setChecked(AppPreferences.webEnabled(this));
        bindingSwitches = false;
        boolean running = WebSyncService.isRunning();
        webStatus.setText(running
                ? "服务运行中 · 已连接显示端 " + WebSyncService.clientCount() + " 个"
                : "服务未开启");
        webStatus.setTextColor(running ? 0xFF6EE7F2 : 0xFF9EAFBF);
        webUrlRows.removeAllViews();
        if (!running) return;
        List<String> urls = WebSyncService.lanUrls();
        if (urls.isEmpty()) {
            TextView none = text("未找到局域网地址；请确认手机与显示设备在同一网络", 12,
                    0xFF8392A8, false);
            none.setPadding(0, dp(2), 0, dp(2));
            webUrlRows.addView(none);
            return;
        }
        for (String url : urls) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView label = text(url, 13, 0xFFD8E1EE, false);
            label.setPadding(0, 0, dp(8), 0);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            MaterialButton copy = button("复制", false);
            copy.setOnClickListener(v -> copyText("网页歌词地址", url));
            row.addView(copy, new LinearLayout.LayoutParams(dp(72), dp(42)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(4);
            webUrlRows.addView(row, params);
        }
    }

    private void copyText(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        SafeToast.show(this, "已复制", Toast.LENGTH_SHORT);
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
        TextView note = text("仅放大设置页面的文字与控件，不改变网页端字号。", 12,
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
                    SafeToast.show(this, "已按修正后的歌曲信息开始匹配",
                            Toast.LENGTH_SHORT);
                })
                .show();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(16));
        MaterialShapeDrawable surface = new MaterialShapeDrawable();
        surface.setFillColor(ColorStateList.valueOf(
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

    private void confirmResetSettings() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("恢复默认设置？")
                .setMessage("将清除歌词匹配规则、本地歌词目录和网页服务设置。")
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
        int removed = AppPreferences.resetUserSettings(this);
        MusicStateStore.reloadLyrics(this);
        SafeToast.show(this, "已恢复默认设置（重置 " + removed + " 项）",
                Toast.LENGTH_SHORT);
        recreate();
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
                .setMessage("用于没有系统目录选择器的车机。应用只在该目录及其子目录查找匹配的 .lrc；路径不会离开本机。"
                        + LocalLyricClient.manualDirectoryRequirementNote(this))
                .setView(layout)
                .setNegativeButton("取消", null)
                .setNeutralButton("清除", (dialog, which) -> {
                    AppPreferences.setLocalLyricDirectoryPath(this, "");
                    MusicStateStore.reloadLyrics(this);
                })
                .setPositiveButton("保存", (dialog, which) -> {
                    String path = input.getText() == null ? "" : input.getText().toString().trim();
                    AppPreferences.setLocalLyricDirectoryPath(this, path);
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
        button.setBackgroundTintList(ColorStateList.valueOf(
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
        android.util.TypedValue value = new android.util.TypedValue();
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

    /** Scales every text control by the settings-UI scale, replacing the old font-store hook. */
    private void applyUiScale(View view) {
        int scale = AppPreferences.settingsUiScale(this);
        if (scale == 100 || view == null) return;
        float factor = scale / 100f;
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            textView.setTextSize(textView.getTextSize() / getResources().getDisplayMetrics()
                    .scaledDensity * factor);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                applyUiScale(group.getChildAt(index));
            }
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

    private static String formatSessionReadAge(long lastReadElapsedMs) {
        if (lastReadElapsedMs <= 0L) return "从未";
        long ageMs = Math.max(0L, SystemClock.elapsedRealtime() - lastReadElapsedMs);
        if (ageMs < 1_000L) return "不到 1 秒前";
        return ageMs / 1_000L + " 秒前";
    }

    private static String permissionState(boolean granted) {
        return granted ? "已授权" : "未授权";
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
