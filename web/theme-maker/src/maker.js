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
    return { xml, files: Object.assign(Object.create(null), files), layouts: Object.assign(Object.create(null), layouts) };
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

  /**
   * A new document of [base] (a document) with [image] (its bytes, [type] png/jpeg/webp) as the keyboard's
   * picture: the source and each orientation's cut the same file, the keyboard and the keys showing it.
   * The colours stay [base]'s (picking them from the picture is ImageTheme.make's, M6).
   */
  function withPicture(base, image, type) {
    const file = 'picture.' + (type === 'jpeg' ? 'jpg' : type);
    const picture = '  <picture src="' + file + '">\n    <cut orientation="portrait" file="' + file + '"/>\n    <cut orientation="landscape" file="' + file + '"/>\n  </picture>\n';
    let xml = base.xml.replace(/\s*<picture\b[\s\S]*?<\/picture>/, '');
    // after the last <colors> (a theme of two versions has one for each), before its fonts and styles
    const at = xml.lastIndexOf('</colors>') + '</colors>'.length;
    xml = xml.slice(0, at) + '\n' + picture.replace(/\n$/, '') + xml.slice(at);
    xml = xml.replace(/(\n?)(\s*<\/theme>)/, (all, nl, end) => '\n  <style select="keyboard" fill="picture"/>' + nl + end);
    const files = Object.assign(Object.create(null), base.files, { [file]: image });
    return doc(xml, files, base.layouts);
  }

  const decode = (bytes) => new TextDecoder().decode(bytes);
  const isZip = (bytes) => bytes.length >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b;

  /**
   * [bytes] (a file the user opened) as a document: a 4.0 pack (a zip with theme.xml at its top), a 3.0 or 2.1
   * theme file (youmo-lib reads it as the app does, the engine makes it 4.0), or a bare theme.xml.
   * `{doc, notes}`: notes say what was done to it (`converted`) and what it carries (`layouts`: their ids).
   * Throws youmo-lib's ThemeError (or an Error) where it is none of them.
   */
  async function open(E, L, format, bytes) {
    if (!isZip(bytes)) return { doc: doc(decode(bytes)), notes: [] };
    const entries = L.readZip(bytes);
    const top = entries.find((e) => e.name === THEME_XML);
    if (top) {
      const lim = format.limits;
      let total = 0;
      const files = {};
      const layouts = {};
      for (const [i, e] of entries.entries()) {
        const breach = L.entryBreach(lim, i + 1, e.size, total);
        if (breach) throw new L.ThemeError('limit', breach);
        total += e.size;
        if (e.name.endsWith('/') || e.name === THEME_XML) continue;
        const parts = L.entryPath(e.name);
        if (parts.length === 1 && !parts[0].startsWith('.')) files[parts[0]] = await e.read();
        else if (parts.length === 2 && parts[0] + '/' === LAYOUTS) layouts[parts[1]] = decode(await e.read());
      }
      const d = doc(decode(await top.read()), files, layouts);
      const notes = Object.keys(layouts).length ? [{ kind: 'layouts', ids: Object.keys(layouts).map((p) => p.replace(/\.xml$/, '')) }] : [];
      return { doc: d, notes };
    }
    const opened = await L.openZip(format, bytes);
    const r = E.convert30(L.writeTheme(format, opened.theme));
    if (!r.ok) throw new Error(r.problems.map((p) => p.message).join('; '));
    const files = {};
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
    doc, themeName, withName, fromBuiltin, withPicture, open, save, same, History, keepDraft, draft, dropDraft, idbStore, envJson, check,
  };
})(typeof window !== 'undefined' ? window : globalThis);
