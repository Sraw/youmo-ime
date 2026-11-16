// The layout maker's page (061-custom-layout §12): the preview and its environment, 文件 (新建, 打开, 保存), undo and
// redo, the source, the problems. What a change does is youmo-layout-maker's; the drawing is the engine's.
(function () {
  'use strict';

  const E = globalThis['youmo-engine'] && globalThis['youmo-engine'].YoumoEngine;
  const M = window.YoumoMaker;
  const LM = window.YoumoLayoutMaker;
  const $ = (id) => document.getElementById(id);
  const ICONS = JSON.parse($('youmo-engine-icons').textContent);
  const THEME_LABEL = { material: 'Material', pixel: 'Pixel', nord: 'Nord', 'deep-blue': 'Deep Blue', monokai: 'Monokai', 'amoled-black': 'AMOLED', monet: '系统取色' };
  const FONTS = { key: '"Youmo Latin", "Youmo Han", sans-serif' };

  let FORMAT = null; // the engine's layoutFormat()
  let history = null; // a document: M.doc(the layout's text)
  let env = Object.assign({}, LM.ENV);
  let edited = false; // the draft is kept only once the user has changed something
  let keeping = null;
  let measurer = null;
  const themes = new Map(); // a built-in theme read, by id
  const store = (() => { try { return M.idbStore(window.indexedDB, 'youmo-layout-maker'); } catch (e) { return null; } })();

  function toast(text) {
    const t = $('toast');
    const host = document.querySelector('dialog[open]') || document.body;
    if (t.parentNode !== host) host.append(t);
    t.textContent = text;
    t.classList.add('on');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => t.classList.remove('on'), 3500);
  }

  // ---- the document ----

  function set(xml, { fresh = false } = {}) {
    const d = M.doc(xml);
    if (fresh || !history) history = new M.History(d);
    else if (!history.push(d)) return;
    else edited = true;
    changed();
  }

  function keep() {
    if (!store || !edited || !history) return;
    clearTimeout(keeping);
    keeping = setTimeout(keepNow, 500);
  }
  let keepFailed = false;
  function keepNow() {
    clearTimeout(keeping);
    keeping = null;
    if (!store || !edited || !history) return;
    // a text past the app's limit is not kept (§13): the draft before it stays
    if (new TextEncoder().encode(history.current.xml).length > FORMAT.limits.bytes) return;
    M.keepDraft(store, history.current, env).then((ok) => {
      if (!ok && !keepFailed) toast('草稿存不下了（浏览器的存储满了）：关掉页面前记得保存');
      keepFailed = !ok;
    });
  }

  function changed() {
    const xml = history.current.xml;
    if ($('source').value !== xml) $('source').value = xml;
    const i = LM.info(xml);
    $('name').textContent = (i && i.name) || '（无名）';
    $('top').inert = $('main').inert = false;
    $('undo').disabled = !history.canUndo;
    $('redo').disabled = !history.canRedo;
    keep();
    preview();
  }

  // ---- the preview ----

  function themeOf(id) {
    if (!themes.has(id)) {
      const r = E.readTheme(E.themeSource(id), null, null);
      themes.set(id, r.ok ? r.theme : null);
    }
    return themes.get(id);
  }

  function preview() {
    const canvas = $('preview');
    if (!measurer) {
      const m = E.canvasMeasurer(canvas.getContext('2d'), FONTS);
      measurer = m.ok ? m.measurer : E.fixedMeasurer();
    }
    const xml = history.current.xml;
    const r = LM.check(E, xml, env, themeOf(env.theme), measurer, FORMAT.version);
    showProblems(r.problems);
    fillShape(r.info && r.info.family);
    if (!r.scene) {
      canvas.width = canvas.width; // cleared: a layout the engine cannot read draws nothing
      return;
    }
    canvas.width = r.scene.width;
    canvas.height = r.scene.height;
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    E.paint(r.scene, ctx, { pressed: null, icons: ICONS, images: {}, fonts: FONTS });
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
    ta.scrollTop = Math.max(0, (line - 3) * (parseFloat(getComputedStyle(ta).lineHeight) || 17));
  }

  // ---- the environment ----

  // what the window is laid out as (never a toggle: the device and its turn say it), and the field or the pads by family
  function fillShape(family) {
    const s = LM.shape(E, env);
    $('env-shape').textContent = s.widthDp + ' dp 宽，按' + (s.landscape ? '横屏' : '竖屏') + '排';
    const number = family === 'number';
    $('env-field-label').hidden = number;
    $('env-pad-label').hidden = !number;
  }

  function fillEnv() {
    $('env-device').value = env.device;
    $('env-turned').value = env.turned ? 'turned' : '';
    $('env-form').value = env.form;
    $('env-ime').value = env.ime;
    $('env-shift').value = env.shift;
    $('env-field').value = env.field;
    $('env-pad').value = env.pad;
    $('env-up').value = env.upAt;
    $('env-down').value = env.downAt;
    $('env-theme').value = env.theme;
    $('dark').setAttribute('aria-pressed', String(env.dark));
    $('dark').textContent = env.dark ? '深色' : '浅色';
  }

  function readEnv() {
    const dev = M.DEVICES.find((x) => x.id === $('env-device').value) || M.DEVICES[0];
    Object.assign(env, {
      device: dev.id, w: dev.w, h: dev.h, density: dev.density, turned: $('env-turned').value === 'turned', form: $('env-form').value,
      ime: $('env-ime').value, shift: $('env-shift').value, field: $('env-field').value, pad: $('env-pad').value,
      upAt: $('env-up').value, downAt: $('env-down').value, theme: $('env-theme').value,
    });
    keep();
    if (history) preview();
  }

  // ---- files ----

  function pick(input) {
    return new Promise((ok) => {
      input.value = '';
      input.onchange = () => ok(input.files[0] || null);
      input.oncancel = () => ok(null);
      input.click();
    });
  }

  async function openFile() {
    const f = await pick($('open-input'));
    if (!f) return false;
    try {
      // a file far past the limit is not read whole to be refused
      const bytes = new Uint8Array(await f.slice(0, FORMAT.limits.bytes + 1).arrayBuffer());
      set(LM.openText(bytes, FORMAT.limits.bytes, f.size), { fresh: true });
      edited = true;
      keep();
      toast('已打开 ' + f.name);
      return true;
    } catch (e) {
      toast('打不开 ' + f.name + '：' + (e.message || e));
      return false;
    }
  }

  async function save() {
    if (!history) return;
    const r = LM.saveCheck(E, history.current.xml, FORMAT.version, themeOf(env.theme));
    if (r.errors.length) {
      showProblems([...r.errors, ...r.warnings]);
      $('problems-area').scrollIntoView({ block: 'nearest' });
      toast('有 ' + r.errors.length + ' 个错误，App 导入不了，先改好（见「问题」）');
      return;
    }
    if (r.warnings.length) {
      $('export-text').textContent = r.warnings.length + ' 个警告。App 能导入，但这些地方可能和你想的不一样：';
      $('export-list').replaceChildren(...r.warnings.slice(0, 50).map((p) => Object.assign(document.createElement('li'), { textContent: (p.line ? '第 ' + p.line + ' 行：' : '') + p.message })));
      $('export-panel').returnValue = '';
      $('export-panel').showModal();
      const answer = await new Promise((ok) => $('export-panel').addEventListener('close', () => ok($('export-panel').returnValue), { once: true }));
      if (answer !== 'save') return;
    }
    // min-app as the checker worked it out: part of the document from now on (undo takes it back out)
    set(r.xml);
    const name = LM.fileName(r.xml);
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([r.xml], { type: 'application/xml' }));
    a.download = name;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 10000);
    toast('已保存 ' + name);
  }

  // ---- 新建 ----

  let base = 'qwerty';
  function chooseBase(b) {
    base = b;
    for (const btn of $('bases').children) btn.setAttribute('aria-pressed', String(btn.dataset.base === b));
    const s = LM.suggested(b);
    $('new-name').value = s.name;
    $('new-id').value = s.id;
    $('new-problem').textContent = '';
  }

  function makeNew() {
    const id = $('new-id').value.trim();
    const name = $('new-name').value;
    const why = LM.nameProblem(name, FORMAT) || LM.idProblem(id, FORMAT);
    if (why) {
      $('new-problem').textContent = why;
      return;
    }
    set(LM.fromBuiltin(E, base, id, name), { fresh: true });
    edited = true;
    keep();
    $('new-panel').close();
  }

  // ---- start ----

  function wire() {
    window.addEventListener('pagehide', () => { if (keeping) keepNow(); });
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden' && keeping) keepNow(); });
    const options = (sel, list) => { for (const [v, label] of list) sel.add(new Option(label, v)); };
    options($('env-device'), M.DEVICES.map((d) => [d.id, d.label]));
    options($('env-form'), LM.FORMS);
    options($('env-ime'), LM.IMES.map((x) => [x.id, x.label]));
    options($('env-shift'), LM.SHIFTS);
    options($('env-field'), LM.FIELDS);
    options($('env-pad'), FORMAT.pads.map((p) => [p, LM.PAD_LABEL[p] || p]));
    options($('env-up'), LM.AT);
    options($('env-down'), LM.AT);
    options($('env-theme'), E.themes().map((t) => [t, THEME_LABEL[t] || t]));
    $('env-bar').addEventListener('change', readEnv);
    $('env-theme').addEventListener('change', readEnv);
    $('dark').addEventListener('click', () => { env.dark = !env.dark; fillEnv(); keep(); preview(); });

    $('file').addEventListener('click', () => { $('file-menu').hidden = !$('file-menu').hidden; });
    document.addEventListener('click', (e) => { if (!e.target.closest('#file, #file-menu')) $('file-menu').hidden = true; });
    $('file-menu').addEventListener('click', (e) => {
      const act = e.target.dataset.act;
      $('file-menu').hidden = true;
      if (act === 'new') $('new-panel').showModal();
      else if (act === 'open') openFile();
      else if (act === 'save') save();
    });
    $('undo').addEventListener('click', () => { if (history.canUndo) { history.undo(); edited = true; changed(); } });
    $('redo').addEventListener('click', () => { if (history.canRedo) { history.redo(); edited = true; changed(); } });
    // the source read again a moment after typing stops: the checker builds every scene, not on every key
    let typing = null;
    document.addEventListener('keydown', (e) => {
      if (!(e.ctrlKey || e.metaKey) || !history || document.querySelector('dialog[open]')) return;
      const k = e.key.toLowerCase();
      if (e.target === $('source')) {
        // the textarea's own undo inside it; 保存 there takes what is typed now, not what the pause last read
        if (k === 's') { e.preventDefault(); clearTimeout(typing); set($('source').value); save(); }
        return;
      }
      if (k === 'z' && !e.shiftKey) { e.preventDefault(); $('undo').click(); } else if (k === 'y' || (k === 'z' && e.shiftKey)) { e.preventDefault(); $('redo').click(); } else if (k === 's') { e.preventDefault(); save(); }
    });
    $('source').addEventListener('input', () => {
      clearTimeout(typing);
      typing = setTimeout(() => set($('source').value), 300);
    });
    $('source').addEventListener('change', () => { clearTimeout(typing); set($('source').value); });

    for (const b of FORMAT.bases) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.dataset.base = b;
      btn.textContent = LM.BASE_LABEL[b] || b;
      btn.addEventListener('click', () => chooseBase(b));
      $('bases').append(btn);
    }
    $('new-make').addEventListener('click', makeNew);
    $('new-open').addEventListener('click', async () => { if (await openFile()) $('new-panel').close(); });
    $('new-panel').addEventListener('close', () => { if (!history) set(LM.fromBuiltin(E, 'qwerty', LM.suggested('qwerty').id, LM.suggested('qwerty').name), { fresh: true }); });
    chooseBase('qwerty');
  }

  async function start() {
    if (!E) {
      document.body.textContent = '这个页面没有带绘制引擎：./gradlew :lib:keyboard-scene:youmoEngine，再 node web/theme-maker/tools/assemble.mjs --engine';
      return;
    }
    FORMAT = E.layoutFormat();
    wire();
    try {
      await Promise.all(['400', '700'].flatMap((w) => [document.fonts.load(w + ' 20px "Youmo Latin"', 'a'), document.fonts.load(w + ' 20px "Youmo Han"', '中')]));
    } catch (e) {
      // drawn in the fallback font: measured the same, a little off from the phone's
    }
    const kept = store ? await M.draft(store) : null;
    if (kept) {
      const i = LM.info(kept.doc.xml);
      $('restore-text').textContent = '上次的草稿「' + ((i && i.name) || '无名') + '」（' + new Date(kept.at).toLocaleString() + '）还在，恢复吗？';
      $('restore-panel').addEventListener('cancel', (e) => e.preventDefault());
      $('restore-panel').returnValue = '';
      $('restore-panel').showModal();
      const answer = await new Promise((ok) => $('restore-panel').addEventListener('close', () => ok($('restore-panel').returnValue), { once: true }));
      if (answer === 'restore') {
        try {
          if (!LM.info(kept.doc.xml)) throw new Error('没有 <keyboard>');
          env = LM.restoreEnv(kept.env, [...E.themes()], [...FORMAT.pads]);
          fillEnv();
          history = new M.History(M.doc(kept.doc.xml));
          changed();
          return;
        } catch (e) {
          history = null;
          env = Object.assign({}, LM.ENV);
          if (confirm('草稿打不开（' + (e.message || e) + '），丢弃它吗？')) await M.dropDraft(store);
        }
      } else if (answer === 'discard') await M.dropDraft(store);
    }
    fillEnv();
    $('new-panel').showModal();
  }

  start();
})();
