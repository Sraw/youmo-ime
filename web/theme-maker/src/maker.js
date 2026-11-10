// youmo-maker: the theme maker's document (060-render-p3 §5.1, M1), apart from the page so node tests it:
// a theme 4.0 pack (theme.xml, its files, its layouts/) opened, saved, kept as a draft, undone and redone;
// the preview's environment. The engine (youmo-engine) reads and draws; youmo-lib has the zip and the 3.0
// and 2.1 files, which the engine's convert30 makes 4.0.
(function (root) {
  'use strict';

  const THEME_XML = 'theme.xml';
  const LAYOUTS = 'layouts/';
  const UNDO_LIMIT = 100;

  /** A document: the theme's text, its files by name, its layouts by their path under layouts/ (kept as they are). */
  function doc(xml, files = {}, layouts = {}) {
    // own keys copied one by one: a file named __proto__ stays a file
    const copy = (o) => { const out = Object.create(null); for (const k of Object.keys(o)) out[k] = o[k]; return out; };
    return { xml, files: copy(files), layouts: copy(layouts) };
  }

  /** The <theme>'s name= of [xml], null for none. */
  function themeName(xml) {
    const m = /<theme\b[^>]*?\sname="([^"]*)"/.exec(xml);
    return m ? m[1] : null;
  }

  /** [xml] with its <theme>'s name= [name] (one put in where it has none). */
  function withName(xml, name) {
    if (themeName(xml) != null) return xml.replace(/(<theme\b[^>]*?\sname=")[^"]*(")/, (all, a, b) => a + name + b);
    return xml.replace(/<theme\b/, (t) => t + ' name="' + name + '"');
  }

  /** A new document of the app's theme [id] (engine's themeSource), named apart from it so an import adds a theme. */
  function fromBuiltin(E, id) {
    const xml = E.themeSource(id);
    if (xml == null) throw new Error('no built-in theme ' + id);
    return doc(withName(xml, id + '-mine'));
  }

  const PICTURE = /<picture\b[^>]*?(?:\/>|>[\s\S]*?<\/picture>)/;
  const KEYBOARD_PICTURE = '<style select="keyboard" fill="picture"/>';
  // the comments of [xml] blanked (same length), so what is looked for is never found inside one
  const uncommented = (xml) => xml.replace(/<!--[\s\S]*?-->/g, (c) => ' '.repeat(c.length));

  /**
   * A new document of [base] (a document) with [image] (its bytes, [type] png/jpg/webp: [pictureFile]'s) as the
   * keyboard's picture: the source and each orientation's cut the same file, the keyboard showing it. The colours
   * stay [base]'s (picking them from the picture comes with the page's crop and colours, M2/M3). A picture [base] had is replaced, its
   * files dropped where nothing else names them.
   */
  function withPicture(base, image, type) {
    const file = 'picture.' + type;
    const picture = '  <picture src="' + file + '">\n    <cut orientation="portrait" file="' + file + '"/>\n    <cut orientation="landscape" file="' + file + '"/>\n  </picture>';
    let xml = base.xml;
    const files = doc('', base.files).files;
    const old = PICTURE.exec(uncommented(xml));
    if (old) {
      const was = xml.slice(old.index, old.index + old[0].length);
      xml = xml.slice(0, old.index).replace(/[ \t]*$/, '') + xml.slice(old.index + old[0].length).replace(/^[ \t]*\n/, '');
      for (const m of was.matchAll(/\b(?:src|file)="([^"]+)"/g)) if (!xml.includes('"' + m[1] + '"')) delete files[m[1]];
    }
    // after the last <colors> (a theme of two versions has one for each), else after <theme …>: before fonts and styles
    const plain = uncommented(xml);
    const colors = plain.lastIndexOf('</colors>');
    const open = /<theme\b[^>]*>/.exec(plain);
    if (colors < 0 && !open) throw new Error('no <theme> to put a picture in');
    const at = colors >= 0 ? colors + '</colors>'.length : open.index + open[0].length;
    xml = xml.slice(0, at) + '\n' + picture + xml.slice(at);
    if (!uncommented(xml).includes(KEYBOARD_PICTURE)) {
      const end = uncommented(xml).lastIndexOf('</theme>');
      xml = xml.slice(0, end).replace(/\s*$/, '') + '\n  ' + KEYBOARD_PICTURE + '\n' + xml.slice(end);
    }
    files[file] = image;
    return doc(xml, files, base.layouts);
  }

  // a picture the user picks is never kept as it was (the user's ruling, 2026-11-09): always encoded anew, its long
  // side no more than longSide (never made bigger), JPEG at quality; PNG only where it has transparency
  const PICTURE_SAVE = { longSide: 2560, quality: 0.85 };

  /**
   * A picture the user picked ([bytes]) made a file the app takes: refused (ThemeError) where it is no image the
   * app reads or too big to decode; else always [encode]d anew no bigger than PICTURE_SAVE.longSide
   * (`async (bytes, sizeOf, quality) => {bytes, type}`, the browser's canvas: jpg, or png where it has alpha). The
   * encoder sizes it by what it decoded (`sizeOf(width, height)`), not the file's header: a photo the camera tagged
   * as turned (EXIF) is decoded upright, its sides swapped.
   * `{bytes, type}`.
   */
  async function pictureFile(L, format, bytes, encode) {
    const type = L.imageType(bytes);
    const size = L.imageSize(bytes);
    if (!size || !['png', 'jpg', 'webp'].includes(type)) throw new L.ThemeError('image', 'not a PNG, JPEG or WebP picture');
    if (!L.decodable(size, L.decodeCaps(format, 'source'))) throw new L.ThemeError('limit', 'pictureSide');
    const side = Math.min(PICTURE_SAVE.longSide, format.limits.pictureSide);
    const made = await encode(bytes, (bw, bh) => L.resizeInside(bw, bh, side, side), PICTURE_SAVE.quality);
    const got = L.imageSize(made.bytes);
    if (!got || Math.max(got[0], got[1]) > side) throw new L.ThemeError('limit', 'pictureSide');
    return made;
  }

  /**
   * What the app refuses on import that the engine does not see (ThemePackFiles.check): the name, the files the
   * theme names and does not bring, images past their sides, entries past the zip's limits. `[{level: "error", line: 0, message}]`.
   */
  function saveProblems(E, L, format, d) {
    const lim = format.limits;
    const out = [];
    const bad = (message) => out.push({ level: 'error', line: 0, message });
    // the engine's reading of it (the name as the app reads it, the blank keys by the app's own rule: BlankKeys)
    const read = E.readTheme(d.xml, null, null);
    if (!read.ok) return read.problems.map((p) => ({ level: 'error', line: p.line || 0, message: p.message }));
    const name = read.theme.name;
    if (name == null || !L.isPlainFileName(name) || name.startsWith('.') || /#(light|dark)$/.test(name)) bad('主题名 "' + name + '" 不能做文件名');
    const xml = uncommented(d.xml);
    const named = new Set();
    for (const m of xml.matchAll(/<(picture|cut|font|icon)\b[^>]*?\b(src|file)="([^"]+)"/g)) named.add(m[3] + '|' + m[1]);
    for (const m of xml.matchAll(/\bimage\(\s*([^,)\s]+)/g)) named.add(m[1].replace(/^["']|["']$/g, '') + '|image');
    for (const key of named) {
      const [file, role] = key.split('|');
      const bytes = d.files[file];
      if (!bytes) { bad('缺少文件 ' + file); continue; }
      if (role === 'picture' || role === 'cut' || role === 'image') {
        const s = L.imageSize(bytes);
        const side = role === 'image' ? lim.keyImageSide : lim.pictureSide;
        if (!s) bad(file + ' 不是 App 认得的图片');
        else if (Math.max(s[0], s[1]) > side) bad(file + ' 太大：' + s[0] + '×' + s[1] + '，最多 ' + side);
        else if (role === 'image' && bytes.length > lim.keyImageBytes) bad(file + ' 超过 ' + lim.keyImageBytes + ' 字节');
      }
    }
    for (const line of read.theme.hidesRequired()) bad('第 ' + line + ' 行把 Shift、退格、空格或回车的字藏了：这些键会是空白');
    let total = 0;
    const enc = new TextEncoder();
    const sizes = [enc.encode(d.xml).length, ...Object.values(d.files).map((b) => b.length), ...Object.values(d.layouts).map((t) => enc.encode(t).length)];
    for (const [i, size] of sizes.entries()) {
      const breach = L.entryBreach(lim, i + 1, size, total);
      if (breach) { bad('超过 App 的限制：' + breach); break; }
      total += size;
    }
    return out;
  }

  // the pixels the page decodes of a theme at most, all its images together (64 MP, ~256 MB of bitmaps)
  const DECODE_BUDGET = 64 * 1000 * 1000;

  /**
   * Which of [d]'s files the page may decode: only images the theme names, each within its role's sides (the
   * picture's source, its cuts, a key's image: youmo-lib's decodeCaps), and all of them within DECODE_BUDGET, the
   * first named first. A pack's bytes are not trusted: a decode past the browser's memory takes the tab down.
   */
  function decodableImages(L, format, d) {
    const xml = uncommented(d.xml);
    const roles = new Map();
    const name = (n, role) => { if (!roles.has(n)) roles.set(n, role); };
    for (const m of xml.matchAll(/<picture\b[^>]*?\bsrc="([^"]+)"/g)) name(m[1], 'source');
    for (const m of xml.matchAll(/<cut\b[^>]*?\bfile="([^"]+)"/g)) name(m[1], 'picture');
    for (const m of xml.matchAll(/\bimage\(\s*([^,)\s]+)/g)) name(m[1].replace(/^["']|["']$/g, ''), 'key');
    let spent = 0;
    const out = [];
    for (const [n, role] of roles) {
      const bytes = d.files[n];
      const size = bytes && /\.(png|jpe?g|webp)$/i.test(n) ? L.imageSize(bytes) : null;
      if (!size || !L.decodable(size, L.decodeCaps(format, role))) continue;
      if (spent + size[0] * size[1] > DECODE_BUDGET) continue;
      spent += size[0] * size[1];
      out.push(n);
    }
    return out;
  }

  const decode = (bytes) => new TextDecoder().decode(bytes);
  const isZip = (bytes) => bytes.length >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b;

  /**
   * [bytes] (a file the user opened) as a document: a 4.0 pack (a zip with theme.xml at its top), a 3.0 or 2.1
   * theme file (youmo-lib reads it as the app does, the engine makes it 4.0), or a bare theme.xml.
   * `{doc, notes}`: notes say what was done to it (`converted`), what it carries (`layouts`: their ids) and what
   * the app would not take from it (`dropped`). Throws youmo-lib's ThemeError (or an Error) where it is none of them.
   */
  async function open(E, L, format, bytes) {
    if (!isZip(bytes)) {
      // a theme.xml: text that starts as XML, no bigger than a file the app takes
      if (bytes.length > format.limits.fileBytes) throw new L.ThemeError('limit', 'fileBytes');
      const text = decode(bytes).replace(/^﻿/, '');
      if (!/^\s*</.test(text)) throw new L.ThemeError('not-theme', 'not a theme file');
      return { doc: doc(text), notes: [] };
    }
    const entries = L.readZip(bytes);
    // the app's unzip keeps the last of a name: so does the page
    const tops = entries.filter((e) => !e.name.endsWith('/') && L.entryPath(e.name).join('/') === THEME_XML);
    if (tops.length) {
      const lim = format.limits;
      let total = 0;
      const files = Object.create(null);
      const layouts = Object.create(null);
      const notes = [];
      const dropped = [];
      for (const [i, e] of entries.entries()) {
        const breach = L.entryBreach(lim, i + 1, e.size, total);
        if (breach) throw new L.ThemeError('limit', breach);
        total += e.size;
        if (e.name.endsWith('/')) continue;
        const parts = L.entryPath(e.name);
        if (parts.length === 1 && parts[0] === THEME_XML) continue;
        if (parts.length === 1 && !parts[0].startsWith('.')) files[parts[0]] = await e.read();
        else if (parts.length === 2 && parts[0] + '/' === LAYOUTS && parts[1].endsWith('.xml')) layouts[parts[1]] = decode(await e.read());
        else dropped.push(parts.join('/'));
      }
      const d = doc(decode(await tops[tops.length - 1].read()), files, layouts);
      if (Object.keys(layouts).length) notes.push({ kind: 'layouts', ids: Object.keys(layouts).map((p) => p.replace(/\.xml$/, '')) });
      if (dropped.length) notes.push({ kind: 'dropped', names: dropped });
      return { doc: d, notes };
    }
    const opened = await L.openZip(format, bytes);
    const r = E.convert30(L.writeTheme(format, opened.theme));
    if (!r.ok) throw new Error(r.problems.map((p) => p.message).join('; '));
    const files = Object.create(null);
    for (const [from, to] of r.files) if (opened.files[from]) files[to] = opened.files[from];
    return { doc: doc(r.xml, files), notes: [{ kind: 'converted', from: opened.migrated ? '2.1' : '3.0' }] };
  }

  /** [d] as the file the app imports: `{name: "<theme's name>.theme", bytes}`, theme.xml first, then its files, then layouts/. */
  function save(L, d) {
    const enc = new TextEncoder();
    const entries = [[THEME_XML, enc.encode(d.xml)]];
    for (const n of Object.keys(d.files).sort()) entries.push([n, d.files[n]]);
    for (const p of Object.keys(d.layouts).sort()) entries.push([LAYOUTS + p, enc.encode(d.layouts[p])]);
    return { name: (themeName(d.xml) || 'theme') + '.theme', bytes: L.writeZip(entries) };
  }

  /** Two documents the same: their text, files and layouts. */
  function same(a, b) {
    if (a.xml !== b.xml) return false;
    const eq = (x, y, byte) => {
      const kx = Object.keys(x).sort();
      const ky = Object.keys(y).sort();
      if (kx.join('\n') !== ky.join('\n')) return false;
      return kx.every((k) => (byte ? x[k].length === y[k].length && x[k].every((v, i) => v === y[k][i]) : x[k] === y[k]));
    };
    return eq(a.files, b.files, true) && eq(a.layouts, b.layouts, false);
  }

  /** Undo and redo: the documents as they were, at most [limit] back (each a whole document: files are shared, not copied). */
  class History {
    constructor(first, limit = UNDO_LIMIT) {
      this.limit = limit;
      this.past = [];
      this.future = [];
      this.now = first;
    }
    get current() { return this.now; }
    get canUndo() { return this.past.length > 0; }
    get canRedo() { return this.future.length > 0; }
    /** [next] the document now; nothing kept where it is the same as now. */
    push(next) {
      if (same(next, this.now)) return false;
      this.past.push(this.now);
      if (this.past.length > this.limit) this.past.shift();
      this.future = [];
      this.now = next;
      return true;
    }
    undo() {
      if (!this.past.length) return this.now;
      this.future.push(this.now);
      this.now = this.past.pop();
      return this.now;
    }
    redo() {
      if (!this.future.length) return this.now;
      this.past.push(this.now);
      this.now = this.future.pop();
      return this.now;
    }
  }

  /**
   * The draft: the document as it is now, kept in [store] (`get(key)`, `put(key, value)`, `remove(key)`, each a
   * promise) so a page opened again offers it back. A store that fails (a private window, no IndexedDB) is a
   * page without drafts, never one that stops.
   */
  const DRAFT = 'draft';
  async function keepDraft(store, d, env) {
    try {
      await store.put(DRAFT, { v: 1, doc: { xml: d.xml, files: d.files, layouts: d.layouts }, env, at: Date.now() });
      return true;
    } catch (e) {
      return false;
    }
  }
  async function draft(store) {
    try {
      const v = await store.get(DRAFT);
      if (!v || v.v !== 1 || !v.doc || typeof v.doc.xml !== 'string') return null;
      return { doc: doc(v.doc.xml, v.doc.files || {}, v.doc.layouts || {}), env: v.env || null, at: v.at };
    } catch (e) {
      return null;
    }
  }
  async function dropDraft(store) {
    try {
      await store.remove(DRAFT);
    } catch (e) {
      // nothing kept: nothing to drop
    }
  }

  /** IndexedDB as a draft store (pictures are too big for localStorage); null where the browser has none. */
  function idbStore(idb) {
    if (!idb) return null;
    let db = null;
    const opened = () => (db ??= new Promise((ok, fail) => {
      const r = idb.open('youmo-theme-maker', 1);
      r.onupgradeneeded = () => r.result.createObjectStore('kv');
      r.onsuccess = () => ok(r.result);
      r.onerror = () => fail(r.error);
    }));
    const run = (mode, act) => opened().then((d) => new Promise((ok, fail) => {
      const tx = d.transaction('kv', mode);
      const r = act(tx.objectStore('kv'));
      tx.oncomplete = () => ok(r.result);
      tx.onerror = () => fail(tx.error);
      tx.onabort = () => fail(tx.error);
    }));
    return {
      get: (k) => run('readonly', (s) => s.get(k)),
      put: (k, v) => run('readwrite', (s) => s.put(v, k)),
      remove: (k) => run('readwrite', (s) => s.delete(k)),
    };
  }

  // ---- the preview's environment ----

  /** Phones to preview on: the screen's short and long side in px and its density (the keyboard as tall as the app makes it by default). */
  const DEVICES = [
    { id: 'oneplus-6t', label: 'OnePlus 6T', w: 1080, h: 2340, density: 2.625 },
    { id: 'pixel-8', label: 'Pixel 8', w: 1080, h: 2400, density: 2.625 },
    { id: 'pixel-8-pro', label: 'Pixel 8 Pro', w: 1344, h: 2992, density: 3.5 },
    { id: 'galaxy-s24', label: 'Galaxy S24', w: 1080, h: 2340, density: 3 },
    { id: 'small', label: '小屏 720p', w: 720, h: 1280, density: 2 },
    { id: 'tablet', label: '平板 10"', w: 1600, h: 2560, density: 2 },
  ];
  /** The keyboard's height in dp (the app's default, portrait and landscape). */
  const KEYBOARD_DP = { portrait: 267, landscape: 200 };
  const FORMS = ['normal', 'compact', 'floating', 'left', 'right', 'split', 'flat'];
  const BOARDS = ['qwerty', 'nine', 'k14', 'k18', 'zhuyin', 'zhuyin-nine', 'number', 'symbols', 'handwriting'];

  /** The environment's choices as the page starts. */
  const ENV = { device: 'oneplus-6t', w: 1080, h: 2340, density: 2.625, landscape: false, board: 'qwerty', form: 'normal', dark: false, shift: 'none' };

  /** [e] (the page's choices) as the engine's EnvJson: the keyboard's width and height in px, the form's flags. */
  function envJson(e) {
    const across = e.landscape ? Math.max(e.w, e.h) : Math.min(e.w, e.h);
    const out = {
      width: across,
      height: Math.round((e.landscape ? KEYBOARD_DP.landscape : KEYBOARD_DP.portrait) * e.density),
      density: e.density,
    };
    if (e.landscape) out.landscape = true;
    if (e.dark) out.dark = true;
    if (e.shift && e.shift !== 'none') out.shift = e.shift;
    if (e.form === 'compact') out.compact = true;
    else if (e.form === 'floating') out.floating = 1;
    else if (e.form === 'left' || e.form === 'right') out.oneHanded = e.form;
    else if (e.form === 'split') out.split = true;
    else if (e.form === 'flat') out.flat = true;
    return out;
  }

  /**
   * What is wrong with [d] drawn on [board] in [env] (EnvJson): `[{level: "error"|"warning", line, message}]`,
   * the theme's own, then the scene's; `theme` and `scene` where they were made.
   */
  function check(E, d, board, env, measurer) {
    try {
      return checked(E, d, board, env, measurer);
    } catch (e) {
      return { problems: [{ level: 'error', line: 0, message: '引擎出错：' + (e && e.message ? e.message : e) }], theme: null, scene: null };
    }
  }
  function checked(E, d, board, env, measurer) {
    const r = E.readTheme(d.xml, null, null);
    const problems = [];
    const add = (level, list) => { for (const p of list || []) problems.push({ level, line: p.line || 0, message: p.message }); };
    add('error', r.problems);
    add('warning', r.warnings);
    if (!r.ok) return { problems, theme: null, scene: null };
    const s = E.buildScene(board, r.theme, env, measurer || E.fixedMeasurer());
    add('error', s.problems);
    add('warning', s.ok ? s.scene.json().warnings : s.warnings);
    return { problems, theme: r.theme, scene: s.ok ? s.scene : null };
  }

  root.YoumoMaker = {
    THEME_XML, UNDO_LIMIT, DEVICES, KEYBOARD_DP, FORMS, BOARDS, ENV,
    doc, themeName, withName, fromBuiltin, withPicture, PICTURE_SAVE, DECODE_BUDGET, pictureFile, saveProblems, decodableImages, open, save, same, History, keepDraft, draft, dropDraft, idbStore, envJson, check,
  };
})(typeof window !== 'undefined' ? window : globalThis);
