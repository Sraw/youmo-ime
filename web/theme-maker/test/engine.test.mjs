// The theme maker's engine (060-render-p3 W1: lib/keyboard-scene's library, youmo-engine.js) against what the
// JVM's engine and the app make: every snapshot's scene built through the library's facade is the snapshot
// (lib/keyboard-scene's facade/shots.json says how each was asked for), a theme 3.0 made 4.0 is the JVM's, and
// the library's painter draws on a 2D context what the app's ScenePainter draws on its canvas (testdata/paint.txt,
// EnginePaintTest's), line for line.
// Run: ./gradlew :lib:keyboard-scene:youmoEngine, then node --test web/theme-maker/test/engine.test.mjs
// contract: the library's facade (YoumoEngine) and CanvasPainter against the JVM's snapshots and the app's
// drawing, which neither side's own tests can see.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { ENGINE } from '../tools/assemble.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const scene = join(here, '..', '..', '..', 'lib', 'keyboard-scene', 'src');
const data = join(scene, 'commonTest', 'data');
const testdata = join(here, 'testdata');
const read = (...p) => readFileSync(join(...p), 'utf8');
// a built-in layout as the library keeps it (lib/keyboard-scene's builtInFiles, packed): no comments, no indents, its lines kept
const packed = (xml) => xml.replace(/<!--[\s\S]*?-->/g, (c) => '\n'.repeat(c.split('\n').length - 1)).replace(/^[ \t]+|[ \t]+$/gm, '');

// Path2D is the browser's; an icon's path here is its name (the icons below), so a fill of one says which
globalThis.Path2D = class { constructor(d) { this.d = d; } };
const E = createRequire(import.meta.url)(ENGINE).YoumoEngine;
const fixed = E.fixedMeasurer();
const shots = JSON.parse(read(data, 'facade', 'shots.json'));

// a shot's theme as SnapshotTest's ThemeSource makes it (Kotlin's replace: every one; a function, so a `$` put in is text)
const themes = new Map();
function theme(t) {
  const key = JSON.stringify(t);
  if (!themes.has(key)) {
    let text = read(data, t.file);
    if (t.styles || t.fonts) {
      text = text.replaceAll('</colors>', () => '</colors>' + (t.fonts || '')).replaceAll('</theme>', () => [...(t.styles || []), '</theme>'].join('\n'));
    }
    const r = E.readTheme(text, null, null);
    assert.ok(r.ok, JSON.stringify(r.problems));
    themes.set(key, r.theme);
  }
  return themes.get(key);
}

function built(s) {
  const r = s.request ? E.buildPopup(theme(s.theme), s.env, s.request, fixed) : E.buildScene(s.board, theme(s.theme), s.env, fixed);
  assert.ok(r.ok, s.name + ': ' + JSON.stringify(r.problems));
  return r.scene;
}

test('every snapshot is what the library builds of its ask (the JVM wrote them, the library is held to them)', () => {
  const all = [...shots.shots, ...shots.popups];
  // bar-*: T6a's BarSample scenes, built straight from the sample, not asked of the facade
  const names = all.map((s) => s.name + '.txt').sort();
  assert.deepEqual(names, readdirSync(join(data, 'snapshots')).filter((f) => !f.startsWith('bar-')).sort(), 'a shot for every snapshot, each once');
  const wrong = all.filter((s) => built(s).text() !== read(data, 'snapshots', s.name + '.txt')).map((s) => s.name);
  assert.deepEqual(wrong, []);
});

test("a board's default marks are the app's (the JVM recorded them), its own layout's too, a key's first as the punctuation shows it", () => {
  for (const f of readdirSync(join(data, 'default-marks'))) {
    assert.deepEqual(E.defaultMarks(f.slice(0, -5), null), JSON.parse(read(data, 'default-marks', f)), f);
  }
  const own = E.readLayout(read(scene, 'commonMain', 'layouts', 'qwerty.xml'), null);
  assert.ok(own.ok, JSON.stringify(own.problems));
  const mapped = E.defaultMarks(own.layout, { '@': '＠' });
  assert.deepEqual(mapped.shortcuts['full/a:up'], { symbol: '＠' });
  assert.deepEqual(mapped.shortcuts['full/c:down'], { icon: 'content-copy' });
});

test("a scene's keys, slots and size as a page reads them", () => {
  const s = built(shots.shots.find((x) => x.name === 'qwerty-block'));
  const j = s.json();
  assert.equal(j.width, 1080);
  assert.equal(s.width, 1080);
  assert.equal(s.height, j.height);
  const q = j.keys.find((k) => k.id === 'q');
  assert.equal(q.label, 'q');
  assert.equal(q.actions.send, 'q');
  assert.deepEqual(q.bounds.slice(0, 3), [0, 0, 108]);
  assert.ok(q.shape.rect && q.shape.radii.length === 4);
  assert.equal(j.keys[s.keyAt(50, 80)].id, 'q');
  assert.ok(Array.isArray(j.slots) && Array.isArray(j.warnings));
  const space = j.keys.find((k) => k.id === 'space');
  assert.ok(space && space.sound === 'space', JSON.stringify(space));
});

test('a theme 3.0 is made 4.0 as the JVM makes it, its files named alike', () => {
  const c = E.convert30(read(data, 'look-3.0.json'));
  assert.ok(c.ok, JSON.stringify(c.problems));
  assert.equal(c.xml, read(data, 'facade', 'look-3.0.xml'));
  assert.equal(c.name, 'full');
  assert.equal(c.dark, true);
  assert.equal(c.files.map(([a, b]) => a + ' ' + b + '\n').join(''), read(data, 'facade', 'look-3.0-files.txt'));
  // what it makes reads, and draws
  const t = E.readTheme(c.xml, null, null);
  assert.ok(t.ok, JSON.stringify(t.problems));
  assert.ok(E.buildScene('qwerty', t.theme, { width: 1080, height: 702, density: 2.625, dark: true }, fixed).ok);
  const bad = E.convert30('{\n  "version": 3.0,');
  assert.equal(bad.ok, false);
  assert.equal(bad.problems[0].line, 2);
});

test('a theme is written as the JVM writes it; a bad one is its problems with their lines, never a throw', () => {
  const t = E.readTheme(read(data, 'material-light.xml'), null, null);
  assert.ok(t.ok);
  assert.equal(E.writeTheme(t.theme), read(data, 'writer', 'material-light.xml'));
  const info = t.theme.info(false);
  assert.equal(info.name, t.theme.name);
  assert.deepEqual([...t.theme.variants], ['light']);
  assert.match(info.colors.keyboard, /^#[0-9a-f]{8}$/);
  const bad = E.readTheme('<theme xmlns="https://youmo-ime/theme/4" version="4.0">\n<colors>\n<color name="x" value="nope"/>', null, null);
  assert.equal(bad.ok, false);
  assert.ok(bad.problems.length > 0 && bad.problems.every((p) => typeof p.line === 'number' && p.message));
  assert.equal(E.readTheme('', null, null).ok, false);
  // system() colours from the page: a wrong one is a problem, not a throw
  assert.equal(E.readTheme(read(data, 'material-light.xml'), { light: { primary: 'red' } }, null).ok, false);
  assert.ok(E.readTheme(read(data, 'material-light.xml'), { light: { primary: '#ff0000' }, dark: {} }, null).ok);
});

// also: the built-in themes and layouts are what the build put in (lib/keyboard-scene builtInFiles)
test("the app's layouts are the library's, a layout of a page's reads with its own fragments or the app's", () => {
  const dir = join(scene, 'commonMain', 'layouts');
  const files = readdirSync(dir).filter((f) => f.endsWith('.xml')).map((f) => f.slice(0, -4)).sort();
  const boards = files.filter((f) => /<keyboard[\s>]/.test(read(dir, f + '.xml')));
  assert.deepEqual([...E.layouts()], boards);
  for (const f of files) assert.equal(E.layoutSource(f), packed(read(dir, f + '.xml')));
  assert.equal(E.layoutSource('nope'), null);
  assert.equal(E.layout('grouped-bottom'), null, 'a fragment is no board');
  assert.equal(E.layout('qwerty').id, 'qwerty');
  // the app's themes (060-render-p3 T3's files, where there are any)
  const themesDir = join(scene, 'commonMain', 'themes');
  let themeFiles = [];
  try { themeFiles = readdirSync(themesDir).filter((f) => f.endsWith('.xml')).map((f) => f.slice(0, -4)).sort(); } catch (e) { /* none yet */ }
  assert.deepEqual([...E.themes()], themeFiles);
  for (const f of themeFiles) {
    assert.equal(E.themeSource(f), read(themesDir, f + '.xml'));
    // each reads and builds in the library, each of its versions
    const t = E.readTheme(E.themeSource(f), null, null);
    assert.ok(t.ok, f + ': ' + JSON.stringify(t.problems));
    for (const v of t.theme.variants) assert.ok(E.buildScene('qwerty', t.theme, { width: 1080, height: 702, density: 2.625, dark: v === 'dark' }, fixed).ok, f + ' ' + v);
  }

  const k14 = read(dir, 'k14.xml');
  const own = E.readLayout(k14, null);
  assert.ok(own.ok, JSON.stringify(own.problems));
  const light = theme({ file: 'material-light.xml' });
  const env = { width: 1080, height: 702, density: 2.625 };
  assert.equal(E.buildScene(own.layout, light, env, fixed).scene.text(), E.buildScene('k14', light, env, fixed).scene.text());
  // its own bottom row instead of the app's
  const fragment = read(dir, 'grouped-bottom.xml');
  const mine = E.readLayout(k14, { 'grouped-bottom': fragment });
  assert.ok(mine.ok);
  const missing = E.readLayout(k14.replace(/src="grouped-bottom"/g, 'src="nowhere"'), null);
  assert.equal(missing.ok, false);
  assert.match(missing.problems[0].message, /nowhere/);
  assert.equal(E.readLayout(k14, { 'grouped-bottom': '<fragment' }).ok, false);
  assert.equal(E.buildScene('nope', light, env, fixed).ok, false);
  assert.equal(E.buildScene('qwerty', light, { width: 1080 }, fixed).ok, false, 'an environment without its height');
  assert.equal(E.buildScene('qwerty', light, '{"width": 1, "height": 1, "density": 1, "shift": "twice"}', fixed).ok, false);
  assert.equal(E.buildPopup(light, env, { kind: 'nothing' }, fixed).ok, false);
});

// ------------------------------------------------------------------ the painter, on a context that writes down

// one decimal, as EnginePaintTest writes (Kotlin's roundToLong: halves up)
const fixedN = (v, places) => {
  const units = Math.floor(v * 10 ** places + 0.5);
  const sign = units < 0 ? '-' : '';
  const a = String(Math.abs(units)).padStart(places + 1, '0');
  return sign + a.slice(0, a.length - places) + '.' + a.slice(a.length - places);
};
const n = (v) => fixedN(v, 1);
const n2 = (v) => fixedN(v, 2);

function argb(css) {
  const m = /^rgba\((\d+),(\d+),(\d+),([\d.e-]+)\)$/.exec(css);
  if (m) return '#' + [Math.round(Number(m[4]) * 255), Number(m[1]), Number(m[2]), Number(m[3])].map((x) => x.toString(16).padStart(2, '0')).join('');
  if (css === '#000000') return '#ff000000';
  throw new Error('a colour the painter does not write: ' + css);
}

/**
 * A 2D context that writes, as EnginePaintTest's canvas does, a line for each thing drawn, in the device's pixels:
 * a blurred thing is the shadow of what was drawn far off, so its place is the shadow's, its colour the shadow's,
 * its sigma the shadow's blur halved; glyphs stroked and then filled alike are one text with its stroke. Its canvas
 * makes layers (the painter's, a canvas of its document): what is drawn on one is written when it is laid on this,
 * as it is laid (a fill with a shape cut out of it a fill less that shape; at an alpha, its colour's that alpha; as
 * a shadow, the shadow's colour and blur, nothing cut out: Android's blur of the one shape).
 */
class RecordingContext {
  constructor(canvas = RecordingContext.page()) {
    // a line is a string, or an entry ([line] writes it) that a layer laid can still change
    this.lines = [];
    this.canvas = canvas;
    this.s = this.fresh();
    this.stack = [];
    this.path = [];
  }

  // the page's canvas: its size the shots' at most, layers made as a document makes canvases
  static page() {
    return { width: 4000, height: 4000, ownerDocument: { createElement: () => {
      const layer = { width: 0, height: 0 };
      const ctx = new RecordingContext(layer);
      layer.getContext = () => ctx;
      return layer;
    } } };
  }

  fresh() {
    return {
      m: [1, 0, 0, 1, 0, 0], fillStyle: '#000000', strokeStyle: '#000000', lineWidth: 1, lineJoin: 'miter', lineCap: 'butt', miterLimit: 10,
      font: '10px sans-serif', letterSpacing: '0px', shadowColor: 'rgba(0,0,0,0)', shadowBlur: 0, shadowOffsetX: 0, shadowOffsetY: 0,
      globalAlpha: 1, globalCompositeOperation: 'source-over', textAlign: 'start', textBaseline: 'alphabetic', clip: null,
    };
  }

  // the state's members as a context's properties
  static {
    for (const k of ['fillStyle', 'strokeStyle', 'lineWidth', 'lineJoin', 'lineCap', 'miterLimit', 'font', 'letterSpacing', 'shadowColor', 'shadowBlur',
      'shadowOffsetX', 'shadowOffsetY', 'globalAlpha', 'globalCompositeOperation', 'textAlign', 'textBaseline']) {
      Object.defineProperty(this.prototype, k, { get() { return this.s[k]; }, set(v) { this.s[k] = v; } });
    }
  }

  // the lines as EnginePaintTest writes them
  written() { return this.lines.map((l) => (typeof l === 'string' ? l : RecordingContext.line(l))); }

  static line(e) {
    const blur = e.blur != null ? ' blur=' + n(e.blur) : '';
    if (e.t === 'fill') return 'fill ' + RecordingContext.box(e.b) + ' ' + e.color + (e.less ? ' less=' + RecordingContext.box(e.less) : '') + blur;
    return 'text "' + e.text + '" ' + n(e.x) + ',' + n(e.y) + ' w=' + e.w + ' s=' + n(e.size) + ' sx=' + n2(e.sx) + ' ls=' + n2(e.ls) + ' ' + e.color + e.stroke + blur +
      (e.rot !== 0 ? ' rot=' + e.rot : '');
  }

  // a level's own clip is its own (a restore of it writes `unclip`)
  save() {
    this.stack.push(this.s);
    this.s = { ...this.s, m: [...this.s.m], clip: null };
  }

  restore() {
    const clipped = this.s.clip;
    this.s = this.stack.pop();
    if (clipped === 'clip') this.lines.push('unclip');
  }

  getTransform() { const [a, b, c, d, e, f] = this.s.m; return { a, b, c, d, e, f }; }

  setTransform(a, b, c, d, e, f) { this.s.m = typeof a === 'object' ? [a.a, a.b, a.c, a.d, a.e, a.f] : [a, b, c, d, e, f]; }

  transform(a2, b2, c2, d2, e2, f2) {
    const [a, b, c, d, e, f] = this.s.m;
    this.s.m = [a * a2 + c * b2, b * a2 + d * b2, a * c2 + c * d2, b * c2 + d * d2, a * e2 + c * f2 + e, b * e2 + d * f2 + f];
  }

  translate(x, y) { this.transform(1, 0, 0, 1, x, y); }
  scale(x, y) { this.transform(x, 0, 0, y, 0, 0); }
  rotate(r) { this.transform(Math.cos(r), Math.sin(r), -Math.sin(r), Math.cos(r), 0, 0); }

  map(x, y) { const [a, b, c, d, e, f] = this.s.m; return [a * x + c * y + e, b * x + d * y + f]; }

  // a path's points in the device's pixels as they are added (a canvas's path is), by subpath
  beginPath() { this.path = []; }
  moveTo(x, y) { this.path.push([this.map(x, y)]); }
  lineTo(x, y) { if (!this.path.length) this.path.push([]); this.path.at(-1).push(this.map(x, y)); }
  closePath() {}
  rect(x, y, w, h) { this.path.push([this.map(x, y), this.map(x + w, y), this.map(x + w, y + h), this.map(x, y + h)]); }

  arc(cx, cy, r, a0, a1) {
    // its ends and every quarter it passes: its bounds
    const at = [a0, a1];
    for (let q = Math.ceil(a0 / (Math.PI / 2)); q * (Math.PI / 2) < a1; q++) at.push(q * (Math.PI / 2));
    for (const a of at) this.lineTo(cx + r * Math.cos(a), cy + r * Math.sin(a));
  }

  static bounds(points) {
    const xs = points.map((p) => p[0]);
    const ys = points.map((p) => p[1]);
    return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)];
  }

  static box(b) { return [n(b[0]), n(b[1]), n(b[2]), n(b[3])].join(','); }

  static moved(b, dx, dy) { return [b[0] + dx, b[1] + dy, b[2] + dx, b[3] + dy]; }

  // the shadow's way of drawing, where there is one: [colour, dx, dy, sigma]
  shadow() {
    if (this.s.shadowBlur <= 0) return null;
    return [argb(this.s.shadowColor), this.s.shadowOffsetX, this.s.shadowOffsetY, this.s.shadowBlur / 2];
  }

  clip(rule) {
    assert.notEqual(rule, 'evenodd', 'Android clips by a winding path');
    this.lines.push('clip ' + RecordingContext.box(RecordingContext.bounds(this.path.flat())));
    this.s.clip = 'clip';
  }

  clearRect() { this.lines = []; }

  fill(a, rule) {
    if (a instanceof Path2D) return this.icon(a);
    assert.notEqual(a, 'evenodd', 'Path.op takes winding paths: the nonzero rule');
    const b = RecordingContext.bounds(this.path.flat());
    // a shape cut out of what the layer has: the fill there less it
    if (this.s.globalCompositeOperation === 'destination-out') {
      const last = this.lines.at(-1);
      assert.ok(last && last.t === 'fill', 'a cut of nothing');
      last.less = b;
      return;
    }
    if (typeof this.s.fillStyle !== 'string') return this.lines.push('gradient ' + RecordingContext.box(b));
    const sh = this.shadow();
    if (sh) return this.lines.push({ t: 'fill', b: RecordingContext.moved(b, sh[1], sh[2]), color: sh[0], blur: sh[3] });
    this.lines.push({ t: 'fill', b, color: argb(this.s.fillStyle) });
  }

  stroke(p) {
    if (p instanceof Path2D) return this.icon(p);
    const [a, b] = this.s.m;
    this.lines.push('stroke ' + RecordingContext.box(RecordingContext.bounds(this.path.flat())) + ' ' + argb(this.s.strokeStyle) + ' w=' + n(this.s.lineWidth * Math.hypot(a, b)));
  }

  // an icon: its name (its one path's, below), its box the viewport's corners on the device
  icon(p) {
    const view = icons[p.d];
    const corners = [this.map(0, 0), this.map(view.vw, 0), this.map(view.vw, view.vh), this.map(0, view.vh)];
    this.lines.push('icon ' + p.d + ' ' + RecordingContext.box(RecordingContext.bounds(corners)) + ' ' + argb(this.s.fillStyle));
  }

  // a stroked text's line is its stroke's; the fill right after it must be the same text at the same place in the
  // colour set before the stroke, or a fill gone wrong (moved, its colour left as another's) would pass unseen
  fillText(text, x, y) {
    const st = this.stroked;
    this.stroked = null;
    if (st && st.text === text) {
      assert.deepEqual([x, y, [...this.s.m], argb(this.s.fillStyle)], [st.x, st.y, st.m, st.fill], 'the fill of stroked ' + text);
      return;
    }
    this.text(text, x, y, argb(this.s.fillStyle), '');
  }

  strokeText(text, x, y) {
    this.stroked = { text, x, y, m: [...this.s.m], fill: argb(this.s.fillStyle) };
    const [a, b] = this.s.m;
    this.text(text, x, y, argb(this.s.strokeStyle), ' stroke=' + n(this.s.lineWidth * Math.hypot(a, b)));
  }

  // where Paint would have it: the painter moved it by half a letter's spacing (squeezed), along the text's line
  text(text, x, y, color, stroke) {
    const m = /^(\d+) ([\d.e-]+)px /.exec(this.s.font);
    const [a, b, c, d] = this.s.m;
    const sx = Math.hypot(a, b) / Math.hypot(c, d);
    const size = Number(m[2]) * Math.hypot(c, d);
    const spacing = parseFloat(this.s.letterSpacing);
    const angle = Math.atan2(b, a);
    let [px, py] = this.map(x, y);
    px -= Math.cos(angle) * spacing * sx / 2;
    py -= Math.sin(angle) * spacing * sx / 2;
    const sh = this.shadow();
    let blur = null;
    if (sh) { px += sh[1]; py += sh[2]; color = sh[0]; blur = sh[3]; }
    this.lines.push({ t: 'text', text, x: px, y: py, w: m[1], size, sx, ls: spacing / Number(m[2]), color, stroke, blur, rot: Math.round(angle * 180 / Math.PI) });
  }

  // a text's width, for the area a text's layer takes (the painter's textArea): no fonts here, a half em a letter
  measureText(text) { return { width: text.length * (parseFloat(this.s.font.split('px')[0].split(' ').at(-1)) || 10) / 2 }; }

  createLinearGradient() { return { addColorStop() {} }; }

  // a layer laid: its lines here, moved as it is laid, in the shadow's colour and blur or at the alpha laid with
  drawImage(image, sx, sy, sw, sh, dx, dy) {
    const layer = image.getContext ? image.getContext('2d') : null;
    if (!(layer instanceof RecordingContext)) return this.lines.push('image');
    assert.equal(this.s.globalCompositeOperation, 'source-over');
    const [ox, oy] = this.map(dx - sx, dy - sy);
    const shadow = this.shadow();
    for (const l of layer.lines) {
      if (typeof l === 'string') { this.lines.push(l); continue; }
      const e = { ...l };
      if (shadow) {
        Object.assign(e, { color: shadow[0], blur: shadow[3], less: null });
        RecordingContext.shift(e, ox + shadow[1], oy + shadow[2]);
      } else {
        // drawn opaque there, laid at an alpha: that alpha its colour's
        assert.equal(e.color.slice(1, 3), 'ff', 'a layer drawn opaque');
        e.color = '#' + Math.round(this.s.globalAlpha * 255).toString(16).padStart(2, '0') + e.color.slice(3);
        RecordingContext.shift(e, ox, oy);
      }
      this.lines.push(e);
    }
  }

  static shift(e, dx, dy) {
    if (e.t === 'fill') {
      e.b = RecordingContext.moved(e.b, dx, dy);
      if (e.less) e.less = RecordingContext.moved(e.less, dx, dy);
    } else {
      e.x += dx;
      e.y += dy;
    }
  }
}

/**
 * Two lines alike: the same words, each number within a tenth and a little: the app draws in floats, the library in
 * doubles, so a number at a half of a tenth (42.95) may round either way.
 */
function sameLine(a, b) {
  if (a === b) return true;
  if (a == null || b == null) return false;
  const num = /-?\d+(?:\.\d+)?/g;
  if (a.replace(num, '#') !== b.replace(num, '#')) return false;
  const x = a.match(num) || [];
  const y = b.match(num) || [];
  return x.every((v, i) => Math.abs(Number(v) - Number(y[i])) <= 0.1 + 1e-9);
}

// the app's icons by the scene's names (EnginePaintTest writes them): each here a path of its own name
const icons = Object.fromEntries(Object.keys(JSON.parse(read(testdata, 'engine-icons.json'))).map((k) => [k, { vw: 24, vh: 24, paths: [{ d: k }] }]));

test("the library's painter draws what the app's ScenePainter draws, line for line (testdata/paint.txt)", () => {
  const sections = read(testdata, 'paint.txt').split(/^== /m).filter(Boolean);
  assert.ok(sections.length >= 10, 'the shots EnginePaintTest draws');
  const all = Object.fromEntries([...shots.shots, ...shots.popups].map((s) => [s.name, s]));
  const wrong = [];
  for (const section of sections) {
    const [head, ...want] = section.replace(/\n$/, '').split('\n');
    const [name, shot, pressedAt] = head.split(' ');
    const pressed = pressedAt.slice('pressed='.length);
    const ctx = new RecordingContext();
    E.paint(built(all[shot]), ctx, { pressed: pressed === '-' ? null : pressed, icons });
    const got = ctx.written();
    const at = got.findIndex((l, i) => !sameLine(l, want[i]));
    if (at >= 0 || got.length !== want.length) {
      const i = at >= 0 ? at : Math.min(got.length, want.length);
      wrong.push(name + ' line ' + (i + 2) + ':\n  app:     ' + want[i] + '\n  library: ' + got[i]);
    }
  }
  assert.deepEqual(wrong, []);
});

test('a page with no icon or image for an op draws what it can: a nine-slice its fallback colour, nothing for the rest', () => {
  // theme 3.0's space key an image over its key's colour (look-3.0.xml): that colour where the page has no image
  const t = E.readTheme(read(data, 'facade', 'look-3.0.xml'), null, null);
  assert.ok(t.ok, JSON.stringify(t.problems));
  const s = E.buildScene('qwerty', t.theme, { width: 1080, height: 702, density: 2.625, dark: true }, fixed);
  assert.ok(s.ok, JSON.stringify(s.problems));
  const face = s.scene.text().split('\n').find((l) => l.includes('nine-slice') && l.includes('full-space.png') && l.includes(' else='));
  assert.ok(face, 'the space key a nine-slice: ' + s.scene.text().slice(0, 400));
  const none = new RecordingContext();
  assert.ok(E.paint(s.scene, none, null).ok);
  const lines = none.written();
  assert.ok(!lines.some((l) => l === 'image'), 'no image given, none drawn');
  assert.ok(!lines.some((l) => l.startsWith('icon')), 'no icons given, none drawn');
  assert.ok(lines.some((l) => l.startsWith('text')));
  const fallback = / else=(#[0-9a-f]{8})/.exec(face)[1];
  assert.ok(lines.some((l) => l.startsWith('fill ') && l.includes(' ' + fallback)), 'the fallback colour ' + fallback + ' filled');
  // with the image: drawn, its nine pieces
  const some = new RecordingContext();
  assert.ok(E.paint(s.scene, some, { images: { 'full-space.png': { image: {}, width: 72, height: 72 } } }).ok);
  assert.equal(some.written().filter((l) => l === 'image').length, 9);
});

test("the facade's answers to values of the wrong type are problems, never a throw", () => {
  const t = E.readTheme(read(data, 'material-light.xml'), null, null).theme;
  const env = { width: 1080, height: 702, density: 2.625 };
  const scene = E.buildScene('qwerty', t, env, fixed).scene;
  const refused = (r, what) => {
    assert.equal(r.ok, false, what);
    assert.equal(r.problems[0].line, 0, what);
    assert.match(r.problems[0].message, /not /, what);
  };
  refused(E.readTheme(42, null, null), 'a theme not text');
  refused(E.readTheme('<theme/>', 'red', null), 'system colours not an object');
  refused(E.readTheme('<theme/>', { light: { primary: 7 } }, null), 'a system colour not text');
  refused(E.readLayout(null, null), 'a layout not text');
  refused(E.readLayout('<layout/>', { k: 3 }), 'a fragment not text');
  refused(E.buildScene({}, t, env, fixed), 'a layout not one');
  refused(E.buildScene('qwerty', {}, env, fixed), 'a theme not one');
  refused(E.buildScene('qwerty', t, 5, fixed), 'an environment not one');
  refused(E.buildScene('qwerty', t, env, {}), 'a measurer not one');
  refused(E.buildPopup(t, env, null, fixed), 'no request');
  refused(E.buildPopup(t, env, { kind: 'preview', text: 1, keyWidth: 40 }, fixed), 'a preview text not text');
  refused(E.buildPopup(t, env, { kind: 'keys', labels: 'ab', rows: [[0]], columns: 1, selected: 0 }, fixed), 'labels not a list');
  refused(E.buildPopup(t, env, { kind: 'keys', labels: ['a'], rows: [[0.5]], columns: 1, selected: 0 }, fixed), 'a row not whole numbers');
  refused(E.buildPopup(t, env, { kind: 'menu', cells: [{ label: 'a' }], order: [0], selected: '0' }, fixed), 'selected not a number');
  refused(E.paint(scene, new RecordingContext(), { pressed: 3 }), 'pressed not an id or list');
  refused(E.paint(scene, new RecordingContext(), { pressed: ['q', 1] }), 'a pressed id not text');
  refused(E.paint(scene, new RecordingContext(), { fonts: { system: 1 } }), 'a font not text');
  refused(E.paint(scene, new RecordingContext(), { picture: 'p.png' }), 'the picture not a function');
  refused(E.paint({}, new RecordingContext(), null), 'a scene not one');
  refused(E.paint(scene, {}, null), 'a context not one');
  refused(E.canvasMeasurer({}, null), 'a context to measure with not one');
  refused(E.convert30(null), 'a theme 3.0 not text');
  // and a right one still is: the environment as text, a popup of kind none a problem of its own
  assert.ok(E.buildScene('qwerty', t, JSON.stringify(env), fixed).ok);
  assert.equal(E.buildPopup(t, env, { kind: 'nope' }, fixed).problems[0].message, 'no popup of kind "nope"');
  assert.ok(E.paint(scene, new RecordingContext(), { pressed: ['q'] }).ok);
});

test("the bar's sample and the app's views' looks for the maker's preview (M3): of the theme, its height the bar's", () => {
  const light = theme({ file: 'material-light.xml' });
  const env = { width: 1080, height: 702, density: 2.625 };
  const bar = E.buildBar(light, env, fixed);
  assert.ok(bar.ok, JSON.stringify(bar.problems));
  assert.equal(bar.scene.width, 1080);
  assert.equal(bar.scene.height, Math.trunc(40 * 2.625), "the bar's 40dp at 100%");
  const l = E.looks(light, env);
  assert.ok(l.ok);
  for (const k of ['preedit', 'toolbar', 'frame', 'handle', 'sideButton', 'clipboard', 'picker', 'editing', 'voice']) assert.ok(l.looks[k], k);
  assert.match(l.looks.preedit.color, /^#[0-9a-f]{8}$/);
  assert.equal(E.buildBar(light, { width: 1080 }, fixed).ok, false, 'an environment without its height');
});

// 060-theme-sounds-icons: what the maker checks a sound by (SoundFile) and draws a theme's icon from (IconPath, its fill)
test('readSound reads each of the three formats, and says why a file is not one', () => {
  const bytes = (n) => new Uint8Array(readFileSync(join(testdata, n)));
  assert.deepEqual(JSON.parse(JSON.stringify(E.readSound('space.ogg', bytes('space.ogg')).info)), { format: 'ogg', channels: 2, rate: 48000, ms: 120 });
  assert.deepEqual(JSON.parse(JSON.stringify(E.readSound('function.wav', bytes('function.wav')).info)), { format: 'wav', channels: 1, rate: 8000, ms: 100 });
  assert.equal(E.readSound('short.mp3', bytes('short.mp3')).info.rate, 22050);
  // a bare ArrayBuffer, and a view into a larger buffer, read as the same bytes do
  const wav = { format: 'wav', channels: 1, rate: 8000, ms: 100 };
  assert.deepEqual(JSON.parse(JSON.stringify(E.readSound('function.wav', bytes('function.wav').buffer).info)), wav);
  const inner = bytes('function.wav'), outer = new Uint8Array(inner.length + 7);
  outer.set(inner, 3);
  assert.deepEqual(JSON.parse(JSON.stringify(E.readSound('function.wav', outer.subarray(3, 3 + inner.length)).info)), wav);
  const named = E.readSound('short.wav', bytes('short.mp3'));
  assert.equal(named.ok, false);
  assert.match(named.problems[0].message, /extension and its content differ/);
});

test('readIcon gives each path its own colour (CSS order, alpha last) or null, and its d made absolute', () => {
  const r = E.readIcon('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="m2 2h4v4z"/><path fill="#ff000080" d="M0 0H1V1Z"/></svg>');
  assert.ok(r.ok);
  assert.equal(r.icon.paths[0].fill, null);
  assert.match(r.icon.paths[0].d, /^M2 2 ?L6 2/);
  assert.equal(r.icon.paths[1].fill >>> 0, 0x80ff0000);
});
