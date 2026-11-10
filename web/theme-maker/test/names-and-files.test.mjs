// The maker's names and files as theme.xml has them: a theme's name with XML's special characters, a value with
// '>' in it, which files are still named, the effect images the app checks, the draft's files written once.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';
import { blockOf, ENGINE } from '../tools/assemble.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const src = join(here, '..', 'src');
for (const f of ['lib.js', 'form.js', 'maker.js']) vm.runInThisContext(readFileSync(join(src, f), 'utf8'), { filename: f });
globalThis.YoumoPanels = [];
for (const f of readdirSync(join(src, 'panels')).sort()) vm.runInThisContext(readFileSync(join(src, 'panels', f), 'utf8'), { filename: f });
const F = globalThis.YoumoForm;
const L = globalThis.YoumoLib;
const M = globalThis.YoumoMaker;
const P = Object.fromEntries(globalThis.YoumoPanels.map((p) => [p.id, p]));
globalThis.Path2D = class { constructor(d) { this.d = d; } };
const E = createRequire(import.meta.url)(ENGINE).YoumoEngine;
const FORMAT = JSON.parse(blockOf(readFileSync(join(here, '..', 'index.html'), 'utf8'), 'youmo-format'));

function pngHeader(w, h, length = 33) {
  const b = new Uint8Array(length);
  b.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52]);
  new DataView(b.buffer).setUint32(16, w);
  new DataView(b.buffer).setUint32(20, h);
  return b;
}

test('a theme name with &, < and quotes is written escaped and read back as typed; the engine reads the file', () => {
  const rows = [
    // [name, why]
    ['A & "B"', 'an ampersand and double quotes: escaped, never a broken attribute'],
    ['x<y>z', 'angle brackets inside the value'],
    ["it's", 'a single quote stays as it is inside double quotes'],
  ];
  for (const [name, why] of rows) {
    const xml = M.withName(M.fromBuiltin(E, 'material').xml, name);
    assert.equal(M.themeName(xml), name, why);
    const r = E.readTheme(xml, null, null);
    assert.ok(r.ok, why + ': ' + JSON.stringify(r.problems));
    assert.equal(r.theme.name, name, why);
  }
  // a name= in single quotes is changed, not given a second name=
  const single = M.withName("<theme xmlns=\"https://youmo-ime/theme/4\" name='old' version=\"4.0\"></theme>", 'new');
  assert.equal((single.match(/\bname\s*=/g) || []).length, 1);
  assert.equal(M.themeName(single), 'new');
});

test('a value with ">" in it is written escaped, and an element is not cut at a ">" inside quotes', () => {
  const xml = '<theme><meta author="A>B" version="1"/></theme>';
  const meta = F.elements(xml, 'meta');
  assert.equal(meta.length, 1);
  assert.equal(meta[0].tag, '<meta author="A>B" version="1"/>');
  assert.deepEqual(meta[0].attrs, [['author', 'A>B'], ['version', '1']]);
  const set = F.setProp(F.setProp('<theme>\n</theme>', 'key', 'fill', 'a>b'), 'key', 'color', '#ffffff');
  assert.ok(set.includes('fill="a&gt;b"'), set);
  assert.equal(F.valueOf(set, 'key', 'fill'), 'a>b');
});

test('whether a file is still named: as an attribute in either quote, or as a function\'s first argument; not in a comment', () => {
  const rows = [
    // [xml, named?, why]
    ['<icon name="x" src="f.svg"/>', true, 'an attribute in double quotes'],
    ["<icon name='x' src='f.svg'/>", true, 'an attribute in single quotes'],
    ['<style select="key" fill="image(f.svg, 1 1 1 1)"/>', true, 'a key image'],
    ['<style select="key:pressed" effect="ripple-image(f.svg, 100%, 300ms)"/>', true, 'an effect image'],
    ['<!-- <icon src="f.svg"/> -->', false, 'only in a comment'],
    ['<icon name="x" src="f.svg2"/>', false, 'another file whose name starts the same'],
  ];
  for (const [xml, named, why] of rows) assert.equal(F.isNamed(xml, 'f.svg'), named, why);
});

test('replacing an icon keeps a file a key image still names, and never writes over a file there is', () => {
  const xml = '<theme xmlns="https://youmo-ime/theme/4" name="t" version="4.0">\n  <icon name="shift" src="icon-shift.svg"/>\n  <style select="key" fill="image(icon-shift.svg, 1 1 1 1)"/>\n</theme>\n';
  const d = M.doc(xml, { 'icon-shift.svg': new Uint8Array([1]) });
  const got = P.text.ops.setIcon(d, 'shift', new Uint8Array([2]));
  assert.deepEqual([...got.files['icon-shift.svg']], [1], 'the old file still named by image(): kept, not overwritten');
  assert.ok(Object.keys(got.files).includes('icon-shift-2.svg'), Object.keys(got.files).join());
});

test('an effect image is checked before a save as the app checks it: PNG or WebP by name, its side and bytes', () => {
  const lim = Object.assign({}, FORMAT.limits, { effectImageSide: 256, effectImageBytes: 128 * 1024 });
  const format = Object.assign({}, FORMAT, { limits: lim });
  const base = M.fromBuiltin(E, 'material').xml;
  const withEffect = (fx) => F.setProp(base, 'key:pressed', 'effect', fx);
  const rows = [
    // [effect=, files, an error naming the file?, why]
    ['ripple-image(effect-a.png, 100%, 300ms)', { 'effect-a.png': pngHeader(64, 64) }, false, 'within its limits'],
    ['ripple-image(effect-a.png, 100%, 300ms)', {}, true, 'not in the pack'],
    ['particles(effect-a.png, 8, 24dp, 400ms)', { 'effect-a.png': pngHeader(512, 64) }, true, 'past 256 px, a particle image too'],
    ['ripple-image(effect-a.png, 100%, 300ms)', { 'effect-a.png': pngHeader(64, 64, 200 * 1024) }, true, 'past 128 KB'],
    ['ripple-image(effect-a.jpg, 100%, 300ms)', { 'effect-a.jpg': pngHeader(64, 64) }, true, 'not .png or .webp by its name'],
    ['ripple-image(effect-a.png, 100%, 300ms)', { 'effect-a.png': pngHeader(1000, 1000) }, true, 'within a key image\'s 1024 px but past an effect\'s: the effect\'s limit'],
  ];
  for (const [fx, files, bad, why] of rows) {
    const out = M.saveProblems(E, L, format, M.doc(withEffect(fx), files));
    assert.equal(out.some((p) => p.level === 'error' && /effect-a/.test(p.message)), bad, why + ': ' + JSON.stringify(out));
  }
  // an effect's image is not a key image the preview decodes
  const d = M.doc(withEffect('ripple-image(effect-a.png, 100%, 300ms)'), { 'effect-a.png': pngHeader(64, 64) });
  assert.deepEqual(M.decodableImages(L, FORMAT, d), []);
  assert.deepEqual(P.keys.ops.keyImages(d), []);
});

test('a theme.xml past the app\'s size is an error before a save', () => {
  const format = Object.assign({}, FORMAT, { limits: Object.assign({}, FORMAT.limits, { themeXmlBytes: 100 }) });
  const out = M.saveProblems(E, L, format, M.fromBuiltin(E, 'material'));
  assert.ok(out.some((p) => /theme\.xml/.test(p.message)), JSON.stringify(out));
});

test('a theme named as a built-in theme is an error before a save', () => {
  const format = Object.assign({}, FORMAT, { builtinNames: ['Material'] });
  const d = M.doc(M.withName(M.fromBuiltin(E, 'material').xml, 'Material'));
  assert.ok(M.saveProblems(E, L, format, d).some((p) => /内置/.test(p.message)));
});

test('a fill the engine reads is read by the form as what it is: a decimal angle, a bare picture', () => {
  assert.deepEqual(F.parseFill('linear(45.5deg, #000000 0%, #ffffff 100%)'), { kind: 'linear', angle: 45.5, stops: [['#000000', 0], ['#ffffff', 100]] });
  assert.equal(F.parseFill('picture').kind, 'picture');
});

test('a new picture over an animated one drops animated=; one only tuned keeps it', () => {
  const xml = '<theme xmlns="https://youmo-ime/theme/4" name="t" version="4.0">\n  <picture src="bg.webp" animated="true">\n    <cut orientation="portrait" file="p.png"/>\n  </picture>\n</theme>\n';
  const d = M.doc(xml, { 'bg.webp': new Uint8Array([1]), 'p.png': new Uint8Array([2]) });
  const img = new Uint8Array([3]);
  const picked = P.background.ops.setPicture(d, { type: 'jpg', src: img, cuts: { portrait: { bytes: img, type: 'jpg', rect: null } } });
  assert.ok(!/animated/.test(picked.xml), picked.xml);
  assert.ok(/animated="true"/.test(P.background.ops.tune(d, { blur: 4 }).xml));
});

test('the draft writes the files again only when they changed; a draft of the page before still opens', async () => {
  const m = new Map();
  const puts = [];
  const store = { get: async (k) => (m.has(k) ? structuredClone(m.get(k)) : undefined), put: async (k, v) => { puts.push(k); m.set(k, structuredClone(v)); }, remove: async (k) => { m.delete(k); } };
  const d = M.doc('<a/>', { 'p.png': new Uint8Array([1, 2]) });
  await M.keepDraft(store, d, M.ENV);
  await M.keepDraft(store, M.doc('<b/>', d.files), M.ENV);
  assert.equal(puts.filter((k) => k === 'draft-files').length, 2, 'doc() copies the files object: written again');
  const e = Object.assign({}, d, { xml: '<c/>' }); // an edit of the text sharing its files object
  await M.keepDraft(store, e, M.ENV);
  await M.keepDraft(store, Object.assign({}, e, { xml: '<d/>' }), M.ENV);
  assert.equal(puts.filter((k) => k === 'draft-files').length, 3, 'the same files object: not written again');
  const back = await M.draft(store);
  assert.equal(back.doc.xml, '<d/>');
  assert.deepEqual([...back.doc.files['p.png']], [1, 2]);
  m.clear();
  m.set('draft', { v: 1, doc: { xml: '<old/>', files: { 'q.png': new Uint8Array([9]) }, layouts: {} }, env: null, at: 1 });
  const old = await M.draft(store);
  assert.deepEqual([...old.doc.files['q.png']], [9]);
});
