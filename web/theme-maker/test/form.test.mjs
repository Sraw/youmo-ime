// The editing's forms (src/form.js and src/panels/, 060-render-p3 M2): each panel's operations write the style text
// meant, find the style of exactly the same select= or add one, keep what they do not know, and the engine reads
// the result; the cascade check names the style the engine says each value comes from.
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
for (const f of ['form.js', 'maker.js']) vm.runInThisContext(readFileSync(join(src, f), 'utf8'), { filename: f });
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
    ['radius', 8, '8dp'], ['border', { width: 1, color: '#33000000' }, '1dp #33000000'], ['border', null, 'none'],
    ['shadow', { dx: 0, dy: 2, blur: 3, color: '@keyShadow' }, '0dp 2dp 3dp @keyShadow'], ['inner-shadow', { dx: 0, dy: 1, blur: 2, color: '#40000000' }, '0dp 1dp 2dp #40000000'],
    ['depth', 3, '3dp'], ['inset', [6, 3], '6dp 3dp'], ['shape', 'circle', 'circle'], ['press', 'ripple', 'ripple'], ['overlay', '#22000000', '#22000000'],
  ];
  for (const [prop, v, text] of all) {
    const out = k.set(doc, 'key.letter', prop, v);
    assert.equal(F.valueOf(out.xml, 'key.letter', prop), text, prop);
    read(out.xml);
  }
  assert.equal(F.valueOf(k.set(doc, 'key', 'radius', null).xml, 'key', 'radius'), null, 'cleared: the attribute gone');
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
  const ttf = new Uint8Array([0, 1, 0, 0, 1, 2, 3]);
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
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47]);
  const icon = t.setIcon(doc, 'backspace', 'png', png);
  assert.match(icon.xml, /<icon name="backspace" src="icon-backspace.png"\/>/);
  assert.ok(icon.xml.lastIndexOf('<icon') > icon.xml.lastIndexOf('<style'), 'icons last');
  assert.equal(read(icon.xml).theme.info(false).icons.backspace, 'icon-backspace.png');
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
  assert.equal(F.valueOf(through.xml, 'key', 'fill'), 'picture(0dp, 100%, #66ffffff)');
  read(through.xml);
  assert.equal(F.valueOf(b.through(through, false).xml, 'key', 'fill'), F.valueOf(nord, 'key', 'fill'), 'off: as it was');
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
  const src = (key, part, pressed) => Object.fromEntries(E.sources('qwerty', r.theme, ENV, key, part, pressed).sources.map((s) => [s.property, s.line]));
  assert.equal(src('q', null, false).fill, lineOf('key.letter'));
  assert.equal(src('q', null, false).radius, lineOf('key'));
  assert.equal(src('q', null, true).overlay, lineOf('qwerty/q:pressed'));
  assert.equal(src('w', null, true).overlay, lineOf('key:pressed'));
  assert.equal(src('enter', null, false).fill, lineOf('key.enter'));
  assert.equal(src('q', 'mark', false).color, lineOf('key mark, key shortcut'));
  assert.equal(src('q', 'label', false).color, lineOf('key'), 'inherited from the key');
  assert.deepEqual(E.sources('qwerty', r.theme, ENV, 'no-such-key', null, false).sources, []);
  // what the panel shows: each property, its value, the style's select= and line
  const rows = P.keys.ops.sources(E, F, { xml: x }, r.theme, 'qwerty', ENV, 'q', null, false);
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
