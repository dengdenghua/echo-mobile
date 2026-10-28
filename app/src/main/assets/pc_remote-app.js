/* Remote desktop presentation. The session state machine owns all reconnects. */
;(function () {
  'use strict';
  const video = document.getElementById('v');
  const relayImage = document.getElementById('relay-frame');
  const surface = document.getElementById('wrap');
  const status = document.getElementById('status');
  const badge = document.getElementById('st');
  const input = document.getElementById('kbi');
  const pendingText = new Map();
  let stream = null;
  const labels = {
    offline: '电脑连接已断开，正在等待重新连接', checking: '正在检查电脑状态…',
    authorization_required: '请在电脑的设备互联中，允许此手机观看或控制电脑。',
    component_missing: '电脑端缺少远控组件，请更新 Echo 桌面端。',
    display_unavailable: '当前主机没有可用桌面，请连接正在登录使用的电脑。',
    network_config_invalid: '电脑端的远程连接配置需要修复。',
    connecting: '正在连接电脑…', waiting_frame: '已连接，正在等待电脑画面…',
    capture_or_permission_failed: '无法取得电脑画面或输入权限，请在电脑上检查录屏与辅助功能权限。',
    busy: '另一台手机正在使用电脑，请先结束该会话。',
    host_update_required: '请更新电脑端后重试。', client_update_required: '请更新手机端后重试。',
    failed: '暂时无法连接。请检查电脑是否在线，或在电脑端配置跨网连接。',
    operator_stopped: '电脑已结束本次远控，可点击重新连接。',
    paused: '远控已暂停，返回页面后重新连接。',
  };
  const session = new RemoteDesktopSession({
    sendSignal(method, params) { return Android.signal(JSON.stringify({ method, params })); },
    createPeer(config) { return new RTCPeerConnection(config); },
    onStream(value) { stream = value; video.srcObject = value; relayImage.hidden = true; video.style.visibility = 'visible'; if (!value) pendingText.clear(); if (value) video.play().catch(() => {}); },
    onImage(frame) { if (typeof frame.jpeg !== 'string' || frame.jpeg.length > 320000) return; relayImage.hidden = false; video.style.visibility = 'hidden'; relayImage.src = 'data:image/jpeg;base64,' + frame.jpeg; },
    onState(state, detail) {
      const connected = state === 'connected';
      status.style.display = connected ? 'none' : 'block';
      status.textContent = state === 'recovering' ? '连接中断，正在恢复…' : (labels[state] || '连接暂不可用，请重试。');
      badge.textContent = connected ? ((detail.permission === 'control' ? '已连接 · 可控制' : '已连接 · 仅观看') + (detail.transport === 'relay' ? ' · 兼容连接' : '')) : '未连接';
      document.querySelectorAll('[data-input]').forEach(el => { el.disabled = !connected || detail.permission !== 'control'; });
    },
    onInputAck(ack) {
      const text = pendingText.get(ack.seq);
      if (text === undefined) return;
      pendingText.delete(ack.seq);
      if (ack.ok && input.value === text) input.value = '';
      else if (!ack.ok) badge.textContent = '文字未能输入，请检查电脑权限后重试';
    },
  });
  relayImage.onload = () => { if (!relayImage.hidden) session.frameReady(); };
  video.addEventListener('loadeddata', () => { if (stream && video.srcObject === stream) session.frameReady(); });
  window.remoteSignal = message => { void session.receive(message); };
  window.remoteConnection = online => session.setOnline(online);
  window.remoteResume = () => session.resume();
  window.remotePause = () => session.pause();
  function send(event) { return session.sendInput(event); }
  const key = text => send({ action: 'key', text });
  document.getElementById('btn-back').onclick = () => { session.pause(); Android.back(); };
  document.getElementById('btn-retry').onclick = () => { session.pause(); Android.reconnect(); session.resume(); };
  document.getElementById('btn-kb').onclick = () => document.getElementById('kb').classList.toggle('visible');
  document.getElementById('btn-backspace').onclick = () => key('backspace');
  document.getElementById('btn-esc').onclick = () => key('esc');
  document.getElementById('btn-enter').onclick = () => key('enter');
  document.getElementById('btn-send-type').onclick = () => {
    if (!input.value || input.value.length > 4096 || pendingText.size) return;
    const seq = send({ action: 'type', text: input.value });
    if (seq) pendingText.set(seq, input.value);
    else badge.textContent = '输入通道未就绪，文字已保留';
  };
  document.getElementById('btn-help').onclick = () => {
    const help = document.getElementById('guide');
    help.hidden = !help.hidden;
  };
  const norm = touch => {
    const target = relayImage.hidden ? video : relayImage;
    const r = target.getBoundingClientRect();
    const ratio = !relayImage.hidden && relayImage.naturalWidth ? relayImage.naturalWidth / relayImage.naturalHeight : video.videoWidth && video.videoHeight ? video.videoWidth / video.videoHeight : r.width / r.height;
    const width = Math.min(r.width, r.height * ratio), height = width / ratio;
    return { x: Math.max(0, Math.min(1, (touch.clientX - r.left - (r.width - width) / 2) / width)),
             y: Math.max(0, Math.min(1, (touch.clientY - r.top - (r.height - height) / 2) / height)) };
  };
  let down = null, last = null, dragging = false, longPressed = false, longTimer = null;
  let multi = null, scale = 1;
  const distance = touches => Math.hypot(touches[0].clientX - touches[1].clientX, touches[0].clientY - touches[1].clientY);
  function release() {
    clearTimeout(longTimer);
    if (dragging && last) send(Object.assign({ action: 'up' }, last));
    down = null; dragging = false;
  }
  surface.addEventListener('touchstart', e => {
    e.preventDefault();
    if (e.touches.length >= 2) {
      release();
      multi = { distance: distance(e.touches), y: (e.touches[0].clientY + e.touches[1].clientY) / 2, scale, mode: null };
      return;
    }
    if (multi) return;
    down = last = norm(e.touches[0]); longPressed = false;
    longTimer = setTimeout(() => { if (down && !dragging) { send(Object.assign({action: 'rightclick'}, down)); longPressed = true; } }, 550);
  }, { passive: false });
  surface.addEventListener('touchmove', e => {
    e.preventDefault();
    if (multi && e.touches.length >= 2) {
      const d = distance(e.touches), y = (e.touches[0].clientY + e.touches[1].clientY) / 2;
      if (!multi.mode) {
        if (Math.abs(d - multi.distance) > 14) multi.mode = 'zoom';
        else if (Math.abs(y - multi.y) > 10) multi.mode = 'scroll';
      }
      if (multi.mode === 'zoom') {
        scale = Math.max(1, Math.min(3, multi.scale * d / Math.max(1, multi.distance)));
        video.style.transform = relayImage.style.transform = 'scale(' + scale + ')';
      } else if (multi.mode === 'scroll') {
        const delta = Math.max(-20, Math.min(20, Math.round((multi.y - y) / 12)));
        if (delta) { send({action: 'scroll', delta}); multi.y = y; }
      }
      return;
    }
    if (!down || multi || longPressed || !e.touches.length) return;
    last = norm(e.touches[0]);
    if (!dragging && Math.hypot(last.x - down.x, last.y - down.y) > 0.015) {
      clearTimeout(longTimer); dragging = !!send(Object.assign({action: 'down'}, down));
    }
    if (dragging) send(Object.assign({action: 'move'}, last));
  }, { passive: false });
  surface.addEventListener('touchend', e => {
    e.preventDefault(); clearTimeout(longTimer);
    if (multi) { if (!e.touches.length) multi = null; release(); return; }
    if (down && !dragging && !longPressed) send(Object.assign({action: 'tap'}, down));
    release();
  }, { passive: false });
  surface.addEventListener('touchcancel', () => { release(); multi = null; send({action: 'release_all'}); });
  window.addEventListener('pagehide', () => session.pause());
  document.addEventListener('visibilitychange', () => { if (document.hidden) session.pause(); });
  session.resume();
  Android.ready();
})();
