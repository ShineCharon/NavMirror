package com.colin.navmirror;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖当前 H.264 播放器（src/main/assets/player.js，WSS + WebCodecs 路径）。
 * 背景：此前 player.js 出现 JavaScript 语法错误时，单元测试（只检查页面上已废弃的
 * 内嵌 MSE 播放器字符串）仍然全部通过——真正的播放器没有任何测试保护。
 */
public class PlayerJsTest {

    private static String playerSource() throws IOException {
        File file = playerJsFile();
        Assume.assumeTrue("player.js not found (unexpected working directory)", file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    static File playerJsFile() {
        File file = new File("src/main/assets/player.js");       // 模块目录（Gradle 单测默认 cwd）
        if (file.isFile()) return file;
        return new File("app/src/main/assets/player.js");        // 工程根目录兜底
    }

    /** 真实语法检查：机器上有 node 时跑 node --check（能查出一切语法错误）。 */
    @Test
    public void playerJs_isSyntacticallyValidJavaScript() throws Exception {
        File file = playerJsFile();
        Assume.assumeTrue("player.js not found", file.isFile());
        String node = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "node.exe" : "node";
        Process process;
        try {
            process = new ProcessBuilder(node, "--check", file.getAbsolutePath())
                    .redirectErrorStream(true).start();
        } catch (IOException nodeNotInstalled) {
            Assume.assumeNoException("node not installed; real syntax check skipped", nodeNotInstalled);
            return;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = process.getInputStream().read(buffer)) >= 0) output.write(buffer, 0, read);
        boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        Assume.assumeTrue("node --check did not finish in 30s", finished);
        assertTrue("player.js 语法错误:\n" + output.toString(StandardCharsets.UTF_8.name()),
                process.exitValue() == 0);
    }

    /** 无 node 环境的兜底：跳过字符串/注释后 ()[]{} 必须配对（player.js 无正则与模板字面量）。 */
    @Test
    public void playerJs_bracketsAreBalancedEvenWithoutNode() throws IOException {
        String source = playerSource();
        int round = 0, square = 0, curly = 0;
        char state = 0; // 0=代码 '\'' '"' '"' '`' '/' '*' 之外的值表示普通代码
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (state == '/') { // 行注释
                if (c == '\n') state = 0;
            } else if (state == '*') { // 块注释
                if (c == '*' && i + 1 < source.length() && source.charAt(i + 1) == '/') { state = 0; i++; }
            } else if (state == '\'' || state == '"') { // 字符串
                if (c == '\\') i++;
                else if (c == state) state = 0;
            } else { // 普通代码
                if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') state = '/';
                else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') state = '*';
                else if (c == '\'' || c == '"') state = c;
                else if (c == '(') round++;
                else if (c == ')') round--;
                else if (c == '[') square++;
                else if (c == ']') square--;
                else if (c == '{') curly++;
                else if (c == '}') curly--;
                assertTrue("多余的右括号 ')' 位于偏移 " + i, round >= 0);
                assertTrue("多余的右方括号 ']' 位于偏移 " + i, square >= 0);
                assertTrue("多余的右花括号 '}' 位于偏移 " + i, curly >= 0);
            }
        }
        assertTrue("存在未闭合的字符串或注释（state=" + (int) state + "）", state == 0);
        assertEquals("圆括号不配对", 0, round);
        assertEquals("方括号不配对", 0, square);
        assertEquals("花括号不配对", 0, curly);
    }

    /** 修复"车机只显示一帧后冻结"的核心设计：慢解码丢帧自愈而不是断线重连。 */
    @Test
    public void slowDecoder_selfHealsAtKeyframeInsteadOfReconnectStorm() throws IOException {
        String source = playerSource();
        // 丢帧后 waitKey 等下一个关键帧（客户端上行 needkey 请求新 IDR 加速恢复）
        assertTrue("缺少 waitKey 自愈逻辑", source.contains("waitKey"));
        assertTrue(source.contains("waitKey = true"));
        assertTrue(source.contains("if (key) waitKey = false"));
        // 进入 waitKey 的瞬间上行请求关键帧（服务端限频承接）
        assertTrue(source.contains("postMessage({type: 'needkey'})"));
        assertTrue(source.contains("socket.send('K')"));
        // 主线程 24 帧保险阀（worker 假死才断线，慢解码不再触发）
        assertTrue(source.contains("pending > 24"));
        // worker 队列>6 丢帧
        assertTrue(source.contains("decodeQueueSize > 6"));
        // 合并 ACK（弱车机消息调度）：worker 攒 4 帧/50ms 回 {count:n}
        assertTrue(source.contains("message.type === 'ack'"));
        assertTrue(source.contains("pending - message.count"));
        assertTrue(source.contains("ackCount >= 4"));
        // 丢帧回 ack 保持 pending 平衡（resync 断线路径已移除）
        assertFalse("不应再存在 resync 断线消息", source.contains("postMessage({type: 'resync'"));
        // 重连去重：closingForResync 标志防止重复 close/重连
        assertTrue(source.contains("closingForResync"));
        assertTrue(source.contains("if (!closingForResync)"));
        // 重连指数退避
        assertTrue(source.contains("Math.min(4000"));
    }

    /** 能力守卫必须检查播放器真正需要的 API（VideoDecoder + Worker + transferControlToOffscreen）。 */
    @Test
    public void capabilityGuard_checksWhatThePlayerActuallyNeeds() throws IOException {
        String source = playerSource();
        assertTrue(source.contains("!window.VideoDecoder || !window.Worker"
                + " || !HTMLCanvasElement.prototype.transferControlToOffscreen"));
        // 能力不足时提示手机端手动切 JPEG，而不是自动降级
        assertTrue(source.contains("手动切换 JPEG"));
    }

    /** 解码器失败必须终态化：isConfigSupported 预检 + unsupported 停机 + fatal 三振 + Worker 崩溃停机。 */
    @Test
    public void decoderFailures_terminateInsteadOfReconnectLoops() throws IOException {
        String source = playerSource();
        // 创建解码器前先问 VideoDecoder 是否真的支持该配置（API 存在不代表配置可用）
        assertTrue(source.contains("VideoDecoder.isConfigSupported(decodeConfig(codec))"));
        // 能力不支持 = 终态，不再空转重连（"连接正常但永远没有画面"）
        assertTrue(source.contains("stopForGood"));
        assertTrue(source.contains("'unsupported'"));
        // 终态守卫：迟到的重连计时器与 connect 本身都不得再建新连接
        assertTrue(source.contains("if (permanentFail) return"));
        // 排定的重连计时器在终态时必须取消
        assertTrue(source.contains("clearTimeout(reconnectTimer)"));
        // 解码错误连续 3 次失败且期间无稳定输出即停（防 decode 持续抛错的重连死循环）
        assertTrue(source.contains("fatalCount >= 3"));
        // fatal 计数只能在稳定输出 25 帧后清零（configured 时清零会让持续故障无限重连）
        assertTrue(source.contains("fatalAtDecoded"));
        assertTrue(source.contains("- fatalAtDecoded >= 25"));
        assertFalse("configured 消息不得清零 fatalCount", source.contains("message.codec; fatalCount = 0;"));
        // Worker 崩溃：onerror 停 socket，消息不再投给死 Worker
        assertTrue(source.contains("worker.onerror"));
        // 探测期间丢帧（防止引用链断裂的增量帧被解码）
        assertTrue(source.contains("if (checking)"));
        // reset 使过期的 isConfigSupported 回调作废（代际号守卫）
        assertTrue(source.contains("generation"));
        assertTrue(source.contains("gen !== generation"));
        assertTrue(source.contains("generation++"));
        // configure 抛异常 → unsupported 终态，不是 fatal
        assertTrue(source.contains("decoder = null;"));
    }

    /** 热路径零分配：每帧 NAL 扫描不得创建数组/对象（720P60 车机 GC 压力主因）。 */
    @Test
    public void frameHotPath_isZeroAllocation() throws IOException {
        String source = playerSource();
        // 零分配定位：findNal 返回偏移而不是 units 数组
        assertTrue(source.contains("function findNal(data, wanted)"));
        assertTrue(source.contains("findNal(data, 5)"));
        assertFalse("旧 scan() 每帧分配数组+对象，必须移除", source.contains("function scan(data)"));
        assertFalse(source.contains("units.push"));
        // 低频性能统计：绘制均耗以实际 drawImage 帧数为分母（提交帧数会在积压时低估）；
        // 输出 FPS / 提交→输出延迟 / 队列峰值 / 接收帧率 / 只解码开关
        assertTrue(source.contains("qMax"));
        assertTrue(source.contains("drawMs / drawFrames"));
        assertTrue(source.contains("drawFps"));
        assertTrue(source.contains("latAvg"));
        assertTrue(source.contains("latMax"));
        assertTrue(source.contains("clientStats.rtcFps = framesReceived * 1000 / elapsed"));
        assertTrue("缺 ?nodraw 只解码 A/B 开关", source.contains("nodraw: /nodraw/.test(location.search)"));
    }

    /** 播放器是纯 WSS + WebCodecs(Annex-B) 实现，不得混入旧 MSE 代码。 */
    @Test
    public void h264Path_isWebCodecsOnly_noLegacyMse() throws IOException {
        String source = playerSource();
        assertTrue(source.contains("new VideoDecoder"));
        assertTrue(source.contains("format: 'annexb'"));
        assertTrue(source.contains("optimizeForLatency"));
        assertTrue(source.contains("hardwareAcceleration: 'prefer-hardware'"));
        assertFalse(source.contains("MediaSource"));
        assertFalse(source.contains("appendBuffer"));
        assertFalse(source.contains("addSourceBuffer"));
        assertFalse(source.contains("RTCPeerConnection"));
    }

    /**
     * 实时反控上行通道：视频 WS 上承载拖拽消息（DB/DM/DU/DC），随重连自动指向新 socket；
     * 发送失败返回 false → 页面回退到松手回放（JPEG 模式无本脚本即无此通道）。
     */
    @Test
    public void exposesDragUplinkOverVideoWebSocket() throws IOException {
        String source = playerSource();
        assertTrue(source.contains("window.navSendDrag"));
        assertTrue(source.contains("socket.readyState === 1"));
        assertTrue(source.contains("return false"));
        // 通道必须读取闭包里的 socket 变量（重连后 socket 被重新赋值，函数自动跟随）
        int assign = source.indexOf("socket = new WebSocket");
        int expose = source.indexOf("window.navSendDrag");
        int socketVar = source.indexOf("var socket = null");
        assertTrue(socketVar >= 0 && expose > socketVar);
    }

    private static void assertEquals(String message, int expected, int actual) {
        assertTrue(message + "（期望 " + expected + "，实际 " + actual + "）", expected == actual);
    }
}
