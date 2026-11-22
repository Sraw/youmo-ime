// Another input method's skin opened on the page: unzipped and Sogou's container opened here, converted by the engine.
// Run: ./gradlew :lib:keyboard-scene:youmoEngine, then node --test web/theme-maker/test/skin-import.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { createRequire } from 'node:module';
import { randomBytes } from 'node:crypto';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';
import { ENGINE, generated } from '../tools/assemble.mjs';
import { deflateSync } from 'node:zlib';
import { decodePng, nodeIo, png, pixels, skinFixtures, sogouPayload, sogouSeal, writeSkinFixtures, zipOf, baidu, sogou } from '../tools/make-skin-fixtures.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const src = join(here, '..', 'src');
for (const f of ['lib.js', 'form.js', 'maker.js']) vm.runInThisContext(readFileSync(join(src, f), 'utf8'), { filename: f });
vm.runInThisContext(readFileSync(join(src, 'layout', 'layout-maker.js'), 'utf8'), { filename: 'layout-maker.js' });
const L = globalThis.YoumoLib;
const M = globalThis.YoumoMaker;
const LM = globalThis.YoumoLayoutMaker;
const F = globalThis.YoumoForm;
const E = createRequire(import.meta.url)(ENGINE).YoumoEngine;
const FORMAT = JSON.parse(generated('youmo-format'));
const enc = (t) => new TextEncoder().encode(t);
const cat = (a, b) => { const out = new Uint8Array(a.length + b.length); out.set(a); out.set(b, a.length); return out; };
const strings = readFileSync(join(here, '..', '..', '..', 'app', 'src', 'main', 'res', 'values-zh-rCN', 'strings.xml'), 'utf8');
const string = (name) => {
  const m = new RegExp('<string name="' + name + '">([^<]*)</string>').exec(strings);
  assert.ok(m, name);
  return m[1].replace(/\\'/g, "'");
};

// the fixtures as files in a directory of the run's own, read back as the page reads a file picked
const dir = mkdtempSync(join(tmpdir(), 'youmo-skins-'));
process.on('exit', () => rmSync(dir, { recursive: true, force: true }));
const paths = writeSkinFixtures(dir);
const file = (name) => new Uint8Array(readFileSync(paths[name]));
const openSkin = (name, bytes = file(name), io = nodeIo) => M.open(E, L, FORMAT, bytes, name, io);

async function refused(promise, reason) {
  await assert.rejects(promise, (e) => {
    assert.equal(e.reason, reason, e.message);
    return true;
  });
}

test('each skin made here converts and opens as a theme the engine reads, its pictures PNGs the app takes', async () => {
  for (const name of ['sea.bds', 'warm-plain.ssf', 'warm.ssf', 'warm-in.zip', 'spring.it', 'wave.zip', 'mac.zip']) {
    const { doc, notes, skin } = await openSkin(name);
    const read = E.readTheme(doc.xml, null, null);
    assert.ok(read.ok, name + ': ' + JSON.stringify(read.problems));
    assert.ok(skin && skin.themeXml === doc.xml, name);
    assert.deepEqual(Object.keys(doc.layouts), [], name + ': no layouts till they are ticked');
    assert.equal(notes[0].kind, 'skin', name);
    for (const [n, b] of Object.entries(doc.files)) if (/\.png$/.test(n)) assert.ok(decodePng(b), name + ': ' + n);
    assert.deepEqual(M.saveProblems(E, L, FORMAT, doc).filter((p) => p.level === 'error'), [], name);
  }
});

test("Sogou's skin sealed, sealed in a ZIP and plain converts the same: every picture there, none said unread", async () => {
  const plain = await openSkin('warm-plain.ssf');
  const sealed = await openSkin('warm.ssf');
  const inZip = await openSkin('warm-in.zip');
  assert.ok(Object.keys(plain.doc.files).length > 2);
  for (const other of [sealed, inZip]) {
    assert.equal(other.doc.xml, plain.doc.xml);
    assert.deepEqual(Object.keys(other.doc.files).sort(), Object.keys(plain.doc.files).sort());
    assert.deepEqual(other.notes, plain.notes);
  }
  assert.ok(!plain.notes[0].lines.includes(M.SKIN_NOTES.IMAGES), plain.notes[0].lines.join('\n'));
  // ST-213: a conversion using nothing new keeps 0.6.0, one with a dark picture 0.6.3
  assert.equal(F.meta(sealed.doc.xml)['min-app'], '0.6.3');
  assert.equal(F.meta((await openSkin('sea.bds')).doc.xml)['min-app'], '0.6.0');
});

test("a container whose stated length its data goes past stops there, refused as the app's", async () => {
  await refused(openSkin('bomb.ssf'), 'SOGOU_ENCRYPTED');
  assert.equal(M.openError({ reason: 'SOGOU_ENCRYPTED' }), string('exception_skin_sogou_encrypted'));
  // a length past what is left to open, zlib data after it: too big, not broken
  const noise = randomBytes(1 << 20);
  await refused(openSkin('big.ssf', sogouSeal(noise, 150 << 20)), 'UNPACKED_TOO_BIG');
  // a length past 200 times what is sealed: no payload (the app's rule), whatever follows
  await refused(openSkin('big.ssf', sogouSeal(new Uint8Array(16), 200 << 20)), 'SOGOU_ENCRYPTED');
  // sealed with another key: no payload
  const wrong = sogouSeal(sogouPayload([['phoneTheme.ini', enc('x')]]));
  wrong[20] ^= 0xff;
  await refused(openSkin('wrong.ssf', wrong), 'SOGOU_ENCRYPTED');
});

test("the notes are the app's words, the layouts' check box not ticked: the app's own layouts", async () => {
  const { doc, notes, skin } = await openSkin('sea.bds');
  const n = notes.find((x) => x.kind === 'skin');
  assert.deepEqual(n.lines, [
    string('theme_pack_skin_note_decoration'), string('theme_pack_skin_note_animation'), string('theme_pack_skin_note_letter_images'),
    string('theme_pack_skin_note_layout_extra').replace('%1$s', '26 键'),
  ]);
  assert.ok(n.layouts.length > 0, 'a 26-key skin brings its layout');
  assert.match(M.skinLayoutsText(n.layouts), /^这个皮肤带 \d+ 块键位：/);
  assert.ok(Object.keys(skin.layouts).includes('qwerty.xml'), Object.keys(skin.layouts).join());
  assert.ok(!M.save(L, doc).bytes.length || !L.readZip(M.save(L, doc).bytes).some((e) => e.name.startsWith('layouts/')));
  // ticked: its layouts in the pack, the theme the one written with them, the version they need
  const on = M.skinLayouts(doc, skin, true);
  assert.equal(on.xml, skin.themeXmlWithLayouts);
  assert.ok(L.readZip(M.save(L, on).bytes).some((e) => e.name === 'layouts/qwerty.xml'));
  assert.match(on.xml, /min-app="0\.6\.3"/);
  assert.equal(M.withMinApp(E, on), null, 'the converter wrote the version itself');
  // and taken out again
  assert.ok(M.same(M.skinLayouts(on, skin, false), doc));
});

test("every note and error has the app's words, the layouts' lacking ones the layout page's", () => {
  const notes = {
    LAYOUT: 'layout', DECORATION: 'decoration', ANIMATION: 'animation', KEYBOARD_ANIMATION: 'keyboard_animation', FRAME_ANIMATION: 'frame_animation',
    TEXT_SIZE: 'text_size', FONT: 'font', KEY_ICONS: 'key_icons', LETTER_IMAGES: 'letter_images', SOUND: 'sound', DARK_PICTURE: 'dark_picture',
    NO_DARK: 'no_dark', MORE_PERIODS: 'more_periods', IMAGES: 'images', MORE_THEMES: 'more_themes', PANELS: 'panels',
  };
  for (const [id, s] of Object.entries(notes)) assert.deepEqual(M.skinNoteLines(E, { id, args: [] }), [string('theme_pack_skin_note_' + s)], id);
  assert.equal(M.SKIN_NOTES_HEAD, string('theme_pack_skin_notes'));
  assert.deepEqual(M.skinNoteLines(E, { id: 'TOOLBAR_ICONS', args: ['3'] }), [string('theme_pack_skin_note_toolbar_icons_count').replace('%1$d', '3')]);
  assert.deepEqual(M.skinNoteLines(E, { id: 'TOOLBAR_ICONS', args: [] }), [string('theme_pack_skin_note_toolbar_icons')]);
  assert.deepEqual(M.skinNoteLines(E, { id: 'INCLUDES', args: ['2'] }), [string('theme_pack_skin_note_includes_count').replace('%1$d', '2')]);
  assert.deepEqual(M.skinNoteLines(E, { id: 'LAYOUT_SOME', args: ['qwerty', 'nine'] }), [string('theme_pack_skin_note_layout_some').replace('%1$s', '26 键、九键')]);
  assert.deepEqual(M.skinNoteLines(E, { id: 'LAYOUT_EXTRA', args: ['k18'] }), [string('theme_pack_skin_note_layout_extra').replace('%1$s', '18 键')]);
  assert.deepEqual(M.skinNoteLines(E, { id: 'LAYOUT_LACKS', args: ['qwerty\tbackspace', 'nine\tdigits\t1 2'] }), [
    string('theme_pack_skin_note_layout_lacks').replace('%1$s', '26 键').replace('%2$s', LM.words({ key: 'lacks', args: ['backspace'] })),
    string('theme_pack_skin_note_layout_lacks').replace('%1$s', '九键').replace('%2$s', LM.words({ key: 'lacks', args: ['digits', '1 2'] })),
  ]);
  // the layout page's words of each lacking key, the page's copy alike
  for (const what of [...E.layoutFormat().keys, 'backspace', 'enter', 'back', 'panel-back', 'lang', 'lang-no-space', 'space', 'shift', 'nine-side', 'symbol-categories', 'symbol-grid', 'digits', 'letters', 'pad-digits', 'other']) {
    for (const args of [[what], [what, 'a b'], [what, 'a', 'full phone']]) assert.equal(M.lacks(args), LM.words({ key: 'lacks', args }), args.join('|'));
  }
  const errors = {
    NOT_ZIP: 'not_zip', BROKEN: 'not_zip', UNKNOWN: 'unknown', UNPACKED_TOO_BIG: 'unpacked_too_big', ENCRYPTED: 'encrypted', SOGOU_ENCRYPTED: 'sogou_encrypted',
    SOGOU_PC: 'sogou_pc', NO_KEYBOARD: 'no_keyboard',
  };
  for (const [reason, s] of Object.entries(errors)) assert.equal(M.skinError({ reason }), string('exception_skin_' + s), reason);
  assert.equal(M.skinError({ reason: 'TOO_BIG' }), string('exception_skin_too_big').replace('%1$s', '64'));
  assert.equal(M.skinError({ reason: 'TOO_MANY_FILES' }), string('exception_theme_entries').replace('%1$s', '5000'));
  assert.equal(M.skinError({ reason: 'UNSAFE_NAME', detail: '../x' }), string('exception_skin_unsafe_name').replace('%1$s', '../x'));
  assert.equal(M.skinError({ reason: 'NOT_THEME', detail: 'a.zip' }), string('exception_theme_filename').replace('%1$s', 'a.zip'));
  const formats = { shouxin: 'shouxin', gboard: 'gboard', cang: 'cang', apk: 'apk', 'sogou-diy': 'sogou_diy' };
  for (const [id, s] of Object.entries(formats)) assert.equal(M.skinError({ reason: 'UNSUPPORTED', detail: id }), string('exception_skin_unsupported').replace('%1$s', string('skin_format_' + s)));
  const limits = E.skinFormat();
  assert.equal(limits.archiveBytes, 64 << 20, 'the words say 64 MB');
  assert.equal(limits.entries, 5000, 'the words say 5000');
  // ThemeFileName.SKIN_EXTENSIONS, and the page's picker offers each
  const kt = readFileSync(join(here, '..', '..', '..', 'lib', 'ime-core', 'src', 'main', 'kotlin', 'org', 'fcitx', 'fcitx5', 'android', 'input', 'theme', 'ThemeFileName.kt'), 'utf8');
  assert.deepEqual(M.SKIN_EXTENSIONS, /SKIN_EXTENSIONS = setOf\(([^)]*)\)/.exec(kt)[1].split(',').map((x) => x.trim().replace(/"/g, '')));
  const accept = /id="open-input" type="file" accept="([^"]*)"/.exec(readFileSync(join(src, 'page.html'), 'utf8'))[1].split(',');
  for (const x of M.SKIN_EXTENSIONS) assert.ok(accept.includes('.' + x), x);
});

test('a file no skin is refused in the words the app has; a 仓 skin is not converted, said so', async () => {
  await refused(openSkin('lotus.cskin'), 'UNSUPPORTED');
  assert.equal(M.openError(await openSkin('lotus.cskin').catch((e) => e)), '仓输入法的皮肤 暂不支持导入');
  await refused(openSkin('x.bds', enc('not a zip')), 'NOT_ZIP');
  await refused(openSkin('x.bds', zipOf([['readme.txt', enc('hi')]])), 'UNKNOWN');
  await refused(openSkin('x.zip', zipOf([['readme.txt', enc('hi')]])), 'NOT_THEME');
  await refused(openSkin('x.bds', zipOf([['skin.ini', enc('[x]')]])), 'SOGOU_PC');
  await refused(openSkin('x.bds', zipOf([['../port/gen.ini', enc('x')]])), 'UNSAFE_NAME');
  await refused(openSkin('x.apk', new Uint8Array(100)), 'UNSUPPORTED');
  // the browser without what it takes
  await refused(openSkin('sea.bds', file('sea.bds'), null), 'BROWSER');
  // a .theme or a 3.0 .zip opens as before
  const theme = M.save(L, M.fromBuiltin(E, 'nord'));
  assert.ok((await M.open(E, L, FORMAT, theme.bytes, 'nord-mine.zip', nodeIo)).doc.xml.includes('nord-mine'));
});

test('ZIPs as skin tools write them: no directory (sizes after the data), GB18030 names, stored files, macOS extras', async () => {
  const lim = E.skinFormat();
  const files = Object.entries(baidu());
  const scanned = await L.readSkinZip(zipOf(files, { descriptor: true }), lim);
  assert.deepEqual(scanned.map((e) => e.name), files.map(([n]) => n));
  assert.deepEqual(scanned[0].bytes, files[0][1]);
  assert.deepEqual((await L.readSkinZip(zipOf(files, { stored: true }), lim)).map((e) => e.name), files.map(([n]) => n));
  // 皮肤 in GB18030, no UTF-8 flag
  const gb = await L.readSkinZip(zipOf([[Uint8Array.of(0xc6, 0xa4, 0xb7, 0xf4, 0x2e, 0x74, 0x78, 0x74), enc('x')]]), lim);
  assert.equal(gb[0].name, '皮肤.txt');
  const backslash = await L.readSkinZip(zipOf([['a\\b.txt', enc('x')], ['a\\b.txt', enc('second')]]), lim);
  assert.deepEqual(backslash.map((e) => [e.name, e.bytes.length]), [['a/b.txt', 1]], 'the first of a name twice');
  for (const bad of ['/etc/x', 'C:/x', 'a/../../x', 'a\u0001b']) await refused(L.readSkinZip(zipOf([[bad, enc('x')]]), lim), 'UNSAFE_NAME');
  // a deflated file past the ratio: stopped as it inflates
  await refused(L.readSkinZip(zipOf([['big.bin', new Uint8Array(8 << 20)]]), lim), 'UNPACKED_TOO_BIG');
  await refused(L.readSkinZip(enc('Skin....'), lim), 'SOGOU_ENCRYPTED');
  const { doc } = await openSkin('mac.zip');
  assert.equal(doc.xml, (await openSkin('sea.bds')).doc.xml, 'the Finder\'s files and the one folder taken off');
});

test('past the pixels all the images may take together, the rest are not decoded: said as unread', async () => {
  // three images ahead of the skin's own taking nearly all of them (each within one image's)
  const big = png(pixels(3500, 3500));
  const filler = png(pixels(1775, 1775));
  const files = [['a1.png', big], ['a2.png', big], ['a3.png', big], ['a4.png', filler], ...Object.entries(baidu())];
  const { notes } = await openSkin('full.bds', zipOf(files));
  assert.ok(notes[0].lines.includes(M.SKIN_NOTES.IMAGES), notes[0].lines.join('\n'));
  assert.ok(!(await openSkin('sea.bds')).notes[0].lines.includes(M.SKIN_NOTES.IMAGES));
  assert.equal(M.SKIN_PIXELS, 40e6);
});

test("a picture past its bytes is halved till within them, as the app's SkinImport.png", async () => {
  const r = E.skinResized({ width: 4, height: 2, rgba: new Uint8ClampedArray(32).fill(255) }, 2, 1);
  assert.deepEqual([r.width, r.height, r.rgba.length, r.rgba[3]], [2, 1, 8, 255]);
  const { doc } = await openSkin('sea.bds');
  const lim = FORMAT.limits;
  for (const [n, b] of Object.entries(doc.files)) if (/\.png$/.test(n)) assert.ok(b.length <= lim.fileBytes, n);
  // a preview of noise, 600 a side: past its 500 KB as a PNG, within them at 300
  const noise = pixels(600, 600);
  const rnd = randomBytes(4 * 600 * 600);
  for (let i = 0; i < noise.argb.length; i++) noise.argb[i] = (0xff000000 | rnd.readUInt32LE(4 * i)) >>> 0;
  const big = png(noise);
  assert.ok(big.length > 500 * 1024);
  const halved = (await openSkin('noisy.bds', zipOf(Object.entries({ ...baidu(), 'demo.png': big })))).doc.files['preview.png'];
  const p = decodePng(halved);
  assert.deepEqual([p.width, p.height], [300, 300]);
  assert.ok(halved.length <= 500 * 1024, String(halved.length));
});

test('a ZIP that is not a theme, or a broken .theme, is refused as before where the page opens no skin', async () => {
  const theme = M.save(L, M.fromBuiltin(E, 'nord')).bytes;
  const cut = theme.subarray(0, theme.length - 30);
  const before = await M.open(E, L, FORMAT, cut).catch((e) => e);
  assert.ok(!before.reason && M.openError(before), 'the theme reader\'s own error');
  assert.equal(M.openError(before), '这不是主题文件（.theme 或 theme.xml）', 'the words ST-212 says');
  // the theme maker, a .theme: the theme's words, not a skin's
  assert.equal(M.openError(await openSkin('nord.theme', cut).catch((e) => e)), M.openError(before));
  // a broken .zip read as neither: the theme's words too
  assert.equal(M.openError(await openSkin('nord.zip', cut).catch((e) => e)), M.openError(before));
  // the layout maker (layout/ui.js): no file's name, no io; never the browser's words
  const plain = zipOf([['readme.txt', enc('hi')]]);
  for (const bytes of [cut, plain]) {
    const e = await M.open(E, L, FORMAT, bytes).catch((x) => x);
    assert.notEqual(e.reason, 'BROWSER');
    assert.ok(!e.reason, M.openError(e));
  }
  assert.ok(/M\.open\(E, L, TFORMAT, new Uint8Array\(await f\.arrayBuffer\(\)\)\)/.test(readFileSync(join(src, 'layout', 'ui.js'), 'utf8')), 'the layout maker opens with no name, no io');
});

test('entries whose sizes follow them are found in one pass: many of them and a long tail are quick', async () => {
  const lim = E.skinFormat();
  // 80 files of 400 KB that do not deflate: each found where it ends, not after a scan to the file's end
  const files = Array.from({ length: 80 }, (_, i) => ['f' + i + '.bin', new Uint8Array(randomBytes(400 << 10))]);
  for (const descriptor of ['loose', true]) {
    const z = zipOf(files, { descriptor });
    const t0 = Date.now();
    const got = await L.readSkinZip(z, lim);
    assert.deepEqual(got.map((e) => e.name), files.map(([n]) => n), String(descriptor));
    assert.ok(Date.now() - t0 < 1500, descriptor + ': ' + (Date.now() - t0) + ' ms');
  }
  // one with no descriptor anywhere: one scan to the end, then refused
  const lost = zipOf([['a.txt', enc('x')]], { descriptor: true }).subarray(0, -16);
  const t0 = Date.now();
  await refused(L.readSkinZip(cat(lost, new Uint8Array(16 << 20)), lim), 'NOT_ZIP');
  assert.ok(Date.now() - t0 < 1500);
});

test("bytes after a deflated stream's end are let be, as the app's Inflater does", async () => {
  const lim = E.skinFormat();
  const junk = enc('padding after the stream');
  const files = Object.entries(baidu());
  for (const opts of [{}, { descriptor: true }]) {
    const got = await L.readSkinZip(zipOf(files, { ...opts, junk }), lim);
    assert.deepEqual(got.map((e) => [e.name, e.bytes.length]), files.map(([n, b]) => [n, b.length]), JSON.stringify(opts));
  }
  const payload = sogouPayload(Object.entries(sogou()));
  const sealed = sogouSeal(payload, payload.length, cat(deflateSync(payload), junk));
  assert.deepEqual((await openSkin('junk.ssf', sealed)).doc.xml, (await openSkin('warm.ssf')).doc.xml);
});

test('the fixtures are made afresh, none is kept in the repository', () => {
  assert.ok(Object.keys(skinFixtures()).length >= 9);
  assert.ok(!readdirSync(join(here, 'testdata')).some((n) => /\.(bds|bdi|bda|ssf|it|cskin|flex)$/.test(n)));
});
