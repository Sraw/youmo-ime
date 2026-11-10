// The maker's names and files as theme.xml has them: a theme's name with XML's special characters, a value with
// '>' in it, which files are still named, the effect images the app checks, the draft's files written once.
// contract: the page's youmo-format block (index.html) and the app's ThemeAssetLimits / built-in names it is held to.
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
  // a ' name=' inside another attribute's quoted value is not the theme's name: the real name= is the one changed
  const inside = M.withName('<theme xmlns="https://youmo-ime/theme/4" author="x name=y" name="old" version="4.0"></theme>', 'new');
  assert.ok(inside.includes('author="x name=y"'), inside);
  assert.equal(M.themeName(inside), 'new');
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

test('save refuses what the app refuses: an effect image, a theme.xml past its size, a built-in theme\'s name', () => {
  // youmo-format's own limits (the app's ThemeAssetLimits): a block missing them would turn these checks off
  assert.deepEqual([FORMAT.limits.effectImageSide, FORMAT.limits.effectImageBytes, FORMAT.limits.themeXmlBytes], [256, 128 * 1024, 1024 * 1024]);
  assert.ok(FORMAT.builtinNames.includes('MaterialLight'), FORMAT.builtinNames.join());
  const base = M.fromBuiltin(E, 'material').xml;
  const withEffect = (fx) => F.setProp(base, 'key:pressed', 'effect', fx);
  const big = M.withName(base, 'big').replace('</theme>', '<!--' + '汉'.repeat(400 * 1024) + '-->\n</theme>');
  const rows = [
    // [document, a problem matching, refused?, why]
    [M.doc(withEffect('ripple-image(effect-a.png, 100%, 300ms)'), { 'effect-a.png': pngHeader(64, 64) }), /effect-a/, false, 'an effect image within its limits'],
    [M.doc(withEffect('ripple-image(effect-a.png, 100%, 300ms)')), /effect-a/, true, 'an effect image not in the pack'],
    [M.doc(withEffect('particles(effect-a.png, 8, 24dp, 400ms)'), { 'effect-a.png': pngHeader(512, 64) }), /effect-a/, true, 'past 256 px, a particle image too'],
    [M.doc(withEffect('ripple-image(effect-a.png, 100%, 300ms)'), { 'effect-a.png': pngHeader(64, 64, 200 * 1024) }), /effect-a/, true, 'past 128 KB'],
    [M.doc(withEffect('ripple-image(effect-a.jpg, 100%, 300ms)'), { 'effect-a.jpg': pngHeader(64, 64) }), /effect-a/, true, 'not .png or .webp by its name'],
    [M.doc(withEffect('ripple-image(effect-a.png, 100%, 300ms)'), { 'effect-a.png': pngHeader(1000, 1000) }), /effect-a/, true, 'within a key image\'s 1024 px but past an effect\'s: the effect\'s limit'],
    [M.doc(big), /theme\.xml/, true, 'a theme.xml past the app\'s 1 MB in bytes (UTF-8), within the engine\'s characters'],
    [M.doc(M.withName(base, 'MaterialLight')), /内置/, true, 'named as a built-in theme: the app would take it for its own'],
  ];
  for (const [d, what, bad, why] of rows) {
    const out = M.saveProblems(E, L, FORMAT, d);
    assert.equal(out.some((p) => p.level === 'error' && what.test(p.message)), bad, why + ': ' + JSON.stringify(out).slice(0, 300));
  }
  // an effect's image is not a key image the preview decodes
  const d = M.doc(withEffect('ripple-image(effect-a.png, 100%, 300ms)'), { 'effect-a.png': pngHeader(64, 64) });
  assert.deepEqual(M.decodableImages(L, FORMAT, d), []);
  assert.deepEqual(P.keys.ops.keyImages(d), []);
});

test('an effect the engine reads is read by the form as it is: a particle spread of a bare 0', () => {
  const rows = [
    // [effect=, read, why]
    ['particles(f.png, 8, 0, 400ms)', { kind: 'particles', file: 'f.png', count: 8, spread: 0, ms: 400 }, 'a bare 0, as the engine reads it'],
    ['particles(f.png, 8, 24dp, 400ms)', { kind: 'particles', file: 'f.png', count: 8, spread: 24, ms: 400 }, 'a spread in dp'],
    ['particles(f.png, 8, 5, 400ms)', null, 'a bare number but 0: no unit, not read'],
  ];
  for (const [text, read, why] of rows) assert.deepEqual(P.motion.ops.parseEffect(text), read, why);
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

test('the draft writes the files again only when they changed; drafts of the pages before still open', async () => {
  const m = new Map();
  const puts = [];
  const store = { get: async (k) => (m.has(k) ? structuredClone(m.get(k)) : undefined), put: async (k, v) => { puts.push(k); m.set(k, structuredClone(v)); }, remove: async (k) => { m.delete(k); }, keys: async () => [...m.keys()] };
  const filePuts = () => puts.filter((k) => k.startsWith('draft-files')).length;
  const d = M.doc('<a/>', { 'p.png': new Uint8Array([1, 2]) });
  await M.keepDraft(store, d, M.ENV);
  await M.keepDraft(store, M.doc('<b/>', d.files), M.ENV);
  assert.equal(filePuts(), 2, 'doc() copies the files object: written again');
  assert.deepEqual([...m.keys()].sort(), ['draft', 'draft-files-' + m.get('draft').files], 'the files the text no longer names are let go');
  const e = Object.assign({}, d, { xml: '<c/>' }); // an edit of the text sharing its files object
  await M.keepDraft(store, e, M.ENV);
  await M.keepDraft(store, Object.assign({}, e, { xml: '<d/>' }), M.ENV);
  assert.equal(filePuts(), 3, 'the same files object: not written again');
  const back = await M.draft(store);
  assert.equal(back.doc.xml, '<d/>');
  assert.deepEqual([...back.doc.files['p.png']], [1, 2]);
  // the drafts of the pages before: each opens with its own files, never with files it does not name
  const v1files = { 'q.png': new Uint8Array([9]) };
  const rows = [
    // [records, xml opened or null, why]
    [{ draft: { v: 1, doc: { xml: '<v1/>', files: v1files, layouts: {} }, env: null, at: 1 } }, '<v1/>', 'files kept with the text'],
    [{ draft: { v: 2, doc: { xml: '<v2/>' } }, 'draft-files': v1files }, '<v2/>', 'files apart, with no id'],
    [{ draft: { v: 2, doc: { xml: '<v2/>' } }, 'draft-files': { id: 'x', files: v1files } }, null, 'a newer page wrote its files since'],
    [{ draft: { v: 3, doc: { xml: '<v3/>' }, files: 'x' }, 'draft-files': { id: 'x', files: v1files } }, '<v3/>', 'one record, its id'],
    [{ draft: { v: 3, doc: { xml: '<v3/>' }, files: 'x' }, 'draft-files': { id: 'y', files: v1files } }, null, 'one record, another id'],
    [{ draft: { v: 4, doc: { xml: '<v4/>' }, files: 'x' }, 'draft-files-y': { id: 'y', files: v1files } }, null, 'the record it names is gone'],
  ];
  for (const [records, xml, why] of rows) {
    m.clear();
    for (const [k, v] of Object.entries(records)) m.set(k, v);
    const got = await M.draft(store);
    assert.equal(got && got.doc.xml, xml, why);
    if (xml) assert.deepEqual([...got.doc.files['q.png']], [9], why);
  }
  // two tabs on one store: each keeps the text with its own files, and the one that wrote last opens
  m.clear();
  const tabA = Object.create(store);
  const tabB = Object.create(store); // another page: its own memory of the files it wrote
  const b = M.doc('<b/>', { 'b.png': new Uint8Array([7]) });
  await M.keepDraft(tabA, d, M.ENV);
  await M.keepDraft(tabB, b, M.ENV);
  await M.keepDraft(tabA, Object.assign({}, d, { xml: '<a2/>' }), M.ENV); // only the text changed in A
  const last = await M.draft(store);
  assert.equal(last.doc.xml, '<a2/>');
  assert.deepEqual(Object.keys(last.doc.files), ['p.png'], 'A\'s text with A\'s files');
  assert.equal(m.size, 3, 'the text, A\'s files and B\'s (B lets its own go when it writes again): ' + [...m.keys()].join());
  await M.keepDraft(tabB, Object.assign({}, b, { xml: '<b2/>' }), M.ENV);
  assert.deepEqual(Object.keys((await M.draft(store)).doc.files), ['b.png']);
  assert.equal(m.size, 3, [...m.keys()].join());
  // B writes all of its draft after A read the text and before A wrote it: A's text still opens with A's files
  let between = null;
  const slowA = Object.create(store);
  slowA.get = async (k) => {
    const v = await store.get(k);
    if (k === 'draft' && between) { const f = between; between = null; await f(); }
    return v;
  };
  await M.keepDraft(slowA, d, M.ENV);
  await M.keepDraft(tabB, Object.assign({}, b, { xml: '<b3/>' }), M.ENV);
  await M.keepDraft(slowA, d, M.ENV); // A wrote last: its files, then only its text from now on
  between = () => M.keepDraft(tabB, Object.assign({}, b, { xml: '<b4/>' }), M.ENV);
  await M.keepDraft(slowA, Object.assign({}, d, { xml: '<a3/>' }), M.ENV);
  const both = await M.draft(store);
  assert.equal(both && both.doc.xml, '<a3/>', 'A wrote its text last');
  assert.deepEqual(Object.keys(both.doc.files), ['p.png'], 'with A\'s files, not lost');
  // a write cut between the files and the text: the text before still opens with its own files
  m.clear();
  await M.keepDraft(store, d, M.ENV);
  const cut = { get: store.get, put: async (k, v) => { if (k === 'draft') throw new Error('cut'); return store.put(k, v); }, remove: store.remove };
  assert.equal(await M.keepDraft(cut, M.doc('<z/>', { 'z.png': new Uint8Array([3]) }), M.ENV), false);
  assert.deepEqual(Object.keys((await M.draft(store)).doc.files), ['p.png']);
  // the files of cut writes are let go by the page's next write; the record of the page that wrote before is
  // left: without the tabs' writes one at a time (no store.lock) a first write lets go of no other page's record
  const records = () => [...m.keys()].filter((k) => k.startsWith('draft-files-'));
  const fromStore = ['draft-files-' + m.get('draft').files]; // the cut writes never named theirs
  await M.keepDraft(cut, M.doc('<z/>', { 'z.png': new Uint8Array([3]) }), M.ENV);
  cut.put = store.put;
  await M.keepDraft(cut, Object.assign({}, d, { xml: '<y/>' }), M.ENV);
  const fromCut = 'draft-files-' + m.get('draft').files;
  assert.deepEqual(records().sort(), [...fromStore, fromCut].sort(), 'no files of a cut write left behind');
  // dropped by a page whose write was cut: the files of that write go too
  m.clear();
  const cutAgain = { get: store.get, put: async (k, v) => { if (k === 'draft') throw new Error('cut'); return store.put(k, v); }, remove: store.remove };
  assert.equal(await M.keepDraft(cutAgain, d, M.ENV), false);
  assert.equal(records().length, 1);
  await M.dropDraft(cutAgain);
  assert.deepEqual(records(), [], 'the cut write\'s files dropped');
  // the tabs' writes one at a time (the browser's Web Locks): a page's first write lets go of the record of a
  // page closed before, and never of a record an open tab is about to name
  m.clear();
  let chain = Promise.resolve();
  const locking = Object.create(store);
  locking.lock = (act) => { const r = chain.then(act); chain = r.catch(() => {}); return r; };
  await M.keepDraft(Object.create(locking), d, M.ENV); // a page, then closed
  const closedRecord = 'draft-files-' + m.get('draft').files;
  m.set('draft-files-gone', { id: 'gone', files: {} }); // a page closed while another tab's text was there
  let during = null;
  const openA = Object.create(locking);
  openA.get = async (k) => {
    const v = await store.get(k);
    if (k === 'draft' && during) {
      const f = during;
      during = null;
      f();
      await new Promise((r) => setTimeout(r, 0)); // time for the other write to finish, were it not made to wait
    }
    return v;
  };
  // A's first write cut: it lets go of nothing of another page's, and its first write that goes in still does
  const cutPut = openA.put;
  openA.put = async (k, v) => { if (k === 'draft') throw new Error('cut'); return store.put(k, v); };
  assert.equal(await M.keepDraft(openA, b, M.ENV), false);
  assert.ok(m.has(closedRecord) && m.has('draft-files-gone'), 'a cut write lets go of nothing');
  openA.put = cutPut;
  await M.keepDraft(openA, b, M.ENV);
  assert.deepEqual([...m.keys()].filter((k) => k.startsWith('draft-files-')), ['draft-files-' + m.get('draft').files], 'nor the cut write\'s files left');
  assert.ok(!m.has(closedRecord), 'the closed page\'s record let go by the next page\'s first write');
  assert.ok(!m.has('draft-files-gone'), 'and any other the text does not name');
  let fresh = null; // a new tab's first write, asked for while A is between reading the text and writing it
  during = () => { fresh = M.keepDraft(Object.create(locking), M.doc('<f/>', { 'f.png': new Uint8Array([5]) }), M.ENV); };
  await M.keepDraft(openA, Object.assign({}, b, { xml: '<a4/>' }), M.ENV); // only the text: names its record again
  await fresh;
  const now = await M.draft(store);
  assert.equal(now && now.doc.xml, '<f/>', 'the new tab waited for A and wrote last');
  assert.deepEqual(Object.keys(now.doc.files), ['f.png']);
  await M.keepDraft(openA, Object.assign({}, b, { xml: '<a5/>' }), M.ENV);
  const again = await M.draft(store);
  assert.equal(again && again.doc.xml, '<a5/>');
  assert.deepEqual(Object.keys(again.doc.files), ['b.png'], 'A wrote its files again');
  // a lock refused (a file:// page): written as without one, never a write that fails
  const refused = Object.create(store);
  refused.lock = async () => { throw new Error('SecurityError'); };
  const others = [...m.keys()].filter((k) => k.startsWith('draft-files-'));
  assert.equal(others.length, 2, 'A\'s and the new tab\'s');
  assert.equal(await M.keepDraft(refused, d, M.ENV), true);
  for (const k of others) assert.ok(m.has(k), 'a write without the lock lets go of no other page\'s record: ' + k);
  await M.dropDraft(refused);
  assert.equal(await M.draft(store), null, 'dropped');
  // an old draft written over: its files record goes too
  m.clear();
  m.set('draft', { v: 3, doc: { xml: '<v3/>' }, files: 'x' });
  m.set('draft-files', { id: 'x', files: v1files });
  await M.keepDraft(store, d, M.ENV);
  assert.ok(!m.has('draft-files'));
  // dropped: nothing comes back, nothing is left in the store, and the next draft writes its files again
  m.clear();
  await M.keepDraft(store, d, M.ENV);
  await M.dropDraft(store);
  assert.equal(await M.draft(store), null);
  assert.equal(m.size, 0, [...m.keys()].join());
  puts.length = 0;
  await M.keepDraft(store, d, M.ENV);
  assert.deepEqual(puts.map((k) => k.replace(/-[^-]+-[^-]+$/, '-<id>')), ['draft-files-<id>', 'draft']);
});
