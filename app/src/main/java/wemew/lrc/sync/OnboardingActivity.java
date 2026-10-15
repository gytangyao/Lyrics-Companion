package wemew.lrc.sync;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.shape.MaterialShapeDrawable;

/**
 * First-run guide. Screen 1 picks the lyric source (WeSing via the PC watcher vs.
 * media-session players); screen 2 walks the permissions and background-survival
 * steps that make the chosen mode actually work. The main screen can re-run it any
 * time from the permission card.
 */
@SuppressLint("SetTextI18n")
public final class OnboardingActivity extends AppCompatActivity {
    /** "wesing" = PC watcher source; "media" = notification/media-session players. */
    private String mode = "";
    private LinearLayout sourceCards;
    private LinearLayout permissionList;
    private MaterialButton nextButton;
    private MaterialButton finishButton;
    private LinearLayout screen1;
    private LinearLayout screen2;
    private LinearLayout screen3;
    private TextView screen2Title;
    private TextView deviceCodeView;
    private TextView bindingStatusView;
    private TextView barNameView;
    private TextView barIdView;
    private MaterialButton finishButton3;
    /** Light poll that re-reads the binding preferences while screen 3 is visible. */
    private final android.os.Handler refreshHandler = new android.os.Handler();
    private final Runnable bindingRefresh = new Runnable() {
        @Override public void run() {
            refreshBindingStatus();
            refreshHandler.postDelayed(this, 3_000L);
        }
    };
    /** MIUI autostart has no query API; the user confirms it manually on that ROM. */
    private boolean autostartConfirmed;

    @Override protected void onCreate(Bundle savedInstanceState) {
        getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFF0B0E1C);
            getWindow().setNavigationBarColor(0xFF0B0E1C);
        }
        String saved = getIntent().getStringExtra("mode");
        mode = saved != null && !saved.isEmpty() ? saved : AppPreferences.musicSourceMode(this);
        setContentView(buildContent());
        refreshChecklist();
        // The binding poll and cloud leg live in the sync service; start it now so the
        // authorization screen reflects a backend registration within seconds.
        try {
            WebSyncService.start(this);
        } catch (Throwable ignored) { }
    }

    @Override protected void onResume() {
        super.onResume();
        // Settings round-trips land here; re-evaluate every checklist row.
        refreshChecklist();
        if (screen3 != null && screen3.getVisibility() == View.VISIBLE) startBindingRefresh();
    }

    @Override protected void onPause() {
        stopBindingRefresh();
        super.onPause();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0E1C);

        screen1 = buildSourceScreen();
        screen2 = buildPermissionScreen();
        screen3 = buildBindingScreen();
        screen2.setVisibility(View.GONE);
        screen3.setVisibility(View.GONE);
        root.addView(screen1, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(screen2, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(screen3, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    // ---- screen 3: device authorization (shared by both modes) --------------------

    private LinearLayout buildBindingScreen() {
        LinearLayout page = page();
        TextView back = text("←  上一步", 14, 0xFFF40861, true);
        back.setPadding(dp(4), dp(6), dp(4), dp(6));
        back.setOnClickListener(v -> {
            stopBindingRefresh();
            screen3.setVisibility(View.GONE);
            screen2.setVisibility(View.VISIBLE);
        });
        page.addView(back, wrap());
        TextView title = text("设备授权", 22, 0xFFF3F7FC, true);
        title.setPadding(dp(4), dp(2), dp(4), dp(4));
        page.addView(title, wrap());
        TextView subtitle = text("把设备码登记到微喵门店后台，绑定后自动连接门店房间",
                13, 0xFFADABF2, false);
        subtitle.setLineSpacing(0f, 1.2f);
        subtitle.setPadding(dp(4), dp(2), dp(4), dp(4));
        page.addView(subtitle, wrap());

        LinearLayout codeCard = new LinearLayout(this);
        codeCard.setOrientation(LinearLayout.VERTICAL);
        codeCard.setGravity(Gravity.CENTER_HORIZONTAL);
        codeCard.setPadding(dp(16), dp(18), dp(16), dp(18));
        MaterialShapeDrawable surface = new MaterialShapeDrawable();
        surface.setFillColor(ColorStateList.valueOf(0xFF1C1D2C));
        surface.setCornerSize(dp(20));
        codeCard.setBackground(surface);
        TextView codeLabel = text("设备码", 13, 0xFFADABF2, false);
        codeCard.addView(codeLabel, wrap());
        deviceCodeView = text(StoreBindingClient.deviceId(this), 22, 0xFFF3F7FC, true);
        if (Build.VERSION.SDK_INT >= 21) deviceCodeView.setLetterSpacing(0.18f);
        deviceCodeView.setPadding(0, dp(6), 0, dp(10));
        codeCard.addView(deviceCodeView, wrap());
        MaterialButton copy = button("复制设备码", false);
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("设备码",
                        StoreBindingClient.deviceId(this)));
            }
            SafeToast.show(this, "设备码已复制", Toast.LENGTH_SHORT);
        });
        codeCard.addView(copy, new LinearLayout.LayoutParams(-1, dp(44)));
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(-1, -2);
        codeParams.topMargin = dp(8);
        page.addView(codeCard, codeParams);

        bindingStatusView = text("", 14, 0xFFF4E230, true);
        bindingStatusView.setLineSpacing(0f, 1.2f);
        bindingStatusView.setPadding(dp(4), dp(12), dp(4), dp(4));
        page.addView(bindingStatusView, wrap());
        barNameView = text("", 16, 0xFFF3F7FC, true);
        barNameView.setPadding(dp(4), dp(2), dp(4), dp(2));
        page.addView(barNameView, wrap());
        barIdView = text("", 14, 0xFFADABF2, false);
        barIdView.setPadding(dp(4), dp(2), dp(4), dp(4));
        page.addView(barIdView, wrap());

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.BOTTOM);
        finishButton3 = button("等待门店绑定…", true);
        finishButton3.setEnabled(false);
        finishButton3.setAlpha(0.45f);
        finishButton3.setOnClickListener(v -> {
            if (!AppPreferences.boundBarId(this).isEmpty()) finishOnboarding();
        });
        bottom.addView(finishButton3, new LinearLayout.LayoutParams(-1, dp(48)));
        page.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        return page;
    }

    private void refreshBindingStatus() {
        if (bindingStatusView == null) return;
        String barId = AppPreferences.boundBarId(this);
        boolean bound = !barId.isEmpty();
        bindingStatusView.setText(bound ? "已绑定" : "等待绑定…登记后约 30 秒内自动生效");
        bindingStatusView.setTextColor(bound ? 0xFF7ADB9B : 0xFFF4E230);
        barNameView.setText(bound ? "门店名称　" + AppPreferences.boundBarName(this) : "");
        barIdView.setText(bound ? "门店Id　　" + barId : "");
        finishButton3.setText(bound ? "完成，进入主界面" : "等待门店绑定…");
        finishButton3.setEnabled(bound);
        finishButton3.setAlpha(bound ? 1f : 0.45f);
    }

    private void startBindingRefresh() {
        refreshHandler.removeCallbacks(bindingRefresh);
        refreshHandler.post(bindingRefresh);
    }

    private void stopBindingRefresh() {
        refreshHandler.removeCallbacks(bindingRefresh);
    }

    // ---- screen 1: source choice ------------------------------------------------

    private LinearLayout buildSourceScreen() {
        LinearLayout page = page();

        TextView title = text("选择歌词来源", 22, 0xFFF3F7FC, true);
        title.setPadding(dp(4), dp(14), dp(4), dp(4));
        page.addView(title, wrap());

        sourceCards = new LinearLayout(this);
        sourceCards.setOrientation(LinearLayout.VERTICAL);
        page.addView(sourceCards, wrap());

        // Created before the cards: addSourceCard() flags the pre-selected mode on it.
        nextButton = button("下一步", true);
        nextButton.setEnabled(false);
        nextButton.setAlpha(0.45f);
        nextButton.setOnClickListener(v -> {
            AppPreferences.putMusicSourceMode(this, mode);
            AppLog.i("Onboarding", "source mode chosen: " + mode);
            screen1.setVisibility(View.GONE);
            screen2.setVisibility(View.VISIBLE);
            refreshChecklist();
        });

        addSourceCard("全民K歌", "wesing",
                new String[]{"com.tencent.karaoke"});
        addSourceCard("媒体播放器", "media",
                new String[]{"com.tencent.qqmusic", "com.netease.cloudmusic",
                        "com.kugou.android", "com.luna.music"});

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.BOTTOM);
        bottom.addView(nextButton, new LinearLayout.LayoutParams(-1, dp(48)));
        page.addView(bottom, new LinearLayout.LayoutParams(-1, 0, 1f));
        return page;
    }

    /** A source card: real app icons (from PackageManager) plus one short label. */
    private void addSourceCard(String label, String value, String[] iconPackages) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(18), dp(16), dp(18));
        MaterialShapeDrawable surface = new MaterialShapeDrawable();
        surface.setCornerSize(dp(20));
        surface.setElevation(dp(1));
        card.setBackground(surface);
        card.setOnClickListener(v -> {
            mode = value;
            // Persist immediately: a rotation or process death must not lose the choice.
            AppPreferences.putMusicSourceMode(this, mode);
            for (int i = 0; i < sourceCards.getChildCount(); i++) {
                highlightSourceCard((LinearLayout) sourceCards.getChildAt(i), i == indexForMode(value));
            }
            nextButton.setEnabled(true);
            nextButton.setAlpha(1f);
        });

        LinearLayout icons = new LinearLayout(this);
        icons.setOrientation(LinearLayout.HORIZONTAL);
        icons.setGravity(Gravity.CENTER_VERTICAL);
        int size = iconPackages.length == 1 ? dp(56) : dp(40);
        for (int i = 0; i < iconPackages.length; i++) {
            View icon = iconView(iconPackages[i], size);
            LinearLayout.LayoutParams iconParams =
                    new LinearLayout.LayoutParams(size, size);
            if (i > 0) iconParams.leftMargin = dp(6);
            icons.addView(icon, iconParams);
        }
        card.addView(icons, new LinearLayout.LayoutParams(-2, -2));
        TextView heading = text(label, 18, 0xFFF3F7FC, true);
        heading.setPadding(dp(16), 0, 0, 0);
        card.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        sourceCards.addView(card, params);
        highlightSourceCard(card, value.equals(mode));
        if (value.equals(mode)) {
            nextButton.setEnabled(true);
            nextButton.setAlpha(1f);
        }
    }

    /** The app's launcher icon, or a styled letter block when it is not installed. */
    private View iconView(String packageName, int sizePx) {
        try {
            android.graphics.drawable.Drawable icon =
                    getPackageManager().getApplicationIcon(packageName);
            android.widget.ImageView view = new android.widget.ImageView(this);
            view.setImageDrawable(icon);
            view.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            return view;
        } catch (Throwable ignored) {
            TextView fallback = text("♪", 18, 0xFFF3F7FC, true);
            fallback.setGravity(Gravity.CENTER);
            fallback.setBackground(makeFallbackIconBackground());
            return fallback;
        }
    }

    private MaterialShapeDrawable makeFallbackIconBackground() {
        MaterialShapeDrawable surface = new MaterialShapeDrawable();
        surface.setFillColor(ColorStateList.valueOf(0xFF2A2C44));
        surface.setCornerSize(dp(12));
        return surface;
    }

    private int indexForMode(String value) {
        return "media".equals(value) ? 1 : 0;
    }

    private void highlightSourceCard(LinearLayout card, boolean selected) {
        MaterialShapeDrawable surface = (MaterialShapeDrawable) card.getBackground();
        surface.setFillColor(ColorStateList.valueOf(selected ? 0xFF2A2C44 : 0xFF1C1D2C));
        surface.setStroke(dp(1.5f), selected ? 0xFFF40861 : 0x00000000);
    }

    // ---- screen 2: permissions ----------------------------------------------------

    private LinearLayout buildPermissionScreen() {
        LinearLayout page = page();
        TextView back = text("←  上一步", 14, 0xFFF40861, true);
        back.setPadding(dp(4), dp(6), dp(4), dp(6));
        back.setOnClickListener(v -> {
            screen2.setVisibility(View.GONE);
            screen1.setVisibility(View.VISIBLE);
        });
        page.addView(back, wrap());
        screen2Title = text(modeTitle(), 22, 0xFFF3F7FC, true);
        screen2Title.setPadding(dp(4), dp(2), dp(4), dp(4));
        page.addView(screen2Title, wrap());
        TextView subtitle = text("完成必选项即可使用", 13, 0xFFADABF2, false);
        subtitle.setPadding(dp(4), dp(2), dp(4), dp(4));
        page.addView(subtitle, wrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        permissionList = new LinearLayout(this);
        permissionList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(permissionList, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.BOTTOM);
        finishButton = button("下一步", true);
        finishButton.setOnClickListener(v -> {
            screen2.setVisibility(View.GONE);
            screen3.setVisibility(View.VISIBLE);
            startBindingRefresh();
            refreshBindingStatus();
        });
        bottom.addView(finishButton, new LinearLayout.LayoutParams(-1, dp(48)));
        page.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        return page;
    }

    private String modeTitle() {
        return "wesing".equals(mode) ? "全民K歌 · 权限与后台" : "媒体播放器 · 权限与后台";
    }

    /** Rebuilds the checklist; called on resume and after every settings round-trip. */
    private void refreshChecklist() {
        if (permissionList == null) return;
        permissionList.removeAllViews();
        if (screen2Title != null) screen2Title.setText(modeTitle());

        if ("media".equals(mode)) {
            addCheckRow("音乐读取权限（通知使用权）", hasNotificationAccess(),
                    "必选 · 识别正在播放的歌曲", v -> openNotificationAccess());
        } else {
            TextView watcher = text("电脑端启动方式：手机连电脑，在仓库目录运行\n"
                    + "node wesing_watcher.js --bar 门店号", 12, 0xFFADABF2, false);
            watcher.setLineSpacing(0f, 1.25f);
            watcher.setPadding(dp(4), dp(8), dp(4), dp(4));
            permissionList.addView(watcher, wrap());
        }

        addCheckRow("后台不受限（电池白名单）", isIgnoringBatteryOptimizations(),
                "必选 · 防止后台冻结断开同步", v -> requestBatteryWhitelist());

        if ("media".equals(mode)) {
            if (Build.VERSION.SDK_INT >= 33) {
                addCheckRow("通知显示权限", postNotificationsGranted(),
                        "必选 · 前台服务常驻可见",
                        v -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101));
            }
            if (needsVendorAutostart()) {
                addConfirmRow("开机自启动（厂商后台管理）", autostartConfirmed,
                        "必选 · 系统无查询接口，允许自启动/后台运行后请手动确认",
                        v -> openVendorAutostart(),
                        v -> {
                            autostartConfirmed = true;
                            refreshChecklist();
                        });
            }
        }

        finishButton.setText(requiredDone() ? "下一步" : "完成必选项后继续");
        finishButton.setEnabled(requiredDone());
        finishButton.setAlpha(requiredDone() ? 1f : 0.45f);
    }

    private boolean requiredDone() {
        if (!isIgnoringBatteryOptimizations()) return false;
        if (!"media".equals(mode)) return true;
        if (!hasNotificationAccess()) return false;
        if (Build.VERSION.SDK_INT >= 33 && !postNotificationsGranted()) return false;
        return !needsVendorAutostart() || autostartConfirmed;
    }

    private boolean postNotificationsGranted() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Most Chinese OEMs layer a proprietary autostart / background manager on top of the
     * platform. There is no query API on any of them, so on these brands the guide shows
     * a manual-confirm step that deep-links into each vendor's manager.
     */
    private boolean needsVendorAutostart() {
        String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase(java.util.Locale.ROOT);
        String fingerprint = Build.FINGERPRINT == null ? ""
                : Build.FINGERPRINT.toLowerCase(java.util.Locale.ROOT);
        return brand.contains("xiaomi") || brand.contains("redmi")
                || brand.contains("huawei") || brand.contains("honor")
                || brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus")
                || brand.contains("vivo") || brand.contains("iqoo") || brand.contains("meizu")
                || fingerprint.contains("miui") || fingerprint.contains("coloros")
                || fingerprint.contains("funtouch") || fingerprint.contains("emui");
    }

    private void openVendorAutostart() {
        Intent[] candidates = {
                new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                new Intent().setComponent(new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                new Intent().setComponent(new ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                new Intent().setComponent(new ComponentName("com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity")),
                new Intent().setComponent(new ComponentName("com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity")),
                new Intent().setComponent(new ComponentName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                new Intent().setComponent(new ComponentName("com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
                new Intent().setComponent(new ComponentName("com.meizu.safe",
                        "com.meizu.safe.permission.SmartBGActivity")),
        };
        for (Intent intent : candidates) {
            if (startSettingsActivity(intent)) return;
        }
        openApplicationDetails();
    }

    private void openApplicationDetails() {
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        if (!startSettingsActivity(details)) {
            startSettingsActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    /** A checklist row whose done-state is a manual user confirmation (no API exists). */
    private void addConfirmRow(String label, boolean done, String hint,
                               View.OnClickListener settingsAction, View.OnClickListener confirmAction) {
        addCheckRow(label, done, hint, settingsAction,
                done ? null : "我已开启", confirmAction);
    }

    private void addCheckRow(String label, boolean done, String hint,
                             View.OnClickListener action) {
        addCheckRow(label, done, hint, action, null, null);
    }

    private void addCheckRow(String label, boolean done, String hint,
                             View.OnClickListener action, String secondLabel,
                             View.OnClickListener secondAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView status = text(done ? "✓" : "!", 16, done ? 0xFF7ADB9B : 0xFFF4E230, true);
        status.setPadding(0, 0, dp(10), 0);
        row.addView(status, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(label, 15, 0xFFF3F7FC, true);
        texts.addView(name, wrap());
        TextView detail = text(hint, 12, 0xFF8B90B8, false);
        detail.setLineSpacing(0f, 1.2f);
        detail.setPadding(0, dp(2), 0, 0);
        texts.addView(detail, wrap());
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout actions = new LinearLayout(this);
        MaterialButton go = button(done ? "已就绪" : "去设置", !done);
        go.setEnabled(!done);
        go.setOnClickListener(action);
        actions.addView(go, new LinearLayout.LayoutParams(-2, dp(44)));
        if (secondLabel != null && secondAction != null) {
            MaterialButton second = button(secondLabel, false);
            second.setOnClickListener(secondAction);
            LinearLayout.LayoutParams secondParams =
                    new LinearLayout.LayoutParams(-2, dp(44));
            secondParams.leftMargin = dp(8);
            actions.addView(second, secondParams);
        }
        row.addView(actions, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout card = new LinearLayout(this);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        MaterialShapeDrawable surface = new MaterialShapeDrawable();
        surface.setFillColor(ColorStateList.valueOf(0xFF1C1D2C));
        surface.setCornerSize(dp(20));
        card.setBackground(surface);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        permissionList.addView(card, params);
    }

    private void finishOnboarding() {
        AppPreferences.setOnboardingDone(this);
        AppLog.i("Onboarding", "completed (mode=" + mode + ", requiredDone=" + requiredDone() + ")");
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    // ---- settings plumbing (mirrors MainActivity) --------------------------------

    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (enabled == null) return false;
        ComponentName expected = new ComponentName(this, MusicNotificationListener.class);
        for (String entry : enabled.split(":")) {
            if (expected.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    private void openNotificationAccess() {
        if (startSettingsActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))) return;
        Intent kitKat = new Intent().setComponent(new ComponentName(
                "com.android.settings", "com.android.settings.Settings$NotificationAccessSettingsActivity"));
        if (startSettingsActivity(kitKat)) return;
        if (startSettingsActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS))) return;
        startSettingsActivity(new Intent(Settings.ACTION_SETTINGS));
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
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Throwable error) {
                        startSettingsActivity(new Intent(
                                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < 23) return true;
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    // ---- small view helpers (same palette as MainActivity) ------------------------

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        // Keep the content below the status bar; the theme draws edge-to-edge.
        page.setPadding(pad, statusBarHeight() + dp(8), pad, dp(16));
        return page;
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(28);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return view;
    }

    @SuppressWarnings("SameParameterValue")
    private MaterialButton button(String value, boolean primary) {
        MaterialButton button = new MaterialButton(this);
        button.setText(value);
        button.setTextSize(13f);
        button.setTextColor(primary ? 0xFFFFFFFF : 0xFFF1F5FA);
        button.setAllCaps(false);
        button.setCornerRadius(dp(15));
        button.setBackgroundTintList(ColorStateList.valueOf(
                primary ? 0xFFF40861 : 0xFF2A2C44));
        return button;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
