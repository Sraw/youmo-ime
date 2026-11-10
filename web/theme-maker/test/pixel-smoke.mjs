// The engine's painting in a real browser against the app's on Robolectric (060-render-p3 W1's pixel smoke):
// eight shots in six groups drawn by dev/engine.html in headless Chromium, compared pixel by pixel with the PNGs the app's
// EnginePaintTest drew (app/build/w1-pixel, written by `./gradlew :app:testDebugUnitTest --tests '*EnginePaintTest*'`),
// within what anti-aliasing and two text rasterizers make different. Local only, not CI: it needs a Chromium.
// Run: node web/theme-maker/test/pixel-smoke.mjs [chromium]   (default: Playwright's headless shell, the newest
// in ~/Library/Caches/ms-playwright or ~/.cache/ms-playwright; CHROMIUM=<path> too)
import { execFile } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { inflateSync } from 'node:zlib';
import { serve } from '../dev/serve.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const app = join(here, '..', '..', '..', 'app', 'build', 'w1-pixel');
const out = join(here, '..', '..', '..', 'app', 'build', 'w1-pixel-web');

// [picture, shot, key pressed]: the six groups: plain keys (and one pressed), dark and flat, another board, stroke and shadow, the inner shadow and depth, a popup's icons
const SHOTS = [
  ['qwerty-block', 'qwerty-block', null], ['pressed-qwerty-block', 'qwerty-block', 'q'], ['qwerty-dark-flat', 'qwerty-dark-flat', null], ['nine-block', 'nine-block', null],
  ['qwerty-stroke-shadow', 'qwerty-stroke-shadow', null], ['qwerty-inner-shadow', 'qwerty-inner-shadow', null], ['qwerty-depth', 'qwerty-depth', null],
  ['popup-menu-icons-block', 'popup-menu-icons-block', null],
];
// Two measures, as two rasterizers' text and edges differ in thin lines and a wrong colour or a missing thing
// fills an area: a shot is unlike when more than ROUGH of its pixels are off by more than FAR in a channel
// (edges, glyphs: 0.4-0.8% on the eight), or when more than SOLID of its pixels are in a 5x5 block every pixel
// of which is off by more than NEAR (an area: under 0.01% on the eight; one key shown pressed is 1.4%)
const FAR = 48;
const ROUGH = 0.02;
const NEAR = 16;
const SOLID = 0.002;

export function chromium() {
  const given = process.argv[2] || process.env.CHROMIUM;
  if (given) return given;
  for (const cache of [join(homedir(), 'Library', 'Caches', 'ms-playwright'), join(homedir(), '.cache', 'ms-playwright')]) {
    if (!existsSync(cache)) continue;
    const shells = readdirSync(cache).filter((d) => d.startsWith('chromium_headless_shell-')).sort().reverse();
    for (const d of shells) {
      for (const sub of ['chrome-mac/headless_shell', 'chrome-mac-arm64/headless_shell', 'chrome-linux/headless_shell', 'chrome-headless-shell-mac-arm64/chrome-headless-shell', 'chrome-headless-shell-linux64/chrome-headless-shell']) {
        if (existsSync(join(cache, d, sub))) return join(cache, d, sub);
      }
    }
  }
  return null;
}

/** A PNG's pixels: {width, height, rgba} (8 bits a channel, RGB or RGBA, not interlaced: what both sides write). */
export function decodePng(bytes) {
  let at = 8;
  let width = 0;
  let height = 0;
  let type = 0;
  const idat = [];
  while (at < bytes.length) {
    const len = bytes.readUInt32BE(at);
    const kind = bytes.toString('latin1', at + 4, at + 8);
    const body = bytes.subarray(at + 8, at + 8 + len);
    if (kind === 'IHDR') {
      width = body.readUInt32BE(0);
      height = body.readUInt32BE(4);
      type = body[9];
      if (body[8] !== 8 || body[12] !== 0 || (type !== 2 && type !== 6)) throw new Error('a PNG this does not read: depth ' + body[8] + ', type ' + type);
    } else if (kind === 'IDAT') idat.push(body);
    at += 12 + len;
  }
  const raw = inflateSync(Buffer.concat(idat));
  const bpp = type === 6 ? 4 : 3;
  const stride = width * bpp;
  const px = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? px[y * stride + x - bpp] : 0;
      const b = y > 0 ? px[(y - 1) * stride + x] : 0;
      const c = x >= bpp && y > 0 ? px[(y - 1) * stride + x - bpp] : 0;
      let v = line[x];
      if (filter === 1) v += a;
      else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      px[y * stride + x] = v & 255;
    }
  }
  const rgba = Buffer.alloc(width * height * 4);
  for (let i = 0; i < width * height; i++) {
    for (let k = 0; k < 3; k++) rgba[i * 4 + k] = px[i * bpp + k];
    rgba[i * 4 + 3] = bpp === 4 ? px[i * bpp + 3] : 255;
  }
  return { width, height, rgba };
}

// a pixel over white, as a page shows it: a transparent corner is the same whatever its colour
function over(p, i, k) { return (p.rgba[i * 4 + k] * p.rgba[i * 4 + 3] + 255 * (255 - p.rgba[i * 4 + 3])) / 255; }

/** How unlike two pictures are: {rough, solid}, shares of their pixels (above); both 1 where their sizes differ. */
export function unlike(a, b) {
  if (a.width !== b.width || a.height !== b.height) return { rough: 1, solid: 1 };
  const w = a.width;
  const h = a.height;
  const off = new Uint8Array(w * h);
  let far = 0;
  for (let i = 0; i < w * h; i++) {
    let d = 0;
    for (let k = 0; k < 3; k++) d = Math.max(d, Math.abs(over(a, i, k) - over(b, i, k)));
    if (d > FAR) far++;
    off[i] = d > NEAR ? 1 : 0;
  }
  let solid = 0;
  for (let y = 2; y < h - 2; y++) {
    for (let x = 2; x < w - 2; x++) {
      let all = true;
      for (let dy = -2; dy <= 2 && all; dy++) for (let dx = -2; dx <= 2 && all; dx++) all = off[(y + dy) * w + x + dx] === 1;
      if (all) solid++;
    }
  }
  return { rough: far / (w * h), solid: solid / (w * h) };
}

const alike = (u) => u.rough <= ROUGH && u.solid <= SOLID;
const percent = (u) => (u.rough * 100).toFixed(2) + '% off, ' + (u.solid * 100).toFixed(3) + '% in areas';

// the browser's picture of [shot] with [pressed] held, as dev/engine.html draws it
async function drawn(shell, port, shot, pressed) {
  const url = 'http://127.0.0.1:' + port + '/?shot=' + shot + (pressed ? '&pressed=' + pressed : '');
  const dom = await new Promise((resolve, reject) => {
    execFile(shell, ['--headless', '--disable-gpu', '--hide-scrollbars', '--virtual-time-budget=10000', '--dump-dom', url],
      { maxBuffer: 64 << 20, timeout: 60000 }, (e, stdout) => (e ? reject(e) : resolve(stdout)));
  });
  const m = /<pre id="out">data:image\/png;base64,([^<]*)<\/pre>/.exec(dom);
  if (!m) throw new Error('the page drew nothing: ' + (/<pre id="out">([^<]*)<\/pre>/.exec(dom) || [, '?'])[1].slice(0, 300));
  return Buffer.from(m[1], 'base64');
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const shell = chromium();
  if (!shell) {
    console.log('no headless Chromium (Playwright\'s headless shell, or CHROMIUM=<path>): not run');
    process.exit(2);
  }
  if (!existsSync(app)) {
    console.log('no ' + app + ": ./gradlew :app:testDebugUnitTest --tests '*EnginePaintTest*' first");
    process.exit(2);
  }
  mkdirSync(out, { recursive: true });
  const server = await serve(0);
  const port = server.address().port;
  let failed = 0;
  const pictures = {};
  try {
    for (const [name, shot, pressed] of SHOTS) {
      let web;
      try {
        web = await drawn(shell, port, shot, pressed);
      } catch (e) {
        console.log('FAIL ' + name + ': ' + e.message);
        failed++;
        continue;
      }
      writeFileSync(join(out, name + '.png'), web);
      pictures[name] = decodePng(web);
      const u = unlike(pictures[name], decodePng(readFileSync(join(app, name + '.png'))));
      if (!alike(u)) failed++;
      console.log((alike(u) ? 'ok   ' : 'FAIL ') + name + ': ' + percent(u));
    }
    // the measures see a difference as small as one key pressed: else every shot is alike for nothing
    if (pictures['qwerty-block']) {
      const u = unlike(pictures['qwerty-block'], decodePng(readFileSync(join(app, 'pressed-qwerty-block.png'))));
      if (alike(u)) failed++;
      console.log((alike(u) ? 'FAIL ' : 'ok   ') + 'the plain board is unlike the app\'s with q pressed: ' + percent(u));
    }
  } finally {
    server.close();
  }
  console.log((failed ? failed + ' failed' : 'all as they should be') + "; the browser's pictures are in " + out);
  process.exit(failed ? 1 : 0);
}
