/* Bounded system-audio playback. No microphone access, recording or persistence. */
(function (root) {
  'use strict';
  class RemoteAudioPlayer {
    constructor(options) {
      this.options = options;
      this.media = options.media;
      this.enabled = false;
      this.context = null;
      this.sources = new Set();
      this.next = 0;
      this.sequence = -1;
      this.epoch = 0;
    }
    state(value) {
      if (this.lastState === value) return;
      this.lastState = value;
      if (this.options.onState) this.options.onState(value);
    }
    async setEnabled(value) {
      this.enabled = value === true;
      if (!this.enabled) { this.reset(); this.state('off'); return; }
      // Called directly from the sound button, before any network negotiation.
      try {
        if (!this.context) this.context = this.options.createContext();
        await this.context.resume();
        if (this.enabled && this.media.srcObject) await this.playMedia();
      } catch (_) { if (this.enabled) this.state('needs_gesture'); }
    }
    async playMedia() {
      const epoch = this.epoch;
      this.media.muted = false;
      try {
        await this.media.play();
        if (this.enabled && epoch === this.epoch) this.state('playing');
      } catch (_) { if (this.enabled && epoch === this.epoch) this.state('needs_gesture'); }
    }
    track(track) {
      if (!this.enabled || !track || track.kind !== 'audio') return;
      this.media.srcObject = this.options.createStream(track);
      void this.playMedia();
    }
    pcm(packet) {
      const context = this.context;
      if (!this.enabled || !context || packet.rate !== 24000 || packet.channels !== 1 ||
          !Number.isSafeInteger(packet.seq) || packet.seq < 0 || packet.seq <= this.sequence ||
          typeof packet.pcm !== 'string' || packet.pcm.length !== 1280) return;
      if (context.state !== 'running') { this.state('needs_gesture'); return; }
      let raw;
      try { raw = atob(packet.pcm); } catch (_) { return; }
      if (raw.length !== 960) return;
      this.sequence = packet.seq;
      // Drop old playback instead of building latency after a stalled tab/network.
      if (this.next > context.currentTime + .12 || this.sources.size >= 7) this.clearSources();
      const buffer = context.createBuffer(1, 480, 24000), data = buffer.getChannelData(0);
      for (let i = 0; i < data.length; i++) {
        const value = raw.charCodeAt(i * 2) | raw.charCodeAt(i * 2 + 1) << 8;
        data[i] = (value & 32768 ? value - 65536 : value) / 32768;
      }
      const source = context.createBufferSource(); source.buffer = buffer;
      source.connect(context.destination); this.sources.add(source);
      source.onended = () => { this.sources.delete(source); source.disconnect(); };
      this.next = Math.max(context.currentTime + .02, this.next);
      source.start(this.next); this.next += .02;
      this.state('playing');
    }
    clearSources() {
      for (const source of this.sources) { try { source.stop(); } catch (_) { /* Already ended. */ } source.disconnect(); }
      this.sources.clear(); this.next = 0;
    }
    reset() {
      this.epoch++; this.sequence = -1; this.lastState = null; this.clearSources();
      this.media.pause(); this.media.muted = true; this.media.srcObject = null;
    }
    dispose() {
      this.enabled = false; this.reset();
      const context = this.context; this.context = null;
      if (context) void context.close();
    }
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = RemoteAudioPlayer;
  else root.RemoteAudioPlayer = RemoteAudioPlayer;
})(typeof globalThis !== 'undefined' ? globalThis : this);
