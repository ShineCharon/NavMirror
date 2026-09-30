package com.colin.navmirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

public class NavMirrorService extends Service {
    private static final String TAG = "NavMirror";
    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "navmirror_stream";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String ACTION_STOP = "com.colin.navmirror.STOP";
    public static final String ACTION_NEED_REAUTH = "com.colin.navmirror.REAUTH";
    public static final String ACTION_RESTORE = "com.colin.navmirror.RESTORE";
    public static volatile boolean reauthRequested = false;
    /** 投屏真实运行标志：capture 就绪 + 流服务器启动后为 true，任何停止/失效即 false。
     *  UI 据此显示真实状态，避免"提示在投屏、实际已断"的误报 */
    public static volatile boolean streaming;
    /** 画质档位显示名 */
    public static String qualityLabel(int q) {
        if (q == QUALITY_720P60) return "720P 60FPS";
        return "1080P 30FPS";
    }
    /** 特斯拉模式证书域名（deSEC 免费子域，部署时 A 记录需指向 100.99.88.77）。 */
    public static final String TESLA_DOMAIN = BuildConfig.TESLA_DOMAIN;
    public static final int TESLA_PORT = 9999;
    /** 画质档位：1=720P60 2=1080P30(默认推荐，常量名沿用历史 ID) */
    public static final String EXTRA_QUALITY = "quality";
    public static final int QUALITY_720P60 = 1;
    public static final int QUALITY_1080P45 = 2;
    /** 投屏模式：true=JPEG 图像管线，false=H.264 视频管线；仅由手机端手动选择。 */
    public static final String EXTRA_JPEG_MODE = "jpegMode";

    private MediaProjection projection;
    private CaptureEngine capture;
    private StreamServer teslaServer;
    private PowerManager.WakeLock wakeLock;
    private int resultCode;
    private Intent resultData;
    private long lastReauthTs = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null) return START_NOT_STICKY;

        // Idempotent (re)start: tear down any previous instance before starting a new one
        stopAll();

        // 反控自愈放在服务路径（不依赖 MainActivity 可见——用户日常是通知栏「恢复投屏」，
        // 手机停在导航界面，Activity 可能从未到前台）。无条件完整重绑（等效设置页开关）：
        // install/force-stop 会把在绑服务标记 Crashed，仅写回列表会得到"status 正常但
        // 动作静默失效"的半开状态——必须摘除再写回。未授权（未 adb pm grant）时静默跳过。
        Thread a11yHeal = new Thread(() -> {
            boolean wrote = RemoteControlService.heal(this);
            Log.i(TAG, "remote control rebind " + (wrote ? "written" : "unavailable")
                    + ", ready=" + RemoteControlService.isReady());
        }, "navmirror-a11y-heal");
        a11yHeal.setDaemon(true);
        a11yHeal.start();

        resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        resultData = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_RESULT_DATA);
        int quality = intent.getIntExtra(EXTRA_QUALITY, QUALITY_1080P45);
        boolean jpegMode = intent.getBooleanExtra(EXTRA_JPEG_MODE, false);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NavMirror:stream");
            wakeLock.setReferenceCounted(false);
            // 6 小时超时兜底：异常生命周期下（服务泄漏未走 onDestroy）也自动释放，
            // 正常停止路径 stopAll() 会提前 release()。锁屏本身会使投影 token 失效，长会话必经重授权。
            wakeLock.acquire(6L * 60 * 60 * 1000);
        }

        // 特斯拉代理（VpnService 双 TUN）由 MainActivity 独立管理（App 打开时自动开，
        // 手动关闭后保持关闭）。投屏服务不启动/不停止它：
        // 1. 无条件 start 会覆盖用户"手动关闭"的意愿（README 承诺保持关闭）；
        // 2. 重启投屏不重建 TUN，车机已建立的连接不中断。
        // 用户划掉任务时在 onTaskRemoved 里用 TeslaVpnService.stop() 真正关闭 FD。

        startForegroundCompat("NavMirror 运行中（" + qualityLabel(quality)
                + (jpegMode ? " · JPEG" : " · H.264") + "）  https://"
                + TESLA_DOMAIN + ":" + TESLA_PORT + "/");

        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null || resultData == null || resultCode == 0) {
            requestReauth(new IllegalStateException("missing screen-capture authorization"));
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            projection = mgr.getMediaProjection(resultCode, resultData);
            capture = new CaptureEngine(this, projection, quality, jpegMode);
            capture.setCaptureFailureListener(this::requestReauth);
            capture.start();
        } catch (Exception e) {
            Log.e(TAG, "start failed: " + e.getMessage(), e);
            requestReauth(e);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            teslaServer = new StreamServer(TESLA_PORT, capture, buildTlsContext(this), this);
            teslaServer.start();
            streaming = true;
            Log.i(TAG, "HTTPS (Tesla) stream ready at https://" + TESLA_DOMAIN + ":" + TESLA_PORT);
        } catch (Exception e) {
            Log.e(TAG, "tesla https server failed", e);
            streaming = false;
            try { startForegroundCompat("投屏服务启动失败：请停止后重试"); } catch (Exception ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    /** 授权失效/重建失败：更新状态并广播提示，等待用户手动恢复。 */
    private void requestReauth(Throwable t) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastReauthTs < 45000) {
            Log.i(TAG, "re-auth already requested recently, skip");
            return;
        }
        lastReauthTs = now;
        reauthRequested = true;
        streaming = false;
        Log.e(TAG, "capture create failed: " + (t != null ? t.getMessage() : "null") + " -> stream invalid, wait manual restore");
        // 通知如实改为"失效"状态（含「恢复投屏」按钮），不自动弹授权框，等用户手动恢复
        try { startForegroundCompat("投屏已失效：点「恢复投屏」或打开 App 点「开始投屏」"); } catch (Exception ignored) {}
        try {
            Intent reauth = new Intent(ACTION_NEED_REAUTH);
            reauth.setPackage(getPackageName());
            sendBroadcast(reauth);
        } catch (Exception ignored) {}
    }

    /** 从 assets 证书解析有效期（yyyy-MM-dd），无真证书返回 null */
    public static String certExpiry(Context ctx) {
        String pem = readAsset(ctx, "tesla_cert.pem");
        if (pem == null) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "-----BEGIN CERTIFICATE-----\\s*(.*?)\\s*-----END CERTIFICATE-----",
                    java.util.regex.Pattern.DOTALL).matcher(pem);
            if (m.find()) {
                byte[] der = java.util.Base64.getMimeDecoder().decode(m.group(1));
                java.security.cert.X509Certificate c = (java.security.cert.X509Certificate)
                        java.security.cert.CertificateFactory.getInstance("X.509")
                                .generateCertificate(new java.io.ByteArrayInputStream(der));
                return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(c.getNotAfter());
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 供 Activity 判断投屏服务是否在运行（触发重授权脚本用） */
    public static boolean isRunning(Context ctx) {
        try {
            Class<?> c = Class.forName("android.app.ActivityManager");
            java.lang.reflect.Method m = c.getMethod("getRunningServices", int.class);
            java.lang.Object am = ctx.getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<?> list = (java.util.List<?>) m.invoke(am, Integer.MAX_VALUE);
            for (java.lang.Object s : list) {
                Class<?> sc = s.getClass();
                java.lang.reflect.Field f = sc.getField("service");
                android.content.ComponentName cn = (android.content.ComponentName) f.get(s);
                if (NavMirrorService.class.getName().equals(cn.getClassName())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void stopAll() {
        streaming = false;
        // 特斯拉代理不在投屏停止路径上（MainActivity 管理）；这里只收投屏自己的资源
        if (teslaServer != null) { teslaServer.stop(); teslaServer = null; }
        if (capture != null) { capture.stop(); capture = null; }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }

    /** 用户从最近任务划掉本应用（或一键清理后台）时，彻底停止投屏与代理，
     *  而不是让前台服务残留在后台继续采集屏幕（「屏幕共享中」图标随之熄灭）。
     *  代理必须用 TeslaVpnService.stop() 直接关 FD：VpnService 被系统 bind，
     *  stopService() 不触发 onDestroy，TUN 不会撤销。 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.i(TAG, "task removed -> stopping stream and tesla proxy");
        stopAll();
        try { TeslaVpnService.stop(this); } catch (Exception ignored) {}
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundCompat(String text) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID);
        // 通知栏「停止投屏」：应用内 PendingIntent -> ACTION_STOP。
        // 划掉/退出 App 界面后，前台服务仍在后台投屏，从这里可一键释放 MediaProjection（屏幕共享图标熄灭）
        Intent stopI = new Intent(this, NavMirrorService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // 通知栏「恢复投屏」：失效/授权流程被系统打断时，从通知一键回授权页（自动弹授权框）
        Intent restI = new Intent(this, MainActivity.class).setAction(ACTION_RESTORE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent restPi = PendingIntent.getActivity(this, 2, restI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = b.setContentTitle("NavMirror")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(0, "恢复投屏", restPi)
                .addAction(0, "停止投屏", stopPi)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "投屏服务", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    /** 从 assets 加载 TLS 证书：优先真证书 PEM（特斯拉模式免警告），自签 p12 兜底 */
    private static javax.net.ssl.SSLContext buildTlsContext(Context ctx) throws Exception {
        char[] pass = "colin123".toCharArray();
        String certPem = readAsset(ctx, "tesla_cert.pem");
        String keyPem = readAsset(ctx, "tesla_key.pem");
        if (certPem != null && keyPem != null) {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            java.util.List<java.security.cert.X509Certificate> chain = new java.util.ArrayList<>();
            for (byte[] der : pemBlocks(certPem)) {
                chain.add((java.security.cert.X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der)));
            }
            String caPem = readAsset(ctx, "tesla_ca.pem");
            if (caPem != null) {
                for (byte[] der : pemBlocks(caPem)) {
                    try { chain.add((java.security.cert.X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der))); }
                    catch (Exception ignored) {}
                }
            }
            byte[] keyDer = pemBlock(keyPem, "PRIVATE KEY");
            if (keyDer == null) keyDer = pemBlock(keyPem, "RSA PRIVATE KEY");
            java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(keyDer);
            java.security.PrivateKey key = null;
            try {
                key = java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec);
            } catch (Exception rsaE) {
                try {
                    key = java.security.KeyFactory.getInstance("EC").generatePrivate(keySpec);
                } catch (Exception ecE) {
                    throw new Exception("unsupported private key type: " + ecE.getMessage());
                }
            }
            ks.setKeyEntry("tesla", key, pass, chain.toArray(new java.security.cert.Certificate[0]));
            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                    javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pass);
            javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), null, null);
            return ssl;
        }
        // A source-only checkout intentionally contains no certificate or key.
        if (readAsset(ctx, "colinmirror.p12") == null) {
            throw new IllegalStateException("缺少 TLS 证书：请按 docs/SETUP.md 配置自己的域名和证书后重新构建");
        }
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = ctx.getAssets().open("colinmirror.p12")) {
            ks.load(in, pass);
        }
        javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        return ssl;
    }

    private static String readAsset(Context ctx, String name) {
        try (java.io.InputStream in = ctx.getAssets().open(name)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static java.util.List<byte[]> pemBlocks(String pem) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "-----BEGIN [A-Z0-9 ]+-----\\s*(.*?)\\s*-----END [A-Z0-9 ]+-----",
                java.util.regex.Pattern.DOTALL).matcher(pem);
        while (m.find()) {
            try { out.add(java.util.Base64.getMimeDecoder().decode(m.group(1))); }
            catch (Exception ignored) {}
        }
        return out;
    }

    private static byte[] pemBlock(String pem, String type) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "-----BEGIN " + type + "-----\\s*(.*?)\\s*-----END " + type + "-----",
                java.util.regex.Pattern.DOTALL).matcher(pem);
        if (m.find()) {
            try { return java.util.Base64.getMimeDecoder().decode(m.group(1)); }
            catch (Exception ignored) {}
        }
        return null;
    }
}
