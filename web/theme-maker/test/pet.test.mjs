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
  // another sheet for idle: a name of its own, the old file gone
  d = pet.setSheet(d, L, lim, 'idle', 'cat2.png', png(128, 64));
  assert.match(d.xml, /sheet="pet-idle-2.png" frames="4" columns="4"/);
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
