package com.colin.navmirror;

import android.content.Context;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

/** Minimal HTTP(S) server: index page + MJPEG stream + single frame + info. */
final class StreamServer {
    private static final String BOUNDARY = "navmirrorframe";
    private static final long H264_BATCH_WINDOW_MS = 70;

    private final int port;
    private final CaptureEngine capture;
    private final SSLContext sslContext; // null = plain HTTP
    private final Context context;
    private final String controlToken = UUID.randomUUID().toString().replace("-", "");
    // Long-lived video requests need room alongside short control requests, but the
    // server must not create an unlimited number of threads for stale clients.
    private final ExecutorService pool = createClientPool();
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    /** WS 客户端 → 当前阻塞写开始时刻（0=空闲）。慢/失联客户端的写会卡死线程，看门狗强制关闭。 */
    private final ConcurrentHashMap<Socket, Long> wsWrites = new ConcurrentHashMap<>();
    /** 实际投屏观众数（WSS + MJPEG + /video MSE），不含 /info 等短 HTTP 连接。 */
    private final java.util.concurrent.atomic.AtomicInteger streamClients =
            new java.util.concurrent.atomic.AtomicInteger();
    private Thread wsWatchdog;
    private ServerSocket serverSocket;
    private volatile boolean running;

    StreamServer(int port, CaptureEngine capture) {
        this(port, capture, null, null);
    }

    StreamServer(int port, CaptureEngine capture, SSLContext sslContext) {
        this(port, capture, sslContext, null);
    }

    StreamServer(int port, CaptureEngine capture, SSLContext sslContext, Context context) {
        this.port = port;
        this.capture = capture;
        this.sslContext = sslContext;
        this.context = context == null ? null : context.getApplicationContext();
    }

    private static ExecutorService createClientPool() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 24, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * 强制关闭：Conscrypt 的 {@code close()} 会先把 TLS close_notify 写进 socket（排空发送队列），
     * 客户端 TCP 零窗口时这个写会永远阻塞——close() 永远到不了真正的 fd 关闭，
     * RST 发不出去，卡死的写线程也就解不了阻塞（实测两级卡死：看门狗线程 + 一次性关闭线程）。
     * 因此：先设 SO_LINGER(0)，再反射取底层 fd 直接 {@link android.system.Os#close}——
     * linger=0 下最后一次 fd close 让内核丢弃积压并发 RST，阻塞中的写/读线程随即以错误返回；
     * fd 反射失败（ROM 差异）则回退普通 close()。全部在受限关闭执行器里执行，调用方永不阻塞。
     */
    /** fd 反射路径是否可用：null=未探测；false=ROM 拒绝（hiddenapi core-platform 域）后不再尝试。 */
    private static volatile Boolean fdPathUsable;

    /** 受限关闭执行器：连接风暴下最多 4 个并发关闭线程（卡死的 Conscrypt 排空每个可占 ~15-40s），
     *  超出的排队等待，而不是每条连接新建线程无上界积累。 */
    private static final java.util.concurrent.ExecutorService CLOSE_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "navmirror-close");
                thread.setDaemon(true);
                return thread;
            });
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE_CLOSER =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 池饱和告警节流（风暴时每 5s 最多一条，避免日志放大） */
    private static volatile long lastSaturatedLog;

    private static void forceClose(Socket socket) {
        if (socket == null || socket.isClosed()) return;
        try { socket.setSoLinger(true, 0); } catch (Exception ignored) {}
        int active = ACTIVE_CLOSER.incrementAndGet();
        if (active >= 4) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastSaturatedLog > 5000) {
                lastSaturatedLog = now;
                android.util.Log.w("NavMirror", "close pool saturated (" + active
                        + " active closers); new closes are queued");
            }
        }
        try {
            CLOSE_POOL.execute(() -> {
                try {
                    Boolean usable = fdPathUsable;
                    if (usable == null || usable) {
                        try {
                            Object fd = findField(socket, "socket", "impl", "fd");
                            if (fd instanceof java.io.FileDescriptor) {
                                // fd 直关：立即 RST，解阻塞卡在写里的线程
                                android.system.Os.close((java.io.FileDescriptor) fd);
                                if (usable == null) {
                                    fdPathUsable = true;
                                    // 本 ROM 的 hiddenapi "denied" 只记日志仍放行访问（实测）：
                                    // 之后每次关闭仍会有一条 ROM 侧 E 级拒绝日志，属噪音
                                    android.util.Log.i("NavMirror", "forceClose fd path active (direct fd close + RST)");
                                }
                            } else if (usable == null) {
                                // 字段不可达（hiddenapi 拒绝时 findField 返回 null）：
                                // 记住结果避免每次断开都重复触发拒绝日志；走兜底路径
                                fdPathUsable = false;
                                android.util.Log.i("NavMirror", "forceClose fd path unavailable on this ROM; "
                                        + "TCP retransmit timeout will unblock");
                            }
                        } catch (Throwable reflectionFailed) {
                            if (usable == null) {
                                fdPathUsable = false;
                                android.util.Log.i("NavMirror", "forceClose fd path unavailable on this ROM ("
                                        + reflectionFailed + "); TCP retransmit timeout will unblock");
                            }
                        }
                    }
                    // 兜底：Java 层 close（把 closed 标志置上供写线程判断；排空写阻塞由本线程吸收，
                    // 对端零窗口时靠内核 TCP 重传超时（~15-40s）最终解阻塞收尾）
                    try { socket.close(); } catch (IOException ignored) {}
                } finally {
                    ACTIVE_CLOSER.decrementAndGet();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException poolShutDown) {
            ACTIVE_CLOSER.decrementAndGet();
        }
    }

    /** 沿字段链反射取值（ConscryptEngineSocket.socket -> Socket.impl -> SocketImpl.fd）。 */
    private static Object findField(Object root, String... names) throws Exception {
        Object current = root;
        for (String name : names) {
            Object next = null;
            for (Class<?> c = current.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    next = f.get(current);
                    break;
                } catch (NoSuchFieldException notHere) { }
            }
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    void start() throws IOException {
        running = true;
        if (sslContext != null) {
            serverSocket = sslContext.getServerSocketFactory().createServerSocket();
        } else {
            serverSocket = new ServerSocket();
        }
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
        wsWatchdog = new Thread(() -> {
            while (running) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                long now = System.currentTimeMillis();
                for (Map.Entry<Socket, Long> entry : wsWrites.entrySet()) {
                    Long start = entry.getValue();
                    if (start != null && start > 0 && now - start > 8000) {
                        Socket stuck = entry.getKey();
                        // 先摘除再关：防止写线程 finally 的 put(0) 复活脏时间戳重复触发
                        wsWrites.remove(stuck);
                        forceClose(stuck);
                        android.util.Log.w("NavMirror", "ws watchdog: write stuck >8s, closed "
                                + stuck.getRemoteSocketAddress());
                    }
                }
            }
        }, "navmirror-ws-watchdog");
        wsWatchdog.setDaemon(true);
        wsWatchdog.start();
        Thread acceptor = new Thread(() -> {
            while (running) {
                try {
                    Socket s = serverSocket.accept();
                    clients.add(s);
                    try {
                        pool.execute(() -> handle(s));
                    } catch (RejectedExecutionException rejected) {
                        clients.remove(s);
                        forceClose(s);
                        android.util.Log.w("NavMirror", "too many HTTP clients; connection rejected");
                    }
                } catch (IOException e) {
                    break;
                }
            }
        }, "navmirror-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (wsWatchdog != null) wsWatchdog.interrupt();
        wsWrites.clear();
        pool.shutdownNow();
        // SSLSocket.close() can send close_notify and is treated as network I/O by
        // Android. Service restarts happen on the main thread, so close connected
        // clients asynchronously to avoid NetworkOnMainThreadException on MIUI.
        List<Socket> sockets = new java.util.ArrayList<>(clients);
        clients.clear();
        for (Socket s : sockets) forceClose(s);
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            InputStream in = socket.getInputStream();
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
            int requestCount = 0;
            while (running && requestCount++ < 100) {
                String req = readRequestHead(in);
                if (req == null || req.isEmpty()) return;
                String requestBody = readRequestBody(in, req);
                String firstLine = req.split("\r\n")[0];
                String[] parts = firstLine.split(" ");
                String method = parts.length > 0 ? parts[0] : "GET";
                String path = parts.length > 1 ? parts[1] : "/";
                String mode = "";
                String query = "";
                int q = path.indexOf('?');
                if (q >= 0) {
                    String qs = path.substring(q + 1);
                    query = qs;
                    path = path.substring(0, q);
                    if (qs.contains("mode=cover")) mode = "cover";
                    else if (qs.contains("mode=contain")) mode = "contain";
                }

                boolean streaming = path.equals("/video") || path.equals("/stream");
                if (path.equals("/ws") && isWebSocketUpgrade(req)) {
                    serveWebSocket(socket, out, req);
                    return;
                }
                boolean keepAlive = !streaming && acceptsKeepAlive(firstLine, req);
                if (path.equals("/")) serveIndex(out, mode, keepAlive);
                else if (path.equals("/player.js")) serveAsset(out, "player.js", "application/javascript; charset=utf-8", keepAlive);
                else if (path.equals("/video")) serveVideo(socket, out);
                else if (path.equals("/stream")) serveStream(socket, out);
                else if (path.equals("/frame")) serveFrame(out, keepAlive);
                else if (path.equals("/info")) serveInfo(out, keepAlive);
                else if (path.equals("/capability")) serveCapability(out, keepAlive);
                else if (path.equals("/control")) serveControl(out, method, query, keepAlive);
                else serve404(out, keepAlive);
                if (!keepAlive) return;
            }
        } catch (SocketTimeoutException e) {
            // Normal end of an idle HTTP keep-alive connection.
            android.util.Log.i("NavMirror", "conn " + socket.getRemoteSocketAddress() + " idle timeout");
        } catch (Exception e) {
            android.util.Log.e("NavMirror", "http handler err: " + e);
        } finally {
            forceClose(socket);
            clients.remove(socket);
        }
    }

    private static String readRequestHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder(512);
        int matched = 0;
        while (head.length() < 8192) {
            int b = in.read();
            if (b < 0) return head.length() == 0 ? null : head.toString();
            head.append((char) b);
            char expected = "\r\n\r\n".charAt(matched);
            if (b == expected) {
                matched++;
                if (matched == 4) return head.toString();
            } else {
                matched = b == '\r' ? 1 : 0;
            }
        }
        throw new IOException("HTTP request headers too large");
    }

    private static String readRequestBody(InputStream in, String requestHead) throws IOException {
        int length = 0;
        for (String line : requestHead.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                try { length = Integer.parseInt(line.substring(colon + 1).trim()); }
                catch (NumberFormatException e) { throw new IOException("invalid Content-Length"); }
            }
        }
        if (length <= 0) return "";
        if (length > 1_048_576) throw new IOException("request body too large");
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(body, offset, length - offset);
            if (count < 0) throw new IOException("unexpected EOF in request body");
            offset += count;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static boolean acceptsKeepAlive(String firstLine, String request) {
        String lower = request.toLowerCase(java.util.Locale.US);
        if (lower.contains("\r\nconnection: close")) return false;
        return !firstLine.endsWith("HTTP/1.0")
                || lower.contains("\r\nconnection: keep-alive");
    }

    private static void writeHead(OutputStream out, String ct, long len, boolean keepAlive) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 200 OK\r\n");
        sb.append("Content-Type: ").append(ct).append("\r\n");
        if (len >= 0) sb.append("Content-Length: ").append(len).append("\r\n");
        sb.append("Cache-Control: no-store, no-cache, must-revalidate\r\n");
        sb.append("Pragma: no-cache\r\n");
        sb.append(keepAlive ? "Connection: keep-alive\r\nKeep-Alive: timeout=5, max=100\r\n\r\n"
                : "Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeHead(OutputStream out, String ct, long len) throws IOException {
        writeHead(out, ct, len, false);
    }

    private static boolean isWebSocketUpgrade(String request) {
        String lower = request.toLowerCase(java.util.Locale.US);
        return lower.contains("\r\nupgrade: websocket")
                && lower.contains("\r\nconnection: upgrade");
    }

    private static String header(String request, String name) {
        for (String line : request.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && name.equalsIgnoreCase(line.substring(0, colon).trim())) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private void serveWebSocket(Socket socket, OutputStream out, String request) throws Exception {
        if (capture == null || !capture.isH264Mode()) {
            serve503(out, "H.264 mode is not active");
            return;
        }
        String key = header(request, "Sec-WebSocket-Key");
        if (key == null || key.isEmpty()) throw new IOException("missing WebSocket key");
        byte[] digest = MessageDigest.getInstance("SHA-1").digest((key
                + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                .getBytes(StandardCharsets.US_ASCII));
        String accept = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String remote = String.valueOf(socket.getRemoteSocketAddress());
        long openedAt = System.currentTimeMillis();
        long sentFrames = 0, sentBytes = 0;
        // 控制帧 reader：浏览器关闭页面会发 Close 帧，静止画面(0fps)时写线程无数据可发、
        // 发现不了断线——必须有人读。Close→关闭 socket 唤醒写线程；Ping→回 Pong（与写互斥）。
        // readerClosed：reader 断开后置位，写线程空闲分支据此退出（socket.isClosed()
        // 对 Conscrypt 不可靠——它不置 java.net.Socket 的 closed 标志）。
        final Object writeLock = new Object();
        final java.util.concurrent.atomic.AtomicBoolean readerClosed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread controlReader = startWsControlReader(socket, out, writeLock, readerClosed);
        wsWrites.put(socket, 0L);
        streamClients.incrementAndGet();
        android.util.Log.i("NavMirror", "ws " + remote + " open");
        try {
            long sequence = capture.sequenceBeforeLatestEncodedKeyframe();
            // join 时刻（requestSyncFrame 之前）的缓存截止序号：
            // - 缓存积压（≤ joinCutoff）整段跳过，不会瞬时灌给客户端（触发 pending>24 假死阀）
            // - requestSyncFrame 请求的新 IDR 序号必然 > joinCutoff，不会被再次跳过
            //   （旧实现发送缓存关键帧后才读 latestEncodedSequence，若 IDR 已产生会被一起跳掉，
            //   白等下一个 GOP ~0.5s；缓存为空时还会无条件等第二个关键帧）
            long joinCutoff = capture.latestEncodedSequence();
            capture.requestSyncFrame();
            boolean started = false;
            boolean live = false;
            while (running && capture.isH264Mode() && !capture.isEncoderFailed()) {
                CaptureEngine.EncodedFrame frame = capture.waitForEncodedFrameAfter(sequence, 3000);
                if (frame == null) {
                    // 静止画面 0fps：没有帧要写也就不会碰到 socket ——
                    // 必须显式检查断开（reader 处理了 Close 帧 / 看门狗已强制关闭），否则观众计数永久虚高
                    if (readerClosed.get() || socket.isClosed()) break;
                    // 只为尚未进入实时流的新观众重试同步帧（起播依赖）；
                    // 已 live 的观众静止时不再请求——否则静止后第一次拖动会被强制编成大 IDR
                    // （~300-500KB 突发，弱车机端集中到达）。
                    // live 观众的积压恢复由客户端 waitKey 上行 'K' 主动请求。
                    if (!live) capture.requestSyncFrame();
                    continue;
                }
                sequence = frame.sequence;
                if (!started) {
                    // 1) 只回放缓存关键帧让车机立即出图
                    if (!frame.keyframe) continue;
                    started = true;
                    // 2) 跳过缓存积压（≤ joinCutoff），但 join 之后产生的帧照常发送
                    sequence = Math.max(frame.sequence, joinCutoff);
                    // 无积压时首帧就是 join 后的新 IDR（引用链完整）——直接实时流，不等第二个关键帧
                    live = frame.sequence > joinCutoff;
                } else if (!live) {
                    // 3) 有积压：其引用链已旧的增量帧（含积压后、新 IDR 前的帧）解码无意义，
                    //    等新 IDR 再进入实时流（720P60 一组 ~30 帧突发会触发客户端 pending>24 假死阀）
                    if (!frame.keyframe) continue;
                    live = true;
                }
                // 慢客户端(TCP 零窗口)会让阻塞写卡死线程；看门狗按时间戳强制断开。
                // 时间戳先于加锁置位：Pong 写卡死锁时看门狗同样能救。
                wsWrites.put(socket, System.currentTimeMillis());
                try {
                    synchronized (writeLock) {
                        writeWebSocketBinary(out, frame.data);
                    }
                } finally {
                    wsWrites.put(socket, 0L);
                }
                sentFrames++;
                sentBytes += frame.data.length;
            }
        } finally {
            wsWrites.remove(socket);
            streamClients.decrementAndGet();
            // 关闭 socket 唤醒可能阻塞在 read() 的控制帧 reader；同步 close 会被
            // Conscrypt 的排空写卡死（客户端不读时），必须走 forceClose
            forceClose(socket);
            android.util.Log.i("NavMirror", "ws " + remote + " closed: " + sentFrames
                    + " frames / " + (sentBytes / 1024) + " KB in "
                    + ((System.currentTimeMillis() - openedAt) / 1000) + "s");
        }
    }

    /** WS 控制帧 reader：Close(8)→断开；Ping(9)→回 Pong(10)；数据/Pong 帧忽略（协议无上行）。 */
    private Thread startWsControlReader(final Socket socket, final OutputStream out,
                                        final Object writeLock,
                                        final java.util.concurrent.atomic.AtomicBoolean readerClosed) {
        Thread reader = new Thread(() -> {
            String exitReason = "eof";
            // 上行关键帧请求限频：客户端 waitKey 时请求新 IDR（弱车机解码积压恢复用）
            long lastKeyReqAt = 0;
            // 实时拖拽上行限频（1s 窗口 30 条；正常 20-25Hz + DB/DU）：坏客户端洪泛保护
            long dragWindowStart = 0;
            int dragCount = 0;
            try {
                InputStream in = socket.getInputStream();
                byte[] header = new byte[2];
                while (true) {
                    int opcode;
                    byte[] payload;
                    try {
                        if (!readFully(in, header, 2)) { exitReason = "eof"; break; }
                        boolean fin = (header[0] & 0x80) != 0;
                        opcode = header[0] & 0x0f;
                        boolean masked = (header[1] & 0x80) != 0;
                        long length = header[1] & 0x7f;
                        if (length == 126 || length == 127) {
                            int ext = length == 126 ? 2 : 8;
                            byte[] extBuf = new byte[ext];
                            if (!readFully(in, extBuf, ext)) { exitReason = "eof-mid-ext"; break; }
                            length = 0;
                            for (byte b : extBuf) length = (length << 8) | (b & 0xff);
                        }
                        // RFC 6455 协议校验（认证暂缓，但畸形帧不留给后续漏洞面）：
                        // 客户端→服务器帧必须掩码；控制帧必须不分片且载荷 ≤125
                        if (!masked) { exitReason = "unmasked-frame"; break; }
                        if (opcode >= 8 && (!fin || length > 125)) { exitReason = "bad-control"; break; }
                        if (length > 1 << 20) { exitReason = "oversize " + length; break; }
                        byte[] mask = null;
                        if (masked) {
                            mask = new byte[4];
                            if (!readFully(in, mask, 4)) { exitReason = "eof-mid-mask"; break; }
                        }
                        payload = length > 0 ? new byte[(int) length] : new byte[0];
                        if (length > 0 && !readFully(in, payload, (int) length)) { exitReason = "eof-mid-payload"; break; }
                        if (masked) {
                            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                        }
                    } catch (SocketTimeoutException idle) {
                        continue; // 5s 无控制帧属正常，继续等
                    }
                    if (opcode == 8) { exitReason = "close-frame"; break; }
                    // 客户端解码积压丢帧后进入 waitKey：请求新 IDR 立即恢复引用链
                    // （限频 500ms/连接，配合客户端 1s 端限频）
                    if (opcode == 1 && payload.length == 1 && payload[0] == 'K') {
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastKeyReqAt > 500) {
                            lastKeyReqAt = now;
                            capture.requestSyncFrame();
                        }
                    }
                    // 实时拖拽注入（评审方案：WSS 上行而非逐点 HTTP）：
                    // "DB:x,y" 按下 / "DM:x,y" 移动(只发最新点,~20Hz) / "DU:x,y" 抬起 / "DC" 取消。
                    // 手机侧单飞 continueStroke 分段注入，此处只做解析转发 + 限频。
                    if (opcode == 1 && payload.length >= 2 && payload[0] == 'D'
                            && (payload[1] == 'B' || payload[1] == 'M' || payload[1] == 'U' || payload[1] == 'C')) {
                        if (payload[1] == 'C') {
                            RemoteControlService.dragCancel();
                        } else if (payload.length >= 4 && payload[2] == ':') {
                            long now = SystemClock.elapsedRealtime();
                            if (now - dragWindowStart >= 1000) {
                                dragWindowStart = now;
                                dragCount = 0;
                            }
                            if (++dragCount <= 30) {
                                float[] xy = parseDragPoint(
                                        new String(payload, 3, payload.length - 3, StandardCharsets.US_ASCII));
                                if (xy != null) {
                                    if (payload[1] == 'B') RemoteControlService.dragBegin(xy[0], xy[1]);
                                    else if (payload[1] == 'M') RemoteControlService.dragMove(xy[0], xy[1]);
                                    else RemoteControlService.dragEnd(xy[0], xy[1]);
                                }
                            }
                        }
                    }
                    if (opcode == 9 && payload.length <= 125) {   // Ping -> Pong
                        try {
                            synchronized (writeLock) {
                                out.write(0x8A);
                                out.write(payload.length);
                                out.write(payload);
                                out.flush();
                            }
                        } catch (IOException pongFailed) { exitReason = "pong-failed"; break; }
                    }
                    // opcode 10 (Pong) / 2 (二进制)：本协议无上行数据，忽略
                }
            } catch (IOException | RuntimeException readerError) {
                // 写线程/看门狗已关闭连接，或协议层异常：记录原因后强制关闭
                exitReason = "err " + readerError.getClass().getSimpleName()
                        + (readerError.getMessage() != null ? ": " + readerError.getMessage() : "");
            }
            android.util.Log.i("NavMirror", "ws reader exit (" + exitReason + "): "
                    + socket.getRemoteSocketAddress());
            // 连接断开：终止可能仍在按下的实时拖拽会话（看门狗之外的第二重保险）
            RemoteControlService.dragCancel();
            // 客户端已断开或协议异常：置位信号让写线程空闲分支退出，再强制关闭
            // 唤醒可能阻塞在写/看门狗路径的收尾（不能同步 close，会被 Conscrypt 排空写卡死）
            readerClosed.set(true);
            forceClose(socket);
        }, "navmirror-ws-read");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private static boolean readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int count = in.read(buffer, offset, length - offset);
            if (count < 0) return false;
            offset += count;
        }
        return true;
    }

    /**
     * 解析实时拖拽帧坐标 "0.5123,0.4812" → [x,y]（均已被钳到 [0,1]）。
     * 任何非法输入返回 null（长度、格式、范围、非有限值）。纯函数，可单测。
     */
    static float[] parseDragPoint(String body) {
        if (body == null || body.length() > 24) return null;
        int comma = body.indexOf(',');
        if (comma <= 0 || comma == body.length() - 1) return null;
        String rest = body.substring(comma + 1);
        if (rest.indexOf(',') >= 0 || rest.indexOf(';') >= 0) return null;
        try {
            float x = Float.parseFloat(body.substring(0, comma));
            float y = Float.parseFloat(rest);
            if (!Float.isFinite(x) || !Float.isFinite(y)) return null;
            return new float[]{Math.max(0f, Math.min(1f, x)), Math.max(0f, Math.min(1f, y))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void writeWebSocketBinary(OutputStream out, byte[] payload) throws IOException {
        out.write(0x82);
        int length = payload.length;
        if (length < 126) {
            out.write(length);
        } else if (length <= 0xffff) {
            out.write(126);
            out.write((length >>> 8) & 0xff);
            out.write(length & 0xff);
        } else {
            out.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (((long) length >>> shift) & 0xff));
            }
        }
        out.write(payload);
        out.flush();
    }

    /** player.js 内存缓存：每次开页省一次 assets 读取与拷贝（内容进程内不变）。 */
    private volatile byte[] playerJsCache;

    private void serveAsset(OutputStream out, String name, String contentType,
                            boolean keepAlive) throws IOException {
        if (context == null) { serve404(out, keepAlive); return; }
        byte[] body;
        if (name.equals("player.js") && (body = playerJsCache) != null) {
            writeHead(out, contentType, body.length, keepAlive);
            out.write(body);
            out.flush();
            return;
        }
        try (InputStream asset = context.getAssets().open(name)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = asset.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            body = bytes.toByteArray();
            if (name.equals("player.js")) playerJsCache = body;
            writeHead(out, contentType, body.length, keepAlive);
            out.write(body);
            out.flush();
        }
    }

    private static void writeJson(OutputStream out, int status, String reason,
                                  String json, boolean keepAlive) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason
                + "\r\nContent-Type: application/json; charset=utf-8"
                + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store"
                + (keepAlive ? "\r\nConnection: keep-alive\r\nKeep-Alive: timeout=5, max=100"
                : "\r\nConnection: close") + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private void serveControl(OutputStream out, String method, String query, boolean keepAlive) throws IOException {
        Map<String, String> args = parseQuery(query);
        boolean authorized = "POST".equals(method) && controlToken.equals(args.get("token"));
        boolean ready = RemoteControlService.isReady();
        boolean accepted = false;
        if (authorized) {
            String action = args.get("action");
            if ("status".equals(action)) accepted = ready;
            else if ("back".equals(action) || "home".equals(action) || "recents".equals(action)) {
                accepted = RemoteControlService.performGlobal(action);
            } else if ("tap".equals(action) || "long".equals(action) || "swipe".equals(action)) {
                long duration = parseLong(args.get("duration"), "long".equals(action) ? 650L : 80L);
                accepted = RemoteControlService.dispatchNormalizedPath(args.get("points"), duration);
            }
        }
        String json = "{\"ready\":" + ready + ",\"accepted\":" + accepted + "}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (!authorized) {
            String head = "HTTP/1.1 403 Forbidden\r\nContent-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\nCache-Control: no-store\r\n"
                    + (keepAlive ? "Connection: keep-alive\r\n\r\n" : "Connection: close\r\n\r\n");
            out.write(head.getBytes(StandardCharsets.US_ASCII));
        } else {
            writeHead(out, "application/json; charset=utf-8", body.length, keepAlive);
        }
        out.write(body);
        out.flush();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.length() > 8192) return result;
        for (String part : query.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            String value = equals < 0 ? "" : part.substring(equals + 1);
            try {
                result.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; }
    }

    private void serveIndex(OutputStream out, String mode) throws IOException {
        serveIndex(out, mode, false);
    }

    private void serveIndex(OutputStream out, String mode, boolean keepAlive) throws IOException {
        String initJs = "cover".equals(mode)
                ? "var fitLocked=true,initialFit='cover';"
                : "contain".equals(mode)
                ? "var fitLocked=true,initialFit='contain';"
                : "var fitLocked=false,initialFit='contain';";
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">"
                + "<title>NavMirror</title>"
                + "<style>"
                + "html,body{margin:0;padding:0;background:#07101c;height:100%;overflow:hidden}"
                // 静态四季背景：纯 CSS 渐变零解码开销（替换动画 WebP，车机 GPU 不再
                // 被全屏逐帧解码抢占，视频管线可独占合成资源）
                + "body.season-spring{background:"
                + "radial-gradient(ellipse at 30% 18%,rgba(122,168,128,.22),transparent 62%),"
                + "linear-gradient(175deg,#101d24 0%,#20342f 60%,#2a423a 100%)}"
                + "body.season-summer{background:"
                + "radial-gradient(ellipse at 30% 18%,rgba(64,170,190,.20),transparent 62%),"
                + "linear-gradient(175deg,#0d1b26 0%,#123040 60%,#173b4e 100%)}"
                + "body.season-autumn{background:"
                + "radial-gradient(ellipse at 30% 18%,rgba(196,124,60,.18),transparent 62%),"
                + "linear-gradient(175deg,#1c1712 0%,#2c2216 60%,#3a2c1c 100%)}"
                + "body.season-winter{background:"
                + "radial-gradient(ellipse at 30% 18%,rgba(150,175,205,.16),transparent 62%),"
                + "linear-gradient(175deg,#0e141d 0%,#1a2634 60%,#243446 100%)}"
                + "#edgeTint{position:fixed;inset:0;z-index:1;pointer-events:none;"
                + "background:linear-gradient(90deg,rgba(var(--edge-left,18,34,50),.86) 0%,"
                + "rgba(var(--edge-left,18,34,50),.48) 24%,transparent 43%,transparent 57%,"
                + "rgba(var(--edge-right,42,45,52),.48) 76%,rgba(var(--edge-right,42,45,52),.86) 100%);"
                + "transition:background 1.1s ease}"
                // 只有上下留白（横屏源+contain 典型）：色调层改纵向渐变、用上下边缘色
                + "body.has-tb-gap:not(.has-side-gap) #edgeTint{background:linear-gradient(180deg,"
                + "rgba(var(--edge-top,18,26,38),.86) 0%,rgba(var(--edge-top,18,26,38),.48) 24%,"
                + "transparent 43%,transparent 57%,"
                + "rgba(var(--edge-bottom,10,14,20),.48) 76%,rgba(var(--edge-bottom,10,14,20),.86) 100%)}"
                + "#shade{position:fixed;inset:0;z-index:2;pointer-events:none;"
                + "background:linear-gradient(180deg,rgba(2,7,14,.42) 0%,rgba(2,7,14,.08) 30%,"
                + "rgba(2,7,14,.08) 70%,rgba(2,7,14,.48) 100%),"
                + "radial-gradient(ellipse at center,transparent 38%,rgba(0,0,0,.34) 100%)}"
                + "#host{position:fixed;inset:0;z-index:3}"
                + "#v{position:absolute;left:0;top:0;right:0;bottom:0;width:100%;height:100%;"
                + "object-fit:contain;background:transparent;border:0;display:block}"
                + "body.cover #v{object-fit:cover}"
                + "body.landscape-source:not(.cover) #host,body.landscape-source:not(.cover) #v{background:transparent}"
                + "body.cover.portrait-source #v{left:50%;right:auto;width:auto;height:100%;"
                + "aspect-ratio:var(--capture-aspect);object-fit:fill;transform:translateX(-50%);"
                + "clip-path:inset(0 var(--portrait-crop) 0 var(--portrait-crop))}"
                + "#frameShadow,#featherL,#featherR,#featherT,#featherB{display:none;position:fixed;pointer-events:none;z-index:4}"
                + "body.has-side-gap #frameShadow,body.has-tb-gap #frameShadow{display:block;left:var(--content-left);top:var(--content-top);"
                + "width:var(--content-width);height:var(--content-height);box-sizing:border-box;"
                + "border:1px solid rgba(255,255,255,.10);box-shadow:0 0 26px rgba(0,0,0,.40)}"
                + "body.has-side-gap #featherL,body.has-side-gap #featherR{display:block;"
                + "top:var(--content-top);height:var(--content-height);width:34px}"
                + "body.has-side-gap #featherL{left:var(--content-left);"
                + "background:linear-gradient(90deg,rgba(var(--edge-left,18,34,50),.72),transparent);"
                + "box-shadow:-12px 0 22px rgba(var(--edge-left,18,34,50),.20)}"
                + "body.has-side-gap #featherR{right:var(--content-right);"
                + "background:linear-gradient(270deg,rgba(var(--edge-right,42,45,52),.72),transparent);"
                + "box-shadow:12px 0 22px rgba(var(--edge-right,42,45,52),.20)}"
                // 上下留白融合（与左右完全对称）：视频贴边 34px 渐变 + 轻阴影
                + "body.has-tb-gap #featherT,body.has-tb-gap #featherB{display:block;"
                + "left:var(--content-left);width:var(--content-width);height:34px}"
                + "body.has-tb-gap #featherT{top:var(--content-top);"
                + "background:linear-gradient(180deg,rgba(var(--edge-top,18,26,38),.72),transparent);"
                + "box-shadow:0 -12px 22px rgba(var(--edge-top,18,26,38),.20)}"
                + "body.has-tb-gap #featherB{top:calc(var(--content-top) + var(--content-height) - 34px);"
                + "background:linear-gradient(0deg,rgba(var(--edge-bottom,10,14,20),.72),transparent);"
                + "box-shadow:0 12px 22px rgba(var(--edge-bottom,10,14,20),.20)}"
                + "#ctl{position:fixed;right:max(16px,env(safe-area-inset-right));bottom:max(14px,env(safe-area-inset-bottom));z-index:9;"
                + "display:flex;align-items:center;gap:3px;padding:4px;"
                + "font:600 13px -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;white-space:nowrap;"
                + "background:rgba(8,16,28,.90);border:1px solid rgba(255,255,255,.16);border-radius:15px;"
                + "box-shadow:0 8px 28px rgba(0,0,0,.38),inset 0 1px rgba(255,255,255,.06)}"
                + "#ctl button{appearance:none;-webkit-appearance:none;border:0;border-radius:11px;padding:8px 15px;"
                + "color:rgba(255,255,255,.72);background:transparent;font:inherit;line-height:1;cursor:pointer;"
                + "transition:background .18s ease,color .18s ease,box-shadow .18s ease,transform .12s ease}"
                + "#ctl button:active{transform:scale(.96)}"
                + "#ctl button.active{color:#07121d;background:linear-gradient(135deg,#8be9ff,#58c8f4);"
                + "box-shadow:0 3px 12px rgba(74,196,240,.3)}"
                + "#ctl .sep{width:1px;height:19px;margin:0 2px;background:rgba(255,255,255,.15)}"
                + "#ctl #remote{color:#bfffe1}"
                + "body.control-on #ctl #remote{color:#071d16;background:linear-gradient(135deg,#8fffd2,#59e9c0);"
                + "box-shadow:0 3px 12px rgba(89,233,192,.28)}"
                + "body.control-on #host{touch-action:none;cursor:crosshair}"
                + "#touchDot{position:fixed;left:0;top:0;width:28px;height:28px;margin:0;border:2px solid rgba(140,230,255,.9);border-radius:50%;background:rgba(90,200,255,.25);box-shadow:0 0 6px rgba(90,200,255,.5);pointer-events:none;z-index:11;opacity:0;transition:opacity .18s}"
                // 触点指示只做"输入已收到"的即时反馈；不本地平移画面——
                // 预测错误会造成双重移动+回弹（评审方案明确不建议）
                + "#remoteKeys{display:none;position:fixed;left:max(16px,env(safe-area-inset-left));"
                + "bottom:max(14px,env(safe-area-inset-bottom));z-index:9;gap:5px;padding:5px;"
                + "background:rgba(8,16,28,.90);border:1px solid rgba(255,255,255,.16);border-radius:15px;"
                + "box-shadow:0 8px 28px rgba(0,0,0,.38)}"
                + "body.control-on #remoteKeys{display:flex}#remoteKeys button{border:0;border-radius:10px;padding:9px 13px;"
                + "color:#dffaf0;background:rgba(255,255,255,.07);font:600 13px -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}"
                // 竖屏内容居中时两侧有足够留白：工具条放右侧、反控导航键放左侧，
                // 避开手机画面的底部。留白不足时仍使用原来的底部布局。
                + "body.portrait-source.side-controls #ctl{top:50%;bottom:auto;transform:translateY(-50%);flex-direction:column;left:auto;right:max(8px,env(safe-area-inset-right))}"
                + "body.portrait-source.side-controls #ctl .sep{width:19px;height:1px;margin:2px 0}"
                + "body.portrait-source.side-controls #remoteKeys{top:50%;bottom:auto;transform:translateY(-50%);flex-direction:column;left:max(8px,env(safe-area-inset-left))}"
                + "#controlHint{position:fixed;left:50%;top:max(18px,env(safe-area-inset-top));z-index:12;"
                + "transform:translate(-50%,-12px);opacity:0;pointer-events:none;padding:9px 14px;border-radius:12px;"
                + "color:#e9fbff;background:rgba(5,12,22,.88);font:600 13px -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
                + "transition:opacity .2s,transform .2s}#controlHint.show{opacity:1;transform:translate(-50%,0)}"
                + "#stats{display:none;position:fixed;left:max(16px,env(safe-area-inset-left));top:max(16px,env(safe-area-inset-top));z-index:10;"
                + "padding:10px 12px;border:1px solid rgba(255,255,255,.16);border-radius:12px;color:#d8f7ff;"
                + "background:rgba(5,12,22,.82);font:500 12px/1.55 ui-monospace,SFMono-Regular,Consolas,monospace;"
                + "white-space:pre;box-shadow:0 8px 28px rgba(0,0,0,.35)}"
                + "body.show-stats #stats{display:block}"
                + "</style></head><body>"
                + "<div id=\"edgeTint\"></div><div id=\"shade\"></div><div id=\"host\"></div>"
                + "<div id=\"frameShadow\"></div><div id=\"featherL\"></div><div id=\"featherR\"></div>"
                + "<div id=\"featherT\"></div><div id=\"featherB\"></div><div id=\"stats\"></div>"
                + "<div id=\"controlHint\"></div><div id=\"remoteKeys\"><button data-action=\"back\">返回</button>"
                + "<button data-action=\"home\">主页</button><button data-action=\"recents\">最近</button></div>"
                + "<div id=\"ctl\" role=\"toolbar\" aria-label=\"画面显示方式\">"
                + "<button id=\"fitContain\" type=\"button\" aria-pressed=\"false\">适应</button>"
                + "<button id=\"fitCover\" type=\"button\" aria-pressed=\"false\">铺满</button>"
                + "<span class=\"sep\"></span><button id=\"perf\" type=\"button\" aria-pressed=\"false\">性能</button>"
                + "<button id=\"remote\" type=\"button\" aria-pressed=\"false\">反控</button></div>"
                + "<script src=\"/player.js\"></script><script>" + initJs
                + "var controlToken='" + controlToken + "';"
                + "var host=document.getElementById('host'),fallen=false,streamInfo=null,lastPortrait=null;"
                + "var stats=document.getElementById('stats'),perf=document.getElementById('perf'),clientStats={lag:0,q:0,qb:0,aps:0,dropped:0,total:0,rate:1,hard:0,decoded:0,lost:0,rtcFps:0,rtcMbps:0,rtt:0,jitter:0,bufferMs:0,draw:0,qMax:0,drawFps:0,latAvg:0,latMax:0,codec:'H.264',ice:'new'};"
                + "function renderStats(){if(!document.body.classList.contains('show-stats'))return;var i=streamInfo||{},mb=((i.actualBitrate||0)/1000000).toFixed(1);"
                + "if(i.mode==='h264'){stats.textContent='H.264  '+clientStats.ice+'  ·  '+clientStats.codec+'\\n'"
                + "+'编码  '+Number(i.actualFps||0).toFixed(1)+' / '+(i.fps||0)+' FPS  ·  '+mb+' Mbps\\n'"
                + "+'接收  '+clientStats.rtcMbps.toFixed(1)+' Mbps '+clientStats.rtcFps.toFixed(0)+' FPS  ·  解码 '+clientStats.decoded+' 帧\\n'"
                + "+'输出  '+clientStats.drawFps.toFixed(1)+' FPS  ·  解码延迟 '+clientStats.latAvg+'/'+clientStats.latMax+' ms\\n'"
                + "+'解码队列  '+clientStats.q+'（峰值 '+clientStats.qMax+'）  ·  绘制 '+clientStats.draw+' ms/帧\\n'"
                + "+'丢弃 '+clientStats.dropped+'  ·  重同步 '+clientStats.hard+' 次';return}"
                + "stats.textContent='编码  '+Number(i.actualFps||0).toFixed(1)+' / '+(i.fps||0)+' FPS  ·  '+mb+' Mbps\\n'"
                + "+'缓冲  '+Math.round(clientStats.lag)+' ms  ·  '+clientStats.rate.toFixed(2)+'x\\n'"
                + "+'队列  '+clientStats.q+' / '+Math.round(clientStats.qb/1024)+' KB  ·  '+clientStats.aps.toFixed(1)+' append/s\\n'"
                + "+'丢帧  '+clientStats.dropped+' / '+clientStats.total+'  ·  硬追赶 '+clientStats.hard+' 次';}"
                + "perf.onclick=function(){var on=document.body.classList.toggle('show-stats');perf.classList.toggle('active',on);"
                + "perf.setAttribute('aria-pressed',String(on));renderStats()};"
                + "var month=(new Date()).getMonth()+1,season=month>=3&&month<=5?'spring':month>=6&&month<=8?'summer':month>=9&&month<=11?'autumn':'winter';"
                + "document.body.classList.add('season-'+season);"
                + "var fitContain=document.getElementById('fitContain'),fitCover=document.getElementById('fitCover');"
                + "function setFit(mode,manual){var cover=mode==='cover';document.body.classList.toggle('cover',cover);"
                + "fitContain.classList.toggle('active',!cover);fitCover.classList.toggle('active',cover);"
                + "fitContain.setAttribute('aria-pressed',String(!cover));fitCover.setAttribute('aria-pressed',String(cover));"
                + "if(manual){fitLocked=true;if(history.replaceState)history.replaceState(null,'','?mode='+mode)}updateBlendGeometry()}"
                + "function applyGeometry(i){streamInfo=i;document.body.classList.toggle('portrait-source',i.portrait);"
                + "document.body.classList.toggle('landscape-source',!i.portrait);"
                + "var ca=i.width/i.height,part=Math.max(.05,Math.min(1,(i.sourceAspect||ca)/ca));"
                + "document.body.style.setProperty('--capture-aspect',String(ca));"
                + "document.body.style.setProperty('--portrait-crop',((1-part)*50)+'%');updateBlendGeometry()}"
                + "var controlRect={left:0,top:0,width:1,height:1};"
                + "function updateBlendGeometry(){if(!streamInfo)return;var vw=innerWidth,vh=innerHeight,sa=streamInfo.portrait?(streamInfo.sourceAspect||.5):(streamInfo.width/streamInfo.height),cover=document.body.classList.contains('cover');"
                + "var rw,rh;if(cover&&!streamInfo.portrait){rw=Math.max(vw,vh*sa);rh=rw/sa;if(rh<vh){rh=vh;rw=rh*sa}}"
                + "else{rw=Math.min(vw,vh*sa);rh=rw/sa;if(rh>vh){rh=vh;rw=rh*sa}}var left=(vw-rw)/2,top=(vh-rh)/2,gap=left>10;controlRect={left:left,top:top,width:rw,height:rh};"
                + "document.body.classList.toggle('has-side-gap',gap);"
                // 上下留白（横屏源 + contain 典型：1280x720 视口 + 2.22 源 ≈ 上下各 72px）
                + "document.body.classList.toggle('has-tb-gap',top>10);"
                // 竖排侧置条件：竖屏源且有 ≥76px 侧空隙（竖排宽 ~72px）即可，
                // 不再要求 vw>vh——高（竖）视口下侧空隙窄（如 800x1280 只有 112px），
                // 横排按钮必压进视频区（真机反馈：竖屏时底下按钮挡住投屏）。
                + "document.body.classList.toggle('side-controls',streamInfo.portrait&&left>=76);var s=document.documentElement.style;"
                + "s.setProperty('--content-left',left+'px');s.setProperty('--content-right',left+'px');"
                + "s.setProperty('--content-top',top+'px');s.setProperty('--content-width',rw+'px');s.setProperty('--content-height',rh+'px')}"
                + "var sampleCanvas=document.createElement('canvas');sampleCanvas.width=32;sampleCanvas.height=18;var sampleCtx=sampleCanvas.getContext('2d',{willReadFrequently:true});"
                + "function sampleEdgeColors(v){try{if(!v||!streamInfo||(v.tagName==='VIDEO'&&v.readyState<2))return;sampleCtx.drawImage(v,0,0,32,18);"
                + "var d=sampleCtx.getImageData(0,0,32,18).data,ca=streamInfo.width/streamInfo.height,part=Math.max(.05,Math.min(1,(streamInfo.sourceAspect||ca)/ca));"
                + "var lx=streamInfo.portrait?Math.max(1,Math.floor((1-part)*16)+1):1,rx=streamInfo.portrait?Math.min(30,Math.ceil((1+part)*16)-2):30;"
                + "function avg(cx){var r=0,g=0,b=0,n=0;for(var y=2;y<16;y++)for(var x=Math.max(0,cx-1);x<=Math.min(31,cx+1);x++){var p=(y*32+x)*4;r+=d[p];g+=d[p+1];b+=d[p+2];n++}"
                + "return [Math.round(r/n*.72),Math.round(g/n*.72),Math.round(b/n*.72)]}"
                // 行平均（上下留白融合用）：横屏源时全幅是内容、竖屏源时条带全高是内容，
                // 两种取向下行 1/16 都是真实内容边缘
                + "function avgRow(cy){var r=0,g=0,b=0,n=0;for(var x=2;x<30;x++)for(var y=Math.max(0,cy-1);y<=Math.min(17,cy+1);y++){var p=(y*32+x)*4;r+=d[p];g+=d[p+1];b+=d[p+2];n++}"
                + "return [Math.round(r/n*.72),Math.round(g/n*.72),Math.round(b/n*.72)]}"
                + "var l=avg(lx),r=avg(rx),t=avgRow(1),b=avgRow(16),s=document.documentElement.style;"
                + "s.setProperty('--edge-left',l.join(','));s.setProperty('--edge-right',r.join(','));"
                + "s.setProperty('--edge-top',t.join(','));s.setProperty('--edge-bottom',b.join(','))}catch(e){}}"
                + "function autoFit(i){if(i.controlToken&&i.controlToken!==controlToken)controlToken=i.controlToken;applyGeometry(i);if(lastPortrait===null){lastPortrait=i.portrait;if(!fitLocked)setFit(i.portrait?'cover':'contain',false)}"
                + "else if(lastPortrait!==i.portrait){lastPortrait=i.portrait;fitLocked=false;setFit(i.portrait?'cover':'contain',false)}"
                + "else if(!fitLocked)setFit(i.portrait?'cover':'contain',false)}"
                + "fitContain.onclick=function(){setFit('contain',true)};fitCover.onclick=function(){setFit('cover',true)};"
                + "setFit(initialFit,fitLocked);"
                + "var remote=document.getElementById('remote'),hint=document.getElementById('controlHint'),hintTimer=0,activePointer=null,pathPoints=[],downAt=0,downX=0,downY=0,lastPointAt=0;"
                + "function showHint(text){hint.textContent=text;hint.classList.add('show');clearTimeout(hintTimer);hintTimer=setTimeout(function(){hint.classList.remove('show')},2200)}"
                + "function controlUrl(action,points,duration){var u='/control?token='+encodeURIComponent(controlToken)+'&action='+encodeURIComponent(action);"
                + "if(points)u+='&points='+encodeURIComponent(points);if(duration)u+='&duration='+Math.round(duration);return u}"
                + "function postControl(action,points,duration){"
                // 403 = token 过期（服务器重启后重新生成，弱车机上 1s 轮询可能还没跑到）：
                // 立即拉 /info 换新 token 重试一次。不能把 403 当"服务已关闭"——
                // 否则正常动作被吞掉、反控面板被错误关闭（真机弱车机场景复现）。
                + "function controlFetch(u){return fetch(u,{method:'POST',cache:'no-store'})}"
                + "return controlFetch(controlUrl(action,points,duration)).then(function(r){"
                + "return r.json().then(function(j){if(r.status!==403)return j;"
                + "return fetch('/info?t='+Date.now()).then(function(r2){return r2.json()}).then(function(i){"
                + "if(i&&i.controlToken)controlToken=i.controlToken;"
                + "return controlFetch(controlUrl(action,points,duration)).then(function(r3){return r3.json()})})})})}"
                + "function setRemote(on){document.body.classList.toggle('control-on',on);remote.classList.toggle('active',on);remote.setAttribute('aria-pressed',String(on));if(on)showHint('反控已开启 · 触摸画面即可操作手机')}"
                + "remote.onclick=function(){if(document.body.classList.contains('control-on')){setRemote(false);return}postControl('status').then(function(s){if(s.ready)setRemote(true);else showHint('请先在手机开启 NavMirror 反控服务')}).catch(function(){showHint('反控连接失败')})};"
                + "function normalizedPoint(e){var x=Math.max(0,Math.min(1,(e.clientX-controlRect.left)/controlRect.width));var y=Math.max(0,Math.min(1,(e.clientY-controlRect.top)/controlRect.height));return [x,y]}"
                + "function appendPoint(e,force){var now=performance.now();if(!force&&now-lastPointAt<24)return;lastPointAt=now;var p=normalizedPoint(e);if(pathPoints.length<48)pathPoints.push(p[0].toFixed(4)+','+p[1].toFixed(4))}"
                // === 实时跟手拖拽（评审方案：WSS 上行 + 20Hz 合并 + 最新点）===
                // H.264 WS 可用 → DB/DM/DU 实时注入（JPEG 模式无 WS → 回退到松手回放）
                + "var dragLive=false,lastDragSend=0,touchDot=null;"
                + "function sendDrag(kind,x,y){if(!window.navSendDrag)return false;return window.navSendDrag(kind+':'+x.toFixed(4)+','+y.toFixed(4))}"
                + "function showTouchDot(x,y,hide){if(!touchDot){touchDot=document.createElement('div');touchDot.id='touchDot';document.body.appendChild(touchDot)}if(hide){touchDot.style.opacity='0';return}touchDot.style.opacity='1';touchDot.style.transform='translate('+Math.round(controlRect.left+x*controlRect.width-14)+'px,'+Math.round(controlRect.top+y*controlRect.height-14)+'px)'}"
                + "host.addEventListener('pointerdown',function(e){if(!document.body.classList.contains('control-on')||activePointer!==null)return;"
                + "if(e.clientX<controlRect.left||e.clientX>controlRect.left+controlRect.width||e.clientY<controlRect.top||e.clientY>controlRect.top+controlRect.height)return;"
                + "activePointer=e.pointerId;pathPoints=[];downAt=performance.now();downX=e.clientX;downY=e.clientY;lastPointAt=0;lastDragSend=0;appendPoint(e,true);"
                + "var p=normalizedPoint(e);dragLive=sendDrag('DB',p[0],p[1]);if(dragLive)showTouchDot(p[0],p[1],false);"
                + "try{host.setPointerCapture(e.pointerId)}catch(x){}e.preventDefault()},{passive:false});"
                + "host.addEventListener('pointermove',function(e){if(e.pointerId!==activePointer)return;appendPoint(e,false);"
                + "if(dragLive){var now=performance.now();if(now-lastDragSend>=45){lastDragSend=now;var p=normalizedPoint(e);sendDrag('DM',p[0],p[1]);showTouchDot(p[0],p[1],false)}}"
                + "e.preventDefault()},{passive:false});"
                + "function finishPointer(e,cancel){if(e.pointerId!==activePointer)return;appendPoint(e,true);var elapsed=performance.now()-downAt,dist=Math.hypot(e.clientX-downX,e.clientY-downY),points=pathPoints.join(';');activePointer=null;pathPoints=[];e.preventDefault();"
                + "if(dragLive){var p=normalizedPoint(e);if(cancel)window.navSendDrag&&window.navSendDrag('DC');else sendDrag('DU',p[0],p[1]);showTouchDot(0,0,true);dragLive=false;return}"
                + "if(cancel)return;if(dist<12){postControl(elapsed>=480?'long':'tap',points,elapsed>=480?Math.max(600,elapsed):70)}else postControl('swipe',points,Math.max(80,Math.min(elapsed*0.6,250))).then(function(s){if(!s.ready){setRemote(false);showHint('手机反控服务已关闭')}})}"
                + "host.addEventListener('pointerup',function(e){finishPointer(e,false)},{passive:false});host.addEventListener('pointercancel',function(e){finishPointer(e,true)},{passive:false});"
                + "document.getElementById('remoteKeys').onclick=function(e){var action=e.target&&e.target.getAttribute('data-action');if(action)postControl(action).then(function(s){if(!s.ready){setRemote(false);showHint('手机反控服务已关闭')}})};"
                + "function jpeg(){if(fallen)return;fallen=true;host.innerHTML='<img id=\"v\">';"
                + "var v=document.getElementById('v');function refresh(){v.src='/frame?t='+Date.now()}"
                + "v.onload=function(){setTimeout(refresh,55)};v.onerror=function(){setTimeout(refresh,500)};"
                + "refresh()}"
                + "fetch('/info?t='+Date.now()).then(function(r){return r.json()}).then(function(i){autoFit(i);if(i.mode==='h264')startH264(i);else jpeg()}).catch(function(){showHint('无法读取投屏状态')});"
                + "setInterval(function(){fetch('/info?t='+Date.now()).then(function(r){return r.json()}).then(autoFit).catch(function(){})},1000);"
                // 边缘取色只在有黑边（portrait cover）时才做：getImageData 会触发 GPU→CPU
                // 同步读回，弱车机上造成周期性微卡；无黑边时画面外没有要融合的背景。
                // 15s 足够：导航背景色变化很慢。
                + "setInterval(function(){if(document.body.classList.contains('has-side-gap')||document.body.classList.contains('has-tb-gap'))sampleEdgeColors(document.getElementById('v'))},15000);"
                + "addEventListener('resize',updateBlendGeometry);"
                + "</script></body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        writeHead(out, "text/html; charset=utf-8", body.length, keepAlive);
        out.write(body);
        out.flush();
    }

    /** 流式观众探活：画面静止时写不出数据发现不了断线，靠短读超时探测 FIN。仅在空闲分支调用。 */
    private static boolean streamClientGone(Socket socket) {
        try {
            socket.setSoTimeout(500);
            try {
                return socket.getInputStream().read() < 0;
            } finally {
                socket.setSoTimeout(5000);
            }
        } catch (SocketTimeoutException alive) {
            return false;
        } catch (IOException gone) {
            return true;
        }
    }

    private void serveStream(Socket socket, OutputStream out) throws IOException {
        if (capture == null) { serve503(out, "JPEG mode is not active"); return; }
        if (capture.isH264Mode()) { serve503(out, "JPEG mode must be selected on the phone"); return; }
        writeHead(out, "multipart/x-mixed-replace; boundary=" + BOUNDARY, -1);
        streamClients.incrementAndGet();
        try {
        long lastSent = 0;
        long lastWrite = System.currentTimeMillis();
        byte[] last = null;
        while (running) {
            byte[] frame = capture.getLatestJpeg();
            if (frame == null) {
                Thread.sleep(60);
                continue;
            }
            if (frame != last) {
                StringBuilder part = new StringBuilder(160);
                part.append("--").append(BOUNDARY).append("\r\n");
                part.append("Content-Type: image/jpeg\r\n");
                part.append("Content-Length: ").append(frame.length).append("\r\n\r\n");
                out.write(part.toString().getBytes(StandardCharsets.US_ASCII));
                out.write(frame);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                last = frame;
                lastSent = System.currentTimeMillis();
                lastWrite = lastSent;
            } else {
                // 静止画面：写不出数据发现不了观众断开，每秒探活一次清理僵尸计数
                if (System.currentTimeMillis() - lastWrite > 1000 && streamClientGone(socket)) break;
                long wait = 66 - (System.currentTimeMillis() - lastSent);
                if (wait > 0) Thread.sleep(wait);
                else Thread.sleep(5);
            }
        }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            streamClients.decrementAndGet();
        }
    }

    private void serveVideo(Socket socket, OutputStream out) throws IOException {
        if (capture == null || !capture.isH264Mode()) {
            serve503(out, "H.264/MSE mode is not active"); return;
        }
        byte[] init;
        try {
            init = capture.awaitInitSegment(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (init == null || !capture.isH264Mode()) {
            serve503(out, "H.264 initialization timed out");
            return;
        }
        writeHead(out, "video/mp4", -1);
        out.write(init);
        out.flush();

        // Start every viewer at the newest cached IDR. Starting after the newest
        // segment can leave a static screen black until Android submits another frame.
        int targetFrames = capture.getH264BatchFrames();
        long statsStarted = System.currentTimeMillis();
        int statsBatches = 0;
        int statsFrames = 0;
        // 观众必须先注册（计数>0 才开启 fMP4 封装），再读取序号、请求关键帧。
        // 若先 requestSyncFrame 后注册：编码器在两步之间产出的关键帧不会封装成 fMP4，
        // MSE 观众最坏要多等一个 GOP（约 0.5s 黑屏）。注册到断开全程处于 try/finally。
        capture.addMseClient();
        streamClients.incrementAndGet();
        try {
            long sequence = capture.sequenceBeforeLatestKeyframe();
            capture.requestSyncFrame();
            boolean started = false;
            while (running && capture.isH264Mode() && !capture.isEncoderFailed()) {
                CaptureEngine.VideoSegment segment =
                        capture.waitForVideoSegmentAfter(sequence, 3000);
                if (segment == null) {
                    // 静止画面无分片可写时探活，及时回收僵尸 MSE 观众计数与 fMP4 封装
                    if (streamClientGone(socket)) break;
                    continue;
                }
                sequence = segment.sequence;
                if (!started) {
                    if (!segment.keyframe) continue;
                    started = true;
                }
                long deadline = System.currentTimeMillis() + H264_BATCH_WINDOW_MS;
                int frames = 0;
                ByteArrayOutputStream batch = new ByteArrayOutputStream(128 * 1024);
                while (segment != null && frames < targetFrames) {
                    batch.write(segment.data, 0, segment.data.length);
                    frames++;
                    if (frames >= targetFrames) break;
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    segment = capture.waitForVideoSegmentAfter(sequence, remaining);
                    if (segment != null) sequence = segment.sequence;
                }
                // One network flush per ~66 ms batch: 3 frames at 45 FPS, 4 at 60 FPS.
                out.write(batch.toByteArray());
                out.flush();
                statsBatches++;
                statsFrames += frames;
                long now = System.currentTimeMillis();
                if (now - statsStarted >= 5000) {
                    double seconds = (now - statsStarted) / 1000.0;
                    android.util.Log.i("NavMirror", "H.264 delivery "
                            + String.format(java.util.Locale.US, "%.1f batches/s %.1f frames/s %.2f frames/batch",
                            statsBatches / seconds, statsFrames / seconds,
                            statsBatches == 0 ? 0.0 : statsFrames / (double) statsBatches));
                    statsStarted = now;
                    statsBatches = 0;
                    statsFrames = 0;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            capture.removeMseClient();
            streamClients.decrementAndGet();
        }
    }

    private void serveFrame(OutputStream out, boolean keepAlive) throws IOException {
        if (capture == null) { serve503(out, "JPEG mode is not active", keepAlive); return; }
        // Reading a frame must never change the global encoder. This prevents an
        // old fallback tab from forcing a freshly restarted H.264 session to JPEG.
        if (capture.isH264Mode()) { serve503(out, "H.264 capture active", keepAlive); return; }
        byte[] frame = capture.getLatestJpeg();
        long deadline = System.currentTimeMillis() + 1500;
        while (frame == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            frame = capture.getLatestJpeg();
        }
        if (frame == null) { serve503(out, "JPEG frame unavailable", keepAlive); return; }
        writeHead(out, "image/jpeg", frame.length, keepAlive);
        out.write(frame);
        out.flush();
    }

    private void serveInfo(OutputStream out, boolean keepAlive) throws IOException {
        String json;
        if (capture != null) {
            json = "{\"width\":" + capture.getCaptureWidth()
                    + ",\"height\":" + capture.getCaptureHeight()
                    + ",\"port\":" + port
                    + ",\"fps\":" + capture.getFps()
                    + ",\"bitrate\":" + capture.getBitrate()
                    + ",\"actualFps\":" + (Math.round(capture.getActualFps() * 10.0) / 10.0)
                    + ",\"actualBitrate\":" + capture.getActualBitrate()
                    + ",\"batchFrames\":" + capture.getH264BatchFrames()
                    + ",\"batchWindowMs\":" + H264_BATCH_WINDOW_MS
                    + ",\"clients\":" + streamClients.get()
                    + ",\"portrait\":" + capture.isSourcePortrait()
                    + ",\"sourceAspect\":" + capture.getSourceAspect()
                    + ",\"mode\":\"" + (capture.isH264Mode() ? "h264" : "jpeg") + "\""
                    + (capture.isH264Mode() ? ",\"codec\":\"" + capture.codecString() + "\"" : "")
                    // 反控 token 随 /info 刷新：投影重启（服务器重建、token 重新生成）后，
                    // 已打开的车机页面视频走 WS 自动重连恢复，反控靠这里在 1s 内换到新 token，
                    // 否则旧 token 全部 403——"视频正常但反控不可用"的假故障。
                    + ",\"controlToken\":\"" + controlToken + "\""
                    // 实时拖拽观察面板（评审方案第 6 步）：会话是否活跃 + 段/取消/背压计数
                    + ",\"dragActive\":" + RemoteControlService.isDragActive()
                    + ",\"dragSegs\":" + RemoteControlService.dragSegments()
                    + ",\"dragCancel\":" + RemoteControlService.dragCancelledSegments()
                    + ",\"dragDrop\":" + RemoteControlService.dragDroppedPoints()
                    + "}";
        } else {
            json = "{\"mode\":\"stopped\",\"controlToken\":\"" + controlToken + "\"}";
        }
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        writeHead(out, "application/json", body.length, keepAlive);
        out.write(body);
        out.flush();
    }

    private void serveCapability(OutputStream out, boolean keepAlive) throws IOException {
        String bodyText = "<!doctype html><meta charset=utf-8><title>NavMirror capability</title>"
                + "<style>body{font:18px sans-serif;padding:24px;background:#111;color:#eee}</style>"
                + "<h2>NavMirror H.264 capability</h2><pre id=o>检测中...</pre><script>"
                + "fetch('/info').then(function(r){return r.json()}).then(function(i){"
                + "var m='video/mp4; codecs=\"'+(i.codec||'avc1.42E020')+'\"';"
                + "document.getElementById('o').textContent=JSON.stringify(i,null,2)+'\\nWebCodecs VideoDecoder: '+!!window.VideoDecoder"
                + "+'\\nOffscreenCanvas transfer: '+!!(window.HTMLCanvasElement&&HTMLCanvasElement.prototype.transferControlToOffscreen)"
                + "+'\\nLegacy H.264 MSE: '+(!!window.MediaSource&&MediaSource.isTypeSupported(m))+'\\n'+m"
                + "}).catch(function(e){document.getElementById('o').textContent=e})</script>";
        byte[] body = bodyText.getBytes(StandardCharsets.UTF_8);
        writeHead(out, "text/html; charset=utf-8", body.length, keepAlive);
        out.write(body);
        out.flush();
    }

    private void serve503(OutputStream out, String message) throws IOException {
        serve503(out, message, false);
    }

    private void serve503(OutputStream out, String message, boolean keepAlive) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nCache-Control: no-store\r\nConnection: "
                + (keepAlive ? "keep-alive\r\nKeep-Alive: timeout=5, max=100" : "close") + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private void serve404(OutputStream out) throws IOException {
        serve404(out, false);
    }

    private void serve404(OutputStream out, boolean keepAlive) throws IOException {
        byte[] body = "404 Not Found".getBytes(StandardCharsets.US_ASCII);
        out.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: "
                + body.length + "\r\nConnection: "
                + (keepAlive ? "keep-alive\r\nKeep-Alive: timeout=5, max=100" : "close") + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
