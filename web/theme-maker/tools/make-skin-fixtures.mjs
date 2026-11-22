// Skins made here for the web import's tests (no third party's skin is in the repository), as the engine's own
// SkinFixtures make them: a Baidu .bds, a Sogou phone skin plain and in its container (sealed with the published key),
// an iFlytek shop .it, a Trime theme with an __include, a ZIP from macOS's Finder, a 仓 .cskin; PNGs real ones.
// Run: node web/theme-maker/tools/make-skin-fixtures.mjs <dir>, or import it.
import { createCipheriv } from 'node:crypto';
import { deflateRawSync, deflateSync, inflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SOGOU_KEY = '5236461AD38503669045162879033623DDBE6F03FF04E3CAD57FFCA350E49ED9';
const SOGOU_IV = 'E07AAD35E090AA038A51FD05DF8C5D0F';
const enc = (s) => new TextEncoder().encode(s);
const cat = (...parts) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) { out.set(p, at); at += p.length; }
  return out;
};
const u32le = (v) => Uint8Array.of(v & 0xff, (v >>> 8) & 0xff, (v >>> 16) & 0xff, (v >>> 24) & 0xff);

// ---- pixels (ARGB, as the engine's Pixels) and PNG ----

export const pixels = (w, h, fill = 0) => ({ w, h, argb: new Uint32Array(w * h).fill(fill >>> 0) });

function paste(to, from, x, y) {
  for (let j = 0; j < from.h; j++) for (let i = 0; i < from.w; i++) if (x + i < to.w && y + j < to.h) to.argb[(y + j) * to.w + x + i] = from.argb[j * from.w + i];
  return to;
}

// a key face: an opaque body of [color] inside a transparent [margin]
const face = (w, h, margin, color) => paste(pixels(w, h), pixels(w - 2 * margin, h - 2 * margin, color), margin, margin);

function gradient(w, h, from, to) {
  const p = pixels(w, h);
  for (let y = 0; y < h; y++) {
    const t = y / h;
    const ch = (s) => Math.trunc(((from >>> s) & 0xff) * (1 - t) + ((to >>> s) & 0xff) * t);
    p.argb.fill(((0xff << 24) | (ch(16) << 16) | (ch(8) << 8) | ch(0)) >>> 0, y * w, (y + 1) * w);
  }
  return p;
}

const CRC = new Uint32Array(256).map((_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
function crc32(b) {
  let c = 0xffffffff;
  for (const x of b) c = CRC[(c ^ x) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
const u32be = (v) => Uint8Array.of(v >>> 24, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff);
const chunk = (type, data) => cat(u32be(data.length), enc(type), data, u32be(crc32(cat(enc(type), data))));

/** [rgba] (`width`×`height`, not premultiplied) as an 8-bit RGBA PNG. */
export function pngOf(width, height, rgba) {
  const raw = new Uint8Array((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) raw.set(rgba.subarray(y * width * 4, (y + 1) * width * 4), y * (width * 4 + 1) + 1);
  const ihdr = cat(u32be(width), u32be(height), Uint8Array.of(8, 6, 0, 0, 0));
  return cat(Uint8Array.of(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 1 })), chunk('IEND', new Uint8Array(0)));
}

/** [p] as a PNG. */
export function png(p) {
  const rgba = new Uint8Array(p.w * p.h * 4);
  p.argb.forEach((c, i) => { rgba[4 * i] = (c >>> 16) & 0xff; rgba[4 * i + 1] = (c >>> 8) & 0xff; rgba[4 * i + 2] = c & 0xff; rgba[4 * i + 3] = c >>> 24; });
  return pngOf(p.w, p.h, rgba);
}

/** An 8-bit RGB or RGBA PNG, not interlaced (as [pngOf] writes them), as ImageData has it: `{width, height, data}`; null for any other. */
export function decodePng(b) {
  if (b.length < 33 || b[0] !== 0x89 || b[1] !== 0x50) return null;
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  const width = dv.getUint32(16);
  const height = dv.getUint32(20);
  const [depth, type, , , interlace] = b.subarray(24, 29);
  const n = type === 6 ? 4 : type === 2 ? 3 : 0;
  if (depth !== 8 || !n || interlace) return null;
  const idat = [];
  for (let at = 8; at + 8 <= b.length;) {
    const len = dv.getUint32(at);
    const t = String.fromCharCode(...b.subarray(at + 4, at + 8));
    if (t === 'IDAT') idat.push(b.subarray(at + 8, at + 8 + len));
    at += 12 + len;
  }
  const raw = inflateSync(cat(...idat));
  const row = width * n;
  const out = new Uint8ClampedArray(width * height * 4);
  const prev = new Uint8Array(row);
  const cur = new Uint8Array(row);
  for (let y = 0; y < height; y++) {
    const f = raw[y * (row + 1)];
    const line = raw.subarray(y * (row + 1) + 1, (y + 1) * (row + 1));
    for (let i = 0; i < row; i++) {
      const a = i >= n ? cur[i - n] : 0;
      const up = prev[i];
      const c = i >= n ? prev[i - n] : 0;
      const p = a + up - c;
      const pa = Math.abs(p - a); const pb = Math.abs(p - up); const pc = Math.abs(p - c);
      const pred = f === 1 ? a : f === 2 ? up : f === 3 ? (a + up) >> 1 : f === 4 ? (pa <= pb && pa <= pc ? a : pb <= pc ? up : c) : 0;
      cur[i] = (line[i] + pred) & 0xff;
    }
    for (let x = 0; x < width; x++) {
      for (let k = 0; k < 3; k++) out[(y * width + x) * 4 + k] = cur[x * n + k];
      out[(y * width + x) * 4 + 3] = n === 4 ? cur[x * n + 3] : 255;
    }
    prev.set(cur);
  }
  return { width, height, data: out };
}

/** What the page's browser does with a skin's images, in node: [decodePng] and [pngOf]. */
export const nodeIo = {
  async decode(bytes) { return decodePng(bytes); },
  async png(img) { return pngOf(img.width, img.height, new Uint8Array(img.rgba.buffer, img.rgba.byteOffset, img.rgba.byteLength)); },
};

function wav(ms, rate = 44100, channels = 1) {
  const n = Math.trunc((rate * ms) / 1000) * channels;
  const out = new Uint8Array(44 + 2 * n);
  const dv = new DataView(out.buffer);
  out.set(enc('RIFF'), 0); dv.setUint32(4, 36 + 2 * n, true); out.set(enc('WAVEfmt '), 8); dv.setUint32(16, 16, true);
  dv.setUint16(20, 1, true); dv.setUint16(22, channels, true); dv.setUint32(24, rate, true); dv.setUint32(28, rate * channels * 2, true);
  dv.setUint16(32, channels * 2, true); dv.setUint16(34, 16, true); out.set(enc('data'), 36); dv.setUint32(40, 2 * n, true);
  for (let i = 0; i < n; i++) dv.setInt16(44 + 2 * i, ((i % 64) - 32) * 500, true);
  return out;
}

// ---- ZIP and Sogou's container ----

/**
 * [entries] (`[name, bytes]`, a name a string or its raw bytes) as a ZIP, deflated unless [stored]; [descriptor]: sizes
 * after the data and no central directory (as some skin tools write them), `'loose'` without the descriptor's signature;
 * [junk] after each file's deflated data, counted in its packed size.
 */
export function zipOf(entries, { stored = false, descriptor = false, junk = new Uint8Array(0) } = {}) {
  const locals = [];
  const centrals = [];
  let at = 0;
  for (const [n, data] of entries) {
    const name = typeof n === 'string' ? enc(n) : n;
    const utf8 = typeof n === 'string' && /[^\x00-\x7f]/.test(n);
    const packed = stored ? data : cat(deflateRawSync(data), junk);
    const crc = crc32(data);
    const flags = (descriptor ? 8 : 0) | (utf8 ? 0x800 : 0);
    const head = new Uint8Array(30);
    const h = new DataView(head.buffer);
    h.setUint32(0, 0x04034b50, true); h.setUint16(4, 20, true); h.setUint16(6, flags, true); h.setUint16(8, stored ? 0 : 8, true);
    if (!descriptor) { h.setUint32(14, crc, true); h.setUint32(18, packed.length, true); h.setUint32(22, data.length, true); }
    h.setUint16(26, name.length, true);
    const sizes = cat(u32le(crc), u32le(packed.length), u32le(data.length));
    const tail = descriptor === 'loose' ? sizes : descriptor ? cat(u32le(0x08074b50), sizes) : new Uint8Array(0);
    locals.push(head, name, packed, tail);
    const cd = new Uint8Array(46);
    const c = new DataView(cd.buffer);
    c.setUint32(0, 0x02014b50, true); c.setUint16(4, 20, true); c.setUint16(6, 20, true); c.setUint16(8, flags, true); c.setUint16(10, stored ? 0 : 8, true);
    c.setUint32(16, crc, true); c.setUint32(20, packed.length, true); c.setUint32(24, data.length, true); c.setUint16(28, name.length, true); c.setUint32(42, at, true);
    centrals.push(cd, name);
    at += 30 + name.length + packed.length + tail.length;
  }
  if (descriptor) return cat(...locals);
  const dir = cat(...centrals);
  const end = new Uint8Array(22);
  const e = new DataView(end.buffer);
  e.setUint32(0, 0x06054b50, true); e.setUint16(8, entries.length, true); e.setUint16(10, entries.length, true); e.setUint32(12, dir.length, true); e.setUint32(16, at, true);
  return cat(...locals, dir, end);
}

/** Sogou's payload of [files] (`[name, bytes]`): a word, the index's bytes, the index, each entry's name (UTF-16LE) and data. */
export function sogouPayload(files) {
  const bodies = files.map(([name, data]) => {
    const n = new Uint8Array(2 * name.length);
    for (let i = 0; i < name.length; i++) { n[2 * i] = name.charCodeAt(i) & 0xff; n[2 * i + 1] = name.charCodeAt(i) >> 8; }
    return cat(u32le(n.length), n, u32le(data.length), data);
  });
  let at = 8 + 4 * files.length;
  const offsets = bodies.map((b) => { const o = at; at += b.length; return o; });
  return cat(u32le(0), u32le(4 * files.length), ...offsets.map(u32le), ...bodies);
}

/** [payload] sealed as Sogou does: `Skin`, a version, AES-256-CBC of its length ([stated], if not the true one) and its zlib. */
export function sogouSeal(payload, stated = payload.length, zlib = deflateSync(payload)) {
  const c = createCipheriv('aes-256-cbc', Buffer.from(SOGOU_KEY, 'hex'), Buffer.from(SOGOU_IV, 'hex'));
  const plain = cat(u32le(stated), zlib);
  return cat(enc('Skin'), u32le(3), c.update(plain), c.final());
}

// ---- the skins ----

const text = enc;
const L = (s) => Object.entries(s);

/** A Baidu skin as SkinFixtures.baidu makes it: a 1080-wide panel, an atlas of key faces, letters on three rows, a sound. */
export function baidu(root = '') {
  const f = {};
  f['Info.txt'] = text('[Info]\nName=Sea Breeze\nAuthor=Ann\nVersion=1\n');
  f['demo.png'] = png(gradient(400, 300, 0xff336699, 0xff99ccff));
  const atlas = pixels(600, 260);
  paste(atlas, face(162, 180, 6, 0xffffffff), 0, 0);
  paste(atlas, face(162, 180, 6, 0xff8899aa), 200, 0);
  paste(atlas, face(162, 180, 6, 0xff4477ff), 400, 0);
  paste(atlas, paste(pixels(40, 40), pixels(20, 30, 0xff102030), 10, 5), 0, 210);
  f[root + 'res/keys.png'] = png(atlas);
  f[root + 'res/keys.til'] = text('[IMG1]\nSOURCE_RECT=0,0,162,180\nINNER_RECT=30,30,102,120\n[IMG2]\nSOURCE_RECT=200,0,162,180\nINNER_RECT=230,30,102,120\n[IMG3]\nSOURCE_RECT=400,0,162,180\n[IMG4]\nSOURCE_RECT=0,210,40,40\n');
  f[root + 'res/panel.png'] = png(gradient(1080, 600, 0xffe0e8f0, 0xff8090a0));
  f[root + 'res/panel.til'] = text('[IMG1]\nSOURCE_RECT=0,0,1080,600\nINNER_RECT=10,10,1060,580\n');
  f[root + 'res/click.wav'] = wav(80);
  f[root + 'port/res/default.css'] = text('[STYLE1]\nNM_IMG=keys,1\nHL_IMG=keys,3\n[STYLE2]\nNM_IMG=keys,2\nHL_IMG=keys,3\n[STYLE3]\nNM_COLOR=222222\nHL_COLOR=ff0000\n'
    + '[STYLE4]\nNM_IMG=panel,1\n[STYLE5]\nNM_COLOR=CC336699\n[STYLE6]\nNM_COLOR=ff6600\n[STYLE7]\nPRESS_SOUND_PATH=click.wav\n[STYLE8]\nNM_IMG=keys,4\n[STYLE9]\nNM_IMG=keys,1\nPRESS_ANIM=1\n');
  f[root + 'port/gen.ini'] = text('[PANEL]\nSIZE=1080,600\nBACK_STYLE=4,5\nSOUND_STYLE=7\n[CAND]\nLAYOUT_NAME=cand1\nVIEW_RECT=0,0,1080,126\n[INPUT]\nFORE_STYLE=6\n');
  f[root + 'port/cand1.cnd'] = text('[CAND]\nBACK_STYLE=5\nFORE_STYLE=3\nFIRST_FORE=6\n');
  f[root + 'port/hint1.pop'] = text('[HINT]\nBACK_ICON=1\n[ICON1]\nBACK_STYLE=1\nFORE_STYLE=3\n');
  let keys = '';
  let n = 0;
  ['qwertyuiop', 'asdfghjkl', 'zxcvbnm'].forEach((row, y) => [...row].forEach((c, x) => {
    n++;
    keys += `[KEY${n}]\nVIEW_RECT=${x * 108 + y * 54},${y * 150},108,150\nBACK_STYLE=1\nFORE_STYLE=8\nCENTER=${c}\n`;
  }));
  for (const [code, style] of [['F38', 2], ['F39', 9], ['F36', 2], ['F10', 2], ['F12', 2]]) {
    n++;
    keys += `[KEY${n}]\nVIEW_RECT=0,450,160,150\nBACK_STYLE=${style}\nFORE_STYLE=3\nCENTER=${code}\n`;
  }
  f[root + 'port/py_26.ini'] = text(keys + '[KEY99]\nVIEW_RECT=0,0,50,50\nBACK_STYLE=8\n');
  return f;
}

/** A Sogou phone skin as SkinFixtures.sogou makes it: two periods (day, night), key classes, a letter of its own. */
export function sogou() {
  const f = {};
  f['phoneTheme.ini'] = text('[General]\nskin_name=Warm Orange\nskin_author=Bo\nskin_version=1\n');
  f['time.ini'] = text('[General]\nSUPPORT_SCREEN=720,1080\n[1080]\nSTATUS_LIST=status1,status2\n[status1]\nSTART=6\nEND=18\n[status2]\nSTART=18,0\nEND=24,6\n');
  f['preview_main.png'] = png(gradient(300, 200, 0xffff9900, 0xffffcc66));
  f['default/sound_android.ini'] = text('[Key]\nsound=tick\n[Key_Space]\nsound=space\n[Key_Enter]\nsound=nothing\n');
  f['default/tick.wav'] = wav(60);
  for (const i of [1, 2]) {
    const r = `1080/status${i}/`;
    const night = i > 1;
    f[r + 'bg.png'] = png(gradient(1080, 700, night ? 0xff202020 : 0xffffe0b0, 0xff805020));
    f[r + 'cand.png'] = png(pixels(40, 126, 0x80ffffff));
    f[r + 'key.png'] = png(face(106, 156, 4, night ? 0xff444444 : 0xfffff5e8));
    f[r + 'key_p.png'] = png(face(106, 156, 4, 0xffffaa44));
    f[r + 'fn.png'] = png(face(106, 156, 4, 0xffddaa77));
    f[r + 'enter.png'] = png(face(106, 156, 4, 0xffff7700));
    f[r + 'q.png'] = png(face(106, 156, 4, 0xff00aa00));
    f[r + 'phoneSkin.ini'] = text('[RootView]\nBG_IMAGE=bg.png,10,1070,10,690\n[CandidateView]\nBG_IMAGE=cand.png\n'
      + '[KeyBg_Default]\nNORMAL=key.png,20,86,20,136\nPRESSED=key_p.png,20,86,20,136\n[KeyBg_Function_Qwerty]\nNORMAL=fn.png,20,86,20,136\n'
      + '[KeyBg_Enter]\nNORMAL=enter.png,20,86,20,136\n[Key_Enter_Qwerty]\nBG_IMAGES=KeyBg_Enter\n[KeyBg_Q]\nNORMAL=q.png\n[Key_Q]\nBG_IMAGES=KeyBg_Q\n'
      + '[TextStyle_Qwerty]\nTEXT_COLOR=0xFF331100\nTEXT_COLOR_PRESSED=0xFFFFFFFF\nTEXT_SIZE=20\n[TextStyle_FuncKey_26char]\nTEXT_COLOR=0xFF553311\n'
      + '[TextStyle_Candidate]\nTEXT_COLOR=0xFF222222\n[TextStyle_Candidate_Highlight]\nTEXT_COLOR=0xFFFF6600\n[TextStyle_Composing]\nTEXT_COLOR=0xFF666666\n');
  }
  return f;
}

// text as iFlytek's shop writes it: \r\n, UTF-8 with a byte-order mark
const utf8 = (s) => cat(Uint8Array.of(0xef, 0xbb, 0xbf), enc(s.replace(/\n/g, '\r\n')));

/** An iFlytek shop skin as XunfeiFixtures.store makes it: one folder, theme_info.ini, a folder per size. */
export function xunfei(top = 'chunri01/') {
  const f = {};
  f[top + 'theme_info.ini'] = utf8('#测试用\n[THEME_INFO]\nPLATFORM=android\nNAME=春日\nAUTHOR=测试\nVERSION=1.0\nPREVIEW=Image_Shot\nRESOLUTION=960x480,1440x720\n');
  for (const size of [480, 720]) {
    const k = size / 240;
    const r = `${top}${size}/`;
    f[r + 'res/board.png'] = png(gradient(size, Math.trunc((size * 5) / 6), 0xff0d1b3e, 0xff1b2b5e));
    f[r + 'res/bar.png'] = png(pixels(8, 50 * k, 0xff14244a));
    f[r + 'res/key.png'] = png(face(33 * k, 48 * k, 1, 0xff2a3f73));
    f[r + 'res/fn.png'] = png(face(33 * k, 48 * k, 1, 0xff1c2c55));
    f[r + 'res/key_down.png'] = png(face(33 * k, 48 * k, 1, 0xff6a8fe0));
    f[r + 'res/fn_down.png'] = png(face(33 * k, 48 * k, 1, 0xff8090a0));
    f[r + 'res/bubble.png'] = png(face(50 * k, 60 * k, 2, 0xff3355aa));
    f[r + 'res/shot.png'] = png(gradient(240, 200, 0xff0d1b3e, 0xff2a3f73));
    f[r + 'layout/image.ini'] = utf8(`#测试用\n[Image_Bar]\nSRC=bar.png\nX_DIVS=0,1\nY_DIVS=${2 * k},${47 * k}\n\n[Image_Layout_Bg]\nSRC=board.png\n\n`
      + `[Image_Key]\nSRC=key.png\nX_DIVS=${10 * k},${23 * k}\nY_DIVS=${15 * k},${33 * k}\n\n[Image_Fn]\nSRC=fn.png\nX_DIVS=${10 * k},${23 * k}\nY_DIVS=${15 * k},${33 * k}\n\n`
      + `[Image_Key_Down]\nSRC=key_down.png\nX_DIVS=${10 * k},${23 * k}\nY_DIVS=${15 * k},${33 * k}\n\n[Image_Fn_Down]\nSRC=fn_down.png\n\n`
      + `[Image_Bubble]\nSRC=bubble.png\nX_DIVS=${15 * k},${35 * k}\nY_DIVS=${15 * k},${40 * k}\n\n[Image_Shot]\nSRC=shot.png\n`);
    const letters = size === 720 ? 'FFFFFFFF' : 'FFEEEEEE';
    f[r + 'layout/style.ini'] = utf8(`[Style_Cand_Word_1_Focused]\nTEXT_COLOR=0xFF3FC1E8\n\n[Style_Cand_Word_1]\nTEXT_COLOR=0xFFF0E68C\n\n[Style_Keyboard_Key_Fore_2]\nTEXT_COLOR=0x${letters}\n\n`
      + '[Style_Keyboard_Key_Fore_2_Press]\nTEXT_COLOR=0xFF0D1B3E\n\n[Style_Keyboard_Key_Fore_1]\nTEXT_COLOR=0xFFB0C4FF\n\n[Style_Balloon_Amplify]\nIMAGE=Image_Bubble\nTEXT_COLOR=0xFF112233\n\n'
      + '[Style_Cand_Default]\nIMAGE=Image_Bar\n\n[Style_Keyboard_Key_2]\nIMAGE=Image_Key\n\n[Style_Keyboard_Key_1]\nIMAGE=Image_Fn\n\n[Style_Keyboard_Key_2_Press]\nIMAGE=Image_Key_Down\n\n'
      + '[Style_Py_9]\nIMAGE=Image_Layout_Bg\n');
  }
  const kb = top + 'layout/layout_test/480/layout/';
  f[kb + 'keyboard.ini'] = utf8('[Keyboard_Test]\nSIZE=90%p,40%p\n');
  f[kb + 'animation.ini'] = utf8('[Anim_Keyboard_Up]\nANIM_TYPE=3\nDURATION=300\n');
  return f;
}

// a 26-key keyboard as Trime themes write one (TrimeFixtures)
const TRIME_KEYS = [
  ...[...'qwertyuiop'].map((c) => `{click: ${c}}`), '{width: 5}', ...[...'asdfghjkl'].map((c) => `{click: ${c}}`), '{width: 5}',
  '{click: Shift_L, width: 15}', ...[...'zxcvbnm'].map((c) => `{click: ${c}}`), '{click: BS, width: 15}', '{click: Keyboard_symbols, width: 12}',
  '{click: Keyboard_number, width: 12}', '{click: comma}', '{click: space, width: 41}', '{click: period}', '{click: Return, width: 15}',
].map((k) => '      - ' + k).join('\n');
const TRIME_BASE = `name: Base
author: Cy
style:
  color_scheme: sea
  round_corner: 4
  horizontal_gap: 2
  vertical_gap: 4
  keyboards: [.default, number]
preset_keys:
  BS: {label: del, send: BackSpace}
preset_color_schemes:
  sea:
    name: Sea
    back_color: 0x003366
    key_back_color: 0x336699
    key_text_color: 0xFFFFFF
    dark_scheme: night
  night:
    name: Night
    back_color: 0x000000
    key_back_color: 0x222222
    key_text_color: 0xEEEEEE
preset_keyboards:
  number:
    width: 25
    keys: [{click: '1'}, {click: '2'}]
  default:
    ascii_mode: 0
    width: 10
    height: 50
    keys:
${TRIME_KEYS}
`;
const TRIME_WAVE = `name: Wave
__include: base:/
style:
  round_corner: 8
preset_color_schemes:
  sea:
    key_text_color: 0xFFCC00
  __patch:
    - {night/key_back_color: 0x111111}
`;

/** A Trime theme built on another by `__include` (TrimeFixtures.wave on base). */
export const trime = () => ({ 'wave.trime.yaml': text(TRIME_WAVE), 'base.yaml': text(TRIME_BASE) });

/** Every fixture's file: `{name: bytes}`. */
export function skinFixtures() {
  const b = baidu();
  const s = sogou();
  const sealed = sogouSeal(sogouPayload(L(s)));
  const macos = Object.fromEntries(L(baidu('')).map(([n, v]) => ['Sea Breeze/' + n, v]));
  return {
    'sea.bds': zipOf(L(b)),
    'warm-plain.ssf': zipOf(L(s)),
    'warm.ssf': sealed,
    'warm-in.zip': zipOf([['skins/warm.ssf', sealed]]),
    // its length said as 1000: the zlib data goes on far past it
    'bomb.ssf': sogouSeal(new Uint8Array(4 << 20), 1000),
    'spring.it': zipOf(L(xunfei())),
    'wave.zip': zipOf(L(trime())),
    'mac.zip': zipOf([...L(macos), ['__MACOSX/Sea Breeze/._Info.txt', text('mac')], ['Sea Breeze/._gen.ini', text('mac')], ['__MACOSX/._Sea Breeze', text('mac')]]),
    'lotus.cskin': zipOf([['jsonnet/main.jsonnet', text('{}')], ['config.yaml', text('name: lotus\n')]]),
  };
}

/** The fixtures written into [dir]: their paths by name. */
export function writeSkinFixtures(dir) {
  mkdirSync(dir, { recursive: true });
  return Object.fromEntries(Object.entries(skinFixtures()).map(([n, b]) => { writeFileSync(join(dir, n), b); return [n, join(dir, n)]; }));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  if (!process.argv[2]) { console.error('usage: make-skin-fixtures.mjs <dir>'); process.exit(2); }
  for (const p of Object.values(writeSkinFixtures(process.argv[2]))) console.log(p);
}
