(function () {
  'use strict';

  function decoderWorker() {
    var canvas, ctx, decoder, configured = false, decoded = 0, dropped = 0;
    var lastStats = 0, waitKey = false, checking = false;
    // 代际号：reset 时 +1，过期 isConfigSupported 回调对结果作废——
    // 否则旧 Promise 可能在新连接建立后配置旧关键帧、甚至误入 unsupported 终态
    var generation = 0;
    // A/B 开关（?nodraw）：只解码不绘制，用于把 Canvas2D/合成从解码能力里分离出来测量
    var nodraw = false;
    // 合并 ACK：弱车机上每帧一条 Worker→主线程消息（720P60 = 120 条/秒）调度成本显著；
    // 攒 4 帧或 50ms 后一次回 {count:n}，主线程 pending -= count
    var ackCount = 0, lastAckAt = 0;
    // 低频统计（400ms 窗口）：以实际完成 drawImage 的帧数为分母（不是提交解码的帧数，
    // 否则解码积压时会低估绘制耗时）；另测 提交→VideoFrame 输出 延迟与队列峰值
    var drawMs = 0, drawFrames = 0, qMax = 0;
    var latSum = 0, latMax = 0, latFrames = 0;
    var submitted = new Map();

    function ack() {
      ackCount++;
      var now = performance.now();
      if (ackCount >= 4 || now - lastAckAt > 50) {
        postMessage({type: 'ack', count: ackCount});
        ackCount = 0; lastAckAt = now;
      }
    }

    function flushStats(now) {
      if (ackCount > 0) { postMessage({type: 'ack', count: ackCount}); ackCount = 0; }
      var window = Math.max(1, now - lastStats);
      lastStats = now;
      postMessage({type: 'stats', decoded: decoded, dropped: dropped,
        queue: decoder ? decoder.decodeQueueSize : 0,
        draw: drawFrames ? Math.round(drawMs / drawFrames * 10) / 10 : 0,
        drawFps: Math.round(drawFrames * 100000 / window) / 100,
        qMax: qMax,
        latAvg: latFrames ? Math.round(latSum / latFrames * 10) / 10 : 0,
        latMax: Math.round(latMax)});
      drawMs = 0; drawFrames = 0; qMax = 0;
      latSum = 0; latMax = 0; latFrames = 0;
    }

    // 零分配 Annex-B NAL 定位：返回目标类型 NAL 的载荷偏移（起始码后），找不到返回 -1。
    // 旧 scan() 每帧分配数组+对象，720P60 在车机上造成持续 GC。
    function findNal(data, wanted) {
      var n = data.length;
      for (var i = 0; i + 3 < n;) {
        if (data[i] === 0 && data[i + 1] === 0
            && (data[i + 2] === 1 || (data[i + 2] === 0 && i + 4 < n && data[i + 3] === 1))) {
          var at = i + (data[i + 2] === 1 ? 3 : 4);
          if (at < n && (data[at] & 31) === wanted) return at;
          i = at;
        } else i++;
      }
      return -1;
    }

    function hex(n) { return ('0' + n.toString(16)).slice(-2).toUpperCase(); }

    function codecFrom(data, spsAt) {
      if (spsAt < 0 || spsAt + 3 >= data.length) return null;
      return 'avc1.' + hex(data[spsAt + 1]) + hex(data[spsAt + 2]) + hex(data[spsAt + 3]);
    }

    function decodeConfig(codec) {
      return {codec: codec, avc: {format: 'annexb'},
        hardwareAcceleration: 'prefer-hardware', optimizeForLatency: true};
    }

    function configure(codec) {
      try {
        decoder = new VideoDecoder({
          output: function (frame) {
            try {
              var submittedAt = submitted.get(frame.timestamp);
              if (submittedAt !== undefined) {
                var lat = performance.now() - submittedAt;
                latSum += lat; latMax = Math.max(latMax, lat); latFrames++;
                submitted.delete(frame.timestamp);
              }
              if (nodraw) { decoded++; return; }   // A/B：只解码，不解码-绘制链路分离
              if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
                canvas.width = frame.displayWidth; canvas.height = frame.displayHeight;
              }
              var t0 = performance.now();
              ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
              drawMs += performance.now() - t0;
              drawFrames++;
              decoded++;
            } finally { frame.close(); }
          },
          error: function (error) { postMessage({type: 'fatal', message: String(error)}); }
        });
        decoder.configure(decodeConfig(codec));
      } catch (error) {
        decoder = null;   // 配置抛异常 = 不支持，走 unsupported 终态而不是 fatal 重连循环
        return false;
      }
      configured = true;
      postMessage({type: 'configured', codec: codec});
      return true;
    }

    function decodeChunk(data, key, timestamp) {
      submitted.set(timestamp, performance.now());
      decoder.decode(new EncodedVideoChunk({type: key ? 'key' : 'delta',
        timestamp: timestamp, data: data}));
      if (decoder.decodeQueueSize > qMax) qMax = decoder.decodeQueueSize;
      ack();
      var now = performance.now();
      if (now - lastStats > 400) flushStats(now);
    }

    onmessage = function (event) {
      var message = event.data;
      if (message.type === 'init') {
        canvas = message.canvas;
        nodraw = !!message.nodraw;
        ctx = canvas.getContext('2d', {alpha: false, desynchronized: true});
        return;
      }
      if (message.type === 'reset') {
        generation++;
        if (decoder) try { decoder.close(); } catch (_) {}
        decoder = null; configured = false; waitKey = false;
        // 探测中的旧 isConfigSupported 回调作废；新关键帧到达时重新探测
        checking = false;
        submitted = new Map();
        ackCount = 0;
        return;
      }
      if (message.type !== 'frame') return;
      var data = new Uint8Array(message.data), key = findNal(data, 5) >= 0;
      // 探测期间丢帧并置 waitKey（探测完成前到达的增量帧引用链会断）
      if (checking) {
        waitKey = true; dropped++;
        ack();
        return;
      }
      if (!configured) {
        if (!key) { dropped++; ack(); return; }
        var codec = codecFrom(data, findNal(data, 7));
        if (!codec) { dropped++; ack(); return; }
        checking = true;
        // 先问解码器是否真的支持，再创建解码器：API 存在不代表该配置可用，
        // Chrome 对 isConfigSupported 的否定结果比抛异常更可靠
        var kept = data, keptTs = message.timestamp, gen = generation;
        VideoDecoder.isConfigSupported(decodeConfig(codec)).then(function (r) {
          if (gen !== generation) return;   // reset 后过期：不配置、不进终态
          checking = false;
          if (!r || !r.supported) { postMessage({type: 'unsupported', codec: codec}); return; }
          if (!configure(codec)) { postMessage({type: 'unsupported', codec: codec}); return; }
          decodeChunk(kept, true, keptTs);   // 触发探测的关键帧立即解码出图
        }, function () {
          if (gen !== generation) return;
          checking = false;
          postMessage({type: 'unsupported', codec: codec});
        });
        return;
      }
      // 慢解码不再断线重连：丢帧后等下一个关键帧自愈。
      // 断线重连会触发服务端 sync 帧洪泛，反过来加剧积压 —— 车机只显示一帧的根因。
      // 进入 waitKey 的瞬间上行请求新 IDR（服务端限频），积压恢复不再等 GOP。
      if (key) waitKey = false;
      if (!key && (waitKey || decoder.decodeQueueSize > 6)) {
        if (!waitKey) postMessage({type: 'needkey'});
        waitKey = true;
        dropped++;
        ack();
        return;
      }
      try {
        decodeChunk(data, key, message.timestamp);
      } catch (error) {
        postMessage({type: 'fatal', message: String(error)});
        return;
      }
    };
  }

  window.startH264 = function (info) {
    if (!window.VideoDecoder || !window.Worker || !HTMLCanvasElement.prototype.transferControlToOffscreen) {
      showHint('当前车机不支持 H.264 硬件解码，请在手机端手动切换 JPEG');
      clientStats.ice = 'unsupported'; renderStats(); return;
    }
    var canvas = document.createElement('canvas');
    canvas.id = 'v'; canvas.width = info.width; canvas.height = info.height;
    host.appendChild(canvas);
    var source = '(' + decoderWorker.toString() + ')()';
    var worker = new Worker(URL.createObjectURL(new Blob([source], {type: 'application/javascript'})));
    var offscreen = canvas.transferControlToOffscreen();
    // ?nodraw：只解码不绘制的 A/B 开关（判断 Canvas2D/合成是否为瓶颈）
    worker.postMessage({type: 'init', canvas: offscreen, nodraw: /nodraw/.test(location.search)},
        [offscreen]);
    var socket = null, retry = 350, pending = 0, timestamp = 0;
    var bytes = 0, lastBytes = 0, lastRateAt = performance.now(), closingForResync = false;
    var permanentFail = false, fatalCount = 0, fatalAtDecoded = -1, reconnectTimer = 0;
    var framesReceived = 0, lastKeyReq = 0;

    // 实时反控上行通道（页面指针处理器使用）：走视频 WS，随重连自动指向新 socket。
    // JPEG 模式不加载本脚本 → 页面侧 navSendDrag 不存在 → 自动回退到松手回放路径。
    window.navSendDrag = function (msg) {
      if (socket && socket.readyState === 1) {
        try { socket.send(msg); return true; } catch (_) {}
      }
      return false;
    };

    function stopForGood(state, hint) {
      // 终态：能力不支持 / 解码连续失败 / Worker 崩溃——继续重连只会空转
      permanentFail = true;
      clearTimeout(reconnectTimer);   // 已排定的重连不再执行
      if (hint) showHint(hint);
      clientStats.ice = state; renderStats();
      if (socket) try { socket.close(); } catch (_) {}
    }
    function reconnect() {
      if (permanentFail) return;
      if (socket) try { socket.close(); } catch (_) {}
      socket = null; pending = 0; worker.postMessage({type: 'reset'});
      clearTimeout(reconnectTimer);
      reconnectTimer = setTimeout(connect, retry); retry = Math.min(4000, Math.round(retry * 1.6));
    }
    function connect() {
      if (permanentFail) return;   // 终态后不再建新连接（迟到的重连计时器兜底）
      closingForResync = false;
      var scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
      socket = new WebSocket(scheme + location.host + '/ws');
      socket.binaryType = 'arraybuffer';
      clientStats.ice = 'connecting'; renderStats();
      socket.onopen = function () { retry = 350; clientStats.ice = 'connected'; renderStats(); };
      socket.onmessage = function (event) {
        if (!(event.data instanceof ArrayBuffer)) return;
        // 24 = 最后一道保险（worker 假死）；慢解码靠 worker 侧丢帧自愈，不再断线。
        if (pending > 24) {
          closingForResync = true; clientStats.hard++;
          try { socket.close(); } catch (_) {} return;
        }
        framesReceived++;
        bytes += event.data.byteLength; pending++; timestamp += Math.round(1000000 / Math.max(1, info.fps || 30));
        worker.postMessage({type: 'frame', data: event.data, timestamp: timestamp}, [event.data]);
      };
      socket.onerror = function () { try { socket.close(); } catch (_) {} };
      socket.onclose = function () {
        if (permanentFail) return;
        clientStats.ice = 'reconnecting'; renderStats(); reconnect();
      };
    }
    worker.onmessage = function (event) {
      var message = event.data;
      // 合并 ACK：worker 攒 4 帧或 50ms 回一次 {count:n}（弱车机消息调度成本）
      if (message.type === 'ack') pending = Math.max(0, pending - message.count);
      if (message.type === 'configured') { clientStats.codec = 'H.264 · ' + message.codec; }
      if (message.type === 'needkey') {
        // 解码积压丢帧：请求服务端新 IDR（端 1s 限频 + 服务端 500ms 限频）
        var now = performance.now();
        if (socket && socket.readyState === 1 && now - lastKeyReq > 1000) {
          lastKeyReq = now;
          try { socket.send('K'); } catch (_) {}
        }
      }
      if (message.type === 'stats') {
        clientStats.decoded = message.decoded || 0; clientStats.dropped = message.dropped || 0;
        clientStats.q = message.queue || 0;
        clientStats.draw = message.draw || 0; clientStats.qMax = message.qMax || 0;
        clientStats.drawFps = message.drawFps || 0;
        clientStats.latAvg = message.latAvg || 0; clientStats.latMax = message.latMax || 0;
        // 稳定输出 25 帧后才清零 fatal 计数：configured 时清零会让"fatal→重连→configured→
        // fatal→……"的持续故障无限循环（配置成功≠解码成功）
        if (fatalCount > 0 && fatalAtDecoded >= 0
            && (message.decoded || 0) - fatalAtDecoded >= 25) { fatalCount = 0; fatalAtDecoded = -1; }
        renderStats();
      } else if (message.type === 'unsupported') {
        // 能力探测/配置失败：终态停止重连（否则形成"连接正常但永远没有画面"的空转）
        stopForGood('unsupported', '当前车机不支持 H.264 硬件解码，请在手机端手动切换 JPEG');
      } else if (message.type === 'fatal') {
        // 解码错误可重试，但连续 3 次失败且期间无稳定输出即停（防 decode 持续抛错的重连死循环）
        fatalCount++;
        if (fatalAtDecoded < 0) fatalAtDecoded = clientStats.decoded || 0;
        if (fatalCount >= 3) {
          stopForGood('decoder error', 'H.264 解码连续失败，请在手机端手动切换 JPEG');
        } else if (!closingForResync) {
          closingForResync = true; clientStats.hard++;
          if (socket) try { socket.close(); } catch (_) {}
        }
      } else if (message.type === 'resync') {
        if (!closingForResync) {
          closingForResync = true; clientStats.hard++;
          if (socket) try { socket.close(); } catch (_) {}
        }
      }
    };
    worker.onerror = function () {
      // Worker 已崩溃：后续 postMessage 无人应答，pending 只会无限涨——永久停机等用户处理
      stopForGood('decoder error', 'H.264 解码器启动失败，请在手机端手动切换 JPEG');
    };
    setInterval(function () {
      var now = performance.now(), elapsed = Math.max(1, now - lastRateAt);
      clientStats.rtcMbps = (bytes - lastBytes) * 8 / elapsed / 1000;
      clientStats.rtcFps = framesReceived * 1000 / elapsed;
      framesReceived = 0;
      clientStats.qb = pending; lastBytes = bytes; lastRateAt = now; renderStats();
    }, 1000);
    connect();
  };
})();
