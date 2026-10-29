const {test} = require('node:test');
const assert = require('node:assert/strict');
const Player = require('../app/src/main/assets/pc_remote-audio.js');
const Session = require('../app/src/main/assets/pc_remote-session.js');

function fixture() {
  const states=[], sources=[];
  const context={state:'running', currentTime:0, destination:{},
    resume:async()=>{}, close:async()=>{context.closed=true;},
    createBuffer:(channels,size,rate)=>({channels,rate,data:new Float32Array(size),getChannelData(){return this.data;}}),
    createBufferSource:()=>{const source={connect(){},disconnect(){},start(time){this.time=time;},stop(){this.stopped=true;}};sources.push(source);return source;},
  };
  const media={pause(){this.paused=true;},play:async()=>{},srcObject:null};
  const player=new Player({media,createContext:()=>context,createStream:track=>({track}),onState:s=>states.push(s)});
  return {player,context,media,states,sources};
}
function packet(seq=0) {
  const pcm=Buffer.alloc(960); pcm.writeInt16LE(-32768,0); pcm.writeInt16LE(16384,2);
  return {rate:24000,channels:1,seq,pcm:pcm.toString('base64')};
}

test('audio is opt-in; valid relay PCM decodes with the correct signed sample values',async()=>{
  const {player,sources}=fixture();player.pcm(packet());assert.equal(sources.length,0);
  await player.setEnabled(true);player.pcm(packet());
  assert.equal(sources[0].buffer.data[0],-1);assert.equal(sources[0].buffer.data[1],.5);
  player.dispose();assert.ok(sources[0].stopped);
});
test('stalled playback drops old chunks and stays below 140ms instead of accumulating',async()=>{
  const {player,sources}=fixture();await player.setEnabled(true);
  for(let i=0;i<100;i++)player.pcm(packet(i));
  assert.ok(player.sources.size<=7);assert.ok(player.next<=.14+.000001);
  assert.ok(sources.some(s=>s.stopped));player.dispose();
});
test('malformed, oversized, wrong-format and replayed packets cannot enter the audio graph',async()=>{
  const {player,sources}=fixture();await player.setEnabled(true);
  for(const bad of [{seq:-1},{seq:NaN},{rate:48000},{channels:2},{pcm:'x'.repeat(64000)},{pcm:'!'.repeat(1280)}])player.pcm({...packet(),...bad});
  assert.equal(sources.length,0);player.pcm(packet(4));player.pcm(packet(4));player.pcm(packet(3));
  assert.equal(sources.length,1);player.dispose();
});
test('disconnect or mute immediately stops every queued buffer and detaches RTC audio',async()=>{
  const {player,media,sources}=fixture();await player.setEnabled(true);player.pcm(packet());player.track({kind:'audio'});
  player.reset();assert.ok(sources[0].stopped);assert.equal(media.srcObject,null);assert.equal(media.muted,true);
  player.pcm(packet(0));assert.equal(sources.length,2);await player.setEnabled(false);player.pcm(packet(1));
  assert.equal(sources.length,2);assert.ok(sources[1].stopped);player.dispose();
});
test('autoplay rejection exposes a user gesture retry without changing the audio preference',async()=>{
  const {player,media,states}=fixture();await player.setEnabled(true);
  media.play=async()=>{throw Error('gesture required');};player.track({kind:'audio'});await Promise.resolve();await Promise.resolve();
  assert.equal(states.at(-1),'needs_gesture');assert.equal(player.enabled,true);
  media.play=async()=>{};await player.setEnabled(true);assert.equal(states.at(-1),'playing');player.dispose();
});
test('a delayed playback promise cannot revive a stopped session',async()=>{
  const {player,media,states}=fixture();await player.setEnabled(true);let resolve;
  media.play=()=>new Promise(done=>{resolve=done;});player.track({kind:'audio'});player.reset();resolve();await Promise.resolve();
  assert.notEqual(states.at(-1),'playing');player.dispose();
});
test('suspended audio context cannot accumulate fallback audio',async()=>{
  const {player,context,sources,states}=fixture();await player.setEnabled(true);context.state='suspended';player.pcm(packet());
  assert.equal(sources.length,0);assert.equal(states.at(-1),'needs_gesture');player.dispose();
});
test('session carries audio opt-in, retains quality and rejects stale audio/status packets',async t=>{
  const signals=[],packets=[],states=[];let pc;
  const session=new Session({sendSignal:(method,params)=>signals.push({method,params}),onState(){},onStream(){},
    onAudio:p=>packets.push(p),onAudioStatus:s=>states.push(s),
    createPeer:()=>{pc={setRemoteDescription:async()=>{},createAnswer:async()=>({}),setLocalDescription:async()=>{pc.localDescription={sdp:'answer'};},close(){}};return pc;}});
  t.after(()=>session.pause());session.resume();session.setOnline(true);
  const ready={method:'remote_desktop/status',params:{state:'ready',permission:'view'}};
  await session.receive(ready);assert.equal(signals.at(-1).params.audio,false);
  session.reconfigure({audio:true});await session.receive(ready);assert.equal(signals.at(-1).params.audio,true);assert.equal(signals.at(-1).params.quality,'auto');
  await session.receive({method:'remote_desktop/relay',params:{requestId:session.requestId,sessionId:'new',permission:'view',audio:'enabled'}});
  for(const params of [{requestId:'stale',sessionId:'new'},{requestId:session.requestId,sessionId:'stale'},{sessionId:'new'}]){
    await session.receive({method:'remote_desktop/audio',params:{...packet(),...params}});
    await session.receive({method:'remote_desktop/audioStatus',params:{...params,state:'unavailable'}});
  }
  assert.equal(packets.length,0);assert.deepEqual(states,['enabled']);
  await session.receive({method:'remote_desktop/audio',params:{...packet(),requestId:session.requestId,sessionId:'new'}});assert.equal(packets.length,1);
  session.reconfigure({audio:'true'});assert.equal(session.preferences.audio,true);
});
