// The theme maker's own code against what the app makes (testdata/, written by the app's tests), and the pages assembled.
// Run: node --test web/theme-maker/test/format.test.mjs   (a file: Node 24 takes no folder here)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync, copyFileSync, cpSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';
import { createHash } from 'node:crypto';
import { deflateRawSync } from 'node:zlib';
import { assemble, blockOf, ENGINE, engineText, fontFaces, FONTS, GENERATED, generated, NO_ENGINE } from '../tools/assemble.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const data = join(here, 'testdata');
const html = assemble();
// made when a test asks, so that only those tests fail where the engine is not built
let builtPage = null;
const built = () => (builtPage ??= assemble(undefined, ENGINE));

function block(id, page = html) {
  const text = blockOf(page, id);
  assert.ok(text != null, 'no block ' + id + ' in the page');
  return text;
}

// in this realm, so its objects compare as ours
vm.runInThisContext(block('youmo-lib'), { filename: 'youmo-lib' });
const L = globalThis.YoumoLib;
const format = JSON.parse(block('youmo-format'));
const migration = JSON.parse(readFileSync(join(data, 'migration.json'), 'utf8'));
const themeText = readFileSync(join(data, 'theme.json'), 'utf8');
const bytes = (name) => new Uint8Array(readFileSync(join(data, name)));

test('a 2.1 theme is brought to 3.0 as the app brings it', () => {
  const to = L.from21(format, migration.from);
  assert.equal(L.writeTheme(format, to), JSON.stringify(migration.to));
  const partial = L.from21(format, migration.partialFrom);
  assert.equal(L.writeTheme(format, partial), JSON.stringify(migration.partialTo));
  assert.equal(partial.picture.brightness, 70);
  assert.equal(partial.picture.portrait.rotation, 0);
  assert.deepEqual(partial.picture.portrait.cropRect, { bottom: -1, left: 10, right: -1, top: 20 });
  for (const v of [2147483648, -2147483649, 4294967295]) {
    assert.throws(() => L.from21(format, { ...migration.from, keyTextColor: v }), (e) => e.code === 'bad', String(v));
  }
  assert.equal(L.from21(format, { ...migration.from, keyTextColor: -2147483648 }).palette.keyTextColor, -2147483648);
  const read = L.readTheme(format, JSON.stringify(migration.from));
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
  const t = structuredClone(good);
  t.keys.overrides['k99/r9c9'] = t.keys.overrides['qwerty/q'];
  assert.ok('k99/r9c9' in L.readTheme(format, JSON.stringify(t)).theme.keys.overrides);
});

test('key names are read as KeyNames reads them', () => {
  assert.deepEqual(L.parseKeyName('qwerty/q'), { board: 'qwerty', key: 'q' });
  assert.deepEqual(L.parseKeyName('*/backspace'), { board: '*', key: 'backspace' });
  for (const bad of ['qwerty/', '/q', 'q', 'Qwerty/q', 'a--b/q', 'qwerty/a b']) assert.throws(() => L.parseKeyName(bad), bad);
});

test('what the app exports opens, its own and one before 3.0', async () => {
  const app = await L.openZip(format, bytes('from-app.theme'));
  assert.equal(app.migrated, false);
  const want = JSON.parse(themeText);
  delete want.version;
  want.name = 'from-app';
  want.text.keyFont = null;
  want.text.candidateFont = null;
  const rename = (o) => JSON.parse(JSON.stringify(o).replaceAll('"full-', '"from-app-'));
  assert.deepEqual(app.theme, rename(want));
  for (const [name, b] of Object.entries(app.files)) assert.equal(L.imageType(b), 'png', name);

  const old = await L.openZip(format, bytes('from-app-2.1.theme'));
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
  const one = await L.openZip(format, L.writeZip([['__MACOSX/._t.json', enc.encode('x')], ['._t.json', enc.encode('x')],
    ['t.json', enc.encode(JSON.stringify(migration.from))], ['old-cropped.png', png(16, 24)], ['old-src', new Uint8Array(1)]]));
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
async function appEntries() {
  return Promise.all(L.readZip(bytes('from-app.theme')).map(async (e) => ({ name: e.name, data: await e.read() })));
}
const refusedZip = (zip, code, detail) => assert.rejects(L.openZip(format, zip), (e) => {
  assert.equal(e.code, code, e.message);
  if (detail) assert.ok(e.detail.includes(detail), e.detail + ' has not ' + detail);
  return true;
});

test('a zip is unpacked no further than the app would: a bomb, a lie, a count, a total, an entry outside', async () => {
  const lim = format.limits;
  const good = await appEntries();
  // the zip is made first and the 50 MB let go, so what is measured is the unpacking alone
  const bombZip = zipOf([{ name: 'x.json', data: new Uint8Array(50 * 1024 * 1024), deflate: true, size: 10 }]);
  if (globalThis.gc) globalThis.gc();
  const before = process.memoryUsage().arrayBuffers;
  await refusedZip(bombZip, 'not-theme', 'more than');
  assert.ok(process.memoryUsage().arrayBuffers - before < 8 * 1024 * 1024, 'the bomb was not unpacked whole');
  await refusedZip(zipOf([...good, { name: 'big.bin', data: new Uint8Array(1), size: lim.fileBytes + 1 }]), 'limit', 'fileBytes');
  const third = Math.floor(lim.totalBytes / 3);
  await refusedZip(zipOf([...good, ...[1, 2, 3].map((i) => ({ name: 'f' + i, data: new Uint8Array(1), size: third + 1 }))]), 'limit', 'totalBytes');
  const fill = (n) => Array.from({ length: n }, (_, i) => ({ name: 'pad/' + i, data: new Uint8Array(0) }));
  const atLimit = await L.openZip(format, zipOf([...good, ...fill(lim.entries - good.length)]));
  assert.equal(atLimit.theme.name, 'from-app');
  await refusedZip(zipOf([...good, ...fill(lim.entries - good.length + 1)]), 'limit', 'entries');
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
  const w = 4096 - 1, h = 17 - 1;
  const bits = w | (h << 14);
  assert.deepEqual(L.imageSize(riff('VP8L', [0x2f, bits & 255, (bits >> 8) & 255, (bits >> 16) & 255, (bits >>> 24) & 255])), [4096, 17]);
  assert.deepEqual(L.imageSize(riff('VP8X', [0, 0, 0, 0, 0xff, 0x0f, 0, 0x01, 0x10, 0])), [4096, 4098]);
  assert.deepEqual(L.imageSize(new Uint8Array([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x10, 0x27, 0x05, 0])), [10000, 5]);
  assert.equal(L.imageSize(new Uint8Array([1, 2, 3])), null);
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
  const space = others.find((e) => e.name === 'from-app-space.png');
  const later = png(9, 9);
  const twice = await L.openZip(format, zipOf([...others, { name: space.name, data: later }, json]));
  assert.deepEqual(twice.files['from-app-space.png'], later);
  const t = structuredClone(theme);
  t.keys.space.fill.file = 'some/dir/from-app-space.png';
  const pathed = await L.openZip(format, withTheme(t, others));
  assert.equal(pathed.theme.keys.space.fill.file, 'from-app-space.png');
  const dotted = structuredClone(theme);
  dotted.keys.space.fill.file = '._space.png';
  assert.ok((await L.openZip(format, withTheme(dotted, [...others, { name: '._space.png', data: png(4, 4) }]))).files['._space.png']);
  for (const bad of ['x.json', 'X.JSON', '.saving', 'dir/', '..']) {
    const b = structuredClone(theme);
    b.keys.space.fill.file = bad;
    await refusedZip(withTheme(b, others), 'missing', bad);
  }
  const named = structuredClone(theme);
  named.name = 'a/b';
  await refusedZip(withTheme(named, others), 'bad', 'name');
  for (const odd of ['constructor', 'toString', '__proto__', 'hasOwnProperty']) {
    const o = structuredClone(theme);
    o.keys.space.fill.file = odd;
    const opened = await L.openZip(format, withTheme(o, [...others, { name: odd, data: png(5, 5) }]));
    assert.ok(Object.hasOwn(opened.files, odd), odd);
    assert.deepEqual(opened.files[odd], png(5, 5));
  }
});

test('a cut fits inside', () => {
  assert.deepEqual(L.resizeInside(100, 50, 200, 200), [100, 50]);
  assert.deepEqual(L.resizeInside(200, 200, 200, 200), [200, 200]);
  assert.deepEqual(L.resizeInside(4000, 3000, 1440, 800), [1067, 800]);
  assert.deepEqual(L.resizeInside(3000, 4000, 1440, 800), [600, 800]);
  assert.deepEqual(L.resizeInside(3000, 1000, 1440, 800), [1440, 480]);
  assert.deepEqual(L.resizeInside(10000, 1, 100, 100), [100, 1]);
  assert.deepEqual(L.resizeInside(2000, 1000, 1080, 830), [1080, 540]);
  assert.deepEqual(L.resizeInside(500, 400, 1080, 830), [500, 400]);
});

test('the page carries only the generated blocks it reads', () => {
  const ids = (page) => [...page.matchAll(/<script type="application\/json" id="([\w-]+)">/g)].map((m) => m[1]);
  assert.deepEqual(ids(html), ['youmo-format', 'youmo-engine-icons', 'youmo-bar-icons', 'youmo-examples']);
});

// what can go wrong is an id the app's tests write that no page reads any more, which nothing else would show
test('each generated block the app writes has its file and a page that reads it', () => {
  for (const id of GENERATED) {
    assert.ok(generated(id).length > 0, id);
    assert.ok(blockOf(html, id) !== null, id + ': no page reads it');
  }
});

test("the page made without --engine holds no engine; the page built holds the build's, and no bigger than its budget", () => {
  assert.equal(blockOf(html, 'youmo-engine'), NO_ENGINE);
  const engine = blockOf(built(), 'youmo-engine');
  assert.equal(engine, engineText(), 'the page built has another engine');
  // no minifier without npm here: held just above its size so that it grows only on purpose; each raise's history is in git
  assert.ok(Buffer.byteLength(engine) <= 2_120_000, 'the engine is ' + Buffer.byteLength(engine) + ' bytes');
  assert.match(engine, /globalThis\['youmo-engine'\] = factory\(/);
  const rest = (page) => page.replace(/<script id="youmo-engine">[\s\S]*?<\/script>/, '').replace(/script-src [^;]*;/, '');
  assert.equal(rest(built()), rest(html));
});

test("the page's fonts are fonts/, each family's two weights, put in whole", () => {
  const css = /<style id="youmo-fonts">\n([\s\S]*?)\n<\/style>/.exec(html);
  assert.ok(css, 'no youmo-fonts style');
  assert.equal(css[1], fontFaces());
  assert.deepEqual(FONTS.map(([f, w]) => f + ' ' + w), ['Youmo Latin 400', 'Youmo Latin 700', 'Youmo Han 400', 'Youmo Han 700']);
  for (const [, , file] of FONTS) assert.equal(readFileSync(join(here, '..', 'fonts', file)).subarray(0, 4).toString('latin1'), 'wOF2', file);
  const total = FONTS.reduce((n, [, , file]) => n + readFileSync(join(here, '..', 'fonts', file)).length, 0);
  assert.ok(total <= 250_000, 'the fonts are ' + total + ' bytes');
});

test('an engine that would end its script element, or none at all, is refused', () => {
  const dir = mkdtempSync(join(tmpdir(), 'youmo-engine-'));
  try {
    assert.throws(() => engineText(join(dir, 'none.js')), /youmoEngine/);
    for (const bad of ['a("</script>")', 'a("<!--")', 'a("<SCRIPT>")']) {
      writeFileSync(join(dir, 'e.js'), bad);
      assert.throws(() => engineText(join(dir, 'e.js')), /ends or escapes/, bad);
    }
    writeFileSync(join(dir, 'e.js'), 'a("<\\/script>")\n\n');
    assert.equal(engineText(join(dir, 'e.js')), 'a("<\\/script>")');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('assemble.mjs refuses a page it cannot make, and puts in text that looks like a slot as it is', () => {
  const src = join(here, '..', 'src');
  const dir = mkdtempSync(join(tmpdir(), 'youmo-assemble-'));
  try {
    for (const f of ['style.css', 'maker.js', 'form.js', 'ui.js']) copyFileSync(join(src, f), join(dir, f));
    cpSync(join(src, 'panels'), join(dir, 'panels'), { recursive: true });
    const page = readFileSync(join(src, 'page.html'), 'utf8');
    const made = (p, gen = undefined) => { writeFileSync(join(dir, 'page.html'), p); return assemble(dir, null, undefined, undefined, gen); };
    assert.throws(() => made(page.replace('{{ui}}', '')), /no \{\{ui\}\}/, 'a slot missing');
    assert.throws(() => made(page.replace('{{css}}', '{{css}}{{css}}')), /2 \{\{css\}\}/, 'a slot twice');
    const gen = join(dir, 'generated');
    cpSync(join(here, '..', 'generated'), gen, { recursive: true });
    rmSync(join(gen, 'youmo-engine-icons.json'));
    assert.throws(() => made(page, gen), /no youmo-engine-icons block/, 'a generated block missing');
    assert.throws(() => made(page.replace(/<script id="youmo-ui">/, '<script>')), /a script not hashed/, 'a script not hashed');
    // a slot's name inside what is put in is text, not a slot
    writeFileSync(join(dir, 'style.css'), '/* {{lib}} $& */');
    const out = made(page);
    assert.ok(out.includes('/* {{lib}} $& */'));
    assert.equal(out.split(readFileSync(join(src, 'lib.js'), 'utf8').replace(/\n+$/, '')).length, 2, 'lib.js put in once');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('an image is decoded only within what it may be: the source a photo, a cut 4096, a key image 1024', async () => {
  const lim = format.limits;
  const source = L.decodeCaps(format, 'source');
  assert.ok(L.decodable([12000, 9000], source));
  assert.ok(!L.decodable([12000, 9001], source), 'past the source\'s pixels');
  assert.ok(!L.decodable([12001, 10], source), 'past the source\'s side');
  assert.ok(!L.decodable([11000, 11000], source), 'within the side, past the pixels');
  assert.equal(source.keep, lim.pictureSide);
  const cut = L.decodeCaps(format, 'picture');
  assert.ok(L.decodable([lim.pictureSide, lim.pictureSide], cut));
  assert.ok(!L.decodable([lim.pictureSide, lim.pictureSide + 1], cut));
  const key = L.decodeCaps(format, 'key');
  assert.ok(L.decodable([lim.keyImageSide, lim.keyImageSide], key));
  assert.ok(!L.decodable([lim.keyImageSide + 1, 1], key));
  assert.ok(!L.decodable(null, cut), 'an unread header is not decoded');
  const { theme } = await L.openZip(format, bytes('from-app.theme'));
  const good = await appEntries();
  const huge = await L.openZip(format, zipOf(good.map((e) => (e.name === theme.picture.src ? { name: e.name, data: png(30000, 30000) } : e))));
  assert.deepEqual(L.imageSize(huge.files[theme.picture.src]), [30000, 30000]);
  assert.ok(!L.decodable(L.imageSize(huge.files[theme.picture.src]), source));
});

test('each page runs only its own scripts, by their hashes, without the engine and as built', () => {
  for (const [what, page, count] of [['the page', html, 6], ['the page built', built(), 6]]) {
    const m = /<meta http-equiv="Content-Security-Policy" content="([^"]*)">/.exec(page);
    assert.ok(m, 'no CSP meta in ' + what);
    const csp = m[1];
    const dirs = Object.fromEntries(csp.split(';').map((d) => d.trim().split(/\s+/)).map(([k, ...v]) => [k, v]));
    assert.deepEqual(dirs['default-src'], ["'none'"]);
    assert.deepEqual(dirs['base-uri'], ["'none'"]);
    assert.deepEqual(dirs['form-action'], ["'none'"]);
    assert.ok(!dirs['script-src'].includes("'unsafe-inline'"));
    const run = [...page.matchAll(/<script(?![^>]*type="application\/json")[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
    assert.equal(run.length, count, what);
    assert.deepEqual(dirs['script-src'], run.map((t) => "'sha256-" + createHash('sha256').update(t).digest('base64') + "'"), what);
  }
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


test('a file the page adds is named by its CRC and its length: two of one CRC are two names', () => {
  // the length is in the name: contents of one CRC-32 but of different lengths get different names
  const a = new Uint8Array([1, 2, 3]);
  const b = new Uint8Array([1, 2, 3, 0]);
  assert.match(L.contentId(a), /^[0-9a-f]{8}-3$/);
  assert.notEqual(L.contentId(a), L.contentId(b));
});

// dev/serve.mjs (the engine's preview, pixel-smoke's server): a path it cannot read is no file, never the server down
test('the dev server answers a malformed or outside path with no file, and the page with its engine', async () => {
  const { answer } = await import('../dev/serve.mjs');
  assert.equal(answer('/fonts/%E0%A4%A'), null);
  assert.equal(answer('/data/%2e%2e%2fsecret.xml'), null);
  assert.equal(answer('/fonts/..%2Flatin-400.woff2'), null);
  assert.equal(answer('/nothing'), null);
  assert.equal(answer('/fonts/latin-400.woff2')[0], 'font/woff2');
  const [type, page] = answer('/page.html');
  assert.equal(type, 'text/html; charset=utf-8');
  assert.equal(page, built());
});
