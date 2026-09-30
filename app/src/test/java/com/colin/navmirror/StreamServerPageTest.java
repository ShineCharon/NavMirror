package com.colin.navmirror;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StreamServerPageTest {
    @Test
    public void defaultPage_hasLiveControlsAndAutomaticFit() throws Exception {
        String page = render("");
        assertTrue(page.contains("id=\"fitContain\""));
        assertTrue(page.contains("id=\"fitCover\""));
        assertTrue(page.contains("var fitLocked=false,initialFit='contain'"));
        assertTrue(page.contains("function autoFit(i)"));
        assertTrue(page.contains("setFit(i.portrait?'cover':'contain',false)"));
        assertTrue(page.contains("if(!fitLocked)setFit(i.portrait?'cover':'contain',false)"));
        assertFalse(page.contains("href=\"?mode=cover\""));
    }

    @Test
    public void explicitModes_lockTheManualSelection() throws Exception {
        assertTrue(render("cover").contains("var fitLocked=true,initialFit='cover'"));
        assertTrue(render("contain").contains("var fitLocked=true,initialFit='contain'"));
    }

    @Test
    public void controls_updateSelectionWithoutReloadingTheStream() throws Exception {
        String page = render("");
        assertTrue(page.contains("fitContain.onclick=function(){setFit('contain',true)}"));
        assertTrue(page.contains("fitCover.onclick=function(){setFit('cover',true)}"));
        assertTrue(page.contains("classList.toggle('active'"));
        assertTrue(page.contains("id=\"perf\""));
    }

    /** 单帧快照已从网页移除（用户需求）：按钮/JS/端点全部不存在。 */
    @Test
    public void snapshotOption_removedFromPage() throws Exception {
        String page = render("");
        assertFalse(page.contains("id=\"shot\""));
        assertFalse(page.contains("captureH264Frame"));
        assertFalse(page.contains("/shot'"));
        assertFalse(page.contains("单帧"));
    }

    /** 反控 token 随 /info 刷新：投影重启后旧页面 1s 内换到新 token（视频重连但反控 403 的假故障）。 */
    @Test
    public void reverseControl_tokenRefreshesViaInfoPoll() throws Exception {
        String page = render("");
        assertTrue(page.contains("if(i.controlToken&&i.controlToken!==controlToken)controlToken=i.controlToken"));
        // 403（token 过期）不当"服务已关闭"：立即拉 /info 换新 token 并重试该动作一次
        // ——弱车机轮询慢，重启后窗口期的点击不能被吞掉、面板不能被误关
        assertTrue(page.contains("r.status!==403"));
        assertTrue(page.contains("if(i&&i.controlToken)controlToken=i.controlToken;"));
        // /info（含 stopped 分支）携带 token，页面轮询即可续期
        StreamServer server = new StreamServer(9999, null);
        Method method = StreamServer.class.getDeclaredMethod(
                "serveInfo", java.io.OutputStream.class, boolean.class);
        method.setAccessible(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        method.invoke(server, out, false);
        String response = out.toString(StandardCharsets.UTF_8.name());
        assertTrue(response.contains("\"controlToken\":\""));
    }

    @Test
    public void modeButton_removedFromPage_switchLivesOnPhoneApp() throws Exception {
        // 切换入口收敛到手机 App 右上角 [JPEG][H.264] 徽章：网页工具条不再放模式按钮
        String page = render("");
        assertFalse(page.contains("id=\"mode\""));
        assertFalse(page.contains("modeBtn"));
        assertFalse(page.contains("/h264-mode"));
        // 网页不能改变采集模式；只能在手机 App 选择后重新开始投屏。
        assertFalse(page.contains("/jpeg-mode"));
        assertFalse(page.contains("catch(jpeg)"));
    }

    @Test
    public void h264Client_usesSameOriginWebSocketAndWebCodecsWithoutWebRtc() throws Exception {
        String page = render("");
        assertTrue(page.contains("src=\"/player.js\""));
        assertTrue(page.contains("if(i.mode==='h264')startH264(i)"));
        assertTrue(page.contains("H.264  '+clientStats.ice"));
        assertFalse(page.contains("RTCPeerConnection"));
        assertFalse(page.contains("/webrtc/offer"));
        assertFalse(page.contains("i.mode==='webrtc'"));
    }

    @Test
    public void legacyMsePlayer_isRemovedFromPage_wssWebCodecsIsTheOnlyH264Path() throws Exception {
        // 旧 MSE 播放器(h264函数)从未被启动逻辑调用，只是随页面发给车机的死代码——已移除。
        // /video 端点保留用于手工回归，但页面不再内嵌 MSE 播放器。
        String page = render("");
        assertFalse(page.contains("function h264(info)"));
        assertFalse(page.contains("addSourceBuffer"));
        assertFalse(page.contains("appendBuffer"));
        assertFalse(page.contains("q.length>=8"));
        assertFalse(page.contains("playbackRate=1.06"));
        assertFalse(page.contains("fastSeek"));
        assertFalse(page.contains("fetch('/video?t="));
        // 启动逻辑只分派两条路：h264 -> player.js 的 startH264；jpeg -> /frame 轮询
        assertTrue(page.contains("if(i.mode==='h264')startH264(i);else jpeg()"));
        assertTrue(page.contains("function jpeg(){if(fallen)return;fallen=true;host.innerHTML='<img id=\"v\">';"));
        // 页面内联脚本不再出现 JS 对象/正则字面量拼接错误的高危模式（原 MSE 播放器的问题源头）
        assertFalse(page.contains("function legacyVideoError"));
    }

    @Test
    public void seasonalBackground_isStaticCssGradientWithNoDecodeCost() throws Exception {
        String page = render("");
        assertTrue(page.contains("body.season-spring"));
        assertTrue(page.contains("body.season-summer"));
        assertTrue(page.contains("body.season-autumn"));
        assertTrue(page.contains("body.season-winter"));
        assertTrue(page.contains("classList.add('season-'+season)"));
        assertTrue(page.contains("classList.toggle('landscape-source',!i.portrait)"));
        assertTrue(page.contains("body.landscape-source:not(.cover) #host"));
        // 全屏动画 WebP（软件逐帧解码）已移除：车机 GPU/CPU 只服务视频合成
        assertFalse(page.contains("<img id=\"ambient\""));
        assertFalse(page.contains(".webp"));
        assertFalse(page.contains("/season/"));
        assertFalse(page.contains("amb."));
        assertFalse(page.contains("requestAnimationFrame"));
    }

    @Test
    public void sideBars_useLowFrequencyColorSamplingFeatherAndStaticShadow() throws Exception {
        String page = render("");
        assertTrue(page.contains("id=\"edgeTint\""));
        assertTrue(page.contains("id=\"featherL\""));
        assertTrue(page.contains("id=\"featherR\""));
        assertTrue(page.contains("id=\"frameShadow\""));
        assertTrue(page.contains("sampleCanvas.width=32"));
        assertTrue(page.contains("sampleCanvas.height=18"));
        // 取色只在有留白时执行（getImageData 的 GPU→CPU 同步读回在弱车机上造成周期性微卡），
        // 且 15s 低频足够（导航背景色变化慢）；左右或上下任一留白都触发
        assertTrue(page.contains("contains('has-side-gap')||document.body.classList.contains('has-tb-gap'))"
                + "sampleEdgeColors(document.getElementById('v'))},15000)"));
        assertTrue(page.contains("classList.toggle('has-side-gap',gap)"));
        assertFalse(page.contains("has-side-gap:not(.cover)"));
        // 上下留白融合（与左右对称）：行取色 + featherT/B + frameShadow 任一留白都显示
        assertTrue(page.contains("classList.toggle('has-tb-gap',top>10)"));
        assertTrue(page.contains("function avgRow(cy)"));
        assertTrue(page.contains("avgRow(1),b=avgRow(16)"));
        assertTrue(page.contains("'--edge-top'"));
        assertTrue(page.contains("'--edge-bottom'"));
        assertTrue(page.contains("id=\"featherT\""));
        assertTrue(page.contains("id=\"featherB\""));
        assertTrue(page.contains("body.has-side-gap #frameShadow,body.has-tb-gap #frameShadow{display:block"));
        // 只有上下留白时色调层切纵向渐变（左右留白保持横向，双向都有时左右优先）
        assertTrue(page.contains("body.has-tb-gap:not(.has-side-gap) #edgeTint{background:linear-gradient(180deg,"));
    }

    @Test
    public void reverseControl_isOptInTokenProtectedAndUsesVisibleContentCoordinates() throws Exception {
        String page = render("");
        assertTrue(page.contains("id=\"remote\""));
        assertTrue(page.contains("id=\"remoteKeys\""));
        assertTrue(page.contains("var controlToken='"));
        assertTrue(page.contains("{method:'POST',cache:'no-store'}"));
        assertTrue(page.contains("function normalizedPoint(e)"));
        assertTrue(page.contains("controlRect.left"));
        assertTrue(page.contains("host.addEventListener('pointerdown'"));
        assertTrue(page.contains("postControl(elapsed>=480?'long':'tap'"));
        assertTrue(page.contains("postControl('swipe'"));
        assertFalse(page.contains("class=\"control-on\""));
        // 竖屏（高）视口下侧空隙窄（如 800x1280 只 112px）：竖排侧置不再要求 vw>vh
        // （横排按钮会压进视频区——真机反馈"竖屏时底下按钮挡住投屏"）
        assertTrue(page.contains("side-controls',streamInfo.portrait&&left>=76)"));
        assertFalse(page.contains("vw>vh"));
        assertTrue(page.contains(
                "body.portrait-source.side-controls #remoteKeys{top:50%;bottom:auto;transform:translateY(-50%);flex-direction:column;left:max(8px,env(safe-area-inset-left))}"));
    }

    @Test
    public void portraitToolbar_staysCompactInRightSideGap() throws Exception {
        String page = render("");
        // #ctl 默认有 right；竖屏时若再设 left 而不释放 right，工具栏背景会横向撑满。
        assertTrue(page.contains("body.portrait-source.side-controls #ctl{top:50%;bottom:auto;"
                + "transform:translateY(-50%);flex-direction:column;left:auto;"
                + "right:max(8px,env(safe-area-inset-right))}"));
        // 反控导航键仍位于左侧，避免与右侧工具栏重叠。
        assertTrue(page.contains("body.portrait-source.side-controls #remoteKeys{top:50%;bottom:auto;"
                + "transform:translateY(-50%);flex-direction:column;left:max(8px,env(safe-area-inset-left))}"));
    }

    @Test
    public void shortResponses_canReuseHttpsConnection() throws Exception {
        StreamServer server = new StreamServer(9999, null);
        Method method = StreamServer.class.getDeclaredMethod(
                "serveIndex", java.io.OutputStream.class, String.class, boolean.class);
        method.setAccessible(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        method.invoke(server, out, "", true);
        String response = out.toString(StandardCharsets.UTF_8.name());
        assertTrue(response.contains("Connection: keep-alive"));
        assertTrue(response.contains("Keep-Alive: timeout=5, max=100"));
    }

    @Test
    public void capabilityPage_probesWhatThePlayerActuallyRequires() throws Exception {
        StreamServer server = new StreamServer(9999, null);
        Method method = StreamServer.class.getDeclaredMethod(
                "serveCapability", java.io.OutputStream.class, boolean.class);
        method.setAccessible(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        method.invoke(server, out, false);
        String response = out.toString(StandardCharsets.UTF_8.name());
        // 播放器真正要求的是 transferControlToOffscreen（不是泛泛的 window.OffscreenCanvas），
        // 能力页据此探测才能与 startH264 的守卫一致，不会误报支持。
        assertTrue(response.contains("HTMLCanvasElement.prototype.transferControlToOffscreen"));
        assertFalse(response.contains("!!window.OffscreenCanvas"));
        assertTrue(response.contains("window.VideoDecoder"));
    }

    private static String render(String mode) throws Exception {
        StreamServer server = new StreamServer(9999, null);
        Method method = StreamServer.class.getDeclaredMethod(
                "serveIndex", java.io.OutputStream.class, String.class);
        method.setAccessible(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        method.invoke(server, out, mode);
        String response = out.toString(StandardCharsets.UTF_8.name());
        int body = response.indexOf("\r\n\r\n");
        return body >= 0 ? response.substring(body + 4) : response;
    }

    /**
     * 实时跟手拖拽（评审方案）：WSS 上行 DB/DM/DU/DC + 20-25Hz 合并上报（只发最新点）
     * + 本地触点指示（不本地平移画面，防双重移动/回弹）+ JPEG 模式自动回退松手回放。
     */
    @Test
    public void reverseControl_liveDragUsesWsUplinkWithThrottleAndIndicator() throws Exception {
        String page = render("");
        // 上行通道由 player.js 暴露（H.264 WS）；JPEG 模式无该通道 → sendDrag 返回 false → 回退
        assertTrue(page.contains("window.navSendDrag"));
        assertTrue(page.contains("sendDrag('DB',"));
        assertTrue(page.contains("sendDrag('DM',"));
        assertTrue(page.contains("sendDrag('DU',"));
        assertTrue(page.contains("'DC'"));
        // 45ms ≈ 20-25Hz：合并上报，只保留最新点（绝不逐点 POST）
        assertTrue(page.contains("lastDragSend>=45"));
        // 本地即时反馈：触点指示（CSS + JS 动态创建）+ 收起
        assertTrue(page.contains("showTouchDot"));
        assertTrue(page.contains("#touchDot{position:fixed"));
        assertTrue(page.contains("touchDot.id='touchDot'"));
        // 回退路径的回放时长钳制：min(elapsed×0.6, 250ms)，下限 80ms
        // （松手后才动的问题由实时注入解决；钳制只让回退模式不至于慢拖长放）
        assertTrue(page.contains("Math.max(80,Math.min(elapsed*0.6,250))"));
        // 实时路径不走 postControl（无 HTTP 往返、无 403 误判面）
        assertTrue(page.contains("dragLive=false;return}"));
    }

    /** 拖拽帧坐标解析（"0.5123,0.4812"）：格式/范围/垃圾输入全防，越界钳到 [0,1]。 */
    @Test
    public void parseDragPoint_validatesCoordinates() {
        assertArrayEquals(new float[]{0.5f, 0.25f}, StreamServer.parseDragPoint("0.5,0.25"), 1e-6f);
        assertArrayEquals(new float[]{0f, 1f}, StreamServer.parseDragPoint("0,1"), 1e-6f);
        assertArrayEquals(new float[]{1f, 0f}, StreamServer.parseDragPoint("1.4,-0.2"), 1e-6f);   // 越界钳位
        assertNull(StreamServer.parseDragPoint(null));
        assertNull(StreamServer.parseDragPoint(""));
        assertNull(StreamServer.parseDragPoint("0.5"));
        assertNull(StreamServer.parseDragPoint("0.5,"));
        assertNull(StreamServer.parseDragPoint(",0.5"));
        assertNull(StreamServer.parseDragPoint("0.5,0.5,0.5"));
        assertNull(StreamServer.parseDragPoint("0.5;0.5"));
        assertNull(StreamServer.parseDragPoint("abc,0.5"));
        assertNull(StreamServer.parseDragPoint("NaN,0.5"));
        assertNull(StreamServer.parseDragPoint("Infinity,0.5"));
        // 超长输入直接拒绝（协议帧恒短，防滥用）
        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 30; i++) longBody.append('1');
        assertNull(StreamServer.parseDragPoint(longBody.toString()));
    }

    /** 断开自动终止拖拽：reader 退出路径必须调用 dragCancel（防触点永久按下）。 */
    @Test
    public void wsReader_cancelsLiveDragOnDisconnect() throws IOException {
        String source = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/colin/navmirror/StreamServer.java")),
                StandardCharsets.UTF_8);
        int readerExit = source.indexOf("ws reader exit");
        int cancel = source.indexOf("RemoteControlService.dragCancel()", readerExit);
        assertTrue(cancel > readerExit);
        // DB/DM/DU 转发与连接级限频（1s 窗口 30 条，正常 20-25Hz + 起止帧）
        assertTrue(source.contains("dragWindowStart"));
        assertTrue(source.contains("++dragCount <= 30"));
        assertTrue(source.contains("RemoteControlService.dragBegin(xy[0], xy[1])"));
        assertTrue(source.contains("RemoteControlService.dragMove(xy[0], xy[1])"));
        assertTrue(source.contains("RemoteControlService.dragEnd(xy[0], xy[1])"));
    }
}
