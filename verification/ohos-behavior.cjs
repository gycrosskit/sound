// 用 SDK 的 TypeScript 转译器运行真实 ArkTS 源码；系统播放器由可控替身提供。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require(process.env.SOUND_TYPESCRIPT || '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader/node_modules/typescript');
const source = fs.readFileSync(path.join(__dirname, '../ohos/sound-native/src/main/ets/SoundPlayer.ets'), 'utf8');
const pending = [], timers = new Map();
let timerId = 0;
class Player {
  handlers = new Map(); released = false; plays = 0; seeks = [];
  on(name, callback) { this.handlers.set(name, callback); }
  off(name) { this.handlers.delete(name); }
  emit(name, value) { this.handlers.get(name)?.(value); }
  set url(value) { this.remoteUrl = value; this.emit('stateChange', 'initialized'); }
  set fdSrc(value) { this.descriptor = value; this.emit('stateChange', 'initialized'); }
  async prepare() { this.emit('stateChange', 'prepared'); }
  seek(value) { this.seeks.push(value); }
  async play() { this.plays++; }
  async release() { this.released = true; }
}
const moduleExport = {};
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText, {
  exports: moduleExport,
  require(name) {
    if (name === '@kit.MediaKit') return { media: { createAVPlayer: () => new Promise(resolve => pending.push(resolve)) } };
    if (name === '@kit.ArkTS') return { url: { URL: { parseURL: value => new URL(value) } } };
    if (name === '@kit.AudioKit') return { audio: { StreamUsage: { STREAM_USAGE_GAME: 1 } } };
    if (name === '@kit.PerformanceAnalysisKit') return { hilog: { warn() {} } };
    throw new Error(`Unexpected runtime import: ${name}`);
  },
  setTimeout(fn) { const id = ++timerId; timers.set(id, fn); return id; },
  clearTimeout(id) { timers.delete(id); },
  Promise, Error,
});
const { SoundPlayer } = moduleExport;
const settle = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
const nextPlayer = async () => { const player = new Player(); assert.ok(pending.length); pending.shift()(player); await settle(); return player; };
(async () => {
  const opened = [], closed = [], states = [];
  const resources = { getRawFdSync(name) { opened.push(name); return { fd: 10, offset: 0, length: 12 }; }, closeRawFdSync(name) { closed.push(name); } };
  const sound = new SoundPlayer(resources, 'host.wav', state => states.push(state.phase));
  for (const invalid of ['http://example.test/a.wav', 'https:///a.wav', 'https://:123/a.wav', 'https://user:pass@example.test/a.wav', 'https://example.test/a b.wav']) {
    sound.prepare(invalid);
    assert.equal(sound.getState().phase, 'REMOTE_FAILED');
  }
  assert.equal(pending.length, 0);
  sound.prepare('https://example.test/old.wav');
  sound.prepare('https://example.test/new.wav');
  const old = await nextPlayer();
  assert.equal(old.released, true);
  assert.equal(sound.getState().phase, 'PREPARING');
  const remote = await nextPlayer();
  assert.equal(sound.getState().phase, 'REMOTE_READY');
  sound.prepare('https://example.test/new.wav');
  assert.equal(pending.length, 0);
  sound.play(); await settle(); sound.play();
  await settle();
  assert.equal(remote.plays, 2);
  assert.deepEqual(remote.seeks, [0, 0]);
  remote.emit('error', new Error('remote playback failed'));
  await settle();
  assert.equal(remote.released, true);
  assert.equal(sound.getState().phase, 'REMOTE_FAILED');
  const local = await nextPlayer();
  assert.equal(local.plays, 1);
  assert.deepEqual(opened, ['host.wav']);
  local.emit('stateChange', 'completed');
  await settle();
  assert.equal(local.released, true);
  assert.deepEqual(closed, ['host.wav']);
  sound.prepare('https://example.test/late.wav');
  const late = new Player();
  const done = sound.release();
  pending.shift()(late);
  await done; await settle();
  assert.equal(late.released, true);
  assert.equal(sound.getState().phase, 'RELEASED');
  assert.equal(timers.size, 0);
  sound.prepare('https://example.test/after-release.wav'); sound.play();
  assert.equal(pending.length, 0);
  assert.equal(states.at(-1), 'RELEASED');
  await sound.release();
  const timeout = new SoundPlayer(resources, 'other.wav');
  timeout.prepare('https://example.test/slow.wav');
  [...timers.values()][0]();
  assert.equal(timeout.getState().phase, 'REMOTE_FAILED');
  assert.equal((await nextPlayer()).released, true);
  await timeout.release();
  assert.equal(timers.size, 0);
  console.log('OHOS behavior: URL admission, replacement, replay, fallback, timeout, late callback and terminal release passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
