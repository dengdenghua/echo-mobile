const { test } = require('node:test');
const assert = require('node:assert/strict');
const RemoteSession = require('../app/src/main/assets/pc_remote-session.js');

function fixture(t) {
  const signals = [], states = [], streams = [], configs = [];
  let pc;
  const session = new RemoteSession({
    sendSignal: (method, params) => { signals.push({ method, params }); return true; },
    onState: (state, detail) => states.push({ state, detail }),
    onStream: value => streams.push(value),
    createPeer: config => {
      configs.push(config);
      pc = { connectionState: 'new',
        setRemoteDescription: async () => {}, createAnswer: async () => ({type: 'answer', sdp: 'answer'}),
        setLocalDescription: async answer => { pc.localDescription = answer; }, close: () => { pc.closed = true; },
      };
      return pc;
    },
  });
  t.after(() => session.pause());
  session.resume(); session.setOnline(true);
  return { session, signals, states, streams, configs, peer: () => pc };
}

const ready = { method: 'remote_desktop/status', params: { state: 'ready', permission: 'control' } };
function offer(session) {
  return { method: 'webrtc/offer', params: {requestId: session.requestId, sessionId: 'lease', permission: 'control', sdp: 'offer', iceServers: []} };
}

test('quality change retires the old lease, preserves view-only mode and carries no input', async t => {
  const {session,signals} = fixture(t);
  await session.receive(ready); const old = session.requestId;
  session.viewOnly = true; session.reconfigure({quality:'sharp'});
  assert.equal(signals.at(-1).method,'remote_desktop/status');
  assert.ok(signals.some(s=>s.method==='remote_desktop/stop' && s.params.requestId===old));
  await session.receive(ready);
  assert.equal(signals.at(-1).params.quality,'sharp'); assert.equal(session.viewOnly,true);
  assert.equal(signals.filter(s=>s.method==='remote_desktop/input').length,0);
  session.reconfigure({quality:'arbitrary'}); assert.equal(session.preferences.quality,'sharp');
});

test('mini player blocks every input and resumes only explicit control', async t => {
  const {session,peer} = fixture(t); await session.receive(ready); await session.receive(offer(session));
  const sent=[]; const channel={readyState:'open',bufferedAmount:0,send:s=>sent.push(JSON.parse(s))};
  peer().ondatachannel({channel}); channel.onopen(); session.frameReady(); session.viewOnly=true;
  for (const action of ['tap','type','key','down','system']) assert.equal(session.sendInput({action,text:'x',x:.5,y:.5}),0);
  assert.ok(!sent.some(s=>s.seq)); session.viewOnly=false;
  assert.ok(session.sendInput({action:'key',text:'esc'}));
});

test('relay quality changes retain the working transport', async t => {
  const {session,signals} = fixture(t); await session.receive(ready);
  await session.receive({method:'remote_desktop/relay',params:{requestId:session.requestId,sessionId:'relay',permission:'control'}});
  session.frameReady(); session.reconfigure({quality:'smooth'}); await session.receive(ready);
  assert.equal(signals.at(-1).params.transport,'relay');
  assert.equal(signals.at(-1).params.quality,'smooth');
});

test('statistics ignore a retired peer and report actual inbound measurements', async t => {
  const {session,peer} = fixture(t); const values=[]; session.options.onStats=s=>values.push(s);
  await session.receive(ready); await session.receive(offer(session)); session.firstFrame=true;
  peer().getStats=async()=>new Map([[1,{type:'inbound-rtp',kind:'video',framesPerSecond:14.7,frameWidth:960,frameHeight:540}],[2,{type:'candidate-pair',state:'succeeded',nominated:true,currentRoundTripTime:.032}]]);
  await session.statistics(); assert.equal(values.at(-1).fps,15); assert.equal(values.at(-1).rtt,32);
  let resolve; peer().getStats=()=>new Promise(done=>{resolve=done;}); const pending=session.statistics();
  session.cleanup(true); resolve(new Map()); await pending; assert.equal(values.length,1);
});

test('waits for host authorization and creates one request per attempt', async t => {
  const {session, signals, states} = fixture(t);
  await session.receive({method: 'remote_desktop/status', params: {state:'authorization_required',permission:'denied'}});
  assert.equal(states.at(-1).state, 'authorization_required');
  assert.equal(signals.filter(s => s.method === 'webrtc/request').length, 0);
  await session.receive(ready); await session.receive(ready);
  assert.equal(signals.filter(s => s.method === 'webrtc/request').length, 1);
});

test('stale offers and callbacks cannot affect a replacement session', async t => {
  const {session, signals} = fixture(t);
  await session.receive(ready); const stale = offer(session);
  session.cleanup(true); session.request();
  await session.receive(stale);
  assert.equal(session.pc, null);
  await session.receive(offer(session));
  assert.equal(signals.filter(s => s.method === 'webrtc/answer').length, 1);
});

test('input is unavailable until first frame and open input channel', async t => {
  const {session, peer} = fixture(t);
  await session.receive(ready); await session.receive(offer(session));
  const sent = [];
  const channel = {readyState: 'open', bufferedAmount: 0, send: data => sent.push(JSON.parse(data))};
  peer().ondatachannel({channel}); channel.onopen();
  assert.equal(session.sendInput({action:'type',text:'中文🌏'}), 0);
  session.frameReady();
  assert.ok(session.sendInput({action:'type',text:'中文🌏'}));
  assert.equal(sent.at(-1).text, '中文🌏');
  session.permission = 'view';
  assert.equal(session.sendInput({action:'tap',x:.5,y:.5}), 0);
});

test('disconnect never queues or replays input and retires the old peer', async t => {
  const {session, peer, signals} = fixture(t);
  await session.receive(ready); await session.receive(offer(session));
  const old = peer();
  session.setOnline(false);
  assert.ok(old.closed);
  assert.equal(session.sendInput({action:'type',text:'must not replay'}), 0);
  session.setOnline(true);
  assert.equal(signals.at(-1).method, 'remote_desktop/status');
  assert.equal(session.requestId, null);
});

test('retry selects authenticated relay without requiring phone network configuration', async t => {
  const {session, signals} = fixture(t);
  await session.receive(ready);
  session.cleanup(true); session.request();
  assert.equal(signals.at(-1).params.transport, 'relay');
  await session.receive({method:'remote_desktop/relay',params:{requestId:session.requestId, sessionId:'relay-lease',permission:'control'}});
  session.frameReady();
  assert.ok(session.sendInput({action:'type',text:'兼容连接'}));
  assert.equal(signals.at(-1).method, 'remote_desktop/input');
  assert.equal(JSON.parse(signals.at(-1).params.data).text, '兼容连接');
});

test('operator stop does not trigger an automatic reconnection', async t => {
  const {session, signals, states} = fixture(t);
  await session.receive(ready);
  await session.receive({method:'remote_desktop/stopped',params:{requestId:session.requestId,reason:'operator_stopped'}});
  const count = signals.length;
  session.refresh();
  assert.equal(signals.length, count);
  assert.equal(states.at(-1).state, 'operator_stopped');
  assert.equal(session.active, false);
});

test('late ICE candidates are signaled after the SDP answer', async t => {
  const {session, peer, signals} = fixture(t);
  await session.receive(ready); await session.receive(offer(session));
  peer().onicecandidate({candidate:{toJSON:()=>({candidate:'candidate:test',sdpMid:'0',sdpMLineIndex:0})}});
  assert.equal(signals.at(-1).method, 'webrtc/candidate');
  assert.equal(signals.at(-2).method, 'webrtc/answer');
});

test('backgrounding ends the lease and cannot retain reconnect timers', async t => {
  const {session, signals} = fixture(t);
  await session.receive(ready);
  session.retry('network');
  session.pause();
  assert.equal(session.timer, null);
  assert.equal(session.pulse, null);
  assert.equal(session.active, false);
  assert.ok(signals.some(s => s.method === 'remote_desktop/stop'));
});


test('lost heartbeat retires control and schedules recovery', async t => {
  const {session, signals, states} = fixture(t);
  await session.receive(ready);
  await session.receive({method:'remote_desktop/relay',params:{requestId:session.requestId,sessionId:'relay-lease',permission:'control'}});
  session.frameReady();
  session.lastPong = Date.now() - 16000;
  session.heartbeat();
  assert.equal(session.dc, null);
  assert.equal(states.at(-1).state, 'recovering');
  assert.equal(signals.at(-1).method, 'remote_desktop/stop');
});

test('unavailable WebRTC engine recovers instead of abandoning the host session', async t => {
  const {session, states} = fixture(t);
  await session.receive(ready);
  session.options.createPeer = () => { throw new Error('unsupported'); };
  await session.receive(offer(session));
  assert.equal(session.requestId, null);
  assert.equal(states.at(-1).state, 'recovering');
});


test('operator stop after channel teardown cancels a pending retry', async t => {
  const {session, states} = fixture(t);
  await session.receive(ready); const requestId = session.requestId;
  session.retry('input_disconnected');
  await session.receive({method:'remote_desktop/stopped',params:{requestId,reason:'operator_stopped'}});
  assert.equal(session.active,false);
  assert.equal(session.timer,null);
  assert.equal(states.at(-1).state,'operator_stopped');
});

test('operator stop retires a replacement request already started during teardown', async t => {
  const {session, signals} = fixture(t);
  await session.receive(ready); const requestId = session.requestId;
  session.cleanup(true); session.request(); const replacement = session.requestId;
  await session.receive({method:'remote_desktop/stopped',params:{requestId,reason:'operator_stopped'}});
  assert.equal(session.active,false);
  assert.equal(session.requestId,null);
  assert.ok(signals.some(s => s.method==='remote_desktop/stop' && s.params.requestId===replacement));
});
