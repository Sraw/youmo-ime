// youmo-ui: the maker's page (060-render-p3 §5.1, M1): the top bar, the preview the engine draws, the preview's
// environment, the three ways to start, open and save, the draft, undo and redo, the problems. The document and
// what is done to it are youmo-maker's (node tests them); this part is the controls.
(function () {
  'use strict';
  const E = globalThis['youmo-engine'] && globalThis['youmo-engine'].YoumoEngine;
  const L = window.YoumoLib;
  const M = window.YoumoMaker;
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
  let assets = { key: null, images: {}, fonts: {}, cuts: {} };
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
    changed();
  }

  function changed() {
    const d = history.current;
    if ($('source').value !== d.xml) $('source').value = d.xml;
    $('name').textContent = M.themeName(d.xml) || '（无名）';
    $('undo').disabled = !history.canUndo;
    $('redo').disabled = !history.canRedo;
    if (store) M.keepDraft(store, d, env);
    loadAssets(d).then(preview);
  }

  // the theme's files as the painter takes them: its images decoded, its fonts loaded under a family of their own
  async function loadAssets(d) {
    if (assets.key === d.files) return;
    const images = {};
    const fonts = {};
    const cuts = {};
    for (const [name, bytes] of Object.entries(d.files)) {
      try {
        if (IMAGE.test(name)) {
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
    assets = { key: d.files, images, fonts, cuts };
  }

  // the <font name=… src=…> of the theme, by the CSS family its file was loaded under
  function fontFamilies(xml) {
    const out = {};
    for (const m of xml.matchAll(/<font\b[^>]*\bname="([^"]+)"[^>]*\bsrc="([^"]+)"/g)) if (assets.fonts[m[2]]) out[m[1]] = assets.fonts[m[2]];
    return out;
  }

  // the theme's picture, the scene's size: its orientation's cut centre-cropped, blurred and dimmed as asked
  function pictureOf(xml) {
    const p = /<picture\b[^>]*>([\s\S]*?)<\/picture>/.exec(xml);
    if (!p) return null;
    const attr = (re, dflt) => { const m = re.exec(p[0]); return m ? parseFloat(m[1]) : dflt; };
    const ownBlur = attr(/\bblur="([\d.]+)dp"/, 0);
    const ownBright = attr(/\bbrightness="([\d.]+)%"/, 100);
    const cut = new RegExp('<cut\\b[^>]*orientation="' + (env.landscape ? 'landscape' : 'portrait') + '"[^>]*file="([^"]+)"').exec(p[1]) ||
      /<cut\b[^>]*file="([^"]+)"/.exec(p[1]);
    const bmp = cut && assets.cuts[cut[1]];
    if (!bmp || !scene) return null;
    const made = new Map();
    return (blurDp, brightness) => {
      const b = blurDp == null ? ownBlur : blurDp;
      const l = brightness == null ? ownBright : brightness;
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
    showProblems(r.problems);
    if (!scene) return;
    canvas.width = scene.width;
    canvas.height = scene.height;
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    const state = { pressed, icons: ICONS, images: assets.images, fonts };
    const picture = pictureOf(d.xml);
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

  async function fromImage() {
    const f = await pick($('image-input'));
    if (!f) return;
    const type = /png/.test(f.type) ? 'png' : /webp/.test(f.type) ? 'webp' : 'jpeg';
    const base = M.fromBuiltin(E, $('builtins').dataset.chosen || 'material');
    set(M.withPicture(base, new Uint8Array(await f.arrayBuffer()), type));
    $('new-panel').close();
    toast('图片已做键盘背景；配色可在源码里改');
  }

  function save() {
    const d = history.current;
    const bad = M.check(E, d, env.board, M.envJson(env), null).problems.filter((p) => p.level === 'error');
    if (bad.length) { toast('有 ' + bad.length + ' 个错误，App 导入不了，先改好'); return; }
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
    } else {
      const num = (id, v) => (Number.isFinite(+$(id).value) && +$(id).value > 0 ? +$(id).value : v);
      Object.assign(env, { w: num('env-w', env.w), h: num('env-h', env.h), density: num('env-density', env.density) });
      if (e && /env-(w|h|density)/.test(e.target.id)) env.device = 'custom';
    }
    Object.assign(env, { landscape: $('env-landscape').checked, board: $('env-board').value, form: $('env-form-kind').value, shift: $('env-shift').value });
    fillEnvForm();
    if (store) M.keepDraft(store, history.current, env);
    preview();
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

    const cards = $('builtins');
    for (const id of E.themes()) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = THEME_LABEL[id] || id;
      b.addEventListener('click', () => { set(M.fromBuiltin(E, id)); $('new-panel').close(); });
      b.addEventListener('focus', () => { cards.dataset.chosen = id; });
      cards.append(b);
    }
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
    $('undo').addEventListener('click', () => { history.undo(); changed(); });
    $('redo').addEventListener('click', () => { history.redo(); changed(); });
    document.addEventListener('keydown', (e) => {
      if (!(e.ctrlKey || e.metaKey) || e.target === $('source')) return;
      if (e.key === 'z' && !e.shiftKey) { e.preventDefault(); $('undo').click(); }
      else if (e.key === 'y' || (e.key === 'z' && e.shiftKey)) { e.preventDefault(); $('redo').click(); }
    });
    // the source read again as it is typed: one undo step per pause, not per letter
    let typing = null;
    $('source').addEventListener('input', () => {
      clearTimeout(typing);
      typing = setTimeout(() => set(M.doc($('source').value, history.current.files, history.current.layouts)), 400);
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
      pressed = keyAt(e);
      $('picked').textContent = pressed ? '选中的键：' + pressed : '';
      preview();
    });
    const up = () => { if (pressed) { pressed = null; preview(); } };
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
      $('restore-panel').showModal();
      const answer = await new Promise((ok) => $('restore-panel').addEventListener('close', () => ok($('restore-panel').returnValue), { once: true }));
      if (answer === 'restore') {
        if (kept.env) env = Object.assign({}, M.ENV, kept.env);
        fillEnvForm();
        set(kept.doc, { fresh: true });
        return;
      }
      await M.dropDraft(store);
    }
    fillEnvForm();
    set(M.fromBuiltin(E, 'material'), { fresh: true });
    $('new-panel').showModal();
  }

  start();
})();
