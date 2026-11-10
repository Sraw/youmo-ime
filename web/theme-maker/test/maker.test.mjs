// The maker's document (src/maker.js, 060-render-p3 M1): opened and saved as the app imports it, kept as a
// draft and given back, undone and redone, its preview's environment the engine's.
// Run: ./gradlew :lib:keyboard-scene:youmoEngine, then node --test web/theme-maker/test/maker.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
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
vm.runInThisContext(readFileSync(join(src, 'form.js'), 'utf8'), { filename: 'form.js' });
globalThis.YoumoPanels = [];
for (const f of readdirSync(join(src, 'panels')).sort()) vm.runInThisContext(readFileSync(join(src, 'panels', f), 'utf8'), { filename: f });
const F = globalThis.YoumoForm;
const P = Object.fromEntries(globalThis.YoumoPanels.map((p) => [p.id, p]));
// a picture made the keyboard's as the 背景 page makes one: the picture and its two cuts, of [image] ([type])
const withPicture = (d, image, type) => P.background.ops.setPicture(d, { type, src: image, cuts: { portrait: { bytes: image, type, rect: null }, landscape: { bytes: image, type, rect: null } } });
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
  const d = withPicture(M.fromBuiltin(E, 'material'), new Uint8Array([9]), 'jpg');
  assert.ok('picture.jpg' in d.files);
  const r = E.readTheme(d.xml, null, null);
  assert.ok(r.ok, JSON.stringify(r.problems));
  assert.equal(F.valueOf(d.xml, 'keyboard', 'fill'), 'picture');
  // a second picture replaces the first
  const twice = withPicture(d, new Uint8Array([8]), 'png');
  assert.equal(twice.xml.match(/<picture\b/g).length, 1);
  assert.equal(twice.xml.match(/fill="picture"/g).length, 1, 'the keyboard style not added twice');
  assert.ok(!('picture.jpg' in twice.files) && !('picture-portrait.jpg' in twice.files), 'the old picture files go with it');
  assert.ok(E.readTheme(twice.xml, null, null).ok);
  // a self-closing picture and one inside a comment
  const odd = M.doc(d.xml.replace(/<picture\b[\s\S]*?<\/picture>/, '<!-- <picture src="c.png"/> --><picture src="old.png"/>'), { 'old.png': new Uint8Array([1]) });
  const fixed = withPicture(odd, new Uint8Array([8]), 'png');
  assert.ok(!('old.png' in fixed.files));
  assert.match(fixed.xml, /<!-- <picture src="c.png"\/> -->/);
  assert.equal(fixed.xml.replace(/<!--[\s\S]*?-->/g, '').match(/<picture\b/g).length, 1);
});

// a PNG [w]×[h] by its header alone (what the page's checks read), [alpha] its colour type
function pngHeader(w, h) {
  const b = new Uint8Array(33);
  b.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52]);
  new DataView(b.buffer).setUint32(16, w);
  new DataView(b.buffer).setUint32(20, h);
  return b;
}
function jpegHeader(w, h) {
  return new Uint8Array([0xff, 0xd8, 0xff, 0xc0, 0, 17, 8, h >> 8, h & 255, w >> 8, w & 255, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xd9]);
}

test('a picture picked is always encoded anew, its long side at most PICTURE_SAVE.longSide; one not an image is refused', async () => {
  assert.deepEqual(M.PICTURE_SAVE, { longSide: 2560, quality: 0.85 });
  const asked = [];
  // an encoder that decodes the header's sides as they are, and one that decodes them turned (EXIF 6/8)
  const encoder = (turned) => async (b, sizeOf, q) => {
    const s = L.imageSize(b);
    const [w, h] = turned ? sizeOf(s[1], s[0]) : sizeOf(s[0], s[1]);
    asked.push([w, h, q]);
    return { bytes: jpegHeader(w, h), type: 'jpg' };
  };
  const encode = encoder(false);
  const big = await M.pictureFile(L, FORMAT, jpegHeader(4608, 3456), encode);
  assert.deepEqual(asked.pop(), [2560, 1920, 0.85]);
  assert.equal(big.type, 'jpg');
  // a portrait photo stored landscape with an EXIF turn: sized by what decodes, upright, not squashed
  const turned = await M.pictureFile(L, FORMAT, jpegHeader(4608, 3456), encoder(true));
  assert.deepEqual(asked.pop(), [1920, 2560, 0.85]);
  assert.deepEqual(L.imageSize(turned.bytes), [1920, 2560]);
  // within the side: encoded all the same, never made bigger
  await M.pictureFile(L, FORMAT, pngHeader(800, 600), encode);
  assert.deepEqual(asked.pop(), [800, 600, 0.85]);
  for (const bad of [new Uint8Array([1, 2, 3]), enc('<svg/>'), new Uint8Array([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 0, 1, 0])]) {
    await assert.rejects(M.pictureFile(L, FORMAT, bad, encode), (e) => e.code === 'image');
  }
  // too big to decode at all: refused before the browser tries
  await assert.rejects(M.pictureFile(L, FORMAT, pngHeader(60000, 60000), encode), (e) => e.code === 'limit');
  assert.equal(asked.length, 0);
});

test('save refuses what the app would: the name, files missing, images past their sides, the zip\'s limits', () => {
  const ok = M.fromBuiltin(E, 'nord');
  assert.deepEqual(M.saveProblems(E, L, FORMAT, ok), []);
  for (const name of ['a/b', '.hidden', 'x#dark', 'y#light', '']) {
    assert.equal(M.saveProblems(E, L, FORMAT, M.doc(M.withName(ok.xml, name))).length, 1, name);
  }
  const pic = withPicture(ok, pngHeader(100, 100), 'png');
  assert.deepEqual(M.saveProblems(E, L, FORMAT, pic), []);
  assert.match(M.saveProblems(E, L, FORMAT, M.doc(pic.xml)).map((p) => p.message).join(), /缺少文件 picture.png/);
  assert.match(M.saveProblems(E, L, FORMAT, withPicture(ok, pngHeader(5000, 10), 'png'))[0].message, /太大/);
  assert.match(M.saveProblems(E, L, FORMAT, withPicture(ok, new Uint8Array([1]), 'png'))[0].message, /不是 App 认得的图片/);
  // the app's ThemePackCheck.hidesRequired: blank keys refused, a key faced with its own image let be
  const hide = (styles) => M.saveProblems(E, L, FORMAT, M.doc(ok.xml.replace('</theme>', styles + '\n</theme>'))).length;
  assert.equal(hide('<style select="key.enter" hidden="true"/>'), 1);
  assert.equal(hide('<style select="*/backspace label, */backspace icon" hidden="true"/>'), 1);
  assert.equal(hide('<style select="key.letter" hidden="true"/>'), 0);
  assert.equal(hide('<style select="*/enter" fill="image(nope.png, 0 0 0 0, 3x)"/><style select="*/enter label, */enter icon" hidden="true"/>'), 1, 'only the file missing');
  assert.equal(hide('<style select="*/enter:pressed" fill="#ffffffff"/><style select="*/enter label, */enter icon" hidden="true"/>'), 1);
  // the image must be the face that wins (the app's BlankKeys, through the engine)
  const img = '<style select="*/enter" fill="image(e.png, 0 0 0 0, 3x)"/>';
  const hid = '<style select="*/enter label, */enter icon" hidden="true"/>';
  const withE = (styles) => M.saveProblems(E, L, FORMAT, M.doc(ok.xml.replace('</theme>', styles + '\n</theme>'), { 'e.png': pngHeader(10, 10) })).length;
  assert.equal(withE(img + hid), 0);
  assert.equal(withE(img + '<style select="*/enter" fill="#ffffffff"/>' + hid), 1);
  assert.equal(withE(img + '<style select="qwerty/enter" fill="#ffffffff"/>' + hid), 1);
  assert.equal(withE(img + '<style select="@dark */enter" fill="#ffffffff"/>' + hid), 1);
  assert.equal(withE('<style select="*/enter:pressed label, */enter:pressed icon" hidden="true"/>'), 1, 'hiding while pressed: the app refuses it too');
  // two scenes, a board's every key in a scene, a class the key has, Shift locked
  assert.equal(withE(img + '<style select="@dark @landscape */enter" fill="#ffffffff"/>' + hid), 1);
  assert.equal(withE(img + '<style select="@dark qwerty/*" fill="#ffffffff"/>' + hid), 1);
  assert.equal(withE(img + '<style select="*/enter.enter" fill="#ffffffff"/>' + hid), 1);
  const shift = '<style select="*/shift" fill="image(e.png, 0 0 0 0, 3x)"/><style select="*/shift label, */shift icon" hidden="true"/>';
  assert.equal(withE(shift), 0);
  assert.equal(withE(shift + '<style select="*/shift:locked" fill="#ffffffff"/>'), 1);
  assert.equal(withE(shift + '<style select="*/shift:locked:on" fill="#ffffffff"/>'), 1, 'two states at once');
  const padding = [1, 2, 3, 4, 5, 6].map((i) => '<style select="@im=x' + i + ' keyboard" fill="#ff000000"/>').join('');
  assert.equal(withE(img + padding + '<style select="@dark @landscape @compact @flat */enter" fill="#ffffffff"/>' + hid), 1, 'four scenes, the keyboard\'s styles naming more');
  assert.equal(withE(img + '<style select="@floating */enter" fill="#ffffffff"/><style select="@split */enter label, @split */enter icon" hidden="true"/>'), 0, 'scenes never at once');
  // a pack's own board, its Return named go: asked by its role
  const own = { 'qwerty.xml': E.layoutSource("qwerty").replace('id="enter"', 'id="go"') };
  const ownDoc = (styles) => M.doc(ok.xml.replace('</theme>', styles + '\n</theme>'), { 'e.png': pngHeader(10, 10) }, own);
  assert.equal(M.saveProblems(E, L, FORMAT, ownDoc(img + hid)).length, 0);
  assert.match(M.saveProblems(E, L, FORMAT, ownDoc(img + '<style select="qwerty/go" fill="#ffffffff"/>' + hid))[0].message, /qwerty\/go/);
  // the name as the app reads it: single quotes are a name
  assert.deepEqual(M.saveProblems(E, L, FORMAT, M.doc(ok.xml.replace(/name="nord-mine"/, "name='nord-mine'"))), []);
  const files = Object.fromEntries(Array.from({ length: FORMAT.limits.entries }, (_, i) => ['f' + i + '.bin', new Uint8Array(1)]));
  assert.match(M.saveProblems(E, L, FORMAT, M.doc(ok.xml, files))[0].message, /限制/);
});

test('only images the theme names are decoded, each within its role\'s sides, all within a budget', () => {
  const xml = '<theme name="t"><picture src="src.png"><cut orientation="portrait" file="cut.png"/></picture>' +
    '<style select="key" fill="image(k.png, 0 0 0 0, 3x)"/><style select="*/space" fill="image(big-key.png, 0 0 0 0, 3x)"/></theme>';
  const files = { 'src.png': pngHeader(6000, 4000), 'cut.png': pngHeader(2000, 1000), 'k.png': pngHeader(100, 100), 'big-key.png': pngHeader(2000, 2000), 'loose.png': pngHeader(10, 10), 'c.jpg': new Uint8Array([1]) };
  assert.deepEqual(M.decodableImages(L, FORMAT, M.doc(xml, files)), ['src.png', 'cut.png', 'k.png'], 'a key image past keyImageSide, an unnamed file: not');
  assert.deepEqual(M.decodableImages(L, FORMAT, M.doc(xml, { ...files, 'cut.png': pngHeader(6000, 6000) })), ['src.png', 'k.png'], 'a cut past pictureSide: not');
  // many big images: decoded only while the budget lasts
  const many = Array.from({ length: 10 }, (_, i) => 'p' + i + '.png');
  const all = '<theme name="t">' + many.map((n) => '<style select="qwerty/' + n[1] + '" fill="image(' + n + ', 0 0 0 0, 3x)"/>').join('') + '</theme>';
  const got = M.decodableImages(L, { ...FORMAT, limits: { ...FORMAT.limits, keyImageSide: 4096 } }, M.doc(all, Object.fromEntries(many.map((n) => [n, pngHeader(4000, 4000)]))));
  assert.equal(got.length, Math.floor(M.DECODE_BUDGET / 16e6));
});

test('a pack of odd entries opens as the app unpacks it: the last theme.xml, paths resolved, the rest dropped', async () => {
  const xml = M.fromBuiltin(E, 'pixel').xml;
  const z = L.writeZip([['theme.xml', enc('<theme name="first"/>')], ['a/../b.png', new Uint8Array([1])], ['__proto__', new Uint8Array([2])], ['notes/x.txt', enc('x')], ['layouts/k.txt', enc('x')], ['x/../theme.xml', enc(xml)]]);
  const { doc, notes } = await M.open(E, L, FORMAT, z);
  assert.equal(doc.xml, xml);
  assert.deepEqual(Object.keys(doc.files).sort(), ['__proto__', 'b.png']);
  assert.deepEqual(doc.files.__proto__, new Uint8Array([2]));
  assert.deepEqual(notes, [{ kind: 'dropped', names: ['notes/x.txt', 'layouts/k.txt'] }]);
  await assert.rejects(M.open(E, L, FORMAT, L.writeZip([['theme.xml', enc(xml)], ['../up.png', new Uint8Array(1)]])), (e) => e.code === 'not-theme');
  // a bare file must be XML, no bigger than a file the app takes
  await assert.rejects(M.open(E, L, FORMAT, new Uint8Array([0x25, 0x50, 0x44, 0x46])), (e) => e.code === 'not-theme');
  await assert.rejects(M.open(E, L, FORMAT, new Uint8Array(FORMAT.limits.fileBytes + 1)), (e) => e.code === 'limit');
  assert.equal((await M.open(E, L, FORMAT, enc('\uFEFF' + xml))).doc.xml, xml);
});

test('a converted theme keeps its files byte for byte', async () => {
  const opened = await L.openZip(FORMAT, bytes('from-web.theme'));
  const { doc } = await M.open(E, L, FORMAT, bytes('from-web.theme'));
  const sources = Object.values(opened.files).map((b) => Buffer.from(b).toString('hex')).sort();
  const now = Object.values(doc.files).map((b) => Buffer.from(b).toString('hex')).sort();
  assert.ok(now.length > 0);
  for (const f of now) assert.ok(sources.includes(f));
});

test('the engine throwing is a problem said, not a throw', () => {
  const bad = { readTheme: () => { throw new Error('boom'); } };
  const r = M.check(bad, M.doc('<x/>'), 'qwerty', M.envJson(M.ENV), null);
  assert.equal(r.scene, null);
  assert.match(r.problems[0].message, /boom/);
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

// the app's side (ThemePackImportTest) imports testdata/from-maker.theme: a document of a built-in theme as
// the page saves it; UPDATE=1 writes it again
test('the page saves from-maker.theme, byte for byte', () => {
  const made = M.save(L, M.fromBuiltin(E, 'nord')).bytes;
  if (process.env.UPDATE) writeFileSync(join(testdata, 'from-maker.theme'), made);
  assert.deepEqual(new Uint8Array(made), bytes('from-maker.theme'));
});

// and from-maker-picture.theme (the panels' save: a real 4×4 PNG cut for each orientation, keys see-through, a gradient, an icon) and from-maker-30.theme (the app's own 3.0
// export converted: its Return an image, the label hidden, as 3.0 drew it)
test('a 3.0 theme the app takes converts and saves to a pack the app takes', async () => {
  for (const f of ['from-web.theme', 'from-app.theme', 'from-app-2.1.theme']) {
    const { doc } = await M.open(E, L, FORMAT, bytes(f));
    assert.deepEqual(M.saveProblems(E, L, FORMAT, doc), [], f);
  }
  const made = M.save(L, (await M.open(E, L, FORMAT, bytes('from-app.theme'))).doc).bytes;
  if (process.env.UPDATE) writeFileSync(join(testdata, 'from-maker-30.theme'), made);
  assert.deepEqual(new Uint8Array(made), bytes('from-maker-30.theme'));
});

test('the page saves from-maker-picture.theme, byte for byte', () => {
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAEElEQVR4nGPQCFgARwzEcQC/wxGBfpfKIQAAAABJRU5ErkJggg==', 'base64');
  // made as the panels make one: a picture with its two cuts, the keys see-through, a gradient, an icon of its own
  const px = new Uint8Array(png);
  let d = P.background.ops.setPicture(M.fromBuiltin(E, 'nord'), { type: 'png', src: px, cuts: { portrait: { bytes: px, type: 'png', rect: [0, 0, 4, 2] }, landscape: { bytes: px, type: 'png', rect: [0, 1, 4, 2] } } });
  d = P.background.ops.through(d, true);
  d = P.keys.ops.fill(d, 'key.letter', { kind: 'linear', angle: 90, stops: [['#ff0000', 0], ['#0000ff', 100]] });
  d = P.text.ops.setIcon(d, 'backspace', enc('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M2 12l6-6h14v12H8z"/></svg>'));
  assert.deepEqual(M.saveProblems(E, L, FORMAT, d), []);
  const made = M.save(L, d).bytes;
  if (process.env.UPDATE) writeFileSync(join(testdata, 'from-maker-picture.theme'), made);
  assert.deepEqual(new Uint8Array(made), bytes('from-maker-picture.theme'));
});

test('a 3.0 theme the app exports with every override it allows opens, and stays quick to edit (t10-review M2)', async () => {
  // from-app.theme with every key of the app's boards overridden (up to format.limits.overrides), each a picture of its own, pressed and not: as 4.0
  // past ThemeReader's 1000 styles, within what the app imports (LookTheme.MAX_STYLES), which the engine reads too
  const opened = await L.openZip(FORMAT, bytes('from-app.theme'));
  const t = opened.theme;
  const ids = [];
  const material = E.readTheme(E.themeSource('material'), null, null).theme;
  for (const board of E.layouts()) {
    const s = E.buildScene(board, material, { width: 1080, height: 700, density: 2.75 }, E.fixedMeasurer());
    if (s.ok) for (const k of s.scene.json().keys) ids.push(board + '/' + k.id);
  }
  const names = [...new Set(ids)].slice(0, FORMAT.limits.overrides);
  assert.ok(names.length > 250, names.length + " keys"); // every key of the app's boards (fewer than the limit)
  t.keys.overrides = {};
  names.forEach((n, i) => {
    t.keys.overrides[n] = {
      fill: { type: 'picture', blurDp: Math.floor(i / 100), brightness: i % 100 },
      pressed: { type: 'color', argb: (0x80000000 | (i * 997)) | 0 },
      label: i % 2 ? 'show' : 'hide',
    };
  });
  const json = L.writeTheme(FORMAT, t);
  const zip = L.writeZip([[t.name + '.json', new TextEncoder().encode(json)], ...Object.entries(opened.files)]);
  let at = performance.now();
  const { doc } = await M.open(E, L, FORMAT, zip);
  const openMs = performance.now() - at;
  const styles = (doc.xml.match(/<style /g) || []).length;
  assert.ok(styles > 1000, styles + " styles");
  at = performance.now();
  const r = M.check(E, doc, 'qwerty', M.envJson(M.ENV), null);
  const checkMs = performance.now() - at;
  assert.deepEqual(r.problems.filter((p) => p.level === 'error'), []);
  at = performance.now();
  const edited = M.doc(F.setProp(doc.xml, 'key', 'radius', '9dp'), doc.files, doc.layouts);
  assert.deepEqual(M.check(E, edited, 'qwerty', M.envJson(M.ENV), null).problems.filter((p) => p.level === 'error'), []);
  const editMs = performance.now() - at;
  console.log('# ' + names.length + ' overrides, ' + styles + ' styles: open ' + openMs.toFixed(0) + ' ms, check ' + checkMs.toFixed(0) + ' ms, edit and check ' + editMs.toFixed(0) + ' ms');
  assert.deepEqual(M.saveProblems(E, L, FORMAT, doc), []);
});

// 060-render-p3 M4: the examples the page starts from, as assemble.mjs puts them in
const examples = JSON.parse(blockOf(readFileSync(join(here, '..', 'index.html'), 'utf8'), 'youmo-examples'));

test('each example opens, passes the check before a save with no error, and saves to a pack that opens the same', async () => {
  assert.deepEqual(examples.map((e) => e.id), ['gradient', 'picture', 'k17-motion']);
  for (const ex of examples) {
    const d = M.exampleDoc(ex);
    const { errors, warnings } = M.exportCheck(E, L, FORMAT, d, E.layouts(), M.envJson(M.ENV));
    assert.deepEqual(errors, [], ex.id);
    assert.deepEqual(warnings, [], ex.id);
    const again = (await M.open(E, L, FORMAT, M.save(L, d).bytes)).doc;
    assert.ok(M.same(d, again), ex.id);
  }
  const pic = M.exampleDoc(examples[1]);
  assert.deepEqual(Object.keys(pic.files).sort(), ['picture-landscape.jpg', 'picture-portrait.jpg', 'picture.jpg']);
  assert.ok(/<style select="keyboard" fill="linear\(/.test(examples[0].xml));
  assert.ok(/transition="160ms ease-out"/.test(examples[2].xml) && /press-scale="92%"/.test(examples[2].xml));
});

test("a pack's own layout of a board is the one the preview draws; one that does not read is a warning, the app's drawn", () => {
  const d = M.exampleDoc(examples[2]);
  const keys = (doc) => M.check(E, doc, 'k18', M.envJson(M.ENV), null).scene.json().keys.filter((k) => k.kind === 'letter').map((k) => k.id);
  assert.equal(keys(d).length, keys(M.doc(d.xml)).length - 1, 'h and jk one key');
  const bad = M.doc(d.xml, d.files, { 'k18.xml': '<keyboard' });
  const r = M.check(E, bad, 'k18', M.envJson(M.ENV), null);
  assert.ok(r.problems.some((p) => p.level === 'warning' && p.message.startsWith('layouts/k18.xml')), JSON.stringify(r.problems));
  assert.deepEqual(keys(bad), keys(M.doc(d.xml)));
});

test('the check before a save: an error of any board or version stops it, the app\'s refusals with them, each once', () => {
  const d = M.fromBuiltin(E, 'material');
  assert.deepEqual(M.exportCheck(E, L, FORMAT, d, E.layouts(), M.envJson(M.ENV)), { errors: [], warnings: [] });
  // a file named and not brought: the app refuses it
  const missing = M.doc(F.setProp(d.xml, 'key', 'fill', 'image(nope.png, 0dp)'));
  assert.ok(M.exportCheck(E, L, FORMAT, missing, E.layouts(), M.envJson(M.ENV)).errors.some((p) => /nope\.png/.test(p.message)));
  // a property of a later app: a warning, once though every board and version has it
  const later = M.doc(F.setProp(d.xml, 'key', 'sticker', 'none'));
  const w = M.exportCheck(E, L, FORMAT, later, E.layouts(), M.envJson(M.ENV));
  assert.deepEqual(w.errors, []);
  assert.equal(w.warnings.filter((p) => /sticker/.test(p.message)).length, 1, JSON.stringify(w.warnings));
});

test("a pack's layouts the app refuses on import stop a save, by the app's own limits; one it would not use is not previewed", () => {
  const d = M.exampleDoc(examples[2]);
  const k18 = d.layouts['k18.xml'];
  const many = Object.fromEntries(Array.from({ length: FORMAT.limits.layouts + 1 }, (_, i) => ['k' + i + '.xml', k18]));
  assert.ok(M.exportCheck(E, L, FORMAT, M.doc(d.xml, d.files, many), E.layouts(), M.envJson(M.ENV)).errors.some((p) => /最多收/.test(p.message)));
  const big = M.doc(d.xml, d.files, { 'k18.xml': k18 + '<!--' + 'x'.repeat(FORMAT.limits.layoutBytes) + '-->' });
  assert.ok(M.exportCheck(E, L, FORMAT, big, E.layouts(), M.envJson(M.ENV)).errors.some((p) => /超过 \d+ 字节/.test(p.message)));
  // the app's qwerty under another board's name: the app keeps its own k14, so does the preview, with a warning
  const keys = (doc, board) => M.check(E, doc, board, M.envJson(M.ENV), null).scene.json().keys.map((k) => k.id).join();
  const wrong = M.doc(d.xml, d.files, { 'k14.xml': E.layoutSource('qwerty') });
  const r = M.check(E, wrong, 'k14', M.envJson(M.ENV), null);
  assert.ok(r.problems.some((p) => p.level === 'warning' && /App 不会用它（its id is qwerty）/.test(p.message)), JSON.stringify(r.problems));
  assert.equal(keys(wrong, 'k14'), keys(M.doc(d.xml), 'k14'));
  // and one with no backspace
  const noBack = M.doc(d.xml, d.files, { 'qwerty.xml': E.layoutSource('qwerty').replace(/<key\b[^>]*role="backspace"[^>]*>/, '') });
  assert.ok(M.check(E, noBack, 'qwerty', M.envJson(M.ENV), null).problems.some((p) => /it has no key \[backspace\]/.test(p.message)));
});
