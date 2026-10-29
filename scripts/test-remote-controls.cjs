const { test } = require('node:test');
const assert = require('node:assert/strict');
const Controls = require('../app/src/main/assets/pc_remote-controls.js');

test('modifiers affect one atomic shortcut and never hold native keys', () => {
  const events = [], controls = new Controls(event => { events.push(event); return events.length; });
  controls.toggleModifier('ctrl'); controls.toggleModifier('shift');
  assert.equal(events.length, 0);
  controls.key('z'); controls.key('a');
  assert.deepEqual(events, [{action:'key',text:'ctrl+shift+z'},{action:'key',text:'a'}]);
  controls.toggleModifier('ctrl'); controls.toggleModifier('alt'); controls.toggleModifier('shift'); controls.toggleModifier('win');
  controls.key('s'); assert.equal(events.at(-1).text.split('+').length, 4);
});
test('precise mouse clamps all four edges and ignores invalid movement', () => {
  const events = [], controls = new Controls(event => { events.push(event); return 1; });
  controls.move(3,-3); controls.click(true); controls.move(-4,4); controls.click(false);
  assert.deepEqual(events, [{action:'move',x:1,y:0},{action:'rightclick',x:1,y:0},{action:'move',x:0,y:1},{action:'tap',x:0,y:1}]);
  controls.move(NaN,0); controls.move(0,Infinity); assert.equal(events.length, 4);
});
test('drag is acknowledged locally and release clears held input even when offline', () => {
  const events = []; let online = true;
  const controls = new Controls(event => { events.push(event); return online ? 1 : 0; });
  controls.drag(); assert.equal(controls.dragging, true);
  online=false; controls.toggleModifier('ctrl'); controls.release();
  assert.equal(events.at(-1).action, 'up'); assert.equal(controls.dragging, false); assert.equal(controls.modifiers.size, 0);
  controls.drag(); assert.equal(controls.dragging, false);
});
