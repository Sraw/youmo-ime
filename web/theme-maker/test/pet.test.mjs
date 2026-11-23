// The pet panel: what it writes is the app's <pet>, its sheets held to the app's PetLimits as the pack is saved, and
// a theme with a pet says min-app 0.6.3.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';
import { ENGINE, generated } from '../tools/assemble.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const src = join(here, '..', 'src');
vm.runInThisContext(readFileSync(join(src, 'lib.js'), 'utf8'), { filename: 'lib.js' });
vm.runInThisContext(readFileSync(join(src, 'form.js'), 'utf8'), { filename: 'form.js' });
vm.runInThisContext(readFileSync(join(src, 'maker.js'), 'utf8'), { filename: 'maker.js' });
globalThis.YoumoPanels = [];
for (const f of readdirSync(join(src, 'panels')).sort()) vm.runInThisContext(readFileSync(join(src, 'panels', f), 'utf8'), { filename: f });
const L = globalThis.YoumoLib;
const M = globalThis.YoumoMaker;
const pet = globalThis.YoumoPanels.find((p) => p.id === 'pet').ops;
const E = createRequire(import.meta.url)(ENGINE).YoumoEngine;
const FORMAT = JSON.parse(generated('youmo-format'));
const lim = FORMAT.limits.pet;

/** A PNG's first bytes: the signature and an IHDR of [w] × [h] (all a header-reader sees), [pad] bytes more. */
function png(w, h, pad = 0) {
  const b = new Uint8Array(33 + pad);
  b.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52]);
  new DataView(b.buffer).setUint32(16, w);
  new DataView(b.buffer).setUint32(20, h);
  b[24] = 8; b[25] = 6;
  return b;
}

const base = () => M.fromBuiltin(E, 'nord');
const errors = (d) => M.saveProblems(E, L, FORMAT, d).filter((p) => p.level === 'error').map((p) => p.message);
const read = (d) => {
  const r = E.readTheme(d.xml, null, null);
  assert.ok(r.ok, JSON.stringify(r.problems));
  return r;
};

test('the app’s limits are the page’s', () => {
  assert.deepEqual(
    [lim.sheetBytes, lim.sheetSide, lim.frameSide, lim.frames, lim.totalFrames, lim.fps, lim.restingFps, lim.minSizeDp, lim.maxSizeDp, lim.offsetDp],
    [512 * 1024, 2048, 256, 24, 96, 30, 8, 16, 64, 32]);
});

test('the panel writes a <pet> the engine reads, sheets named by their state', () => {
  let d = pet.setSheet(base(), L, lim, 'idle', 'My Cat.png', png(256, 64));
  assert.match(d.xml, /<pet>\n {4}<state name="idle" sheet="pet-idle.png" frames="4" columns="4"\/>\n {2}<\/pet>\n<\/theme>/);
  assert.ok(d.files['pet-idle.png']);
  d = pet.setSheet(d, L, lim, 'typing', 'peck.png', png(384, 64));
  d = pet.setState(d, 'typing', 'fps', '12');
  d = pet.set(d, 'place', 'bar-end');
  d = pet.set(d, 'size', '32dp');
  const r = read(d);
  assert.deepEqual(r.warnings.filter((w) => /pet/.test(w.message)), []);
  assert.match(d.xml, /<pet place="bar-end" size="32dp">/);
  assert.match(d.xml, /<state name="typing" sheet="pet-typing.png" frames="6" columns="6" fps="12"\/>/);
  // another sheet for idle: a name of its own, its frames counted anew, the old file gone
  d = pet.setSheet(d, L, lim, 'idle', 'cat2.png', png(128, 64));
  assert.match(d.xml, /sheet="pet-idle-2.png" frames="2" columns="2"/);
  assert.equal(d.files['pet-idle.png'], undefined);
  assert.deepEqual(errors(d), []);
});

test('a state taken out takes its sheet; idle taken out, the whole pet', () => {
  let d = pet.setSheet(base(), L, lim, 'idle', 'i.png', png(64, 64));
  d = pet.setSheet(d, L, lim, 'enter', 'e.png', png(128, 64));
  d = pet.remove(d, 'enter');
  assert.doesNotMatch(d.xml, /name="enter"/);
  assert.equal(d.files['pet-enter.png'], undefined);
  d = pet.remove(d, 'idle');
  assert.doesNotMatch(d.xml, /<pet/);
  assert.equal(d.xml, base().xml);
  assert.deepEqual(Object.keys(d.files).filter((f) => f.startsWith('pet-')), []);
});

// a VP8X WebP's header: its flags (0x02: animated) and its canvas size
function webp(w, h, flags) {
  const b = new Uint8Array(30);
  b.set([...'RIFF'].map((c) => c.charCodeAt(0)), 0);
  b.set([...'WEBPVP8X'].map((c) => c.charCodeAt(0)), 8);
  b[16] = 10; b[20] = flags;
  b[24] = (w - 1) & 0xff; b[25] = ((w - 1) >> 8) & 0xff;
  b[27] = (h - 1) & 0xff; b[28] = ((h - 1) >> 8) & 0xff;
  return b;
}

test('a sheet the app would refuse is not taken', () => {
  assert.throws(() => pet.setSheet(base(), L, lim, 'typing', 't.png', png(64, 64)), /先选「平时」/);
  assert.throws(() => pet.setSheet(base(), L, lim, 'idle', 'a.gif', new Uint8Array([0x47, 0x49, 0x46, 0x38])), /不是 PNG 或 WebP/);
  assert.throws(() => pet.setSheet(base(), L, lim, 'idle', 'big.png', png(512, 512)), /每帧 512×512 px，最多 256 px/);
  assert.throws(() => pet.setSheet(base(), L, lim, 'idle', 'heavy.png', png(64, 64, 600 * 1024)), /最多 512 KB/);
  assert.throws(() => pet.setSheet(base(), L, lim, 'idle', 'wide.png', png(4096, 64)), /边长最多 2048 px/);
  assert.throws(() => pet.setSheet(base(), L, lim, 'idle', 'moving.webp', webp(64, 64, 0x02)), /是动图/);
  assert.doesNotThrow(() => pet.setSheet(base(), L, lim, 'idle', 'still.webp', webp(64, 64, 0)));
});

test('the save check holds each sheet to PetLimits, as the app’s import does', () => {
  let d = pet.setSheet(base(), L, lim, 'idle', 'i.png', png(256, 64));
  d = pet.setSheet(d, L, lim, 'typing', 'p.png', png(384, 64));
  assert.deepEqual(errors(d), []);
  // columns changed by hand past what the sheet divides into
  assert.deepEqual(errors(pet.setState(d, 'typing', 'columns', '5')), ['宠物 typing 的 pet-typing.png 宽 384 px，不能分成 5 列']);
  const gone = { ...d, files: { ...d.files } };
  delete gone.files['pet-typing.png'];
  assert.deepEqual(errors(gone), ['缺少文件 pet-typing.png']);
  const bad = { ...d, files: { ...d.files, 'pet-typing.png': png(384, 300) } };
  assert.deepEqual(errors(bad), ['宠物 typing 的 pet-typing.png 每帧 64×300 px，最多 256 px']);
  const tall = pet.setState(pet.setState(d, 'typing', 'frames', '4'), 'typing', 'columns', '2');
  assert.deepEqual(errors({ ...tall, files: { ...tall.files, 'pet-typing.png': png(128, 65) } }), ['宠物 typing 的 pet-typing.png 高 65 px，不能分成 2 行']);
  // a <pet> the engine leaves out (no idle): the app copies none of it, so nothing to refuse
  const left = { ...d, xml: d.xml.replace(/<state name="idle"[^>]*\/>/, ''), files: {} };
  assert.ok(read(left).warnings.some((w) => /^<pet> left out/.test(w.message)));
  assert.deepEqual(errors(left), []);
});

test('a theme with a pet is saved with min-app 0.6.3', () => {
  const d = pet.setSheet(base(), L, lim, 'idle', 'i.png', png(64, 64));
  assert.equal(read(d).theme.info(false).minApp, '0.6.3');
  const m = M.withMinApp(E, d);
  assert.equal(m.version, '0.6.3');
  assert.match(m.doc.xml, /min-app="0\.6\.3"/);
});

test('a new state’s sheet of frames not in one row of squares is taken, its frames and columns guessed', () => {
  const idle = pet.setSheet(base(), L, lim, 'idle', 'i.png', png(64, 64));
  for (const [w, h, frames, columns] of [[384, 48, 8, 8], [64, 384, 6, 1], [1024, 384, 24, 8], [600, 200, 3, 3]]) {
    const d = pet.setSheet(idle, L, lim, 'typing', 't.png', png(w, h));
    assert.match(d.xml, new RegExp('name="typing" sheet="pet-typing.png" frames="' + frames + '" columns="' + columns + '"'), w + '×' + h);
    assert.deepEqual(read(d).warnings.filter((x) => /pet/.test(x.message)), [], w + '×' + h);
    assert.deepEqual(errors(d), [], w + '×' + h);
  }
  // frames not square, as many as fit
  assert.match(pet.setSheet(idle, L, lim, 'typing', 't.png', png(500, 100)).xml, /frames="5" columns="5"/);
});

test('another sheet for a state is counted anew unless its counts still cut square frames or it is the same size', () => {
  let d = pet.setSheet(base(), L, lim, 'idle', 'i.png', png(256, 64));
  // the counts of the sheet it replaces would play 4 frames of 96×64
  assert.match(pet.setSheet(d, L, lim, 'idle', 'j.png', png(384, 64)).xml, /sheet="pet-idle-2.png" frames="6" columns="6"/);
  // a grid of 4 × 2 squares kept on a sheet of other squares
  d = pet.setState(pet.setState(d, 'idle', 'frames', '8'), 'idle', 'columns', '4');
  d = { ...d, files: { ...d.files, 'pet-idle.png': png(256, 128) } };
  assert.match(pet.setSheet(d, L, lim, 'idle', 'j.png', png(128, 64)).xml, /frames="8" columns="4"/);
  // counts of the author's own on a sheet of the same size kept
  const own = pet.setState(pet.setSheet(base(), L, lim, 'idle', 'i.png', png(256, 64)), 'idle', 'frames', '2');
  assert.match(pet.setSheet(own, L, lim, 'idle', 'j.png', png(256, 64)).xml, /frames="2" columns="2"/);
});

test('fewer frames than the columns written: the columns follow, and the engine still reads the pet', () => {
  const d = pet.setState(pet.setSheet(base(), L, lim, 'idle', 'i.png', png(384, 64)), 'idle', 'frames', '5');
  assert.match(d.xml, /name="idle" sheet="pet-idle.png" frames="5" columns="5"/);
  assert.ok(!read(d).warnings.some((w) => /^<pet> left out/.test(w.message)), JSON.stringify(read(d).warnings));
  // more frames: the columns as written, the rows more
  assert.match(pet.setState(d, 'idle', 'frames', '10').xml, /frames="10" columns="5"/);
});

test('at the bar’s end the corner’s align and offsets are taken out', () => {
  let d = pet.setSheet(base(), L, lim, 'idle', 'i.png', png(64, 64));
  d = pet.set(pet.set(pet.set(d, 'align', 'bottom-start'), 'offset-x', '4dp'), 'offset-y', '-2dp');
  d = pet.set(pet.set(d, 'size', '32dp'), 'place', 'bar-end');
  assert.match(d.xml, /<pet size="32dp" place="bar-end">/);
  assert.deepEqual(read(d).warnings.filter((w) => /pet|bar/.test(w.message)), []);
});

test('the preview plays a state as the keyboard does after one key of it', () => {
  const P = globalThis.YoumoPet;
  const at = (d, name, ms) => P.showing(pet.read(d.xml), lim, name, ms);
  let d = pet.setSheet(base(), L, lim, 'idle', 'i.png', png(256, 64));
  d = pet.setState(d, 'idle', 'fps', '4');
  d = pet.setState(pet.setSheet(d, L, lim, 'typing', 't.png', png(128, 64)), 'typing', 'fps', '10');
  // once (200 ms at 10 fps), then idle from its first frame
  assert.deepEqual(at(d, 'typing', 150), { state: 'typing', frame: 1, next: 200 });
  assert.deepEqual(at(d, 'typing', 200), { state: 'idle', frame: 0, next: 450 });
  // looping: on past three rounds, as long as no key comes; without a sleep, for ever
  const looping = pet.setState(d, 'typing', 'loop', 'true');
  assert.deepEqual(at(looping, 'typing', 10_050), { state: 'typing', frame: 0, next: 10_100 });
  assert.deepEqual(at(looping, 'idle', 90_000).state, 'idle');
  // asleep 30 s after the key: a looping sleep three rounds, then held on its last frame
  const sleepy = pet.setSheet(looping, L, lim, 'sleep', 's.png', png(192, 64));
  assert.deepEqual(at(sleepy, 'typing', 29_999).state, 'typing');
  assert.deepEqual(at(sleepy, 'typing', 30_000), { state: 'sleep', frame: 0, next: 30_125 });
  assert.deepEqual(at(sleepy, 'typing', 30_000 + 1125), { state: 'sleep', frame: 2, next: null });
  assert.deepEqual(at(sleepy, 'sleep', 5000), { state: 'sleep', frame: 2, next: null });
  assert.deepEqual(at(pet.setState(sleepy, 'sleep', 'loop', 'false'), 'sleep', 375), { state: 'sleep', frame: 2, next: null });
  // a wake plays once, then idle
  const woken = pet.setSheet(sleepy, L, lim, 'wake', 'w.png', png(128, 64));
  assert.deepEqual(at(woken, 'wake', 250).state, 'idle');
});

test('a second 播放 before the first has its sheets stops the first; each sheet decoded once', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'] });
  const P = globalThis.YoumoPet;
  let d = pet.setSheet(base(), L, lim, 'idle', 'i.png', png(128, 64));
  d = pet.setState(pet.setSheet(d, L, lim, 'typing', 't.png', png(128, 64)), 'typing', 'fps', '10');
  const decodes = [];
  globalThis.createImageBitmap = async (blob) => { decodes.push(blob.size); return { width: 128, height: 64, close() {} }; };
  const draws = [];
  const canvas = { width: 96, height: 96, isConnected: true, getContext: () => ({ clearRect() {}, drawImage(...a) { draws.push(a[1]); } }) };
  const api = { doc: () => d, lib: L };
  try {
    const first = P.play(canvas, api, lim, 'typing');
    const second = P.play(canvas, api, lim, 'typing');
    await Promise.all([first, second]);
    assert.equal(decodes.length, 2, 'idle and typing, once each');
    // one chain: frame 0 now, frame 1 at 100 ms, idle at 200 ms
    t.mock.timers.tick(100);
    t.mock.timers.tick(100);
    assert.deepEqual(draws, [0, 64, 0]);
    P.stop();
  } finally {
    delete globalThis.createImageBitmap;
  }
});

test('a sheet the document no longer has is closed and let go, the play drawing it stopped', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'] });
  const P = globalThis.YoumoPet;
  let d = pet.setSheet(base(), L, lim, 'idle', 'i.png', png(128, 64));
  d = pet.setState(pet.setSheet(d, L, lim, 'typing', 't.png', png(128, 64)), 'typing', 'loop', 'true');
  const before = d;
  let decodes = 0;
  let closed = 0;
  globalThis.createImageBitmap = async () => { decodes++; return { width: 128, height: 64, close() { closed++; } }; };
  const draws = [];
  const canvas = { width: 96, height: 96, isConnected: true, getContext: () => ({ clearRect() {}, drawImage(...a) { draws.push(a[1]); } }) };
  const api = { doc: () => d, lib: L };
  try {
    await P.play(canvas, api, lim, 'typing');
    assert.equal(decodes, 2);
    // typing's sheet replaced: the old one goes, idle's stays
    d = pet.setSheet(d, L, lim, 'typing', 't2.png', png(128, 64, 1));
    P.release(d.files);
    await new Promise((r) => setImmediate(r));
    assert.equal(closed, 1, 'the old typing sheet closed, idle not');
    const drawn = draws.length;
    t.mock.timers.tick(1000);
    assert.equal(draws.length, drawn, 'the play drawing it stopped');
    // let go, not only closed: the old bytes back are decoded anew
    d = before;
    await P.play(canvas, api, lim, 'idle');
    assert.equal(decodes, 3, 'idle kept, the old typing decoded again');
    P.stop();
  } finally {
    delete globalThis.createImageBitmap;
  }
});
