// The maker's document (src/maker.js, 060-render-p3 M1): opened and saved as the app imports it, kept as a
// draft and given back, undone and redone, its preview's environment the engine's.
// Run: ./gradlew :lib:keyboard-scene:youmoEngine, then node --test web/theme-maker/test/maker.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';
import { blockOf, ENGINE } from '../tools/assemble.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const src = join(here, '..', 'src');
const testdata = join(here, 'testdata');
vm.runInThisContext(readFileSync(join(src, 'lib.js'), 'utf8'), { filename: 'lib.js' });
vm.runInThisContext(readFileSync(join(src, 'maker.js'), 'utf8'), { filename: 'maker.js' });
const L = globalThis.YoumoLib;
const M = globalThis.YoumoMaker;
globalThis.Path2D = class { constructor(d) { this.d = d; } };
const E = createRequire(import.meta.url)(ENGINE).YoumoEngine;
const FORMAT = JSON.parse(blockOf(readFileSync(join(here, '..', 'index.html'), 'utf8'), 'youmo-format'));
const bytes = (name) => new Uint8Array(readFileSync(join(testdata, name)));
const enc = (t) => new TextEncoder().encode(t);

// a store as IndexedDB is to the page: values kept by structured clone, so what comes back is a copy
function memoryStore() {
  const m = new Map();
  return { get: async (k) => (m.has(k) ? structuredClone(m.get(k)) : undefined), put: async (k, v) => { m.set(k, structuredClone(v)); }, remove: async (k) => { m.delete(k); }, m };
}

test('a built-in theme starts a document named apart from it, which the engine reads', () => {
  for (const id of E.themes()) {
    const d = M.fromBuiltin(E, id);
    assert.equal(M.themeName(d.xml), id + '-mine');
    assert.ok(E.readTheme(d.xml, null, null).ok, id);
  }
  assert.throws(() => M.fromBuiltin(E, 'none'), /no built-in theme/);
});

test('a document saved opens again the same: theme.xml, its files, its layouts kept as they are', async () => {
  const base = M.fromBuiltin(E, 'nord');
  const d = M.doc(base.xml, { 'a.png': new Uint8Array([1, 2, 3]) }, { 'k18.xml': '<layout>hand written, kept</layout>' });
  const saved = M.save(L, d);
  assert.equal(saved.name, 'nord-mine.theme');
  const names = L.readZip(saved.bytes).map((e) => e.name);
  assert.deepEqual(names, ['theme.xml', 'a.png', 'layouts/k18.xml'], 'theme.xml first, the app finds it at the top');
  const { doc, notes } = await M.open(E, L, FORMAT, saved.bytes);
  assert.ok(M.same(doc, d));
  assert.deepEqual(notes, [{ kind: 'layouts', ids: ['k18'] }]);
  // saved again: byte for byte
  assert.deepEqual(M.save(L, doc).bytes, saved.bytes);
});

test('a 3.0 and a 2.1 theme open as 4.0, as the engine converts them, their files renamed with them', async () => {
  for (const [file, from] of [['from-web.theme', '3.0'], ['from-app.theme', '3.0'], ['from-app-2.1.theme', '2.1']]) {
    const { doc, notes } = await M.open(E, L, FORMAT, bytes(file));
    assert.deepEqual(notes, [{ kind: 'converted', from }], file);
    const read = E.readTheme(doc.xml, null, null);
    assert.ok(read.ok, file + ': ' + JSON.stringify(read.problems));
    for (const m of doc.xml.matchAll(/\b(?:src|file)="([^"]+)"/g)) assert.ok(m[1] in doc.files, file + ' has ' + m[1]);
    // saved and opened again: the same document, now a pack
    const again = await M.open(E, L, FORMAT, M.save(L, doc).bytes);
    assert.ok(M.same(again.doc, doc), file);
    assert.deepEqual(again.notes, []);
  }
});

test('a bare theme.xml opens; a zip past the limits or not a theme is refused', async () => {
  const xml = M.fromBuiltin(E, 'pixel').xml;
  assert.equal((await M.open(E, L, FORMAT, enc(xml))).doc.xml, xml);
  const many = L.writeZip([['theme.xml', enc(xml)], ...Array.from({ length: FORMAT.limits.entries }, (_, i) => ['f' + i + '.png', new Uint8Array(1)])]);
  await assert.rejects(M.open(E, L, FORMAT, many), (e) => e.code === 'limit');
  await assert.rejects(M.open(E, L, FORMAT, L.writeZip([['readme.txt', enc('hi')]])));
});

test('a picture made the keyboard\'s: the file in the pack, the engine draws it', () => {
  const d = M.withPicture(M.fromBuiltin(E, 'material'), new Uint8Array([9]), 'jpeg');
  assert.ok('picture.jpg' in d.files);
  const r = E.readTheme(d.xml, null, null);
  assert.ok(r.ok, JSON.stringify(r.problems));
  assert.match(d.xml, /<style select="keyboard" fill="picture"\/>/);
  // a second picture replaces the first
  const twice = M.withPicture(d, new Uint8Array([8]), 'png');
  assert.equal(twice.xml.match(/<picture\b/g).length, 1);
  assert.ok(E.readTheme(twice.xml, null, null).ok);
});

test('undo and redo walk the documents back and forth, at most 100 back; a new edit drops what was undone', () => {
  const a = M.doc('<a/>');
  const h = new M.History(a);
  assert.ok(!h.canUndo && !h.canRedo);
  assert.equal(h.push(M.doc('<a/>')), false, 'the same document is no step');
  h.push(M.doc('<b/>'));
  h.push(M.doc('<c/>'));
  assert.equal(h.undo().xml, '<b/>');
  assert.equal(h.undo().xml, '<a/>');
  assert.equal(h.undo().xml, '<a/>', 'nothing before the first');
  assert.equal(h.redo().xml, '<b/>');
  h.push(M.doc('<d/>'));
  assert.ok(!h.canRedo);
  assert.equal(h.redo().xml, '<d/>');
  for (let i = 0; i < 150; i++) h.push(M.doc('<x' + i + '/>'));
  let back = 0;
  while (h.canUndo) { h.undo(); back++; }
  assert.equal(back, M.UNDO_LIMIT);
});

test('a draft kept is given back whole; none, a broken one or a failing store is no draft, never a throw', async () => {
  const s = memoryStore();
  assert.equal(await M.draft(s), null);
  const d = M.doc(M.fromBuiltin(E, 'monokai').xml, { 'p.png': new Uint8Array([1, 2]) }, { 'k14.xml': '<layout/>' });
  const env = Object.assign({}, M.ENV, { board: 'nine', dark: true });
  assert.ok(await M.keepDraft(s, d, env));
  const back = await M.draft(s);
  assert.ok(M.same(back.doc, d));
  assert.deepEqual(back.env, env);
  await M.dropDraft(s);
  assert.equal(await M.draft(s), null);
  s.m.set('draft', { v: 99 });
  assert.equal(await M.draft(s), null, 'another version');
  const failing = { get: async () => { throw new Error('private'); }, put: async () => { throw new Error('private'); }, remove: async () => { throw new Error('private'); } };
  assert.equal(await M.keepDraft(failing, d, env), false);
  assert.equal(await M.draft(failing), null);
  await M.dropDraft(failing);
});

test('the environment is the engine\'s: every board and form builds, its size the device\'s keyboard', () => {
  const d = M.fromBuiltin(E, 'material');
  for (const board of M.BOARDS) {
    for (const form of M.FORMS) {
      for (const landscape of [false, true]) {
        const env = M.envJson(Object.assign({}, M.ENV, { board, form, landscape }));
        const r = M.check(E, d, board, env);
        assert.deepEqual(r.problems.filter((p) => p.level === 'error'), [], board + ' ' + form);
        assert.ok(r.scene, board + ' ' + form);
      }
    }
  }
  assert.deepEqual(M.envJson(M.ENV), { width: 1080, height: Math.round(267 * 2.625), density: 2.625 });
  assert.equal(M.envJson(Object.assign({}, M.ENV, { landscape: true })).width, 2340);
});

test('what is wrong is said with its line: a theme the engine refuses has no scene', () => {
  const r = M.check(E, M.doc('<theme xmlns="https://youmo-ime/theme/4" version="4.0">\n<bogus/>\n</theme>'), 'qwerty', M.envJson(M.ENV));
  assert.equal(r.scene, null);
  assert.ok(r.problems.some((p) => p.level === 'error' && p.line > 0), JSON.stringify(r.problems));
});
