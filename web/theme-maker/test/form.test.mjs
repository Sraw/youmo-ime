// The editing's forms (src/form.js and src/panels/, 060-render-p3 M2): each panel's operations write the style text
// meant, find the style of exactly the same select= or add one, keep what they do not know, and the engine reads
// the result; the cascade check names the style the engine says each value comes from.
// contract: the forms' bounds (F.LIMITS) against the engine's E.limits(), and every value a form writes against what
// the engine reads (no "does nothing" / "not supported" warning).
// Run: ./gradlew :lib:keyboard-scene:youmoEngine, then node --test web/theme-maker/test/form.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';
import { ENGINE } from '../tools/assemble.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const src = join(here, '..', 'src');
for (const f of ['lib.js', 'form.js', 'maker.js']) vm.runInThisContext(readFileSync(join(src, f), 'utf8'), { filename: f });
globalThis.YoumoPanels = [];
for (const f of readdirSync(join(src, 'panels')).sort()) vm.runInThisContext(readFileSync(join(src, 'panels', f), 'utf8'), { filename: f });
const F = globalThis.YoumoForm;
const P = Object.fromEntries(globalThis.YoumoPanels.map((p) => [p.id, p]));
globalThis.Path2D = class { constructor(d) { this.d = d; } };
const E = createRequire(import.meta.url)(ENGINE).YoumoEngine;
const nord = E.themeSource('nord');
const read = (xml) => { const r = E.readTheme(xml, null, null); assert.ok(r.ok, JSON.stringify(r.problems)); return r; };
const ENV = { width: 1080, height: 700, density: 2.625 };

const BASE = `<theme xmlns="https://youmo-ime/theme/4" name="t" version="4.0">
  <colors>
    <color name="key" value="#ffffff"/>
  </colors>
  <!-- <style select="key.letter" fill="#000000"/> a comment is never a style -->
  <style select="key" fill="@key" radius="4dp"/>
  <style select="key.letter" weird="kept"   fill="#111111"/>
  <style select="key.letter" color="#222222"/>
</theme>
`;

test('a form edit changes the style of exactly that select=, the last one that gives the property', () => {
  const out = F.setProp(BASE, 'key.letter', 'fill', '#333333');
  assert.equal(out, BASE.replace('fill="#111111"', 'fill="#333333"'), 'only the value moved; the attribute it does not know kept, its spacing too');
  const color = F.setProp(BASE, 'key.letter', 'color', '#444444');
  assert.equal(color, BASE.replace('color="#222222"', 'color="#444444"'));
  // a property no style of it gives: the last of that select=
  assert.equal(F.setProp(BASE, 'key.letter', 'radius', '8dp'), BASE.replace('color="#222222"/>', 'color="#222222" radius="8dp"/>'));
});

test('a form edit of a select= no style has adds one at the end of the styles; one of the same words in another order is another style', () => {
  const out = F.setProp(BASE, 'key.space', 'fill', '#00ff00');
  assert.equal(out, BASE.replace('color="#222222"/>', 'color="#222222"/>\n  <style select="key.space" fill="#00ff00"/>'));
  const spaced = F.setProp(BASE, 'key .letter', 'fill', '#00ff00');
  assert.ok(spaced.includes('<style select="key .letter" fill="#00ff00"/>') && spaced.includes('fill="#111111"'), 'not the same text: not the same style');
});

test('a property taken off goes; a style left with only its select= goes with its line', () => {
  const once = F.setProp(BASE, 'key.letter', 'color', null);
  assert.equal(once, BASE.replace('  <style select="key.letter" color="#222222"/>\n', ''));
  assert.equal(F.setProp(BASE, 'key.enter', 'fill', null), BASE, 'nothing to take off');
  assert.equal(F.setProp(once, 'key.letter', 'fill', null), once.replace(/\s+fill="#111111"/, ''), 'the attribute it does not know keeps the style');
});

test('values are written escaped and read back as written', () => {
  const out = F.setProp(BASE, 'key', 'fill', 'a"b<c&');
  assert.ok(out.includes('fill="a&quot;b&lt;c&amp;"'));
  assert.equal(F.valueOf(out, 'key', 'fill'), 'a"b<c&');
});

test('colours: changed, added, taken out, a dark version made of the light one and dropped', () => {
  assert.equal(F.setColor(BASE, 'key', '#123456'), BASE.replace('value="#ffffff"', 'value="#123456"'), 'only the value moved');
  const material = F.withDark(E.themeSource('material'), false);
  assert.deepEqual(F.variants(material), ['light']);
  let x = F.setColor(material, 'key', '#123456');
  x = F.setColor(x, 'mine', 'darker(@key, 8%)');
  assert.equal(F.colors(x).mine, 'darker(@key, 8%)');
  assert.equal(F.colors(x).key, '#123456');
  read(x);
  x = F.withDark(x, true);
  assert.deepEqual(F.variants(x), ['light', 'dark']);
  assert.deepEqual(F.colors(x, 'dark'), F.colors(x, 'light'));
  x = F.setColor(x, 'key', '#000000', 'dark');
  const r = read(x);
  assert.equal(r.theme.info(true).colors.key, '#ff000000');
  assert.equal(r.theme.info(false).colors.key, '#ff123456');
  x = F.withDark(x, false);
  assert.deepEqual(F.variants(x), ['light']);
  assert.equal(F.elements(x, 'colors').length, 1);
  assert.equal(F.colors(F.setColor(x, 'mine', null)).mine, undefined);
  // a theme with no <colors>: one is made, first in the theme
  const bare = '<theme xmlns="https://youmo-ime/theme/4" name="b" version="4.0">\n  <style select="key" fill="@key"/>\n</theme>\n';
  const made = F.setColor(bare, 'key', '#abcdef');
  assert.ok(made.indexOf('<colors>') < made.indexOf('<style'));
  assert.deepEqual(F.colors(made), { key: '#abcdef' });
  assert.equal(F.hexOf('#AABBCC', 50), '#80aabbcc');
  assert.deepEqual(F.parseHex('#80aabbcc'), { rgb: '#aabbcc', opacity: 50 });
  assert.equal(F.parseHex('@key'), null);
});

test('the 配色 panel writes a colour of the version shown, the dark switch writes the dark version', () => {
  const doc = { xml: nord, files: {}, layouts: {} };
  const out = P.colors.ops.set(doc, 'keyText', '#ff0000', true);
  assert.equal(F.colors(out.xml, 'dark').keyText, '#ff0000');
  assert.equal(F.colors(out.xml, 'light').keyText, '#2e3440');
  const single = P.colors.ops.set({ xml: BASE, files: {}, layouts: {} }, 'key', '#00ff00', true);
  assert.equal(F.colors(single.xml).key, '#00ff00', 'a theme of one version: its colours, whatever the preview shows');
  assert.ok(P.colors.ops.dark({ xml: BASE, files: {}, layouts: {} }, true).xml.includes('variant="dark"'));
});

test('the 按键 panel: its range, scene and state make the select= the line on the panel shows', () => {
  assert.equal(F.selector({ range: 'letter', state: 'pressed' }), 'key.letter:pressed');
  assert.equal(F.selector({ range: 'all', scene: 'compact' }), '@compact key');
  assert.equal(F.selector({ range: 'picked', board: 'qwerty', key: 'q', state: 'pressed' }), 'qwerty/q:pressed');
  assert.equal(F.selector({ range: 'picked', board: 'number', key: '*' }), 'number/\\*');
  assert.equal(F.selector({ range: 'board', board: 'nine', scene: 'field=password' }), '@field=password nine/*');
  assert.equal(F.selector({ range: 'enter', part: 'label' }), 'key.enter label');
  assert.equal(F.selector({ range: 'picked', board: null, key: null }), null);
  for (const sel of ['key.letter:pressed', '@compact key', 'qwerty/q:pressed', 'number/\\*', '@field=password nine/*', 'key.enter label', '@dark key.space:locked']) {
    read(F.setProp(nord, sel, 'radius', '6dp'));
  }
});

test('the 按键 panel writes each property as the engine reads it', () => {
  const doc = { xml: nord, files: {}, layouts: {} };
  const k = P.keys.ops;
  const cases = [
    [{ kind: 'solid', color: '#ff0000' }, 'fill', '#ff0000'],
    [{ kind: 'linear', angle: 90, stops: [['#ff0000', 0], ['@accentKey', 100]] }, 'fill', 'linear(90deg, #ff0000 0%, @accentKey 100%)'],
    [{ kind: 'picture', blur: 3, brightness: 80, over: '#33ffffff' }, 'fill', 'picture(3dp, 80%, #33ffffff)'],
  ];
  for (const [fill, prop, text] of cases) {
    const out = k.fill(doc, 'key.letter', fill);
    assert.equal(F.valueOf(out.xml, 'key.letter', prop), text);
    assert.deepEqual(F.parseFill(text), fill);
    read(out.xml);
  }
  const all = [
    ['radius', 8, '8dp'], ['border', { width: 1, color: '#33000000' }, '1dp #33000000'], ['border', 'none', 'none'],
    ['shadow', { dx: 0, dy: 2, blur: 3, color: '@keyShadow' }, '0dp 2dp 3dp @keyShadow'], ['inner-shadow', { dx: 0, dy: 1, blur: 2, color: '#40000000' }, '0dp 1dp 2dp #40000000'],
    ['depth', 3, '3dp'], ['inset', [6, 3], '6dp 3dp'], ['shape', 'circle', 'circle'], ['press', 'ripple', 'ripple'], ['overlay', '#22000000', '#22000000'],
  ];
  for (const [prop, v, text] of all) {
    const out = k.set(doc, 'key.letter', prop, v);
    assert.equal(F.valueOf(out.xml, 'key.letter', prop), text, prop);
    read(out.xml);
  }
  assert.equal(F.valueOf(k.set(doc, 'key', 'radius', null).xml, 'key', 'radius'), null, 'cleared: the attribute gone');
  assert.equal(F.valueOf(k.set(doc, 'key', 'border', null).xml, 'key', 'border'), null, '不设: the wider style shows');
  // past what the engine reads: kept to it, and the engine reads what is written
  const past = [['radius', 64, '24dp'], ['border', { width: 8, color: '#000000' }, '3dp #000000'], ['depth', 20, '8dp'],
    ['shadow', { dx: 0, dy: 40, blur: 30, color: '#000000' }, '0dp 16dp 16dp #000000'], ['inset', [30, 2.25], '24dp 2.3dp']];
  for (const [prop, v, text] of past) {
    const out = k.set(doc, 'key.letter', prop, v);
    assert.equal(F.valueOf(out.xml, 'key.letter', prop), text, prop);
    read(out.xml);
  }
  const nine = { kind: 'linear', angle: 1000, stops: Array.from({ length: 9 }, (_, i) => ['#000000', i * 10]) };
  const capped = F.parseFill(F.valueOf(k.fill(doc, 'key', nine).xml, 'key', 'fill'));
  assert.equal(capped.stops.length, 8);
  assert.equal(capped.angle, 360);
  read(k.fill(doc, 'key', nine).xml);
});

test('an icon the app would refuse (a PNG, an SVG past its subset) is named, and a pack holding one cannot be saved', () => {
  const t = P.text.ops;
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d]);
  const stroked = new TextEncoder().encode('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M0 0h24" stroke="#000" transform="scale(2)"/></svg>');
  assert.equal(t.iconProblems(E, png, 65536).length, 1);
  assert.ok(t.iconProblems(E, stroked, 65536).length > 0);
  assert.ok(t.iconProblems(E, new Uint8Array(70000), 65536)[0].includes('65536'));
  const L = globalThis.YoumoLib;
  const FORMAT = { limits: { iconBytes: 65536, keyImageSide: 1024, pictureSide: 4096, keyImageBytes: 1 << 21, entries: 64, fileBytes: 1 << 25, totalBytes: 1 << 26 } };
  for (const bad of [png, stroked]) {
    const d = t.setIcon({ xml: nord, files: {}, layouts: {} }, 'backspace', bad);
    assert.ok(globalThis.YoumoMaker.saveProblems(E, L, FORMAT, d).some((p) => p.message.includes('icon-backspace.svg')));
  }
});

test('the forms\' bounds are the engine\'s', () => {
  assert.deepEqual(F.LIMITS, JSON.parse(JSON.stringify(E.limits())));
});

test('a select= differing only in its spacing is the same style', () => {
  assert.equal(F.valueOf(BASE, ' key.letter ', 'color'), '#222222');
  assert.equal(F.setProp(BASE, 'key.letter ', 'color', '#444444'), BASE.replace('color="#222222"', 'color="#444444"'));
});

test('the 字和图标 panel: each part apart, its properties as the engine reads them; fonts and icons as files', () => {
  const doc = { xml: nord, files: {}, layouts: {} };
  const t = P.text.ops;
  const all = [
    ['color', '#ff00ff', '#ff00ff'], ['weight', 700, '700'], ['scale', 120, '120%'], ['align', 'top-left', 'top-left'],
    ['letter-spacing', 0.05, '0.05em'], ['stroke', { width: 1, color: '#000000' }, '1dp #000000'], ['text-shadow', { dx: 0, dy: 1, blur: 2, color: '#80000000' }, '0dp 1dp 2dp #80000000'],
    ['hidden', true, 'true'],
  ];
  for (const part of ['', 'label', 'mark', 'shortcut', 'root']) {
    const sel = F.selector({ range: 'letter', part });
    for (const [prop, v, text] of all) {
      const out = t.set(doc, sel, prop, v);
      assert.equal(F.valueOf(out.xml, sel, prop), text, sel + ' ' + prop);
      read(out.xml);
    }
  }
  const ttf = new Uint8Array([0, 1, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8]);
  const withFont = t.addFont(doc, 'Hand.ttf', ttf);
  assert.equal(withFont.files['hand.ttf'], ttf);
  assert.match(withFont.xml, /<font name="hand" src="hand.ttf"\/>/);
  const used = t.set(withFont, 'key.letter label', 'font', 'hand');
  const r = read(used.xml);
  assert.equal(r.theme.info(false).fonts.hand, 'hand.ttf');
  assert.ok(used.xml.indexOf('<font') < used.xml.indexOf('<style'), 'fonts before the styles, as the reader takes them');
  let four = doc;
  for (const n of ['a', 'b', 'c', 'd']) four = t.addFont(four, n + '.ttf', ttf);
  assert.throws(() => t.addFont(four, 'e.ttf', ttf), /4/);
  const svg = new TextEncoder().encode('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M0 0h24v24z"/></svg>');
  assert.deepEqual(t.iconProblems(E, svg, 65536), []);
  const icon = t.setIcon(doc, 'backspace', svg);
  assert.match(icon.xml, /<icon name="backspace" src="icon-backspace.svg"\/>/);
  assert.ok(icon.xml.lastIndexOf('<icon') > icon.xml.lastIndexOf('<style'), 'icons last');
  assert.equal(read(icon.xml).theme.info(false).icons.backspace, 'icon-backspace.svg');
  const back = t.setIcon(icon, 'backspace', null);
  assert.equal(back.xml, nord);
  assert.deepEqual(Object.keys(back.files), []);
});

test('the 背景 panel: a picture with a cut for each orientation, its crop kept as rect=, brightness and blur; taken off, its files go', () => {
  const doc = { xml: nord, files: {}, layouts: {} };
  const b = P.background.ops;
  const bytes = { src: new Uint8Array([1]), portrait: new Uint8Array([2]), landscape: new Uint8Array([3]) };
  const made = b.setPicture(doc, { type: 'jpg', src: bytes.src, cuts: { portrait: { bytes: bytes.portrait, type: 'jpg', rect: [0, 100, 1000, 800] }, landscape: { bytes: bytes.landscape, type: 'jpg', rect: [0, 0, 1000, 400] } } });
  assert.deepEqual(F.picture(made.xml), { src: 'picture.jpg', brightness: 100, blur: 0, cuts: { portrait: { file: 'picture-portrait.jpg', rect: [0, 100, 1000, 800] }, landscape: { file: 'picture-landscape.jpg', rect: [0, 0, 1000, 400] } } });
  assert.deepEqual(Object.keys(made.files).sort(), ['picture-landscape.jpg', 'picture-portrait.jpg', 'picture.jpg']);
  assert.equal(F.valueOf(made.xml, 'keyboard', 'fill'), 'picture', 'the keyboard shows it');
  const r = read(made.xml);
  assert.equal(r.theme.info(false).picture.cuts.portrait, 'picture-portrait.jpg');
  const tuned = b.tune(made, { brightness: 70, blur: 6 });
  assert.match(tuned.xml, /<picture src="picture.jpg" brightness="70%" blur="6dp">/);
  const p = read(tuned.xml).theme.info(false).picture;
  assert.equal(p.brightness, 70);
  assert.equal(p.blurDp, 6);
  const through = b.through(tuned, true);
  for (const sel of ['key', 'key.function', 'key.enter', 'key.space']) assert.equal(F.valueOf(through.xml, sel, 'fill'), 'picture(0dp, 100%, #66ffffff)', sel);
  read(through.xml);
  assert.equal(b.through(through, false).xml, tuned.xml, 'off: as it was, every key');
  const dropped = b.setPicture(through, null);
  for (const sel of ['key', 'key.function', 'key.enter', 'key.space']) assert.equal(F.valueOf(dropped.xml, sel, 'fill'), F.valueOf(nord, sel, 'fill'), 'no picture: ' + sel + ' its colour again');
  read(dropped.xml);
  const gone = b.setPicture(tuned, null);
  assert.equal(F.picture(gone.xml), null);
  assert.deepEqual(Object.keys(gone.files), []);
  assert.equal(F.valueOf(gone.xml, 'keyboard', 'fill'), '@background', 'the keyboard back to its colour');
  read(gone.xml);
});

test('the crop is the biggest box of the keyboard\'s ratio, zoomed and moved inside the picture', () => {
  assert.deepEqual(F.cropRect(4000, 3000, 2), [0, 500, 4000, 2500]);
  assert.deepEqual(F.cropRect(1000, 3000, 2), [0, 1250, 1000, 1750]);
  assert.deepEqual(F.cropRect(4000, 3000, 2, 2, 0, 0), [0, 0, 2000, 1000], 'moved past the edge: kept inside');
  assert.deepEqual(F.cropRect(4000, 3000, 2, 2, 1, 1), [2000, 2000, 4000, 3000]);
  assert.deepEqual(P.background.ops.ratio({ w: 1080, h: 2340, density: 2.625, landscape: false }), 1080 / Math.round(267 * 2.625));
});

test('the cascade check names the style the engine says each value comes from', () => {
  const x = F.setProp(F.setProp(nord, 'key.letter', 'fill', 'linear(90deg, #ff0000 0%, #0000ff 100%)'), 'qwerty/q:pressed', 'overlay', '#40000000');
  const r = read(x);
  const lineOf = (sel) => F.styles(x).find((s) => s.select === sel).line;
  const src = (key, part, pressed) => Object.fromEntries(E.sources('qwerty', r.theme, ENV, { widget: 'key', key, part, states: pressed ? ['pressed'] : [] }).sources.map((s) => [s.property, s.line]));
  assert.equal(src('q', null, false).fill, lineOf('key.letter'));
  assert.equal(src('q', null, false).radius, lineOf('key'));
  assert.equal(src('q', null, true).overlay, lineOf('qwerty/q:pressed'));
  assert.equal(src('w', null, true).overlay, lineOf('key:pressed'));
  assert.equal(src('enter', null, false).fill, lineOf('key.enter'));
  assert.equal(src('q', 'mark', false).color, lineOf('key mark, key shortcut'));
  assert.equal(src('q', 'label', false).color, lineOf('key'), 'inherited from the key');
  assert.deepEqual(E.sources('qwerty', r.theme, ENV, { widget: 'key', key: 'no-such-key' }).sources, []);
  assert.equal(E.sources('qwerty', r.theme, ENV, { widget: 'bar' }).ok, false);
  // what the panel shows: each property, its value, the style's select= and line
  const rows = P.keys.ops.sources(E, F, { xml: x }, r.theme, 'qwerty', ENV, { key: 'q' });
  const fill = rows.find((row) => row.property === 'fill');
  assert.deepEqual(fill, { property: 'fill', value: 'linear(90deg, #ff0000 0%, #0000ff 100%)', select: 'key.letter', line: lineOf('key.letter') });
  // every property's source has that property, as the engine read it
  for (const row of rows) assert.ok(F.styles(x).find((s) => s.line === row.line).attrs.some((a) => a[0] === row.property), row.property);
});

test('从背景图取色: see-through keys of the picture\'s lightness, text that reads on it, its most vivid colour the accent; the engine reads them', () => {
  const dark = P.colors.ops.fromPicture([[20, 30, 40], [30, 40, 50], [255, 51, 102]]);
  assert.equal(dark.accentKey, '#ff3366');
  assert.equal(dark.key, '#4d000000');
  assert.equal(dark.keyText, '#f1f3f4');
  const light = P.colors.ops.fromPicture([[240, 240, 230], [250, 250, 250], [200, 220, 120]]);
  assert.equal(light.key, '#66ffffff');
  assert.equal(light.keyText, '#202124');
  assert.equal(light.accentKeyText, '#202124', 'a light accent: dark text on it');
  assert.deepEqual(P.colors.ops.fromPicture([]), {});
  const made = P.colors.ops.many({ xml: nord, files: {}, layouts: {} }, dark, false);
  const r = read(made.xml);
  assert.equal(r.theme.info(false).colors.accentKey, '#ffff3366');
  assert.equal(F.colors(made.xml, 'dark').accentKey, '#88c0d0', 'the light version shown: the dark one kept');
});

// ---- M3: 栏, 弹出, 面板和窗口, 尺寸, 动效, 信息, 源码 ----

const PNG4 = new Uint8Array(Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAEElEQVR4nGPQCFgARwzEcQC/wxGBfpfKIQAAAABJRU5ErkJggg==', 'base64'));

// a value the form would write of [p] (its kind's middle)
function sampleOf(p) {
  switch (p.kind) {
    case 'color': return '#ff123456';
    case 'dp': return Math.min(p.max ?? 24, Math.max(p.min ?? 0, 2));
    case 'percent': return Math.round(((p.min ?? 0) + (p.max ?? 100)) / 2);
    case 'ms': return Math.round(((p.min ?? 0) + (p.max ?? 600)) / 2);
    case 'choice': return p.options[p.options.length - 1][0];
    case 'flag': return false;
    case 'radius': return 6;
    case 'inset': return [1, 2, 3, 4];
    case 'twin': return [p.min + 10, p.min + 20];
    case 'border': return { width: 1, color: '#ff000000' };
    case 'shadow': return { dx: 0, dy: 1, blur: 2, color: '#33000000' };
    default: return null;
  }
}

test('栏, 弹出, 面板和窗口, 尺寸: every property each part offers, written as its form writes it, is one the engine reads there', () => {
  for (const id of ['bar', 'popup', 'window', 'size']) {
    const ops = P[id].ops;
    for (const part of ops.parts) {
      let d = { xml: nord, files: {}, layouts: {} };
      const props = part.props.filter((p) => sampleOf(p) != null);
      for (const p of props) d = ops.set(d, part.select, p.prop, p.kind, sampleOf(p), p);
      const r = read(d.xml);
      const said = r.warnings.filter((w) => /does nothing|not supported/.test(w.message) && props.some((p) => w.message.startsWith(p.prop + '=')));
      assert.deepEqual(said, [], id + ' ' + part.select);
      for (const p of props) assert.notEqual(F.valueOf(d.xml, part.select, p.prop), null, part.select + ' ' + p.prop);
    }
  }
});

test('the widgets\' values as theme.xml writes them: shortest radius and inset, a size of two orientations, null not set', () => {
  const T = (kind, v, o) => globalThis.YoumoPanels.text(F, kind, v, o);
  assert.equal(T('radius', [4, 4, 4, 4]), '4dp');
  assert.equal(T('inset', [2, 2, 2, 2]), '2dp');
  assert.equal(T('inset', [1, 2, 3, 4]), '1dp 2dp 3dp 4dp');
  assert.equal(T('dp', null), null);
  assert.deepEqual(F.parseInset('1dp 2dp 3dp 4dp'), [1, 2, 3, 4]);
  assert.deepEqual(F.parseRadius('1dp 2dp 3dp 4dp'), [1, 2, 3, 4]);
  assert.equal(F.parseRadius(F.radiusText([4, 4, 0, 0])).join(), '4,4,0,0');
  assert.equal(F.parseInset(F.insetText([1, 2, 3, 4])).join(), '1,2,3,4');
});

test('按键: a key image is a nine-slice fill the engine reads; one past the app\'s limits refused', () => {
  const L = globalThis.YoumoLib;
  const lim = { keyImageSide: 1024, keyImageBytes: 2097152 };
  const made = P.keys.ops.addKeyImage({ xml: nord, files: {}, layouts: {} }, L, lim, 'My Key.PNG', PNG4);
  assert.equal(made.file, 'key-my-key.png');
  assert.deepEqual(P.keys.ops.keyImages(made), ['key-my-key.png']);
  assert.throws(() => P.keys.ops.addKeyImage(made, L, { keyImageSide: 2, keyImageBytes: 99 }, 'a.png', PNG4), /太大/);
  assert.throws(() => P.keys.ops.addKeyImage(made, L, lim, 'a.png', new Uint8Array([1, 2, 3])), /不是/);
  const fill = { kind: 'image', file: made.file, insets: [1, 1, 1, 1], scale: 2, fallback: '#ff0000' };
  const d = P.keys.ops.fill(made, 'key.letter', fill);
  assert.deepEqual(F.parseFill(F.valueOf(d.xml, 'key.letter', 'fill')), fill);
  read(d.xml);
});

test('动效: each property written as the format writes it; whether this engine draws it is the engine\'s answer', () => {
  const ops = P.motion.ops;
  let d = { xml: nord, files: {}, layouts: {} };
  d = ops.set(d, 'key', 'transition', { ms: 120, easing: 'ease-out' });
  d = ops.set(d, 'preview', 'appear', { kind: 'rise', ms: 120 });
  assert.deepEqual(ops.parseTransition(F.valueOf(d.xml, 'key', 'transition')), { ms: 120, easing: 'ease-out' });
  assert.deepEqual(ops.parseAppear(F.valueOf(d.xml, 'preview', 'appear')), { kind: 'rise', ms: 120 });
  const r = E.readTheme(d.xml, null, null);
  assert.ok(r.ok, JSON.stringify(r.problems));
  const ok = ops.supported(E, nord);
  for (const p of ['transition', 'appear']) assert.equal(ok[p], !r.warnings.some((w) => w.message.startsWith(p + '= is not supported')), p);
  assert.equal(ops.sample(d.xml).appear.kind, 'rise');
  const L = globalThis.YoumoLib;
  assert.ok(ops.addEffectImage(d, L, 'Dot.png', PNG4).file.startsWith('effect-'));
  assert.throws(() => ops.addEffectImage(d, L, 'x.jpg', new Uint8Array([0xff, 0xd8, 0xff])));
});

test('动效: every MOTION property, each effect kind and a clamped value, written by the form, reads back as meant and is in its place for the engine', () => {
  const ops = P.motion.ops;
  const L = globalThis.YoumoLib;
  const base = ops.addEffectImage({ xml: nord, files: {}, layouts: {} }, L, 'Dot.png', PNG4);
  const file = base.file;
  const parse = { transition: ops.parseTransition, appear: ops.parseAppear, effect: ops.parseEffect };
  // [prop, value written, value read back], each the form's own: percent and dp as numbers, the rest objects
  const rows = [
    ['transition', { ms: 120, easing: 'ease-out' }, { ms: 120, easing: 'ease-out' }],
    ['press-scale', 95, '95%'],
    ['press-sink', 1, '1dp'],
    ['appear', { kind: 'fade', ms: 200 }, { kind: 'fade', ms: 200 }],
    ['effect', { kind: 'none' }, { kind: 'none' }],
    ['effect', { kind: 'ripple-image', file, radius: 150, ms: 300 }, { kind: 'ripple-image', file, radius: 150, ms: 300 }],
    // past the bounds: count 12 at most, spread 64dp at most
    ['effect', { kind: 'particles', file, count: 40, spread: 99, ms: 400 }, { kind: 'particles', file, count: 12, spread: 64, ms: 400 }],
    // a ripple's radius 10..200 %, its duration 1..600 ms (the engine's)
    ['effect', { kind: 'ripple-image', file, radius: 0, ms: 0 }, { kind: 'ripple-image', file, radius: 10, ms: 1 }],
    ['effect', { kind: 'ripple-image', file, radius: 999, ms: 300 }, { kind: 'ripple-image', file, radius: 200, ms: 300 }],
  ];
  for (const [prop, v, want] of rows) {
    const m = ops.MOTION.find((x) => x.prop === prop);
    for (const [select] of m.selects) {
      const d = ops.set(base, select, prop, v);
      const text = F.valueOf(d.xml, select, prop);
      assert.deepEqual(parse[prop] ? parse[prop](text) : text, want, prop + ' on ' + select);
      const r = E.readTheme(d.xml, null, null);
      assert.ok(r.ok, JSON.stringify(r.problems));
      // "not supported by this version" is this engine's answer (the panel greys the row); "does nothing" would be the form's mistake
      const said = r.warnings.filter((w) => w.message.startsWith(prop + '=') && /does nothing/.test(w.message));
      assert.deepEqual(said, [], prop + ' on ' + select);
    }
  }
});

test('信息: the name and <meta> written in place, taken out when emptied; a preview image the app takes', () => {
  const ops = P.info.ops;
  let d = { xml: nord, files: {}, layouts: {} };
  d = ops.meta(d, 'author', 'Me & you');
  d = ops.meta(d, 'version', '1.0');
  d = ops.name(d, 'my-nord');
  read(d.xml);
  assert.deepEqual(F.meta(d.xml), { author: 'Me & you', version: '1.0' });
  d = ops.meta(d, 'author', '');
  d = ops.meta(d, 'version', null);
  assert.deepEqual(F.meta(d.xml), {});
  const L = globalThis.YoumoLib;
  const withPreview = ops.preview(d, L, PNG4);
  assert.equal(F.meta(withPreview.xml).preview, 'preview.png');
  assert.ok('preview.png' in withPreview.files);
  read(withPreview.xml);
  const none = ops.preview(withPreview, L, null);
  assert.ok(!('preview.png' in none.files) && F.meta(none.xml).preview == null);
  assert.throws(() => ops.preview(d, L, new Uint8Array([0xff, 0xd8, 0xff])), /PNG 或 WebP/);
});

test('源码: styles the form does not know, comments and spacing are kept verbatim through every new panel\'s edits', () => {
  const hand = '  <!-- my own -->\n  <style select="key.letter , key.function"   fill="#010203"/>\n';
  const xml = nord.replace('</theme>', hand + '</theme>');
  let d = { xml, files: {}, layouts: {} };
  for (const id of ['bar', 'popup', 'window', 'size']) {
    const part = P[id].ops.parts[0];
    const p = part.props.find((x) => sampleOf(x) != null);
    d = P[id].ops.set(d, part.select, p.prop, p.kind, sampleOf(p), p);
  }
  d = P.motion.ops.set(d, 'key', 'transition', { ms: 100, easing: 'ease' });
  d = P.info.ops.meta(d, 'license', 'CC0');
  assert.ok(d.xml.includes(hand), 'the hand-written lines as they were');
  read(d.xml);
});

test('the picture changed in place: an attribute and a comment of the user\'s kept', () => {
  const xml = nord.replace('<style', '<picture src="a.png" rotation="0">\n    <!-- mine -->\n    <cut orientation="portrait" file="a.png" note="x"/>\n  </picture>\n  <style');
  const p = F.picture(xml);
  p.brightness = 80;
  p.cuts.portrait = { file: 'b.png', rect: [0, 0, 10, 5] };
  const out = F.setPicture(xml, p);
  const x = out.xml;
  assert.deepEqual(out.gone, [], 'a.png is still the picture\'s src=');
  assert.match(x, /rotation="0"/);
  assert.match(x, /<!-- mine -->/);
  assert.match(x, /note="x"/);
  assert.match(x, /file="b.png"/);
});

test('从背景图取色: one noisy pixel is not the accent; both versions each worked out for its own', () => {
  const ops = P.colors.ops;
  const greys = Array.from({ length: 50 }, () => [120, 120, 120]);
  assert.notEqual(ops.fromPicture(greys.concat([[255, 0, 0]])).accentKey, '#ff0000');
  const both = ops.fromPictureAll({ xml: F.withDark(nord, true), files: {}, layouts: {} }, [[20, 20, 20], [40, 40, 40]]);
  assert.deepEqual(F.variants(both.xml), ['light', 'dark']);
  assert.equal(F.colors(both.xml, 'light').key, '#66ffffff');
  assert.equal(F.colors(both.xml, 'dark').key, '#4d000000');
  read(both.xml);
});

test('an uploaded key or effect image never overwrites a file: a name taken gets -2, one of no letters a name', () => {
  const L = globalThis.YoumoLib;
  const lim = { keyImageSide: 1024, keyImageBytes: 2097152 };
  const one = P.keys.ops.addKeyImage({ xml: nord, files: {}, layouts: {} }, L, lim, 'a.png', PNG4);
  const two = P.keys.ops.addKeyImage(one, L, lim, 'A.png', PNG4);
  assert.equal(two.file, 'key-a-2.png');
  assert.ok('key-a.png' in two.files);
  assert.equal(P.keys.ops.addKeyImage(one, L, lim, '按键.png', PNG4).file, 'key-image.png');
  const e1 = P.motion.ops.addEffectImage(one, L, '火花.png', PNG4);
  assert.equal(e1.file, 'effect-image.png');
  assert.equal(P.motion.ops.addEffectImage(e1, L, '星.png', PNG4).file, 'effect-image-2.png');
});

test('按键音: a sound goes in <sounds> after the icons, one per key, its old file gone; the last taken out takes <sounds> with it', () => {
  const s = P.sounds.ops;
  const wav = new Uint8Array(readFileSync(join(here, 'testdata', 'function.wav')));
  const ogg = new Uint8Array(readFileSync(join(here, 'testdata', 'space.ogg')));
  const icon = '\n  <icon name="star" src="star.svg"/>\n</theme>';
  let d = { xml: nord.replace('</theme>', icon), files: { 'star.svg': new TextEncoder().encode('<svg/>') }, layouts: {} };
  d = s.setSound(d, E, 'letter', 'Tap!.wav', wav);
  d = s.setSound(d, E, 'space', 'x.ogg', ogg);
  assert.deepEqual(F.sounds(d.xml), { letter: 'sound-letter.wav', space: 'sound-space.ogg' });
  assert.ok(d.xml.indexOf('<sounds>') > d.xml.indexOf('<icon '));
  const r = read(d.xml);
  assert.deepEqual(JSON.parse(JSON.stringify(r.theme.info(false).sounds)), [{ key: 'letter', src: 'sound-letter.wav' }, { key: 'space', src: 'sound-space.ogg' }]);
  d = s.setSound(d, E, 'letter', 'b.ogg', ogg);
  assert.equal(F.sounds(d.xml).letter, 'sound-letter.ogg');
  assert.ok(!('sound-letter.wav' in d.files));
  assert.throws(() => s.setSound(d, E, 'enter', 'a.mp3', wav), /extension and its content differ/);
  d = s.removeSound(s.removeSound(d, 'letter'), 'space');
  assert.ok(!/<sounds?\b/.test(d.xml));
  assert.deepEqual(Object.keys(d.files), ['star.svg']);
  // a key a later app knows: kept as it is, taken out alone
  const later = F.setSound(F.setSound(nord, 'future', 'f.wav').xml, 'space', 's.ogg').xml;
  assert.deepEqual(F.removeSound(later, 'space').gone, ['s.ogg']);
  assert.match(F.removeSound(later, 'space').xml, /<sounds>\s*<sound key="future" src="f.wav"\/>\s*<\/sounds>/);
  // a hand-written <sounds/> opens into a pair that reads back
  const empty = F.setSound(nord.replace('</theme>', '  <sounds/>\n</theme>'), 'space', 's.ogg').xml;
  assert.match(empty, /<sounds>\s*<sound key="space" src="s.ogg"\/>\s*<\/sounds>/);
  assert.deepEqual(JSON.parse(JSON.stringify(read(empty).theme.info(false).sounds)), [{ key: 'space', src: 's.ogg' }]);
  // the same format twice: the new file is sound-space-2.ogg, the old one gone (one file left)
  let same = s.setSound({ xml: nord, files: {}, layouts: {} }, E, 'space', 'a.ogg', ogg);
  same = s.setSound(same, E, 'space', 'b.ogg', ogg);
  assert.deepEqual(Object.keys(same.files).filter((f) => f.startsWith('sound-space')), ['sound-space-2.ogg']);
  // replacing keeps a later app's attributes on the <sound>: only src= changes
  const extra = F.setSound(F.setSound(nord, 'space', 'a.ogg').xml.replace('src="a.ogg"', 'src="a.ogg" volume="50"'), 'space', 'b.ogg').xml;
  assert.match(extra, /<sound key="space" src="b.ogg" volume="50"\/>/);
  // a key given twice (the engine refuses that): the one listed is the one changed
  const twice = nord.replace('</theme>', '  <sounds>\n    <sound key="space" src="a.ogg"/>\n    <sound key="space" src="b.ogg"/>\n  </sounds>\n</theme>');
  assert.equal(F.sounds(F.setSound(twice, 'space', 'c.ogg').xml).space, 'c.ogg');
  // a hand-written <sound></sound>: only its open tag changes, its close stays and the file reads back
  const paired = F.setSound(nord.replace('</theme>', '  <sounds>\n    <sound key="space" src="a.ogg"></sound>\n  </sounds>\n</theme>'), 'space', 'b.ogg').xml;
  assert.match(paired, /<sound key="space" src="b.ogg"><\/sound>/);
  assert.deepEqual(JSON.parse(JSON.stringify(read(paired).theme.info(false).sounds)), [{ key: 'space', src: 'b.ogg' }]);
});
