/* ═══════════════════════════════════════════════════════
   Octopus 网页遥控台 — 应用逻辑
   ═══════════════════════════════════════════════════════ */
(function () {
  'use strict';

  /* ── 基础配置 ── */
  // token 从 URL fragment (#token=xxx) 读取,避免泄漏到浏览器历史/日志/Referer。
  // 兼容旧版 ?token= 查询串,读取后立即用 replaceState 剥离。
  const h = new URLSearchParams(location.hash.replace(/^#/, ''));
  const q = new URLSearchParams(location.search);
  const TOKEN = h.get('token') || q.get('token') || '';
  if (q.get('token')) {
    // 旧版链接带 ?token=,升级后剥离查询串避免泄漏
    history.replaceState(null, '', location.pathname + (TOKEN ? '#token=' + encodeURIComponent(TOKEN) : ''));
  } else if (h.get('token')) {
    // fragment 也剥离,避免分享链接时泄漏
    history.replaceState(null, '', location.pathname);
  }
  let DW = 1080, DH = 2400;

  /* ── DOM 引用 ── */
  const img = document.getElementById('screen');
  const st = document.getElementById('st');
  const msg2 = document.getElementById('msg2');
  const chat = document.getElementById('chat');
  const ta = document.getElementById('ta');
  const sendBtn = document.getElementById('send');
  const runEl = document.getElementById('run');
  const latencyEl = document.getElementById('latency');
  const helpPanel = document.getElementById('help-panel');

  /* ── 工具函数 ── */
  // API 路径不再拼接 token;fetch 调用统一用 Authorization 头鉴权。
  function api(p) {
    return p;
  }

  // <img> MJPEG 流无法带 Authorization 头,且长期 token 不得进入 URL:
  // 先用 Bearer 头换取 30s 一次性 ticket,再用 ?ticket= 打开流(ticket 用后即废)。
  async function fetchStreamTicket() {
    const res = await fetch(api('/api/screen/stream-ticket'), {
      method: 'POST',
      cache: 'no-store',
      headers: authHeaders()
    });
    const j = await res.json();
    if (!res.ok || !j || j.code !== 0 || !j.data || !j.data.ticket) throw new Error('ticket');
    return j.data.ticket;
  }

  function authHeaders(extra) {
    return Object.assign({ 'Authorization': 'Bearer ' + TOKEN }, extra || {});
  }

  async function post(action, body) {
    if (!TOKEN) {
      msg2.textContent = '缺少 token,请从应用内复制带 token 的遥控台链接';
      return;
    }
    try {
      const res = await fetch(api('/api/control/input'), {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(Object.assign({ action }, body || {}))
      });
      const j = await res.json().catch(() => null);
      if (j && j.code !== 0) msg2.textContent = j.message || '操作失败';
    } catch (e) {
      msg2.textContent = '控制请求发送失败';
    }
  }

  function act(a) { post(a); }
  function key(c) { post('key', { keyCode: c }); }

  /* ═══════════════════════════════════════════════════
     1. MJPEG 流 — 自动重连（指数退避）
     ═══════════════════════════════════════════════════ */
  let streamBackoff = 1000;          // 初始退避 1s
  const STREAM_BACKOFF_MAX = 30000;  // 最大退避 30s
  let streamRetryTimer = null;
  let pollTimer = null;
  let pingTimer = null;
  let streamWasActive = false;

  let streamSeq = 0;                 // 丢弃过期的异步 ticket 结果
  function reloadStream() {
    if (!TOKEN) return;
    const seq = ++streamSeq;
    fetchStreamTicket().then(function (ticket) {
      if (seq !== streamSeq) return;
      // 默认 900p / 65 JPEG / 20fps; 服务端 ScreenCaptureManager 节流 33ms 兜底上限 30fps
      img.src = '/api/screen/stream?maxWidth=900&fps=20&quality=65&ticket=' + encodeURIComponent(ticket) + '&_=' + Date.now();
    }).catch(function () {
      if (seq !== streamSeq) return;
      img.dispatchEvent(new Event('error'));
    });
  }

  img.addEventListener('load', function () {
    // 成功加载 → 重置退避
    streamBackoff = 1000;
    st.textContent = '\u25CF ' + DW + '\u00D7' + DH;
    msg2.textContent = '';
    if (streamRetryTimer) { clearTimeout(streamRetryTimer); streamRetryTimer = null; }
  });

  img.addEventListener('error', function () {
    st.textContent = '\u25CB 流断 (重连中\u2026)';
    msg2.textContent = '屏幕流断开, ' + Math.round(streamBackoff / 1000) + 's 后重试';
    streamRetryTimer = setTimeout(function () {
      reloadStream();
      streamBackoff = Math.min(streamBackoff * 2, STREAM_BACKOFF_MAX);
    }, streamBackoff);
  });

  // 外部可调用的 reload（工具栏按钮）
  window.reload = function () {
    if (streamRetryTimer) { clearTimeout(streamRetryTimer); streamRetryTimer = null; }
    streamBackoff = 1000;
    reloadStream();
  };

  /* ═══════════════════════════════════════════════════
     2. 连接质量指示器（延迟 ping）
     ═══════════════════════════════════════════════════ */
  function pingLatency() {
    if (!TOKEN) return;
    var t0 = performance.now();
    fetch(api('/api/auth/check'), { method: 'GET', cache: 'no-store', headers: authHeaders() })
      .then(function () {
        var ms = Math.round(performance.now() - t0);
        latencyEl.textContent = ms + ' ms';
        latencyEl.className = ms < 200 ? '' : ms < 500 ? 'warn' : 'bad';
      })
      .catch(function () {
        latencyEl.textContent = '-- ms';
        latencyEl.className = 'bad';
      });
  }

  pingTimer = setInterval(pingLatency, 5000);

  /* ═══════════════════════════════════════════════════
     3. 手机镜像初始化 + 触控处理
     ═══════════════════════════════════════════════════ */
  function initMirror() {
    if (!TOKEN) {
      st.textContent = '\u25CB 无 token';
      msg2.textContent = '用 \u2026/console#token=\u4F60\u7684token \u6253\u5F00';
      sendBtn.disabled = true;
      bubble('err', '缺少访问令牌,无法连接手机或启动任务。');
      return;
    }
    fetch(api('/api/screen/info'), { headers: authHeaders() })
      .then(function (r) { return r.json(); })
      .then(function (j) { if (j && j.data) { DW = j.data.width; DH = j.data.height; } })
      .catch(function () { msg2.textContent = '屏幕信息获取失败'; });
    reloadStream();
  }

  function toDev(cx, cy) {
    var r = img.getBoundingClientRect();
    var nw = img.naturalWidth || r.width;
    var nh = img.naturalHeight || r.height;
    var scale = Math.min(r.width / nw, r.height / nh);
    var cw = nw * scale;
    var ch = nh * scale;
    var offX = (r.width - cw) / 2;
    var offY = (r.height - ch) / 2;
    var sx = (cx - r.left - offX) / cw;
    var sy = (cy - r.top - offY) / ch;
    return {
      x: Math.round(Math.min(1, Math.max(0, sx)) * DW),
      y: Math.round(Math.min(1, Math.max(0, sy)) * DH)
    };
  }

  var down = null, lp = null, moved = false;

  img.addEventListener('mousedown', function (e) {
    down = toDev(e.clientX, e.clientY);
    down.t = Date.now();
    moved = false;
    lp = setTimeout(function () {
      if (down && !moved) {
        post('long_press', { x: down.x, y: down.y, duration: 600 });
        down = null;
      }
    }, 520);
  });

  img.addEventListener('mousemove', function (e) {
    if (down) {
      var p = toDev(e.clientX, e.clientY);
      if (Math.hypot(p.x - down.x, p.y - down.y) > 20) moved = true;
    }
  });

  img.addEventListener('mouseup', function (e) {
    if (lp) { clearTimeout(lp); lp = null; }
    if (!down) return;
    var up = toDev(e.clientX, e.clientY);
    if (Math.hypot(up.x - down.x, up.y - down.y) > 20) {
      post('swipe', {
        x1: down.x, y1: down.y, x2: up.x, y2: up.y,
        duration: Math.min(800, Math.max(120, Date.now() - down.t))
      });
    } else {
      post('tap', { x: up.x, y: up.y });
    }
    down = null;
  });

  img.addEventListener('mouseleave', function () {
    if (lp) { clearTimeout(lp); lp = null; }
    down = null;
  });

  img.addEventListener('wheel', function (e) {
    e.preventDefault();
    var r = img.getBoundingClientRect();
    var c = toDev(r.left + r.width / 2, r.top + r.height / 2);
    var dy = e.deltaY > 0 ? -500 : 500;
    post('swipe', {
      x1: c.x, y1: c.y, x2: c.x, y2: Math.min(DH, Math.max(0, c.y + dy)),
      duration: 200
    });
  }, { passive: false });

  /* ═══════════════════════════════════════════════════
     4. 对话（轮询任务助手事件 + 重连处理）
     ═══════════════════════════════════════════════════ */
  var cursor = 0, curAsst = null;
  var pollBackoff = 800;
  var pollReconnecting = false;
  var reconnectBubble = null;

  function bubble(cls, text) {
    var d = document.createElement('div');
    d.className = 'b ' + cls;
    d.textContent = text;
    chat.appendChild(d);
    chat.scrollTop = chat.scrollHeight;
    return d;
  }

  function showReconnecting() {
    if (pollReconnecting) return;
    pollReconnecting = true;
    reconnectBubble = document.createElement('div');
    reconnectBubble.className = 'reconnecting';
    reconnectBubble.textContent = '\u21BB 重连中\u2026';
    chat.appendChild(reconnectBubble);
    chat.scrollTop = chat.scrollHeight;
  }

  function hideReconnecting() {
    pollReconnecting = false;
    if (reconnectBubble && reconnectBubble.parentNode) {
      reconnectBubble.parentNode.removeChild(reconnectBubble);
    }
    reconnectBubble = null;
  }

  function applyEvent(ev) {
    if (ev.type === 'user') { bubble('user', ev.data); curAsst = null; }
    else if (ev.type === 'text') {
      if (!curAsst) curAsst = bubble('asst', '');
      curAsst.textContent += ev.data;
      chat.scrollTop = chat.scrollHeight;
    }
    else if (ev.type === 'tool') { bubble('tool', '\uD83D\uDD27 ' + ev.data); curAsst = null; }
    else if (ev.type === 'image') {
      var wrap = bubble('tool', '');
      var img = document.createElement('img');
      img.src = 'data:image/jpeg;base64,' + ev.data;
      img.style.cssText = 'max-width:100%;border-radius:8px;margin-top:6px;display:block;cursor:pointer';
      img.title = '\u70B9\u51FB\u5728\u65B0\u6807\u7B7E\u9875\u67E5\u770B\u539F\u56FE';
      img.onclick = function() { window.open(img.src, '_blank'); };
      wrap.appendChild(img);
      curAsst = null;
    }
    else if (ev.type === 'html') {
      // \u7B2C\u4E00\u884C\u662F\u9AD8\u5EA6\u6570\u5B57\uFF0C\u5176\u4F59\u662F HTML \u5185\u5BB9
      var nl = ev.data.indexOf('\n');
      var h = nl > 0 ? parseInt(ev.data.substring(0, nl), 10) : 600;
      var htmlSrc = nl > 0 ? ev.data.substring(nl + 1) : ev.data;
      if (!h || h < 100) h = 600;
      var wrap = bubble('tool', '');
      var frame = document.createElement('iframe');
      frame.setAttribute('sandbox', 'allow-scripts');
      frame.srcdoc = htmlSrc;
      frame.style.cssText = 'width:100%;height:' + h + 'px;border:1px solid var(--border,#e5e7eb);border-radius:8px;margin-top:6px;background:#fff';
      wrap.appendChild(frame);
      curAsst = null;
    }
    else if (ev.type === 'done') {
      if ((!curAsst || !curAsst.textContent) && ev.data) bubble('asst', ev.data);
      curAsst = null;
    }
    else if (ev.type === 'error') { bubble('err', '\u26A0 ' + ev.data); curAsst = null; }
  }

  function poll() {
    if (!TOKEN) return;
    pollTimer = null;
    fetch(api('/api/agent/events?since=' + cursor), { headers: authHeaders() })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        hideReconnecting();
        pollBackoff = 800; // 重置退避
        if (j && j.events) {
          for (var i = 0; i < j.events.length; i++) {
            applyEvent(j.events[i]);
            cursor = j.events[i].i + 1;
          }
        }
        var running = j && j.running;
        sendBtn.disabled = !!running;
        runEl.textContent = running ? '任务执行中\u2026' : '';
        schedulePoll(800);
      })
      .catch(function () {
        showReconnecting();
        runEl.textContent = '连接中断';
        pollBackoff = Math.min(pollBackoff * 2, 15000);
        schedulePoll(pollBackoff);
      });
  }

  function schedulePoll(delay) {
    if (document.visibilityState === 'hidden') return;
    pollTimer = setTimeout(poll, delay);
  }

  /* ═══════════════════════════════════════════════════
     5. 发送消息
     ═══════════════════════════════════════════════════ */
  window.send = function () {
    if (!TOKEN) { bubble('err', '缺少访问令牌,无法发送指令。'); return; }
    var t = ta.value.trim();
    if (!t) return;
    ta.value = '';
    sendBtn.disabled = true;
    fetch(api('/api/agent/run'), {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ prompt: t })
    })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        if (j && j.code !== 0) {
          bubble('err', j.message || '任务启动失败');
          sendBtn.disabled = false;
        }
      })
      .catch(function () {
        bubble('err', '任务请求发送失败');
        sendBtn.disabled = false;
      });
  };

  ta.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      window.send();
    }
  });

  /* ═══════════════════════════════════════════════════
     6. 快捷键帮助面板
     ═══════════════════════════════════════════════════ */
  function toggleHelp() {
    helpPanel.classList.toggle('show');
  }

  document.addEventListener('keydown', function (e) {
    // 如果焦点在输入框中，不拦截
    if (e.target === ta) return;
    if (e.key === '?') {
      e.preventDefault();
      toggleHelp();
    }
  });

  /* ═══════════════════════════════════════════════════
     7. 工具栏事件 + 页面生命周期
     ═══════════════════════════════════════════════════ */
  document.querySelectorAll('.ltbar [data-action]').forEach(function (btn) {
    btn.addEventListener('click', function () { act(btn.dataset.action); });
  });
  document.querySelectorAll('.ltbar [data-key]').forEach(function (btn) {
    btn.addEventListener('click', function () { key(Number(btn.dataset.key)); });
  });
  document.getElementById('reloadBtn').addEventListener('click', reload);
  sendBtn.addEventListener('click', send);

  // 帮助面板：顶栏按钮打开、面板内关闭按钮关闭
  var helpBtn = document.getElementById('helpBtn');
  if (helpBtn) helpBtn.addEventListener('click', toggleHelp);
  var helpClose = document.querySelector('.help-close');
  if (helpClose) helpClose.addEventListener('click', function () { helpPanel.classList.remove('show'); });

  img.addEventListener('load', function () { streamWasActive = true; });

  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') {
      if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
      streamWasActive = streamWasActive || !!img.src;
      streamSeq++;
      img.src = '';
      if (streamRetryTimer) { clearTimeout(streamRetryTimer); streamRetryTimer = null; }
    } else {
      if (!pollTimer) poll();
      if (streamWasActive) reloadStream();
    }
  });

  window.addEventListener('beforeunload', function () {
    if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
    if (pingTimer) { clearInterval(pingTimer); pingTimer = null; }
    if (streamRetryTimer) { clearTimeout(streamRetryTimer); streamRetryTimer = null; }
    streamSeq++;
    img.src = '';
  });

  /* ═══════════════════════════════════════════════════
     启动
     ═══════════════════════════════════════════════════ */
  initMirror();
  poll();
  // 首次延迟 ping（等页面稳定）
  setTimeout(pingLatency, 1500);

})();
