// The layout maker's page; what a change does is youmo-layout-maker's and youmo-layout-edit's, the drawing the engine's.
(function () {
  'use strict';

  const E = globalThis['youmo-engine'] && globalThis['youmo-engine'].YoumoEngine;
  const M = window.YoumoMaker;
  const L = window.YoumoLib;
  const LM = window.YoumoLayoutMaker;
  const LE = window.YoumoLayoutEdit;
  const F = window.YoumoForm;
  const $ = (id) => document.getElementById(id);
  const ICONS = JSON.parse($('youmo-engine-icons').textContent);
  const EXAMPLES = JSON.parse($('youmo-layout-examples').textContent);
  const THEME_LABEL = { material: 'Material', pixel: 'Pixel', nord: 'Nord', 'deep-blue': 'Deep Blue', monokai: 'Monokai', 'amoled-black': 'AMOLED', monet: '系统取色' };
  const FONTS = { key: '"Youmo Latin", "Youmo Han", sans-serif' };
  const TFORMAT = JSON.parse($('youmo-format').textContent);
  // not one of E.themes(), so a draft kept with it restores the default
  const OPENED = 'opened';

  let FORMAT = null; // the engine's layoutFormat()
  let history = null; // a document: M.doc(the layout's text)
  let env = Object.assign({}, LM.ENV);
  let edited = false; // the draft is kept only once the user has changed something
  let keeping = null;
  let measurer = null; // {key: its fonts, m}
  let opened = null; // a .theme opened: {name, theme, images, fonts}, its bitmaps and font faces let go when another is
  const themes = new Map(); // a built-in theme read, by id
  const store = (() => { try { return M.idbStore(window.indexedDB, 'youmo-layout-maker'); } catch (e) { return null; } })();

  const OLD_GROUPS = (keys) => (keys ? '；' + keys + ' 个分组键显示的字改成了大写字母（旧版页面写的 {group:…} 超过两个字母，App 读不了）' : '');

  function toast(text) {
    const t = $('toast');
    const host = document.querySelector('dialog[open]') || document.body;
    if (t.parentNode !== host) host.append(t);
    t.textContent = text;
    t.classList.add('on');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => t.classList.remove('on'), 3500);
  }


  function set(xml, { fresh = false, external = false } = {}) {
    const d = M.doc(xml);
    if (fresh || !history) history = new M.History(d);
    else if (!history.push(d)) return;
    else edited = true;
    changed(fresh || external);
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
    if (new TextEncoder().encode(history.current.xml).length > FORMAT.limits.bytes) return;
    M.keepDraft(store, history.current, env).then((ok) => {
      if (!ok && !keepFailed) toast('草稿存不下了（浏览器的存储满了）：关掉页面前记得保存');
      keepFailed = !ok;
    });
  }

  function changed(external = false) {
    const xml = history.current.xml;
    if ($('source').value !== xml) $('source').value = xml;
    const i = LM.info(xml);
    $('name').textContent = (i && i.name) || '（无名）';
    $('top').inert = $('main').inert = false;
    $('undo').disabled = !history.canUndo;
    $('redo').disabled = !history.canRedo;
    keep();
    if (external) relocate();
    preview();
    fill(external);
  }


  function themeOf(id) {
    if (id === OPENED) return opened && opened.theme;
    if (!themes.has(id)) {
      const r = E.readTheme(E.themeSource(id), null, null);
      themes.set(id, r.ok ? r.theme : null);
    }
    return themes.get(id);
  }

  function preview() {
    const canvas = $('preview');
    const fonts = fontsNow();
    const key = JSON.stringify(fonts);
    if (!measurer || measurer.key !== key) {
      const m = E.canvasMeasurer(canvas.getContext('2d'), fonts);
      measurer = { key, m: m.ok ? m.measurer : E.fixedMeasurer() };
    }
    const xml = history.current.xml;
    const r = LM.check(E, xml, env, themeOf(env.theme), measurer.m, FORMAT.version);
    showProblems(r.problems);
    fillShape(r.info && r.info.family);
    if (!r.scene) {
      shown = null;
      canvas.width = canvas.width; // cleared: a layout the engine cannot read draws nothing
      columns();
      return;
    }
    canvas.width = r.scene.width;
    canvas.height = r.scene.height;
    const json = r.scene.json();
    shown = { scene: r.scene, json, keys: json.keys, at: new Map() };
    paintShown();
    columns();
  }

  // the scroll columns over the preview, the category the app shows in the env's field, to scroll through: a tap
  // types nothing here, it selects the column as a tap on a key does
  function columns() {
    const over = $('preview-columns');
    over.textContent = '';
    if (!shown) return;
    const j = shown.json;
    const cells = parsed().rows.flatMap((r) => r.cells);
    // by its id, else the nth unnamed: none where a when= hides one
    const cellOf = (s) => {
      const like = cells.filter((c) => c.name === 'slot' && c.a.kind === s.kind && c.a.id == s.id);
      const drawn = j.slots.filter((x) => x.kind === s.kind && x.id == s.id);
      return like.length === drawn.length ? like[drawn.indexOf(s)] : null;
    };
    j.slots.filter((x) => x.kind === 'scroll' || x.kind === 'nine-side').forEach((s) => {
      const across = s.direction === 'horizontal';
      const items = (s.items || []).map((i) => {
        const span = h('span', {}, i.label != null ? i.label : LE.FN_LABEL[i.command] || i.command);
        span.style[across ? 'width' : 'height'] = 100 / s.show + '%';
        return span;
      });
      const c = cellOf(s);
      const col = h('div', { className: across ? 'column across' : 'column', 'aria-hidden': 'true', onclick: () => {
        if (c) selectCell({ row: c.row, index: c.index });
      } }, ...items);
      const [l, t, r, b] = s.bounds;
      Object.assign(col.style, { left: l / j.width * 100 + '%', top: t / j.height * 100 + '%', width: (r - l) / j.width * 100 + '%', height: (b - t) / j.height * 100 + '%' });
      over.append(col);
    });
  }

  function showProblems(list) {
    const ul = $('problems');
    ul.textContent = '';
    for (const p of list) {
      const li = document.createElement('li');
      li.className = p.level;
      const text = (p.level === 'error' ? '错误' : '警告') + (p.line ? '（第 ' + p.line + ' 行）' : '') + '：' + p.message;
      // any other problem goes to its line: a button, reached by Tab and read as one
      if (p.line) {
        li.append(h('button', { type: 'button', onclick: () => {
          const at = LE.atLine(history.current.xml, p.line);
          if (!at) return goToLine(p.line);
          selectCell(at);
        } }, text));
      } else li.textContent = text;
      ul.append(li);
    }
    const errors = list.filter((p) => p.level === 'error').length;
    $('count').textContent = list.length ? errors + ' 个错误，' + (list.length - errors) + ' 个警告' : '没有';
  }

  function goToLine(line) {
    showTab('source');
    const ta = $('source');
    const lines = ta.value.split('\n');
    const start = lines.slice(0, line - 1).reduce((n, l) => n + l.length + 1, 0);
    ta.focus();
    ta.setSelectionRange(start, start + (lines[line - 1] || '').length);
    ta.scrollTop = Math.max(0, (line - 3) * (parseFloat(getComputedStyle(ta).lineHeight) || 17));
  }


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


  // cells by place and their keys' ids, to find them again after an undo, a redo or the source typed in
  let sel = [];
  let fragmentKey = null; // {id, include}
  let rowTarget = -1; // the row 行 chose: where 加键 puts a key with none selected
  let tab = 'key';
  let shown = null; // the preview's scene: {scene, keys, at: scene id -> cell (found once per preview)}
  let press = null; // a press on the preview: {mode: pending|move|width, …}
  let draggedPart = -1; // the row list's part dragged by its ☰

  const h = (tag, props = {}, ...kids) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(props)) {
      if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else if (k === 'className' || k === 'textContent' || k === 'value' || k === 'checked' || k === 'disabled' || k === 'title') e[k] = v;
      else e.setAttribute(k, v);
    }
    e.append(...kids.filter((x) => x != null && x !== false));
    return e;
  };
  const line = (what, ...controls) => h('div', { className: 'row' }, h('span', { className: 'what' }, what), ...controls);
  const field = (value, commit, props = {}) => h('input', { value: value ?? '', onchange: (e) => commit(e.target.value), ...props });
  const choice = (list, value, commit, props = {}) => {
    const s = h('select', { onchange: (e) => commit(e.target.value), ...props });
    for (const [v, label] of list) s.add(new Option(label, v));
    s.value = value ?? '';
    return s;
  };
  const button = (text, act, props = {}) => h('button', { type: 'button', onclick: act, ...props }, text);

  const xmlNow = () => history.current.xml;
  const keyboardAttrs = (xml) => { const k = F.elements(xml, 'keyboard')[0]; return k ? Object.fromEntries(k.attrs) : {}; };
  const columnsOf = (xml) => parseFloat(keyboardAttrs(xml).columns) || 10;
  // parsed once per text: a drag repaints on every pointer move
  let parsedOf = { xml: null, p: null };
  const parsed = () => {
    const xml = xmlNow();
    if (parsedOf.xml !== xml) parsedOf = { xml, p: LE.parse(xml) };
    return parsedOf.p;
  };
  const source = (src) => { try { return E.layoutSource(src) || null; } catch (e) { return null; } };
  const hasOwn = (o, k) => !!k && Object.hasOwn(o, k);

  function edit(xml, at) {
    if (at !== undefined) {
      sel = at ? [{ row: at.row, index: at.index }] : [];
      fragmentKey = null;
    }
    if (xml !== xmlNow()) set(xml);
    else fill();
  }

  function withId(at) {
    const c = LE.cellAt(parsed(), at);
    return { row: at.row, index: at.index, id: c && c.a.id };
  }

  function selectCell(at) {
    sel = [withId(at)];
    fragmentKey = null;
    showTab('key');
    paintShown();
  }

  function relocate() {
    const xml = xmlNow();
    const seen = new Set();
    sel = sel.map((s) => (s.id ? LE.locate(xml, s.id, drawnIds) : null)).filter(Boolean).map(withId).filter((s) => !seen.has(s.id) && seen.add(s.id));
    if (fragmentKey) {
      const include = LE.includeOf(xml, fragmentKey.id, source);
      fragmentKey = include >= 0 ? { id: fragmentKey.id, include } : null;
    }
  }

  function drawnIds(text) {
    const r = E.readOwnLayout(text);
    const theme = themeOf(env.theme);
    if (!r.ok || !theme) return [];
    const s = E.buildScene(r.layout, theme, LM.envJson(E, env, r.layout.family, r.layout), measurer ? measurer.m : E.fixedMeasurer());
    return s.ok ? s.scene.json().keys.map((k) => k.id) : [];
  }

  function locateShown(id) {
    if (!shown) return LE.locate(xmlNow(), id, drawnIds);
    if (!shown.at.has(id)) shown.at.set(id, LE.locate(xmlNow(), id, drawnIds));
    return shown.at.get(id);
  }

  function cellOfKey(key) {
    const at = locateShown(key.id);
    if (at) return { at: { row: at.row, index: at.index, id: key.id } };
    const include = LE.includeOf(xmlNow(), key.id, source);
    return include >= 0 ? { fragment: { id: key.id, include } } : null;
  }

  function select(key, add) {
    const found = key ? cellOfKey(key) : null;
    if (!found) {
      if (!add) { sel = []; fragmentKey = null; }
    } else if (found.fragment) {
      sel = [];
      fragmentKey = found.fragment;
    } else if (add) {
      fragmentKey = null;
      const i = sel.findIndex((s) => s.row === found.at.row && s.index === found.at.index);
      if (i >= 0) sel.splice(i, 1);
      else sel.push(found.at);
    } else {
      sel = [found.at];
      fragmentKey = null;
    }
    if (tab === 'key') fill(); else showTab('key');
    paintShown();
  }


  function paintShown() {
    const canvas = $('preview');
    if (!shown) return;
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    const own = env.theme === OPENED && opened;
    E.paint(shown.scene, ctx, { pressed: null, icons: ICONS, images: own ? opened.images : {}, fonts: fontsNow() });
    const p = parsed();
    const ids = new Set(sel.map((at) => LE.cellAt(p, at)).filter(Boolean).map((c) => c.a.id));
    if (fragmentKey) ids.add(fragmentKey.id);
    const accent = getComputedStyle(document.body).getPropertyValue('--accent').trim() || '#3b6fd8';
    ctx.save();
    ctx.lineWidth = 6;
    ctx.strokeStyle = accent;
    for (const k of shown.keys) if (ids.has(k.id)) ctx.strokeRect(k.bounds[0] + 3, k.bounds[1] + 3, k.bounds[2] - k.bounds[0] - 6, k.bounds[3] - k.bounds[1] - 6);
    if (press && press.mode === 'move' && press.target) {
      const t = press.target;
      ctx.fillStyle = accent;
      ctx.fillRect(t.x - 4, t.y0, 8, t.y1 - t.y0);
    }
    if (press && press.mode === 'width' && press.units != null) {
      const b = press.key.bounds;
      ctx.setLineDash([12, 8]);
      ctx.strokeRect(b[0], b[1], press.px, b[3] - b[1]);
      ctx.setLineDash([]);
      ctx.font = '600 40px sans-serif';
      ctx.fillStyle = accent;
      ctx.fillText(LE.num(press.units), b[0] + 10, b[1] + 44);
    }
    ctx.restore();
  }

  const toScene = (e) => {
    const c = $('preview');
    const r = c.getBoundingClientRect();
    return { x: (e.clientX - r.left) * c.width / r.width, y: (e.clientY - r.top) * c.height / r.height, scale: c.width / r.width };
  };
  const keyAtPoint = (p) => {
    const i = shown ? shown.scene.keyAt(p.x, p.y) : -1;
    return i >= 0 ? shown.keys[i] : null;
  };

  function dropTarget(p) {
    const key = keyAtPoint(p);
    const at = key && locateShown(key.id);
    if (!at) return null;
    const before = p.x < (key.bounds[0] + key.bounds[2]) / 2;
    return { row: at.row, index: before ? at.index : at.index + 1, x: before ? key.bounds[0] : key.bounds[2], y0: key.bounds[1], y1: key.bounds[3] };
  }

  function widthAt(p) {
    const k = press.key;
    const kw = k.bounds[2] - k.bounds[0];
    const px = Math.max(10, kw + p.x - press.x);
    const pp = parsed();
    const c = LE.cellAt(pp, sel[0]);
    const columns = columnsOf(xmlNow());
    const w = parseFloat(LE.widthOf(xmlNow(), pp, c));
    const per = Number.isFinite(w) && kw > 0 ? w / kw : columns / shown.json.width;
    press.px = px;
    press.units = LE.snap(px * per, columns);
  }

  function pointerDown(e) {
    if (!shown || e.button > 0) return;
    const p = toScene(e);
    const key = keyAtPoint(p);
    const one = sel.length === 1 && LE.cellAt(parsed(), sel[0]);
    const mine = one && key && key.id === one.a.id;
    const touch = e.pointerType === 'touch';
    // a finger only after the 300 ms press, as a drag: else a swipe there would not scroll
    if (mine && Math.abs(p.x - key.bounds[2]) < 16 * p.scale) {
      press = { mode: touch ? 'pending' : 'width', key, x: p.x, y: p.y, id: e.pointerId, touch };
      if (touch) {
        press.timer = setTimeout(() => {
          if (!press || press.mode !== 'pending') return;
          press.mode = 'width';
          try { $('preview').setPointerCapture(press.id); } catch (e) { /* the finger already lifted */ }
        }, 300);
      } else $('preview').setPointerCapture(e.pointerId);
      return;
    }
    if (touch) {
      // a finger selects on lifting (a swipe over the preview scrolls the page) and drags only after a 300 ms press
      press = { mode: 'pending', x: p.x, y: p.y, key, touch, id: e.pointerId };
      press.timer = setTimeout(() => {
        if (!press || press.mode !== 'pending' || !key) return;
        select(key, false);
        if (sel.length !== 1) { press = null; return; }
        press.at = sel[0];
        press.mode = 'move';
        try { $('preview').setPointerCapture(press.id); } catch (e) { /* the finger already lifted */ }
      }, 300);
      return;
    }
    select(key, e.shiftKey || e.ctrlKey || e.metaKey);
    if (!key || sel.length !== 1) return;
    press = { mode: 'pending', x: p.x, y: p.y, at: sel[0], touch, id: e.pointerId };
    $('preview').setPointerCapture(e.pointerId);
  }

  function pointerMove(e) {
    if (!press || e.pointerId !== press.id) return;
    const p = toScene(e);
    const far = Math.hypot(p.x - press.x, p.y - press.y) / p.scale;
    if (press.mode === 'pending') {
      if (press.touch && far > 10) { clearTimeout(press.timer); press = null; return; }
      if (!press.touch && far > 6) press.mode = 'move';
    }
    if (press.mode === 'move') press.target = dropTarget(p);
    else if (press.mode === 'width') widthAt(p);
    paintShown();
  }

  function pointerUp(e) {
    if (!press || e.pointerId !== press.id) return;
    const done = press;
    clearTimeout(done.timer);
    press = null;
    if (done.mode === 'pending' && done.touch) select(done.key, false);
    else if (done.mode === 'move' && done.target) {
      const r = LE.move(xmlNow(), done.at, done.target);
      edit(r.xml, r.at);
    } else if (done.mode === 'width' && done.units != null) {
      edit(LE.setAttrs(xmlNow(), sel[0], { width: LE.num(done.units) }));
    }
    paintShown();
  }

  function stepSelection(e) {
    const dirs = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -2, ArrowDown: 2 };
    if (!shown || !shown.keys.length || !(e.key in dirs)) return;
    e.preventDefault();
    const p = parsed();
    const id = fragmentKey ? fragmentKey.id : sel.length ? (LE.cellAt(p, sel[sel.length - 1]) || { a: {} }).a.id : null;
    const keys = shown.keys;
    const now = keys.findIndex((k) => k.id === id);
    const d = dirs[e.key];
    let next = null;
    if (now < 0) next = keys[0];
    else if (Math.abs(d) === 1) next = keys[now + d] || keys[now];
    else {
      const mid = (k) => [(k.bounds[0] + k.bounds[2]) / 2, (k.bounds[1] + k.bounds[3]) / 2];
      const [x, y] = mid(keys[now]);
      const there = keys.filter((k) => (d < 0 ? k.bounds[3] <= keys[now].bounds[1] : k.bounds[1] >= keys[now].bounds[3]));
      if (there.length) {
        const rowY = mid(there.reduce((b, k) => (Math.abs(mid(k)[1] - y) < Math.abs(mid(b)[1] - y) ? k : b)))[1];
        next = there.filter((k) => Math.abs(mid(k)[1] - rowY) < 1).reduce((b, k) => (Math.abs(mid(k)[0] - x) < Math.abs(mid(b)[0] - x) ? k : b));
      } else next = keys[now];
    }
    select(next, false);
  }


  function render(t) {
    if (t === 'key') return keyTab(parsed());
    if (t === 'row') return rowTab(parsed());
    if (t === 'board') return boardTab();
    if (t === 'scroll') return scrollTab(parsed());
    if (t === 'meta') return metaTab();
    return null;
  }

  function tidy() {
    const p = parsed();
    sel = sel.filter((at) => LE.cellAt(p, at)).map(withId);
    if (rowTarget >= p.rows.length) rowTarget = -1;
    if (fragmentKey && !p.includes[fragmentKey.include]) fragmentKey = null;
    const say = fragmentKey ? '选中 ' + fragmentKey.id + '（App 的底行）' : sel.length ? '选中 ' + sel.map((s) => s.id || '空位').join('、') : '没有选中';
    if ($('selected').textContent !== say) $('selected').textContent = say;
  }

  function showTab(t) {
    tab = t;
    for (const b of $('tabs').children) b.setAttribute('aria-selected', String(b.dataset.tab === t));
    for (const el of document.querySelectorAll('#edit .tab')) el.hidden = el.id !== 'tab-' + t;
    if (!history) return;
    tidy();
    const el = $('tab-' + t);
    const kids = render(t);
    if (!kids) return;
    const key = el.contains(document.activeElement) && document.activeElement.matches(CONTROLS) ? controlKey(document.activeElement) : null;
    el.replaceChildren(...kids);
    const again = key && [...el.querySelectorAll(CONTROLS)].find((c) => controlKey(c) === key);
    if (again) again.focus({ preventScroll: true });
  }

  // nodes kept, values updated, so the field being typed in, its caret and a touch keyboard over it stay
  function fill(external = false) {
    if (!history) return;
    const el = $('tab-' + tab);
    if (!el || el.hidden || !el.childNodes.length) { showTab(tab); return; }
    tidy();
    const kids = render(tab);
    if (!kids) return;
    const fresh = h('div', {}, ...kids);
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

  let dirty = null;
  const typedIn = (el) => !!el && (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && !/^(checkbox|radio|button|range|color|file)$/.test(el.type)));
  const CONTROLS = 'input, select, textarea, button';

  // a part whose data-key differs is replaced whole, never kept with stale handlers
  function sync(to, from, skip) {
    if (to.nodeType !== from.nodeType || to.nodeName !== from.nodeName) return false;
    if (to.nodeType === Node.TEXT_NODE) { if (to.data !== from.data) to.data = from.data; return true; }
    if (to.nodeType !== Node.ELEMENT_NODE) return true;
    if (to.dataset.key !== from.dataset.key) {
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
      for (const name of ['placeholder', 'list']) {
        if (to.getAttribute(name) !== from.getAttribute(name)) {
          if (from.hasAttribute(name)) to.setAttribute(name, from.getAttribute(name)); else to.removeAttribute(name);
        }
      }
    }
    return true;
  }

  // redrawn a task later: on touch the old field's change fires inside the next tap, before focus moves
  let rebuilding = null;
  function rebuildSoon() {
    clearTimeout(rebuilding);
    rebuilding = setTimeout(() => {
      const el = $('tab-' + tab);
      const a = document.activeElement;
      const typed = typedIn(a) && a === dirty && el.contains(a) ? { value: a.value, from: a.selectionStart, to: a.selectionEnd, key: controlKey(a) } : null;
      showTab(tab);
      const b = document.activeElement;
      if (typed && typedIn(b) && controlKey(b) === typed.key && b.value !== typed.value) {
        b.value = typed.value;
        dirty = b;
        try { b.setSelectionRange(typed.from, typed.to); } catch (e) { /* a number field has no caret */ }
      }
    }, 0);
  }

  function controlKey(c) {
    const row = c.closest('.row, li, p');
    const what = row && row.querySelector('.what');
    const inRow = row ? [...row.querySelectorAll(CONTROLS)].indexOf(c) : -1;
    return c.tagName + '|' + (what ? what.textContent : '') + '|' + (c.getAttribute('aria-label') || '') + '|' + inRow + '|' + (what ? '' : c.textContent || c.type);
  }

  async function ownBottom(include) {
    const d = $('own-panel');
    d.returnValue = '';
    d.showModal();
    const before = LE.parse(xmlNow()).includes[include];
    const answer = await new Promise((ok) => d.addEventListener('close', () => ok(d.returnValue), { once: true }));
    if (answer !== 'yes') return;
    // the text changed while asking (an undo, the source): the include may be another or gone
    const inc = LE.parse(xmlNow()).includes[include];
    const text = inc && before && inc.a.src === before.a.src ? source(inc.a.src) : null;
    if (!text) return toast('底行已经不在了');
    const id = fragmentKey && fragmentKey.id;
    fragmentKey = null;
    sel = [];
    set(LE.ownRows(xmlNow(), include, text));
    const at = id && LE.locate(xmlNow(), id, drawnIds);
    if (at) { sel = [withId(at)]; fill(); paintShown(); }
  }

  function insertAt(p) {
    if (sel.length === 1) return sel[0];
    const row = rowTarget >= 0 ? rowTarget : p.rows.length - 1;
    return row >= 0 ? { row, index: p.rows[row].cells.length } : null;
  }
  function add(text) {
    const xml = xmlNow();
    const at = insertAt(LE.parse(xml));
    if (!at) return toast('先加一行（「行」）');
    edit(LE.insert(xml, at, LE.withFreeId(xml, text, source)), at);
  }

  function palette(p) {
    const i = LM.info(xmlNow()) || {};
    const base = i.basedOn && FORMAT.bases.includes(i.basedOn) ? i.basedOn : null;
    const texts = base ? [source(base), ...LE.parse(source(base) || '').includes.map((x) => source(x.a.src))].filter(Boolean) : [];
    const letters = field('', () => {}, { placeholder: 'q 或 we', maxlength: 5, 'aria-label': '字母', autocapitalize: 'off' });
    const text = field('', () => {}, { placeholder: '一段文字', maxlength: 32, 'aria-label': '字符键打出的文字' });
    const where = insertAt(p);
    const fns = line('功能键', ...LE.functionKeys(texts).map((f) => button(f.label, () => add(f.text), { title: f.id })));
    fns.dataset.key = base || '';
    return [
      h('h3', {}, '加键'),
      h('p', { className: 'muted' }, sel.length === 1 ? '加在选中的键左边' : where ? '加在第 ' + (where.row + 1) + ' 行末尾' : '还没有行'),
      line('字母键', letters, button('加', () => {
        const v = letters.value.trim().toLowerCase();
        const why = LE.lettersProblem(v);
        if (why) return toast(why);
        add(LE.letterKey(xmlNow(), v, source));
      })),
      line('字符键', text, button('加', () => { if (text.value) add(LE.charKey(xmlNow(), text.value, source)); })),
      fns,
      line('空位', button('加空位', () => add('<gap/>'))),
    ];
  }

  function groupBar() {
    const n = LE.coverage(xmlNow());
    return h('div', { className: 'groups', 'aria-label': '分组字母' }, ...'abcdefghijklmnopqrstuvwxyz'.split('').map((ch) =>
      h('span', { className: !n[ch] ? 'missing' : n[ch] > 1 ? 'twice' : '', title: !n[ch] ? '没有键打这个字母' : n[ch] > 1 ? n[ch] + ' 个键打这个字母' : '' }, ch)));
  }

  function iconPreview(name) {
    const c = h('canvas', { width: 48, height: 48, className: 'icon', 'data-key': name || '' });
    if (hasOwn(ICONS, name)) E.paintIcon(c.getContext('2d'), { icons: ICONS }, name, 8, 8, 32, '#ff808890', 0);
    return c;
  }

  const KINDS = [['', '默认'], ['letter', '字母键'], ['function', '功能键'], ['space', '空格'], ['enter', '回车']];
  const LABELS = [['none', '不显示字'], ['text', '文字'], ['case', '随 Shift 大小写'], ['punct', '按标点转换'], ['other', '绑定（照写）']];
  const POPUP_FROM = { preset: '底稿里同 id 的键', nine: '九键的数字（from="nine"）', group: '键上的分组字母（from="group"）', none: '没有：长按不弹（from="none"）' };

  function keyTab(p) {
    const out = [];
    const xml = xmlNow();
    if (fragmentKey) {
      const inc = p.includes[fragmentKey.include];
      out.push(h('p', {}, '「' + fragmentKey.id + '」在 App 的底行里（' + (inc ? inc.a.src : '') + '）：App 改底行，这里跟着变。'),
        button('改成这块键盘自己的底行…', () => ownBottom(fragmentKey.include)));
    } else if (!sel.length) {
      out.push(h('p', { className: 'muted' }, '点预览里的键选中它；Shift/Ctrl 点多选；预览有焦点时方向键换键、Delete 删除。拖键换位置，拖选中键的右边缘改宽度；手机上长按 0.3 秒再拖。'));
    } else if (sel.length > 1) {
      out.push(...manyKeys(p));
    } else {
      out.push(...oneKey(p, sel[0], LE.cellAt(p, sel[0]), xml));
    }
    const mine = h('div', { 'data-key': fragmentKey ? 'f:' + fragmentKey.id + ':' + fragmentKey.include : 'k:' + sel.map((s) => s.row + '.' + s.index).join(' ') }, ...out);
    return [mine, ...palette(p)];
  }

  function manyKeys(p) {
    const cells = sel.map((at) => LE.cellAt(p, at));
    const family = (LM.info(xmlNow()) || {}).family;
    const setAll = (values) => {
      let xml = xmlNow();
      for (const at of sel) xml = LE.setAttrs(xml, at, values);
      edit(xml);
    };
    const canMerge = family === 'letters' && cells.every(LE.lettersOf);
    return [
      h('h3', {}, '选了 ' + sel.length + ' 个'),
      line('宽度', field('', (v) => setAll({ width: v.trim() }), { type: 'number', min: 0.5, max: 100, step: 0.5, placeholder: '单位', 'aria-label': '宽度' })),
      line('种类', choice(KINDS, '', (v) => setAll({ kind: v || null }), { 'aria-label': '种类' })),
      canMerge && line('合并', button('合并成一个分组键', () => {
        const r = LE.merge(xmlNow(), sel, source);
        if (r.error) return toast(r.error);
        edit(r.xml, r.at);
        toast(LE.MERGED);
      })),
      line('', button('删除这些', removeSelected)),
    ];
  }

  function removeSelected() {
    let xml = xmlNow();
    for (const at of sel.slice().sort((a, b) => b.row - a.row || b.index - a.index)) xml = LE.remove(xml, at);
    edit(xml, null);
  }

  function oneKey(p, at, c, xml) {
    const set1 = (values) => edit(LE.setAttrs(xmlNow(), at, values));
    // a handler kept over a change reads the key as written now, not what the tab was drawn from
    const now = () => (LE.cellAt(parsed(), at) || { a: {} }).a;
    const go = (r) => edit(r.xml, r.at);
    const moves = line('位置',
      button('←', () => go(LE.step(xmlNow(), at, -1, 0)), { 'aria-label': '左移' }),
      button('→', () => go(LE.step(xmlNow(), at, 1, 0)), { 'aria-label': '右移' }),
      button('上一行', () => go(LE.step(xmlNow(), at, 0, -1))),
      button('下一行', () => go(LE.step(xmlNow(), at, 0, 1))),
      button('删除', () => edit(LE.remove(xmlNow(), at), null)));
    const a = c.a;
    const columns = columnsOf(xml);
    const width = LE.widthOf(xml, p, c);
    const fill = width === 'fill';
    const units = fill ? '' : width;
    const slider = h('input', { type: 'range', min: columns === 100 ? 1 : 0.5, max: Math.min(100, columns), step: columns === 100 ? 1 : 0.5, value: units || 1, disabled: fill, 'aria-label': '宽度滑块',
      onchange: (e) => set1({ width: e.target.value }) });
    const widths = line('宽度', field(a.width === 'fill' ? '' : a.width, (v) => set1({ width: v.trim() }), { type: 'number', min: 0, max: 100, step: 0.5, placeholder: units || '默认', disabled: fill, 'aria-label': '宽度' }),
      h('label', { className: 'inline' }, h('input', { type: 'checkbox', checked: fill, onchange: (e) => set1({ width: e.target.checked ? 'fill' : null }) }), '占满'), slider);
    const out = [h('h3', {}, (c.name === 'key' ? '键 ' : c.name === 'gap' ? '空位 ' : '位置 ') + (a.id || '')), moves, widths];
    const span = line('跨行', field(a.rows, (v) => set1({ rows: v === '1' ? null : v }), { type: 'number', min: 1, max: 4, step: 1, placeholder: '1', 'aria-label': '跨几行' }));
    if (c.name === 'slot') out.push(span);
    if (c.name === 'slot' && a.kind === 'scroll') out.push(...scrollOptions(at, a));
    if (c.name !== 'key') return out;
    const shared = LE.sharedName(a, FORMAT.prefixes);
    const label = LE.labelParts(a.label);
    const writeLabel = (type, arg) => {
      const why = LE.labelProblem(type, arg);
      if (why) return toast(why);
      set1({ label: LE.labelText(type, arg) });
    };
    const labelInput = field(label.arg, (v) => writeLabel(labelKind.value, v), { 'aria-label': '显示的字', maxlength: 32, disabled: label.type === 'none' });
    const labelKind = choice(LABELS, label.type, (v) => { labelInput.disabled = v === 'none'; writeLabel(v, labelInput.value); }, { 'aria-label': '显示的字是' });
    const icons = [['', '无'], ...Object.keys(ICONS).sort().map((n) => [n, n])];
    const iconName = a.icon && !/[{}]/.test(a.icon) ? a.icon : '';
    out.push(
      line('名字', h('code', {}, a.shortcut || '（没有，跟底稿同 id 的键）')),
      line('显示的字', labelKind, labelInput),
      line('图标', a.icon && !iconName ? h('code', {}, a.icon) : choice(icons, iconName, (v) => set1({ icon: v || null }), { 'aria-label': '图标' }), iconPreview(iconName)),
      line('读屏念', field(a.a11y, (v) => set1({ a11y: v }), { 'aria-label': '读屏念什么', maxlength: 32 }),
        a.icon && !a.label && !a.a11y ? h('span', { className: 'bad' }, '图标键必填') : null),
      span,
      line('种类', choice(KINDS, a.kind || '', (v) => set1({ kind: v || null }), { 'aria-label': '种类' })),
    );
    if (a.letters || (LM.info(xml) || {}).family === 'grouped') {
      out.push(groupBar(), line('分组字母', field(a.letters, (v) => {
        const l = v.trim().toLowerCase();
        if (l && LE.lettersProblem(l.replace(/ /g, ''))) return toast(LE.lettersProblem(l.replace(/ /g, '')));
        const held = (x) => E.checkLayout(x, FORMAT.version, null).warnings.filter((f) => f.key === 'held-letters').map((f) => f.line);
        edit(LE.setAttrs(LE.setLetters(xmlNow(), at, l || null, held), at, { lean: l ? now().lean : null }));
      }, { 'aria-label': '分组字母', autocapitalize: 'off' })));
      if (a.letters) out.push(line('落点', choice([['', '默认'], ['on', '偏向按到的字母'], ['off', '不偏']], a.lean || '', (v) => set1({ lean: v || null }), { 'aria-label': 'lean' })));
    }
    out.push(h('h3', {}, '动作'));
    if (shared) out.push(h('p', { className: 'muted' }, shared));
    else out.push(h('p', { className: 'muted' }, '这里写的是默认值：用户在 App 里改过的以用户的为准。'));
    for (const [name, what] of LE.ACTIONS) {
      const ap = LE.actionParts(a[name]);
      const off = !!shared && name !== 'send';
      const arg = field(ap.arg, (v) => set1({ [name]: LE.actionText(kind.value, v) }), { disabled: off || !ap.type, 'aria-label': what + '的参数', list: 'action-args-' + (ap.type || 'none') });
      const kind = choice(LE.ACTION_TYPES, ap.type, (v) => {
        arg.disabled = !v;
        arg.setAttribute('list', 'action-args-' + (v || 'none'));
        if (!v || arg.value) set1({ [name]: v ? LE.actionText(v, arg.value) : null });
        else arg.focus();
      }, { disabled: off, 'aria-label': what });
      out.push(line(what, kind, arg));
    }
    const pop = LE.popupOf(xml, at);
    const list = pop && pop.from === 'list';
    if (pop && !list) {
      out.push(h('p', { className: 'muted' }, '这个键的长按来自' + (pop.from === 'preset' && pop.preset ? '键 ' + pop.preset : POPUP_FROM[pop.from] || pop.from) + '；在下面写列表会换成自己的。'));
    }
    out.push(line('长按列表', field(list ? pop.items.map((x) => x.text).join(' ') : '', (v) => edit(LE.setPopup(xmlNow(), at, v.split(/\s+/).filter(Boolean))),
      { disabled: !!shared, placeholder: list || !pop ? '空格分开，如 ， 。 ？' : '写了就换掉上面说的', 'aria-label': '长按列表' })));
    return out;
  }

  function rowTab(p) {
    const list = h('ol', { className: 'parts' });
    p.parts.forEach((part, i) => {
      const moves = [button('↑', () => edit(LE.movePart(xmlNow(), i, i - 1)), { disabled: i === 0, 'aria-label': '上移' }),
        button('↓', () => edit(LE.movePart(xmlNow(), i, i + 2)), { disabled: i === p.parts.length - 1, 'aria-label': '下移' })];
      let item;
      if (part.include) {
        item = h('li', { 'data-key': i + ':' + part.include.a.src }, h('span', { className: 'handle', 'aria-hidden': 'true' }, '☰'), 'App 的底行（' + part.include.a.src + '）', ...moves,
          button('改成自己的…', () => ownBottom(part.include.index)));
      } else {
        const r = part.row;
        const ra = r.a;
        item = h('li', { className: rowTarget === r.index || (sel.length === 1 && sel[0].row === r.index) ? 'on' : '', 'data-key': i + ':' + r.index },
          h('span', { className: 'handle', 'aria-hidden': 'true' }, '☰'),
          button('第 ' + (r.index + 1) + ' 行 · ' + r.cells.length + ' 个', () => { rowTarget = r.index; sel = []; fragmentKey = null; fill(); paintShown(); }),
          h('label', { className: 'inline' }, '行高', field(ra.height, (v) => edit(LE.setRow(xmlNow(), r.index, { height: v === '1' ? null : v })), { type: 'number', min: 0.1, max: 4, step: 0.1, placeholder: '1', 'aria-label': '行高' })),
          h('label', { className: 'inline' }, '缩进', field(ra.indent, (v) => edit(LE.setRow(xmlNow(), r.index, { indent: v === '0' ? null : v })), { type: 'number', min: 0, max: 100, step: 0.5, placeholder: '0', 'aria-label': '缩进' })),
          ...moves,
          button('删除', () => edit(LE.removePart(xmlNow(), i), null)));
      }
      const handle = item.querySelector('.handle');
      handle.draggable = true;
      handle.addEventListener('dragstart', (e) => { draggedPart = i; e.dataTransfer.setData('text/plain', String(i)); });
      item.addEventListener('dragover', (e) => e.preventDefault());
      item.addEventListener('drop', (e) => {
        e.preventDefault();
        const from = draggedPart;
        draggedPart = -1;
        if (from >= 0 && from !== i) edit(LE.movePart(xmlNow(), from, from < i ? i + 1 : i));
      });
      list.append(item);
    });
    return [
      h('p', { className: 'muted' }, '拖 ☰ 或用 ↑↓ 换行序；点「第 n 行」后，加键加在那一行末尾。'),
      list,
      button('加一行', addRow),
    ];
  }

  function addRow() {
    const xml = xmlNow();
    const p = LE.parse(xml);
    const row = sel.length ? sel[sel.length - 1].row : rowTarget;
    let part;
    if (row >= 0 && row < p.rows.length) part = LE.partOfRow(xml, row) + 1;
    else for (part = p.parts.length; part > 0 && p.parts[part - 1].include;) part--;
    const out = LE.addRow(xml, part, '<gap/>');
    rowTarget = p.parts.slice(0, part).filter((x) => x.row).length;
    edit(out, null);
    toast('加了第 ' + (rowTarget + 1) + ' 行（先放了一个空位，加了键后可以删掉它）：去「键」加键');
  }

  function boardTab() {
    const xml = xmlNow();
    const a = keyboardAttrs(xml);
    const setK = (name, v) => edit(LM.setKeyboard(xmlNow(), name, v === '' ? null : v));
    const out = [
      line('名字', field(a.name, (v) => { const why = LM.nameProblem(v, FORMAT); if (why) return toast(why); setK('name', v.trim()); }, { maxlength: FORMAT.limits.name, 'aria-label': '名字' })),
      line('id', h('code', {}, a.id || '')),
      line('种类', choice(FORMAT.families.map((f) => [f, LM.FAMILY_LABEL[f] || f]), a.family, (v) => setK('family', v), { 'aria-label': 'family' })),
      line('底稿', choice(FORMAT.bases.map((b) => [b, LM.BASE_LABEL[b] || b]), a['based-on'], (v) => setK('based-on', v), { 'aria-label': '底稿' })),
      line('列数', field(a.columns, (v) => setK('columns', v), { type: 'number', min: 1, max: 100, step: 1, placeholder: '10', 'aria-label': '列数' })),
      line('分体时', choice([['', '默认（放在一边）'], ['split', '分成两半'], ['side', '放在一边'], ['none', '不分']], a['on-split'] || '', (v) => setK('on-split', v), { 'aria-label': '分体方式' })),
    ];
    if (a.family === 'number') {
      // an older pad's name is read as the full pad's, as the engine reads it; another unknown one is the engine's error
      const merged = ['digits', 'signed', 'decimal', 'signed-decimal'];
      const pads = (x) => new Set((keyboardAttrs(x).pads || '').split(' ').filter(Boolean).map((p) => (merged.includes(p) ? 'full' : p)));
      const on = pads(xml);
      out.push(line('数字键盘', ...FORMAT.pads.map((pad) => h('label', { className: 'inline' }, h('input', { type: 'checkbox', checked: on.has(pad), onchange: (e) => {
        // a box kept over a change does not have the set it was drawn with
        const now = pads(xmlNow());
        if (e.target.checked) now.add(pad); else now.delete(pad);
        setK('pads', FORMAT.pads.filter((x) => now.has(x)).join(' '));
      } }), LM.PAD_LABEL[pad] || pad))));
    }
    if (a.family === 'grouped') out.push(h('h3', {}, '分组字母'), groupBar());
    return out;
  }

  // a scroll column's category, direction and how many items it shows: the 「滑动列」 tab's and the key tab's alike
  function scrollOptions(at, a) {
    const set1 = (values) => edit(LE.setAttrs(xmlNow(), at, values));
    const most = FORMAT.scroll.maxShow;
    return [
      line('类别', choice(FORMAT.scroll.lists.map((id) => [id, LM.SCROLL_LABEL[id] || id]), a.list || '', (v) => set1({ list: v }), { 'aria-label': '滑动列类别' })),
      line('方向', choice(LM.DIRECTIONS, a.direction || 'vertical', (v) => set1({ direction: v === 'vertical' ? null : v }), { 'aria-label': '滑动方向' })),
      line('显示几项', choice(Array.from({ length: most }, (_, i) => [String(i + 1), String(i + 1)]), a.show || '4', (v) => set1({ show: v === '4' ? null : v }), { 'aria-label': '显示几项' })),
    ];
  }

  function scrollTab(p) {
    const xml = xmlNow();
    const family = (LM.info(xml) || {}).family;
    const at = sel.length === 1 ? sel[0] : null;
    const why = LE.scrollProblem(xml, family, at, FORMAT);
    const out = [h('p', { className: 'muted' }, '滑动列是一列能滑动的符号，内容由 App 按类别给（用户在「按键字符和快捷键」的「滑动列」里改）。宽度、跨几行在「键」页调，删除也在「键」页。')];
    for (const s of LE.scrolls(p)) {
      out.push(h('h3', {}, '滑动列 ' + (s.a.id || '') + '（第 ' + (s.at.row + 1) + ' 行）'),
        line('', button('选中它', () => selectCell(s.at))),
        ...scrollOptions(s.at, s.a));
    }
    out.push(line('', button('加一列', () => {
      const r = LE.addScroll(xmlNow(), at, family, FORMAT, source);
      if (r.error) return toast(r.error);
      edit(r.xml, r.at);
      toast('在' + (at ? '选中的格子' : '第一行') + '左边加了一列（宽是整行的 16%、跨 ' + (LE.cellAt(LE.parse(r.xml), r.at).a.rows || 1) + ' 行）：到「键」页改宽度和跨几行');
    }, { disabled: !!why, title: why || '' })), why && h('p', { className: 'muted' }, why));
    return out;
  }

  function metaTab() {
    const m = (LM.info(xmlNow()) || { meta: {} }).meta;
    const setM = (name) => (v) => edit(LM.setMeta(xmlNow(), name, v.trim()));
    return [
      line('作者', field(m.author, setM('author'), { maxlength: 64, 'aria-label': '作者' })),
      line('版本', field(m.version, setM('version'), { maxlength: 64, 'aria-label': '版本' })),
      line('许可', field(m.license, setM('license'), { maxlength: 64, placeholder: '如 CC-BY-4.0', 'aria-label': '许可' })),
      line('说明', field(m.description, setM('description'), { maxlength: 200, 'aria-label': '说明' })),
      line('最低 App', h('code', {}, m['min-app'] || '（保存时写）')),
    ];
  }


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
    // by its bytes, not its name: one saved as .zip or x.theme.zip is still a theme
    const head = new Uint8Array(await f.slice(0, 2).arrayBuffer());
    if (/\.theme$/i.test(f.name) || (head[0] === 0x50 && head[1] === 0x4b)) return openTheme(f);
    try {
      // a file far past the limit is not read whole to be refused
      const bytes = new Uint8Array(await f.slice(0, FORMAT.limits.bytes + 1).arrayBuffer());
      const read = M.oldGroupFaces(LM.openText(bytes, FORMAT.limits.bytes, f.size));
      set(read.xml, { fresh: true });
      edited = true;
      keep();
      toast('已打开 ' + f.name + OLD_GROUPS(read.keys));
      return true;
    } catch (e) {
      toast('打不开 ' + f.name + '：' + (e.message || e));
      return false;
    }
  }

  function fontsNow() {
    return Object.assign({}, FONTS, env.theme === OPENED && opened ? opened.fonts : {});
  }

  let opening = 0;
  async function openTheme(f) {
    let d;
    let theme;
    // the later wins, the earlier's late finish dropped
    const mine = ++opening;
    try {
      d = (await M.open(E, L, TFORMAT, new Uint8Array(await f.arrayBuffer()))).doc;
      const r = E.readTheme(d.xml, null, null);
      if (!r.ok) throw new L.ThemeError('bad', r.problems.map((p) => p.message).join('；'));
      theme = r.theme;
    } catch (e) {
      toast('打不开 ' + f.name + '：' + M.openError(e));
      return false;
    }
    if (!(await useTheme(d, theme, () => mine === opening))) return false;
    const boards = LM.themeBoards(E, d, theme, [...FORMAT.bases], FORMAT.version);
    $('theme-text').textContent = boards.length
      ? '「' + theme.name + '」已用作预览的主题。它带 ' + boards.length + ' 块键盘，可以从其中一块开始（和 App 导入时转出来的一样）：'
      : '「' + theme.name + '」已用作预览的主题。它没有带 App 列在布局里的键盘（26 键、14 键、18 键、九键、数字键盘、符号面板）。';
    $('theme-boards').replaceChildren(...boards.map((b) => {
      const li = document.createElement('li');
      if (b.ok) {
        const btn = Object.assign(document.createElement('button'), { type: 'button', textContent: '从「' + b.name + '」开始' });
        btn.addEventListener('click', () => {
          $('theme-panel').close();
          set(b.text, { fresh: true });
          edited = true;
          keep();
          toast('已打开「' + theme.name + '」带的 ' + (LM.BASE_LABEL[b.board] || b.board) + '（id ' + b.id + '）');
        });
        li.append(btn);
      } else li.textContent = (LM.BASE_LABEL[b.board] || b.board) + '：转不出来（' + b.why + '）';
      return li;
    }));
    $('theme-panel').showModal();
    return true;
  }

  // only the images the app would take are decoded; its picture is not drawn here
  async function useTheme(d, theme, current) {
    const images = {};
    const fonts = {};
    const faces = [];
    const safe = new Set(M.decodableImages(L, TFORMAT, d, ['key']));
    const families = {};
    for (const [name, bytes] of Object.entries(d.files)) {
      try {
        if (safe.has(name)) {
          const bmp = await createImageBitmap(new Blob([bytes]));
          images[name] = { image: bmp, width: bmp.width, height: bmp.height };
        } else if (/\.(ttf|otf)$/i.test(name)) {
          const family = 'youmo-file-' + L.contentId(bytes);
          const face = new FontFace(family, bytes);
          await face.load();
          document.fonts.add(face);
          faces.push(face);
          families[name] = family;
        }
      } catch (e) {
      }
    }
    if (!current()) {
      for (const i of Object.values(images)) i.image.close();
      for (const face of faces) document.fonts.delete(face);
      return false;
    }
    for (const [font, file] of Object.entries(theme.info(env.dark).fonts || {})) if (families[file]) fonts[font] = families[file];
    if (opened) {
      for (const i of Object.values(opened.images)) i.image.close();
      for (const face of opened.faces) document.fonts.delete(face);
    }
    opened = { name: theme.name, theme, images, fonts, faces };
    const sel = $('env-theme');
    for (const o of [...sel.options]) if (o.value === OPENED) o.remove();
    sel.add(new Option(theme.name + '（打开的 .theme）', OPENED));
    env.theme = OPENED;
    fillEnv();
    keep();
    if (history) preview();
    return true;
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


  let base = 'qwerty';
  function chooseBase(b) {
    base = b;
    for (const btn of $('bases').children) btn.setAttribute('aria-pressed', String(btn.dataset.base === b));
    for (const btn of $('layout-examples').children) btn.setAttribute('aria-pressed', String(typeof b !== 'string' && btn.dataset.example === b.id));
    $('new-note').textContent = typeof b === 'string' ? '' : b.note;
    const s = LM.suggested(b, FORMAT);
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
    set(typeof base === 'string' ? LM.fromBuiltin(E, base, id, name) : LM.fromExample(E, base, id, name), { fresh: true });
    edited = true;
    keep();
    $('new-panel').close();
  }


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
    for (const b of $('tabs').children) b.addEventListener('click', () => showTab(b.dataset.tab));
    const canvas = $('preview');
    canvas.addEventListener('pointerdown', pointerDown);
    canvas.addEventListener('pointermove', pointerMove);
    canvas.addEventListener('pointerup', pointerUp);
    canvas.addEventListener('pointercancel', () => { if (press) clearTimeout(press.timer); press = null; paintShown(); });
    // touch-action pan-y lets a swipe scroll, so a drag must stop it
    canvas.addEventListener('touchmove', (e) => { if (press && (press.mode === 'move' || press.mode === 'width')) e.preventDefault(); }, { passive: false });
    canvas.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') select(null, false);
      else stepSelection(e);
    });
    document.addEventListener('keydown', (e) => {
      if ((e.key !== 'Delete' && e.key !== 'Backspace') || !history || !sel.length || tab === 'source' || document.querySelector('dialog[open]')) return;
      if (e.target !== canvas && e.target !== document.body) return;
      e.preventDefault();
      removeSelected();
    });
    $('undo').addEventListener('click', () => { if (history.canUndo) { history.undo(); edited = true; changed(true); } });
    $('redo').addEventListener('click', () => { if (history.canRedo) { history.redo(); edited = true; changed(true); } });
    $('edit').addEventListener('input', (e) => { if (typedIn(e.target)) dirty = e.target; });
    $('edit').addEventListener('change', (e) => { if (e.target === dirty) dirty = null; }, true);
    // typed back to what it was: no change event, so the field is let go on leaving it
    let focusValue = null;
    $('edit').addEventListener('focusin', (e) => { focusValue = e.target.value; });
    $('edit').addEventListener('focusout', (e) => { if (e.target === dirty && e.target.value === focusValue) dirty = null; });
    // debounced: the checker builds every scene
    let typing = null;
    document.addEventListener('keydown', (e) => {
      if (!(e.ctrlKey || e.metaKey) || !history || document.querySelector('dialog[open]')) return;
      const k = e.key.toLowerCase();
      if (e.target === $('source')) {
        // 保存 there takes what is typed now, not what the pause last read
        if (k === 's') { e.preventDefault(); clearTimeout(typing); set($('source').value, { external: true }); save(); }
        return;
      }
      // selects, boxes and sliders have no undo of their own
      if (k === 's' && e.target.closest('input, textarea, select')) {
        e.preventDefault();
        if (typedIn(e.target)) e.target.blur();
        save();
        return;
      }
      if (typedIn(e.target)) return;
      if (k === 'z' && !e.shiftKey) { e.preventDefault(); $('undo').click(); } else if (k === 'y' || (k === 'z' && e.shiftKey)) { e.preventDefault(); $('redo').click(); } else if (k === 's') { e.preventDefault(); save(); }
    });
    $('source').addEventListener('input', () => {
      clearTimeout(typing);
      typing = setTimeout(() => set($('source').value, { external: true }), 300);
    });
    $('source').addEventListener('change', () => { clearTimeout(typing); set($('source').value, { external: true }); });

    for (const b of FORMAT.bases) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.dataset.base = b;
      btn.textContent = LM.BASE_LABEL[b] || b;
      btn.addEventListener('click', () => chooseBase(b));
      $('bases').append(btn);
    }
    for (const ex of EXAMPLES) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.dataset.example = ex.id;
      btn.textContent = ex.label;
      btn.title = ex.note;
      btn.addEventListener('click', () => chooseBase(ex));
      $('layout-examples').append(btn);
    }
    // Enter in the name or id clicks it: its submit would close the dialog unchecked
    $('new-make').addEventListener('click', (e) => { e.preventDefault(); makeNew(); });
    $('help-button').addEventListener('click', () => $('help-panel').showModal());
    $('new-open').addEventListener('click', async () => { if (await openFile()) $('new-panel').close(); });
    $('new-panel').addEventListener('close', () => { if (!history) set(LM.fromBuiltin(E, 'qwerty', LM.suggested('qwerty', FORMAT).id, LM.suggested('qwerty', FORMAT).name), { fresh: true }); });
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
          const restored = M.oldGroupFaces(kept.doc.xml);
          history = new M.History(M.doc(restored.xml));
          changed();
          if (restored.keys) toast('草稿已恢复' + OLD_GROUPS(restored.keys));
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
