// youmo-maker: the theme maker's document (060-render-p3 §5.1, M1), apart from the page so node tests it:
// a theme 4.0 pack (theme.xml, its files, its layouts/) opened, saved, kept as a draft, undone and redone;
// the preview's environment. The engine (youmo-engine) reads and draws; youmo-lib has the zip and the 3.0
// and 2.1 files, which the engine's convert30 makes 4.0.
(function (root) {
  'use strict';

  // the comments of [xml] blanked (same length), so what is looked for is never found inside one
  const uncommented = (xml) => xml.replace(/<!--[\s\S]*?-->/g, (c) => ' '.repeat(c.length));
  const unescape = (v) => v.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
  const escape = (v) => String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  // a file named by an attribute: either quote, spaces around '=' (as XML has it and the app reads it)
  const srcOf = (tag, attr) => new RegExp('<(' + tag + ')\\b[^>]*?\\b(' + attr + ')\\s*=\\s*(?:"([^"]+)"|\'([^\']+)\')', 'g');
  const fileOf = (m) => unescape(m[3] ?? m[4]);
  // the image a style's function names: a key's image(…), an effect's ripple-image(…) or particles(…)
  const IMAGE_FN = /(?<![\w-])(image|ripple-image|particles)\(\s*([^,)\s]+)/g;

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
    const m = /<theme\b(?:[^>"']|"[^"]*"|'[^']*')*?\sname\s*=\s*("([^"]*)"|'([^']*)')/.exec(uncommented(xml));
    return m ? unescape(m[2] ?? m[3]) : null;
  }

  /** [xml] with its <theme>'s name= [name] (one put in where it has none). */
  function withName(xml, name) {
    const t = /<theme\b(?:[^>"']|"[^"]*"|'[^']*')*?\/?>/.exec(uncommented(xml));
    if (!t) return xml;
    const tag = xml.slice(t.index, t.index + t[0].length);
    // its attributes one by one, so a ' name=' inside another's quoted value is never the one changed
    let at = null;
    for (const a of tag.matchAll(/(\s)([^\s=/>]+)\s*=\s*("[^"]*"|'[^']*')/g)) if (a[2] === 'name') { at = a; break; }
    const named = at
      ? tag.slice(0, at.index) + at[1] + 'name="' + escape(name) + '"' + tag.slice(at.index + at[0].length)
      : tag.replace(/^<theme\b/, '<theme name="' + escape(name) + '"');
    return xml.slice(0, t.index) + named + xml.slice(t.index + t[0].length);
  }

  /** A new document of the app's theme [id] (engine's themeSource), named apart from it so an import adds a theme. */
  function fromBuiltin(E, id) {
    const xml = E.themeSource(id);
    if (xml == null) throw new Error('no built-in theme ' + id);
    return doc(withName(xml, id + '-mine'));
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
    // the app refuses a pack named as a built-in theme (ThemeFilesManager.packClash): the names the page is given
    else if ((format.builtinNames || []).includes(name)) bad('主题名 "' + name + '" 是 App 内置主题的名字，App 不会收；换一个名字');
    const xml = uncommented(d.xml);
    const named = new Set();
    for (const m of xml.matchAll(srcOf('picture|cut|font|icon', 'src|file'))) named.add(fileOf(m) + '|' + m[1]);
    for (const m of xml.matchAll(IMAGE_FN)) named.add(m[2].replace(/^["']|["']$/g, '') + '|' + (m[1] === 'image' ? 'image' : 'effect'));
    for (const m of xml.matchAll(srcOf('meta', 'preview'))) named.add(fileOf(m) + '|preview');
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
      } else if (role === 'effect') {
        // the app's ThemeAssetLimits.checkEffectImage: PNG or WebP by its name, its sides and bytes
        const s = L.imageSize(bytes);
        if (!/\.(png|webp)$/i.test(file)) bad(file + '：特效图片只能是 .png 或 .webp');
        else if (!s) bad(file + ' 不是 App 认得的图片');
        else if (lim.effectImageSide != null && Math.max(s[0], s[1]) > lim.effectImageSide) bad(file + ' 太大：' + s[0] + '×' + s[1] + '，特效图片最多 ' + lim.effectImageSide);
        else if (lim.effectImageBytes != null && bytes.length > lim.effectImageBytes) bad(file + ' 超过 ' + lim.effectImageBytes + ' 字节（特效图片）');
      } else if (role === 'preview') {
        // the app's ThemeAssetLimits.preview
        const s = L.imageSize(bytes);
        if (!s || !['png', 'webp'].includes(L.imageType(bytes))) bad(file + ' 不是 PNG 或 WebP');
        else if (Math.max(s[0], s[1]) > 2048 || bytes.length > 512 * 1024) bad(file + ' 太大：预览图最大 2048×2048、512 KB');
      } else if (role === 'icon') {
        // the app keeps an icon only as an SVG of filled paths (SvgSubset, the engine's readIcon)
        let text = null;
        try { text = new TextDecoder('utf-8', { fatal: true }).decode(bytes); } catch (e) { /* not text: no SVG */ }
        const r = text == null ? null : E.readIcon(text);
        if (lim.iconBytes != null && bytes.length > lim.iconBytes) bad(file + ' 超过 ' + lim.iconBytes + ' 字节');
        else if (!r) bad(file + ' 不是 SVG（App 只收 SVG 图标）');
        else if (!r.ok) bad(file + '：' + r.problems[0].message);
      }
    }
    // an animated <picture> (060-render-p3 A4): past the app's limits it is drawn still, with a warning on import
    // the engine's reading of it (info().picture.animated), each version's
    const animSrcs = new Set([false, true].map((dark) => read.theme.info(dark).picture).filter((p) => p && p.animated).map((p) => p.src));
    for (const animSrc of animSrcs) {
      if (!d.files[animSrc]) continue;
      const why = animatedStill(lim, d.files[animSrc]);
      if (why) out.push({ level: 'warning', line: 0, message: animSrc + ' 在 App 里会显示成静态图：' + why });
    }
    // the app's own check (BlankKeys): on its boards and the theme's own layouts, in every scene the styles name
    const layouts = Object.create(null);
    for (const [n, t] of Object.entries(d.layouts)) layouts[n] = t;
    for (const where of E.blankKeys(read.theme, layouts)) {
      if (where === '*') bad('键的样式用的场景组合太多，没法逐一检查 Shift、退格、空格和回车会不会成空白键（App 会拒收）');
      else bad(where + '：字藏了，键面又不是图片——Shift、退格、空格或回车会是空白键');
    }
    const enc = new TextEncoder();
    // the pack's layouts as the app takes them (ThemePackFiles.checkLayouts, ThemeAssetLimits.layouts; ThemePackLayouts.report)
    const names = Object.keys(d.layouts);
    for (const n of names) if (!n.endsWith('.xml') || !L.isPlainFileName(n)) bad('layouts/' + n + ' 不是 App 认得的布局文件名（<键盘>.xml）');
    if (names.length > lim.layouts) bad('自带的键盘布局 ' + names.length + ' 个，App 最多收 ' + lim.layouts + ' 个');
    for (const n of names) if (enc.encode(d.layouts[n]).length > lim.layoutBytes) bad('layouts/' + n + ' 超过 ' + lim.layoutBytes + ' 字节');
    const boards = new Set(E.layouts());
    for (const n of names) {
      const id = n.replace(/\.xml$/, '');
      if (!boards.has(id) && E.layoutSource(id) == null) out.push({ level: 'warning', line: 0, message: 'layouts/' + n + '：App 没有这个键盘，不会用它' });
    }
    // the app's ThemeAssetLimits.MAX_THEME_XML_BYTES
    const xmlBytes = enc.encode(d.xml).length;
    if (lim.themeXmlBytes != null && xmlBytes > lim.themeXmlBytes) bad('theme.xml 有 ' + xmlBytes + ' 字节，App 最多收 ' + lim.themeXmlBytes + ' 字节');
    let total = 0;
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
    for (const m of xml.matchAll(srcOf('picture', 'src'))) name(fileOf(m), 'source');
    for (const m of xml.matchAll(srcOf('cut', 'file'))) name(fileOf(m), 'picture');
    // an effect's image is not drawn by the preview: not decoded
    for (const m of xml.matchAll(IMAGE_FN)) if (m[1] === 'image') name(m[2].replace(/^["']|["']$/g, ''), 'key');
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
      return kx.every((k) => (byte ? x[k] === y[k] || (x[k].length === y[k].length && x[k].every((v, i) => v === y[k][i])) : x[k] === y[k]));
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
  // the files apart from the text: written again only when they changed (an edit of theme.xml shares its files
  // object with the document before it), not megabytes of pictures each time a value is typed
  // the text names its files record by id (each set of files kept under its own key): the files are written
  // first, then the text naming them, then this page's records before it are let go. A text read with files
  // not its own is no draft, never a mix; two tabs each write their own files again when the other wrote last
  const DRAFT_FILES = 'draft-files'; // the one files record of the pages before (drafts 2 and 3)
  const filesKey = (id) => DRAFT_FILES + '-' + id;
  // the store → { files, id, own }: the files last written to it, and the ids of the files records this page
  // wrote and has not let go yet (a record of a write cut before its text, or the one an earlier text named)
  const keptFiles = new WeakMap();
  async function keepDraft(store, d, env) {
    try {
      const before = await store.get(DRAFT);
      const prev = before && before.v === 4 ? before.files : null;
      const first = !keptFiles.has(store);
      let kept = keptFiles.get(store);
      // files changed, or another tab's text is there now (its files may have replaced ours): written anew
      if (!kept || kept.files !== d.files || prev !== kept.id) {
        const id = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
        kept = { files: d.files, id, own: (kept ? kept.own : []).concat(id) };
        keptFiles.set(store, kept);
        await store.put(filesKey(id), { id, files: d.files });
      }
      await store.put(DRAFT, { v: 4, doc: { xml: d.xml, layouts: d.layouts }, files: kept.id, env, at: Date.now() });
      // only this page's own records are let go: another open tab may have read its text before we wrote and
      // write it after, naming its record (removing that record lost its draft). The record of a page closed
      // before is let go by the first write of the next page
      const gone = kept.own.filter((x) => x !== kept.id);
      if (first && prev && prev !== kept.id) gone.push(prev);
      kept.own = [kept.id];
      for (const x of gone) await store.remove(filesKey(x));
      if (before && before.v !== 4) await store.remove(DRAFT_FILES);
      return true;
    } catch (e) {
      return false;
    }
  }
  async function draft(store) {
    try {
      const v = await store.get(DRAFT);
      if (!v || ![1, 2, 3, 4].includes(v.v) || !v.doc || typeof v.doc.xml !== 'string') return null;
      // 1: the files kept with the text; 2: kept apart, with no id; 3: one record with an id; 4: a record per id
      let files = v.doc.files;
      if (v.v !== 1) {
        const kept = await store.get(v.v === 4 ? filesKey(v.files) : DRAFT_FILES);
        if (v.v === 2) {
          if (kept && typeof kept.id === 'string') return null; // a newer page's files since
          files = kept;
        } else if (!kept || kept.id !== v.files) return null;
        else files = kept.files;
      }
      return { doc: doc(v.doc.xml, files || {}, v.doc.layouts || {}), env: v.env || null, at: v.at };
    } catch (e) {
      return null;
    }
  }
  async function dropDraft(store) {
    try {
      const v = await store.get(DRAFT);
      await store.remove(DRAFT);
      if (v && v.v === 4) await store.remove(filesKey(v.files));
      const kept = keptFiles.get(store);
      for (const x of kept ? kept.own : []) await store.remove(filesKey(x));
      await store.remove(DRAFT_FILES);
      keptFiles.delete(store);
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

  /**
   * The preview's environment (060-render-p3 §5.1 预览环境): the device, the board and its form; the field and the
   * input method typed in (the theme's `@field=`, `@im=`); what is shown besides the keys: a popup (`show`: preview,
   * keys, menu), the bar (`bar`: idle, typing) and a panel over the keys (`panel`: clipboard, picker, editing, voice).
   */
  const ENV = {
    device: 'oneplus-6t', w: 1080, h: 2340, density: 2.625, landscape: false, board: 'qwerty', form: 'normal', dark: false, shift: 'none',
    field: 'text', ime: 'pinyin', show: '', bar: 'idle', panel: '',
  };
  /** The fields and input methods the environment offers: the engine's (Selector.FIELDS, Selector.IMS). */
  const FIELDS = ['text', 'password', 'url', 'number', 'email'];
  const IMES = ['pinyin', 'shuangpin', 'english', 'wubi', 'cangjie', 'handwriting'];

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
    if ((e.field && e.field !== 'text') || (e.ime && e.ime !== 'pinyin')) out.input = { field: e.field || 'text', ime: e.ime || 'pinyin' };
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
    const s = E.buildScene(layoutOf(E, d, board, (list) => add('warning', list)), r.theme, env, measurer || E.fixedMeasurer());
    add('error', s.problems);
    add('warning', s.ok ? s.scene.json().warnings : s.warnings);
    return { problems, theme: r.theme, scene: s.ok ? s.scene : null };
  }

  /**
   * The keyboard [board] is drawn with: the pack's own `layouts/<board>.xml` where it has one that reads (its includes
   * from the pack's other files there, else the app's), else the app's (its id). One that does not read is [warn]ed
   * of, the app's drawn, as the app does (ThemePackLayouts: logged, its own drawn).
   */
  function layoutOf(E, d, board, warn) {
    const own = d.layouts[board + '.xml'];
    if (own == null) return board;
    const fragments = Object.create(null);
    // the pack's fragments the app reads: only under an id of its own (ThemePackLayouts.read)
    for (const [n, t] of Object.entries(d.layouts)) if (n !== board + '.xml' && n.endsWith('.xml') && E.layoutSource(n.slice(0, -4)) != null) fragments[n.slice(0, -4)] = t;
    const l = E.readLayout(own, fragments);
    if (!l.ok) {
      warn(l.problems.map((p) => ({ line: 0, message: 'layouts/' + board + '.xml' + (p.line ? ' 第 ' + p.line + ' 行' : '') + '：' + p.message + '（App 会用自己的这个键盘）' })));
      return board;
    }
    // as typable as the app's, else the app's drawn (ThemePackLayouts.judge, the same rule: PackLayouts)
    const why = E.refuses(board, l.layout);
    if (why == null) return l.layout;
    warn([{ line: 0, message: 'layouts/' + board + '.xml：App 不会用它（' + why + '），会用自己的这个键盘' }]);
    return board;
  }

  /**
   * An animated WebP's header as the app reads it (AnimatedWebp.header): `{width, height, frames, shortestMs}`, null where
   * it is none (no VP8X first with its animation flag, no frame) or cut short; at most [maxFrames] + 64 chunks walked.
   */
  function animatedWebp(bytes, maxFrames) {
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const tag = (at) => String.fromCharCode(bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]);
    const u24 = (at) => bytes[at] | (bytes[at + 1] << 8) | (bytes[at + 2] << 16);
    if (bytes.length < 12 || tag(0) !== 'RIFF' || tag(8) !== 'WEBP') return null;
    const end = Math.min(bytes.length, 8 + dv.getUint32(4, true));
    let at = 12; let chunks = 0; let animated = false; let w = 0; let h = 0; let frames = 0; let shortest = Infinity;
    while (at + 8 <= end) {
      if (++chunks > maxFrames + 64) return null;
      const size = dv.getUint32(at + 4, true);
      const t = tag(at);
      if (t === 'VP8X') {
        if (at !== 12 || at + 18 > bytes.length) return null;
        animated = (bytes[at + 8] & 2) !== 0;
        w = u24(at + 12) + 1;
        h = u24(at + 15) + 1;
      } else if (t === 'ANMF') {
        if (at + 24 > bytes.length) return null;
        frames++;
        shortest = Math.min(shortest, u24(at + 20));
        if (frames > maxFrames) break;
      }
      at += 8 + size + (size & 1);
    }
    return animated && frames > 0 ? { width: w, height: h, frames, shortestMs: shortest } : null;
  }

  /** Why the app draws [bytes] still (ThemeAssetLimits.animated, the limits of youmo-format), null where it plays. */
  function animatedStill(lim, bytes) {
    if (bytes.length > lim.animatedBytes) return '超过 ' + (lim.animatedBytes / 1048576) + ' MB';
    const h = animatedWebp(bytes, lim.animatedFrames);
    if (!h) return '不是动画 WebP';
    if (Math.max(h.width, h.height) > lim.animatedSide) return '长边超过 ' + lim.animatedSide + ' 像素';
    if (h.frames > lim.animatedFrames) return '超过 ' + lim.animatedFrames + ' 帧';
    if (h.shortestMs < lim.animatedFrameMs) return '有一帧短于 ' + lim.animatedFrameMs + ' ms（最快 ' + Math.floor(1000 / lim.animatedFrameMs) + ' 帧/秒）';
    return null;
  }

  /**
   * What stands before a save (060-render-p3 M4): every problem of [d] on each of the app's boards ([boards]), in
   * [env] (EnvJson) light and dark, and what the app refuses on import (saveProblems), each once.
   * `{errors, warnings}`: an error stops the save, a warning is the user's to ignore.
   */
  function exportCheck(E, L, format, d, boards, env) {
    const all = [];
    const seen = new Set();
    const add = (p) => { const k = p.level + '|' + p.line + '|' + p.message; if (!seen.has(k)) { seen.add(k); all.push(p); } };
    for (const board of boards) {
      for (const dark of [false, true]) {
        const e = Object.assign({}, env);
        if (dark) e.dark = true; else delete e.dark;
        // the number board as the app shows it (a field's pad named): not the engine's warning of a pad not given
        if (board === 'number') e.input = Object.assign({ field: 'number', ime: 'pinyin' }, e.input, { pad: 'full' });
        for (const p of check(E, d, board, e, null).problems) add(p);
      }
    }
    if (!all.some((p) => p.level === 'error')) for (const p of saveProblems(E, L, format, d)) add(p);
    return { errors: all.filter((p) => p.level === 'error'), warnings: all.filter((p) => p.level !== 'error') };
  }

  /** An example (the page's youmo-examples block: `{id, label, note, xml, files: {name: base64}, layouts}`) as a document. */
  function exampleDoc(ex) {
    const files = Object.create(null);
    for (const [n, b64] of Object.entries(ex.files || {})) files[n] = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    return doc(ex.xml, files, ex.layouts || {});
  }

  root.YoumoMaker = {
    THEME_XML, UNDO_LIMIT, DEVICES, KEYBOARD_DP, FORMS, BOARDS, ENV, FIELDS, IMES,
    doc, themeName, withName, fromBuiltin, PICTURE_SAVE, DECODE_BUDGET, pictureFile, saveProblems, decodableImages, open, save, same, History, keepDraft, draft, dropDraft, idbStore, envJson, check, layoutOf, exportCheck, animatedWebp, animatedStill, exampleDoc,
  };
})(typeof window !== 'undefined' ? window : globalThis);
