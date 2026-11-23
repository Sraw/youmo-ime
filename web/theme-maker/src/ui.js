// youmo-ui: the theme maker page's controls; the document and its edits are youmo-maker's (node tests them).
(function () {
  'use strict';
  const E = globalThis['youmo-engine'] && globalThis['youmo-engine'].YoumoEngine;
  const L = window.YoumoLib;
  const M = window.YoumoMaker;
  const F = window.YoumoForm;
  const PANELS = window.YoumoPanels || [];
  const json = (id) => JSON.parse(document.getElementById(id).textContent);
  const FORMAT = json('youmo-format');
  const EXAMPLES = json('youmo-examples');
  const ICONS = json('youmo-engine-icons');
  const BAR_ICONS = json('youmo-bar-icons');
  const iconCache = new WeakMap();
  const iconsNow = () => M.iconsOf(E, ICONS, history.current, iconCache, assets.images);
  // the app's bar icons (hide-keyboard's ▾) under the theme's own
  const BAR_BASE = Object.assign({}, ICONS, BAR_ICONS.toolbarFallback);
  const barIconsNow = () => M.iconsOf(E, BAR_BASE, history.current, iconCache, assets.images);
  const $ = (id) => document.getElementById(id);

  const THEME_LABEL = { material: 'Material', pixel: 'Pixel', nord: 'Nord', 'deep-blue': 'Deep Blue', monokai: 'Monokai', 'amoled-black': 'AMOLED', monet: '系统取色' };
  const BOARD_LABEL = { qwerty: '26 键', nine: '九键', k14: '14 键', k18: '18 键', zhuyin: '注音', 'zhuyin-nine': '注音九键', number: '数字', symbols: '符号' };
  const FORM_LABEL = { normal: '普通', compact: '紧凑', floating: '悬浮', left: '单手（左）', right: '单手（右）', split: '分体', flat: '平铺' };
  const IMAGE = /\.(png|jpe?g|webp)$/i;
  const FONT = /\.(ttf|otf)$/i;

  let history = null;
  let env = Object.assign({}, M.ENV);
  let scene = null;
  let pressed = null;
  let picked = null; // the key tapped last: {board, key, layout} (layout: the one drawn, an id or own's)
  let own = null;
  const OWN = '@own';
  const OPEN_LAYOUT = '@open';
  const boardDrawn = () => (env.board === OWN && own ? own.layout : env.board);
  const boardName = () => (env.board === OWN && own ? own.id : env.board);
  let theme = null;
  let tab = PANELS.length ? PANELS[0].id : 'source';
  let assets = { key: null, images: {}, fonts: {}, cuts: {} };
  let loading = 0; // which loadAssets is the latest: an older one finishing late is dropped
  let edited = false;
  let keeping = null;
  let problems = [];
  // said in the problems till the next edit (min-app= filled in)
  let notice = null;
  let barH = 0;
  let playing = null;
  // pictures picked this session: cuts are made from them, not from the re-encoded file
  const originals = new Map();
  const store = (() => { try { return M.idbStore(window.indexedDB); } catch (e) { return null; } })();

  function toast(text) {
    const t = $('toast');
    // inside an open dialog (the top layer): else a toast behind a tall dialog is never seen
    const host = document.querySelector('dialog[open]') || document.body;
    if (t.parentNode !== host) host.append(t);
    t.textContent = text;
    t.classList.add('on');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => t.classList.remove('on'), 3500);
  }


  // [other]: a document of its own (another file, a built-in, an example), not an edit of the one shown
  function set(d, { fresh = false, other = false } = {}) {
    notice = null;
    const from = history && history.current;
    if (fresh || !history) history = new M.History(d);
    else if (!history.push(d)) return;
    else edited = true;
    if (!fresh && !other && fromSkin.has(from)) fromSkin.add(d);
    changed(fresh);
  }

  function keep() {
    if (!store || !edited || !history) return;
    clearTimeout(keeping);
    keeping = setTimeout(keepNow, 500);
  }
  // a store refusing (quota) is said once and the page goes on without drafts
  let keepFailed = false;
  function keepNow() {
    clearTimeout(keeping);
    keeping = null;
    if (!store || !edited || !history) return;
    M.keepDraft(store, history.current, env).then((ok) => {
      if (!ok && !keepFailed) toast('草稿存不下了（浏览器的存储满了）：关掉页面前记得保存');
      keepFailed = !ok;
    });
  }

  function changed(external = false) {
    const d = history.current;
    if ($('source').value !== d.xml) $('source').value = d.xml;
    $('name').textContent = M.themeName(d.xml) || '（无名）';
    $('top').inert = $('main').inert = false;
    $('undo').disabled = !history.canUndo;
    $('redo').disabled = !history.canRedo;
    skinShown(d);
    keep();
    return loadAssets(d).then((current) => { if (current) { preview(); refreshTab(external); } });
  }

  // Ctrl+Z there is the field's own undo, and only its text survives a rebuild
  let dirty = null;

  const typedIn = (el) => !!el && (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && !/^(checkbox|radio|button|range|color|file)$/.test(el.type)));
  const CONTROLS = 'input, select, textarea, button';

  // keeps [to]'s nodes; false where the controls differ (the tab is redrawn); [skip] keeps the field being typed in
  function sync(to, from, skip) {
    if (to.nodeType !== from.nodeType || to.nodeName !== from.nodeName) return false;
    if (to.nodeType === Node.TEXT_NODE) { if (to.data !== from.data) to.data = from.data; return true; }
    if (to.nodeType !== Node.ELEMENT_NODE) return true;
    // a control's handlers hold what it was drawn for: where that differs the part is replaced, never kept with stale handlers
    const what = (e) => (e.matches('label.row') ? e.querySelector('.what')?.textContent ?? null : null);
    if (to.dataset.key !== from.dataset.key || what(to) !== what(from)) {
      if (to.contains(skip)) return false;
      to.replaceWith(from);
      return true;
    }
    const kids = to.childNodes.length === from.childNodes.length;
    if (!to.matches(CONTROLS) && !to.querySelector(CONTROLS) && !from.querySelector(CONTROLS)) {
      if (!kids || !to.isEqualNode(from)) to.replaceWith(from);
      return true;
    }
    if (!kids || (to.tagName === 'INPUT' && to.type !== from.type)) return false;
    // read before the children: an <option> synced across is moved out of [from], and its value with it
    const value = 'value' in from ? from.value : undefined;
    const checked = from.checked;
    for (let i = from.childNodes.length - 1; i >= 0; i--) if (!sync(to.childNodes[i], from.childNodes[i], skip)) return false;
    if (to.className !== from.className) to.className = from.className;
    if (to.matches(CONTROLS) && to !== skip) {
      if (value !== undefined && to.value !== value) to.value = value;
      if (to.checked !== checked) to.checked = checked;
      to.disabled = from.disabled;
      to.title = from.title;
    }
    return true;
  }

  // values change in place, never the tab under the user: a redrawn tab took the tapped field, its focus or typing
  function refreshTab(external = false) {
    const el = $('tab-' + tab);
    const panel = PANELS.find((p) => p.id === tab);
    if (!panel || !history || !el || el.hidden) { showTab(); return; }
    const fresh = document.createElement('div');
    try {
      panel.render(fresh, api);
    } catch (e) {
      showTab();
      return;
    }
    const active = document.activeElement;
    const skip = !external && el.contains(active) && typedIn(active) && active === dirty ? active : null;
    const key = el.contains(active) && active.matches(CONTROLS) ? controlKey(active) : null;
    const same = el.childNodes.length === fresh.childNodes.length && [...fresh.childNodes].every((n, i) => sync(el.childNodes[i], n, skip));
    if (!same) { rebuildSoon(); return; }
    if (key && !el.contains(document.activeElement)) {
      const again = [...el.querySelectorAll(CONTROLS)].find((c) => controlKey(c) === key);
      if (again) again.focus({ preventScroll: true });
    }
  }

  // redrawn a task later: on touch the old field's change fires inside the next tap's mousedown, before focus moves
  let rebuilding = null;
  function rebuildSoon() {
    clearTimeout(rebuilding);
    rebuilding = setTimeout(() => {
      const a = document.activeElement;
      const typed = typedIn(a) && a === dirty && $('tab-' + tab).contains(a) ? { value: a.value, from: a.selectionStart, to: a.selectionEnd, key: controlKey(a) } : null;
      showTab();
      const b = document.activeElement;
      if (typed && typedIn(b) && controlKey(b) === typed.key && b.value !== typed.value) {
        b.value = typed.value;
        dirty = b;
        try { b.setSelectionRange(typed.from, typed.to); } catch (e) { /* a number field has no caret */ }
      }
    }, 0);
  }

  function controlKey(c) {
    const row = c.closest('label.row, .group, p, li, tr');
    const what = row && row.querySelector('.what');
    const inRow = row ? [...row.querySelectorAll(CONTROLS)].indexOf(c) : -1;
    return c.tagName + '|' + (what ? what.textContent : '') + '|' + inRow + '|' + (what ? '' : c.textContent || c.type);
  }

  // decoded only where the header says the app would take it: pack bytes are untrusted, and a huge decode kills the tab
  async function loadAssets(d) {
    // a theme.xml edit copies the object, not the bytes: nothing decoded again
    const same = (a, b) => a === b || (a && Object.keys(a).length === Object.keys(b).length && Object.keys(b).every((k) => a[k] === b[k]));
    if (same(assets.key, d.files)) { ++loading; return true; }
    const mine = ++loading;
    const images = {};
    const fonts = {};
    const cuts = {};
    const added = new Set();
    const safe = new Set(M.decodableImages(L, FORMAT, d));
    for (const [name, bytes] of Object.entries(d.files)) {
      try {
        if (IMAGE.test(name)) {
          if (!safe.has(name)) continue;
          // a cropper holds the bitmap, so the same bytes keep it
          const kept = assets.key && assets.key[name] === bytes && assets.cuts[name];
          const bmp = kept || await createImageBitmap(new Blob([bytes]));
          images[name] = { image: bmp, width: bmp.width, height: bmp.height };
          cuts[name] = bmp;
        } else if (FONT.test(name)) {
          const family = 'youmo-file-' + L.contentId(bytes);
          if (![...document.fonts].some((f) => f.family === family)) {
            const face = new FontFace(family, bytes);
            await face.load();
            document.fonts.add(face);
            faces.set(family, face);
            added.add(family);
          }
          fonts[name] = family;
        }
      } catch (e) {
        // undecodable: the engine draws without it, as the app would refuse it on import
      }
    }
    if (mine !== loading) {
      const ours = Object.values(assets.cuts);
      for (const b of Object.values(cuts)) if (!ours.includes(b)) b.close();
      // kept in faces, so a newer call that found one there puts it back when it lands
      const shown = new Set(Object.values(assets.fonts));
      for (const family of added) {
        const face = faces.get(family);
        if (face && !shown.has(family)) document.fonts.delete(face);
      }
      return false;
    }
    for (const b of Object.values(assets.cuts)) if (!Object.values(cuts).includes(b)) b.close();
    const families = new Set(Object.values(fonts));
    for (const [family, face] of faces) {
      if (!families.has(family)) { document.fonts.delete(face); faces.delete(family); } else if (!document.fonts.has(face)) document.fonts.add(face);
    }
    assets = { key: d.files, images, fonts, cuts };
    return true;
  }
  const faces = new Map();

  function fontFamilies(xml) {
    const out = {};
    for (const f of F.elements(xml, 'font')) {
      const a = Object.fromEntries(f.attrs);
      if (a.name && assets.fonts[a.src]) out[a.name] = assets.fonts[a.src];
    }
    return out;
  }

  function pictureOf() {
    const p = theme && theme.info(env.dark).picture;
    if (!p) return null;
    const cuts = p.cuts || {};
    // by the window's landscape (a tablet upright is), as the app picks the cut
    const file = cuts[M.shape(E, env).landscape ? 'landscape' : 'portrait'] || cuts.portrait || cuts.landscape;
    const bmp = file && assets.cuts[file];
    if (!bmp || !scene) return null;
    if (pictures.bmp !== bmp || pictures.w !== scene.width || pictures.h !== scene.height || pictures.density !== env.density) Object.assign(pictures, { bmp, w: scene.width, h: scene.height, density: env.density, made: new Map() });
    const made = pictures.made;
    return (blurDp, brightness) => {
      const b = blurDp == null ? p.blurDp : blurDp;
      const l = brightness == null ? p.brightness : brightness;
      const key = b + '/' + l;
      if (made.has(key)) return made.get(key);
      const c = document.createElement('canvas');
      c.width = pictures.w;
      c.height = pictures.h;
      const g = c.getContext('2d');
      const px = b * env.density;
      // where the browser has no filter, unblurred
      const m = 'filter' in g && px > 0 ? Math.ceil(px * 3) : 0;
      const s = Math.max(c.width / bmp.width, c.height / bmp.height);
      const dw = bmp.width * s;
      const dh = bmp.height * s;
      const dx = (c.width - dw) / 2;
      const dy = (c.height - dh) / 2;
      if (!m) {
        g.drawImage(bmp, dx, dy, dw, dh);
      } else {
        // the edges stretched over a margin before blurring, as the app's box blur clamps there
        const big = document.createElement('canvas');
        big.width = c.width + 2 * m;
        big.height = c.height + 2 * m;
        const k = big.getContext('2d');
        k.drawImage(bmp, m + dx, m + dy, dw, dh);
        const [W, H] = [c.width, c.height];
        k.drawImage(big, m, m, W, 1, m, 0, W, m); // top
        k.drawImage(big, m, m + H - 1, W, 1, m, m + H, W, m); // bottom
        k.drawImage(big, m, 0, 1, H + 2 * m, 0, 0, m, H + 2 * m); // left, corners with it
        k.drawImage(big, m + W - 1, 0, 1, H + 2 * m, m + W, 0, m, H + 2 * m); // right
        g.filter = 'blur(' + px + 'px)';
        g.drawImage(big, -m, -m);
        g.filter = 'none';
      }
      if (l < 100) {
        g.fillStyle = 'rgba(0, 0, 0, ' + (1 - l / 100) + ')';
        g.fillRect(0, 0, c.width, c.height);
      }
      made.set(key, c);
      return c;
    };
  }
  const pictures = { bmp: null, w: 0, h: 0, density: 0, made: new Map() };

  function pictureAt(picture, x, y, w, h) {
    if (!picture) return null;
    return (blurDp, brightness) => {
      const whole = picture(blurDp, brightness);
      const c = document.createElement('canvas');
      c.width = Math.max(1, Math.round(w));
      c.height = Math.max(1, Math.round(h));
      c.getContext('2d').drawImage(whole, -x, -y);
      return c;
    };
  }

  const PREEDIT_DP = 24;
  function barOf(m) {
    if (!env.bar || !theme) return null;
    const r = E.buildBar(theme, M.envJson(E, env), m);
    const l = E.looks(theme, M.envJson(E, env));
    if (!r.ok || !l.ok) return null;
    const pre = env.bar === 'typing' ? Math.round(PREEDIT_DP * env.density) : 0;
    return { scene: r.scene, looks: l.looks, pre, height: pre + r.scene.height };
  }

  const css = (argb) => {
    if (!argb) return null;
    const v = argb.replace('#', '');
    const a = parseInt(v.slice(0, 2), 16) / 255;
    return 'rgba(' + parseInt(v.slice(2, 4), 16) + ',' + parseInt(v.slice(4, 6), 16) + ',' + parseInt(v.slice(6, 8), 16) + ',' + a.toFixed(3) + ')';
  };

  function drawBar(ctx, bar, state, fonts) {
    const dp = env.density;
    const lk = bar.looks;
    ctx.save();
    if (bar.pre) {
      const size = Math.round(14 * dp);
      ctx.font = size + 'px ' + fonts.key;
      ctx.fillStyle = css(lk.preedit.color) || '#000';
      ctx.textBaseline = 'middle';
      const text = 'you mo';
      const x = 12 * dp;
      const y = bar.pre / 2;
      ctx.fillText(text, x, y);
      const w = ctx.measureText(text).width;
      if (lk.preedit.underline) {
        ctx.fillStyle = css(lk.preedit.underline.color) || css(lk.preedit.color);
        ctx.fillRect(x, y + size * 0.6, w, Math.max(1, lk.preedit.underline.widthDp * dp));
      }
      if (lk.preedit.cursor) {
        ctx.fillStyle = css(lk.preedit.cursor);
        ctx.fillRect(x + w + 1, y - size / 2, Math.max(1, dp), size);
      }
    }
    ctx.translate(0, bar.pre);
    if (env.bar === 'typing') E.paint(bar.scene, ctx, state);
    else {
      // the menu key's ⌄ turned where it is the app's
      const h = bar.scene.height;
      if (lk.bar) { ctx.fillStyle = css(lk.bar); ctx.fillRect(0, 0, bar.scene.width, h); }
      const size = Math.min(h - 8 * dp, 36 * dp);
      const step = bar.scene.width / 6;
      const icons = barIconsNow();
      const names = ['toolbar-expand', 'undo', 'clipboard', 'emoji', 'voice'];
      for (let i = 0; i < 5; i++) {
        const cx = step * (i + 0.5) + step / 2;
        const on = i === 2;
        const fill = on ? lk.toolbar.onFill : lk.toolbar.fill;
        const r = lk.toolbar.radiusDp == null ? size / 2 : Math.min(size / 2, lk.toolbar.radiusDp * dp);
        if (fill) {
          ctx.fillStyle = css(fill);
          ctx.beginPath();
          ctx.roundRect(cx - size / 2, (h - size) / 2, size, size, r);
          ctx.fill();
        }
        const name = names[i];
        const icon = 20 * dp;
        const turned = icons[name] === BAR_BASE[name] ? BAR_ICONS.turned[name] || 0 : 0;
        E.paintIcon(ctx, { icons }, name, cx - icon / 2, (h - icon) / 2, icon, on ? lk.toolbar.onIcon : lk.toolbar.icon, turned, theme);
      }
    }
    ctx.restore();
  }

  function drawPanel(ctx, lk, w, h) {
    const dp = env.density;
    const round = (x, y, ww, hh, r, fill) => { if (!fill) return; ctx.fillStyle = css(fill); ctx.beginPath(); ctx.roundRect(x, y, ww, hh, Math.max(0, r)); ctx.fill(); };
    const text = (t, x, y, color) => { ctx.fillStyle = css(color) || '#000'; ctx.font = Math.round(13 * dp) + 'px "Youmo Han", sans-serif'; ctx.textBaseline = 'middle'; ctx.fillText(t, x, y); };
    ctx.save();
    const p = 8 * dp;
    if (env.panel === 'clipboard') {
      const c = lk.clipboard;
      for (let i = 0; i < 4; i++) {
        const x = p + (i % 2) * (w / 2);
        const y = p + Math.floor(i / 2) * 64 * dp;
        round(x, y, w / 2 - 2 * p, 56 * dp, c.radiusDp * dp, c.fill);
        text(['复制的一段文字', '幽默输入法', 'https://…', '12345'][i], x + p, y + 28 * dp, c.color);
      }
    } else if (env.panel === 'picker') {
      const c = lk.picker;
      round(0, 0, w, h, 0, c.fill);
      const bar = 40 * dp;
      round(0, h - bar, w, bar, 0, c.categories);
      const tabs = ['常用', '中文', '英文', '数学', '箭头'];
      tabs.forEach((t, i) => {
        if (i === 0) round(i * w / tabs.length, h - bar, w / tabs.length, bar, 0, c.selected);
        text(t, i * w / tabs.length + p, h - bar / 2, i === 0 ? c.selectedColor : c.color);
      });
      '，。、？！：；“”（）《》'.split('').forEach((t, i) => text(t, p + (i % 6) * (w / 6), p + 20 * dp + Math.floor(i / 6) * 48 * dp, c.color));
    } else if (env.panel === 'editing') {
      const c = lk.editing;
      round(0, 0, w, h, 0, c.fill);
      ['全选', '复制', '粘贴', '剪切', '←', '→'].forEach((t, i) => {
        const x = p + (i % 3) * (w - p) / 3;
        const y = p + Math.floor(i / 3) * (h - p) / 2;
        round(x, y, (w - p) / 3 - p, (h - p) / 2 - p, c.radiusDp * dp, lk.clipboard.fill);
        text(t, x + p, y + ((h - p) / 2 - p) / 2, lk.clipboard.color);
      });
    } else if (env.panel === 'voice') {
      const c = lk.voice;
      round(0, 0, w, h, 0, c.fill);
      const r = 36 * dp;
      round(w / 2 - r, h / 2 - r, 2 * r, 2 * r, r, c.mic);
      round(w / 2 - r / 4, h / 2 - r / 2, r / 2, r, r / 4, c.micColor);
    }
    ctx.restore();
  }

  function popupOf(m) {
    if (!env.show || !scene || !theme) return null;
    const keys = scene.json().keys;
    const k = (picked && picked.board === boardName() && keys.find((x) => x.id === picked.key)) || keys[Math.floor(keys.length / 3)];
    if (!k) return null;
    const [l, t, r] = k.bounds;
    const label = k.label || 'a';
    let req;
    if (env.show === 'preview') req = { kind: 'preview', text: label, keyWidth: r - l };
    else if (env.show === 'keys') req = { kind: 'keys', labels: ['à', 'á', 'â', 'ä', 'ā'], rows: [[0, 1, 2, 3, 4]], columns: 5, selected: 1 };
    else req = { kind: 'menu', cells: [{ label: '剪贴板' }, { label: '表情' }, { label: '编辑' }, { label: '设置' }], order: [0, 1, 2, 3], selected: 0 };
    const got = E.buildPopup(theme, M.envJson(E, env), req, m);
    if (!got.ok) return null;
    const p = got.scene;
    const cx = (l + r) / 2;
    const x = env.show === 'menu' ? (scene.width - p.width) / 2 : Math.max(0, Math.min(scene.width - p.width, cx - p.width / 2));
    const y = env.show === 'menu' ? (scene.height - p.height) / 2 : t - p.height;
    return { scene: p, x, y };
  }

  let measured = { key: null, canvas: null, fonts: null, m: null };
  function measurerFor(canvas, xml) {
    const fonts = Object.assign({ key: '"Youmo Latin", "Youmo Han", sans-serif' }, fontFamilies(xml));
    const key = JSON.stringify(fonts);
    if (measured.key !== key || measured.canvas !== canvas) measured = { key, canvas, fonts, m: E.canvasMeasurer(canvas.getContext('2d'), fonts) };
    return measured.m;
  }
  let checked = null;

  // a pad named, as the app shows it from 123: avoids the engine's no-pad warning
  function ownEnv(board) {
    const e = M.envJson(E, env);
    if (typeof board === 'object' && board.family === 'number') e.input = Object.assign({ field: 'number', ime: 'pinyin' }, e.input, { pad: 'full' });
    return e;
  }
  function preview() {
    const d = history.current;
    const canvas = $('preview');
    const m = measurerFor(canvas, d.xml);
    const fonts = measured.fonts;
    // a tap changes only what is pressed: the theme read and the scene built are reused
    const envKey = JSON.stringify(env);
    const board = boardDrawn();
    const r = checked && checked.d === d && checked.env === envKey && checked.board === board && checked.m === m ? checked.r : M.check(E, d, board, ownEnv(board), m.ok ? m.measurer : null);
    checked = { d, env: envKey, board, m, r };
    scene = r.scene;
    theme = r.theme;
    showProblems(r.problems);
    if (!scene) return;
    let bar = null;
    let popup = null;
    try {
      bar = barOf(m.ok ? m.measurer : null);
      popup = popupOf(m.ok ? m.measurer : null);
    } catch (e) {
    }
    barH = bar ? bar.height : 0;
    canvas.width = scene.width;
    canvas.height = scene.height + barH;
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    const state = { pressed, icons: iconsNow(), images: assets.images, fonts };
    const picture = pictureOf();
    if (picture) state.picture = picture;
    if (bar) drawBar(ctx, bar, Object.assign({}, state, { pressed: null, picture: pictureAt(picture, 0, 0, scene.width, bar.height) }), fonts);
    ctx.save();
    ctx.translate(0, barH);
    const looks = env.panel && theme ? E.looks(theme, M.envJson(E, env)) : null;
    if (looks && looks.ok) {
      E.paint(scene, ctx, Object.assign({}, state, { pressed: null }));
      drawPanel(ctx, looks.looks, scene.width, scene.height);
    } else E.paint(scene, ctx, state);
    if (popup) {
      ctx.translate(popup.x, popup.y);
      E.paint(popup.scene, ctx, Object.assign({}, state, { picture: pictureAt(picture, popup.x, popup.y, popup.scene.width, popup.scene.height) }));
    }
    ctx.restore();
  }


  function playFrame(ctx, c) {
    ctx.drawImage(c.keys, 0, 0);
    const [l, t, r, b] = c.bounds;
    ctx.save();
    ctx.globalAlpha = playing.alpha;
    const cx = (l + r) / 2;
    const cy = (t + b) / 2 + playing.sink * env.density;
    ctx.translate(cx, cy);
    ctx.scale(playing.scale, playing.scale);
    ctx.drawImage(c.pressed, l, t, r - l, b - t, -(r - l) / 2, -(b - t) / 2, r - l, b - t);
    ctx.restore();
    if (playing.appear != null && c.bubble) {
      const p = c.bubble;
      const a = playing.appear;
      ctx.save();
      const kind = playing.appearKind;
      ctx.globalAlpha = kind === 'fade' ? a : 1;
      const x = Math.max(0, Math.min(scene.width - p.width, cx - p.width / 2));
      const y = t - p.height + (kind === 'rise' ? (1 - a) * 12 * env.density : 0);
      ctx.translate(x + p.width / 2, y + p.height);
      if (kind === 'scale') ctx.scale(0.5 + a / 2, 0.5 + a / 2);
      ctx.translate(-p.width / 2, -p.height);
      E.paint(p, ctx, c.state);
      ctx.restore();
    }
  }

  const EASE = {
    linear: (x) => x, ease: (x) => x * x * (3 - 2 * x), 'ease-in': (x) => x * x, 'ease-out': (x) => 1 - (1 - x) * (1 - x), 'ease-in-out': (x) => (x < 0.5 ? 2 * x * x : 1 - 2 * (1 - x) * (1 - x)),
  };

  let plays = 0;
  function play(m) {
    if (!scene) return;
    const keys = scene.json().keys;
    const k = (picked && keys.find((x) => x.id === picked.key)) || keys.find((x) => x.kind === 'letter') || keys[0];
    if (!k) return;
    const HOLD = 220;
    const back = m.transition ? m.transition.ms : 0;
    const ease = EASE[m.transition ? m.transition.easing : 'linear'] || EASE.linear;
    const pressMs = Math.min(80, back || 80);
    const appearMs = m.appear ? m.appear.ms : 0;
    // prepared once per play, not per frame (else frames drop on a phone)
    measurerFor($('preview'), history.current.xml);
    const state = { pressed: null, icons: iconsNow(), images: assets.images, fonts: measured.fonts };
    const picture = pictureOf();
    if (picture) state.picture = picture;
    const layer = (pressedKey) => {
      const o = document.createElement('canvas');
      o.width = scene.width;
      o.height = scene.height;
      E.paint(scene, o.getContext('2d'), Object.assign({}, state, { pressed: pressedKey }));
      return o;
    };
    const got = E.buildPopup(theme, M.envJson(E, env), { kind: 'preview', text: k.label || 'a', keyWidth: k.bounds[2] - k.bounds[0] }, null);
    const cache = { keys: layer(null), pressed: layer(k.id), bounds: k.bounds, bubble: got.ok ? got.scene : null, state };
    const canvas = $('preview');
    const ctx = canvas.getContext('2d');
    const t0 = performance.now();
    // the earlier loop stops (two would draw over each other)
    const mine = ++plays;
    const step = (now) => {
      if (mine !== plays) return;
      const t = now - t0;
      const pressIn = Math.min(1, t / pressMs);
      const release = t < HOLD ? 0 : back ? Math.min(1, (t - HOLD) / back) : 1;
      playing = {
        key: k.id,
        scale: 1 + (m.scale / 100 - 1) * pressIn * (1 - ease(release)),
        sink: m.sink * pressIn * (1 - ease(release)),
        alpha: 1 - ease(release),
        appear: t < HOLD + back ? (appearMs ? Math.min(1, t / appearMs) : 1) : null,
        appearKind: m.appear ? m.appear.kind : 'fade',
      };
      if (t >= HOLD + back) {
        playing = null;
        preview();
        return;
      }
      ctx.clearRect(0, barH, canvas.width, canvas.height - barH);
      ctx.save();
      ctx.translate(0, barH);
      playFrame(ctx, cache);
      ctx.restore();
      requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }

  function showProblems(list) {
    problems = list;
    const ul = $('problems');
    ul.textContent = '';
    if (notice) ul.append(Object.assign(document.createElement('li'), { className: 'info', textContent: '提示：' + notice }));
    for (const p of list) {
      const li = document.createElement('li');
      li.className = p.level;
      li.textContent = (p.level === 'error' ? '错误' : '警告') + (p.line ? '（第 ' + p.line + ' 行）' : '') + '：' + p.message;
      if (p.line) li.addEventListener('click', () => goToLine(p.line));
      ul.append(li);
    }
    const errors = list.filter((p) => p.level === 'error').length;
    $('count').textContent = list.length ? errors + ' 个错误，' + (list.length - errors) + ' 个警告' : '没有';
  }

  function goToLine(line) {
    tab = 'source';
    showTab();
    const ta = $('source');
    const lines = ta.value.split('\n');
    const start = lines.slice(0, line - 1).reduce((n, l) => n + l.length + 1, 0);
    ta.focus();
    ta.setSelectionRange(start, start + (lines[line - 1] || '').length);
    ta.scrollTop = Math.max(0, (line - 3) * (parseFloat(getComputedStyle(ta).lineHeight) || 17));
  }


  function pick(input) {
    return new Promise((ok) => {
      input.value = '';
      input.onchange = () => ok(input.files[0] || null);
      input.oncancel = () => ok(null);
      input.click();
    });
  }

  /** Not kept in the draft: a restored draft draws 26 键. */
  async function openLayout() {
    const f = await pick($('layout-input'));
    if (!f) return;
    try {
      own = M.openLayout(E, new Uint8Array(await f.arrayBuffer()));
    } catch (e) {
      toast('打不开这个布局：' + (e.message || e));
      return;
    }
    if (picked && picked.board === own.id) picked = null;
    const sel = $('env-board');
    for (const o of [...sel.options]) if (o.value === OWN) o.remove();
    sel.add(new Option(own.name + '（' + own.id + '.layout）', OWN), sel.querySelector('option[value="' + OPEN_LAYOUT + '"]'));
    env.board = OWN;
    fillEnvForm();
    keep();
    if (history) { preview(); showTab(); }
    if (own.oldFaces) toast(own.oldFaces + ' 个分组键显示的字是旧版布局制作页写的 {group:…}（超过两个字母，App 读不了），这里按大写字母画；在布局制作页打开再保存一次，App 才能导入');
  }

  async function openFile() {
    const f = await pick($('open-input'));
    if (f) await openBytes(f);
  }

  // the browser's image codec for a skin's pictures, null where it has none; a 2D canvas keeps premultiplied pixels, so a
  // nearly transparent one's colour is rounded where the app's decoder keeps it exact
  const skinIo = typeof DecompressionStream === 'function' && typeof OffscreenCanvas === 'function' && globalThis.crypto && crypto.subtle ? {
    async decode(bytes) {
      const bmp = await createImageBitmap(new Blob([bytes]), { premultiplyAlpha: 'none', colorSpaceConversion: 'none' });
      try {
        const g = new OffscreenCanvas(bmp.width, bmp.height).getContext('2d', { willReadFrequently: true });
        g.drawImage(bmp, 0, 0);
        const d = g.getImageData(0, 0, bmp.width, bmp.height);
        return { width: d.width, height: d.height, data: d.data };
      } finally {
        bmp.close();
      }
    },
    async png(img) {
      const c = new OffscreenCanvas(img.width, img.height);
      c.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(img.rgba), img.width, img.height), 0, 0);
      return new Uint8Array(await (await c.convertToBlob({ type: 'image/png' })).arrayBuffer());
    },
  } : null;

  let skin = null; // the skin opened last: its layouts added or taken out by the banner's check box
  let skinClosed = false;
  // the documents of that skin's conversion and the edits of them, undone and redone alike: the banner is theirs alone
  let fromSkin = new WeakSet();
  let busy = false;

  async function openBytes(f) {
    if (busy) return;
    // closing it would answer for the user: start() goes on to a new theme, and the kept draft is replaced
    if ($('restore-panel').open) {
      toast('先回答要不要恢复上次的草稿，再打开文件');
      return;
    }
    busy = true;
    // the conversion holds the page: nothing else is done meanwhile, and past 2 s it says why
    const was = [$('top').inert, $('main').inert];
    $('top').inert = $('main').inert = true;
    // a modal (新建, 导出, 帮助…) would sit over the page's 正在转换 and stay clickable: each closed, 新建 opened again if nothing opens
    const fromNew = $('new-panel').open;
    for (const d of document.querySelectorAll('dialog[open]')) if (d.id !== 'busy') d.close();
    const slow = setTimeout(() => $('busy').showModal(), 2000);
    try {
      const { doc, notes, skin: converted } = await M.open(E, L, FORMAT, new Uint8Array(await f.arrayBuffer()), f.name, skinIo);
      skin = converted || null;
      fromSkin = new WeakSet();
      if (skin) fromSkin.add(doc);
      showSkin(notes.find((n) => n.kind === 'skin'));
      set(doc, { other: true });
      // the same file again is no new step: the banner is the one shown
      if (skin) fromSkin.add(history.current);
      skinShown(history.current);
      // one after another, only the last toast would be seen
      const said = [];
      for (const n of notes) {
        if (n.kind === 'converted') said.push('这是 ' + n.from + ' 主题，已转成 4.0');
        if (n.kind === 'layouts') said.push('带自定义布局：' + n.ids.join('、') + '，保存时原样保留');
        if (n.kind === 'dropped') said.push('包里有 App 不认的文件，已去掉：' + n.names.slice(0, 3).join('、') + (n.names.length > 3 ? ' 等 ' + n.names.length + ' 个' : ''));
        if (n.kind === 'skin') said.push('已从皮肤转成主题');
        if (n.kind === 'min-app') notice = M.minAppNotice(n.version);
      }
      if (said.length) toast(said.join('；'));
    } catch (e) {
      if (fromNew) $('new-panel').showModal();
      toast('打不开这个文件：' + M.openError(e));
    } finally {
      clearTimeout(slow);
      $('busy').close();
      busy = false;
      [$('top').inert, $('main').inert] = history ? [false, false] : was;
    }
  }

  // the banner of what a skin's conversion left out, and its layouts' check box (not ticked: the app's own layouts)
  function showSkin(note) {
    skinClosed = false;
    if (!note) return;
    $('skin-lines').replaceChildren(...note.lines.map((t) => Object.assign(document.createElement('li'), { textContent: t })));
    $('skin-head').hidden = !note.lines.length;
    $('skin-layouts-row').hidden = !note.layouts.length;
    $('skin-layouts-text').textContent = note.layouts.length ? M.skinLayoutsText(note.layouts) : '';
  }

  // the banner shown over the skin's own documents only, its box ticked where they carry the skin's layouts
  function skinShown(d) {
    $('skin-note').hidden = !skin || skinClosed || !fromSkin.has(d);
    const ids = skin ? Object.keys(skin.layouts) : [];
    $('skin-layouts').checked = ids.length > 0 && ids.every((n) => n in d.layouts);
  }


  // decoded upright (EXIF); JPEG, or PNG where any pixel is see-through. A bitmap [bytes] stays the caller's to close
  async function encodePicture(bytes, sizeOf, quality, rect = null, jpg = false) {
    const given = bytes instanceof ImageBitmap;
    const bmp = given ? bytes : await createImageBitmap(new Blob([bytes]));
    const opaque = given ? jpg : L.imageType(bytes) === 'jpg';
    try {
      const [l, t, r, b] = rect || [0, 0, bmp.width, bmp.height];
      const [w, h] = sizeOf(r - l, b - t);
      const c = document.createElement('canvas');
      c.width = w;
      c.height = h;
      const g = c.getContext('2d');
      g.drawImage(bmp, l, t, r - l, b - t, 0, 0, w, h);
      let alpha = false;
      if (!opaque) {
        const px = g.getImageData(0, 0, w, h).data;
        for (let i = 3; i < px.length && !alpha; i += 4) alpha = px[i] < 255;
      }
      const type = alpha ? 'png' : 'jpg';
      const blob = await new Promise((ok) => c.toBlob(ok, alpha ? 'image/png' : 'image/jpeg', quality));
      return { bytes: new Uint8Array(await blob.arrayBuffer()), type };
    } finally {
      if (!given) bmp.close();
    }
  }

  async function choosePicture() {
    const f = await pick($('image-input'));
    if (!f) return null;
    try {
      const picked = new Uint8Array(await f.arrayBuffer());
      // decoded once, after pictureFile's header checks (never one too big)
      let bmp = null;
      const jpg = L.imageType(picked) === 'jpg';
      try {
        // an animated WebP is kept as is (it plays centred, filling the keyboard); its first frame makes the cuts
        const animated = M.keepsAnimated(FORMAT.limits, picked);
        const made = animated ? { bytes: picked, type: 'webp' }
          : await M.pictureFile(L, FORMAT, picked, async (bytes, sizeOf, quality) => encodePicture(bmp ??= await createImageBitmap(new Blob([bytes])), sizeOf, quality, null, jpg));
        bmp ??= await createImageBitmap(new Blob([picked]));
        const size = L.imageSize(made.bytes);
        if (!size) throw new Error('no size');
        const scale = bmp.width / size[0];
        const bg = PANELS.find((p) => p.id === 'background').ops;
        const side = Math.min(M.PICTURE_SAVE.longSide, FORMAT.limits.pictureSide);
        const cuts = {};
        for (const o of ['portrait', 'landscape']) {
          const rect = F.cropRect(size[0], size[1], bg.ratio(E, Object.assign({}, env, { landscape: o === 'landscape' })));
          const c = await encodePicture(bmp, (w, h) => L.resizeInside(w, h, side, side), M.PICTURE_SAVE.quality, rect.map((v) => v * scale), jpg);
          cuts[o] = { bytes: c.bytes, type: c.type, rect };
        }
        // a cut of the saved JPEG would be second generation
        originals.clear();
        originals.set(L.contentId(made.bytes), { bytes: picked, scale });
        return { type: made.type, src: made.bytes, cuts, animated };
      } finally {
        if (bmp) bmp.close();
      }
    } catch (e) {
      toast(e && e.code === 'limit' ? '图片太大，打不开' : '这张图片用不了（只认 PNG、JPEG、WebP）');
      return null;
    }
  }

  async function cutOf(bytes, rect) {
    const side = Math.min(M.PICTURE_SAVE.longSide, FORMAT.limits.pictureSide);
    const first = originals.get(L.contentId(bytes));
    const from = first ? first.bytes : bytes;
    const at = first ? rect.map((v) => v * first.scale) : rect;
    const made = await encodePicture(from, (w, h) => L.resizeInside(w, h, side, side), M.PICTURE_SAVE.quality, at);
    return { bytes: made.bytes, type: made.type, rect };
  }

  async function fromImage() {
    const chosen = document.querySelector('input[name="image-base"]:checked');
    const made = await choosePicture();
    if (!made) return;
    const bg = PANELS.find((p) => p.id === 'background').ops;
    const colors = PANELS.find((p) => p.id === 'colors').ops;
    const dark = !!(document.querySelector('input[name="image-tone"]:checked') || {}).value?.startsWith('dark');
    env.dark = dark;
    fillEnvForm();
    let d = bg.setPicture(M.fromBuiltin(E, chosen ? chosen.value : 'material'), made);
    const cut = made.cuts.portrait || { bytes: made.src };
    const bmp = await createImageBitmap(new Blob([cut.bytes]));
    d = colors.fromPictureAll(d, samplesOf(bmp), dark);
    bmp.close();
    set(asDoc(d), { other: true });
    $('new-panel').close();
    tab = 'background';
    showTab();
    toast('图片已做键盘背景：在「背景」里调裁切，「配色」里可以从图片取色');
  }


  const asDoc = (x) => M.doc(x.xml, x.files, x.layouts);

  const api = {
    engine: E,
    doc: () => history.current,
    env: () => env,
    envJson: (e = env) => M.envJson(E, e),
    theme: () => theme,
    picked: () => picked,
    // says so instead of failing silently
    apply: (fn) => {
      if (!history) return;
      let next;
      try {
        next = fn(history.current);
      } catch (e) {
        toast(e.message || String(e));
        return;
      }
      set(asDoc(next));
    },
    rerender: () => showTab(),
    problems: () => problems,
    play,
    // PNG where it fits, else WebP at falling quality (a picture background's PNG is past the app's 512 KB)
    async previewImage(width, bytes = Infinity) {
      const c = $('preview');
      const encode = async (w, type, q) => {
        const k = Math.min(1, w / c.width);
        const o = document.createElement('canvas');
        o.width = Math.max(1, Math.round(c.width * k));
        o.height = Math.max(1, Math.round(c.height * k));
        o.getContext('2d').drawImage(c, 0, 0, o.width, o.height);
        const blob = await new Promise((ok) => o.toBlob(ok, type, q));
        return blob && blob.type === type ? new Uint8Array(await blob.arrayBuffer()) : null;
      };
      let out = await encode(width, 'image/png');
      for (let w = width; out && out.length > bytes && w >= 120; w = Math.round(w * 0.8)) {
        for (const q of [0.9, 0.8, 0.7, 0.6]) {
          out = (await encode(w, 'image/webp', q)) || (await encode(w, 'image/png')) || out;
          if (out.length <= bytes) break;
        }
      }
      return out;
    },
    toast,
    lib: L,
    limits: () => FORMAT.limits,
    goToLine,
    bitmap: (name) => assets.cuts[name] || null,
    iconGroups: () => BAR_ICONS.groups,
    barOwnImage: (name) => name in BAR_ICONS.toolbarFallback,
    choosePicture,
    cutPicture: (src, rect) => cutOf(history.current.files[src], rect),
    async pickFile(accept) {
      const input = $('any-input');
      input.accept = accept;
      const f = await pick(input);
      return f ? { name: f.name, bytes: await f.arrayBuffer() } : null;
    },
    async pictureSamples() {
      const p = theme && theme.info(env.dark).picture;
      const bmp = p && assets.cuts[(p.cuts || {}).portrait || p.src];
      return bmp ? samplesOf(bmp) : null;
    },
  };

  function samplesOf(bmp) {
    const c = document.createElement('canvas');
    c.width = 24;
    c.height = 16;
    const g = c.getContext('2d');
    g.drawImage(bmp, 0, 0, c.width, c.height);
    const px = g.getImageData(0, 0, c.width, c.height).data;
    const out = [];
    for (let i = 0; i < px.length; i += 4) out.push([px[i], px[i + 1], px[i + 2]]);
    return out;
  }

  function showTab() {
    for (const b of $('tabs').children) b.setAttribute('aria-selected', String(b.dataset.tab === tab));
    for (const el of document.querySelectorAll('#edit .tab')) el.hidden = el.id !== 'tab-' + tab;
    const panel = PANELS.find((p) => p.id === tab);
    if (!panel || !history) return;
    const el = $('tab-' + tab);
    const scroll = $('edit').scrollTop;
    // found by row and place (controlKey), not index: rows added or removed shift that
    const key = el.contains(document.activeElement) && document.activeElement.matches(CONTROLS) ? controlKey(document.activeElement) : null;
    el.textContent = '';
    try {
      panel.render(el, api);
    } catch (e) {
      el.textContent = '这一页出错了：' + (e.message || e);
    }
    const again = key && [...el.querySelectorAll(CONTROLS)].find((c) => controlKey(c) === key);
    if (again) again.focus({ preventScroll: true });
    $('edit').scrollTop = scroll;
  }

  // an error stops it; warnings are the user's to ignore
  async function save() {
    if (!history) return;
    const { errors, warnings, minApp } = M.exportCheck(E, L, FORMAT, history.current, E.layouts(), M.envJson(E, env));
    if (minApp) {
      set(minApp.doc);
      notice = M.minAppNotice(minApp.version);
      showProblems(problems);
    }
    const d = history.current;
    if (errors.length) {
      showProblems([...errors, ...warnings]);
      $('problems-area').scrollIntoView({ block: 'nearest' });
      toast('有 ' + errors.length + ' 个错误，App 导入不了，先改好（见「问题」）');
      return;
    }
    if (warnings.length) {
      $('export-text').textContent = warnings.length + ' 个警告。App 能导入，但这些地方可能和你想的不一样：';
      $('export-list').replaceChildren(...warnings.slice(0, 50).map((p) => Object.assign(document.createElement('li'), { textContent: (p.line ? '第 ' + p.line + ' 行：' : '') + p.message })));
      $('export-panel').returnValue = '';
      $('export-panel').showModal();
      const answer = await new Promise((ok) => $('export-panel').addEventListener('close', () => ok($('export-panel').returnValue), { once: true }));
      if (answer !== 'save') return;
    }
    const { name, bytes } = M.save(L, d);
    const a = document.createElement('a');
    // a browser adds no .zip to a name it takes for a zip
    a.href = URL.createObjectURL(new Blob([bytes], { type: 'application/octet-stream' }));
    a.download = name;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 10000);
    toast(/Android|iPhone/.test(navigator.userAgent) ? '已保存 ' + name + '。打开幽默输入法 → 主题 → 新增主题 → 导入主题文件' : '已保存 ' + name);
  }


  function fillEnvForm() {
    $('env-device').value = env.device;
    $('env-w').value = env.w;
    $('env-h').value = env.h;
    $('env-density').value = env.density;
    $('env-landscape').checked = env.landscape;
    $('env-board').value = env.board;
    $('env-form-kind').value = env.form;
    $('env-shift').value = env.shift;
    $('env-field').value = env.field;
    $('env-ime').value = env.ime;
    $('env-show').value = env.show;
    $('env-bar').value = env.bar;
    $('env-sheet').value = env.panel;
    $('dark').setAttribute('aria-pressed', String(env.dark));
    $('dark').textContent = env.dark ? '深色' : '浅色';
  }

  function readEnvForm(e) {
    if (e && e.target === $('env-device')) {
      const dev = M.DEVICES.find((x) => x.id === $('env-device').value);
      if (dev) Object.assign(env, { device: dev.id, w: dev.w, h: dev.h, density: dev.density });
      else env.device = 'custom';
    } else {
      const num = (id, v) => (Number.isFinite(+$(id).value) && +$(id).value > 0 ? +$(id).value : v);
      Object.assign(env, { w: num('env-w', env.w), h: num('env-h', env.h), density: num('env-density', env.density) });
      if (e && /env-(w|h|density)/.test(e.target.id)) env.device = 'custom';
    }
    if ($('env-board').value === OPEN_LAYOUT) {
      $('env-board').value = env.board;
      openLayout();
    }
    Object.assign(env, {
      landscape: $('env-landscape').checked, board: $('env-board').value, form: $('env-form-kind').value, shift: $('env-shift').value,
      field: $('env-field').value, ime: $('env-ime').value, show: $('env-show').value, bar: $('env-bar').value, panel: $('env-sheet').value,
    });
    fillEnvForm();
    keep();
    if (history) { preview(); showTab(); }
  }


  function wire() {
    // now, not debounced: a closed tab is gone by then
    window.addEventListener('pagehide', () => { if (keeping) keepNow(); });
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden' && keeping) keepNow(); });
    for (const d of M.DEVICES) $('env-device').add(new Option(d.label + '（' + d.w + '×' + d.h + '）', d.id));
    $('env-device').add(new Option('自定义', 'custom'));
    const boards = new Set(E.layouts());
    for (const b of M.BOARDS) if (boards.has(b)) $('env-board').add(new Option(BOARD_LABEL[b] || b, b));
    $('env-board').add(new Option('打开 .layout…', OPEN_LAYOUT));
    for (const f of M.FORMS) $('env-form-kind').add(new Option(FORM_LABEL[f], f));
    $('env-form').addEventListener('change', readEnvForm);
    $('env').addEventListener('click', () => $('env-panel').showModal());
    $('dark').addEventListener('click', () => { env.dark = !env.dark; readEnvForm(); });

    // a radio group, so a touch picks one (a button's focus is no choice on a phone)
    for (const id of E.themes()) {
      const label = document.createElement('label');
      const r = document.createElement('input');
      r.type = 'radio';
      r.name = 'image-base';
      r.value = id;
      r.checked = id === 'material';
      label.append(r, ' ' + (THEME_LABEL[id] || id));
      $('image-base').append(label);
    }
    for (const ex of EXAMPLES) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = ex.label;
      b.title = ex.note;
      b.addEventListener('click', () => {
        set(M.exampleDoc(ex), { other: true });
        $('new-panel').close();
        if (Object.keys(ex.layouts).length) toast('带自定义布局：' + Object.keys(ex.layouts).map((n) => n.replace(/\.xml$/, '')).join('、') + '，保存时原样保留');
      });
      $('examples').append(b);
    }
    $('help-button').addEventListener('click', () => $('help-panel').showModal());
    const cards = $('builtins');
    for (const id of E.themes()) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = THEME_LABEL[id] || id;
      b.addEventListener('click', () => { set(M.fromBuiltin(E, id), { other: true }); $('new-panel').close(); });
      cards.append(b);
    }
    for (const p of PANELS) {
      const b = Object.assign(document.createElement('button'), { textContent: p.label });
      b.setAttribute('role', 'tab');
      b.dataset.tab = p.id;
      $('tabs').lastElementChild.before(b);
      const el = document.createElement('div');
      el.id = 'tab-' + p.id;
      el.className = 'tab';
      $('tab-source').before(el);
    }
    $('tabs').addEventListener('click', (e) => { if (e.target.dataset.tab) { tab = e.target.dataset.tab; showTab(); } });
    showTab();
    $('from-image').addEventListener('click', fromImage);
    $('from-file').addEventListener('click', openFile);
    $('skin-layouts').addEventListener('change', () => { if (history && skin && fromSkin.has(history.current)) set(M.skinLayouts(history.current, skin, $('skin-layouts').checked)); });
    $('skin-close').addEventListener('click', () => { skinClosed = true; $('skin-note').hidden = true; });
    $('busy').addEventListener('cancel', (e) => e.preventDefault());
    // a file dropped anywhere is opened as 打开文件… opens one
    document.addEventListener('dragover', (e) => { if (e.dataTransfer && [...e.dataTransfer.types].includes('Files')) e.preventDefault(); });
    document.addEventListener('drop', (e) => {
      const f = e.dataTransfer && e.dataTransfer.files[0];
      if (!f) return;
      e.preventDefault();
      openBytes(f);
    });

    $('file').addEventListener('click', () => { $('file-menu').hidden = !$('file-menu').hidden; });
    $('file-menu').addEventListener('click', (e) => {
      const act = e.target.dataset.act;
      $('file-menu').hidden = true;
      if (act === 'new') $('new-panel').showModal();
      else if (act === 'open') openFile();
      else if (act === 'save') save();
    });
    $('undo').addEventListener('click', () => { if (history && history.canUndo) { history.undo(); edited = true; changed(true); } });
    $('redo').addEventListener('click', () => { if (history && history.canRedo) { history.redo(); edited = true; changed(true); } });
    $('edit').addEventListener('input', (e) => { if (typedIn(e.target)) dirty = e.target; });
    $('edit').addEventListener('change', (e) => { if (e.target === dirty) dirty = null; }, true);
    // typed back to what it was: no change event, so the field is let go on leaving it
    let focusValue = null;
    $('edit').addEventListener('focusin', (e) => { focusValue = e.target.value; });
    $('edit').addEventListener('focusout', (e) => { if (e.target === dirty && e.target.value === focusValue) dirty = null; });
    document.addEventListener('keydown', (e) => {
      if (!(e.ctrlKey || e.metaKey) || e.target === $('source') || e.target === dirty) return;
      // the save would write what it checked, not what an undo made
      if (document.querySelector('dialog[open]')) return;
      // Shift and Caps Lock make it 'Z'
      const k = e.key.toLowerCase();
      if (k === 'z' && !e.shiftKey) { e.preventDefault(); $('undo').click(); }
      else if (k === 'y' || (k === 'z' && e.shiftKey)) { e.preventDefault(); $('redo').click(); }
    });
    let typing = null;
    $('source').addEventListener('input', () => {
      clearTimeout(typing);
      typing = setTimeout(() => { if (history) set(M.doc($('source').value, history.current.files, history.current.layouts)); }, 400);
    });

    const canvas = $('preview');
    const keyAt = (e) => {
      if (!scene) return null;
      const r = canvas.getBoundingClientRect();
      const k = scene.keyAt((e.clientX - r.left) * canvas.width / r.width, (e.clientY - r.top) * canvas.height / r.height - barH);
      return k >= 0 ? scene.json().keys[k].id : null;
    };
    canvas.addEventListener('pointerdown', (e) => {
      if (!history) return;
      pressed = keyAt(e);
      const before = picked;
      if (pressed) picked = { board: boardName(), key: pressed, layout: boardDrawn() };
      $('picked').textContent = picked ? '选中的键：' + picked.key + '（' + (BOARD_LABEL[picked.board] || (own && own.id === picked.board ? own.name : picked.board)) + '）' : '';
      preview();
      if (picked !== before && (!before || before.key !== picked.key || before.board !== picked.board)) refreshTab();
    });
    const up = () => { if (pressed && history) { pressed = null; preview(); } };
    canvas.addEventListener('pointerup', up);
    canvas.addEventListener('pointerleave', up);
  }

  async function start() {
    if (!E) {
      document.body.textContent = '这个页面没有带绘制引擎：./gradlew :lib:keyboard-scene:youmoEngine，再 node web/theme-maker/tools/assemble.mjs --engine';
      return;
    }
    wire();
    try {
      await Promise.all(['400', '700'].flatMap((w) => [document.fonts.load(w + ' 20px "Youmo Latin"', 'a'), document.fonts.load(w + ' 20px "Youmo Han"', '中')]));
    } catch (e) {
      // fallback font: measured the same, a little off from the phone's
    }
    const kept = store ? await M.draft(store) : null;
    if (kept) {
      $('restore-text').textContent = '上次的草稿「' + (M.themeName(kept.doc.xml) || '无名') + '」（' + new Date(kept.at).toLocaleString() + '）还在，恢复吗？';
      // Esc or back keeps the draft: only 恢复 or an explicit 丢弃 answers
      $('restore-panel').addEventListener('cancel', (e) => e.preventDefault());
      $('restore-panel').returnValue = '';
      $('restore-panel').showModal();
      const answer = await new Promise((ok) => $('restore-panel').addEventListener('close', () => ok($('restore-panel').returnValue), { once: true }));
      if (answer === 'restore') {
        try {
          // a board since gone would draw nothing
          const offered = (id) => [...$(id).options].map((o) => o.value).filter((v) => v !== OWN && v !== OPEN_LAYOUT);
          if (kept.env) {
            env = M.restoreEnv(kept.env, {
              device: offered('env-device'), board: offered('env-board'), form: offered('env-form-kind'), shift: offered('env-shift'),
              field: offered('env-field'), ime: offered('env-ime'), show: offered('env-show'), bar: offered('env-bar'), panel: offered('env-sheet'),
            });
          }
          fillEnvForm();
          history = new M.History(kept.doc);
          await changed(true);
          return;
        } catch (e) {
          history = null;
          env = Object.assign({}, M.ENV);
          if (confirm('草稿打不开（' + (e.message || e) + '），丢弃它吗？')) await M.dropDraft(store);
        }
      } else if (answer === 'discard') await M.dropDraft(store);
    }
    fillEnvForm();
    set(M.fromBuiltin(E, 'material'), { fresh: true });
    $('new-panel').showModal();
  }

  start();
})();
