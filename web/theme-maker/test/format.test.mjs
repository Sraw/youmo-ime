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
  // flat, the space lays its own colour: switching moves which colour is turned down
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
    ['t.json', enc.encode(JSON.stringify(rules.migration.from))], ['old-cropped.png', bytes('from-app.zip').slice(0, 4)], ['old-src', new Uint8Array(1)]]));
  assert.equal(one.theme.name, 'old');
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
