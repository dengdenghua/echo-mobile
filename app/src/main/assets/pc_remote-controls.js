/* Input state is local until an authorized live session accepts an event. */
(function (root) {
  'use strict';
  class RemoteControls {
    constructor(send, changed) {
      this.send = send; this.changed = changed || (() => {});
      this.modifiers = new Set(); this.cursor = { x: .5, y: .5 }; this.dragging = false;
    }
    toggleModifier(key) {
      if (!['ctrl', 'alt', 'shift', 'win', 'command'].includes(key)) return;
      if (this.modifiers.has(key)) this.modifiers.delete(key); else if (this.modifiers.size < 3) this.modifiers.add(key);
      this.changed(this);
    }
    key(key) {
      const combo = [...this.modifiers, key].join('+');
      const result = this.send({ action: 'key', text: combo });
      this.modifiers.clear(); this.changed(this); return result;
    }
    shortcut(combo) { this.modifiers.clear(); this.changed(this); return this.send({ action: 'key', text: combo }); }
    move(dx, dy) {
      if (!Number.isFinite(dx) || !Number.isFinite(dy)) return;
      this.cursor = { x: Math.max(0, Math.min(1, this.cursor.x + dx)), y: Math.max(0, Math.min(1, this.cursor.y + dy)) };
      this.changed(this);
      return this.send({ action: 'move', ...this.cursor });
    }
    click(right) { if (this.dragging) this.release(); return this.send({ action: right ? 'rightclick' : 'tap', ...this.cursor }); }
    drag() {
      const next = !this.dragging;
      const accepted = this.send({ action: next ? 'down' : 'up', ...this.cursor });
      if (accepted) this.dragging = next;
      this.changed(this); return accepted;
    }
    release() {
      if (this.dragging) this.send({ action: 'up', ...this.cursor });
      this.dragging = false; this.modifiers.clear(); this.changed(this);
    }
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = RemoteControls;
  else root.RemoteControls = RemoteControls;
})(typeof globalThis !== 'undefined' ? globalThis : this);
