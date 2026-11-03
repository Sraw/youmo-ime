// The theme maker's own code against what the app makes (testdata/, written by the app's tests):
// the drawing rules case by case (rules.json), the theme file both ways (theme.json, the zips), and
// the page's export byte for byte (from-web.zip, which the app's ThemeMakerFormatTest imports).
// Run: node --test web/theme-maker/test/   (UPDATE=1 writes from-web.zip again)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';
import { createHash } from 'node:crypto';
import { deflateRawSync } from 'node:zlib';

const here = dirname(fileURLToPath(import.meta.url));
const data = join(here, 'testdata');
const html = readFileSync(join(here, '..', 'index.html'), 'utf8');

function block(id) {
  const m = new RegExp('<script(?: type="application/json")? id="' + id + '">\\n?([\\s\\S]*?)\\n?</script>').exec(html);
  assert.ok(m, 'no block ' + id + ' in index.html');
  return m[1];
}

// the page's library as the page runs it, in this realm (so its objects compare as ours)
vm.runInThisContext(block('youmo-lib'), { filename: 'youmo-lib' });
const L = globalThis.YoumoLib;
const format = JSON.parse(block('youmo-format'));
const presets = JSON.parse(block('youmo-presets'));
const layout = JSON.parse(block('youmo-layout'));
const rules = JSON.parse(readFileSync(join(data, 'rules.json'), 'utf8'));
const themeText = readFileSync(join(data, 'theme.json'), 'utf8');
const bytes = (name) => new Uint8Array(readFileSync(join(data, name)));
const plain = (x) => JSON.parse(JSON.stringify(x));
const fr = Math.fround;
const sameArea = (got, want, what) => {
  if (want === null) return assert.equal(got, null, what);
  assert.deepEqual(got, want.map(fr), what);
};

test('a key face is laid as the app lays it', () => {
  assert.equal(rules.geometry.length, 560);
  for (const c of rules.geometry) {
    const keys = rules.keys[c.keys];
    const g = L.keyFace(c.width, c.height, c.kind, keys, c.landscape, c.density, c.margin);
    const what = JSON.stringify({ k: c.keys, w: c.width, h: c.height, kind: c.kind, land: c.landscape, d: c.density, m: c.margin });
    sameArea(g.face, c.face, 'face ' + what);
    sameArea(g.shadow, c.shadow, 'shadow ' + what);
    sameArea(g.pressed, c.pressed, 'pressed ' + what);
    assert.equal(g.border, fr(c.border), 'border ' + what);
    assert.equal(g.radius, fr(c.radius), 'radius ' + what);
    assert.equal(g.oval, c.oval, 'oval ' + what);
  }
});

test("a key's own say over its face (the layout's b) is the app's", () => {
  assert.equal(rules.borders.length, 64);
  for (const c of rules.borders) {
    const g = L.keyFace(c.width, c.height, c.kind, rules.keys[c.keys], false, 2.75, true, c.b);
    const what = JSON.stringify({ k: c.keys, w: c.width, h: c.height, kind: c.kind, b: c.b });
    sameArea(g.face, c.face, 'face ' + what);
    sameArea(g.shadow, c.shadow, 'shadow ' + what);
    sameArea(g.pressed, c.pressed, 'pressed ' + what);
    assert.equal(g.border, fr(c.border), 'border ' + what);
    assert.equal(g.radius, fr(c.radius), 'radius ' + what);
    assert.equal(g.oval, c.oval, 'oval ' + what);
  }
});

test('each part is drawn in the palette field the app draws it in', () => {
  const c = rules.colors;
  for (const blocks of [true, false]) {
    const want = c[blocks ? 'blocks' : 'flat'];
    assert.equal(L.boardField({ blocks }), want.board);
    assert.equal(L.panelField({ blocks }), want.panel);
    for (const kind of ['letter', 'function', 'space', 'enter']) assert.equal(L.faceField(kind, blocks), want.faces[kind], kind);
  }
  for (const [type, want] of Object.entries(c.bar)) assert.equal(L.barField({ type }), want, type);
  assert.deepEqual([0, 1, 30, 50, 99, 100].map(L.darkenAlpha), c.dim);
});

test('a colour laid over the picture is turned down to 30 % and given back after', () => {
  const { theme } = L.readTheme(format, themeText);
  const P = theme.palette;
  const K = theme.keys;
  const alpha = (f) => P[f] >>> 24;
  const kindOf = (n) => ({ 'qwerty/q': 'letter', '*/enter': 'enter' })[n];
  // the fixture as the page exports it: its letters show the picture, their colour at 30 %
  assert.equal(K.letter.fill.type, 'picture');
  assert.equal(alpha('keyBackgroundColor'), L.SEE_THROUGH_ALPHA);
  assert.equal(L.SEE_THROUGH_ALPHA, Math.round(0.3 * 255));
  K.letter.fill = { type: 'color' };
  K.space.fill = { type: 'color' };
  P.keyBackgroundColor = 0xff464646 | 0;
  P.altKeyBackgroundColor = 0x20123456;
  P.accentKeyBackgroundColor = 0xff0000ff | 0;
  let kept = L.seeThrough(theme, kindOf, {});
  assert.deepEqual(kept, {}, 'nothing over the picture: nothing turned down');
  // the letters through: their colour turned down, its alpha kept
  K.letter.fill = { type: 'picture', blurDp: 0, brightness: 100 };
  kept = L.seeThrough(theme, kindOf, kept);
  assert.deepEqual(kept, { keyBackgroundColor: 0xff });
  assert.equal(P.keyBackgroundColor, 0x4d464646);
  // turned up again by hand while through: left as set
  P.keyBackgroundColor = 0x00464646;
  kept = L.seeThrough(theme, kindOf, kept);
  assert.equal(P.keyBackgroundColor, 0x00464646);
  // a colour already as clear or clearer is left, and has nothing to give back
  K.function.fill = { type: 'picture', blurDp: 0, brightness: 100 };
  kept = L.seeThrough(theme, kindOf, kept);
  assert.equal(P.altKeyBackgroundColor, 0x20123456);
  assert.equal(kept.altKeyBackgroundColor, null);
  // a single key through: its kind's colour, as the app lays it (blocks: the space shares the letters')
  K.overrides['*/enter'] = { fill: { type: 'picture', blurDp: 0, brightness: 100 }, pressed: null, label: 'show' };
  kept = L.seeThrough(theme, kindOf, kept);
  assert.equal(P.accentKeyBackgroundColor, 0x4d0000ff);
  // back from the picture: the alpha given back, and only once nothing else lays that colour over it
  K.space.fill = { type: 'picture', blurDp: 0, brightness: 100 };
  K.letter.fill = { type: 'color' };
  kept = L.seeThrough(theme, kindOf, kept);
  assert.equal(P.keyBackgroundColor, 0x00464646, 'the space still lays it over the picture');
  K.space.fill = { type: 'color' };
  K.function.fill = { type: 'color' };
  delete K.overrides['*/enter'];
  kept = L.seeThrough(theme, kindOf, kept);
  assert.deepEqual(kept, {});
  assert.equal(P.keyBackgroundColor, 0xff464646 | 0);
  assert.equal(P.altKeyBackgroundColor, 0x20123456);
  assert.equal(P.accentKeyBackgroundColor, 0xff0000ff | 0);
  // a role on all boards is of each kind its keys are: every one of their colours turned down
  K.blocks = true;
  for (const k of ['letter', 'function', 'space', 'enter']) K[k].fill = { type: 'color' };
  K.overrides = { '*/space': { fill: { type: 'picture', blurDp: 0, brightness: 100 }, pressed: null, label: 'show' } };
  P.keyBackgroundColor = 0xff464646 | 0;
  P.altKeyBackgroundColor = 0xff101010 | 0;
  kept = L.seeThrough(theme, () => ['space', 'function'], {});
  assert.equal(alpha('keyBackgroundColor'), L.SEE_THROUGH_ALPHA);
  assert.equal(alpha('altKeyBackgroundColor'), L.SEE_THROUGH_ALPHA);
  K.overrides = {};
  kept = L.seeThrough(theme, () => ['space', 'function'], kept);
  assert.deepEqual(kept, {});
  assert.equal(P.altKeyBackgroundColor, 0xff101010 | 0);
  // the measured boards: the space a function key on the number board, numbers and symbols letters somewhere
  const kindsOf = (role) => [...new Set(layout.views.flatMap((v) => v.items).filter((e) => e.t === 'key' && e.n && e.n.endsWith('/' + role)).map((e) => e.k))].sort();
  assert.deepEqual(kindsOf('space'), ['function', 'space']);
  assert.ok(kindsOf('symbols').length > 1, kindsOf('symbols').join());
  // flat, the space lays its own colour: switching moves which colour is turned down
  P.keyBackgroundColor = 0xff464646 | 0;
  kept = {};
  K.space.fill = { type: 'picture', blurDp: 0, brightness: 100 };
  P.spaceBarColor = 0xff222222 | 0;
  kept = L.seeThrough(theme, kindOf, kept);
  assert.equal(alpha('keyBackgroundColor'), L.SEE_THROUGH_ALPHA);
  K.blocks = false;
  kept = L.seeThrough(theme, kindOf, kept);
  assert.equal(P.keyBackgroundColor, 0xff464646 | 0);
  assert.equal(alpha('spaceBarColor'), L.SEE_THROUGH_ALPHA);
  assert.deepEqual(kept, { spaceBarColor: 0xff });
});

test('a nine-slice image is cut as the app cuts it', () => {
  for (const c of rules.nineSlice) {
    const pieces = L.nineSlice(c.srcWidth, c.srcHeight, c.insets, c.pxPerDp, c.target.map(fr), c.density);
    assert.deepEqual(pieces, c.pieces.map((p) => ({ src: p.src.map(fr), dst: p.dst.map(fr) })), JSON.stringify(c.insets));
  }
});

test('the picture is centre-cropped and a key shows what is under it', () => {
  for (const c of rules.pictures) {
    const p = L.centerCrop(c.picWidth, c.picHeight, c.boardWidth, c.boardHeight);
    assert.deepEqual([p.scale, p.dx, p.dy], [fr(c.scale), fr(c.dx), fr(c.dy)]);
    sameArea(L.pictureUnder(p, c.key.map(fr)), c.under);
  }
});

test('the blur is the app\'s to the pixel', () => {
  for (const c of rules.blur) {
    if ('radius' in c) assert.deepEqual([...L.blur(c.pixels, c.width, c.height, c.radius)], c.blurred, 'blur ' + c.width + 'x' + c.height);
    else assert.deepEqual([...L.blurShrink(c.pixels, c.width, c.height, c.shrink)], c.shrunk, 'shrink ' + c.width + 'x' + c.height);
  }
});

test('a key\'s style is looked up in the app\'s order', () => {
  const s = rules.styles;
  const roles = layout.names.roles;
  for (const c of s.cases) {
    const got = L.resolveStyle(s.keys, c.board, c.key, c.kind, roles);
    assert.deepEqual(plain(got), c.style, c.board + '/' + c.key);
  }
  for (const c of s.imagesOn) {
    assert.deepEqual(L.imagesOn(s.keys, c.board, c.names, roles).sort(), c.files, c.board);
  }
});

test('a board\'s key images are budgeted as the app decodes them', () => {
  assert.equal(rules.budget.limit, format.limits.boardImageBytes);
  for (const c of rules.budget.cases) {
    const images = c.images.map(([w, h], i) => ({ file: 'k' + i, width: w, height: h }));
    assert.equal(L.budgetBytes(images), c.bytes);
    const sample = L.budgetSample(images, rules.budget.limit);
    assert.equal(sample, c.sampleSize);
    assert.equal(L.budgetBytes(images, sample), c.sampledBytes);
  }
});

test('a 2.1 theme is brought to 3.0 as the app brings it', () => {
  const to = L.from21(format, rules.migration.from);
  assert.equal(L.writeTheme(format, to), JSON.stringify(rules.migration.to));
  // what an older 2.1 left out: the app's defaults (brightness 70, no turn, a missing edge -1)
  const partial = L.from21(format, rules.migration.partialFrom);
  assert.equal(L.writeTheme(format, partial), JSON.stringify(rules.migration.partialTo));
  assert.equal(partial.picture.brightness, 70);
  assert.equal(partial.picture.portrait.rotation, 0);
  assert.deepEqual(partial.picture.portrait.cropRect, { bottom: -1, left: 10, right: -1, top: 20 });
  // a number past an Int is refused, as jsonPrimitive.int refuses it (not cut to 32 bits)
  for (const v of [2147483648, -2147483649, 4294967295]) {
    assert.throws(() => L.from21(format, { ...rules.migration.from, keyTextColor: v }), (e) => e.code === 'bad', String(v));
  }
  assert.equal(L.from21(format, { ...rules.migration.from, keyTextColor: -2147483648 }).palette.keyTextColor, -2147483648);
  const read = L.readTheme(format, JSON.stringify(rules.migration.from));
  assert.equal(read.migrated, true);
  assert.deepEqual(read.theme, to);
});

test('the app\'s 3.0 file reads, and is written again as the app writes it', () => {
  const { theme, migrated } = L.readTheme(format, themeText);
  assert.equal(migrated, false);
  assert.equal(L.writeTheme(format, theme), JSON.stringify(JSON.parse(themeText)));
  assert.equal(JSON.parse(themeText).version, format.version);
  assert.deepEqual(L.lookFiles(theme), [
    'full-src.png', 'full-portrait.png', 'full-landscape.png', 'full-space.png', 'full-enter-down.png',
    'full-enter.png', 'full-font.ttf', 'full-candidates.ttf',
  ]);
});

test('a theme file the app would refuse is refused, each for its reason', () => {
  const good = JSON.parse(themeText);
  const refused = (mutate, code, detail) => {
    const t = structuredClone(good);
    mutate(t);
    assert.throws(() => L.readTheme(format, JSON.stringify(t)), (e) => e.code === code && (!detail || e.detail.includes(detail)), code + ' ' + detail);
  };
  refused((t) => (t.version = '3.1'), 'newer');
  refused((t) => (t.version = '2.0'), 'older');
  refused((t) => delete t.version, 'older');
  refused((t) => (t.extra = 1), 'bad', 'extra');
  refused((t) => delete t.bar, 'bad', 'bar');
  refused((t) => (t.keys.letter.fill.extra = 1), 'bad', 'extra');
  refused((t) => delete t.picture.landscape, 'bad', 'landscape');
  refused((t) => (t.keys.letter.fill = { type: 'glass' }), 'bad', 'glass');
  refused((t) => (t.keys.radiusDp = 25), 'bad', 'radiusDp');
  refused((t) => (t.keys.radiusDp = 2.5), 'bad', 'radiusDp');
  refused((t) => (t.picture.blurDp = 26), 'bad', 'blurDp');
  refused((t) => (t.picture.landscape.rotation = 45), 'bad', 'quarter');
  refused((t) => (t.text.keyWeight = 500), 'bad', 'weight');
  refused((t) => (t.keys.overrides['qwerty/'] = t.keys.overrides['qwerty/q']), 'bad', 'qwerty/');
  refused((t) => (t.keys.space.fill.insets = [1, 2, 3]), 'bad', 'insets');
  refused((t) => (t.keys.space.fill.pxPerDp = 9), 'bad', 'pxPerDp');
  const lim = format.limits;
  refused((t) => (t.keys.borderDp = lim.borderDp + 1), 'bad', 'borderDp');
  refused((t) => (t.keys.borderDp = -1), 'bad', 'borderDp');
  refused((t) => (t.keys.shadowDp = lim.shadowDp + 1), 'bad', 'shadowDp');
  for (const g of ['h', 'v', 'hLand', 'vLand']) refused((t) => (t.keys.gap[g] = lim.gapDp + 1), 'bad', 'gap.' + g);
  refused((t) => (t.text.keyScale = lim.keyScale[0] - 1), 'bad', 'keyScale');
  refused((t) => (t.text.keyScale = lim.keyScale[1] + 1), 'bad', 'keyScale');
  refused((t) => (t.picture.brightness = 101), 'bad', 'picture.brightness');
  refused((t) => (t.bar.brightness = -1), 'bad', 'bar.brightness');
  refused((t) => (t.keys.letter.fill.brightness = 101), 'bad', 'letter.brightness');
  refused((t) => (t.keys.letter.fill.blurDp = lim.blurDp + 1), 'bad', 'letter.blurDp');
  // each at its limit reads
  const atLimits = structuredClone(good);
  Object.assign(atLimits.keys, { borderDp: lim.borderDp, shadowDp: lim.shadowDp, radiusDp: lim.radiusDp, gap: { h: lim.gapDp, v: lim.gapDp, hLand: 0, vLand: 0 } });
  atLimits.text.keyScale = lim.keyScale[1];
  atLimits.picture.brightness = 100;
  assert.ok(L.readTheme(format, JSON.stringify(atLimits)));
  atLimits.text.keyScale = lim.keyScale[0];
  atLimits.picture.brightness = 0;
  assert.ok(L.readTheme(format, JSON.stringify(atLimits)));
  refused((t) => {
    t.picture = null;
  }, 'bad', 'picture');
  assert.throws(() => L.readTheme(format, '[1]'), (e) => e.code === 'not-theme');
  assert.throws(() => L.readTheme(format, '{'), (e) => e.code === 'not-theme');
  // a later build's key name is kept, as the app keeps it
  const t = structuredClone(good);
  t.keys.overrides['k99/r9c9'] = t.keys.overrides['qwerty/q'];
  assert.ok('k99/r9c9' in L.readTheme(format, JSON.stringify(t)).theme.keys.overrides);
});

test('key names are read as KeyNames reads them', () => {
  const names = layout.names;
  assert.deepEqual(L.parseKeyName('qwerty/q'), { board: 'qwerty', key: 'q' });
  assert.deepEqual(L.parseKeyName('*/backspace'), { board: '*', key: 'backspace' });
  for (const bad of ['qwerty/', '/q', 'q', 'Qwerty/q', 'a--b/q', 'qwerty/a b']) assert.throws(() => L.parseKeyName(bad), bad);
  assert.equal(L.isKnownKeyName(names, 'qwerty/q'), true);
  assert.equal(L.isKnownKeyName(names, 'qwerty/backspace'), true);
  assert.equal(L.isKnownKeyName(names, '*/backspace'), true);
  assert.equal(L.isKnownKeyName(names, '*/q'), false);
  assert.equal(L.isKnownKeyName(names, 'k20/r1c1'), false);
  assert.equal(L.isKnownKeyName(names, 'nine/side'), true);
});

test('what the app exports opens, its own and one before 3.0', async () => {
  const app = await L.openZip(format, bytes('from-app.zip'));
  assert.equal(app.migrated, false);
  const want = JSON.parse(themeText);
  delete want.version;
  want.name = 'from-app';
  want.text.keyFont = null;
  want.text.candidateFont = null;
  const rename = (o) => JSON.parse(JSON.stringify(o).replaceAll('"full-', '"from-app-'));
  assert.deepEqual(app.theme, rename(want));
  for (const [name, b] of Object.entries(app.files)) assert.equal(L.imageType(b), 'png', name);

  const old = await L.openZip(format, bytes('from-app-2.1.zip'));
  assert.equal(old.migrated, true);
  assert.equal(old.theme.picture.portrait.file, 'old-cropped.png');
  assert.deepEqual(Object.keys(old.files), ['old-src', 'old-cropped.png']);
  assert.equal(L.imageType(old.files['old-src']), 'png');
});

test('a zip that is not a theme is told apart', async () => {
  const notTheme = async (b, code) => assert.rejects(L.openZip(format, b), (e) => e.code === code);
  await notTheme(new TextEncoder().encode('hello'), 'not-theme');
  const enc = new TextEncoder();
  await notTheme(L.writeZip([['a.json', enc.encode(themeText)], ['b.json', enc.encode(themeText)]]), 'not-theme');
  await notTheme(L.writeZip([['x.txt', enc.encode('x')]]), 'not-theme');
  await notTheme(L.writeZip([['full.json', enc.encode(themeText)]]), 'missing');
  // in a folder, or Mac's attribute file: passed over, as the app does
  const one = await L.openZip(format, L.writeZip([['__MACOSX/._t.json', enc.encode('x')], ['._t.json', enc.encode('x')],
    ['t.json', enc.encode(JSON.stringify(rules.migration.from))], ['old-cropped.png', png(16, 24)], ['old-src', new Uint8Array(1)]]));
  assert.equal(one.theme.name, 'old');
});

// ---- zips made by hand: what a crafted one may say ----
/** A PNG's first bytes: the signature and an IHDR of [w] × [h] (all a header-reader sees). */
function png(w, h) {
  const b = new Uint8Array(33);
  b.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52]);
  new DataView(b.buffer).setUint32(16, w);
  new DataView(b.buffer).setUint32(20, h);
  b[24] = 8; b[25] = 6;
  return b;
}
/**
 * A zip of [entries] ({name, data, deflate, size: what it says it unpacks to, local: its local
 * header's offset}), its CRCs right for the data.
 */
function zipOf(entries) {
  const enc = new TextEncoder();
  const parts = [];
  const central = [];
  let off = 0;
  for (const e of entries) {
    const name = enc.encode(e.name);
    const packed = e.deflate ? new Uint8Array(deflateRawSync(e.data)) : e.data;
    const crc = L.crc32(e.data);
    const size = e.size ?? e.data.length;
    const lh = new DataView(new ArrayBuffer(30));
    lh.setUint32(0, 0x04034b50, true); lh.setUint16(4, 20, true); lh.setUint16(6, 0x0800, true); lh.setUint16(8, e.deflate ? 8 : 0, true);
    lh.setUint32(14, crc, true); lh.setUint32(18, packed.length, true); lh.setUint32(22, size, true); lh.setUint16(26, name.length, true);
    const cd = new DataView(new ArrayBuffer(46));
    cd.setUint32(0, 0x02014b50, true); cd.setUint16(4, 20, true); cd.setUint16(6, 20, true); cd.setUint16(8, 0x0800, true);
    cd.setUint16(10, e.deflate ? 8 : 0, true); cd.setUint32(16, crc, true); cd.setUint32(20, packed.length, true); cd.setUint32(24, size, true);
    cd.setUint16(28, name.length, true); cd.setUint32(42, e.local ?? off, true);
    parts.push(new Uint8Array(lh.buffer), name, packed);
    central.push(new Uint8Array(cd.buffer), name);
    off += 30 + name.length + packed.length;
  }
  const cdSize = central.reduce((a, p) => a + p.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true); end.setUint16(8, entries.length, true); end.setUint16(10, entries.length, true);
  end.setUint32(12, cdSize, true); end.setUint32(16, off, true);
  const all = [...parts, ...central, new Uint8Array(end.buffer)];
  const out = new Uint8Array(all.reduce((a, p) => a + p.length, 0));
  let at = 0;
  for (const p of all) { out.set(p, at); at += p.length; }
  return out;
}
/** from-app.zip's entries, unpacked: [{name, data}]. */
async function appEntries() {
  return Promise.all(L.readZip(bytes('from-app.zip')).map(async (e) => ({ name: e.name, data: await e.read() })));
}
const refusedZip = (zip, code, detail) => assert.rejects(L.openZip(format, zip), (e) => {
  assert.equal(e.code, code, e.message);
  if (detail) assert.ok(e.detail.includes(detail), e.detail + ' has not ' + detail);
  return true;
});

test('a zip is unpacked no further than the app would: a bomb, a lie, a count, a total, an entry outside', async () => {
  const lim = format.limits;
  const good = await appEntries();
  // 50 MB of zeros in 50 KB, said to be 10 bytes: refused at the first chunk past what it says
  const bomb = new Uint8Array(50 * 1024 * 1024);
  const before = process.memoryUsage().arrayBuffers;
  await refusedZip(zipOf([{ name: 'x.json', data: bomb, deflate: true, size: 10 }]), 'not-theme', 'more than');
  assert.ok(process.memoryUsage().arrayBuffers - before < 60 * 1024 * 1024, 'the bomb was not unpacked whole');
  // said past a file's limit, or the zip's: refused before anything is unpacked
  await refusedZip(zipOf([...good, { name: 'big.bin', data: new Uint8Array(1), size: lim.fileBytes + 1 }]), 'limit', 'fileBytes');
  const third = Math.floor(lim.totalBytes / 3);
  await refusedZip(zipOf([...good, ...[1, 2, 3].map((i) => ({ name: 'f' + i, data: new Uint8Array(1), size: third + 1 }))]), 'limit', 'totalBytes');
  // the entries counted as the app counts them, a folder's too: at the limit it opens, one more is refused
  const fill = (n) => Array.from({ length: n }, (_, i) => ({ name: 'pad/' + i, data: new Uint8Array(0) }));
  const atLimit = await L.openZip(format, zipOf([...good, ...fill(lim.entries - good.length)]));
  assert.equal(atLimit.theme.name, 'from-app');
  await refusedZip(zipOf([...good, ...fill(lim.entries - good.length + 1)]), 'limit', 'entries');
  // a size that is not what unpacks, a local header outside the zip, an entry out of the zip's top
  const json = good.find((e) => e.name.endsWith('.json'));
  await refusedZip(zipOf([{ ...json, size: json.data.length + 5 }]), 'not-theme', 'size');
  await refusedZip(zipOf([{ ...json, local: 1 << 30 }]), 'not-theme', 'local header');
  await refusedZip(zipOf([...good, { name: '../x.png', data: new Uint8Array(1) }]), 'not-theme', 'outside');
});

test('an image\'s size is read from its header, and one past the app\'s side is refused undecoded', async () => {
  assert.deepEqual(L.imageSize(png(30000, 20)), [30000, 20]);
  const jpg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 4, 0, 0, 0xff, 0xc2, 0, 11, 8, 0x01, 0x2c, 0x00, 0xc8, 3, 0, 0]);
  assert.deepEqual(L.imageSize(jpg), [200, 300]);
  const riff = (tag, body) => {
    const b = new Uint8Array(30 + body.length);
    b.set(new TextEncoder().encode('RIFF\0\0\0\0WEBP' + tag));
    b.set(body, 20);
    return b;
  };
  assert.deepEqual(L.imageSize(riff('VP8 ', [0, 0, 0, 0x9d, 0x01, 0x2a, 0x90, 0x01, 0x2c, 0x01])), [400, 300]);
  // VP8L: 14 bits each of width − 1 and height − 1 after its signature byte
  const w = 4096 - 1, h = 17 - 1;
  const bits = w | (h << 14);
  assert.deepEqual(L.imageSize(riff('VP8L', [0x2f, bits & 255, (bits >> 8) & 255, (bits >> 16) & 255, (bits >>> 24) & 255])), [4096, 17]);
  assert.deepEqual(L.imageSize(riff('VP8X', [0, 0, 0, 0, 0xff, 0x0f, 0, 0x01, 0x10, 0])), [4096, 4098]);
  assert.deepEqual(L.imageSize(new Uint8Array([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x10, 0x27, 0x05, 0])), [10000, 5]);
  assert.equal(L.imageSize(new Uint8Array([1, 2, 3])), null);
  // in a zip: the cut at the picture's side opens, one past it is refused; a key image the same at its own
  const lim = format.limits;
  const good = await appEntries();
  const swap = (name, data) => good.map((e) => (e.name === name ? { name, data } : e));
  assert.ok(await L.openZip(format, zipOf(swap('from-app-portrait.png', png(lim.pictureSide, 10)))));
  await refusedZip(zipOf(swap('from-app-portrait.png', png(lim.pictureSide + 1, 10))), 'limit', 'pictureSide');
  await refusedZip(zipOf(swap('from-app-landscape.png', png(10, lim.pictureSide + 1))), 'limit', 'pictureSide');
  assert.ok(await L.openZip(format, zipOf(swap('from-app-space.png', png(lim.keyImageSide, 4)))));
  await refusedZip(zipOf(swap('from-app-space.png', png(4, lim.keyImageSide + 1))), 'limit', 'keyImageSide');
  const heavy = new Uint8Array(lim.keyImageBytes + 1);
  heavy.set(png(4, 4));
  await refusedZip(zipOf(swap('from-app-space.png', heavy)), 'limit', 'keyImageBytes');
  const atBytes = new Uint8Array(lim.keyImageBytes);
  atBytes.set(png(4, 4));
  assert.ok(await L.openZip(format, zipOf(swap('from-app-space.png', atBytes))));
  await refusedZip(zipOf(swap('from-app-space.png', new Uint8Array([1, 2, 3]))), 'image', 'from-app-space.png');
});

test('a zip\'s names are taken as the app takes them', async () => {
  const good = await appEntries();
  const json = good.find((e) => e.name.endsWith('.json'));
  const others = good.filter((e) => e !== json);
  const theme = JSON.parse(new TextDecoder().decode(json.data));
  const withTheme = (t, files) => zipOf([...files, { name: json.name, data: new TextEncoder().encode(JSON.stringify(t)) }]);
  // two entries of a name: the later, as unpacking writes it over the first
  const space = others.find((e) => e.name === 'from-app-space.png');
  const later = png(9, 9);
  const twice = await L.openZip(format, zipOf([...others, { name: space.name, data: later }, json]));
  assert.deepEqual(twice.files['from-app-space.png'], later);
  // a path in the json is its last part, found at the top, and kept so
  const t = structuredClone(theme);
  t.keys.space.fill.file = 'some/dir/from-app-space.png';
  const pathed = await L.openZip(format, withTheme(t, others));
  assert.equal(pathed.theme.keys.space.fill.file, 'from-app-space.png');
  // a Mac's ._ at the top is a file like any other but for the json
  const dotted = structuredClone(theme);
  dotted.keys.space.fill.file = '._space.png';
  assert.ok((await L.openZip(format, withTheme(dotted, [...others, { name: '._space.png', data: png(4, 4) }]))).files['._space.png']);
  // what the app would not take as a file of its own; a name that is a path
  for (const bad of ['x.json', 'X.JSON', '.saving', 'dir/', '..']) {
    const b = structuredClone(theme);
    b.keys.space.fill.file = bad;
    await refusedZip(withTheme(b, others), 'missing', bad);
  }
  const named = structuredClone(theme);
  named.name = 'a/b';
  await refusedZip(withTheme(named, others), 'bad', 'name');
  // names an object has of its own: files like any other
  for (const odd of ['constructor', 'toString', '__proto__', 'hasOwnProperty']) {
    const o = structuredClone(theme);
    o.keys.space.fill.file = odd;
    const opened = await L.openZip(format, withTheme(o, [...others, { name: odd, data: png(5, 5) }]));
    assert.ok(Object.hasOwn(opened.files, odd), odd);
    assert.deepEqual(opened.files[odd], png(5, 5));
    const back = await L.openZip(format, L.exportZip(format, opened.theme, opened.files));
    assert.deepEqual(back.files[odd], png(5, 5), odd);
  }
  // and the export refuses what the app would: a path, a json, SAVING, a name past 255 bytes
  const { theme: full, files } = await L.openZip(format, bytes('from-app.zip'));
  for (const bad of ['a/b.png', 'b.json', '.saving', 'é'.repeat(128) + '.png']) {
    const m = L.mapFiles(full, (f) => (f === 'from-app-space.png' ? bad : f));
    const fs = Object.assign(Object.create(null), files, { [bad]: files['from-app-space.png'] });
    assert.throws(() => L.exportZip(format, m, fs), (e) => e.code === 'bad', bad);
  }
  assert.throws(() => L.exportZip(format, { ...full, name: 'n'.repeat(251) }, files), (e) => e.code === 'bad');
  assert.ok(L.exportZip(format, { ...full, name: 'n'.repeat(250) }, files));
  assert.throws(() => L.exportZip(format, full, Object.create(null)), (e) => e.code === 'missing');
});

test('what the app refuses as it imports is told before export, each limit at it and one past', () => {
  const lim = format.limits;
  const { theme } = L.readTheme(format, themeText);
  const names = L.lookFiles(theme);
  const sized = (n) => ({ length: n });
  const base = () => Object.fromEntries(names.map((n) => [n, sized(10)]));
  const what = (t, files, sizes) => L.limitBreaches(format, t, files, sizes || {}).map((b) => b.what);
  assert.deepEqual(what(theme, base()), []);
  // a file
  const big = base();
  big['full-font.ttf'] = sized(lim.fileBytes);
  assert.deepEqual(what(theme, big), []);
  big['full-font.ttf'] = sized(lim.fileBytes + 1);
  assert.deepEqual(what(theme, big), ['fileBytes']);
  // the total, the json counted as the app counts every entry
  const json = new TextEncoder().encode(L.writeTheme(format, theme)).length;
  const others = (names.length - 2) * 10;
  const total = base();
  const half = lim.totalBytes - json - others;
  total['full-font.ttf'] = sized(Math.floor(half / 2));
  total['full-candidates.ttf'] = sized(half - Math.floor(half / 2));
  assert.deepEqual(what(theme, total), []);
  total['full-candidates.ttf'] = sized(half - Math.floor(half / 2) + 1);
  assert.deepEqual(what(theme, total), ['totalBytes']);
  // the entries: the theme's files and its json
  const many = structuredClone(theme);
  const add = (n) => {
    for (let i = 0; i < n; i++) many.keys.overrides['qwerty/x' + i] = { fill: { type: 'image', file: 'k' + i + '.png', insets: [0, 0, 0, 0], pxPerDp: 1 }, pressed: null, label: 'show' };
  };
  add(lim.entries - names.length - 1);
  assert.deepEqual(what(many, {}), []);
  add(lim.entries - names.length);
  assert.deepEqual(what(many, {}), ['entries']);
  // the single keys
  const keys = structuredClone(theme);
  keys.keys.overrides = {};
  for (let i = 0; i < lim.overrides; i++) keys.keys.overrides['qwerty/y' + i] = { fill: null, pressed: null, label: 'hide' };
  assert.deepEqual(what(keys, base()), []);
  keys.keys.overrides['qwerty/y' + lim.overrides] = { fill: null, pressed: null, label: 'hide' };
  assert.deepEqual(what(keys, base()), ['overrides']);
  // the sides, and a key image's bytes
  const side = (file, n) => ({ [file]: [n, 1] });
  assert.deepEqual(what(theme, base(), side('full-portrait.png', lim.pictureSide)), []);
  assert.deepEqual(what(theme, base(), side('full-portrait.png', lim.pictureSide + 1)), ['pictureSide']);
  assert.deepEqual(what(theme, base(), side('full-landscape.png', lim.pictureSide + 1)), ['pictureSide']);
  assert.deepEqual(what(theme, base(), side('full-space.png', lim.keyImageSide)), []);
  assert.deepEqual(what(theme, base(), side('full-space.png', lim.keyImageSide + 1)), ['keyImageSide']);
  const heavy = base();
  heavy['full-space.png'] = sized(lim.keyImageBytes);
  assert.deepEqual(what(theme, heavy), []);
  heavy['full-space.png'] = sized(lim.keyImageBytes + 1);
  assert.deepEqual(what(theme, heavy), ['keyImageBytes']);
  // a file named as an object's own is a file all the same
  const odd = L.mapFiles(theme, (f) => (f === 'full-font.ttf' ? 'constructor' : f));
  assert.deepEqual(what(odd, Object.assign(Object.create(null), base(), { constructor: sized(lim.fileBytes + 1) })), ['fileBytes']);
  assert.deepEqual(what(odd, Object.assign(Object.create(null), base())), []);
});

test('a key image is made no bigger than the app draws it: 3 × the largest key\'s dp', () => {
  const cases = [
    // [size, largest dp, insets, pxPerDp] → [scale, pxPerDp, insets] or null
    [[300, 300], [100, 100], [10, 10, 10, 10], 3, null],
    [[450, 450], [100, 100], [30, 30, 30, 30], 3, [2 / 3, 2, [20, 20, 20, 20]]],
    [[600, 600], [100, 100], [30, 30, 30, 30], 3, [1 / 3, 1, [10, 10, 10, 10]]],
    [[900, 300], [100, 50], [0, 0, 0, 0], 1, [1 / 3, 1, [0, 0, 0, 0]]],
    [[900, 300], [100, 50], [0, 0, 0, 0], 3, [1 / 3, 3, [0, 0, 0, 0]]],
    [[800, 800], [100, 100], [40, 40, 40, 40], 1, [3 / 8, 1, [15, 15, 15, 15]]],
    // pxPerDp can go no lower than 1: the image still made small enough, its corners less wide
    [[1000, 100], [100, 100], [12, 6, 12, 6], 2, [0.3, 1, [3, 1, 3, 1]]],
    [[1000, 1000], [100, 100], [12, 12, 12, 12], 4, [0.25, 1, [3, 3, 3, 3]]],
  ];
  for (const [size, largest, insets, pxPerDp, want] of cases) {
    const got = L.fitKeyImage(size, largest, { insets, pxPerDp });
    const what = JSON.stringify([size, largest, insets, pxPerDp]);
    if (!want) { assert.equal(got, null, what); continue; }
    assert.ok(Math.abs(got.scale - want[0]) < 1e-12, what + ' scale ' + got.scale);
    assert.equal(got.pxPerDp, want[1], what);
    assert.deepEqual(got.insets, want[2], what);
    // never past 3 × dp, whatever the corners
    assert.ok(Math.floor(size[0] * got.scale) <= 3 * largest[0] && Math.floor(size[1] * got.scale) <= 3 * largest[1], what);
  }
});

test('contrast is Contrast.ratio, a colour reads dark as the page guesses, a cut fits inside', () => {
  for (const [fg, bg, ratio] of rules.contrast) assert.ok(Math.abs(L.contrast(fg, bg) - ratio) < 1e-12, [fg, bg, ratio, L.contrast(fg, bg)].join(' '));
  assert.ok(rules.contrast.some(([fg]) => fg >>> 24 !== 0xff), 'translucent text among the cases');
  // luminance under 0.18: dark
  assert.equal(L.isDarkColor(L.argb(255, 0, 0, 0)), true);
  assert.equal(L.isDarkColor(-1), false);
  assert.equal(L.isDarkColor(L.argb(255, 117, 117, 117)), true); // 0.178
  assert.equal(L.isDarkColor(L.argb(255, 118, 118, 118)), false); // 0.181
  assert.equal(L.isDarkColor(L.argb(255, 0, 137, 0)), true); // 0.1789
  assert.equal(L.isDarkColor(L.argb(255, 0, 138, 0)), false); // 0.1818
  // never up; down to fit both sides, the ratio kept, at least a pixel
  assert.deepEqual(L.resizeInside(100, 50, 200, 200), [100, 50]);
  assert.deepEqual(L.resizeInside(200, 200, 200, 200), [200, 200]);
  assert.deepEqual(L.resizeInside(4000, 3000, 1440, 800), [1067, 800]);
  assert.deepEqual(L.resizeInside(3000, 4000, 1440, 800), [600, 800]);
  assert.deepEqual(L.resizeInside(3000, 1000, 1440, 800), [1440, 480]);
  assert.deepEqual(L.resizeInside(10000, 1, 100, 100), [100, 1]);
});

test('the page runs only its own two scripts, by their hashes', () => {
  const csp = /<meta http-equiv="Content-Security-Policy" content="([^"]*)">/.exec(html)[1];
  const dirs = Object.fromEntries(csp.split(';').map((d) => d.trim().split(/\s+/)).map(([k, ...v]) => [k, v]));
  assert.deepEqual(dirs['default-src'], ["'none'"]);
  assert.deepEqual(dirs['base-uri'], ["'none'"]);
  assert.deepEqual(dirs['form-action'], ["'none'"]);
  assert.ok(!dirs['script-src'].includes("'unsafe-inline'"));
  const run = [...html.matchAll(/<script(?![^>]*type="application\/json")[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
  assert.equal(run.length, 2);
  assert.deepEqual(dirs['script-src'], run.map((t) => "'sha256-" + createHash('sha256').update(t).digest('base64') + "'"));
});

test('the zip is written byte for byte the same, its names UTF-8, CRCs right', async () => {
  assert.equal(L.crc32(new TextEncoder().encode('123456789')), 0xcbf43926);
  const enc = new TextEncoder();
  const z = L.writeZip([['主题.json', enc.encode('{}')], ['a.png', new Uint8Array([1, 2, 3])]]);
  assert.deepEqual(z, L.writeZip([['主题.json', enc.encode('{}')], ['a.png', new Uint8Array([1, 2, 3])]]));
  const back = L.readZip(z);
  assert.deepEqual(back.map((e) => e.name), ['主题.json', 'a.png']);
  assert.deepEqual([...(await back[1].read())], [1, 2, 3]);
  assert.equal(new DataView(z.buffer).getUint16(6, true) & 0x0800, 0x0800);
});

// the files of theme.json for the page's export: the app's PNGs under theme.json's names, a tiny font
async function webFiles() {
  const app = await L.openZip(format, bytes('from-app.zip'));
  const font = bytes('tiny.ttf');
  const files = {};
  for (const [name, b] of Object.entries(app.files)) files[name.replace('from-app-', 'full-')] = b;
  files['full-font.ttf'] = font;
  files['full-candidates.ttf'] = font;
  return files;
}

test('the page exports theme.json as from-web.zip, byte for byte', async () => {
  const { theme } = L.readTheme(format, themeText);
  const zip = L.exportZip(format, theme, await webFiles());
  if (process.env.UPDATE) writeFileSync(join(data, 'from-web.zip'), zip);
  assert.deepEqual(zip, bytes('from-web.zip'), 'from-web.zip is not what the page exports now: UPDATE=1 node --test writes it');
  const back = await L.openZip(format, zip);
  assert.deepEqual(back.theme, theme);
  assert.deepEqual(L.readZip(zip).map((e) => e.name), [...L.lookFiles(theme), 'full.json']);
  assert.equal(L.fontType(back.files['full-font.ttf']), 'ttf');
  assert.deepEqual(L.limitBreaches(format, theme, back.files, {}), []);
});

test('a crop on the turned picture is the source\'s box and back, each quarter turn', () => {
  const W = 400, H = 300;
  const box = [10, 20, 110, 70];
  for (const rot of [0, 90, 180, 270]) {
    const src = L.cropToSource(box, W, H, rot);
    assert.ok(src[0] >= 0 && src[1] >= 0 && src[2] <= W && src[3] <= H, 'in the source at ' + rot);
    assert.deepEqual(L.cropFromSource(src, W, H, rot), box, 'back at ' + rot);
  }
  // a quarter turn clockwise: the turned picture's top-left corner is the source's bottom-left
  assert.deepEqual(L.cropToSource([0, 0, 10, 10], W, H, 90), [0, H - 10, 10, H]);
  assert.deepEqual(L.cropToSource([0, 0, 10, 10], W, H, 270), [W - 10, 0, W, 10]);
  assert.deepEqual(L.resizeInside(2000, 1000, 1080, 830), [1080, 540]);
  assert.deepEqual(L.resizeInside(500, 400, 1080, 830), [500, 400]);
  assert.equal(L.darkenAlpha(70), 76);
  assert.equal(L.darkenAlpha(100), 0);
});

test('the simple view\'s five colours make the 24', () => {
  const s = { bg: L.fromHex('#FFEEEEEE'), key: -1, text: L.fromHex('#212121'), fn: L.fromHex('#FFE1E1E1'), accent: L.fromHex('#FF4285F4') };
  const p = L.deriveSimple(s, false);
  assert.deepEqual(Object.keys(p), Object.keys(format.types.Palette));
  assert.equal(L.toHex(p.keyTextColor), '#FF212121');
  assert.equal(L.toHex(p.candidateCommentColor), '#BF212121');
  assert.equal(L.toHex(p.keyPressHighlightColor), '#1F212121');
  assert.equal(L.toHex(p.accentKeyTextColor), '#FFFFFFFF');
  assert.equal(L.toHex(p.keyShadowColor), '#33000000');
  assert.deepEqual(L.simpleOf(p), s);
  const dark = L.deriveSimple({ ...s, bg: L.fromHex('#FF202124'), text: -1, accent: L.fromHex('#FFAAC7FF') }, true);
  assert.equal(L.toHex(dark.keyPressHighlightColor), '#33FFFFFF');
  assert.equal(L.toHex(dark.accentKeyTextColor), '#FF000000');
  assert.equal(L.toHex(dark.keyShadowColor), '#66000000');
  assert.equal(L.isDarkColor(L.fromHex('#FF202124')), true);
  assert.equal(L.isDarkColor(L.fromHex('#FFEEEEEE')), false);
  assert.equal(L.toHex(L.invertLightness(L.fromHex('#80FFFFFF'))), '#80000000');
  assert.equal(L.fromHex('12'), null);
  assert.equal(L.fromHex('#123456'), L.fromHex('#FF123456'));
});

test('the built-in themes are the app\'s eleven', () => {
  assert.equal(presets.length, 11);
  for (const p of presets) {
    assert.deepEqual(Object.keys(p.palette), Object.keys(format.types.Palette), p.name);
    assert.equal(typeof p.isDark, 'boolean');
  }
});

test('the measured layout and icons have what the preview draws', () => {
  const icons = JSON.parse(block('youmo-icons'));
  assert.equal(layout.format, 1);
  assert.deepEqual(layout.palette, Object.keys(format.types.Palette));
  assert.ok(layout.phones.length > 0 && layout.views.length > 0);
  const phones = new Set(layout.phones.map((p) => p.id));
  const box = (r) => assert.ok(Array.isArray(r) && r.length === 4 && r.every(Number.isFinite) && r[2] >= 0 && r[3] >= 0, JSON.stringify(r));
  const color = (c) => assert.ok(layout.palette.includes(c) || /^#[0-9A-F]{8}$/i.test(c), c);
  const alpha = (e) => assert.ok(e.a === undefined || (typeof e.a === 'number' && e.a >= 0 && e.a <= 1), JSON.stringify(e));
  const text = (e) => {
    box(e.r); color(e.c); alpha(e);
    assert.ok(typeof e.s === 'string' && e.z > 0 && Number.isFinite(e.y));
    // the alignment and x come together: where the line is set in a view wider than it
    assert.equal(e.al === undefined, e.x === undefined, JSON.stringify(e));
    if (e.al !== undefined) assert.ok(['l', 'c', 'r'].includes(e.al) && Number.isFinite(e.x), JSON.stringify(e));
    // a is an alpha only
  };
  const icon = (e) => { box(e.r); color(e.c); assert.ok(icons[e.i], e.i); assert.ok(e.rot === undefined || Number.isFinite(e.rot)); };
  for (const v of layout.views) {
    assert.ok(phones.has(v.phone), v.phone);
    assert.ok(['normal', 'one-handed', 'floating'].includes(v.mode), v.mode);
    assert.equal(v.items.filter((e) => e.t === 'base').length, 1);
    for (const e of v.items) {
      if (e.t === 'key') {
        box(e.r);
        assert.ok(['letter', 'function', 'space', 'enter'].includes(e.k), e.k);
        assert.ok(['default', 'on', 'off', 'special'].includes(e.b), e.b);
        if (e.n) assert.ok(L.isKnownKeyName(layout.names, e.n), e.n);
        for (const x of e.l || []) text(x);
        for (const x of e.i || []) icon(x);
      } else if (e.t === 'text') text(e);
      else if (e.t === 'icon') icon(e);
      else if (e.t === 'fill') { box(e.r); color(e.c); alpha(e); }
      else assert.ok(['base', 'bar'].includes(e.t), e.t);
    }
  }
  for (const ic of Object.values(icons)) {
    assert.ok(ic.vw > 0 && ic.vh > 0 && ic.paths.length > 0);
    for (const p of ic.paths) assert.equal(typeof p.d, 'string');
  }
  // the default phone's portrait keyboard has every board the page offers, idle
  for (const b of ['qwerty', 'nine', 'k14', 'k18', 'zhuyin', 'zhuyin-nine', 'number', 'symbols']) {
    assert.ok(layout.views.some((v) => v.phone === layout.phones[0].id && !v.land && v.board === b && v.mode === 'normal' && v.state === 'idle'), b);
  }
});
