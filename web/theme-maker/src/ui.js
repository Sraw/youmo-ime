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
  const store = (() => { try { return M.idbStore(window.indexedDB); } catch (e) { return null; } })();

  function toast(text) {
    const t = $('toast');
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
    changed();
  }

  // the draft written a moment after the last change, not on every key
  function keep() {
    if (!store || !edited || !history) return;
    clearTimeout(keeping);
    keeping = setTimeout(() => M.keepDraft(store, history.current, env), 500);
  }

  function changed() {
    const d = history.current;
    if ($('source').value !== d.xml) $('source').value = d.xml;
    $('name').textContent = M.themeName(d.xml) || '（无名）';
    $('undo').disabled = !history.canUndo;
    $('redo').disabled = !history.canRedo;
    keep();
    return loadAssets(d).then((current) => { if (current) { preview(); refreshTab(); } });
  }

  // a field the user types in: what a rebuild of its tab under it would take the typing from
  const typedIn = (el) => !!el && (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && !/^(checkbox|radio|button|range|color|file)$/.test(el.type)));
  let stale = false; // the tab shown is older than the document: rebuilt when the field typed in is left

  // the tab drawn anew after a change, unless a field of it is being typed in: a field commits on leaving it
  // (change), so a rebuild then would take the field or button just tapped away (focus lost, its click never
  // comes) or, done later (pictures and fonts decoded first), the characters typed into the next field; the
  // controls read the document when used, not when drawn, so a tab left as it is still does the right thing
  // a pointer held on the tab: a field left by pressing on another control commits (change) between the press
  // and the release, and a tab drawn then would take the control pressed, its focus and its click
  let held = false;
  function refreshTab() {
    const el = $('tab-' + tab);
    if (held || (el && el.contains(document.activeElement) && typedIn(document.activeElement))) { stale = true; return; }
    showTab();
  }
  // once the press is over (its click dispatched) a tab left stale is drawn anew, unless a field of it is typed in
  function released() {
    held = false;
    setTimeout(() => { if (stale && !held) refreshTab(); }, 0);
  }

  // the theme's files as the painter takes them: its images decoded, its fonts loaded under a family of their own
  // an image is decoded only where its header says the app would take it: a pack's bytes are not trusted, and a
  // decode past the browser's memory takes the tab down (a draft restored would do it again each time)
  async function loadAssets(d) {
    // the same files (an edit of theme.xml copies the object, not the bytes): nothing decoded again
    const same = (a, b) => a === b || (a && Object.keys(a).length === Object.keys(b).length && Object.keys(b).every((k) => a[k] === b[k]));
    if (same(assets.key, d.files)) return true;
    const mine = ++loading;
    const images = {};
    const fonts = {};
    const cuts = {};
    const safe = new Set(M.decodableImages(L, FORMAT, d));
    for (const [name, bytes] of Object.entries(d.files)) {
      try {
        if (IMAGE.test(name)) {
          if (!safe.has(name)) continue;
          const bmp = await createImageBitmap(new Blob([bytes]));
          images[name] = { image: bmp, width: bmp.width, height: bmp.height };
          cuts[name] = bmp;
        } else if (FONT.test(name)) {
          const family = 'youmo-file-' + L.contentId(bytes);
          if (![...document.fonts].some((f) => f.family === family)) {
            const face = new FontFace(family, bytes);
            await face.load();
            document.fonts.add(face);
          }
          fonts[name] = family;
        }
      } catch (e) {
        // a file the browser cannot decode: the engine draws without it, as the app would refuse it on import
      }
    }
    if (mine !== loading) {
      for (const b of Object.values(cuts)) b.close();
      return false;
    }
    for (const b of Object.values(assets.cuts)) if (!Object.values(cuts).includes(b)) b.close();
    assets = { key: d.files, images, fonts, cuts };
    return true;
  }

  // the <font name=… src=…> of the theme, by the CSS family its file was loaded under
  function fontFamilies(xml) {
    const out = {};
    for (const m of xml.matchAll(/<font\b[^>]*\bname="([^"]+)"[^>]*\bsrc="([^"]+)"/g)) if (assets.fonts[m[2]]) out[m[1]] = assets.fonts[m[2]];
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
    const made = new Map();
    return (blurDp, brightness) => {
      const b = blurDp == null ? p.blurDp : blurDp;
      const l = brightness == null ? p.brightness : brightness;
      const key = b + '/' + l;
      if (made.has(key)) return made.get(key);
      const c = document.createElement('canvas');
      c.width = scene.width;
      c.height = scene.height;
      const g = c.getContext('2d');
      const s = Math.max(c.width / bmp.width, c.height / bmp.height);
      g.filter = 'blur(' + b * env.density + 'px) brightness(' + l + '%)';
      g.drawImage(bmp, (c.width - bmp.width * s) / 2, (c.height - bmp.height * s) / 2, bmp.width * s, bmp.height * s);
      made.set(key, c);
      return c;
    };
  }

  function preview() {
    const d = history.current;
    const canvas = $('preview');
    const fonts = Object.assign({ key: '"Youmo Latin", "Youmo Han", sans-serif' }, fontFamilies(d.xml));
    const m = E.canvasMeasurer(canvas.getContext('2d'), fonts);
    const r = M.check(E, d, env.board, M.envJson(env), m.ok ? m.measurer : null);
    scene = r.scene;
    theme = r.theme;
    showProblems(r.problems);
    if (!scene) return;
    canvas.width = scene.width;
    canvas.height = scene.height;
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    const state = { pressed, icons: ICONS, images: assets.images, fonts };
    const picture = pictureOf();
    if (picture) state.picture = picture;
    E.paint(scene, ctx, state);
  }

  function showProblems(list) {
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
    ta.scrollTop = Math.max(0, (line - 3) * 17);
  }

  // ---- files ----

  function pick(input) {
    return new Promise((ok) => {
      input.value = '';
      input.onchange = () => ok(input.files[0] || null);
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
      for (const n of notes) {
        if (n.kind === 'converted') toast('这是 ' + n.from + ' 主题，已转成 4.0');
        if (n.kind === 'layouts') toast('带自定义布局：' + n.ids.join('、') + '，保存时原样保留');
      }
    } catch (e) {
      toast('打不开这个文件：' + (e.message || e));
    }
  }

  // the picture decoded (upright, as its EXIF says), drawn at sizeOf its decoded sides and encoded: JPEG, or PNG
  // where any pixel is see-through
  // [rect] (l t r b of the decoded picture): only that part of it
  async function encodePicture(bytes, sizeOf, quality, rect = null) {
    const bmp = await createImageBitmap(new Blob([bytes]));
    try {
      const [l, t, r, b] = rect || [0, 0, bmp.width, bmp.height];
      const [w, h] = sizeOf(r - l, b - t);
      const c = document.createElement('canvas');
      c.width = w;
      c.height = h;
      const g = c.getContext('2d');
      g.drawImage(bmp, l, t, r - l, b - t, 0, 0, w, h);
      const px = g.getImageData(0, 0, w, h).data;
      let alpha = false;
      for (let i = 3; i < px.length && !alpha; i += 4) alpha = px[i] < 255;
      const type = alpha ? 'png' : 'jpg';
      const blob = await new Promise((ok) => c.toBlob(ok, alpha ? 'image/png' : 'image/jpeg', quality));
      return { bytes: new Uint8Array(await blob.arrayBuffer()), type };
    } finally {
      bmp.close();
    }
  }

  // a picture the user picks as the 背景 page takes it: the picture made a file the app takes, and a cut of the
  // keyboard's shape for each orientation, cropped at its middle (the 背景 page moves them)
  async function choosePicture() {
    const f = await pick($('image-input'));
    if (!f) return null;
    try {
      const made = await M.pictureFile(L, FORMAT, new Uint8Array(await f.arrayBuffer()), encodePicture);
      const size = L.imageSize(made.bytes);
      const bg = PANELS.find((p) => p.id === 'background').ops;
      const cuts = {};
      for (const o of ['portrait', 'landscape']) {
        const rect = F.cropRect(size[0], size[1], bg.ratio(Object.assign({}, env, { landscape: o === 'landscape' })));
        cuts[o] = await cutOf(made.bytes, rect);
      }
      return { type: made.type, src: made.bytes, cuts };
    } catch (e) {
      toast(e && e.code === 'limit' ? '图片太大，打不开' : '这张图片用不了（只认 PNG、JPEG、WebP）');
      return null;
    }
  }

  // [rect] of the picture [bytes] as a file of its own, encoded as every picture is
  async function cutOf(bytes, rect) {
    const side = Math.min(M.PICTURE_SAVE.longSide, FORMAT.limits.pictureSide);
    const made = await encodePicture(bytes, (w, h) => L.resizeInside(w, h, side, side), M.PICTURE_SAVE.quality, rect);
    return { bytes: made.bytes, type: made.type, rect };
  }

  async function fromImage() {
    const chosen = document.querySelector('input[name="image-base"]:checked');
    const made = await choosePicture();
    if (!made) return;
    const bg = PANELS.find((p) => p.id === 'background').ops;
    set(asDoc(bg.setPicture(M.fromBuiltin(E, chosen ? chosen.value : 'material'), made)));
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
      if (!bmp) return null;
      const c = document.createElement('canvas');
      c.width = 24;
      c.height = 16;
      const g = c.getContext('2d');
      g.drawImage(bmp, 0, 0, c.width, c.height);
      const px = g.getImageData(0, 0, c.width, c.height).data;
      const out = [];
      for (let i = 0; i < px.length; i += 4) out.push([px[i], px[i + 1], px[i + 2]]);
      return out;
    },
  };

  // the tab shown drawn anew from the document (the source tab is the text area, kept as it is typed)
  function showTab() {
    for (const b of $('tabs').children) b.setAttribute('aria-selected', String(b.dataset.tab === tab));
    for (const el of document.querySelectorAll('#edit .tab')) el.hidden = el.id !== 'tab-' + tab;
    const panel = PANELS.find((p) => p.id === tab);
    if (!panel || !history) return;
    const el = $('tab-' + tab);
    const scroll = $('edit').scrollTop;
    // the control that had focus (a select just changed, a box ticked) has it again in the tab drawn anew: the
    // same place among the tab's controls
    const controls = () => [...el.querySelectorAll('input, select, textarea, button')];
    const focused = controls().indexOf(document.activeElement);
    stale = false;
    el.textContent = '';
    try {
      panel.render(el, api);
    } catch (e) {
      el.textContent = '这一页出错了：' + (e.message || e);
    }
    if (focused >= 0 && controls()[focused]) controls()[focused].focus({ preventScroll: true });
    $('edit').scrollTop = scroll;
  }

  function save() {
    if (!history) return;
    const d = history.current;
    const bad = M.check(E, d, env.board, M.envJson(env), null).problems.filter((p) => p.level === 'error');
    if (bad.length) { toast('有 ' + bad.length + ' 个错误，App 导入不了，先改好'); return; }
    const refused = M.saveProblems(E, L, FORMAT, d);
    if (refused.length) { toast('App 会拒绝导入：' + refused.map((p) => p.message).join('；')); return; }
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
    Object.assign(env, { landscape: $('env-landscape').checked, board: $('env-board').value, form: $('env-form-kind').value, shift: $('env-shift').value });
    fillEnvForm();
    keep();
    if (history) { preview(); showTab(); }
  }

  // ---- start ----

  function wire() {
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
    // a tab left stale while its field was typed in (refreshTab) drawn anew once focus leaves the tab
    $('edit').addEventListener('focusout', (e) => {
      const el = $('tab-' + tab);
      if (stale && !held && el && !(e.relatedTarget && el.contains(e.relatedTarget))) showTab();
    });
    $('edit').addEventListener('pointerdown', () => { held = true; }, true);
    window.addEventListener('pointerup', released, true);
    window.addEventListener('pointercancel', released, true);
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
    $('undo').addEventListener('click', () => { if (history && history.canUndo) { history.undo(); edited = true; changed(); } });
    $('redo').addEventListener('click', () => { if (history && history.canRedo) { history.redo(); edited = true; changed(); } });
    document.addEventListener('keydown', (e) => {
      if (!(e.ctrlKey || e.metaKey) || e.target === $('source')) return;
      if (e.key === 'z' && !e.shiftKey) { e.preventDefault(); $('undo').click(); }
      else if (e.key === 'y' || (e.key === 'z' && e.shiftKey)) { e.preventDefault(); $('redo').click(); }
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
      const k = scene.keyAt((e.clientX - r.left) * scene.width / r.width, (e.clientY - r.top) * scene.height / r.height);
      return k >= 0 ? scene.json().keys[k].id : null;
    };
    canvas.addEventListener('pointerdown', (e) => {
      if (!history) return;
      pressed = keyAt(e);
      if (pressed) picked = { board: env.board, key: pressed };
      $('picked').textContent = picked ? '选中的键：' + picked.key + '（' + (BOARD_LABEL[picked.board] || picked.board) + '）' : '';
      preview();
      showTab();
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
          await changed();
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
