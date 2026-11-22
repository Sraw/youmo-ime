// youmo-lib: run as is by the page and by node:vm, so it uses only what both have (TextEncoder, DecompressionStream, Math).
(function (root) {
  'use strict';

  class ThemeError extends Error {
    constructor(code, detail) {
      super(code + (detail ? ': ' + detail : ''));
      this.code = code;
      this.detail = detail || '';
    }
  }

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

  // zip written STORED at a fixed time so the same input gives the same bytes, as the app's test fixtures are stamped
  const DOS_TIME = 0;
  const DOS_DATE = ((2026 - 1980) << 9) | (1 << 5) | 1;

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

  /** Past [max] bytes it stops and throws: a zip bomb is never whole in memory. */
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

  /** By the central directory, so an entry with a data descriptor (as the app writes them) reads too. */
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

  /** A skin refused, by the name of the engine's SkinError.Reason: the page's own refusals and the engine's said alike. */
  class SkinError extends Error {
    constructor(reason, detail = '') {
      super((reason + ' ' + detail).trim());
      this.reason = reason;
      this.detail = detail;
    }
  }

  function joined(parts, n) {
    const out = new Uint8Array(n);
    let at = 0;
    for (const p of parts) {
      out.set(p, at);
      at += p.length;
    }
    return out;
  }

  /**
   * [raw] inflated as SkinZip inflates a skin's file: past [cap] bytes, or past [lim]'s ratio to [packed] once over its
   * floor, it stops (UNPACKED_TOO_BIG); a stream bad or cut short is NOT_ZIP.
   */
  async function inflateSkin(raw, cap, packed, name, lim, size = -1) {
    const reader = new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw')).getReader();
    const parts = [];
    let n = 0;
    for (;;) {
      let r;
      try {
        r = await reader.read();
      } catch (e) {
        // bytes after the stream's end: the app's Inflater stops at its end and never sees them
        if (n === size) break;
        throw new SkinError('NOT_ZIP', name);
      }
      if (r.done) break;
      n += r.value.length;
      if (n > cap || (n > lim.ratioFrom && n > packed * lim.ratio)) {
        await reader.cancel().catch(() => {});
        throw new SkinError('UNPACKED_TOO_BIG', name);
      }
      parts.push(r.value);
    }
    return joined(parts, n);
  }

  const STRICT_UTF8 = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });

  // UTF-8 if flagged or valid, else GB18030 (Chinese tools' ZIPs); one leading out of the skin refused, not cleaned
  function skinName(raw, flags) {
    let text;
    if (flags & 0x800) text = new TextDecoder('utf-8', { ignoreBOM: true }).decode(raw);
    else {
      try {
        text = STRICT_UTF8.decode(raw);
      } catch (e) {
        text = new TextDecoder('gb18030', { ignoreBOM: true }).decode(raw);
      }
    }
    const name = text.replace(/\\/g, '/');
    const parts = name.split('/');
    if (name.startsWith('/') || /^[A-Za-z]:/.test(name) || parts.includes('..') || /[\u0000-\u001f]/.test(name)) throw new SkinError('UNSAFE_NAME', name);
    return parts.filter((x) => x && x !== '.').join('/') + (name.endsWith('/') ? '/' : '');
  }

  /**
   * A skin's ZIP as SkinZip.read unpacks it: `[{name, bytes}]`, `/`-separated, no folders, the first of a name twice.
   * By its central directory, else entry by entry from the start (skin tools write odd ZIPs); held to [lim] (the
   * engine's skinFormat()), [budget] all of them together. Throws a SkinError.
   */
  async function readSkinZip(bytes, lim, budget = lim.totalBytes) {
    const n = bytes.length;
    if (n > lim.archiveBytes) throw new SkinError('TOO_BIG');
    if (n >= 4 && bytes[0] === 0x53 && bytes[1] === 0x6b && bytes[2] === 0x69 && bytes[3] === 0x6e) throw new SkinError('SOGOU_ENCRYPTED');
    const u16 = (at) => (at < 0 || at + 2 > n ? 0 : bytes[at] | (bytes[at + 1] << 8));
    const u32 = (at) => (at < 0 || at + 4 > n ? -1 : u16(at) + u16(at + 2) * 65536);
    const LOCAL = 0x04034b50;
    const name = (at, len, flags) => skinName(bytes.subarray(at, at + len), flags);

    // null where there is none to read, or one that says none (a tool left it empty)
    const central = () => {
      let end = -1;
      for (let i = n - 22; i >= Math.max(0, n - 22 - 0xffff); i--) if (u32(i) === 0x06054b50) { end = i; break; }
      if (end < 0) return null;
      const count = u16(end + 10);
      const at = u32(end + 16);
      if (count > lim.entries) throw new SkinError('TOO_MANY_FILES');
      if (count === 0 || at < 0 || at >= end) return null;
      const out = [];
      let p = at;
      for (let i = 0; i < count; i++) {
        if (p + 46 > n || u32(p) !== 0x02014b50) return null;
        const flags = u16(p + 8);
        const method = u16(p + 10);
        const packed = u32(p + 20);
        const nameLen = u16(p + 28);
        if (p + 46 + nameLen > n) return null;
        const nm = name(p + 46, nameLen, flags);
        const local = u32(p + 42);
        if (local < 0 || local + 30 > n || u32(local) !== LOCAL) return null;
        const data = local + 30 + u16(local + 26) + u16(local + 28);
        if (packed < 0 || data + packed > n) return null;
        out.push({ name: nm, flags, method, data, packed, size: u32(p + 24) });
        p += 46 + nameLen + u16(p + 30) + u16(p + 32);
      }
      return out;
    };

    // where a deflated file whose sizes follow it ends: the first descriptor that gives its own packed size (the first, so
    // that the entries one after another are read in one pass over the file, not each to its end)
    const descriptor = (data) => {
      for (let q = data; q + 12 <= n; q++) {
        if (u32(q) === 0x08074b50 && q + 16 <= n && u32(q + 8) === q - data) return [q, 16];
        if (u32(q + 4) === q - data) {
          const next = u32(q + 12);
          if (q + 12 === n || next === LOCAL || next === 0x02014b50 || next === 0x06054b50) return [q, 12];
        }
      }
      throw new SkinError('NOT_ZIP');
    };

    // entries read one after another from the start: a ZIP whose directory is broken or missing
    const local = async () => {
      const out = [];
      let held = 0;
      let p = 0;
      while (p + 30 <= n && u32(p) === LOCAL) {
        if (out.length >= lim.entries) throw new SkinError('TOO_MANY_FILES');
        const flags = u16(p + 6);
        const method = u16(p + 8);
        const nameLen = u16(p + 26);
        const data = p + 30 + nameLen + u16(p + 28);
        if (data > n) throw new SkinError('NOT_ZIP');
        const nm = name(p + 30, nameLen, flags);
        let packed = u32(p + 18);
        let inflated = null;
        let after = 0;
        if (flags & 8) {
          if (method !== 8) throw new SkinError('NOT_ZIP');
          if (flags & 1) throw new SkinError('ENCRYPTED', nm);
          const [end, size] = descriptor(data);
          packed = end - data;
          inflated = await inflateSkin(bytes.subarray(data, end), Math.min(lim.entryBytes, budget - held), packed, nm, lim, u32(end + size - 4));
          held += inflated.length;
          after = size;
        }
        if (packed < 0 || data + packed > n) throw new SkinError('NOT_ZIP');
        out.push({ name: nm, flags, method, data, packed, inflated, size: u32(p + 22) });
        p = data + packed + after;
      }
      if (!out.length) throw new SkinError('NOT_ZIP');
      return out;
    };

    const unpack = async (e, left) => {
      if (e.flags & 1) throw new SkinError('ENCRYPTED', e.name);
      if (e.method === 0) {
        if (e.packed > lim.entryBytes || e.packed > left) throw new SkinError('UNPACKED_TOO_BIG', e.name);
        return bytes.slice(e.data, e.data + e.packed);
      }
      if (e.method === 8) return inflateSkin(bytes.subarray(e.data, e.data + e.packed), Math.min(lim.entryBytes, left), e.packed, e.name, lim, e.size);
      throw new SkinError('ENCRYPTED', e.name);
    };

    let entries = central();
    if (!entries) {
      if (u32(0) !== LOCAL) throw new SkinError('NOT_ZIP');
      entries = await local();
    }
    const out = [];
    const seen = new Set();
    let total = 0;
    for (const e of entries) {
      if (!e.name || e.name.endsWith('/')) continue;
      const b = e.inflated || await unpack(e, budget - total);
      total += b.length;
      if (total > budget) throw new SkinError('UNPACKED_TOO_BIG', e.name);
      if (!seen.has(e.name)) { seen.add(e.name); out.push({ name: e.name, bytes: b }); }
    }
    if (!out.length) throw new SkinError('NOT_ZIP');
    return out;
  }

  /**
   * A Sogou container's payload (SkinPlatform.sogouPlain, as the app's codec opens it): AES-CBC with [lim]'s key, a
   * length, then zlib inflated to exactly that length. Null where it does not open; `'too-big'` where a true length
   * (zlib data follows) is past [maxBytes]. Read a piece at a time: past the length it stops.
   */
  async function sogouPlain(container, maxBytes, lim) {
    const sealed = container.length - 8;
    if (sealed <= 0) return null;
    const hex = (h) => Uint8Array.from(h.match(/../g), (x) => parseInt(x, 16));
    let plain;
    try {
      const key = await crypto.subtle.importKey('raw', hex(lim.sogouKey), { name: 'AES-CBC' }, false, ['decrypt']);
      plain = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-CBC', iv: hex(lim.sogouIv) }, key, container.subarray(8)));
    } catch (e) {
      return null;
    }
    if (plain.length < 4) return null;
    const raw = plain[0] + plain[1] * 0x100 + plain[2] * 0x10000 + plain[3] * 0x1000000;
    if (raw > lim.ratioFrom && raw > lim.ratio * sealed) return null;
    // a wrong key's random length is no payload; a true one past what is left is too big
    if (raw > maxBytes) return plain.length >= 6 && (plain[4] & 0x0f) === 8 && ((plain[4] << 8) | plain[5]) % 31 === 0 ? 'too-big' : null;
    const reader = new Blob([plain.subarray(4)]).stream().pipeThrough(new DecompressionStream('deflate')).getReader();
    const parts = [];
    let got = 0;
    for (;;) {
      let r;
      try {
        r = await reader.read();
      } catch (e) {
        // bytes after the stream's end, as above
        if (got === raw) break;
        return null;
      }
      if (r.done) break;
      got += r.value.length;
      if (got > raw) {
        await reader.cancel().catch(() => {});
        return null;
      }
      parts.push(r.value);
    }
    return got === raw ? joined(parts, got) : null;
  }

  /** A PNG's or WebP's type, `size` ([w, h], null unread) and whether it moves, as the app's RasterIcon reads its header; null: neither. */
  function raster(bytes) {
    const type = imageType(bytes);
    if (type !== 'png' && type !== 'webp') return null;
    const size = imageSize(bytes);
    // a side of 0 the app reads as no image
    return { type, size: size && size[0] > 0 && size[1] > 0 ? size : null, animated: type === 'webp' && bytes.length > 20 && String.fromCharCode(...bytes.slice(12, 16)) === 'VP8X' && (bytes[20] & 0x02) !== 0 };
  }

  // the app's ThemeMakerFormatTest holds the youmo-format block to the fields ThemeLookReader reads

  function typeOf(v) {
    if (v === null) return 'null';
    if (Array.isArray(v)) return 'array';
    return typeof v;
  }

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

  function parseKeyName(text) {
    const slash = text.indexOf('/');
    if (slash <= 0) throw new ThemeError('bad', 'key name "' + text + '" is not board/key');
    const board = text.substring(0, slash);
    const key = text.substring(slash + 1);
    if (!(board === '*' || BOARD_SYNTAX.test(board))) throw new ThemeError('bad', 'key name "' + text + '" has a bad board');
    if (key.length === 0 || /\s/.test(key)) throw new ThemeError('bad', 'key name "' + text + '" has a bad key');
    return { board, key };
  }

  const KINDS = ['letter', 'function', 'space', 'enter'];
  const kindStyles = (keys) => KINDS.map((k) => keys[k]);

  function inRange(v, lo, hi, what) {
    if (v < lo || v > hi) throw new ThemeError('bad', what + ' ' + v + ' out of ' + lo + '..' + hi);
  }

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

  function isThemeFileName(name) {
    return isPlainFileName(name) && !name.toLowerCase().endsWith('.json') && name !== '.saving' && utf8Length(name) <= 255;
  }
  function isPlainFileName(name) {
    return name.length > 0 && name !== '.' && name !== '..' && !name.includes('/');
  }
  const utf8Length = (s) => new TextEncoder().encode(s).length;
  const baseName = (name) => name.substring(name.lastIndexOf('/') + 1);
  /** As the app's unpacking resolves it (File(dir, name).canonicalPath); one escaping the zip's top refuses the zip. */
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
        // SOF0..SOF15 but DHT, JPG, DAC
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

  /** A theme zip opened as the app imports one (ThemeFilesManager.openTheme): refused by what the zip says before anything is unpacked. */
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

  function entryBreach(lim, count, size, total) {
    if (count > lim.entries) return 'entries';
    if (size > lim.fileBytes) return 'fileBytes';
    if (total + size > lim.totalBytes) return 'totalBytes';
    return null;
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


  /** CanHub's RESIZE_INSIDE: scaled down, never up. */
  function resizeInside(bw, bh, w, h) {
    if (bw <= w && bh <= h) return [bw, bh];
    const k = Math.min(w / bw, h / bh);
    return [Math.max(1, Math.round(bw * k)), Math.max(1, Math.round(bh * k))];
  }

  // a source picture may be a phone's photo (the app keeps it as it is); one past these is not decoded
  const SOURCE_SIDE = 12000;
  const SOURCE_PIXELS = 108e6;

  /** A few header bytes can claim 30000 × 30000, gigabytes once decoded. */
  function decodeCaps(format, role) {
    const L = format.limits;
    if (role === 'source') return { side: SOURCE_SIDE, pixels: SOURCE_PIXELS, keep: L.pictureSide };
    const side = role === 'key' ? L.keyImageSide : role === 'icon' ? L.rasterIconSide : role === 'sticker' ? L.stickerSide : L.pictureSide;
    return { side, pixels: side * side, keep: side };
  }

  function decodable(size, caps) {
    return !!size && Math.max(size[0], size[1]) <= caps.side && size[0] * size[1] <= caps.pixels;
  }

  function imageType(bytes) {
    if (bytes.length > 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) return 'png';
    if (bytes.length > 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'jpg';
    if (bytes.length > 12 && bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[8] === 0x57 && bytes[9] === 0x45) return 'webp';
    if (bytes.length > 6 && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46) return 'gif';
    return null;
  }

  function fontType(bytes) {
    if (bytes.length < 12) return null;
    const tag = (bytes[0] << 24 | bytes[1] << 16 | bytes[2] << 8 | bytes[3]) >>> 0;
    if (tag === 0x00010000 || tag === 0x74727565) return 'ttf';
    if (tag === 0x4f54544f) return 'otf';
    return null;
  }

  function contentId(bytes) {
    // with the length: two files of one CRC differ in it too
    return crc32(bytes).toString(16).padStart(8, '0') + '-' + bytes.length.toString(16);
  }

  root.YoumoLib = {
    ThemeError, crc32, writeZip, readZip, readTheme, writeTheme, openZip, lookFiles, entryBreach, imageSize, decodeCaps,
    decodable, mapFiles, isPlainFileName, entryPath, from21, parseKeyName, resizeInside, imageType, fontType, contentId,
    SkinError, readSkinZip, sogouPlain, raster,
  };
})(typeof window !== 'undefined' ? window : globalThis);
