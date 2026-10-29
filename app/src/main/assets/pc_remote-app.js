/* Remote presentation; no typed content is persisted or automatically replayed. */
;(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  const video = $('v'), image = $('relay-frame'), surface = $('wrap'), input = $('kbi');
  const pendingText = new Map(), pointers = new Map();
  let connected = false, canControl = false, mini = false, mouseMode = false, full = false;
  let platform = '', stream = null, scale = 1, down = null, last = null, dragging = false, multi = null, longTimer = null, longPressed = false;
  let tap = null, focusEpoch = 0, autoOpened = false, secure = false, frames = 0, previousFrames = 0;
  let metrics = {}, noticeTimer = null;
  const audio = new RemoteAudioPlayer({
    media: $('system-audio'),
    createContext: () => new (window.AudioContext || window.webkitAudioContext)(),
    createStream: track => new MediaStream([track]),
    onState: audioState,
  });
  function audioState(state) {
    const descriptions = {off:'系统声音已关闭', enabled:'系统声音已接入', playing:'正在播放电脑系统声音',
      unavailable:'系统声音不可用，画面和操作不受影响；检查电脑输出设备后可关闭再开启',
      unsupported:'请更新电脑端以使用系统声音', needs_gesture:'浏览器暂停了声音，请点击播放'};
    $('audio-note').textContent = descriptions[state] || '系统声音等待连接';
    $('btn-audio-play').hidden = state !== 'needs_gesture';
  }
  const labels = {
    offline: '电脑连接已断开，正在等待重新连接', checking: '正在检查电脑状态…',
    authorization_required: '请在电脑的设备互联中，允许此手机观看或控制电脑。',
    component_missing: '电脑缺少远控组件，请更新桌面端。', display_unavailable: '当前主机没有可用桌面。',
    network_config_invalid: '电脑的远程连接配置需要修复。', invalid_settings: '画面设置不受支持，请更新电脑端。',
    connecting: '正在连接电脑…', waiting_frame: '已连接，正在等待电脑画面…',
    capture_or_permission_failed: '无法取得画面或输入权限，请在电脑检查授权。', busy: '另一台手机正在使用电脑。',
    host_update_required: '请更新电脑端后重试。', client_update_required: '请更新手机端后重试。',
    failed: '暂时无法连接，请检查电脑是否在线及跨网通道。', operator_stopped: '电脑已结束远控，可点击重新连接。', paused: '远控已暂停。',
  };
  function send(event) {
    const seq = session.sendInput(event);
    if (seq && event.action === 'tap') tap = { seq, epoch: focusEpoch };
    return seq;
  }
  function updateButtons() {
    document.querySelectorAll('[data-input]').forEach(el => { el.disabled = !canControl || mini || !$('stop-confirm').hidden; });
    $('btn-send-type').disabled = !canControl || mini || !$('stop-confirm').hidden || pendingText.size > 0;
  }
  function panel(id, open) {
    if (open && id !== 'mouse-panel') { controls.release(); releasePointer(); }
    if (open || !id) document.querySelectorAll('.panel').forEach(el => { el.hidden = el.id !== id || !open; });
    else $(id).hidden = true;
    document.body.classList.toggle('confirming-stop', !$('stop-confirm').hidden);
    $('btn-kb').setAttribute('aria-expanded', String(!$('kb').hidden));
    $('btn-actions').setAttribute('aria-expanded', String(!$('actions').hidden));
    updateButtons();
    layoutPanel();
  }
  function layoutPanel() {
    const available = Math.min(innerHeight * .75, Math.max(160, innerHeight - 220));
    document.querySelectorAll('.panel').forEach(el => { el.style.maxHeight = available + 'px'; });
    const active = mini ? null : document.querySelector('.panel:not([hidden])');
    surface.style.bottom = active ? Math.ceil(active.getBoundingClientRect().height + 24) + 'px' : '0px';
    cursor();
  }
  function textTab(active) {
    $('text-panel').hidden = !active; $('keys-panel').hidden = active;
    $('tab-text').setAttribute('aria-pressed', String(active)); $('tab-keys').setAttribute('aria-pressed', String(!active));
    layoutPanel();
  }
  function keyboard(open, automatic) {
    if (!automatic) { focusEpoch++; autoOpened = false; }
    if (open && canControl && !mini) {
      autoOpened = !!automatic; textTab(true); panel('kb', true); input.focus();
      if (Android.showKeyboard) Android.showKeyboard();
    } else { autoOpened = false; panel('kb', false); input.blur(); if (Android.hideKeyboard) Android.hideKeyboard(); }
  }
  function rectangle() {
    const r = surface.getBoundingClientRect();
    const ratio = !image.hidden && image.naturalWidth ? image.naturalWidth / image.naturalHeight : video.videoWidth ? video.videoWidth / video.videoHeight : 16 / 9;
    const width = Math.min(r.width, r.height * ratio) * scale, height = width / ratio;
    return { left: r.left + (r.width - width) / 2, top: r.top + (r.height - height) / 2, width, height };
  }
  function norm(point) {
    const r = rectangle();
    return { x: Math.max(0, Math.min(1, (point.x - r.left) / r.width)), y: Math.max(0, Math.min(1, (point.y - r.top) / r.height)) };
  }
  function cursor() {
    const r = rectangle(); $('remote-cursor').hidden = !mouseMode || !connected || mini;
    $('remote-cursor').style.left = (r.left + controls.cursor.x * r.width) + 'px';
    $('remote-cursor').style.top = (r.top + controls.cursor.y * r.height) + 'px';
  }
  const session = new RemoteDesktopSession({
    sendSignal(method, params) { return Android.signal(JSON.stringify({ method, params })); },
    createPeer(config) { return new RTCPeerConnection(config); },
    onHostStatus(host) {
      platform = host.platform || '';
      $('quality').disabled = !Array.isArray(host.qualityProfiles);
      $('btn-audio').disabled = host.systemAudio !== true;
      $('btn-audio').title = host.systemAudio === true ? '播放电脑的系统声音' : '此主机缺少系统声音组件或尚未更新';
      $('network-note').textContent = host.relayConfigured ? '电脑已配置视频中继；异地仍需手机能连接电脑的安全入口。' : '电脑尚未配置视频中继，连接受限时会尝试兼容画面。异地需要安全入口。';
      $('auto-keyboard').title = host.inputFocusHints ? '' : '此主机可能需要手动打开键盘';
      document.querySelectorAll('[data-modifier=win]').forEach(el => { el.textContent = platform === 'darwin' ? '⌘' : 'Win'; });
    },
    onStream(value) {
      stream = value; video.srcObject = value; image.hidden = true; video.style.visibility = 'visible';
      if (!value) pendingText.clear(); else video.play().catch(() => {});
    },
    onImage(frame) {
      if (typeof frame.jpeg !== 'string' || frame.jpeg.length > 320000) return;
      image.hidden = false; video.style.visibility = 'hidden'; image.src = 'data:image/jpeg;base64,' + frame.jpeg; frames++;
    },
    onAudioTrack(track) { audio.track(track); },
    onAudio(packet) { audio.pcm(packet); },
    onAudioStatus(state) { audioState(session.preferences.audio ? state : 'off'); },
    onAudioReset() { audio.reset(); audioState(session.preferences.audio ? 'waiting' : 'off'); },
    onState(state, detail) {
      connected = state === 'connected'; canControl = connected && detail.permission === 'control';
      $('conn-badge').className = connected ? 'connected' : ['checking', 'connecting', 'recovering', 'waiting_frame'].includes(state) ? 'checking' : 'disconnected';
      $('status').hidden = connected;
      $('status').textContent = state === 'recovering' ? '连接中断，正在恢复…' : labels[state] || '连接暂不可用，请重试。';
      $('st').textContent = connected ? ('已连接 · ' + (canControl ? '可控制' : '仅观看') + (detail.transport === 'relay' ? ' · 兼容连接' : '')) : '未连接';
      if (!canControl) { controls.release(); releasePointer(); tap = null; }
      updateButtons(); cursor();
    },
    onInputAck(ack) {
      if (!ack.ok) {
        $('operation-note').textContent = '操作未完成，请检查电脑桌面或输入权限'; $('operation-note').hidden = false;
        clearTimeout(noticeTimer); noticeTimer = setTimeout(() => { $('operation-note').hidden = true; }, 5000);
      }
      if (tap && tap.seq === ack.seq && tap.epoch === focusEpoch && ack.ok && ack.inputFocus && ack.inputFocus.available && canControl && !mini) {
        const hint = ack.inputFocus; secure = hint.secure === true;
        if (secure || !input.value) input.type = secure ? 'password' : 'text';
        $('input-note').textContent = secure ? '密码隐藏显示；确认发送到当前电脑输入框' : '文字会发送到当前电脑输入框';
        if ($('auto-keyboard').checked) {
          if (hint.editable) keyboard(true, true); else if (autoOpened && !input.value) keyboard(false, true);
        }
        tap = null;
      }
      const text = pendingText.get(ack.seq);
      if (text !== undefined) {
        pendingText.delete(ack.seq);
        if (ack.ok && input.value === text) input.value = '';
        else if (!ack.ok) $('st').textContent = '文字未能完整输入，请检查电脑后再发送';
        updateButtons();
      }
    },
    onStats(value) { metrics = { ...metrics, ...value }; renderMetrics(); },
  });
  const controls = new RemoteControls(send, state => {
    document.querySelectorAll('[data-modifier]').forEach(el => el.setAttribute('aria-pressed', String(state.modifiers.has(el.dataset.modifier === 'win' && platform === 'darwin' ? 'command' : el.dataset.modifier))));
    $('mouse-drag').setAttribute('aria-pressed', String(state.dragging)); $('mouse-drag').textContent = state.dragging ? '松开拖拽' : '拖拽'; cursor();
  });
  function renderMetrics() {
    const width = image.hidden ? video.videoWidth : image.naturalWidth, height = image.hidden ? video.videoHeight : image.naturalHeight;
    const parts = [width && height ? width + '×' + height : null, metrics.fps != null ? metrics.fps + ' 帧/秒' : null, metrics.rtt != null ? '往返 ' + metrics.rtt + 'ms' : null].filter(Boolean);
    $('metrics').textContent = connected ? parts.join(' · ') || '正在测量连接' : '连接指标等待画面';
  }
  image.onload = () => { if (!image.hidden) { session.frameReady(); cursor(); } };
  video.addEventListener('loadeddata', () => { if (stream && video.srcObject === stream) { session.frameReady(); cursor(); } });
  window.remoteSignal = message => { void session.receive(message); };
  window.remoteConnection = online => session.setOnline(online);
  window.remoteResume = () => session.resume(); window.remotePause = () => session.pause();
  window.remoteMini = value => {
    controls.release(); releasePointer(); mini = !!value; session.viewOnly = mini;
    document.body.classList.toggle('mini-mode', mini); $('mini-caption').hidden = !mini;
    if (mini) { panel('', false); input.blur(); }
    updateButtons(); cursor();
  };
  function keyButton(parent, label, key, modifier) {
    const button = document.createElement('button'); button.type = 'button'; button.textContent = label; button.setAttribute('data-input', ''); button.disabled = true;
    if (modifier) { button.dataset.modifier = key; button.className = 'modifier'; button.setAttribute('aria-pressed', 'false'); }
    else button.dataset.key = key;
    button.onclick = () => modifier ? controls.toggleModifier(key === 'win' && platform === 'darwin' ? 'command' : key) : controls.key(key);
    parent.appendChild(button);
  }
  const rows = [
    Array.from({length:12}, (_, i) => ['F' + (i + 1), 'f' + (i + 1)]),
    '1234567890-='.split('').map(k => [k, k]),
    'QWERTYUIOP'.split('').map(k => [k, k.toLowerCase()]),
    'ASDFGHJKL'.split('').map(k => [k, k.toLowerCase()]),
    [...'ZXCVBNM'.split('').map(k => [k, k.toLowerCase()]), [',', ','], ['.', '.'], ['/', '/']],
    [['[','['],[']',']'],['\\','\\'],[';',';'],["'","'"],['`','`']],
    [['Home','home'],['End','end'],['PgUp','pageup'],['PgDn','pagedown'],['Ins','insert'],['Del','delete']],
    [['Ctrl','ctrl',true],['Alt','alt',true],['Shift','shift',true],['Win','win',true],['空格','space'],['Enter','enter'],['↑','up'],['↓','down']],
  ];
  rows.forEach(row => { const el = document.createElement('div'); el.className = 'key-row'; row.forEach(key => keyButton(el, ...key)); $('key-grid').appendChild(el); });
  [['复制','c'],['粘贴','v'],['剪切','x'],['全选','a'],['撤销','z'],['保存','s']].forEach(([label, key]) => {
    const button = document.createElement('button'); button.type = 'button'; button.textContent = label; button.setAttribute('data-input', ''); button.disabled = true;
    button.onclick = () => controls.shortcut((platform === 'darwin' ? 'command' : 'ctrl') + '+' + key); $('shortcut-grid').appendChild(button);
  });
  $('btn-back').onclick = () => { session.pause(); Android.back(); };
  $('btn-audio').onclick = () => {
    const enabled = !session.preferences.audio;
    void audio.setEnabled(enabled);
    $('btn-audio').setAttribute('aria-pressed', String(enabled));
    $('btn-audio').textContent = enabled ? '声音开' : '声音关';
    controls.release(); session.reconfigure({audio:enabled});
  };
  $('btn-audio-play').onclick = () => { void audio.setEnabled(true); };
  $('btn-retry').onclick = () => { session.pause(); Android.reconnect(); session.resume(); };
  $('btn-kb').onclick = () => keyboard($('kb').hidden, false);
  $('btn-kb-close').onclick = () => keyboard(false, false);
  $('tab-text').onclick = () => { textTab(true); input.focus(); if (Android.showKeyboard) Android.showKeyboard(); };
  $('tab-keys').onclick = () => { focusEpoch++; autoOpened = false; input.blur(); if (Android.hideKeyboard) Android.hideKeyboard(); textTab(false); };
  $('btn-actions').onclick = () => panel('actions', $('actions').hidden);
  document.querySelectorAll('[data-close]').forEach(el => { el.onclick = () => panel(el.dataset.close, false); });
  $('btn-help').onclick = () => panel('guide', true);
  [['btn-backspace','backspace'],['btn-esc','esc'],['btn-enter','enter'],['btn-tab','tab'],['btn-space','space'],['btn-arrow-left','left'],['btn-arrow-right','right']].forEach(([id,key]) => { $(id).onclick = () => controls.key(key); });
  $('btn-send-type').onclick = () => {
    if (!input.value || input.value.length > 4096 || pendingText.size) return;
    const seq = send({ action: 'type', text: input.value });
    if (seq) pendingText.set(seq, input.value); else $('st').textContent = '输入通道未就绪，文字已保留'; updateButtons();
  };
  input.addEventListener('input', () => { if (!input.value && !secure) input.type = 'text'; });
  function setMouse(value) {
    controls.release(); releasePointer(); mouseMode = value; $('btn-mouse').setAttribute('aria-pressed', String(value)); panel('mouse-panel', value); cursor();
  }
  $('btn-mouse').onclick = () => setMouse(!mouseMode); $('btn-mouse-close').onclick = () => setMouse(false);
  $('mouse-left').onclick = () => controls.click(false); $('mouse-right').onclick = () => controls.click(true); $('mouse-drag').onclick = () => controls.drag();
  $('mouse-up').onclick = () => send({action:'scroll',delta:3}); $('mouse-down').onclick = () => send({action:'scroll',delta:-3});
  $('btn-desktop').onclick = () => { controls.release(); send({action:'system',command:'show_desktop'}); panel('actions',false); };
  $('btn-windows').onclick = () => { controls.release(); send({action:'system',command:'all_windows'}); panel('actions',false); };
  $('btn-full').onclick = () => { full = !full; if (Android.setFullscreen) Android.setFullscreen(full); else if (full && surface.requestFullscreen) surface.requestFullscreen().catch(() => {}); else if (document.fullscreenElement) document.exitFullscreen().catch(() => {}); $('btn-full').textContent = full ? '退出横屏' : '横屏全屏'; panel('actions',false); };
  $('btn-mini').hidden = !Android.supportsMiniPlayer || !Android.supportsMiniPlayer();
  $('btn-mini').onclick = () => { if (Android.miniPlayer) Android.miniPlayer(); };
  $('btn-stop').onclick = () => { controls.release(); releasePointer(); panel('stop-confirm',true); }; $('stop-cancel').onclick = () => panel('stop-confirm',false);
  $('stop-confirm-button').onclick = () => { controls.release(); session.pause(); panel('',false); };
  function savePreferences() { try { localStorage.setItem('echo-remote-preferences',JSON.stringify({quality:$('quality').value,autoKeyboard:$('auto-keyboard').checked})); } catch (_) { /* Optional preferences only. */ } }
  try { const saved = JSON.parse(localStorage.getItem('echo-remote-preferences') || '{}'); if (['auto','smooth','sharp','saver'].includes(saved.quality)) { $('quality').value = saved.quality; session.preferences.quality = saved.quality; } if (typeof saved.autoKeyboard === 'boolean') $('auto-keyboard').checked = saved.autoKeyboard; } catch (_) { /* Ignore damaged local preferences. */ }
  $('quality').onchange = () => { controls.release(); savePreferences(); session.reconfigure({quality:$('quality').value}); };
  $('auto-keyboard').onchange = savePreferences;
  function releasePointer() {
    clearTimeout(longTimer); longTimer = null;
    if (dragging && last) send({action:'up',...last});
    down = null; last = null; dragging = false; multi = null; pointers.clear();
  }
  function point(event) { return { x:event.clientX, y:event.clientY }; }
  function distance(values) { return Math.hypot(values[0].x-values[1].x,values[0].y-values[1].y); }
  surface.addEventListener('contextmenu', e => e.preventDefault());
  surface.addEventListener('pointerdown', e => {
    if (!canControl || mini) return; e.preventDefault();
    if (e.button === 2) { mouseMode ? controls.click(true) : send({action:'rightclick',...norm(point(e))}); return; }
    surface.setPointerCapture(e.pointerId); pointers.set(e.pointerId,point(e));
    if (pointers.size >= 2) {
      clearTimeout(longTimer); if (dragging && last) send({action:'up',...last}); dragging = false;
      const values = [...pointers.values()]; multi = {distance:distance(values),y:(values[0].y+values[1].y)/2,scale,mode:null}; down = null; return;
    }
    down = {point:point(e),normal:norm(point(e)),moved:false}; last = down.normal; longPressed = false;
    longTimer = setTimeout(() => { if (down && !down.moved && !dragging && !mini) { mouseMode ? controls.click(true) : send({action:'rightclick',...down.normal}); longPressed = true; } },550);
  });
  surface.addEventListener('pointermove', e => {
    if (!pointers.has(e.pointerId) || !canControl || mini) return; e.preventDefault();
    const previous = pointers.get(e.pointerId); pointers.set(e.pointerId,point(e));
    if (multi) {
      if (pointers.size < 2) return;
      const values = [...pointers.values()], d = distance(values), y = (values[0].y+values[1].y)/2;
      if (!multi.mode) { if (Math.abs(d-multi.distance)>14) multi.mode='zoom'; else if (Math.abs(y-multi.y)>10) multi.mode='scroll'; }
      if (multi.mode==='zoom') { scale=Math.max(1,Math.min(3,multi.scale*d/Math.max(1,multi.distance))); video.style.transform=image.style.transform='scale('+scale+')'; cursor(); }
      else if (multi.mode==='scroll') { const delta=Math.max(-20,Math.min(20,Math.round((multi.y-y)/12))); if (delta) { send({action:'scroll',delta}); multi.y=y; } }
      return;
    }
    if (!down || longPressed) return;
    if (Math.hypot(e.clientX-down.point.x,e.clientY-down.point.y)>6) { down.moved=true; clearTimeout(longTimer); }
    if (mouseMode) { const r=rectangle(); controls.move((e.clientX-previous.x)/r.width,(e.clientY-previous.y)/r.height); }
    else if (down.moved) { last=norm(point(e)); if (!dragging) dragging=!!send({action:'down',...down.normal}); if (dragging) send({action:'move',...last}); }
  });
  surface.addEventListener('pointerup', e => {
    if (!pointers.has(e.pointerId)) return; e.preventDefault(); clearTimeout(longTimer); pointers.delete(e.pointerId);
    if (multi) { if (!pointers.size) releasePointer(); return; }
    if (down && !down.moved && !longPressed) mouseMode ? controls.click(false) : send({action:'tap',...down.normal});
    releasePointer();
  });
  surface.addEventListener('pointercancel', () => { releasePointer(); controls.release(); send({action:'release_all'}); });
  window.addEventListener('keydown', e => {
    if (!canControl || mini || !$('stop-confirm').hidden || !(e.target instanceof Element) || e.target.closest('button,input,select,textarea,[contenteditable]') || e.isComposing) return;
    const aliases={ArrowUp:'up',ArrowDown:'down',ArrowLeft:'left',ArrowRight:'right',Escape:'esc',Backspace:'backspace',Delete:'delete',Enter:'enter',Tab:'tab',' ':'space',PageUp:'pageup',PageDown:'pagedown',Home:'home',End:'end',Insert:'insert'};
    const key=aliases[e.key] || (/^F\d{1,2}$/.test(e.key) ? e.key.toLowerCase() : /^[a-z0-9\-=\[\];',./]$/i.test(e.key) ? e.key.toLowerCase() : null);
    if (!key) return; e.preventDefault();
    const modifiers=[e.ctrlKey?'ctrl':null,e.altKey?'alt':null,e.shiftKey?'shift':null,e.metaKey?(platform==='darwin'?'command':'win'):null].filter(Boolean);
    const combo=[...new Set([...controls.modifiers,...modifiers]),key]; if (combo.length<=4) controls.shortcut(combo.join('+'));
  });
  window.addEventListener('resize',layoutPanel);
  if (typeof ResizeObserver !== 'undefined') {
    const observer = new ResizeObserver(layoutPanel);
    document.querySelectorAll('.panel').forEach(el => observer.observe(el));
  }
  window.addEventListener('blur', () => { releasePointer(); controls.release(); send({action:'release_all'}); });
  window.addEventListener('pagehide', () => { controls.release(); releasePointer(); session.pause(); audio.dispose(); clearInterval(measurements); });
  document.addEventListener('visibilitychange', () => { if (document.hidden && !mini) { controls.release(); releasePointer(); session.pause(); } });
  const measurements=setInterval(() => { if (!image.hidden && connected) { metrics={...metrics,fps:Math.round((frames-previousFrames)/2)}; previousFrames=frames; renderMetrics(); } },2000);
  session.resume(); Android.ready();
})();
