package com.colin.navmirror;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

/**
 * 特斯拉车机访问代理（非 root）：
 *
 * 问题：特斯拉车机浏览器拒绝访问 RFC1918 私有网段（10.x / 192.168.x / 172.16-31.x），
 *       域名解析到手机热点私有 IP 时，浏览器直接报"无法访问此网站"。
 *
 * 本项目的访问方式：
 *   1. 域名的公开 A 记录需指向 CGNAT 地址 100.99.88.77（100.64.0.0/10 共享地址段，
 *      不属于 RFC1918 私网，不触发浏览器拦截）。
 *   2. VpnService 建立双 TUN：
 *        tun0 = 100.99.88.77/32  (车机访问 HTTPS 的地址)
 *        tun1 = 192.168.229.229/32 (辅助隧道)
 *      并 allowBypass()，其余流量直连，不影响手机自身联网。
 *   3. 车机访问 https://域名:TESLA_PORT 时，数据包到达手机后由内核经 local table
 *      本地投递给监听 0.0.0.0:TESLA_PORT 的 HTTPS 服务器，证书域名匹配即出画面。
 *      无需读取 TUN 并自行转发数据，也无需 root。
 */
public class TeslaVpnService extends VpnService {
    private static final String TAG = "NavMirror";

    /** CGNAT 段地址：不属于 RFC1918 私网，供车机浏览器访问。 */
    public static final String VPN_IP = "100.99.88.77";
    private static final int VPN_PREFIX = 32;
    /** 辅助隧道地址。 */
    private static final String TUN2_IP = "192.168.229.229";
    private static final String SESSION_NAME = "Colin投屏";
    private static final String SESSION_NAME_TUN2 = "Colin投屏-辅助隧道";

    private ParcelFileDescriptor tunFd;   // tun0 = 100.99.88.77
    private ParcelFileDescriptor tunFd2;  // tun1 = 192.168.229.229

    /** 静态实例引用：VpnService 被系统 bind，stopService() 不触发 onDestroy，
     *  必须通过实例直接关闭 fd 才能撤销 TUN */
    private static volatile TeslaVpnService sInstance;

    public static boolean isRunning() {
        return "running".equals(instanceState);
    }

    private static volatile String instanceState = "stopped";

    public static void start(Context ctx) {
        ctx.startService(new Intent(ctx, TeslaVpnService.class));
    }

    public static void stop(Context ctx) {
        // 关键：VpnService 由系统 bind，stopService() 不会触发 onDestroy；
        // 直接关闭 TUN fd，系统检测到 fd 关闭即撤销 VPN 并结束代理
        TeslaVpnService svc = sInstance;
        if (svc != null) {
            svc.closeFds();
        }
        instanceState = "stopped";
        try { ctx.stopService(new Intent(ctx, TeslaVpnService.class)); } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Log.i(TAG, "TeslaVpnService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "TeslaVpnService onStartCommand");
        if (!establishTunnels()) {
            instanceState = "failed";
            stopSelf();
            return START_NOT_STICKY;
        }
        instanceState = "running";
        return START_STICKY;
    }

    /** 建立双 TUN，并允许其他流量绕过 VPN。 */
    private boolean establishTunnels() {
        try {
            closeFds();
            // ---- tun0: 车机访问地址 100.99.88.77/32 ----
            VpnService.Builder b0 = new Builder()
                    .addAddress(VPN_IP, VPN_PREFIX)
                    .setMtu(1500)
                    .addRoute(VPN_IP, VPN_PREFIX)
                    .allowBypass()
                    .allowFamily(OsConstants.AF_INET)
                    .allowFamily(OsConstants.AF_INET6)
                    .setSession(SESSION_NAME);
            tunFd = b0.establish();
            // ---- tun1: 辅助隧道 192.168.229.229/32 ----
            VpnService.Builder b1 = new Builder()
                    .addAddress(TUN2_IP, VPN_PREFIX)
                    .setMtu(1500)
                    .addRoute(TUN2_IP, VPN_PREFIX)
                    .allowBypass()
                    .allowFamily(OsConstants.AF_INET)
                    .setSession(SESSION_NAME_TUN2);
            tunFd2 = b1.establish();

            if (tunFd == null && tunFd2 == null) {
                Log.e(TAG, "both establish() returned null (permission revoked?)");
                return false;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    Log.i(TAG, "VPN tunnels established: " + VPN_IP + "/" + VPN_PREFIX
                            + " + " + TUN2_IP + "/" + VPN_PREFIX
                            + " -> local HTTPS server on 0.0.0.0:" + NavMirrorService.TESLA_PORT);
                } catch (Throwable ignored) {}
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "establish tunnels failed: " + t.getMessage(), t);
            return false;
        }
    }

    private void closeFds() {
        ParcelFileDescriptor[] fds = {tunFd, tunFd2};
        for (int i = 0; i < fds.length; i++) {
            if (fds[i] != null) {
                try { fds[i].close(); } catch (Exception ignored) {}
            }
        }
        tunFd = null;
        tunFd2 = null;
    }

    @Override
    public void onRevoke() {
        Log.w(TAG, "VPN revoked by user/system");
        super.onRevoke();
        instanceState = "revoked";
    }

    @Override
    public void onDestroy() {
        instanceState = "stopped";
        closeFds();
        sInstance = null;
        Log.i(TAG, "TeslaVpnService destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
