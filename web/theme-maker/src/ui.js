// youmo-ui: the maker's page (060-render-p3 §5.1, M1): the top bar, the preview the engine draws, the preview's
// environment, the three ways to start, open and save, the draft, undo and redo, the problems. The document and
// what is done to it are youmo-maker's (node tests them); this part is the controls.
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
  const $ = (id) => document.getElementById(id);

  const THEME_LABEL = { material: 'Material', pixel: 'Pixel', nord: 'Nord', 'deep-blue': 'Deep Blue', monokai: 'Monokai', 'amoled-black': 'AMOLED', monet: '系统取色' };
  const BOARD_LABEL = { qwerty: '26 键', nine: '九键', k14: '14 键', k18: '18 键', zhuyin: '注音', 'zhuyin-nine': '注音九键', number: '数字', symbols: '符号', handwriting: '手写' };
  const FORM_LABEL = { normal: '普通', compact: '紧凑', floating: '悬浮', left: '单手（左）', right: '单手（右）', split: '分体', flat: '平铺' };
  const IMAGE = /\.(png|jpe?g|webp)$/i;
  const FONT = /\.(ttf|otf)$/i;

  let history = null;
  let env = Object.assign({}, M.ENV);
  let scene = null;
  let pressed = null;
  let picked = null; // the key tapped last: {board, key}, the 按键 page's 选中的这个键
  let theme = null; // the engine's reading of the document as the preview last drew it
  let tab = PANELS.length ? PANELS[0].id : 'source';
  let assets = { key: null, images: {}, fonts: {}, cuts: {} };
  let loading = 0; // which loadAssets is the latest: an older one finishing late is dropped
  let edited = false; // the draft is kept only once the user has changed something
  let keeping = null;
  let problems = []; // the problems the preview last listed: a tab shows those of its styles
  let barH = 0; // the bar's sample's height over the keys on the preview canvas (px), the keys under it
  let playing = null; // 试一下's frame: {key, scale, sink, alpha, appear} while it plays
  // the pictures picked this session as they were picked (L9): a cut is made from them, not from the re-encoded file.
  // By the saved file's content id: `{bytes, scale}` (the picked one's decoded width over the saved one's)
  const originals = new Map();
  const store = (() => { try { return M.idbStore(window.indexedDB); } catch (e) { return null; } })();

  function toast(text) {
    const t = $('toast');
    // inside a dialog open (the top layer): under it, a toast behind a tall dialog is never seen
    const host = document.querySelector('dialog[open]') || document.body;
    if (t.parentNode !== host) host.append(t);
    t.textContent = text;
    t.classList.add('on');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => t.classList.remove('on'), 3500);
  }

  // ---- the document ----

  function set(d, { fresh = false } = {}) {
    if (fresh || !history) history = new M.History(d);
    else if (!history.push(d)) return;
    else edited = true;
    changed(fresh);
  }

  // the draft written a moment after the last change, not on every key
  function keep() {
    if (!store || !edited || !history) return;
    clearTimeout(keeping);
    keeping = setTimeout(keepNow, 500);
  }
  // the draft written now (the page hidden or closed: a change of the last moment is kept too); a store that
  // refuses (its quota) said once, the page going on without drafts
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
    // the page answers only once there is a document (m1-review L6): until then its buttons are inert
    $('top').inert = $('main').inert = false;
    $('undo').disabled = !history.canUndo;
    $('redo').disabled = !history.canRedo;
    keep();
    return loadAssets(d).then((current) => { if (current) { preview(); refreshTab(external); } });
  }

  // the tab's field typed in since its last commit (input after its change): Ctrl+Z there is the field's own undo
  // (m3 L-a), and only its text is kept over a rebuild (m3 L-b: a field committed shows the document's value)
  let dirty = null;

  // a field the user types in: what a value written over it would take the typing from
  const typedIn = (el) => !!el && (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && !/^(checkbox|radio|button|range|color|file)$/.test(el.type)));
  const CONTROLS = 'input, select, textarea, button';

  // [to] made to show what [from] (the tab drawn anew, off the page) shows, its nodes kept: a control's value,
  // a text; a part with no control replaced whole by the new one (its handlers, a canvas drawn). False where the controls differ (one more stop, a field
  // gone): the tab is then drawn anew. [skip] keeps its value (the field being typed in).
  function sync(to, from, skip) {
    if (to.nodeType !== from.nodeType || to.nodeName !== from.nodeName) return false;
    if (to.nodeType === Node.TEXT_NODE) { if (to.data !== from.data) to.data = from.data; return true; }
    if (to.nodeType !== Node.ELEMENT_NODE) return true;
    // a control's handlers hold what it was drawn for: the row's name (a colour's, a stop's), a box's data-key
    // (a picture decoded, its crop). Another, and the part is put in whole, never kept with old handlers
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

  // the tab after a change: a value edited changes values in place, never the tab under the user (a tab drawn
  // anew took the field or box just tapped, its focus and its click, or the characters typed into it: on touch
  // the old field's change comes once the new one is tapped). The field being typed in keeps what it has, but
  // not where the change is not its own ([external]: undo, redo, a draft): then it shows the document's.
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
    // a row or box put in whole took the focused control with it (用这个裁切's button): focus its like in the new one
    if (key && !el.contains(document.activeElement)) {
      const again = [...el.querySelectorAll(CONTROLS)].find((c) => controlKey(c) === key);
      if (again) again.focus({ preventScroll: true });
    }
  }

  // a change that adds or takes away controls (a border's colour once it has a width) draws the tab anew, but a task
  // later: on touch the old field's change comes inside the next tap's mousedown, before focus moves, so drawn at
  // once the tab lost the field tapped. By then the focus is on it: showTab focuses its like by controlKey, and what
  // was typed into it and not yet committed is kept with its caret
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

  // a control's place in a tab, the same in the tab drawn anew: its row's label, its place in the row (else its text)
  function controlKey(c) {
    const row = c.closest('label.row, .group, p, li, tr');
    const what = row && row.querySelector('.what');
    const inRow = row ? [...row.querySelectorAll(CONTROLS)].indexOf(c) : -1;
    return c.tagName + '|' + (what ? what.textContent : '') + '|' + inRow + '|' + (what ? '' : c.textContent || c.type);
  }

  // the theme's files as the painter takes them: its images decoded, its fonts loaded under a family of their own
  // an image is decoded only where its header says the app would take it: a pack's bytes are not trusted, and a
  // decode past the browser's memory takes the tab down (a draft restored would do it again each time)
  async function loadAssets(d) {
    // the same files (an edit of theme.xml copies the object, not the bytes): nothing decoded again
    const same = (a, b) => a === b || (a && Object.keys(a).length === Object.keys(b).length && Object.keys(b).every((k) => a[k] === b[k]));
    // the latest all the same: an older one still decoding is dropped when it lands
    if (same(assets.key, d.files)) { ++loading; return true; }
    const mine = ++loading;
    const images = {};
    const fonts = {};
    const cuts = {};
    const safe = new Set(M.decodableImages(L, FORMAT, d));
    for (const [name, bytes] of Object.entries(d.files)) {
      try {
        if (IMAGE.test(name)) {
          if (!safe.has(name)) continue;
          // a file of the same bytes as before: its bitmap kept, not decoded again (a cropper holds it)
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
          }
          fonts[name] = family;
        }
      } catch (e) {
        // a file the browser cannot decode: the engine draws without it, as the app would refuse it on import
      }
    }
    if (mine !== loading) {
      const ours = Object.values(assets.cuts);
      for (const b of Object.values(cuts)) if (!ours.includes(b)) b.close();
      return false;
    }
    for (const b of Object.values(assets.cuts)) if (!Object.values(cuts).includes(b)) b.close();
    // a font no file has any more (replaced, deleted, undone) is let go
    const families = new Set(Object.values(fonts));
    for (const [family, face] of faces) if (!families.has(family)) { document.fonts.delete(face); faces.delete(family); }
    assets = { key: d.files, images, fonts, cuts };
    return true;
  }
  const faces = new Map(); // the fonts this page added to document.fonts, by family

  // the <font name=… src=…> of the theme, by the CSS family its file was loaded under
  function fontFamilies(xml) {
    const out = {};
    // as XML has them: in any order, either quote, entities read, comments skipped
    for (const f of F.elements(xml, 'font')) {
      const a = Object.fromEntries(f.attrs);
      if (a.name && assets.fonts[a.src]) out[a.name] = assets.fonts[a.src];
    }
    return out;
  }

  // the theme's picture, the scene's size: its orientation's cut (made the keyboard's shape by the 背景 page's crop)
  // centre-cropped, blurred and dimmed as asked; read by the engine, as the app reads it
  function pictureOf() {
    const p = theme && theme.info(env.dark).picture;
    if (!p) return null;
    const cuts = p.cuts || {};
    const file = cuts[env.landscape ? 'landscape' : 'portrait'] || cuts.portrait || cuts.landscape;
    const bmp = file && assets.cuts[file];
    if (!bmp || !scene) return null;
    // made once for a bitmap, a size and a blur and brightness: not blurred again on every preview (each tap)
    if (pictures.bmp !== bmp || pictures.w !== scene.width || pictures.h !== scene.height) Object.assign(pictures, { bmp, w: scene.width, h: scene.height, made: new Map() });
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
      // the blur's edge as the app's (its box blur clamps there): drawn past the canvas by 3σ, so no see-through
      // pixels come in at the sides; where the browser has no filter, unblurred (the dimming is drawn, below)
      const m = 'filter' in g && px > 0 ? Math.ceil(px * 3) : 0;
      const s = Math.max((c.width + 2 * m) / bmp.width, (c.height + 2 * m) / bmp.height);
      if (m) g.filter = 'blur(' + px + 'px)';
      g.drawImage(bmp, (c.width - bmp.width * s) / 2, (c.height - bmp.height * s) / 2, bmp.width * s, bmp.height * s);
      g.filter = 'none';
      if (l < 100) {
        g.fillStyle = 'rgba(0, 0, 0, ' + (1 - l / 100) + ')';
        g.fillRect(0, 0, c.width, c.height);
      }
      made.set(key, c);
      return c;
    };
  }
  const pictures = { bmp: null, w: 0, h: 0, made: new Map() };

  // [picture] (pictureOf's) as a part of the scene at [x, y] ([w] × [h] px) sees it: that part of it, not the whole squeezed in
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

  // the bar's sample over the keys (环境 → 候选栏样例): the candidates typing, the toolbar's buttons idle; the code
  // being typed over it. Its height in px, 0 for none
  const PREEDIT_DP = 24;
  function barOf(m) {
    if (!env.bar || !theme) return null;
    const r = E.buildBar(theme, M.envJson(env), m);
    const l = E.looks(theme, M.envJson(env));
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
      // the code being typed: its text, the underline fcitx asks for, the cursor at its end
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
      // idle: the bar's base and five toolbar buttons (a circle, or the toolbar's radius), the second one on
      const h = bar.scene.height;
      if (lk.bar) { ctx.fillStyle = css(lk.bar); ctx.fillRect(0, 0, bar.scene.width, h); }
      const size = Math.min(h - 8 * dp, 36 * dp);
      const step = bar.scene.width / 6;
      for (let i = 0; i < 5; i++) {
        const cx = step * (i + 0.5) + step / 2;
        const on = i === 1;
        const fill = on ? lk.toolbar.onFill : lk.toolbar.fill;
        const r = lk.toolbar.radiusDp == null ? size / 2 : Math.min(size / 2, lk.toolbar.radiusDp * dp);
        if (fill) {
          ctx.fillStyle = css(fill);
          ctx.beginPath();
          ctx.roundRect(cx - size / 2, (h - size) / 2, size, size, r);
          ctx.fill();
        }
        ctx.fillStyle = css(on ? lk.toolbar.onIcon : lk.toolbar.icon);
        ctx.beginPath();
        ctx.arc(cx, h / 2, size / 5, 0, Math.PI * 2);
        ctx.fill();
      }
    }
    ctx.restore();
  }

  // a panel over the keys (环境 → 面板样例): blocks of what the app's views take of the theme (the engine's looks)
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

  // the popup 环境 → 显示 asks for, over the key picked (or the keyboard's middle key): `{scene, x, y}` px on the keys
  function popupOf(m) {
    if (!env.show || !scene || !theme) return null;
    const keys = scene.json().keys;
    const k = (picked && picked.board === env.board && keys.find((x) => x.id === picked.key)) || keys[Math.floor(keys.length / 3)];
    if (!k) return null;
    const [l, t, r] = k.bounds;
    const label = k.label || 'a';
    let req;
    if (env.show === 'preview') req = { kind: 'preview', text: label, keyWidth: r - l };
    else if (env.show === 'keys') req = { kind: 'keys', labels: ['à', 'á', 'â', 'ä', 'ā'], rows: [[0, 1, 2, 3, 4]], columns: 5, selected: 1 };
    else req = { kind: 'menu', cells: [{ label: '剪贴板' }, { label: '表情' }, { label: '编辑' }, { label: '设置' }], order: [0, 1, 2, 3], selected: 0 };
    const got = E.buildPopup(theme, M.envJson(env), req, m);
    if (!got.ok) return null;
    const p = got.scene;
    const cx = (l + r) / 2;
    const x = env.show === 'menu' ? (scene.width - p.width) / 2 : Math.max(0, Math.min(scene.width - p.width, cx - p.width / 2));
    const y = env.show === 'menu' ? (scene.height - p.height) / 2 : t - p.height;
    return { scene: p, x, y };
  }

  // the measurer of the theme's fonts, kept while they are the same (its measures cached)
  let measured = { key: null, canvas: null, fonts: null, m: null };
  function measurerFor(canvas, xml) {
    const fonts = Object.assign({ key: '"Youmo Latin", "Youmo Han", sans-serif' }, fontFamilies(xml));
    const key = JSON.stringify(fonts);
    if (measured.key !== key || measured.canvas !== canvas) measured = { key, canvas, fonts, m: E.canvasMeasurer(canvas.getContext('2d'), fonts) };
    return measured.m;
  }
  let checked = null;

  function preview() {
    const d = history.current;
    const canvas = $('preview');
    const m = measurerFor(canvas, d.xml);
    const fonts = measured.fonts;
    // a tap changes only what is pressed: the theme read and the scene built as they were
    const envKey = JSON.stringify(env);
    const r = checked && checked.d === d && checked.env === envKey && checked.m === m ? checked.r : M.check(E, d, env.board, M.envJson(env), m.ok ? m.measurer : null);
    checked = { d, env: envKey, m, r };
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
      // a sample the engine could not make: the keys drawn without it
    }
    barH = bar ? bar.height : 0;
    canvas.width = scene.width;
    canvas.height = scene.height + barH;
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    const state = { pressed, icons: ICONS, images: assets.images, fonts };
    const picture = pictureOf();
    if (picture) state.picture = picture;
    if (bar) drawBar(ctx, bar, Object.assign({}, state, { pressed: null, picture: pictureAt(picture, 0, 0, scene.width, bar.height) }), fonts);
    ctx.save();
    ctx.translate(0, barH);
    const looks = env.panel && theme ? E.looks(theme, M.envJson(env)) : null;
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

  // ---- 试一下 (动效): the press and its release, the bubble appearing, by requestAnimationFrame ----

  // a frame of what plays ([c]: what play made once for it): the keys, the played key drawn pressed (scaled and sunk
  // about its middle, see-through as it fades back), the bubble over it as it appears
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

  /**
   * [m] (动效's sample: transition, scale, sink, appear) played on the preview: pressed at once (never faded in),
   * held, then faded back over the transition's time; the bubble appearing over its own. Stops when done.
   */
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
    // drawn once for the play, not each frame (the theme read again, the scene built again: frames dropped on a phone)
    measurerFor($('preview'), history.current.xml);
    const state = { pressed: null, icons: ICONS, images: assets.images, fonts: measured.fonts };
    const picture = pictureOf();
    if (picture) state.picture = picture;
    const layer = (pressedKey) => {
      const o = document.createElement('canvas');
      o.width = scene.width;
      o.height = scene.height;
      E.paint(scene, o.getContext('2d'), Object.assign({}, state, { pressed: pressedKey }));
      return o;
    };
    const got = E.buildPopup(theme, M.envJson(env), { kind: 'preview', text: k.label || 'a', keyWidth: k.bounds[2] - k.bounds[0] }, null);
    const cache = { keys: layer(null), pressed: layer(k.id), bounds: k.bounds, bubble: got.ok ? got.scene : null, state };
    const canvas = $('preview');
    const ctx = canvas.getContext('2d');
    const t0 = performance.now();
    // played again before it is done: the one before stops (two loops would draw over each other)
    const mine = ++plays;
    const step = (now) => {
      if (mine !== plays) return;
      const t = now - t0;
      const pressIn = Math.min(1, t / pressMs);
      const release = t < HOLD ? 0 : back ? Math.min(1, (t - HOLD) / back) : 1;
      playing = {
        key: k.id,
        // the press's scale and sink come in over at most 80 ms (§4.1), its colour at once
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

  // ---- files ----

  function pick(input) {
    return new Promise((ok) => {
      input.value = '';
      input.onchange = () => ok(input.files[0] || null);
      input.oncancel = () => ok(null); // the dialog closed with nothing picked
      input.click();
    });
  }

  async function openFile() {
    const f = await pick($('open-input'));
    if (!f) return;
    try {
      const { doc, notes } = await M.open(E, L, FORMAT, new Uint8Array(await f.arrayBuffer()));
      set(doc);
      $('new-panel').close();
      // every note in one toast: one after another, only the last would be seen
      const said = [];
      for (const n of notes) {
        if (n.kind === 'converted') said.push('这是 ' + n.from + ' 主题，已转成 4.0');
        if (n.kind === 'layouts') said.push('带自定义布局：' + n.ids.join('、') + '，保存时原样保留');
        if (n.kind === 'dropped') said.push('包里有 App 不认的文件，已去掉：' + n.names.slice(0, 3).join('、') + (n.names.length > 3 ? ' 等 ' + n.names.length + ' 个' : ''));
      }
      if (said.length) toast(said.join('；'));
    } catch (e) {
      toast('打不开这个文件：' + openError(e));
    }
  }

  // a file refused (youmo-lib's ThemeError codes) in words that say what to do
  function openError(e) {
    switch (e && e.code) {
      case 'limit': return '文件太大或文件太多，超过了 App 的限制';
      case 'newer': return '它是更新版本的幽默输入法做的（' + e.message + '），先刷新这个网页再试';
      case 'older': return '它是很旧的版本（' + e.message + '），先导入 App 再从 App 导出';
      case 'not-theme': return '这不是主题文件（.theme 或 theme.xml）';
      case 'missing': return '主题里写到的文件 ' + e.message + ' 包里没有';
      case 'bad': return '主题文件有错：' + e.message;
      default: return (e && e.message) || String(e);
    }
  }

  // the picture decoded (upright, as its EXIF says), drawn at sizeOf its decoded sides and encoded: JPEG, or PNG
  // where any pixel is see-through
  // [rect] (l t r b of the decoded picture): only that part of it
  // ([bytes] may be a bitmap decoded already: it is drawn, and stays the caller's to close)
  async function encodePicture(bytes, sizeOf, quality, rect = null) {
    const given = bytes instanceof ImageBitmap;
    const bmp = given ? bytes : await createImageBitmap(new Blob([bytes]));
    // a JPEG has no alpha: its pixels are not looked through
    const opaque = !given && L.imageType(bytes) === 'jpg';
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

  // a picture the user picks as the 背景 page takes it: the picture made a file the app takes, and a cut of the
  // keyboard's shape for each orientation, cropped at its middle (the 背景 page moves them)
  async function choosePicture() {
    const f = await pick($('image-input'));
    if (!f) return null;
    try {
      const picked = new Uint8Array(await f.arrayBuffer());
      // decoded once for the file and both cuts (after pictureFile's checks of its header: never one too big)
      let bmp = null;
      try {
        const made = await M.pictureFile(L, FORMAT, picked, async (bytes, ...rest) => encodePicture(bmp ??= await createImageBitmap(new Blob([bytes])), ...rest));
        const size = L.imageSize(made.bytes);
        if (!size) throw new Error('no size');
        const scale = bmp.width / size[0];
        const bg = PANELS.find((p) => p.id === 'background').ops;
        const side = Math.min(M.PICTURE_SAVE.longSide, FORMAT.limits.pictureSide);
        const cuts = {};
        for (const o of ['portrait', 'landscape']) {
          const rect = F.cropRect(size[0], size[1], bg.ratio(Object.assign({}, env, { landscape: o === 'landscape' })));
          const c = await encodePicture(bmp, (w, h) => L.resizeInside(w, h, side, side), M.PICTURE_SAVE.quality, rect.map((v) => v * scale));
          cuts[o] = { bytes: c.bytes, type: c.type, rect };
        }
        // the picked picture kept for this session's cuts (a cut of the saved JPEG would be a second generation):
        // the one before let go only now that this one is made
        originals.clear();
        originals.set(L.contentId(made.bytes), { bytes: picked, scale });
        return { type: made.type, src: made.bytes, cuts };
      } finally {
        if (bmp) bmp.close();
      }
    } catch (e) {
      toast(e && e.code === 'limit' ? '图片太大，打不开' : '这张图片用不了（只认 PNG、JPEG、WebP）');
      return null;
    }
  }

  // [rect] of the picture [bytes] as a file of its own, encoded as every picture is
  async function cutOf(bytes, rect) {
    const side = Math.min(M.PICTURE_SAVE.longSide, FORMAT.limits.pictureSide);
    // [rect] is of the saved picture; the picked one it was made from (this session's) is cut instead, at its scale
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
    // 浅/深 (060-render-p3 §5.1 新建 2): the version shown and the colours worked out of the picture for it
    const dark = !!(document.querySelector('input[name="image-tone"]:checked') || {}).value?.startsWith('dark');
    env.dark = dark;
    fillEnvForm();
    let d = bg.setPicture(M.fromBuiltin(E, chosen ? chosen.value : 'material'), made);
    // sampled from the picture just made (the preview's is still the last one's until it is drawn), as one step
    const cut = made.cuts.portrait || { bytes: made.src };
    const bmp = await createImageBitmap(new Blob([cut.bytes]));
    d = colors.fromPictureAll(d, samplesOf(bmp), dark);
    bmp.close();
    set(asDoc(d));
    $('new-panel').close();
    tab = 'background';
    showTab();
    toast('图片已做键盘背景：在「背景」里调裁切，「配色」里可以从图片取色');
  }

  // ---- the editing's tabs ----

  const asDoc = (x) => M.doc(x.xml, x.files, x.layouts);

  const api = {
    engine: E,
    doc: () => history.current,
    env: () => env,
    envJson: (e = env) => M.envJson(e),
    theme: () => theme,
    picked: () => picked,
    // an op that throws (a document it cannot change: no <theme>) says so instead of failing silently
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
    // the preview as an image at most [width] px across and [bytes] long (信息's preview image): a PNG where one fits,
    // else WebP at falling quality, then smaller, until it does (a picture background's PNG is past the App's 512 KB)
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
    iconNames: () => Object.keys(ICONS).sort(),
    choosePicture,
    cutPicture: (src, rect) => cutOf(history.current.files[src], rect),
    async pickFile(accept) {
      const input = $('any-input');
      input.accept = accept;
      const f = await pick(input);
      return f ? { name: f.name, bytes: await f.arrayBuffer() } : null;
    },
    // the portrait cut's colours, a few hundred of its pixels
    async pictureSamples() {
      const p = theme && theme.info(env.dark).picture;
      const bmp = p && assets.cuts[(p.cuts || {}).portrait || p.src];
      return bmp ? samplesOf(bmp) : null;
    },
  };

  // a few hundred of [bmp]'s pixels, `[[r, g, b]]`
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

  // the tab shown drawn anew from the document (the source tab is the text area, kept as it is typed)
  function showTab() {
    for (const b of $('tabs').children) b.setAttribute('aria-selected', String(b.dataset.tab === tab));
    for (const el of document.querySelectorAll('#edit .tab')) el.hidden = el.id !== 'tab-' + tab;
    const panel = PANELS.find((p) => p.id === tab);
    if (!panel || !history) return;
    const el = $('tab-' + tab);
    const scroll = $('edit').scrollTop;
    // the control that had focus (a select just changed, a box ticked) has it again in the tab drawn anew, where
    // it still is: found by its row and place (controlKey), not its index (a row added or gone moves that)
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

  // 保存 (060-render-p3 M4): checked first on every board, light and dark, and as the app imports. An error stops
  // it (the problems list shows them all, each a click from its line); warnings are listed, the user's to ignore
  async function save() {
    if (!history) return;
    const d = history.current;
    const { errors, warnings } = M.exportCheck(E, L, FORMAT, d, E.layouts(), M.envJson(env));
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
    // octet-stream, as the app exports it: a browser adds no .zip to a name it takes for a zip (specs/app.md)
    a.href = URL.createObjectURL(new Blob([bytes], { type: 'application/octet-stream' }));
    a.download = name;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 10000);
    toast(/Android|iPhone/.test(navigator.userAgent) ? '已保存 ' + name + '。打开幽默输入法 → 主题 → 新增主题 → 导入主题文件' : '已保存 ' + name);
  }

  // ---- the environment ----

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
    Object.assign(env, {
      landscape: $('env-landscape').checked, board: $('env-board').value, form: $('env-form-kind').value, shift: $('env-shift').value,
      field: $('env-field').value, ime: $('env-ime').value, show: $('env-show').value, bar: $('env-bar').value, panel: $('env-sheet').value,
    });
    fillEnvForm();
    keep();
    if (history) { preview(); showTab(); }
  }

  // ---- start ----

  function wire() {
    // the page hidden or closed: the draft written now, not half a second later (a tab closed is gone by then)
    window.addEventListener('pagehide', () => { if (keeping) keepNow(); });
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden' && keeping) keepNow(); });
    for (const d of M.DEVICES) $('env-device').add(new Option(d.label + '（' + d.w + '×' + d.h + '）', d.id));
    $('env-device').add(new Option('自定义', 'custom'));
    const boards = new Set(E.layouts());
    for (const b of M.BOARDS) if (boards.has(b)) $('env-board').add(new Option(BOARD_LABEL[b] || b, b));
    for (const f of M.FORMS) $('env-form-kind').add(new Option(FORM_LABEL[f], f));
    $('env-form').addEventListener('change', readEnvForm);
    $('env').addEventListener('click', () => $('env-panel').showModal());
    $('dark').addEventListener('click', () => { env.dark = !env.dark; readEnvForm(); });

    // the picture's colours: a radio group, so a touch picks one (a button's focus is no choice on a phone)
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
        set(M.exampleDoc(ex));
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
      b.addEventListener('click', () => { set(M.fromBuiltin(E, id)); $('new-panel').close(); });
      cards.append(b);
    }
    // the panels' tabs before the source's, in their order
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
    // (only then: a field left with other text commits by its change, which needs it still dirty)
    let focusValue = null;
    $('edit').addEventListener('focusin', (e) => { focusValue = e.target.value; });
    $('edit').addEventListener('focusout', (e) => { if (e.target === dirty && e.target.value === focusValue) dirty = null; });
    document.addEventListener('keydown', (e) => {
      if (!(e.ctrlKey || e.metaKey) || e.target === $('source') || e.target === dirty) return;
      // not under a dialog: the save's would write what it checked, not what an undo made
      if (document.querySelector('dialog[open]')) return;
      // by the letter whatever the case (Shift, Caps Lock make it 'Z')
      const k = e.key.toLowerCase();
      if (k === 'z' && !e.shiftKey) { e.preventDefault(); $('undo').click(); }
      else if (k === 'y' || (k === 'z' && e.shiftKey)) { e.preventDefault(); $('redo').click(); }
    });
    // the source read again as it is typed: one undo step per pause, not per letter
    let typing = null;
    $('source').addEventListener('input', () => {
      clearTimeout(typing);
      typing = setTimeout(() => { if (history) set(M.doc($('source').value, history.current.files, history.current.layouts)); }, 400);
    });

    // a key tapped is the key picked (the editing's 按键 goes to it, M2); held, it shows pressed
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
      if (pressed) picked = { board: env.board, key: pressed };
      $('picked').textContent = picked ? '选中的键：' + picked.key + '（' + (BOARD_LABEL[picked.board] || picked.board) + '）' : '';
      preview();
      // the tab follows another key picked, its values put in place (a field being typed in keeps its text)
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
      // drawn in the fallback font: measured the same, a little off from the phone's
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
          if (kept.env) env = Object.assign({}, M.ENV, kept.env);
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
