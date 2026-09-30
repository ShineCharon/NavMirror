package com.colin.navmirror;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_MEDIA_PROJECTION = 1001;
    private static final int REQ_VPN = 1002;

    private static final String PREFS = "colin_proxy";
    private static final String KEY_MANUAL_OFF = "proxy_manual_off";
    private static final String KEY_QUALITY = "quality";
    private static final String KEY_JPEG_MODE = "jpeg_mode";
    private static final String KEY_REMOTE_WAS_READY = "remote_was_ready";

    private boolean remoteWarned = false;

    private static final int COLOR_BG = 0xFF070C14;
    private static final int COLOR_CARD = 0xFF111A28;
    private static final int COLOR_BORDER = 0xFF25334A;
    private static final int COLOR_ACCENT = 0xFF5DE3F3;
    private static final int COLOR_PRIMARY = 0xFFF2F7FC;
    private static final int COLOR_SECONDARY = 0xFF91A0B8;
    private static final int COLOR_SUCCESS = 0xFF67E8A8;

    private TextView teslaView;
    private TextView certView;
    private TextView clockView;
    private TextView proxyStatusView;
    private Button vpnBtn;
    private Button remoteBtn;
    private Button qualityBtn1080_45;
    private Button qualityBtn720;
    /** 右上角双徽章 [JPEG][H.264]：点选投屏模式，重新投屏后生效。 */
    private TextView codecBadgeJpeg;
    private TextView codecBadgeH264;
    /** 道具授权框去重：REAUTH 时 onResume 与广播都会触发 startStream，2s 内只弹一次 */
    private long lastStreamReqTs = 0;
    /** 主界面是否可见（锁屏/后台时避免误自动弹授权框） */
    private static volatile boolean uiVisible;

    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            // 真实状态：服务 capture 就绪才显示"投屏中"，断流/失效立即如实显示，杜绝误报（不显示时间）
            if (NavMirrorService.streaming) {
                clockView.setText("● 正在投屏");
                clockView.setTextColor(COLOR_SUCCESS);
            } else {
                clockView.setText("● 等待投屏");
                clockView.setTextColor(COLOR_SECONDARY);
            }
            updateProxyStatus(); // 每 250ms 刷新，确保 VpnService 异步建立后状态跟上
            updateRemoteControlStatus();
            clockHandler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }
        buildUi();
        maybeAutoEnableProxy();
        // 从通知「恢复投屏」冷启动：直接弹授权（launchMode=singleTask + NEW_TASK 冷启时走 onCreate）
        if (NavMirrorService.ACTION_RESTORE.equals(getIntent().getAction())) {
            Log.i("NavMirror", "restore requested (cold start)");
            NavMirrorService.reauthRequested = false;
            clockHandler.postDelayed(this::startStream, 500);
        }
    }

    private final BroadcastReceiver reauthReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 只提示，不自动弹授权框（由用户手动点「开始投屏」/通知「恢复投屏」恢复）
            Toast.makeText(MainActivity.this, "投屏已失效：请点「开始投屏」重新投屏", Toast.LENGTH_LONG).show();
        }
    };

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // API <33 has no receiver-flags overload.
    protected void onResume() {
        super.onResume();
        uiVisible = true;
        refreshStatus();
        clockHandler.post(clockTick);
        updateProxyStatus();
        updateRemoteControlStatus();
        warnIfRemoteControlDropped();
        try {
            IntentFilter filter = new IntentFilter(NavMirrorService.ACTION_NEED_REAUTH);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(reauthReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(reauthReceiver, filter);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        uiVisible = false;
        clockHandler.removeCallbacks(clockTick);
        try { unregisterReceiver(reauthReceiver); } catch (Exception ignored) {}
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 通知「恢复投屏」= 用户手动触发，才弹授权
        if (NavMirrorService.ACTION_RESTORE.equals(intent.getAction())) {
            Log.i("NavMirror", "restore requested from notification");
            NavMirrorService.reauthRequested = false;
            Toast.makeText(this, "正在恢复投屏…", Toast.LENGTH_SHORT).show();
            clockHandler.postDelayed(this::startStream, 300);
            return;
        }
        if (NavMirrorService.reauthRequested) {
            NavMirrorService.reauthRequested = false;
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static GradientDrawable rounded(int radiusPx, int fill, int strokePx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radiusPx);
        if (fill != 0) g.setColor(fill);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    private static GradientDrawable gradient(int radiusPx, int from, int to) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{from, to});
        g.setCornerRadius(radiusPx);
        return g;
    }

    private TextView mkText(String text, int sizeSp, int color, boolean bold, int gravity) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setGravity(gravity);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout mkCard(android.view.View child) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(13), dp(15), dp(13));
        card.setBackground(rounded(dp(18), COLOR_CARD, dp(1), COLOR_BORDER));
        if (child != null) card.addView(child);
        return card;
    }

    private String teslaUrl() {
        return "https://" + NavMirrorService.TESLA_DOMAIN
                + (NavMirrorService.TESLA_PORT == 443 ? "" : ":" + NavMirrorService.TESLA_PORT) + "/";
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pageBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF070C14, 0xFF0B1421});
        root.setBackground(pageBg);
        root.setPadding(dp(18), dp(14), dp(18), dp(14));

        // 品牌头部
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(com.colin.navmirror.R.drawable.navmirror_icon_foreground);
        logo.setBackground(rounded(dp(16), 0xFFFFFFFF, dp(1), 0xFFD4E2EF));
        logo.setPadding(dp(2), dp(2), dp(2), dp(2));
        brand.addView(logo, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout brandCopy = new LinearLayout(this);
        brandCopy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams brandCopyLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        brandCopyLp.setMargins(dp(12), 0, 0, 0);
        TextView title = mkText("NavMirror", 23, COLOR_PRIMARY, true, Gravity.START);
        brandCopy.addView(title);
        brandCopy.addView(mkText("导航投屏 · 为车机而生", 12, COLOR_SECONDARY, false, Gravity.START),
                lp(0, dp(2), 0, 0));
        brand.addView(brandCopy, brandCopyLp);
        // 右上角双徽章：JPEG 在左、H.264 在右；选中高亮，未选置灰，点选即切换
        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.setGravity(Gravity.CENTER_VERTICAL);
        codecBadgeJpeg = mkCodecBadge("JPEG");
        codecBadgeH264 = mkCodecBadge("H.264");
        codecBadgeJpeg.setOnClickListener(v -> selectJpegMode(true));
        codecBadgeH264.setOnClickListener(v -> selectJpegMode(false));
        badges.addView(codecBadgeJpeg);
        LinearLayout.LayoutParams badgeGap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeGap.setMargins(dp(6), 0, 0, 0);
        badges.addView(codecBadgeH264, badgeGap);
        brand.addView(badges);
        updateCodecBadge();
        root.addView(brand, lp(0, 0, 0, dp(12)));

        // 核心状态与主操作
        LinearLayout hero = mkCard(null);
        hero.setPadding(dp(16), dp(14), dp(16), dp(14));
        hero.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setOrientation(LinearLayout.HORIZONTAL);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);
        heroTop.addView(mkText("实时镜像", 12, COLOR_SECONDARY, true, Gravity.START),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        clockView = mkText("● 等待投屏", 12, COLOR_SECONDARY, true, Gravity.END);
        heroTop.addView(clockView);
        hero.addView(heroTop);
        hero.addView(mkText("把导航带到更大的屏幕", 19, COLOR_PRIMARY, true, Gravity.START),
                lp(0, dp(8), 0, 0));
        hero.addView(mkText("低延迟 H.264 · 自动适配 · 四季背景", 11,
                COLOR_SECONDARY, false, Gravity.START), lp(0, dp(3), 0, dp(11)));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button startBtn = new Button(this);
        startBtn.setText("开始投屏");
        startBtn.setTextSize(16);
        startBtn.setAllCaps(false);
        startBtn.setTypeface(Typeface.DEFAULT_BOLD);
        startBtn.setTextColor(0xFF05232D);
        startBtn.setMinHeight(0);
        startBtn.setBackground(gradient(dp(18), 0xFF78ECF7, COLOR_ACCENT));
        startBtn.setOnClickListener(v -> startStream());
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(0, dp(46), 1.7f);
        startLp.setMargins(0, 0, dp(8), 0);
        actions.addView(startBtn, startLp);
        Button stopBtn = new Button(this);
        stopBtn.setText("停止");
        stopBtn.setTextSize(14);
        stopBtn.setAllCaps(false);
        stopBtn.setTextColor(COLOR_SECONDARY);
        stopBtn.setMinHeight(0);
        stopBtn.setBackground(rounded(dp(18), 0xFF0D1521, dp(1), COLOR_BORDER));
        stopBtn.setOnClickListener(v -> startService(
                new Intent(this, NavMirrorService.class).setAction(NavMirrorService.ACTION_STOP)));
        actions.addView(stopBtn, new LinearLayout.LayoutParams(0, dp(46), 0.8f));
        hero.addView(actions);
        root.addView(hero, lpWeight(2.5f, 0, 0, 0, dp(8)));

        // 两档画质
        LinearLayout qCard = mkCard(null);
        qCard.setPadding(dp(14), dp(11), dp(14), dp(11));
        qCard.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout qHeader = new LinearLayout(this);
        qHeader.setOrientation(LinearLayout.HORIZONTAL);
        qHeader.setGravity(Gravity.CENTER_VERTICAL);
        qHeader.addView(mkText("画质模式", 14, COLOR_PRIMARY, true, Gravity.START),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        qHeader.addView(mkText("重新投屏后生效", 11, COLOR_SECONDARY, false, Gravity.END));
        qCard.addView(qHeader);
        LinearLayout qRow = new LinearLayout(this);
        qRow.setOrientation(LinearLayout.HORIZONTAL);
        qualityBtn1080_45 = mkQualityButton("均衡推荐\n1080P · 30FPS", NavMirrorService.QUALITY_1080P45);
        qualityBtn720 = mkQualityButton("高帧流畅\n720P · 60FPS", NavMirrorService.QUALITY_720P60);
        LinearLayout.LayoutParams qLeft = new LinearLayout.LayoutParams(0, dp(60), 1f);
        qLeft.setMargins(0, 0, dp(8), 0);
        qRow.addView(qualityBtn1080_45, qLeft);
        qRow.addView(qualityBtn720, new LinearLayout.LayoutParams(0, dp(60), 1f));
        qCard.addView(qRow, lp(0, dp(8), 0, 0));
        updateQualityHighlight();
        root.addView(qCard, lpWeight(0.4f, 0, 0, 0, dp(8)));

        // 车机地址
        LinearLayout addrCard = mkCard(null);
        addrCard.setPadding(dp(14), dp(11), dp(14), dp(11));
        addrCard.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout addrHeader = new LinearLayout(this);
        addrHeader.setOrientation(LinearLayout.HORIZONTAL);
        addrHeader.setGravity(Gravity.CENTER_VERTICAL);
        addrHeader.addView(mkText("车机浏览器地址", 13, COLOR_PRIMARY, true, Gravity.START),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button copyBtn = new Button(this);
        copyBtn.setText("复制");
        copyBtn.setTextSize(12);
        copyBtn.setAllCaps(false);
        copyBtn.setTextColor(COLOR_ACCENT);
        copyBtn.setMinWidth(0);
        copyBtn.setMinHeight(0);
        copyBtn.setPadding(dp(12), 0, dp(12), 0);
        copyBtn.setBackground(rounded(dp(14), 0xFF102838, dp(1), 0xFF28566A));
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("NavMirror", teslaUrl()));
            Toast.makeText(this, "车机地址已复制", Toast.LENGTH_SHORT).show();
        });
        addrHeader.addView(copyBtn, new LinearLayout.LayoutParams(dp(62), dp(32)));
        addrCard.addView(addrHeader);
        teslaView = mkText(teslaUrl(), 16, COLOR_ACCENT, true, Gravity.START);
        addrCard.addView(teslaView, lp(0, dp(7), 0, 0));
        String until = NavMirrorService.certExpiry(this);
        if (until != null) {
            certView = mkText("安全证书有效期至 " + until, 11, COLOR_SECONDARY, false, Gravity.START);
            addrCard.addView(certView, lp(0, dp(3), 0, 0));
        }
        TextView quickGuide = mkText("连接热点  →  选择导航  →  车机打开地址", 10,
                COLOR_SECONDARY, false, Gravity.START);
        addrCard.addView(quickGuide, lp(0, dp(6), 0, 0));
        root.addView(addrCard, lpWeight(1f, 0, 0, 0, dp(8)));

        // 代理设置
        LinearLayout proxyCard = mkCard(null);
        proxyCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout proxyRow = new LinearLayout(this);
        proxyRow.setOrientation(LinearLayout.HORIZONTAL);
        proxyRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout proxyCopy = new LinearLayout(this);
        proxyCopy.setOrientation(LinearLayout.VERTICAL);
        proxyCopy.addView(mkText("车机连接与反控", 13, COLOR_PRIMARY, true, Gravity.START));
        proxyStatusView = mkText("", 11, COLOR_SECONDARY, false, Gravity.START);
        proxyCopy.addView(proxyStatusView, lp(0, dp(3), 0, 0));
        proxyRow.addView(proxyCopy, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        vpnBtn = new Button(this);
        vpnBtn.setTextSize(12);
        vpnBtn.setAllCaps(false);
        vpnBtn.setTextColor(COLOR_PRIMARY);
        vpnBtn.setMinWidth(0);
        vpnBtn.setMinHeight(0);
        vpnBtn.setBackground(rounded(dp(15), 0xFF18253A, dp(1), COLOR_BORDER));
        vpnBtn.setOnClickListener(v -> toggleVpn());
        LinearLayout.LayoutParams vpnLp = new LinearLayout.LayoutParams(dp(78), dp(36));
        vpnLp.setMargins(0, 0, dp(6), 0);
        proxyRow.addView(vpnBtn, vpnLp);
        remoteBtn = new Button(this);
        remoteBtn.setTextSize(12);
        remoteBtn.setAllCaps(false);
        remoteBtn.setTextColor(COLOR_PRIMARY);
        remoteBtn.setMinWidth(0);
        remoteBtn.setMinHeight(0);
        remoteBtn.setBackground(rounded(dp(15), 0xFF18253A, dp(1), COLOR_BORDER));
        remoteBtn.setOnClickListener(v -> {
            // 完整重绑（等效设置页开关，含 Crashed 残留清理；阻塞 ~1.2s，后台线程执行）
            new Thread(() -> {
                boolean wrote = RemoteControlService.heal(MainActivity.this);
                clockHandler.postDelayed(() -> {
                    updateRemoteControlStatus();
                    if (!wrote || !RemoteControlService.isReady()) openAccessibilitySettings();
                }, wrote ? 800 : 0);
            }, "navmirror-a11y-btn").start();
        });
        proxyRow.addView(remoteBtn, new LinearLayout.LayoutParams(dp(78), dp(36)));
        proxyCard.addView(proxyRow);
        proxyCard.addView(mkText("代理解决访问限制；反控需在系统设置中手动授权", 10,
                COLOR_SECONDARY, false, Gravity.START), lp(0, dp(5), 0, 0));
        root.addView(proxyCard);

        // 将剩余高度留成呼吸空间，不再机械地平均拉伸四张卡片。
        android.widget.Space breathingRoom = new android.widget.Space(this);
        root.addView(breathingRoom, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 4f));

        TextView footer = mkText("NavMirror  ·  Secure local streaming", 10,
                COLOR_SECONDARY, false, Gravity.CENTER);
        footer.setAlpha(0.65f);
        root.addView(footer, lp(0, dp(10), 0, 0));

        // 根布局放入 ScrollView：小屏/内容变多时可滚动，确保停止/开始按钮永远可及
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
        refreshStatus();
    }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    private LinearLayout.LayoutParams lpWeight(float weight, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        p.setMargins(l, t, r, b);
        return p;
    }

    private int savedQuality() {
        int saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_QUALITY, NavMirrorService.QUALITY_1080P45);
        // Migrate the removed 1080P30 value (0) and any unknown value to 1080P45.
        return saved == NavMirrorService.QUALITY_720P60
                ? NavMirrorService.QUALITY_720P60 : NavMirrorService.QUALITY_1080P45;
    }

    private Button mkQualityButton(String label, int quality) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(false);
        b.setMaxLines(2);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(3), 0, dp(3), 0);
        b.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_QUALITY, quality).apply();
            updateQualityHighlight();
            Toast.makeText(this, "已选择 " + label.replace('\n', ' ')
                    + "（重新开始投屏后生效）", Toast.LENGTH_SHORT).show();
        });
        return b;
    }

    private boolean savedJpegMode() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_JPEG_MODE, false);
    }

    private void selectJpegMode(boolean jpeg) {
        if (jpeg == savedJpegMode()) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_JPEG_MODE, jpeg).apply();
        updateCodecBadge();
        Toast.makeText(this, jpeg
                ? "已选 图像 JPEG（老方案·重新开始投屏后生效）"
                : "已选 H.264 低延迟（重新开始投屏后生效）", Toast.LENGTH_SHORT).show();
    }

    private TextView mkCodecBadge(String label) {
        TextView badge = mkText(label, 11, COLOR_SECONDARY, true, Gravity.CENTER);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        return badge;
    }

    /** 右上角双徽章：选中项高亮（H.264 青 / JPEG 琥珀），未选置灰。 */
    private void updateCodecBadge() {
        boolean jpeg = savedJpegMode();
        if (codecBadgeJpeg != null) {
            if (jpeg) {
                codecBadgeJpeg.setTextColor(0xFFFFC46B);
                codecBadgeJpeg.setBackground(rounded(dp(20), 0xFF3A2410, dp(1), 0xFF6E4A1E));
            } else {
                codecBadgeJpeg.setTextColor(COLOR_SECONDARY);
                codecBadgeJpeg.setBackground(rounded(dp(20), 0xFF0D1521, dp(1), COLOR_BORDER));
            }
        }
        if (codecBadgeH264 != null) {
            if (!jpeg) {
                codecBadgeH264.setTextColor(COLOR_ACCENT);
                codecBadgeH264.setBackground(rounded(dp(20), 0xFF112C3C, dp(1), 0xFF28566A));
            } else {
                codecBadgeH264.setTextColor(COLOR_SECONDARY);
                codecBadgeH264.setBackground(rounded(dp(20), 0xFF0D1521, dp(1), COLOR_BORDER));
            }
        }
    }

    private void updateQualityHighlight() {
        int q = savedQuality();
        styleQualityButton(qualityBtn1080_45, q == NavMirrorService.QUALITY_1080P45);
        styleQualityButton(qualityBtn720, q == NavMirrorService.QUALITY_720P60);
    }

    private void styleQualityButton(Button b, boolean selected) {
        if (b == null) return;
        if (selected) {
            b.setTextColor(0xFF062A33);
            b.setBackground(gradient(dp(20), 0xFF4FE3F7, COLOR_ACCENT));
        } else {
            b.setTextColor(COLOR_SECONDARY);
            b.setBackground(rounded(dp(20), 0xFF1B2C4F, dp(1), 0xFF3B5B8F));
        }
    }

    /** 打开应用时默认开启特斯拉代理：已授权直接开；未授权首次自动弹授权框；手动关闭过则不再自动开 */
    private void maybeAutoEnableProxy() {
        if (TeslaVpnService.isRunning()) return;
        // 用户手动关闭过 -> 尊重，保持关闭
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_MANUAL_OFF, false)) return;
        try {
            Intent prepare = VpnService.prepare(this);
            if (prepare != null) {
                // 未授权：自动请求（用户点允许即开启；拒绝则仅提示）
                startActivityForResult(prepare, REQ_VPN);
            } else {
                // 已授权过，直接启动
                startVpnServiceAuto();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法开启代理: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 特斯拉代理开关：VpnService 授权 -> 启动/停止 TeslaVpnService */
    private void toggleVpn() {
        if (TeslaVpnService.isRunning()) {
            TeslaVpnService.stop(this);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_MANUAL_OFF, true).apply();
            updateProxyStatus();
            Toast.makeText(this, "特斯拉代理已关闭", Toast.LENGTH_SHORT).show();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_MANUAL_OFF, false).apply();
        try {
            Intent prepare = VpnService.prepare(this);
            if (prepare != null) {
                startActivityForResult(prepare, REQ_VPN);
            } else {
                // 已授权过，直接启动
                startVpnService();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法开启代理: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startVpnServiceAuto() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_MANUAL_OFF, false).apply();
        startVpnService();
    }

    private void startVpnService() {
        TeslaVpnService.start(this);
        updateProxyStatus();
        Toast.makeText(this, "特斯拉代理已开启", Toast.LENGTH_SHORT).show();
    }

    private void updateProxyStatus() {
        boolean running = TeslaVpnService.isRunning();
        if (vpnBtn != null) {
            vpnBtn.setText(running ? "关闭代理" : "开启代理");
        }
        if (proxyStatusView != null) {
            if (running) {
                proxyStatusView.setText("● 已开启");
                proxyStatusView.setTextColor(0xFF4ADE80);
            } else {
                proxyStatusView.setText("● 已关闭");
                proxyStatusView.setTextColor(COLOR_SECONDARY);
            }
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "请找到“NavMirror 反控”并手动开启", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开无障碍设置: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateRemoteControlStatus() {
        if (remoteBtn == null) return;
        boolean ready = RemoteControlService.isReady();
        // 反控一旦成功开启过就记下来：HyperOS 在强停/应用更新后会静默关闭无障碍服务，
        // 之后 RESTORE/重装都不再提醒——用户只会在车机上看到"反控不可用"却不知道要去哪里开。
        if (ready) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_REMOTE_WAS_READY, true).apply();
        remoteBtn.setText(ready ? "反控已开" : "开启反控");
        remoteBtn.setTextColor(ready ? 0xFF062A33 : COLOR_PRIMARY);
        remoteBtn.setBackground(ready
                ? gradient(dp(15), 0xFF67E8A8, 0xFF5DE3F3)
                : rounded(dp(15), 0xFF18253A, dp(1), COLOR_BORDER));
    }

    private void warnIfRemoteControlDropped() {
        if (RemoteControlService.isReady()) return;
        // 后台完整重绑自愈（无授权时 heal 返回 false，再按持久化标记提示手动开启）
        new Thread(() -> {
            boolean wrote = RemoteControlService.heal(MainActivity.this);
            if (wrote) {
                clockHandler.postDelayed(MainActivity.this::updateRemoteControlStatus, 800);
                return;
            }
            if (!getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_REMOTE_WAS_READY, false)
                    || remoteWarned) return;
            remoteWarned = true;
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "反控服务已被系统关闭（强停/更新后会自动关闭），点「开启反控」重新授权；"
                            + "或连接电脑执行一次: adb shell pm grant com.colin.navmirror "
                            + "android.permission.WRITE_SECURE_SETTINGS 实现永久默认开启",
                    Toast.LENGTH_LONG).show());
        }, "navmirror-a11y-warn").start();
    }

    private void startStream() {
        // REAUTH 流程可能同时从 onResume / onNewIntent / 广播触发，5s 内合并为一次，避免弹出多个授权框互相取消
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastStreamReqTs < 5000) { Log.i("NavMirror", "skip duplicate stream request"); return; }
        lastStreamReqTs = now;
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.i("NavMirror", "onActivityResult req=" + requestCode + " result=" + resultCode
                + " data=" + (data != null ? "present" : "null"));
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Intent svc = new Intent(this, NavMirrorService.class);
                svc.putExtra(NavMirrorService.EXTRA_RESULT_CODE, resultCode);
                svc.putExtra(NavMirrorService.EXTRA_RESULT_DATA, data);
                svc.putExtra(NavMirrorService.EXTRA_QUALITY, savedQuality());
                svc.putExtra(NavMirrorService.EXTRA_JPEG_MODE, savedJpegMode());
                startForegroundService(svc);
                refreshStatus();
                Toast.makeText(this, "NavMirror 正在工作", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "未完成屏幕共享授权，投屏未启动。请重新点「开始投屏」", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startVpnService();
            } else {
                Toast.makeText(this, "未允许 VPN 授权，特斯拉代理未开启", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void refreshStatus() {
        if (teslaView != null) {
            teslaView.setText(teslaUrl());
        }
    }
}
