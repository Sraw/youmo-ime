// youmo-maker: the theme maker's document (a 4.0 pack), apart from the page so node tests it.
(function (root) {
  'use strict';

  const F = root.YoumoForm;
  if (!F) throw new Error('youmo-maker needs youmo-form loaded first');
  const { uncommented, unescape, escape } = F;
  // either quote, spaces around '=' (as XML allows and the app reads)
  const srcOf = (tag, attr) => new RegExp('<(' + tag + ')\\b[^>]*?\\b(' + attr + ')\\s*=\\s*(?:"([^"]+)"|\'([^\']+)\')', 'g');
  const fileOf = (m) => unescape(m[3] ?? m[4]);
  const IMAGE_FN = /(?<![\w-])(image|ripple-image|particles)\(\s*([^,)\s]+)/g;

  const THEME_XML = 'theme.xml';
  const LAYOUTS = 'layouts/';
  const UNDO_LIMIT = 100;

  function doc(xml, files = {}, layouts = {}) {
    // own keys copied one by one: a file named __proto__ stays a file
    const copy = (o) => { const out = Object.create(null); for (const k of Object.keys(o)) out[k] = o[k]; return out; };
    return { xml, files: copy(files), layouts: copy(layouts) };
  }

  function themeName(xml) {
    const t = F.elements(xml, 'theme')[0];
    const a = t && t.attrs.find((x) => x[0] === 'name');
    return a ? a[1] : null;
  }

  function withName(xml, name) {
    const t = F.elements(xml, 'theme')[0];
    if (!t) return xml;
    const tag = t.tag;
    // attribute by attribute, so a ' name=' inside another's quoted value is never the one changed
    let at = null;
    for (const a of tag.matchAll(/(\s)([^\s=/>]+)\s*=\s*("[^"]*"|'[^']*')/g)) if (a[2] === 'name') { at = a; break; }
    const named = at
      ? tag.slice(0, at.index) + at[1] + 'name="' + escape(name) + '"' + tag.slice(at.index + at[0].length)
      // a function, not a string: a `$&` or `$'` in the name is the name's, never a replacement pattern
      : tag.replace(/^<theme\b/, (open) => open + ' name="' + escape(name) + '"');
    return xml.slice(0, t.start) + named + xml.slice(t.start + tag.length);
  }

  /** A new document of the app's theme [id] (engine's themeSource), named apart from it so an import adds a theme. */
  function fromBuiltin(E, id) {
    const xml = E.themeSource(id);
    if (xml == null) throw new Error('no built-in theme ' + id);
    return doc(withName(xml, id + '-mine'));
  }


  // a picture the user picks is never kept as it was (the user's ruling, 2026-11-09): always encoded anew, its long
  // side no more than longSide (never made bigger), JPEG at quality; PNG only where it has transparency. The one
  // exception: an animated WebP the app plays (keepsAnimated) is kept as picked
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

  // RasterIcon.named: by the file's suffix, whatever its bytes are
  const RASTER_NAME = /\.(png|webp)$/i;

  // RasterIcon.problem in the page's words: a still PNG or WebP within [side] and [max] bytes; the panels' checks too
  function rasterProblem(L, bytes, side, max) {
    const r = L.raster(bytes);
    if (!r) return '不是 PNG 或 WebP 图片';
    // a side of 0 the app reads as no image
    if (!r.size) return '读不出图片的大小';
    if (r.animated) return '动态 WebP，App 只收静态图';
    if (Math.max(r.size[0], r.size[1]) > side) return r.size[0] + '×' + r.size[1] + '，边长最多 ' + side;
    if (bytes.length > max) return '超过 ' + Math.round(max / 1024) + ' KB';
    return null;
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
    // the app refuses a pack named as a built-in theme or pack (ThemeFilesManager.packClash): the names the page is
    // given, looked up as ThemePackFiles.builtinClash does, by the name and, for two versions, each version's ref
    else if ([name, ...(read.theme.variants.length > 1 ? read.theme.variants.map((v) => name + '#' + v) : [])].some((n) => (format.builtinNames || []).includes(n))) bad('主题名 "' + name + '" 是 App 内置主题的名字，App 不会收；换一个名字');
    const xml = uncommented(d.xml);
    const named = new Set();
    for (const m of xml.matchAll(srcOf('picture|cut|font|icon|sound', 'src|file'))) named.add(fileOf(m) + '|' + m[1]);
    for (const m of xml.matchAll(IMAGE_FN)) named.add(m[2].replace(/^["']|["']$/g, '') + '|' + (m[1] === 'image' ? 'image' : 'effect'));
    for (const m of xml.matchAll(srcOf('meta', 'preview'))) named.add(fileOf(m) + '|preview');
    for (const file of F.stickers(xml)) named.add(file + '|sticker');
    for (const key of named) {
      const [file, role] = key.split('|');
      const bytes = d.files[file];
      // the app draws a sticker the pack lacks as what it hides (ThemePackFiles.checkFiles)
      if (!bytes) { if (role !== 'sticker') bad('缺少文件 ' + file); continue; }
      if (role === 'picture' || role === 'cut' || role === 'image') {
        const s = L.imageSize(bytes);
        const side = role === 'image' ? lim.keyImageSide : lim.pictureSide;
        if (!s) bad(file + ' 不是 App 认得的图片');
        else if (Math.max(s[0], s[1]) > side) bad(file + ' 太大：' + s[0] + '×' + s[1] + '，最多 ' + side);
        else if (role === 'image' && bytes.length > lim.keyImageBytes) bad(file + ' 超过 ' + lim.keyImageBytes + ' 字节');
      } else if (role === 'effect') {
        const s = L.imageSize(bytes);
        if (!/\.(png|webp)$/i.test(file)) bad(file + '：特效图片只能是 .png 或 .webp');
        else if (!s) bad(file + ' 不是 App 认得的图片');
        else if (lim.effectImageSide != null && Math.max(s[0], s[1]) > lim.effectImageSide) bad(file + ' 太大：' + s[0] + '×' + s[1] + '，特效图片最多 ' + lim.effectImageSide);
        else if (lim.effectImageBytes != null && bytes.length > lim.effectImageBytes) bad(file + ' 超过 ' + lim.effectImageBytes + ' 字节（特效图片）');
      } else if (role === 'preview') {
        const s = L.imageSize(bytes);
        if (!s || !['png', 'webp'].includes(L.imageType(bytes))) bad(file + ' 不是 PNG 或 WebP');
        else if (Math.max(s[0], s[1]) > 2048 || bytes.length > 512 * 1024) bad(file + ' 太大：预览图最大 2048×2048、512 KB');
      } else if ((role === 'icon' && RASTER_NAME.test(file)) || role === 'sticker') {
        // RasterIcon's checks: an icon or a sticker named .png or .webp is that, not an SVG
        const why = rasterProblem(L, bytes, role === 'icon' ? lim.rasterIconSide : lim.stickerSide, role === 'icon' ? lim.rasterIconBytes : lim.stickerBytes);
        if (why) bad(file + (role === 'icon' ? '（位图图标）' : '（贴纸）') + '：' + why);
      } else if (role === 'icon') {
        // the app keeps any other icon only as an SVG of filled paths (SvgSubset)
        let text = null;
        try { text = new TextDecoder('utf-8', { fatal: true }).decode(bytes); } catch (e) { /* not text: no SVG */ }
        const r = text == null ? null : E.readIcon(text);
        if (lim.iconBytes != null && bytes.length > lim.iconBytes) bad(file + ' 超过 ' + lim.iconBytes + ' 字节');
        else if (!r) bad(file + ' 不是 SVG（不是 .png、.webp 的图标 App 当 SVG 读）');
        else if (!r.ok) bad(file + '：' + r.problems[0].message);
      } else if (role === 'sound') {
        const r = E.readSound(file, bytes);
        if (!r.ok) bad(r.problems[0].message);
      }
    }
    if (root.YoumoPet) out.push(...root.YoumoPet.petProblems(L, lim.pet, read, d));
    const animSrcs = new Set([false, true].map((dark) => read.theme.info(dark).picture).filter((p) => p && p.animated).map((p) => p.src));
    for (const animSrc of animSrcs) {
      if (!d.files[animSrc]) continue;
      const why = animatedStill(lim, d.files[animSrc]);
      if (why) out.push({ level: 'warning', line: 0, message: animSrc + ' 在 App 里会显示成静态图：' + why });
    }
    const layouts = Object.create(null);
    for (const [n, t] of Object.entries(d.layouts)) layouts[n] = t;
    for (const where of E.blankKeys(read.theme, layouts)) {
      if (where === '*') bad('键的样式用的场景组合太多，没法逐一检查 Shift、退格、空格和回车会不会成空白键（App 会拒收）');
      else bad(where + '：字藏了，键面又不是图片——Shift、退格、空格或回车会是空白键');
    }
    const enc = new TextEncoder();
    const names = Object.keys(d.layouts);
    const boards = new Set(E.layouts());
    for (const n of names) if (!n.endsWith('.xml') || !L.isPlainFileName(n)) bad('layouts/' + n + ' 不是 App 认得的布局文件名（<键盘>.xml）');
    if (names.length > lim.layouts) bad('自带的键盘布局 ' + names.length + ' 个，App 最多收 ' + lim.layouts + ' 个');
    for (const n of names) if (enc.encode(d.layouts[n]).length > lim.layoutBytes) bad('layouts/' + n + ' 超过 ' + lim.layoutBytes + ' 字节');
    for (const n of names) {
      const id = n.replace(/\.xml$/, '');
      if (boards.has(id) && !E.replaces(id)) out.push({ level: 'warning', line: 0, message: 'layouts/' + n + '：范围内的布局在 0.6.1 起要在布局列表里选（导入主题时放进 外观 → 布局，不再顶替这个键盘）' });
      else if (!E.replaces(id)) out.push({ level: 'warning', line: 0, message: 'layouts/' + n + '：App 没有这个键盘，不会用它' });
    }
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

  const DECODE_BUDGET = 64 * 1000 * 1000;

  /** Which files the page may decode: pack bytes are untrusted, and a decode past the browser's memory takes the tab down. */
  function decodableImages(L, format, d, only = null) {
    const xml = uncommented(d.xml);
    const roles = new Map();
    const name = (n, role) => { if (!roles.has(n)) roles.set(n, role); };
    for (const m of xml.matchAll(srcOf('picture', 'src'))) name(fileOf(m), 'source');
    for (const m of xml.matchAll(srcOf('cut', 'file'))) name(fileOf(m), 'picture');
    for (const m of xml.matchAll(IMAGE_FN)) if (m[1] === 'image') name(m[2].replace(/^["']|["']$/g, ''), 'key');
    for (const m of xml.matchAll(srcOf('icon', 'src'))) if (RASTER_NAME.test(fileOf(m))) name(fileOf(m), 'icon');
    for (const file of F.stickers(xml)) name(file, 'sticker');
    let spent = 0;
    const out = [];
    for (const [n, role] of roles) {
      if (only && !only.includes(role)) continue;
      const bytes = d.files[n];
      const size = bytes && /\.(png|jpe?g|webp)$/i.test(n) ? L.imageSize(bytes) : null;
      if (!size || !L.decodable(size, L.decodeCaps(format, role))) continue;
      if (spent + size[0] * size[1] > DECODE_BUDGET) continue;
      spent += size[0] * size[1];
      out.push(n);
    }
    return out;
  }

  // a file refused (youmo-lib's ThemeError codes) in words that say what to do: the theme maker's and the layout page's
  function openError(e) {
    if (e && e.reason) return skinError(e);
    switch (e && e.code) {
      case 'limit': return '文件太大或文件太多，超过了 App 的限制';
      case 'newer': return '它是更新版本的幽默输入法做的（' + e.detail + '），先刷新这个网页再试';
      case 'older': return '它是很旧的版本（' + e.detail + '），先导入 App 再从 App 导出';
      case 'not-theme': return '这不是主题文件（.theme 或 theme.xml）';
      case 'missing': return '主题里写到的文件 ' + e.detail + ' 包里没有';
      case 'bad': return '主题文件有错：' + e.detail;
      default: return (e && e.message) || String(e);
    }
  }

  const decode = (bytes) => new TextDecoder().decode(bytes);
  const isZip = (bytes) => bytes.length >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b;

  /**
   * [bytes] (a file named [fileName]) as a document: `{doc, notes}`, its min-app= filled in (a `min-app` note); a skin
   * of another input method converted ([io]: openSkin's), its `skin` too.
   */
  async function open(E, L, format, bytes, fileName = '', io = null) {
    const r = await openAsIs(E, L, format, bytes, fileName, io);
    const fixed = withMinApp(E, r.doc);
    if (fixed) {
      r.doc = fixed.doc;
      r.notes.push({ kind: 'min-app', version: fixed.version });
    }
    return r;
  }

  async function openAsIs(E, L, format, bytes, fileName, io) {
    if (fileName && isSkinFile(fileName) && extension(fileName) !== 'zip') return openSkin(E, L, bytes, fileName, io);
    if (!isZip(bytes)) {
      if (bytes.length > format.limits.fileBytes) throw new L.ThemeError('limit', 'fileBytes');
      const text = decode(bytes).replace(/^﻿/, '');
      if (!/^\s*</.test(text)) throw new L.ThemeError('not-theme', 'not a theme file');
      return { doc: doc(text), notes: [] };
    }
    // a .theme is the app's theme pack alone (ThemePackReader), and a page without the browser's means opens none
    const skinToo = !!io && !!fileName && extension(fileName) !== 'theme';
    let entries = null;
    let refused = null;
    try { entries = L.readZip(bytes); } catch (e) { if (!skinToo) throw e; refused = e; }
    // a ZIP with neither a theme.xml nor one 3.0 or 2.1 theme's json at the top is a skin's (FlorisBoard's has its extension.json)
    const top = (n) => entries.some((e) => !e.name.endsWith('/') && L.entryPath(e.name).join('/') === n);
    const jsons = entries && entries.filter((e) => /^[^/]+\.json$/.test(L.entryPath(e.name).join('/')) && !e.name.startsWith('._') && !e.name.endsWith('/'));
    if (skinToo && (!entries || (!top(THEME_XML) && (jsons.length !== 1 || top('extension.json'))))) {
      try {
        return await openSkin(E, L, bytes, fileName, io);
      } catch (e) {
        // no skin either: a broken ZIP keeps the theme's own words
        if (refused && ['NOT_ZIP', 'UNKNOWN', 'BROKEN'].includes(e.reason)) throw refused;
        throw e.reason === 'UNKNOWN' ? new L.SkinError('NOT_THEME', fileName.slice(fileName.lastIndexOf('/') + 1)) : e;
      }
    }
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

  function save(L, d) {
    const enc = new TextEncoder();
    const entries = [[THEME_XML, enc.encode(d.xml)]];
    for (const n of Object.keys(d.files).sort()) entries.push([n, d.files[n]]);
    for (const p of Object.keys(d.layouts).sort()) entries.push([LAYOUTS + p, enc.encode(d.layouts[p])]);
    return { name: (themeName(d.xml) || 'theme') + '.theme', bytes: L.writeZip(entries) };
  }

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

  /** A failing store (private window) means no drafts, never a broken page. */
  const DRAFT = 'draft';
  // files are written apart from the text, only when changed; a text read with files not its own is no draft, never a mix
  const DRAFT_FILES = 'draft-files'; // the one files record of the pages before (drafts 2 and 3)
  const filesKey = (id) => DRAFT_FILES + '-' + id;
  const keptFiles = new WeakMap();
  const wrote = new WeakSet(); // the pages whose text has gone in
  // a page's first write lets go of a closed page's record, which without the lock may be one an open tab is about to name
  async function locked(store, act) {
    if (store.lock) {
      try {
        return await store.lock(() => act(true));
      } catch (e) {
      }
    }
    return act(false);
  }
  function keepDraft(store, d, env) {
    return locked(store, (serial) => keepDraftNow(store, d, env, serial));
  }
  async function keepDraftNow(store, d, env, serial) {
    try {
      const before = await store.get(DRAFT);
      const prev = before && before.v === 4 ? before.files : null;
      const first = !wrote.has(store);
      let kept = keptFiles.get(store);
      if (!kept || kept.files !== d.files || prev !== kept.id) {
        const id = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
        kept = { files: d.files, id, own: (kept ? kept.own : []).concat(id) };
        keptFiles.set(store, kept);
        await store.put(filesKey(id), { id, files: d.files });
      }
      await store.put(DRAFT, { v: 4, doc: { xml: d.xml, layouts: d.layouts }, files: kept.id, env, at: Date.now() });
      wrote.add(store);
      const gone = kept.own.filter((x) => x !== kept.id);
      if (first && serial) {
        for (const k of await store.keys()) {
          if (typeof k !== 'string' || !k.startsWith(DRAFT_FILES + '-')) continue;
          const id = k.slice(DRAFT_FILES.length + 1);
          if (id !== kept.id && !gone.includes(id)) gone.push(id);
        }
      }
      kept.own = [kept.id];
      for (const x of gone) await store.remove(filesKey(x));
      if (before && before.v !== 4) await store.remove(DRAFT_FILES);
      return true;
    } catch (e) {
      return false;
    }
  }
  // read under the lock too: a write in between lets go of the record the text read before it named
  function draft(store) {
    return locked(store, () => draftNow(store));
  }
  async function draftNow(store) {
    try {
      const v = await store.get(DRAFT);
      if (!v || ![1, 2, 3, 4].includes(v.v) || !v.doc || typeof v.doc.xml !== 'string') return null;
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
  function dropDraft(store) {
    return locked(store, () => dropDraftNow(store));
  }
  async function dropDraftNow(store) {
    try {
      const v = await store.get(DRAFT);
      await store.remove(DRAFT);
      if (v && v.v === 4) await store.remove(filesKey(v.files));
      const kept = keptFiles.get(store);
      for (const x of kept ? kept.own : []) await store.remove(filesKey(x));
      await store.remove(DRAFT_FILES);
      keptFiles.delete(store);
      wrote.delete(store);
    } catch (e) {
    }
  }

  /** IndexedDB as a draft store (pictures are too big for localStorage); null where the browser has none. */
  function idbStore(idb, name = 'youmo-theme-maker') {
    if (!idb) return null;
    let db = null;
    const opened = () => (db ??= new Promise((ok, fail) => {
      const r = idb.open(name, 1);
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
    const locks = globalThis.navigator && navigator.locks;
    return {
      get: (k) => run('readonly', (s) => s.get(k)),
      put: (k, v) => run('readwrite', (s) => s.put(v, k)),
      remove: (k) => run('readwrite', (s) => s.delete(k)),
      keys: () => run('readonly', (s) => s.getAllKeys()),
      lock: locks ? (act) => locks.request(name + '-draft', act) : undefined,
    };
  }


  const DEVICES = [
    { id: 'oneplus-6t', label: 'OnePlus 6T', w: 1080, h: 2340, density: 2.625 },
    { id: 'pixel-8', label: 'Pixel 8', w: 1080, h: 2400, density: 2.625 },
    { id: 'pixel-8-pro', label: 'Pixel 8 Pro', w: 1344, h: 2992, density: 3.5 },
    { id: 'galaxy-s24', label: 'Galaxy S24', w: 1080, h: 2340, density: 3 },
    { id: 'small', label: '小屏 720p', w: 720, h: 1280, density: 2 },
    { id: 'tablet', label: '平板 10"', w: 1600, h: 2560, density: 2 },
  ];
  const KEYBOARD_DP = { portrait: 267, landscape: 192, landscapeLarge: 224 };
  const LARGE_SHORT_SIDE_DP = 600;

  function shape(E, e) {
    const width = e.landscape ? Math.max(e.w, e.h) : Math.min(e.w, e.h);
    const landscape = !!E.screenLandscape(!!e.landscape, Math.round(width / e.density));
    const dp = !landscape ? KEYBOARD_DP.portrait : Math.min(e.w, e.h) / e.density >= LARGE_SHORT_SIDE_DP ? KEYBOARD_DP.landscapeLarge : KEYBOARD_DP.landscape;
    return { landscape, width, height: Math.round(dp * e.density) };
  }
  const FORMS = ['normal', 'compact', 'floating', 'left', 'right', 'split', 'flat'];
  const BOARDS = ['qwerty', 'nine', 'k14', 'k18', 'zhuyin', 'zhuyin-nine', 'number', 'symbols'];

  const ENV = {
    device: 'oneplus-6t', w: 1080, h: 2340, density: 2.625, landscape: false, board: 'qwerty', form: 'normal', dark: false, shift: 'none',
    field: 'text', ime: 'pinyin', show: '', bar: 'idle', panel: '',
  };
  /** A choice the form no longer offers falls back to the default (else the select empties). */
  function restoreEnv(kept, offered) {
    const k = kept && typeof kept === 'object' ? kept : {};
    const e = Object.assign({}, ENV);
    for (const key of Object.keys(ENV)) {
      const v = k[key];
      if (typeof v !== typeof ENV[key]) continue;
      if (typeof v === 'number' && !(Number.isFinite(v) && v > 0)) continue;
      if (offered[key] && !offered[key].includes(v)) {
        if (key === 'device') e.device = 'custom';
        continue;
      }
      e[key] = v;
    }
    return e;
  }
  const FIELDS = ['text', 'password', 'url', 'number', 'email'];
  const IMES = ['pinyin', 'shuangpin', 'english', 'wubi', 'cangjie'];

  /** As the app ships it (`punc.mb.zh_CN`): the test holds it to the app's file. */
  const ZH_PUNCTUATION = {
    '.': '。', ',': '，', '?': '？', '"': '“', ':': '：', ';': '；', "'": '‘', '<': '《', '>': '》', '\\': '、', '!': '！',
    '#': '#', '$': '$', '%': '%', '^': '……', '_': '——', '(': '（', ')': '）', '[': '【', ']': '】', '{': '{', '}': '}',
    '~': '～', '`': '`',
  };

  function envJson(E, e) {
    const s = shape(E, e);
    const out = { width: s.width, height: s.height, density: e.density };
    if (s.landscape) out.landscape = true;
    if (e.dark) out.dark = true;
    if (e.shift && e.shift !== 'none') out.shift = e.shift;
    if ((e.field && e.field !== 'text') || (e.ime && e.ime !== 'pinyin')) out.input = { field: e.field || 'text', ime: e.ime || 'pinyin' };
    if (e.form === 'compact') out.compact = true;
    else if (e.form === 'floating') out.floating = 1;
    else if (e.form === 'left' || e.form === 'right') out.oneHanded = e.form;
    else if (e.form === 'split') out.split = true;
    else if (e.form === 'flat') out.flat = true;
    if ((e.ime || 'pinyin') !== 'english') out.texts = { punctuation: ZH_PUNCTUATION };
    return out;
  }

  function layoutText(bytes, max, size = bytes.length) {
    if (bytes.length >= 2 && bytes[0] === 0x50 && bytes[1] === 0x4b) throw new Error('这是主题包（.theme），不是 .layout 布局文件');
    if (size > max) throw new Error('文件 ' + size + ' 字节，布局最大 ' + Math.round(max / 1024) + ' KB');
    let text;
    try {
      text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    } catch (e) {
      throw new Error('不是 UTF-8 文本');
    }
    return text.replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n');
  }

  function oldGroupFaces(text) {
    let out = text;
    let keys = 0;
    for (const k of F.elements(text, 'key').reverse()) {
      const a = Object.fromEntries(k.attrs);
      const m = /^\{group:([a-z;]{3,})\}$/.exec(a.label || '');
      if (!m || (a.letters || '').replace(/ /g, '') !== m[1]) continue;
      // whole attributes, so a "label=…" inside another's value is that value
      const at = [...k.tag.matchAll(/(\s+)([\w:.-]+)\s*=\s*("[^"]*"|'[^']*')/g)].find((x) => x[2] === 'label');
      if (!at) continue;
      const tag = k.tag.slice(0, at.index) + at[1] + 'label="' + m[1].toUpperCase() + '"' + k.tag.slice(at.index + at[0].length);
      out = out.slice(0, k.start) + tag + out.slice(k.start + k.tag.length);
      keys++;
    }
    return { xml: out, keys };
  }

  /**
   * A `.layout` file's [bytes] as 环境 › 键盘 draws the theme on it (061-custom-layout §10): `{id, name, layout}`, the
   * layout as the engine's readOwnLayout reads it, `oldFaces` its group keys' faces of a page before L8 drawn as now
   * (oldGroupFaces: the file itself the app still refuses); throws with what the page says otherwise (a zip, past the
   * app's size, not UTF-8, one that does not read: its first problem; one of a built-in board's id). A layout the
   * checker would refuse otherwise is still drawn.
   */
  function openLayout(E, bytes) {
    const read = oldGroupFaces(layoutText(bytes, E.layoutFormat().limits.bytes));
    const r = E.readOwnLayout(read.xml);
    if (!r.ok) {
      const p = r.problems[0];
      throw new Error((p && p.line ? '第 ' + p.line + ' 行：' : '') + (p ? p.message : '读不出'));
    }
    // the app's own board of this id is what 键盘 lists already, and a style picked on it would be that board's: the app's
    // import refuses it too (LayoutCheck id-built-in)
    if (E.layoutSource(r.layout.id) != null) throw new Error('id「' + r.layout.id + '」和 App 自带的键盘重名，App 不会导入它：换一个 id');
    return { id: r.layout.id, name: r.layout.name, layout: r.layout, oldFaces: read.keys };
  }

  /**
   * What is wrong with [d] drawn on [board] (an app board's id, or a layout of the user's as readOwnLayout reads it) in [env] (EnvJson): `[{level: "error"|"warning", line, message}]`,
   * the theme's own, then the scene's; `theme` and `scene` where they were made. An [env] with `marks` is drawn with
   * those whole (none of the defaults filled in for a member it leaves out); one without, with the board's default
   * marks (defaultMarksOf).
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
    // a layout of the user's (环境 › 键盘 › 打开 .layout, 061-custom-layout §10) drawn as it is: the pack's own boards only replace the app's
    const layout = typeof board === 'string' ? layoutOf(E, d, board, (list) => add('warning', list)) : board;
    const marks = env.marks ? null : defaultMarksOf(E, layout, env);
    const drawn = marks ? Object.assign({}, env, { marks }) : env;
    const s = E.buildScene(layout, r.theme, drawn, measurer || E.fixedMeasurer());
    add('error', s.problems);
    add('warning', s.ok ? s.scene.json().warnings : s.warnings);
    return { problems, theme: r.theme, scene: s.ok ? s.scene : null };
  }

  /**
   * The marks [layout] (an own layout or a built-in's id) has on a new install (060-keys-unify C2: each key's first,
   * shortcuts' icons), each first shown as [env]'s punctuation maps it, as the app does. The layout family's (pinyin's
   * 26 keys, ;-first 18 keys): the engine takes no input method, so 双拼 here shows pinyin's. Kept per layout and map
   * (each edit checks again, the export check each board twice); an id the engine has no board of gets none, so
   * buildScene says what is wrong with it.
   */
  const defaultMarksCache = new Map();
  function defaultMarksOf(E, layout, env) {
    const punctuation = (env.texts && env.texts.punctuation) || null;
    let byMap = defaultMarksCache.get(layout);
    if (!byMap) {
      byMap = new Map();
      if (defaultMarksCache.size > 64) defaultMarksCache.clear();
      defaultMarksCache.set(layout, byMap);
    }
    if (!byMap.has(punctuation)) byMap.set(punctuation, typeof layout === 'string' && !E.layout(layout) ? null : E.defaultMarks(layout, punctuation));
    return byMap.get(punctuation);
  }

  /**
   * The keyboard [board] is drawn with: the pack's own `layouts/<board>.xml` where it has one the app reads (PackLayouts.replaces: 注音's, the emoji's…) that reads (its includes
   * from the pack's other files there, else the app's), else the app's (its id). One that does not read is [warn]ed
   * of, the app's drawn, as the app does (ThemePackLayouts: logged, its own drawn). One taken is drawn as the app takes
   * it: its keys' long press, corner and swipes the app's keys' of their names (主题只管外观), what it writes of them
   * [warn]ed of, file by file, with the lines.
   */
  function layoutOf(E, d, board, warn) {
    const own = d.layouts[board + '.xml'];
    // a board the app draws its own of since 0.6.1 (26 键, 14/18 键…: the pack's is one of the user's layouts once imported)
    if (own == null || !E.replaces(board)) return board;
    const fragments = Object.create(null);
    // the pack's fragments the app reads: those the board includes, only under an id of its own (ThemePackLayouts.taken,
    // .read); never the pack's other boards, keyboards that fail it read as fragments
    for (const inc of F.elements(own, 'include')) {
      const src = Object.fromEntries(inc.attrs).src;
      if (src && src !== board && E.replaces(src) && typeof d.layouts[src + '.xml'] === 'string') fragments[src] = d.layouts[src + '.xml'];
    }
    const l = E.readLayout(own, fragments, board);
    if (!l.ok) {
      warn(l.problems.map((p) => ({ line: 0, message: 'layouts/' + board + '.xml' + (p.line ? ' 第 ' + p.line + ' 行' : '') + '：' + p.message + '（App 会用自己的这个键盘）' })));
      return board;
    }
    // as typable as the app's, else the app's drawn (ThemePackLayouts.judge, the same rule: PackLayouts)
    const why = E.refuses(board, l.layout);
    if (why == null) {
      warn(ignoredNotice(l.ignored));
      return l.layout;
    }
    warn([{ line: 0, message: 'layouts/' + board + '.xml：App 不会用它（' + why + '），会用自己的这个键盘' }]);
    return board;
  }

  /**
   * What a pack's layout writes of its keys held or swiped that the app does not take ([ignored], readLayout's), one
   * warning a file: its lines, each once, in order.
   */
  function ignoredNotice(ignored) {
    const lines = new Map();
    for (const i of ignored || []) {
      if (!lines.has(i.file)) lines.set(i.file, new Set());
      if (i.line) lines.get(i.file).add(i.line);
    }
    return [...lines].map(([file, set]) => {
      const at = set.size ? '第 ' + [...set].sort((a, b) => a - b).join('、') + ' 行的' : '';
      return { line: 0, message: 'layouts/' + file + '.xml：' + at + '长按、角标、滑动写法不起作用（0.6.1 起主题只管样子，这些跟着键的名字，在 App 的 按键 → 按键字符和快捷键 里设）' };
    });
  }

  /**
   * The icons the preview draws: [base] (the app's, by name) with the theme's own <icon>s of [d] over them, each as the
   * engine reads it (readIcon, what the app draws), a PNG or WebP one as [images] has it decoded. One that does not read is left out, so
   * the app's of that name is drawn (as the app does with a broken file). Read once per file's bytes ([cache], a WeakMap).
   */
  function iconsOf(E, base, d, cache, images = {}) {
    const out = Object.assign({}, base);
    for (const e of F.elements(d.xml, 'icon')) {
      const a = Object.fromEntries(e.attrs);
      const name = a.name;
      const bytes = d.files[a.src];
      if (!name || !bytes) continue;
      // a raster one as the page decoded it ([images]: `{image, width, height}`), else the app's of its name
      if (RASTER_NAME.test(a.src)) {
        if (images[a.src]) out[name] = images[a.src];
        continue;
      }
      if (!cache.has(bytes)) {
        let r = null;
        try { r = E.readIcon(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); } catch (e) { /* not text: not drawn */ }
        cache.set(bytes, r && r.ok ? r.icon : null);
      }
      if (cache.get(bytes)) out[name] = cache.get(bytes);
    }
    return out;
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

  /**
   * Whether a picture picked on the 背景 page is kept as it is, its file the picked one's: an animated WebP the app plays
   * ([animatedStill] none). Every other picture is encoded anew; the cuts (the still ones) are made either way.
   */
  const keepsAnimated = (lim, bytes) => animatedStill(lim, bytes) == null;

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
   * [env] (EnvJson) light and dark, and what the app refuses on import (saveProblems), each once, of [d0] with its
   * min-app= filled in (withMinApp). `{errors, warnings, minApp}`: an error stops the save, a warning is the user's to
   * ignore; minApp withMinApp's, the document to save where it is not null.
   */
  function exportCheck(E, L, format, d0, boards, env) {
    const minApp = withMinApp(E, d0);
    const d = minApp ? minApp.doc : d0;
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
    return { errors: all.filter((p) => p.level === 'error'), warnings: all.filter((p) => p.level !== 'error'), minApp };
  }

  // LayoutCheck.BASE_VERSION: a pack using nothing newer is saved as before, with no min-app= added
  const BASE_APP = '0.6.0';

  /**
   * [d] with its min-app= the oldest app that reads all of it (the theme's info().minApp, each layout's
   * LayoutCheck.minApp) where it says none or an older one and that is past BASE_APP: `{doc, version}`, else null.
   */
  function withMinApp(E, d) {
    const read = E.readTheme(d.xml, null, null);
    if (!read.ok) return null;
    const info = read.theme.info(false);
    let need = info.minApp;
    const newest = E.layoutFormat().version;
    for (const t of Object.values(d.layouts)) {
      const r = E.checkLayout(t, newest, read.theme);
      if (r.minApp && E.newer(r.minApp, need)) need = r.minApp;
    }
    const said = info.meta && info.meta.minApp;
    if (!E.newer(need, BASE_APP) || !E.newer(need, said || BASE_APP)) return null;
    return { doc: doc(F.setMeta(d.xml, 'min-app', need), d.files, d.layouts), version: need };
  }

  /** What the page says once it filled min-app= in. */
  const minAppNotice = (version) => '用到了 ' + version + ' 的写法，已把最低版本设为 ' + version;

  // ---- another input method's skin, converted by the engine (SkinConverter), its files unpacked and decoded here ----

  /** ThemeFileName.SKIN_EXTENSIONS: what is opened as another input method's skin. */
  const SKIN_EXTENSIONS = ['bds', 'bdi', 'bda', 'ssf', 'it', 'flex', 'yaml', 'yml', 'zip', 'cskin', 'sxi', 'apk', 'pack'];
  // SkinZip.INNER: a skin's own file that is a ZIP
  const INNER = ['flex', 'bds', 'bdi', 'bda', 'ssf', 'it'];
  const extension = (name) => {
    const leaf = name.slice(name.lastIndexOf('/') + 1);
    const dot = leaf.lastIndexOf('.');
    return dot > 0 ? leaf.slice(dot + 1).toLowerCase() : '';
  };
  const isSkinFile = (name) => SKIN_EXTENSIONS.includes(extension(name));
  const macFile = (n) => n.startsWith('__MACOSX/') || n.slice(n.lastIndexOf('/') + 1).startsWith('._');
  const isContainer = (b) => b.length >= 4 && b[0] === 0x53 && b[1] === 0x6b && b[2] === 0x69 && b[3] === 0x6e;
  /** All the decoded images of one skin together: 160 MB as RGBA. */
  const SKIN_PIXELS = 40e6;

  /** SkinFormat's ids in the app's words (SkinImport.FORMATS). */
  const SKIN_FORMATS = {
    shouxin: '手心输入法的皮肤', gboard: 'Gboard 的主题', cang: '仓输入法的皮肤', apk: '安装包（APK）里的主题',
    'sogou-diy': '搜狗的自制皮肤（只有图片和颜色，没有键盘布局）',
  };

  /** A SkinError (the page's or the engine's) in the app's words (SkinImport.refused); BROWSER the page's own. */
  function skinError(e) {
    switch (e.reason) {
      case 'NOT_ZIP': case 'BROKEN': return '皮肤文件损坏了，或者不是皮肤';
      case 'UNKNOWN': return '这个文件不是能转换的皮肤：能转换的是百度（.bds、.bdi、.bda）、讯飞（.it）、搜狗手机版（.ssf）的皮肤和 FlorisBoard（.flex）、同文（.yaml）的主题';
      case 'NOT_THEME': return '只能导入主题文件（.theme）和别的输入法的皮肤（百度 .bds、.bdi、.bda，讯飞 .it，搜狗 .ssf，FlorisBoard .flex，同文 .yaml，以及装着它们的 .zip）：' + e.detail + ' 都不是。以前导出的 .zip 主题改名成 .theme 就能导入。';
      case 'TOO_BIG': return '皮肤文件超过 64 MB';
      case 'TOO_MANY_FILES': return '主题里的文件超过 5000 个';
      case 'UNPACKED_TOO_BIG': return '皮肤里有文件解压后太大';
      case 'ENCRYPTED': return '皮肤里有加密或用读不了的方式压缩的文件';
      case 'UNSAFE_NAME': return '皮肤里有文件的名字指向皮肤以外的位置：' + e.detail;
      case 'SOGOU_ENCRYPTED': return '这个搜狗皮肤读不出来（文件可能不完整或是新的格式）';
      case 'SOGOU_PC': return '这是搜狗电脑版的皮肤：里面没有键盘可以转换';
      case 'NO_KEYBOARD': return '皮肤里没找到键盘';
      case 'UNSUPPORTED': return (SKIN_FORMATS[e.detail] || e.detail) + ' 暂不支持导入';
      case 'BROWSER': return '这个浏览器不能导入皮肤，请用新版 Chrome、Edge、Firefox 或 Safari';
      default: return e.message || String(e.reason);
    }
  }

  /** SkinNote's words without arguments, as the app's (ThemePackDialogs.SKIN_NOTES). */
  const SKIN_NOTES = {
    LAYOUT: '按键的位置和大小（用本输入法自己的布局）',
    DECORATION: '盖在键盘上的装饰图',
    ANIMATION: '按键动画里缩放、下沉以外的部分',
    KEYBOARD_ANIMATION: '键盘出现时的动画',
    FRAME_ANIMATION: '帧动画、GIF、Lottie 和视频',
    TEXT_SIZE: '单个按键的字号（已就近调整）',
    FONT: '字体（只有字体名、没有字体文件，或字体文件太大）',
    KEY_ICONS: '部分按键图标（读不出或太大，用了本输入法自带的）',
    LETTER_IMAGES: '部分画成图片的字母（只取了颜色）',
    SOUND: '格式或长度不合要求的按键音',
    DARK_PICTURE: '深色版的背景图（太大或读不出，深色版用它的颜色）',
    NO_DARK: '深色版（皮肤只有一套，深色版用它的颜色）',
    MORE_PERIODS: '白天、夜间以外其他时段的样子',
    IMAGES: '读不出、过大或文件里没有的图片',
    MORE_THEMES: '文件里其他的主题和配色（只取一套浅色、一套深色）',
    PANELS: '本输入法自己画的面板的样子（剪贴板、表情、单手和悬浮模式）',
  };
  const SKIN_NOTES_HEAD = '从别的输入法的皮肤转换而来。以下没有转过来：';

  // LayoutTexts.lacks, as the layout page's (a test holds the two alike): [what, items, pads]
  const PAD_LABEL = { full: '完整', phone: '电话', datetime: '日期时间' };
  const LACKS = {
    backspace: '没有 ⌫：删不了字，打拼音时打错的字母去不掉（不在打字时可以用工具栏的文本编辑面板删）',
    enter: '没有回车：换不了行，也按不了发送、搜索',
    back: '没有返回键：回字母键盘要收起键盘再点输入框；数字输入框里一直是这块',
    'panel-back': '没有返回键：回字母键盘要收起键盘再点输入框',
    lang: '没有地球键：换输入法要把空格键长按行为设成切换输入法',
    'lang-no-space': '没有地球键：换输入法要把空格键长按行为设成切换输入法；也没有空格，空格长按也用不了',
    space: '没有空格键：打不出空格',
    shift: '没有 Shift：打不出大写',
    'nine-side': '没有九键左列的位置：左列不显示',
    'symbol-categories': '没有符号分类的位置：分类不显示',
    'symbol-grid': '没有符号格子的位置：没有符号可选',
  };
  function lacks([what, items = '', pads = '']) {
    const on = pads.split(' ').filter(Boolean).map((p) => PAD_LABEL[p] || p).join('、');
    if (what === 'pad-digits') return on ? '数字键盘「' + on + '」打不出 ' + items + '：这种输入框里键盘直接是这块' : '数字输入框里键盘直接是这块，打不出 ' + items;
    const said = what === 'digits' ? '没有数字键 ' + items
      : what === 'letters' ? '没有字母 ' + items + '（密码框里也用这块键盘）'
        : LACKS[what] || '少了键：' + [what, items, pads].filter(Boolean).join(' ');
    return on ? said + '（数字键盘「' + on + '」）' : said;
  }

  // a layout's <keyboard name=>, null where it has none
  function keyboardName(xml) {
    const m = /<keyboard\b[^>]*?\bname\s*=\s*"([^"]*)"/.exec(xml || '');
    return m ? unescape(m[1]) : null;
  }

  // the name the app's own layout of that id has (ThemePackDialogs.builtInName)
  const layoutName = (E, id) => keyboardName(E.layoutSource(id));

  /** A SkinNote (`{id, args}`) as the app says it, a line each (ThemePackDialogs.skinNote). */
  function skinNoteLines(E, { id, args }) {
    const count = args.length && /^-?\d+$/.test(args[0]) ? Number(args[0]) : null;
    const boards = () => args.map((b) => layoutName(E, b) || b).join('、');
    switch (id) {
      case 'LAYOUT_SOME': return ['部分键盘的按键位置（' + boards() + '：皮肤里的键和本输入法的对不上，或排不下）'];
      case 'LAYOUT_LACKS': return args.map((a) => a.split('\t')).map((a) => (layoutName(E, a[0]) || a[0]) + '里皮肤没画、布局里也没放——' + lacks(a.slice(1)));
      case 'LAYOUT_EXTRA': return ['皮肤里有、本输入法没有的键（' + boards() + '：数字行、工具栏等）'];
      case 'TOOLBAR_ICONS': return [count == null ? '部分工具栏和候选栏的图标' : '部分工具栏和候选栏的图标（' + count + ' 个没对上）'];
      case 'INCLUDES': return [count == null ? '引用了同文自带或包里没有的配置' : '引用了同文自带或包里没有的配置（' + count + ' 处）'];
      default: return [SKIN_NOTES[id] || id];
    }
  }

  // an image's sides by its header: PNG, JPEG, WebP, GIF, BMP; null where it says none
  function skinImageSize(L, b) {
    if (b.length >= 26 && b[0] === 0x42 && b[1] === 0x4d) {
      const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
      return [Math.abs(dv.getInt32(18, true)), Math.abs(dv.getInt32(22, true))];
    }
    const s = L.imageSize(b);
    return s && s[0] > 0 && s[1] > 0 ? s : null;
  }

  // SkinZip.readSkin: a lone YAML or Sogou container as itself; a ZIP holding one skin file and nothing a reader takes of its own, that one unpacked
  async function readSkin(E, L, lim, bytes, fileName) {
    if (bytes.length > lim.archiveBytes) throw new L.SkinError('TOO_BIG');
    const name = fileName.slice(fileName.lastIndexOf('/') + 1);
    if (['yaml', 'yml'].includes(extension(name))) {
      if (bytes.length > lim.textBytes) throw new L.SkinError('TOO_BIG');
      return [{ name, bytes }];
    }
    if (isContainer(bytes)) return [{ name, bytes }];
    const entries = await L.readSkinZip(bytes, lim);
    const files = entries.filter((e) => !macFile(e.name));
    const inners = files.filter((e) => INNER.includes(extension(e.name)));
    const own = files.some((e) => e.name.slice(e.name.lastIndexOf('/') + 1) === 'extension.json') || E.skinTaken(files.map((e) => e.name));
    if (inners.length !== 1 || own || isContainer(inners[0].bytes)) return entries;
    return L.readSkinZip(inners[0].bytes, lim, lim.totalBytes - entries.reduce((n, e) => n + e.bytes.length, 0));
  }

  // SkinConverter.open's container: the one file, or the one .ssf, where it is Sogou's
  function containerOf(entries) {
    const own = entries.filter((e) => !macFile(e.name));
    const ssf = own.filter((e) => e.name.toLowerCase().endsWith('.ssf'));
    const one = own.length === 1 ? own[0] : ssf.length === 1 ? ssf[0] : null;
    return one && isContainer(one.bytes) ? { one, left: own.reduce((n, e) => n + e.bytes.length, 0) } : null;
  }

  /**
   * Another input method's skin ([bytes], its file's name [fileName]) as a document: `{doc, notes, skin}`, the notes'
   * `{kind: 'skin', lines, layouts}`; [skin] what skinLayouts takes to add its layouts or take them out. [io]: the
   * browser's `{decode(bytes) → {width, height, data} | null, png({width, height, rgba}) → bytes}`, null where it has
   * none (BROWSER). Throws a SkinError.
   */
  async function openSkin(E, L, bytes, fileName, io) {
    if (!io) throw new L.SkinError('BROWSER');
    const lim = E.skinFormat();
    // a format told apart by its name alone is not read at all
    const unsupported = E.skinUnsupported(fileName, null);
    if (unsupported) throw new L.SkinError('UNSUPPORTED', unsupported);
    let entries = await readSkin(E, L, lim, bytes, fileName);
    // a container found again once one is opened (a ZIP's .ssf in a folder) opened too, twice at most
    for (let round = 0; round < 2; round++) {
      const c = containerOf(entries);
      if (round > 0 && !c) break;
      const prepared = Object.create(null);
      if (c) {
        const plain = await L.sogouPlain(c.one.bytes, Math.max(0, Math.min(lim.totalBytes, lim.totalBytes - c.left)), lim);
        if (plain) prepared[c.one.name] = plain;
      }
      const r = E.skinOpen(entries, prepared);
      if (!r.ok) throw new L.SkinError(r.reason || 'BROKEN', r.detail || '');
      entries = r.entries;
    }
    const decoded = Object.create(null);
    let spent = 0;
    for (const e of entries) {
      const size = skinImageSize(L, e.bytes);
      if (!size || size[0] * size[1] > lim.imagePixels || spent + size[0] * size[1] > SKIN_PIXELS) continue;
      let img = null;
      try { img = await io.decode(e.bytes); } catch (x) { /* read as one not decoded: the IMAGES note */ }
      if (!img) continue;
      spent += img.width * img.height;
      if (spent > SKIN_PIXELS) break;
      decoded[e.name] = img;
    }
    const c = E.convertSkin(entries, decoded, fileName);
    if (!c.ok) throw new L.SkinError(c.reason || 'BROKEN', c.detail || '');
    const files = Object.create(null);
    for (const f of c.files) {
      if (f.bytes) { files[f.name] = f.bytes; continue; }
      // SkinImport.png: halved till within its bytes, where it may be
      let img = f;
      let png = await io.png(img);
      while (f.shrink && png.length > f.maxBytes && img.width * img.height > 1) {
        img = E.skinResized(img, Math.max(1, img.width >> 1), Math.max(1, img.height >> 1));
        png = await io.png(img);
      }
      files[f.name] = png;
    }
    const layouts = Object.create(null);
    for (const l of c.layouts) layouts[l.id + '.xml'] = l.xml;
    const names = c.layouts.map((l) => keyboardName(l.xml) || l.id);
    const lines = c.notes.flatMap((n) => skinNoteLines(E, n));
    const skin = { themeXml: c.themeXml, themeXmlWithLayouts: c.themeXmlWithLayouts, layouts };
    return { doc: doc(c.themeXml, files), notes: [{ kind: 'skin', lines, layouts: names }], skin };
  }

  /** The app's confirmation of a skin's layouts (theme_pack_skin_layouts), the page's check box named. */
  const skinLayoutsText = (names) => '这个皮肤带 ' + names.length + ' 块键位：' + names.join('、') + '。勾上「带上皮肤的键位」，保存的主题就带着它们，导入后放进 外观 → 布局。';

  /**
   * [d] with [skin]'s layouts ([on]) or without them: its theme.xml the one written for that where it is still the
   * other one as converted, else as edited (withMinApp says the version on saving).
   */
  function skinLayouts(d, skin, on) {
    const layouts = Object.assign(Object.create(null), d.layouts);
    for (const n of Object.keys(skin.layouts)) {
      if (on) layouts[n] = skin.layouts[n];
      else if (layouts[n] === skin.layouts[n]) delete layouts[n];
    }
    const [from, to] = on ? [skin.themeXml, skin.themeXmlWithLayouts] : [skin.themeXmlWithLayouts, skin.themeXml];
    return doc(d.xml === from ? to : d.xml, d.files, layouts);
  }

  /** An example (the page's youmo-examples block: `{id, label, note, xml, files: {name: base64}, layouts}`) as a document. */
  function exampleDoc(ex) {
    const files = Object.create(null);
    for (const [n, b64] of Object.entries(ex.files || {})) files[n] = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    return doc(ex.xml, files, ex.layouts || {});
  }

  root.YoumoMaker = {
    THEME_XML, UNDO_LIMIT, DEVICES, KEYBOARD_DP, FORMS, BOARDS, ENV, FIELDS, IMES, ZH_PUNCTUATION,
    doc, themeName, withName, fromBuiltin, PICTURE_SAVE, DECODE_BUDGET, pictureFile, rasterProblem, saveProblems, decodableImages, openError, open, save, same, History, keepDraft, draft, dropDraft, idbStore, shape, envJson, restoreEnv, check, layoutText, oldGroupFaces, openLayout, layoutOf, ignoredNotice, exportCheck, animatedWebp, animatedStill, keepsAnimated, iconsOf, exampleDoc,
    withMinApp, minAppNotice, SKIN_EXTENSIONS, SKIN_PIXELS, SKIN_NOTES, SKIN_NOTES_HEAD, SKIN_FORMATS, isSkinFile, skinError, skinNoteLines, lacks, openSkin, skinLayouts, skinLayoutsText,
  };
})(typeof window !== 'undefined' ? window : globalThis);
