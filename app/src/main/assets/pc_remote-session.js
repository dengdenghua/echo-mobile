/* Shared state machine for the Android WebView and its wire-protocol tests. */
(function (root) {
  'use strict';
  class RemoteDesktopSession {
    constructor(options) {
      this.options = options;
      this.online = false;
      this.active = false;
      this.attempt = 0;
      this.seq = 0;
      this.requestId = null;
      this.sessionId = null;
      this.pc = null;
      this.dc = null;
      this.permission = 'denied';
      this.timer = null;
      this.pulse = null;
      this.firstFrame = false;
      this.counter = 0;
    }
    show(state, detail) { this.options.onState(state, detail || {}); }
    signal(method, params) { return this.options.sendSignal(method, params || {}); }
    resume() {
      this.active = true;
      this.attempt = 0;
      this.refresh();
    }
    refresh() {
      if (!this.active) return;
      if (!this.online) { this.show('offline'); return; }
      if (!this.requestId) {
        this.show('checking');
        this.signal('remote_desktop/status');
      }
    }
    setOnline(value) {
      if (value === this.online) return;
      this.online = value;
      if (!value) { this.cleanup(false); this.show('offline'); }
      else if (this.active) { this.attempt = 0; this.refresh(); }
    }
    async receive(message) {
      if (!this.active || !this.online) return;
      const p = message.params || {};
      if (message.error) { this.cleanup(false); this.show('host_update_required'); return; }
      if (p.requestId && p.requestId !== this.requestId &&
          !(message.method === 'remote_desktop/stopped' && p.reason === 'operator_stopped')) return;
      if (message.method === 'remote_desktop/status') {
        this.permission = p.permission || 'denied';
        if (p.state !== 'ready') { this.cleanup(true); this.show(p.state); }
        else if (!this.requestId && !this.timer) this.request();
      } else if (message.method === 'webrtc/offer') {
        if (!this.requestId || this.pc) return;
        await this.accept(p);
      } else if (message.method === 'remote_desktop/relay') {
        if (!this.requestId) return;
        this.sessionId = p.sessionId;
        this.permission = p.permission;
        this.dc = { readyState: 'open', bufferedAmount: 0,
          send: data => this.signal('remote_desktop/input', {
            requestId: this.requestId, sessionId: this.sessionId, data,
          }) };
        clearInterval(this.pulse);
        this.lastPong = Date.now();
        this.heartbeat(); this.pulse = setInterval(() => this.heartbeat(), 5000);
        this.show('waiting_frame');
      } else if (message.method === 'remote_desktop/frame') {
        if (this.sessionId === p.sessionId && this.options.onImage) this.options.onImage(p);
      } else if (message.method === 'remote_desktop/inputAck') {
        if (this.sessionId === p.sessionId && p.type === 'pong') this.lastPong = Date.now();
        if (this.sessionId === p.sessionId && p.type === 'inputAck' && this.options.onInputAck) this.options.onInputAck(p);
      } else if (message.method === 'remote_desktop/error') {
        this.cleanup(true);
        this.show(p.code || 'failed');
      } else if (message.method === 'remote_desktop/stopped') {
        if (p.reason === 'operator_stopped') {
          this.cleanup(true); this.active = false; this.show('operator_stopped');
        } else if (p.reason === 'permission_changed') {
          this.cleanup(false); this.refresh();
        } else if (p.reason !== 'replaced') this.retry(p.reason);
      }
    }
    request() {
      if (!this.active || !this.online || this.requestId) return;
      this.attempt++;
      this.requestId = Date.now().toString(36) + '-' + (++this.counter) + '-' + Math.random().toString(36).slice(2);
      this.show('connecting');
      this.signal('webrtc/request', { requestId: this.requestId, transport: this.attempt > 1 ? 'relay' : 'webrtc' });
      this.timer = setTimeout(() => this.retry('first_frame_timeout'), 20000);
    }
    async accept(offer) {
      const request = this.requestId;
      this.sessionId = offer.sessionId;
      this.permission = offer.permission;
      this.firstFrame = false;
      const servers = offer.iceServers || [];
      const relay = servers.some(s => [].concat(s.urls).some(u => /^turns?:/.test(u)));
      let pc;
      try {
        pc = this.pc = this.options.createPeer({
          iceServers: servers,
          iceTransportPolicy: relay && this.attempt > 1 ? 'relay' : 'all',
        });
      } catch (_) { this.retry('negotiation_failed'); return; }
      const current = () => this.active && this.online && this.pc === pc && this.requestId === request;
      const pendingCandidates = [];
      let answerSent = false;
      const candidate = value => this.signal('webrtc/candidate', {
        requestId: request, sessionId: offer.sessionId, candidate: value,
      });
      pc.onicecandidate = e => {
        if (!current() || !e.candidate) return;
        const value = e.candidate.toJSON();
        if (answerSent) candidate(value); else pendingCandidates.push(value);
      };
      pc.ontrack = e => { if (current()) this.options.onStream(e.streams[0]); };
      pc.ondatachannel = e => {
        if (!current()) { e.channel.close(); return; }
        this.dc = e.channel;
        e.channel.onopen = () => {
          if (!current()) return;
          clearInterval(this.pulse);
          this.lastPong = Date.now();
          this.heartbeat(); this.pulse = setInterval(() => { if (current()) this.heartbeat(); }, 5000); this.connected();
        };
        e.channel.onclose = () => { if (current()) this.retry('input_disconnected'); };
        e.channel.onmessage = event => {
          if (!current()) return;
          try {
            const ack = JSON.parse(event.data);
            if (ack.type === 'pong') this.lastPong = Date.now();
            if (ack.type === 'inputAck' && this.options.onInputAck) this.options.onInputAck(ack);
          } catch (_) { /* Ignore malformed packets; never evaluate peer content. */ }
        };
      };
      pc.onconnectionstatechange = () => {
        if (!current()) return;
        if (pc.connectionState === 'failed' || pc.connectionState === 'closed') this.retry('connection_lost');
        else if (pc.connectionState === 'disconnected') {
          this.show('recovering');
          clearTimeout(this.timer);
          this.timer = setTimeout(() => this.retry('connection_lost'), 3000);
        } else if (pc.connectionState === 'connected') {
          this.connected();
        }
      };
      try {
        await pc.setRemoteDescription({ type: 'offer', sdp: offer.sdp });
        if (!current()) return;
        const answer = await pc.createAnswer();
        if (!current()) return;
        await pc.setLocalDescription(answer);
        if (!current()) return;
        this.signal('webrtc/answer', { requestId: request, sessionId: offer.sessionId, sdp: pc.localDescription.sdp });
        answerSent = true;
        pendingCandidates.forEach(candidate);
        this.show('waiting_frame');
      } catch (_) { if (current()) this.retry('negotiation_failed'); }
    }
    heartbeat() {
      if (Date.now() - this.lastPong > 15000) { this.retry('heartbeat_timeout'); return; }
      if (this.dc && this.dc.readyState === 'open') this.dc.send(JSON.stringify({action: 'ping', sessionId: this.sessionId}));
    }
    frameReady() { this.firstFrame = true; this.connected(); }
    connected() {
      if (!this.firstFrame || !this.dc || this.dc.readyState !== 'open') return;
      clearTimeout(this.timer); this.timer = null;
      this.show('connected', { permission: this.permission, transport: this.pc ? 'webrtc' : 'relay' });
      this.attempt = 0;
    }
    sendInput(event) {
      if (!this.active || !this.online || !this.firstFrame || this.permission !== 'control' ||
          !this.dc || this.dc.readyState !== 'open' || this.dc.bufferedAmount > 64000) return 0;
      const seq = ++this.seq;
      this.dc.send(JSON.stringify(Object.assign({}, event, { sessionId: this.sessionId, seq })));
      return seq;
    }
    retry(reason) {
      this.cleanup(true);
      if (!this.active || !this.online) return;
      if (this.attempt >= 5) { this.show('failed', { reason }); return; }
      const delay = Math.min(1000 * Math.pow(2, Math.max(0, this.attempt - 1)), 10000);
      this.show('recovering', { reason, seconds: delay / 1000 });
      this.timer = setTimeout(() => { this.timer = null; this.request(); }, delay);
    }
    cleanup(notify) {
      clearTimeout(this.timer); this.timer = null;
      clearInterval(this.pulse); this.pulse = null;
      this.sendInput({ action: 'release_all' });
      if (notify && this.requestId) this.signal('remote_desktop/stop', { requestId: this.requestId, sessionId: this.sessionId });
      const pc = this.pc;
      this.pc = null; this.dc = null; this.requestId = null; this.sessionId = null;
      this.firstFrame = false;
      if (pc) pc.close();
      this.options.onStream(null);
    }
    pause() { this.cleanup(true); this.active = false; this.show('paused'); }
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = RemoteDesktopSession;
  else root.RemoteDesktopSession = RemoteDesktopSession;
})(typeof globalThis !== 'undefined' ? globalThis : this);
