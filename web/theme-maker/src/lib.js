// youmo-lib: the theme maker's code that touches no page — zip, the theme file, the app's drawing
// rules. Run as it is by the page and by test/format.test.mjs (node:vm), so it uses only what both
// have: TextEncoder/TextDecoder, DecompressionStream, Math.
(function (root) {
  'use strict';

  // ---- errors the page tells apart ----
  class ThemeError extends Error {
    // code: not-theme | newer | older | bad | missing | limit (detail: which) | image (one the app cannot read the size of)
    constructor(code, detail) {
      super(code + (detail ? ': ' + detail : ''));
      this.code = code;
      this.detail = detail || '';
    }
  }

  // ---- CRC-32 ----
  const CRC_TABLE = (() => {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c >>> 0;
    }
    return t;
  })();

  function crc32(bytes) {
    let c = 0xffffffff;
    for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  }

  // ---- zip: written STORED at a fixed time, so the same input is the same bytes ----
  // 2026-01-01 00:00 as DOS time and date, as the app's test fixtures are stamped
  const DOS_TIME = 0;
  const DOS_DATE = ((2026 - 1980) << 9) | (1 << 5) | 1;

  /** entries: [[name, Uint8Array]] → the zip's bytes; names UTF-8 (flag bit 11), CRC and sizes in the local header. */
  function writeZip(entries) {
    const enc = new TextEncoder();
    const parts = [];
    const central = [];
    let offset = 0;
    for (const [name, data] of entries) {
      const nameBytes = enc.encode(name);
      const crc = crc32(data);
      const local = new DataView(new ArrayBuffer(30));
      local.setUint32(0, 0x04034b50, true);
      local.setUint16(4, 10, true); // version needed: 1.0
      local.setUint16(6, 0x0800, true); // UTF-8 names
      local.setUint16(8, 0, true); // STORED
      local.setUint16(10, DOS_TIME, true);
      local.setUint16(12, DOS_DATE, true);
      local.setUint32(14, crc, true);
      local.setUint32(18, data.length, true);
      local.setUint32(22, data.length, true);
      local.setUint16(26, nameBytes.length, true);
      local.setUint16(28, 0, true);
      parts.push(new Uint8Array(local.buffer), nameBytes, data);
      const cd = new DataView(new ArrayBuffer(46));
      cd.setUint32(0, 0x02014b50, true);
      cd.setUint16(4, 20, true); // made by: MS-DOS, 2.0
      cd.setUint16(6, 10, true);
      cd.setUint16(8, 0x0800, true);
      cd.setUint16(10, 0, true);
      cd.setUint16(12, DOS_TIME, true);
      cd.setUint16(14, DOS_DATE, true);
      cd.setUint32(16, crc, true);
      cd.setUint32(20, data.length, true);
      cd.setUint32(24, data.length, true);
      cd.setUint16(28, nameBytes.length, true);
      // extra, comment, disk, internal attributes 0
      cd.setUint32(38, 0, true); // external attributes
      cd.setUint32(42, offset, true);
      central.push(new Uint8Array(cd.buffer), nameBytes);
      offset += 30 + nameBytes.length + data.length;
    }
    const cdSize = central.reduce((s, p) => s + p.length, 0);
    const end = new DataView(new ArrayBuffer(22));
    end.setUint32(0, 0x06054b50, true);
    end.setUint16(8, entries.length, true);
    end.setUint16(10, entries.length, true);
    end.setUint32(12, cdSize, true);
    end.setUint32(16, offset, true);
    const all = parts.concat(central, [new Uint8Array(end.buffer)]);
    const out = new Uint8Array(all.reduce((s, p) => s + p.length, 0));
    let at = 0;
    for (const p of all) {
      out.set(p, at);
      at += p.length;
    }
    return out;
  }

  /** [bytes] inflated, counted as they come: past [max] bytes it stops (a zip bomb is never whole in memory) and throws. */
  async function inflateRaw(bytes, max, what) {
    const reader = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw')).getReader();
    const parts = [];
    let n = 0;
    for (;;) {
      let r;
      try {
        r = await reader.read();
      } catch (e) {
        throw new ThemeError('not-theme', 'bad data in ' + what);
      }
      if (r.done) break;
      n += r.value.length;
      if (n > max) {
        await reader.cancel().catch(() => {});
        throw new ThemeError('not-theme', what + ' is more than its zip says');
      }
      parts.push(r.value);
    }
    const out = new Uint8Array(n);
    let at = 0;
    for (const p of parts) {
      out.set(p, at);
      at += p.length;
    }
    return out;
  }

  /**
   * A zip's entries by its central directory (so an entry with a data descriptor, as the app writes
   * them, reads too): [{name, size, read: async () => Uint8Array}], size what the zip says it unpacks to.
   * STORED and DEFLATE; read never takes more than that size, nor from outside the zip.
   */
  function readZip(bytes) {
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const bad = (why) => {
      throw new ThemeError('not-theme', why);
    };
    let eocd = -1;
    for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 22 - 65535); i--) {
      if (dv.getUint32(i, true) === 0x06054b50) {
        eocd = i;
        break;
      }
    }
    if (eocd < 0) bad('not a zip');
    const count = dv.getUint16(eocd + 10, true);
    let p = dv.getUint32(eocd + 16, true);
    const dec = new TextDecoder();
    const out = [];
    for (let n = 0; n < count; n++) {
      if (p + 46 > bytes.length || dv.getUint32(p, true) !== 0x02014b50) bad('bad central directory');
      const flags = dv.getUint16(p + 8, true);
      const method = dv.getUint16(p + 10, true);
      const crc = dv.getUint32(p + 16, true);
      const packed = dv.getUint32(p + 20, true);
      const size = dv.getUint32(p + 24, true);
      const nameLen = dv.getUint16(p + 28, true);
      const extraLen = dv.getUint16(p + 30, true);
      const commentLen = dv.getUint16(p + 32, true);
      const local = dv.getUint32(p + 42, true);
      if (p + 46 + nameLen > bytes.length) bad('bad central directory');
      const name = dec.decode(bytes.subarray(p + 46, p + 46 + nameLen));
      p += 46 + nameLen + extraLen + commentLen;
      if (flags & 1) bad('encrypted');
      out.push({
        name,
        size,
        async read() {
          if (local + 30 > bytes.length || dv.getUint32(local, true) !== 0x04034b50) bad('bad local header of ' + name);
          const start = local + 30 + dv.getUint16(local + 26, true) + dv.getUint16(local + 28, true);
          if (start + packed > bytes.length) bad(name + ' runs past the zip');
          const raw = bytes.subarray(start, start + packed);
          let data;
          if (method === 0) data = raw.slice();
          else if (method === 8) data = await inflateRaw(raw, size, name);
          else bad('compression ' + method);
          if (data.length !== size) bad(name + ' is not the size its zip says');
          if (crc32(data) !== crc) bad('CRC of ' + name);
          return data;
        },
      });
    }
    return out;
  }

  // ---- the theme file ----
  // format: the youmo-format block (the app's ThemeMakerFormatTest holds it to ThemeFileJson's fields)

  function typeOf(v) {
    if (v === null) return 'null';
    if (Array.isArray(v)) return 'array';
    return typeof v;
  }

  /** [v] checked against the type [t] of [format] (strictly, as the app's Json reads): what is wrong, by path. */
  function check(format, t, v, path) {
    if (t.endsWith('?')) {
      if (v === null) return;
      t = t.slice(0, -1);
    }
    const bad = (why) => {
      throw new ThemeError('bad', path + ': ' + why);
    };
    if (v === null || v === undefined) bad('missing');
    if (t === 'int') {
      if (!Number.isInteger(v) || v < -2147483648 || v > 2147483647) bad('not an int');
      return;
    }
    if (t === 'string') {
      if (typeof v !== 'string') bad('not a string');
      return;
    }
    if (t === 'boolean') {
      if (typeof v !== 'boolean') bad('not a boolean');
      return;
    }
    if (t.startsWith('[')) {
      if (!Array.isArray(v)) bad('not a list');
      v.forEach((x, i) => check(format, t.slice(1, -1), x, path + '[' + i + ']'));
      return;
    }
    if (t.startsWith('{')) {
      if (typeOf(v) !== 'object') bad('not an object');
      for (const k of Object.keys(v)) check(format, t.slice(1, -1), v[k], path + '[' + JSON.stringify(k) + ']');
      return;
    }
    const def = format.types[t];
    if (!def) throw new Error('no type ' + t);
    if (def['@enum']) {
      if (!def['@enum'].includes(v)) bad('not one of ' + def['@enum'].join(', '));
      return;
    }
    if (typeOf(v) !== 'object') bad('not an object');
    let fields = def;
    if (def['@sealed']) {
      if (!Object.prototype.hasOwnProperty.call(def['@sealed'], v.type)) bad('type ' + JSON.stringify(v.type) + ' not known');
      fields = def['@sealed'][v.type];
    }
    for (const k of Object.keys(v)) {
      if (def['@sealed'] && k === 'type') continue;
      if (!Object.prototype.hasOwnProperty.call(fields, k)) bad('field ' + JSON.stringify(k) + ' not known');
    }
    for (const k of Object.keys(fields)) {
      if (!Object.prototype.hasOwnProperty.call(v, k)) bad('field ' + JSON.stringify(k) + ' missing');
      check(format, fields[k], v[k], path + '.' + k);
    }
  }

  /** [v] with its keys in the format's order: the order the app writes (version first). */
  function ordered(format, t, v) {
    if (t.endsWith('?')) {
      if (v === null) return null;
      t = t.slice(0, -1);
    }
    if (t === 'int' || t === 'string' || t === 'boolean') return v;
    if (t.startsWith('[')) return v.map((x) => ordered(format, t.slice(1, -1), x));
    if (t.startsWith('{')) {
      const o = {};
      for (const k of Object.keys(v)) o[k] = ordered(format, t.slice(1, -1), v[k]);
      return o;
    }
    const def = format.types[t];
    if (def['@enum']) return v;
    const o = {};
    let fields = def;
    if (def['@sealed']) {
      o.type = v.type;
      fields = def['@sealed'][v.type];
    }
    for (const k of Object.keys(fields)) o[k] = ordered(format, fields[k], v[k]);
    return o;
  }

  const BOARD_SYNTAX = /^[a-z0-9]+(-[a-z0-9]+)*$/;

  /** A key name read as KeyNames.parse does: {board, key}; one not board/key throws. */
  function parseKeyName(text) {
    const slash = text.indexOf('/');
    if (slash <= 0) throw new ThemeError('bad', 'key name "' + text + '" is not board/key');
    const board = text.substring(0, slash);
    const key = text.substring(slash + 1);
    if (!(board === '*' || BOARD_SYNTAX.test(board))) throw new ThemeError('bad', 'key name "' + text + '" has a bad board');
    if (key.length === 0 || /\s/.test(key)) throw new ThemeError('bad', 'key name "' + text + '" has a bad key');
    return { board, key };
  }

  /** Whether [names] (KeyNames: {roles, positions}) know the name, as KeyNames.isKnown. */
  function isKnownKeyName(names, text) {
    const { board, key } = parseKeyName(text);
    if (board === '*') return names.roles.includes(key);
    const pos = names.positions[board];
    return !!pos && (pos.includes(key) || names.roles.includes(key));
  }

  // the four kinds a key is drawn as, in the theme file's order; every walk over the kinds' styles goes through these
  const KINDS = ['letter', 'function', 'space', 'enter'];
  const kindStyles = (keys) => KINDS.map((k) => keys[k]);

  function inRange(v, lo, hi, what) {
    if (v < lo || v > hi) throw new ThemeError('bad', what + ' ' + v + ' out of ' + lo + '..' + hi);
  }

  /** What ThemeLook refuses as it is made: ranges, quarter turns, a picture shown but none, key names. */
  function checkRanges(format, theme) {
    const L = format.limits;
    const pic = theme.picture;
    const shows = (f) => f && f.type === 'picture';
    const k = theme.keys;
    const kinds = kindStyles(k);
    const showsPicture = theme.base.type === 'picture' || theme.bar.type === 'picture' ||
      kinds.some((s) => shows(s.fill)) || Object.values(k.overrides).some((o) => shows(o.fill));
    if (!pic && showsPicture) throw new ThemeError('bad', 'the picture is shown but there is none');
    const blur = (v, w) => inRange(v, 0, L.blurDp, w + '.blurDp');
    const bright = (v, w) => inRange(v, 0, 100, w + '.brightness');
    const crop = (c, w) => {
      if (c.rotation % 90 !== 0 || c.rotation < 0 || c.rotation > 270) throw new ThemeError('bad', w + ' rotation ' + c.rotation + ' is not a quarter turn');
    };
    if (pic) {
      bright(pic.brightness, 'picture');
      blur(pic.blurDp, 'picture');
      crop(pic.portrait, 'portrait');
      if (pic.landscape) crop(pic.landscape, 'landscape');
    }
    if (theme.bar.type === 'picture') {
      blur(theme.bar.blurDp, 'bar');
      bright(theme.bar.brightness, 'bar');
    }
    const slice = (s, w) => {
      if (s.insets.length !== 4) throw new ThemeError('bad', w + ': insets are left, top, right, bottom');
      if (s.insets.some((x) => x < 0)) throw new ThemeError('bad', w + ': negative insets');
      inRange(s.pxPerDp, 1, L.pxPerDp, w + '.pxPerDp');
    };
    const fill = (f, w) => {
      if (!f) return;
      if (f.type === 'image') slice(f, w);
      if (f.type === 'picture') {
        blur(f.blurDp, w);
        bright(f.brightness, w);
      }
    };
    const pressed = (p, w) => {
      if (p && p.type === 'image') slice(p, w);
    };
    inRange(k.radiusDp, 0, L.radiusDp, 'radiusDp');
    inRange(k.borderDp, 0, L.borderDp, 'borderDp');
    inRange(k.shadowDp, 0, L.shadowDp, 'shadowDp');
    for (const g of ['h', 'v', 'hLand', 'vLand']) inRange(k.gap[g], 0, L.gapDp, 'gap.' + g);
    for (const kind of KINDS) {
      fill(k[kind].fill, kind);
      pressed(k[kind].pressed, kind);
    }
    for (const [name, o] of Object.entries(k.overrides)) {
      parseKeyName(name);
      fill(o.fill, name);
      pressed(o.pressed, name);
    }
    if (!L.weights.includes(theme.text.keyWeight) || !L.weights.includes(theme.text.candidateWeight)) {
      throw new ThemeError('bad', 'a weight not in ' + L.weights.join(', '));
    }
    inRange(theme.text.keyScale, L.keyScale[0], L.keyScale[1], 'keyScale');
  }

  /** The files a theme names, each once, in the order the app exports them (ThemeLook.files). */
  function lookFiles(theme) {
    const out = [];
    const add = (f) => {
      if (f && !out.includes(f)) out.push(f);
    };
    const img = (x) => (x && x.type === 'image' ? x.file : null);
    const p = theme.picture;
    if (p) {
      add(p.src);
      add(p.portrait.file);
      if (p.landscape) add(p.landscape.file);
    }
    const k = theme.keys;
    for (const s of kindStyles(k)) {
      add(img(s.fill));
      add(img(s.pressed));
    }
    for (const o of Object.values(k.overrides)) {
      add(img(o.fill));
      add(img(o.pressed));
    }
    add(theme.text.keyFont);
    add(theme.text.candidateFont);
    return out;
  }

  /** The theme file's text as the app writes it: compact, version first, fields in the app's order. */
  function writeTheme(format, theme) {
    const body = ordered(format, 'ThemeFile', theme);
    return JSON.stringify(Object.assign({ version: format.version }, body));
  }

  const PALETTE_21 = [
    'backgroundColor', 'barColor', 'keyboardColor', 'keyBackgroundColor', 'keyTextColor', 'candidateTextColor',
    'candidateLabelColor', 'candidateCommentColor', 'altKeyBackgroundColor', 'altKeyTextColor',
    'accentKeyBackgroundColor', 'accentKeyTextColor', 'keyPressHighlightColor', 'keyShadowColor',
    'popupBackgroundColor', 'popupTextColor', 'spaceBarColor', 'dividerColor', 'clipboardEntryColor',
    'genericActiveBackgroundColor', 'genericActiveForegroundColor',
  ];

  /** The look of a theme that sets none but its colours (LookDefaults), keys and text by the format's defaults. */
  function defaultLook(format, name, isDark, palette, picture) {
    return {
      name,
      isDark,
      palette,
      picture: picture || null,
      base: { type: picture ? 'picture' : 'color' },
      bar: { type: 'transparent' },
      keys: JSON.parse(JSON.stringify(format.defaults.keys)),
      text: JSON.parse(JSON.stringify(format.defaults.text)),
    };
  }

  /** A 2.1 theme file (an app before 3.0 wrote it) as 3.0: CustomThemeSerializer's from21, the same json. */
  function from21(format, obj) {
    const need = (k) => {
      if (!(k in obj)) throw new ThemeError('bad', 'field ' + k + ' missing');
      return obj[k];
    };
    // as the app's jsonPrimitive.int: a number past an Int is refused, not cut to one
    const int = (v, w) => {
      if (!Number.isInteger(v) || v < -2147483648 || v > 2147483647) throw new ThemeError('bad', w + ' not an int');
      return v;
    };
    const palette = {};
    for (const k of PALETTE_21) palette[k] = int(need(k), k);
    palette.keyBorderColor = palette.keyShadowColor;
    palette.keyHintColor = palette.altKeyTextColor;
    palette.toolbarIconColor = palette.altKeyTextColor;
    let picture = null;
    const image = obj.backgroundImage;
    if (image && typeOf(image) === 'object') {
      let rect = null;
      if (image.cropRect && typeOf(image.cropRect) === 'object') {
        // an edge left out was -1 (the old Rect reader's), not a refused theme
        const side = (k) => (k in image.cropRect ? int(image.cropRect[k], 'cropRect.' + k) : -1);
        rect = { bottom: side('bottom'), left: side('left'), right: side('right'), top: side('top') };
      }
      if (!('srcFilePath' in image) || !('croppedFilePath' in image)) throw new ThemeError('bad', 'backgroundImage without its files');
      picture = {
        src: String(image.srcFilePath),
        portrait: {
          file: String(image.croppedFilePath),
          cropRect: rect,
          rotation: 'cropRotation' in image ? int(image.cropRotation, 'cropRotation') : 0,
        },
        landscape: null,
        brightness: 'brightness' in image ? int(image.brightness, 'brightness') : 70,
        blurDp: 0,
      };
    }
    if (typeof need('name') !== 'string') throw new ThemeError('bad', 'name not a string');
    if (typeof need('isDark') !== 'boolean') throw new ThemeError('bad', 'isDark not a boolean');
    const theme = defaultLook(format, obj.name, obj.isDark, palette, picture);
    checkRanges(format, theme);
    return theme;
  }

  /**
   * A theme file's text read: {theme, migrated}. 3.0 as the app reads it (every field, none more);
   * 2.1 brought to 3.0 as the app does; older ones and newer ones refused, each with its code.
   */
  function readTheme(format, text) {
    let obj;
    try {
      obj = JSON.parse(text);
    } catch (e) {
      throw new ThemeError('not-theme', 'not json');
    }
    if (typeOf(obj) !== 'object') throw new ThemeError('not-theme', 'not an object');
    const version = 'version' in obj ? String(obj.version) : '1.0';
    if (version === format.version) {
      const body = Object.assign({}, obj);
      delete body.version;
      check(format, 'ThemeFile', body, '$');
      checkRanges(format, body);
      return { theme: ordered(format, 'ThemeFile', body), migrated: false };
    }
    if (format.migrates.includes(version)) return { theme: from21(format, obj), migrated: true };
    if (format.older.includes(version)) throw new ThemeError('older', version);
    throw new ThemeError('newer', version);
  }

  /** A file name the app takes as one file in its themes' directory (ThemeFilesManager.importedFile): never a path, a json, or SAVING. */
  function isThemeFileName(name) {
    return isPlainFileName(name) && !name.toLowerCase().endsWith('.json') && name !== '.saving' && utf8Length(name) <= 255;
  }
  /** As the app's isPlainFileName: not empty, not . or .., no slash. */
  function isPlainFileName(name) {
    return name.length > 0 && name !== '.' && name !== '..' && !name.includes('/');
  }
  const utf8Length = (s) => new TextEncoder().encode(s).length;
  /** File(name).name: the last part of a path. */
  const baseName = (name) => name.substring(name.lastIndexOf('/') + 1);
  /**
   * An entry's name as the app's unpacking resolves it (File(dir, name).canonicalPath): the parts
   * left once . and .. are taken; one out of the zip's top refuses the zip, as the app does.
   */
  function entryPath(name) {
    const parts = [];
    for (const x of name.split('/')) {
      if (x === '' || x === '.') continue;
      if (x === '..') {
        if (!parts.length) throw new ThemeError('not-theme', 'entry ' + JSON.stringify(name) + ' outside the zip');
        parts.pop();
      } else parts.push(x);
    }
    return parts;
  }

  /** [theme] with each file name it has as [fn] makes it (ThemeLook.mapFiles). */
  function mapFiles(theme, fn) {
    const t = JSON.parse(JSON.stringify(theme));
    const p = t.picture;
    if (p) {
      p.src = fn(p.src);
      p.portrait.file = fn(p.portrait.file);
      if (p.landscape) p.landscape.file = fn(p.landscape.file);
    }
    const img = (x) => {
      if (x && x.type === 'image') x.file = fn(x.file);
    };
    const k = t.keys;
    for (const s of [...kindStyles(k), ...Object.values(k.overrides)]) {
      img(s.fill);
      img(s.pressed);
    }
    if (t.text.keyFont) t.text.keyFont = fn(t.text.keyFont);
    if (t.text.candidateFont) t.text.candidateFont = fn(t.text.candidateFont);
    return t;
  }

  /**
   * An image file's width and height from its header, before it is decoded (as the app's probe reads
   * them): PNG, JPEG, WebP; null for another, or one too short to say.
   */
  function imageSize(bytes) {
    const n = bytes.length;
    const u16be = (i) => (bytes[i] << 8) | bytes[i + 1];
    const u32be = (i) => ((bytes[i] << 24) | (bytes[i + 1] << 16) | (bytes[i + 2] << 8) | bytes[i + 3]) >>> 0;
    const u16le = (i) => bytes[i] | (bytes[i + 1] << 8);
    const u24le = (i) => bytes[i] | (bytes[i + 1] << 8) | (bytes[i + 2] << 16);
    const type = imageType(bytes);
    if (type === 'png') return n >= 24 ? [u32be(16), u32be(20)] : null;
    if (type === 'jpg') {
      let i = 2;
      while (i + 9 < n) {
        if (bytes[i] !== 0xff) return null;
        const m = bytes[i + 1];
        if (m === 0xff) { i++; continue; }
        if (m === 0xd8 || m === 0x01 || (m >= 0xd0 && m <= 0xd7)) { i += 2; continue; }
        // a frame's start (SOF0..SOF15 but DHT, JPG, DAC): its height, then its width
        if (m >= 0xc0 && m <= 0xcf && m !== 0xc4 && m !== 0xc8 && m !== 0xcc) return [u16be(i + 7), u16be(i + 5)];
        i += 2 + u16be(i + 2);
      }
      return null;
    }
    if (type === 'webp') {
      const tag = String.fromCharCode(bytes[12], bytes[13], bytes[14], bytes[15]);
      if (tag === 'VP8 ' && n >= 30) return [u16le(26) & 0x3fff, u16le(28) & 0x3fff];
      if (tag === 'VP8L' && n >= 25) {
        const b = bytes;
        return [1 + (b[21] | ((b[22] & 0x3f) << 8)), 1 + ((b[22] >> 6) | (b[23] << 2) | ((b[24] & 0x0f) << 10))];
      }
      if (tag === 'VP8X' && n >= 30) return [1 + u24le(24), 1 + u24le(27)];
      return null;
    }
    if (type === 'gif') return n >= 10 ? [u16le(6), u16le(8)] : null;
    return null;
  }

  /**
   * A theme zip opened as the app imports one (ThemeFilesManager.importTheme): refused past
   * ThemeAssetLimits by what the zip says before anything is unpacked (every entry counted, a folder's
   * too), each entry unpacked no bigger than it says; the json at its top (one; a Mac's `._` passed
   * over), every file it names found at the top by its last part (File(name).name; of two entries of a
   * name the later, as unpacking writes it over the first), the pictures' and key images' sizes read
   * from their headers before anything decodes them (PNG, JPEG, WebP, GIF: another is refused, where
   * the app may read it). {theme, migrated, files: {name: Uint8Array}}, the
   * theme's file names their last parts, as the app keeps them.
   */
  async function openZip(format, bytes) {
    const lim = format.limits;
    const all = readZip(bytes);
    let total = 0;
    all.forEach((e, i) => {
      const breach = entryBreach(lim, i + 1, e.size, total);
      if (breach) throw new ThemeError('limit', breach);
      total += e.size;
    });
    const top = new Map();
    for (const e of all) {
      const parts = entryPath(e.name);
      if (parts.length === 1 && !e.name.endsWith('/')) top.set(parts[0], e);
    }
    const jsons = [...top.keys()].filter((n) => n.endsWith('.json') && !n.startsWith('._'));
    // stricter than the app (it takes the first json): a zip of two is not one the app exported
    if (jsons.length !== 1) throw new ThemeError('not-theme', jsons.length + ' json files');
    const read = readTheme(format, new TextDecoder().decode(await top.get(jsons[0]).read()));
    if (Object.keys(read.theme.keys.overrides).length > lim.overrides) throw new ThemeError('limit', 'overrides');
    if (!isPlainFileName(read.theme.name)) throw new ThemeError('bad', 'name ' + JSON.stringify(read.theme.name));
    for (const name of lookFiles(read.theme)) {
      if (!isThemeFileName(baseName(name))) throw new ThemeError('missing', name);
    }
    const theme = mapFiles(read.theme, baseName);
    const files = Object.create(null);
    for (const name of lookFiles(theme)) {
      const e = top.get(name);
      if (!e) throw new ThemeError('missing', name);
      files[name] = await e.read();
    }
    const side = (name, max, what) => {
      const s = imageSize(files[name]);
      if (!s) throw new ThemeError('image', name);
      if (Math.max(s[0], s[1]) > max) throw new ThemeError('limit', what);
    };
    const p = theme.picture;
    if (p) for (const c of [p.portrait, p.landscape]) if (c) side(c.file, lim.pictureSide, 'pictureSide');
    for (const n of keyImageFiles(theme)) {
      side(n, lim.keyImageSide, 'keyImageSide');
      if (files[n].length > lim.keyImageBytes) throw new ThemeError('limit', 'keyImageBytes');
    }
    return { theme, migrated: read.migrated, files };
  }

  /** ThemeAssetLimits.entry: the [count]th entry, [size] bytes, after [total] bytes before it; null when it passes. */
  function entryBreach(lim, count, size, total) {
    if (count > lim.entries) return 'entries';
    if (size > lim.fileBytes) return 'fileBytes';
    if (total + size > lim.totalBytes) return 'totalBytes';
    return null;
  }

  const has = (o, k) => Object.prototype.hasOwnProperty.call(o, k);

  /** The json entry's name in the zip: the theme's name, as the app writes it. */
  const jsonName = (theme) => theme.name + '.json';

  /**
   * The zip of [theme] and its [files] ({name: Uint8Array}), as the app exports one: each file once,
   * the json last; a name the app would not take as a file of its own refused.
   */
  function exportZip(format, theme, files) {
    if (!isPlainFileName(theme.name) || utf8Length(jsonName(theme)) > 255) throw new ThemeError('bad', 'name ' + JSON.stringify(theme.name));
    const names = lookFiles(theme);
    const entries = names.map((n) => {
      if (!isThemeFileName(n)) throw new ThemeError('bad', 'file name ' + JSON.stringify(n));
      if (!has(files, n)) throw new ThemeError('missing', n);
      return [n, files[n]];
    });
    entries.push([jsonName(theme), new TextEncoder().encode(writeTheme(format, theme))]);
    return writeZip(entries);
  }

  /**
   * What the app refuses as it imports (ThemeAssetLimits), checked before a zip is offered: [{what, detail}].
   * Every entry is counted as the app counts it, the json too.
   */
  function limitBreaches(format, theme, files, sizes) {
    const L = format.limits;
    const out = [];
    const names = lookFiles(theme);
    const json = utf8Length(writeTheme(format, theme));
    if (names.length + 1 > L.entries) out.push({ what: 'entries', detail: names.length + 1 });
    let total = json;
    for (const n of names) {
      const len = has(files, n) ? files[n].length : 0;
      total += len;
      if (len > L.fileBytes) out.push({ what: 'fileBytes', detail: n });
    }
    if (total > L.totalBytes) out.push({ what: 'totalBytes', detail: total });
    const overrides = Object.keys(theme.keys.overrides).length;
    if (overrides > L.overrides) out.push({ what: 'overrides', detail: overrides });
    const pic = theme.picture;
    if (pic) {
      for (const c of [pic.portrait, pic.landscape]) {
        const s = c && has(sizes, c.file) ? sizes[c.file] : null;
        if (s && Math.max(s[0], s[1]) > L.pictureSide) out.push({ what: 'pictureSide', detail: c.file });
      }
    }
    for (const n of keyImageFiles(theme)) {
      const s = has(sizes, n) ? sizes[n] : null;
      if (s && Math.max(s[0], s[1]) > L.keyImageSide) out.push({ what: 'keyImageSide', detail: n });
      if (has(files, n) && files[n].length > L.keyImageBytes) out.push({ what: 'keyImageBytes', detail: n });
    }
    return out;
  }

  function keyImageFiles(theme) {
    const out = new Set();
    const img = (x) => x && x.type === 'image' && out.add(x.file);
    const k = theme.keys;
    for (const s of [...kindStyles(k), ...Object.values(k.overrides)]) {
      img(s.fill);
      img(s.pressed);
    }
    return [...out];
  }

  // ---- the app's drawing rules, as ime-core computes them (Float arithmetic: Math.fround each step) ----
  const f = Math.fround;

  function area(l, t, r, b) {
    return [f(l), f(t), f(r), f(b)];
  }

  /**
   * KeyFaceGeometry.of: {face, shadow, border, pressed, radius, oval}; areas [l, t, r, b]. With [own]
   * (a key's Border, the layout's b: default | on | off | special) the four-argument of(): on a face
   * though the keys are flat, off none, special its kind's when flat, default a letter's when flat.
   */
  function keyFace(width, height, kind, keys, landscape, density, margin = true, own) {
    if (own === 'on') return keyFace(width, height, kind, { ...keys, blocks: true }, landscape, density, margin);
    if (own === 'off') return { ...keyFace(width, height, kind, keys, landscape, density, margin), face: null, shadow: null, border: 0, oval: false };
    if (own === 'default' && !keys.blocks) return keyFace(width, height, 'letter', keys, landscape, density, margin);
    width = f(width);
    height = f(height);
    density = f(density);
    const h = !margin ? 0 : f((landscape ? keys.gap.hLand : keys.gap.h) * density);
    const v = !margin ? 0 : f((landscape ? keys.gap.vLand : keys.gap.v) * density);
    const inner = area(h, v, f(width - h), f(height - v));
    if (keys.blocks) {
      const radius = f(keys.radiusDp * density);
      const shadow = keys.shadowDp > 0 ? [inner[0], inner[1], inner[2], Math.min(f(inner[3] + f(keys.shadowDp * density)), height)] : null; // kept within the cell, as the app's
      return { face: inner, shadow, border: f(keys.borderDp * density), pressed: inner, radius, oval: false };
    }
    if (kind === 'space') {
      const minHeight = f(26 * density);
      const hInset = f(10 * density);
      const vInset = height < minHeight ? 0 : Math.min(f(f(height - minHeight) / 2), f(16 * density));
      const bar = area(hInset, vInset, f(width - hInset), f(height - vInset));
      return { face: bar, shadow: null, border: 0, pressed: bar, radius: f(3 * density), oval: false };
    }
    if (kind === 'enter') {
      const size = Math.min(Math.min(width, height), f(35 * density));
      const left = f(f(width - size) / 2);
      const top = f(f(height - size) / 2);
      const circle = area(left, top, f(left + size), f(top + size));
      return { face: circle, shadow: null, border: 0, pressed: circle, radius: f(size / 2), oval: true };
    }
    return { face: null, shadow: null, border: 0, pressed: inner, radius: 0, oval: false };
  }

  /** NineSliceLayout.of: [{src, dst}] for a source of srcW×srcH, insets [l, t, r, b], drawn in target at density. */
  function nineSlice(srcW, srcH, insets, pxPerDp, target, density) {
    const [il, it, ir, ib] = insets;
    const tw = f(target[2] - target[0]);
    const th = f(target[3] - target[1]);
    if (il === 0 && it === 0 && ir === 0 && ib === 0) return [{ src: area(0, 0, srcW, srcH), dst: target.map(f) }];
    const scale = f(f(density) / pxPerDp);
    const fit = (a, b, room) => {
      if (f(a + b) <= room) return [a, b];
      const k = f(room / f(a + b));
      return [f(a * k), f(b * k)];
    };
    const [dl, dr] = fit(f(il * scale), f(ir * scale), tw);
    const [dt, db] = fit(f(it * scale), f(ib * scale), th);
    const sx = [0, il, srcW - ir, srcW].map(f);
    const sy = [0, it, srcH - ib, srcH].map(f);
    // the inner edges on whole pixels, as the app puts them (kotlin.math.round: ties to even): two anti-aliased
    // pieces meeting mid-pixel let what is under the key show through as a line
    const rint = (x) => { const r = Math.round(x); return r - x === 0.5 && r % 2 !== 0 ? r - 1 : r; };
    // a side with no inset keeps the target's own edge
    const inner = (near, far, lo, hi) => {
      const x1 = near === 0 ? lo : Math.min(Math.max(rint(f(lo + near)), lo), hi);
      return [x1, far === 0 ? hi : Math.min(Math.max(rint(f(hi - far)), x1), hi)];
    };
    const dx = [f(target[0]), ...inner(dl, dr, f(target[0]), f(target[2])), f(target[2])];
    const dy = [f(target[1]), ...inner(dt, db, f(target[1]), f(target[3])), f(target[3])];
    const out = [];
    const nonEmpty = (a) => f(a[2] - a[0]) > 0 && f(a[3] - a[1]) > 0;
    for (let r = 0; r < 3; r++) {
      for (let c = 0; c < 3; c++) {
        const src = [sx[c], sy[r], sx[c + 1], sy[r + 1]];
        const dst = [dx[c], dy[r], dx[c + 1], dy[r + 1]];
        if (nonEmpty(src) && nonEmpty(dst)) out.push({ src, dst });
      }
    }
    return out;
  }

  /** PictureWindow.centerCrop: {scale, dx, dy} of a picW×picH picture over a boardW×boardH board. */
  function centerCrop(picW, picH, boardW, boardH) {
    boardW = f(boardW);
    boardH = f(boardH);
    const scale = Math.max(f(boardW / picW), f(boardH / picH));
    return { scale, dx: f(f(boardW - f(picW * scale)) / 2), dy: f(f(boardH - f(picH * scale)) / 2) };
  }

  /** PictureWindow.under: the picture's pixels under [a] of the board. */
  function pictureUnder(p, a) {
    return [
      f(f(a[0] - p.dx) / p.scale), f(f(a[1] - p.dy) / p.scale),
      f(f(a[2] - p.dx) / p.scale), f(f(a[3] - p.dy) / p.scale),
    ];
  }

  // BoxBlur: 0xAARRGGBB ints (signed, as Kotlin's), three box passes across then down
  // the sums a, r, g, b of n pixels as their rounded mean, packed (scalars: the loops run per pixel and allocate nothing)
  function pack(a, r, g, b, n) {
    const ch = (v) => Math.floor((v * 2 + n) / (2 * n));
    return (ch(a) << 24) | (ch(r) << 16) | (ch(g) << 8) | ch(b);
  }

  function blurShrink(pixels, width, height, factor) {
    const w = Math.max(1, Math.floor(width / factor));
    const h = Math.max(1, Math.floor(height / factor));
    const fx = Math.min(factor, width);
    const fy = Math.min(factor, height);
    const n = fx * fy;
    const out = new Int32Array(w * h);
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        let a = 0, r = 0, g = 0, b = 0;
        for (let yy = 0; yy < fy; yy++) {
          for (let xx = 0; xx < fx; xx++) {
            const c = pixels[(y * fy + yy) * width + x * fx + xx];
            a += (c >>> 24) & 0xff; r += (c >>> 16) & 0xff; g += (c >>> 8) & 0xff; b += c & 0xff;
          }
        }
        out[y * w + x] = pack(a, r, g, b, n);
      }
    }
    return out;
  }

  function blurPass(src, dst, width, height, r, horizontal) {
    const lines = horizontal ? height : width;
    const len = horizontal ? width : height;
    const n = 2 * r + 1;
    // a pixel of the line: along a row 1 apart, down a column width apart; past an end, the end's
    const step = horizontal ? 1 : width;
    const last = len - 1;
    for (let line = 0; line < lines; line++) {
      const base = horizontal ? line * width : line;
      let sa = 0, sr = 0, sg = 0, sb = 0;
      for (let i = -r; i <= r; i++) {
        const c = src[base + (i < 0 ? 0 : i > last ? last : i) * step];
        sa += (c >>> 24) & 0xff; sr += (c >>> 16) & 0xff; sg += (c >>> 8) & 0xff; sb += c & 0xff;
      }
      for (let i = 0; i < len; i++) {
        dst[base + i * step] = pack(sa, sr, sg, sb, n);
        const o = i - r, p = i + r + 1;
        const out = src[base + (o < 0 ? 0 : o > last ? last : o) * step];
        const inn = src[base + (p < 0 ? 0 : p > last ? last : p) * step];
        sa += ((inn >>> 24) & 0xff) - ((out >>> 24) & 0xff);
        sr += ((inn >>> 16) & 0xff) - ((out >>> 16) & 0xff);
        sg += ((inn >>> 8) & 0xff) - ((out >>> 8) & 0xff);
        sb += (inn & 0xff) - (out & 0xff);
      }
    }
  }

  /**
   * ThemeAssets.blurRadius: the box radius in the bitmap's pixels for [blurDp] drawn [widthPx] wide
   * (the bitmap [bitmapWidth] wide; widthPx 0: as wide), on its quarter-size copy.
   */
  function blurRadius(blurDp, widthPx, bitmapWidth, density) {
    const pxPerBitmapPx = widthPx > 0 ? f(f(widthPx) / bitmapWidth) : 1;
    return Math.max(1, Math.round(f(f(f(blurDp * f(density)) / pxPerBitmapPx) / BLUR_SHRINK)));
  }
  const BLUR_SHRINK = 4;

  function blur(pixels, width, height, radius) {
    const cur = Int32Array.from(pixels);
    if (radius === 0) return cur;
    const tmp = new Int32Array(cur.length);
    for (let p = 0; p < 3; p++) {
      blurPass(cur, tmp, width, height, radius, true);
      blurPass(tmp, cur, width, height, radius, false);
    }
    return cur;
  }

  /** KeyStyleResolver.resolve: the key's own, then a role's on every board, then its kind. */
  function resolveStyle(keys, board, key, kind, roles) {
    const own = board != null && key != null ? keys.overrides[board + '/' + key] : null;
    const all = key != null && roles.includes(key) ? keys.overrides['*/' + key] : null;
    const style = keys[kind];
    const pick = (field) => (own && own[field]) || (all && all[field]) || null;
    return {
      fill: pick('fill') || style.fill,
      pressed: pick('pressed') || style.pressed,
      label: (own && own.label) || (all && all.label) || 'show',
    };
  }

  /** KeyStyleResolver.imagesOn: the image files a board with keys [names] draws, each once. */
  function imagesOn(keys, board, names, roles) {
    const out = [];
    const add = (fill, pressed) => {
      for (const x of [fill, pressed]) if (x && x.type === 'image' && !out.includes(x.file)) out.push(x.file);
    };
    for (const s of kindStyles(keys)) add(s.fill, s.pressed);
    for (const key of names) {
      if (roles.includes(key)) {
        const o = keys.overrides['*/' + key];
        if (o) add(o.fill, o.pressed);
      }
      const o = keys.overrides[board + '/' + key];
      if (o) add(o.fill, o.pressed);
    }
    return out;
  }

  /** KeyImageBudget: bytes of [images] ([{file, width, height}]) decoded at [sample], a file once. */
  function budgetBytes(images, sample = 1) {
    const seen = new Set();
    let sum = 0;
    for (const im of images) {
      if (seen.has(im.file)) continue;
      seen.add(im.file);
      sum += Math.max(1, Math.floor(im.width / sample)) * Math.max(1, Math.floor(im.height / sample)) * 4;
    }
    return sum;
  }

  function budgetSample(images, limit) {
    let sample = 1;
    while (budgetBytes(images, sample) > limit && sample < 64) sample *= 2;
    return sample;
  }

  // ---- colours: 0xAARRGGBB as a signed int, as the theme file has them ----
  function argb(a, r, g, b) {
    return ((a & 0xff) << 24) | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
  }

  function channels(c) {
    return { a: (c >>> 24) & 0xff, r: (c >>> 16) & 0xff, g: (c >>> 8) & 0xff, b: c & 0xff };
  }

  function toHex(c) {
    return '#' + (c >>> 0).toString(16).padStart(8, '0').toUpperCase();
  }

  /** #AARRGGBB, #RRGGBB (opaque), with or without the #; null when it is neither. */
  function fromHex(text) {
    const m = /^#?([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/.exec(text.trim());
    if (!m) return null;
    const h = m[1].length === 6 ? 'ff' + m[1] : m[1];
    return parseInt(h, 16) | 0;
  }

  function css(c) {
    const { a, r, g, b } = channels(c);
    return 'rgba(' + r + ',' + g + ',' + b + ',' + +(a / 255).toFixed(4) + ')';
  }

  function withAlpha(c, alpha) {
    return (c & 0xffffff) | (Math.round(alpha * 255) << 24);
  }

  // Contrast.ratio's: [fg] drawn over [bg] (its alpha) against bg
  function blend(a, b, t) {
    const x = channels(a);
    const y = channels(b);
    const ch = (p, q) => Math.max(0, Math.min(255, Math.trunc(p + (q - p) * t)));
    return argb(255, ch(x.r, y.r), ch(x.g, y.g), ch(x.b, y.b));
  }

  function over(fg, bg) {
    return blend(bg, fg, channels(fg).a / 255);
  }

  function luminance(c) {
    const { r, g, b } = channels(c);
    const lin = (v) => {
      const s = v / 255;
      return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    };
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
  }

  function contrast(fg, bg) {
    const la = luminance(over(fg, bg));
    const lb = luminance(bg);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  const BLACK = argb(255, 0, 0, 0);
  const WHITE = -1;

  /**
   * The simple view's five colours (bg, key, text, fn, accent) as the 24 a theme has, after the
   * dynamic-colour theme's rule (Theme.Monet): bg the board, the bar and the popups; key the letters
   * and the space; text the keys', the candidates' and the popups'; fn the function keys; accent
   * Return and what is chosen. The rest: comments and the function keys' text at 75 % of the text,
   * the press 12 % of it (20 % dark), the divider 12 %, the shadow and border black at 20 % (40 % dark),
   * Return's text white unless it is under 3:1 on the accent (as the built-in themes have it), then black.
   */
  function deriveSimple(s, isDark) {
    const accentText = contrast(WHITE, s.accent) >= 3 ? WHITE : BLACK;
    const soft = withAlpha(s.text, 0.75);
    const shadow = withAlpha(BLACK, isDark ? 0.4 : 0.2);
    return {
      backgroundColor: s.bg,
      barColor: s.bg,
      keyboardColor: s.bg,
      keyBackgroundColor: s.key,
      keyTextColor: s.text,
      candidateTextColor: s.text,
      candidateLabelColor: s.text,
      candidateCommentColor: soft,
      altKeyBackgroundColor: s.fn,
      altKeyTextColor: soft,
      accentKeyBackgroundColor: s.accent,
      accentKeyTextColor: accentText,
      keyPressHighlightColor: withAlpha(s.text, isDark ? 0.2 : 0.12),
      keyShadowColor: shadow,
      popupBackgroundColor: s.bg,
      popupTextColor: s.text,
      spaceBarColor: s.key,
      dividerColor: withAlpha(s.text, 0.12),
      clipboardEntryColor: s.key,
      genericActiveBackgroundColor: s.accent,
      genericActiveForegroundColor: accentText,
      keyBorderColor: shadow,
      keyHintColor: soft,
      toolbarIconColor: soft,
    };
  }

  /** The five a palette is read back as in the simple view. */
  function simpleOf(p) {
    return { bg: p.backgroundColor, key: p.keyBackgroundColor, text: p.keyTextColor, fn: p.altKeyBackgroundColor, accent: p.accentKeyBackgroundColor };
  }

  // the field each of the five sets as it is (the rest are worked out of them)
  const SIMPLE_FIELD = { bg: 'backgroundColor', key: 'keyBackgroundColor', text: 'keyTextColor', fn: 'altKeyBackgroundColor', accent: 'accentKeyBackgroundColor' };

  /**
   * The simple view's [which] of the five set to [color]: {palette, manual, isDark, set: the fields given a
   * new colour}. isDark follows the background when [autoDark], and is worked out before the rest (the
   * press, shadow and border are dark's or light's); a field in [manual] keeps its colour, but for the one set.
   */
  function simpleEdit(palette, manual, which, color, isDark, autoDark) {
    const dark = which === 'bg' && autoDark ? isDarkColor(color) : isDark;
    const sim = simpleOf(palette);
    sim[which] = color;
    const all = deriveSimple(sim, dark);
    const out = { ...palette };
    const set = [];
    for (const [k, v] of Object.entries(all)) {
      if (!manual.includes(k) || k === SIMPLE_FIELD[which]) {
        out[k] = v;
        set.push(k);
      }
    }
    return { palette: out, manual: manual.filter((k) => k !== SIMPLE_FIELD[which]), isDark: dark, set };
  }

  /** The fields not in [manual] worked out again for [isDark] (the dark box ticked or not): the fields changed. */
  function followDark(palette, manual, isDark) {
    const all = deriveSimple(simpleOf(palette), isDark);
    const set = [];
    for (const [k, v] of Object.entries(all)) {
      if (!manual.includes(k) && palette[k] !== v) {
        palette[k] = v;
        set.push(k);
      }
    }
    return set;
  }

  /**
   * A palette's copy with its lightness turned over: {palette, isDark}, isDark by its new background (a
   * dark theme's copy is a light one). The shadow and border stay black, as deriveSimple has them for
   * the new isDark, unless set by hand ([manual]).
   */
  function darkPalette(palette, manual) {
    const out = {};
    for (const [k, v] of Object.entries(palette)) out[k] = invertLightness(v);
    const isDark = isDarkColor(out.backgroundColor);
    const derived = deriveSimple(simpleOf(out), isDark);
    for (const k of ['keyShadowColor', 'keyBorderColor']) out[k] = manual.includes(k) ? palette[k] : derived[k];
    return { palette: out, isDark };
  }

  /** Whether a colour reads as dark (the theme's isDark guessed from its background). */
  function isDarkColor(c) {
    return luminance(c) < 0.18;
  }

  /** [c] with its lightness turned over (HSL, alpha and hue kept): a palette's dark copy. */
  function invertLightness(c) {
    const { a, r, g, b } = channels(c);
    const R = r / 255, G = g / 255, B = b / 255;
    const max = Math.max(R, G, B), min = Math.min(R, G, B);
    let h = 0, s = 0;
    const l = (max + min) / 2;
    if (max !== min) {
      const d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      h = max === R ? (G - B) / d + (G < B ? 6 : 0) : max === G ? (B - R) / d + 2 : (R - G) / d + 4;
      h /= 6;
    }
    const L = 1 - l;
    const hue = (p, q, t) => {
      if (t < 0) t += 1;
      if (t > 1) t -= 1;
      if (t < 1 / 6) return p + (q - p) * 6 * t;
      if (t < 1 / 2) return q;
      if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
      return p;
    };
    let r2, g2, b2;
    if (s === 0) r2 = g2 = b2 = L;
    else {
      const q = L < 0.5 ? L * (1 + s) : L + s - L * s;
      const p = 2 * L - q;
      r2 = hue(p, q, h + 1 / 3);
      g2 = hue(p, q, h);
      b2 = hue(p, q, h - 1 / 3);
    }
    return argb(a, Math.round(r2 * 255), Math.round(g2 * 255), Math.round(b2 * 255));
  }

  // ---- the picture's crop: a box seen on the turned picture ↔ the source's, as CanHub keeps it ----

  /** A box [l, t, r, b] on the source (srcW×srcH) turned [rotation] clockwise, in the source's own pixels. */
  function cropToSource(box, srcW, srcH, rotation) {
    const [l, t, r, b] = box;
    switch (rotation) {
      case 0: return [l, t, r, b];
      case 90: return [t, srcH - r, b, srcH - l];
      case 180: return [srcW - r, srcH - b, srcW - l, srcH - t];
      case 270: return [srcW - b, l, srcW - t, r];
      default: throw new Error('rotation ' + rotation);
    }
  }

  /** The other way: a box of the source as seen on it turned [rotation] clockwise. */
  function cropFromSource(box, srcW, srcH, rotation) {
    const [l, t, r, b] = box;
    switch (rotation) {
      case 0: return [l, t, r, b];
      case 90: return [srcH - b, l, srcH - t, r];
      case 180: return [srcW - r, srcH - b, srcW - l, srcH - t];
      case 270: return [t, srcW - r, b, srcW - l];
      default: throw new Error('rotation ' + rotation);
    }
  }

  /** The cut's size: the box scaled down, never up, to fit w×h (CanHub's RESIZE_INSIDE). */
  function resizeInside(bw, bh, w, h) {
    if (bw <= w && bh <= h) return [bw, bh];
    const k = Math.min(w / bw, h / bh);
    return [Math.max(1, Math.round(bw * k)), Math.max(1, Math.round(bh * k))];
  }

  /**
   * How a key image is made no bigger than the app uses (3 × the largest key's dp it draws, [largest]),
   * [size] px as it is with [img]'s insets and pxPerDp: null when it is that small already, else
   * {scale, pxPerDp, insets}. Its corners keep their dp while pxPerDp can come down by whole steps;
   * with none (insets all 0) or pxPerDp at 1 already, the image is scaled all the same, the corners
   * (if any) with it.
   */
  function fitKeyImage(size, largest, img) {
    const k = Math.min((3 * largest[0]) / size[0], (3 * largest[1]) / size[1]);
    if (!(k < 1)) return null;
    const corners = img.insets.some((x) => x > 0);
    const p2 = Math.floor(img.pxPerDp * k);
    const scale = corners && p2 >= 1 ? p2 / img.pxPerDp : k;
    const pxPerDp = corners && p2 >= 1 ? p2 : corners ? 1 : img.pxPerDp;
    return { scale, pxPerDp, insets: img.insets.map((x) => Math.floor(x * scale)) };
  }

  /** ThemeColors: the palette field under the keyboard, under the bar (null: none), under a panel, a key's face. */
  const boardField = (keys) => (keys.blocks ? 'backgroundColor' : 'keyboardColor');
  const barField = (bar) => (bar.type === 'color' ? 'barColor' : null);
  const panelField = (keys) => (keys.blocks ? null : 'barColor');
  function faceField(kind, blocks) {
    if (kind === 'space') return blocks ? 'keyBackgroundColor' : 'spaceBarColor';
    return { letter: 'keyBackgroundColor', function: 'altKeyBackgroundColor', enter: 'accentKeyBackgroundColor' }[kind];
  }

  // A source picture may be a phone's photo (the app keeps it as it is); one past these is not decoded
  const SOURCE_SIDE = 12000;
  const SOURCE_PIXELS = 108e6;

  /**
   * How big an image the page decodes, by what it is in the theme ([role]: 'source', 'key' or a cut of
   * the picture): {side, pixels} it must be within (past them the header said enough: a few bytes can
   * say 30000 × 30000, gigabytes once decoded), and {keep}: its bitmap's longer side at most.
   */
  function decodeCaps(format, role) {
    const L = format.limits;
    if (role === 'source') return { side: SOURCE_SIDE, pixels: SOURCE_PIXELS, keep: L.pictureSide };
    const side = role === 'key' ? L.keyImageSide : L.pictureSide;
    return { side, pixels: side * side, keep: side };
  }

  /** What [theme] has the file [name] as: its picture's source, a key image, or else (a cut). */
  function fileRole(theme, name) {
    if (theme.picture && theme.picture.src === name) return 'source';
    return keyImageFiles(theme).includes(name) ? 'key' : 'picture';
  }

  /** Whether an image of [size] (its header's; null: unread) is decoded under [caps]. */
  function decodable(size, caps) {
    return !!size && Math.max(size[0], size[1]) <= caps.side && size[0] * size[1] <= caps.pixels;
  }

  /** The alpha (30 %) a key's colour is turned down to when its face shows the picture: the app lays the colour over it. */
  const SEE_THROUGH_ALPHA = 0x4d;

  /**
   * The palette fields laid over the picture: of each kind whose face shows it, and of a single key's
   * kinds ([kindOf] its name: a kind, or the kinds a role's key is on its boards, the space on all boards a function
   * key on the number board; the app lays each key's own).
   */
  function seeThroughFields(theme, kindOf) {
    const K = theme.keys;
    const out = new Set();
    for (const k of KINDS) if (K[k].fill.type === 'picture') out.add(faceField(k, K.blocks));
    for (const [n, o] of Object.entries(K.overrides)) {
      const kinds = o.fill && o.fill.type === 'picture' ? kindOf(n) : undefined;
      for (const kind of kinds ? [].concat(kinds) : []) out.add(faceField(kind, K.blocks));
    }
    return out;
  }

  /**
   * A colour newly laid over the picture is turned down to SEE_THROUGH_ALPHA (when more opaque); one no
   * longer over it gets its alpha back. [kept]: the fields over the picture last time → the alpha each had
   * (null: none to give back). Changes theme.palette; returns the fields' kept alphas now, for next time.
   */
  function seeThrough(theme, kindOf, kept) {
    const P = theme.palette;
    const used = seeThroughFields(theme, kindOf);
    const next = {};
    for (const field of used) {
      if (field in kept) next[field] = kept[field];
      else if ((P[field] >>> 24) > SEE_THROUGH_ALPHA) {
        next[field] = P[field] >>> 24;
        P[field] = (P[field] & 0xffffff) | (SEE_THROUGH_ALPHA << 24);
      } else next[field] = null;
    }
    for (const [field, a] of Object.entries(kept)) if (!used.has(field) && a !== null) P[field] = (P[field] & 0xffffff) | (a << 24);
    return next;
  }

  /** ThemeColors.dim (DarkenColorFilter(100 − brightness)): the black's alpha (0..255) over the picture at [brightness] percent. */
  function darkenAlpha(brightness) {
    return Math.floor(((100 - brightness) * 255) / 100);
  }

  /** What an image file is, by its first bytes: png, jpg, webp, gif, or null. */
  function imageType(bytes) {
    if (bytes.length > 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) return 'png';
    if (bytes.length > 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'jpg';
    if (bytes.length > 12 && bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[8] === 0x57 && bytes[9] === 0x45) return 'webp';
    if (bytes.length > 6 && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46) return 'gif';
    return null;
  }

  /** What a font file is, by its first bytes: ttf, otf, or null (a collection or anything else). */
  function fontType(bytes) {
    if (bytes.length < 12) return null;
    const tag = (bytes[0] << 24 | bytes[1] << 16 | bytes[2] << 8 | bytes[3]) >>> 0;
    if (tag === 0x00010000 || tag === 0x74727565) return 'ttf';
    if (tag === 0x4f54544f) return 'otf';
    return null;
  }

  /** A short hex of a file's content: names a file the page adds, so new content is a new name. */
  function contentId(bytes) {
    // with the length: two files of one CRC (rare, but then the second would be taken for the first) differ in it as well
    return crc32(bytes).toString(16).padStart(8, '0') + '-' + bytes.length.toString(16);
  }

  root.YoumoLib = {
    ThemeError, crc32, writeZip, readZip, readTheme, writeTheme, openZip, exportZip, lookFiles, keyImageFiles,
    limitBreaches, entryBreach, imageSize, decodeCaps, fileRole, decodable, mapFiles, isThemeFileName, isPlainFileName, baseName, entryPath, fitKeyImage, from21, defaultLook, parseKeyName, isKnownKeyName, checkRanges, ordered, PALETTE_21,
    KINDS, kindStyles, blurRadius, simpleEdit, followDark, darkPalette,
    keyFace, nineSlice, centerCrop, pictureUnder, blurShrink, blur, resolveStyle, imagesOn, budgetBytes, budgetSample,
    argb, channels, toHex, fromHex, css, withAlpha, contrast, over, luminance, deriveSimple, simpleOf, isDarkColor,
    invertLightness, cropToSource, cropFromSource, resizeInside, darkenAlpha, boardField, barField, panelField, faceField, SEE_THROUGH_ALPHA, seeThroughFields, seeThrough, imageType, fontType, contentId,
  };
})(typeof window !== 'undefined' ? window : globalThis);
