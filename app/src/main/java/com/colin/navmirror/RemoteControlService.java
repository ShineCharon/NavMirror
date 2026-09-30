package com.colin.navmirror;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.List;

/** Executes explicitly enabled, normalized gestures received from the local NavMirror page. */
public final class RemoteControlService extends AccessibilityService {
    private static volatile RemoteControlService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static boolean isReady() {
        return instance != null;
    }

    // ==================== 实时拖拽注入（WSS 上行 DB/DM/DU/DC）====================
    // 设计要点（评审方案）：
    // - continueStroke 分段注入：段起点严格等于前段终点，前段 willContinue=true；
    //   直接连续 dispatchGesture 会互相取消（Android 官方语义）。
    // - 单飞背压：同一时间最多 1 个在飞 segment + 1 个待注入的最新目标；
    //   手机来不及时覆盖旧目标（dragDropped 计数），绝不排队回放旧轨迹。
    // - 看门狗 200ms 无新输入 / DC / 连接断开 → 自动结束手势（防触点永久按下）。
    // - 段时长 = 距上段派发间隔 × 0.8（60–140ms 钳位）：注入比真实拖动略快，
    //   让 150–250ms 的流延迟逐步收敛而不是持续放大。
    private static final Object DRAG_LOCK = new Object();
    private static DragSession dragSession;
    private static long dragSegments, dragCancelled, dragDropped;

    static boolean isDragActive() {
        synchronized (DRAG_LOCK) { return dragSession != null; }
    }

    /** 诊断计数（/info 暴露）：已注入段数 / 被系统取消段数 / 背压覆盖的旧目标数。 */
    static long dragSegments() { synchronized (DRAG_LOCK) { return dragSegments; } }
    static long dragCancelledSegments() { synchronized (DRAG_LOCK) { return dragCancelled; } }
    static long dragDroppedPoints() { synchronized (DRAG_LOCK) { return dragDropped; } }

    private static final class DragSession {
        final int width, height;          // 会话开始时的屏幕尺寸
        float lastSegX, lastSegY;         // 上一段注入终点（像素）——衔接约束
        GestureDescription.StrokeDescription lastStroke;   // continueStroke 链（断裂后置 null 重建）
        boolean executing;                // 单飞：一个 segment 在飞
        boolean hasPending;               // 有待注入最新点（覆盖式）
        float pendingX, pendingY;
        boolean ended;                    // 已收到 DU/看门狗触发：下一段为终止段（willContinue=false）
        long lastSegAt;                   // 上一段派发时刻（段时长依据）
        int stalledRounds;                // 回调丢失（实测断连场景会出现）：看门狗轮数

        DragSession(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    public static void dragBegin(float nx, float ny) {
        RemoteControlService s = instance;
        if (s == null) return;
        s.mainHandler.post(() -> s.handleDragBegin(nx, ny));
    }

    public static void dragMove(float nx, float ny) {
        RemoteControlService s = instance;
        if (s == null) return;
        s.mainHandler.post(() -> s.handleDragMove(nx, ny));
    }

    public static void dragEnd(float nx, float ny) {
        RemoteControlService s = instance;
        if (s == null) return;
        s.mainHandler.post(() -> s.handleDragEnd(nx, ny));
    }

    public static void dragCancel() {
        RemoteControlService s = instance;
        if (s == null) return;
        s.mainHandler.post(s::handleDragCancel);
    }

    private void handleDragBegin(float nx, float ny) {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        wm.getDefaultDisplay().getRealMetrics(metrics);
        synchronized (DRAG_LOCK) {
            if (dragSession != null) terminateLocked(dragSession);   // 新拖拽覆盖旧会话
            DragSession s = new DragSession(metrics.widthPixels, metrics.heightPixels);
            s.lastSegX = nx * (s.width - 1);
            s.lastSegY = ny * (s.height - 1);
            s.lastSegAt = SystemClock.uptimeMillis();
            dragSession = s;
            // 初始段：按下即注入（~90ms 原地保持，willContinue=true）——
            // 车机端立刻有响应，后续 DM 到达后由完成回调串行衔接。
            // 它同时会取消旧会话残留的在飞手势（官方语义），触点随即被系统抬起。
            dispatchSegmentLocked(s, s.lastSegX + 0.1f, s.lastSegY, 90L);
        }
        scheduleDragWatchdog();
    }

    private void handleDragMove(float nx, float ny) {
        synchronized (DRAG_LOCK) {
            DragSession s = dragSession;
            if (s == null || s.ended) return;   // 会话已结束：忽略迟到输入（勿"复活"触点）
            if (s.hasPending) dragDropped++;    // 背压：覆盖旧目标，不排队
            s.hasPending = true;
            s.pendingX = nx * (s.width - 1);
            s.pendingY = ny * (s.height - 1);
            if (!s.executing) dispatchNextLocked(s);
        }
        scheduleDragWatchdog();
    }

    private void handleDragEnd(float nx, float ny) {
        synchronized (DRAG_LOCK) {
            DragSession s = dragSession;
            if (s == null || s.ended) return;
            terminateLocked(s);
            if (!s.executing) dispatchNextLocked(s);
        }
        // 保持看门狗武装：终止段的回调同样可能丢失（断连场景），它是收尾兜底
        scheduleDragWatchdog();
    }

    private void handleDragCancel() {
        synchronized (DRAG_LOCK) {
            if (dragSession != null) terminateLocked(dragSession);
        }
        // 不能取消看门狗：在飞段的完成回调可能永远不回来（真机断连场景实测复现），
        // 看门狗是此泄漏的唯一兜底。
        scheduleDragWatchdog();
    }

    /**
     * 看门狗（会话存续期间常驻自续）：200ms 无新输入 → 进入终止态。
     * 终止态下若回调丢失（段在飞却迟迟不完成）：发一个独立终止手势强制抬起
     * （新手势会取消旧链——官方语义在此反而是正确工具），并清掉会话防泄漏。
     */
    private final Runnable dragWatchdog = () -> {
        synchronized (DRAG_LOCK) {
            DragSession s = dragSession;
            if (s == null) return;                      // 会话已清：停止自续
            if (!s.ended) terminateLocked(s);
            if (s.executing) {
                if (++s.stalledRounds >= 2) {
                    android.util.Log.w("NavMirror", "drag callback lost, force lift after "
                            + s.stalledRounds + " watchdog rounds");
                    forceLiftLocked(s);
                    dragSession = null;
                } else {
                    scheduleDragWatchdog();             // 段仍在飞：再等一轮
                }
                return;
            }
            if (s.hasPending) { dispatchNextLocked(s); scheduleDragWatchdog(); return; }
            dragSession = null;                          // ended 且无 pending 无在飞：收尾完成
        }
    };

    private void scheduleDragWatchdog() {
        mainHandler.removeCallbacks(dragWatchdog);
        mainHandler.postDelayed(dragWatchdog, 200L);
    }

    private void cancelDragWatchdog() { mainHandler.removeCallbacks(dragWatchdog); }

    /**
     * 回调丢失时的强制抬起（看门狗兜底）：一个独立的完整手势（willContinue=false）。
     * 新 dispatchGesture 会取消正在执行的旧链（官方语义），触点随之被系统放下；
     * 旧链的迟到回调按会话身份匹配会被无害丢弃。
     */
    private void forceLiftLocked(DragSession s) {
        try {
            float x = Math.max(0, Math.min(s.width - 1, s.lastSegX));
            float y = Math.max(0, Math.min(s.height - 1, s.lastSegY));
            Path path = new Path();
            path.moveTo(x, y);
            path.lineTo(Math.min(s.width - 1, x + 0.1f), y);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0L, 60L, false))
                    .build();
            dispatchGesture(g, null, mainHandler);
        } catch (RuntimeException e) {
            android.util.Log.w("NavMirror", "drag force lift failed: " + e);
        }
    }

    /** 标记会话进入终止态：下一注入段为终止段（willContinue=false）。 */
    private void terminateLocked(DragSession s) {
        s.ended = true;
        if (!s.hasPending) {          // 无新目标：从当前触点原地终止
            s.hasPending = true;
            s.pendingX = s.lastSegX;
            s.pendingY = s.lastSegY;
        }
    }

    /** 派发待注入目标（须持有 DRAG_LOCK 且 !executing）。 */
    private void dispatchNextLocked(DragSession s) {
        if (!s.hasPending || s.executing) return;
        long interval = SystemClock.uptimeMillis() - s.lastSegAt;
        long duration = Math.max(60L, Math.min(140L, (long) (interval * 0.8)));
        dispatchSegmentLocked(s, s.pendingX, s.pendingY, duration);
    }

    private void dispatchSegmentLocked(DragSession s, float toX, float toY, long duration) {
        s.hasPending = false;
        s.executing = true;
        boolean last = s.ended;   // 终止段：willContinue=false，之后触点抬起
        Path path = new Path();
        path.moveTo(s.lastSegX, s.lastSegY);
        if (toX == s.lastSegX && toY == s.lastSegY) {
            path.lineTo(Math.min(s.width - 1, toX + 0.1f), toY);   // 部分机型要求非空轮廓
        } else {
            path.lineTo(toX, toY);
        }
        try {
            GestureDescription.StrokeDescription stroke = (s.lastStroke == null)
                    ? new GestureDescription.StrokeDescription(path, 0L, duration, !last)
                    : s.lastStroke.continueStroke(path, 0L, duration, !last);
            s.lastStroke = last ? null : stroke;
            s.lastSegX = toX;
            s.lastSegY = toY;
            s.lastSegAt = SystemClock.uptimeMillis();
            dragSegments++;
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            // 返回值必须检查（设计要求）：false = 系统拒收，立即走完成逻辑续链
            boolean queued = dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { onSegmentDone(s, true); }
                @Override public void onCancelled(GestureDescription g) { onSegmentDone(s, false); }
            }, mainHandler);
            if (!queued) onSegmentDone(s, false);
        } catch (RuntimeException e) {
            // continueStroke 链断裂（前段已被系统取消等）：重建触点，下一段重新按下
            android.util.Log.w("NavMirror", "drag segment failed: " + e);
            s.lastStroke = null;
            s.executing = false;
            dragCancelled++;
        }
    }

    /** 段完成/取消回调（主线程）。回调按会话身份匹配：旧会话的残留回调不碰新会话。 */
    private void onSegmentDone(DragSession s, boolean ok) {
        synchronized (DRAG_LOCK) {
            if (dragSession != s) return;   // 陈旧回调（会话已被覆盖/清理）
            s.executing = false;
            if (!ok) {
                // 手势链被系统取消：置空后下一段重新按下（降级但不断流）
                s.lastStroke = null;
                dragCancelled++;
                android.util.Log.i("NavMirror", "drag segment cancelled, will re-press");
            }
            if (s.ended && !s.hasPending) {
                dragSession = null;         // 触点已抬起且无续段：会话结束
                return;
            }
            if (s.hasPending) dispatchNextLocked(s);
            // 否则：手指停在原地（无 pending）——保持按住等新输入，看门狗兜底
        }
    }

    /** 合并本服务组件到既有 enabled_accessibility_services 列表（纯函数，可单测）。 */
    static String mergeEntry(String existing, String component) {
        if (existing == null) return component;
        if (existing.isEmpty()) return component;
        for (String entry : existing.split(":")) {
            if (component.equals(entry)) return existing;
        }
        return existing + ":" + component;
    }

    /** 从既有列表中摘除本服务组件（纯函数，可单测；不存在时原样返回）。 */
    static String removeEntry(String existing, String component) {
        if (existing == null || existing.isEmpty()) return "";
        StringBuilder kept = new StringBuilder();
        for (String entry : existing.split(":")) {
            if (component.equals(entry) || entry.isEmpty()) continue;
            if (kept.length() > 0) kept.append(':');
            kept.append(entry);
        }
        return kept.toString();
    }

    /**
     * 无障碍自愈（完整重绑，等效设置页的关-开开关）：需要一次性
     * {@code adb shell pm grant com.colin.navmirror android.permission.WRITE_SECURE_SETTINGS}
     * （授权跨应用更新持久）。
     *
     * 为什么不能只写回列表：install -r / force-stop 杀进程时系统把在绑服务标记进
     * Crashed services；之后仅把组件写回列表会得到"实例就绪（isReady=true）但系统侧
     * 动作路由已死"的半开状态——反控 status 正常、tap/swipe/global 全部静默失效
     * （真机实测复现）。摘除→写回才等效用户在设置页关再开，Crashed 记录随之清除。
     *
     * 阻塞约 1.2s，必须在后台线程调用。@return 最终写入是否成功（未授权返回 false）。
     */
    static synchronized boolean heal(android.content.Context context) {
        try {
            String component = new android.content.ComponentName(
                    context.getPackageName(), RemoteControlService.class.getName()).flattenToString();
            android.content.ContentResolver resolver = context.getContentResolver();
            String key = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
            // 1) 摘除：触发系统解绑（清 Crashed 记录）
            android.provider.Settings.Secure.putString(resolver, key,
                    removeEntry(android.provider.Settings.Secure.getString(resolver, key), component));
            try { Thread.sleep(1000L); } catch (InterruptedException ignored) {}
            // 2) 基于最新列表写回（期间系统/用户可能改过列表，不能覆盖）
            String latest = android.provider.Settings.Secure.getString(resolver, key);
            android.provider.Settings.Secure.putString(resolver, key, mergeEntry(latest, component));
            android.provider.Settings.Secure.putInt(
                    resolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return true;
        } catch (RuntimeException e) {
            android.util.Log.w("NavMirror", "remote control heal unavailable: " + e);
            return false;   // 无 WRITE_SECURE_SETTINGS 或 ROM 拒绝写：走手动开启
        }
    }

    /**
     * Queues a path expressed as x,y pairs in the normalized [0,1] coordinate space.
     * The browser is responsible for mapping its visible video rectangle into this space.
     */
    public static boolean dispatchNormalizedPath(String encodedPoints, long durationMs) {
        RemoteControlService service = instance;
        if (service == null) return false;
        List<float[]> points = parsePoints(encodedPoints);
        if (points.isEmpty()) return false;
        long duration = Math.max(40L, Math.min(1500L, durationMs));
        service.mainHandler.post(() -> service.dispatchPath(points, duration));
        return true;
    }

    public static boolean performGlobal(String action) {
        RemoteControlService service = instance;
        if (service == null) return false;
        final int command;
        if ("back".equals(action)) command = GLOBAL_ACTION_BACK;
        else if ("home".equals(action)) command = GLOBAL_ACTION_HOME;
        else if ("recents".equals(action)) command = GLOBAL_ACTION_RECENTS;
        else return false;
        service.mainHandler.post(() -> service.performGlobalAction(command));
        return true;
    }

    private static List<float[]> parsePoints(String encoded) {
        List<float[]> result = new ArrayList<>();
        if (encoded == null || encoded.length() > 4096) return result;
        String[] pairs = encoded.split(";");
        for (int i = 0; i < pairs.length && result.size() < 64; i++) {
            String[] xy = pairs[i].split(",");
            if (xy.length != 2) continue;
            try {
                float x = Math.max(0f, Math.min(1f, Float.parseFloat(xy[0])));
                float y = Math.max(0f, Math.min(1f, Float.parseFloat(xy[1])));
                if (Float.isFinite(x) && Float.isFinite(y)) result.add(new float[]{x, y});
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private void dispatchPath(List<float[]> points, long durationMs) {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        wm.getDefaultDisplay().getRealMetrics(metrics);
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return;

        Path path = new Path();
        float[] first = points.get(0);
        float startX = first[0] * (metrics.widthPixels - 1);
        float startY = first[1] * (metrics.heightPixels - 1);
        path.moveTo(startX, startY);
        if (points.size() == 1) {
            // Android requires a non-empty contour on some vendor builds.
            path.lineTo(Math.min(metrics.widthPixels - 1, startX + 0.1f), startY);
        } else {
            for (int i = 1; i < points.size(); i++) {
                float[] p = points.get(i);
                path.lineTo(p[0] * (metrics.widthPixels - 1), p[1] * (metrics.heightPixels - 1));
            }
        }
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                .build();
        dispatchGesture(gesture, null, null);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Window content is deliberately not inspected; this service only injects gestures.
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
