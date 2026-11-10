// 按键 (060-render-p3 §5.1 panel 3): a range of keys (all, letters, function, space, Return, the picked key, the
// board's every key), a scene, a state, then the face's properties; the line on top says which select= the
// changes go to, the cascade check under it where each of the picked key's values comes from (the engine's own answer).
// Every control reads the document when it is used, not when it was drawn: the page leaves a tab as it is while a
// field of it is typed in (ui.js refreshTab), so a control drawn before the last change must still do the right thing.
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const F = root.YoumoForm;
  const LIM = F.LIMITS;

  // the properties' texts as theme.xml has them (060-render-v2 §5, ThemeReader), kept to what the engine reads
  const TEXT = {
    radius: (v) => F.clamp(v, 0, LIM.lengthDp, 1) + 'dp',
    depth: (v) => F.clamp(v, 0, LIM.depthDp, 1) + 'dp',
    border: (v) => F.borderText(v),
    shadow: (v) => F.shadowText(v),
    'inner-shadow': (v) => F.shadowText(v),
    inset: ([y, x]) => F.clamp(y, 0, LIM.lengthDp, 1) + 'dp ' + F.clamp(x, 0, LIM.lengthDp, 1) + 'dp',
  };

  const ops = {
    /** [d] with [select]'s fill [fill] (F.fillText's; null: not set). At most 8 stops (the engine's). */
    fill: (d, select, fill) => {
      if (fill && fill.kind === 'linear' && fill.stops.length > LIM.stops[1]) fill = Object.assign({}, fill, { stops: fill.stops.slice(0, LIM.stops[1]) });
      return panels.next(d, F.setProp(d.xml, select, 'fill', fill ? F.fillText(fill) : null));
    },
    /**
     * [d] with [select]'s [prop] [v] (a form's value): null not set (the wider style's shows), 'none' none (a
     * border or a shadow taken off over a wider style's).
     */
    set: (d, select, prop, v) => panels.next(d, F.setProp(d.xml, select, prop, v == null ? null : v === 'none' ? 'none' : TEXT[prop] ? TEXT[prop](v) : String(v))),
    /**
     * The cascade check: where key [target].key of [board] gets each property in [env] ([target] as E.sources
     * takes it: `{key, part?, states?}`): `[{property, value, select, line}]`, the value as the winning style
     * writes it, the style found by its place among the styles (two on one line are two).
     */
    sources(E, F, d, theme, board, env, target) {
      const r = E.sources(board, theme, env, Object.assign({ widget: 'key' }, target));
      if (!r.ok) return [];
      const all = F.styles(d.xml);
      return r.sources.map((s) => {
        const style = all[s.order];
        const a = style && style.attrs.find((x) => x[0] === s.property);
        return { property: s.property, value: a ? a[1] : null, select: style ? style.select : null, line: s.line };
      });
    },
    /** The theme's key images (files named `key-…`, what addKeyImage makes, and any an image() fill names; not an effect's). */
    keyImages: (d) => {
      const named = new Set([...F.uncommented(d.xml).matchAll(/(?<![\w-])image\(\s*["']?([^,)\s"']+)/g)].map((m) => m[1]));
      return Object.keys(d.files).filter((n) => /^key-/.test(n) || named.has(n)).sort();
    },
    /**
     * [d] with [bytes] ([name]) a key's image, `key-<name>`: refused (throws) where the app would (a PNG, JPEG or WebP,
     * its long side and bytes in [lim]'s keyImageSide, keyImageBytes). `{…d, file}`.
     */
    addKeyImage(d, L, lim, name, bytes) {
      const type = L.imageType(bytes);
      const s = L.imageSize(bytes);
      if (!['png', 'jpg', 'webp'].includes(type) || !s) throw new Error(name + ' 不是 PNG、JPEG 或 WebP');
      if (Math.max(s[0], s[1]) > lim.keyImageSide) throw new Error(name + ' 太大：最大 ' + lim.keyImageSide + '×' + lim.keyImageSide);
      if (bytes.length > lim.keyImageBytes) throw new Error(name + ' 超过 ' + lim.keyImageBytes + ' 字节');
      const file = panels.fileName(d.files, 'key-', name, type);
      const files = panels.filesOf(d);
      files[file] = bytes;
      return { xml: d.xml, files, layouts: d.layouts, file };
    },
    /** The preview's environment [env] (the page's) as the scene [scene] (F.SCENES') has it: where the cascade check looks. */
    envOf(env, scene) {
      const e = Object.assign({}, env);
      if (scene === 'landscape') e.landscape = true;
      else if (scene === 'dark') e.dark = true;
      else if (scene === 'one-handed') e.form = 'left';
      else if (['compact', 'flat', 'floating', 'split'].includes(scene)) e.form = scene;
      return e;
    },
  };

  // what the page chose, kept while the page is open
  const chosen = { range: 'all', scene: '', state: '' };

  /** The range/scene/state choosers and the select= line: shared with 字和图标. */
  function chooser(el, api, rerender, part = '') {
    const { h, row, select } = panels.ui;
    const picked = api.picked();
    const ranges = F.RANGES.filter(([v]) => v !== 'picked' || picked);
    if (!ranges.some(([v]) => v === chosen.range)) chosen.range = 'all';
    el.append(
      row('范围', select(ranges.map(([v, t]) => [v, v === 'picked' && picked ? t + '（' + picked.key + '）' : t]), chosen.range, (v) => { chosen.range = v; rerender(); })),
      row('场景', select(F.SCENES, chosen.scene, (v) => { chosen.scene = v; rerender(); })),
      row('状态', select(F.STATES, chosen.state, (v) => { chosen.state = v; rerender(); })),
    );
    const sel = F.selector(Object.assign({}, chosen, { board: picked && picked.board, key: picked && picked.key, part }));
    el.append(h('p', { className: 'selector' }, '这些改动写成 ', h('code', { textContent: sel || '—' })));
    return sel;
  }

  // the scene and state chosen, as the table's title says them
  const label = (list, v) => (list.find(([x]) => x === v) || [, v])[1];

  function sourcesTable(el, api, part) {
    const { h } = panels.ui;
    const picked = api.picked();
    const theme = api.theme();
    if (!picked || !theme) {
      el.append(h('p', { className: 'muted', textContent: '在预览上点一个键，这里列出它每个值来自哪条样式。' }));
      return;
    }
    const env = api.envJson(ops.envOf(api.env(), chosen.scene));
    const rows = ops.sources(api.engine, F, api.doc(), theme, picked.board, env, { key: picked.key, part: part || null, states: chosen.state ? [chosen.state] : [] });
    const where = [chosen.scene ? label(F.SCENES, chosen.scene) : '', chosen.state ? label(F.STATES, chosen.state) : ''].filter(Boolean).join('、');
    el.append(h('h3', { textContent: '「' + picked.key + '」' + (part ? ' ' + part : '') + (where ? '（' + where + '）' : '') + '实际生效的值' }));
    if (chosen.scene === 'field=password') el.append(h('p', { className: 'muted', textContent: '密码框按预览的输入框算' }));
    const t = h('table', { className: 'sources' });
    for (const r of rows) {
      t.append(h('tr', {}, h('td', { textContent: r.property }), h('td', { textContent: r.value ?? '' }),
        h('td', {}, h('a', { href: '#', textContent: (r.select || '?') + '（第 ' + r.line + ' 行）', on: { click: (e) => { e.preventDefault(); api.goToLine(r.line); } } }))));
    }
    el.append(t);
  }

  function fillForm(api, sel, value) {
    const { h, row, select, number, color, clear } = panels.ui;
    const f = F.parseFill(value) || { kind: 'none' };
    // the fill as the document has it now (a control drawn before the last change reads it anew)
    const now = () => F.parseFill(F.valueOf(api.doc().xml, sel, 'fill')) || f;
    const out = h('div', { className: 'group' });
    const set = (fill) => api.apply((x) => ops.fill(x, sel, fill));
    const kinds = [['none', '不设'], ['solid', '纯色'], ['linear', '渐变'], ['picture', '透出背景图'], ['image', '九宫格图片']];
    if (f.kind === 'other') kinds.push(['other', '图片（在源码里改）']);
    out.append(row('填充', select(kinds, f.kind, (k) => {
      const c = now();
      if (k === 'none') set(null);
      else if (k === 'solid') set({ kind: 'solid', color: c.kind === 'solid' ? c.color : '@key' });
      else if (k === 'linear') set({ kind: 'linear', angle: 90, stops: [[c.kind === 'solid' ? c.color : '#ffffff', 0], ['#c0c0c0', 100]] });
      else if (k === 'picture') set({ kind: 'picture', blur: 0, brightness: 100, over: '#66ffffff' });
      else if (k === 'image') {
        const file = ops.keyImages(api.doc())[0];
        if (file) set({ kind: 'image', file, insets: [0, 0, 0, 0], scale: 3, fallback: c.kind === 'solid' ? c.color : null });
        else { api.toast('先上传一张键面图片（下面的按钮）'); api.rerender(); } // the choice not made: the select shows what is
      }
    })));
    if (f.kind === 'none' || f.kind === 'solid' || f.kind === 'image') out.append(uploadImage(api, sel));
    if (f.kind === 'solid') out.append(row('颜色', color(f.color, (c) => set(c ? { kind: 'solid', color: c } : null), F)));
    if (f.kind === 'linear') {
      const patch = (fn) => { const c = now(); if (c.kind === 'linear') set(fn(c)); };
      out.append(row('角度', number(f.angle, (a) => patch((c) => Object.assign({}, c, { angle: a ?? 90 })), { min: -360, max: 360, unit: '°' })));
      f.stops.forEach(([c, at], i) => {
        const stop = (fn) => patch((g) => Object.assign({}, g, { stops: g.stops.map((s, j) => (j === i ? fn(s) : s)) }));
        out.append(row('颜色 ' + (i + 1), color(c, (v) => stop(([, p]) => [v || '#000000', p]), F), number(at, (v) => stop(([cc]) => [cc, v ?? 0]), { min: 0, max: 100, unit: '%' }),
          f.stops.length > LIM.stops[0] ? clear(() => patch((g) => Object.assign({}, g, { stops: g.stops.filter((s, j) => j !== i) }))) : null));
      });
      if (f.stops.length < LIM.stops[1]) {
        out.append(h('button', { type: 'button', textContent: '加一个颜色', on: { click: () => patch((g) => (g.stops.length < LIM.stops[1] ? Object.assign({}, g, { stops: g.stops.concat([['#808080', 100]]) }) : g)) } }));
      }
    }
    if (f.kind === 'picture') {
      const patch = (p) => { const c = now(); if (c.kind === 'picture') set(Object.assign({}, c, p)); };
      out.append(
        row('模糊', number(f.blur, (v) => patch({ blur: v ?? 0 }), { min: 0, max: LIM.blurDp, step: 0.5, unit: 'dp' })),
        row('亮度', number(f.brightness, (v) => patch({ brightness: v ?? 100 }), { min: 0, max: 100, unit: '%' })),
        row('盖一层颜色', color(f.over, (v) => patch({ over: v }), F)),
      );
    }
    if (f.kind === 'image') {
      const patch = (p) => { const c = now(); if (c.kind === 'image') set(Object.assign({}, c, p)); };
      const files = ops.keyImages(api.doc());
      out.append(row('图片', select(files.concat(files.includes(f.file) ? [] : [f.file]).map((n) => [n, n]), f.file, (file) => patch({ file }))));
      out.append(row('四角不拉伸', ...['上', '右', '下', '左'].map((side, k) => number(f.insets[k], (v) => { const c = now(); if (c.kind !== 'image') return; const ins = c.insets.slice(); ins[k] = v ?? 0; patch({ insets: ins }); }, { min: 0, max: 9999, unit: 'px ' + side }))));
      out.append(row('图片密度', number(f.scale, (v) => patch({ scale: v ?? 1 }), { min: 1, max: 8, unit: '倍（像素每 dp）' })));
      out.append(row('图片没有时的颜色', color(f.fallback, (v) => patch({ fallback: v }), F)));
    }
    return out;
  }

  // a key's image put in the theme and made the fill: PNG, JPEG or WebP within the app's side and bytes for a key's
  function uploadImage(api, sel) {
    const { h } = panels.ui;
    return h('button', { type: 'button', textContent: '上传键面图片…', on: { click: async () => {
      const f = await api.pickFile('image/png,image/jpeg,image/webp,.png,.jpg,.jpeg,.webp');
      if (!f) return;
      // the image and the fill it is, one step (undone together: no file left that nothing names)
      api.apply((x) => {
        const made = ops.addKeyImage(x, api.lib, api.limits(), f.name, new Uint8Array(f.bytes));
        const c = F.parseFill(F.valueOf(made.xml, sel, 'fill')) || { kind: 'none' };
        return ops.fill(made, sel, c.kind === 'image' ? Object.assign({}, c, { file: made.file }) : { kind: 'image', file: made.file, insets: [0, 0, 0, 0], scale: 3, fallback: c.kind === 'solid' ? c.color : null });
      });
    } } });
  }

  /**
   * A border's or a shadow's 有/无/不设: 不设 the wider style's shows (the property taken out), 无 none over it
   * (`none`), 有 its fields: [p] of [made].sel, [text] its text as drawn, [made].fresh what 有 writes first.
   */
  function onOff(api, p, text, made) {
    const { select } = panels.ui;
    const kind = text == null ? '' : text === 'none' ? 'none' : 'set';
    return select([['', '不设（用更宽的样式）'], ['none', '无'], ['set', '有']], kind, (k) => {
      if (k === '') api.apply((x) => ops.set(x, made.sel, p, null));
      else if (k === 'none') api.apply((x) => ops.set(x, made.sel, p, 'none'));
      else api.apply((x) => ops.set(x, made.sel, p, made.fresh));
    });
  }

  function render(el, api) {
    const { h, row, select, number, color, clear } = panels.ui;
    const rerender = () => api.rerender();
    const sel = chooser(el, api, rerender);
    if (!sel) return;
    const xml = api.doc().xml;
    const val = (p) => F.valueOf(xml, sel, p);
    const now = (p) => F.valueOf(api.doc().xml, sel, p);
    const set = (p) => (v) => api.apply((x) => ops.set(x, sel, p, v));
    el.append(fillForm(api, sel, val('fill')));
    panels.ui.propRows(el, api, F, sel, [{ prop: 'radius', label: '圆角', kind: 'radius' }], true);

    const borderText = val('border');
    const border = F.parseBorder(borderText);
    const putBorder = (patch) => { const b = F.parseBorder(now('border')) || { width: 1, color: '#33000000' }; set('border')(Object.assign({}, b, patch)); };
    el.append(row('描边', onOff(api, 'border', borderText, { sel, fresh: { width: 1, color: '#33000000' } }),
      border ? number(border.width, (w) => (w ? putBorder({ width: w }) : set('border')('none')), { min: 0, max: LIM.borderDp, step: 0.5, unit: 'dp' }) : null,
      border ? color(border.color, (c) => (c ? putBorder({ color: c }) : set('border')('none')), F) : null));

    for (const [p, label] of [['shadow', '阴影'], ['inner-shadow', '内阴影']]) {
      const text = val(p);
      const s = F.parseShadow(text);
      const fresh = { dx: 0, dy: 1, blur: 2, color: '#33000000' };
      const put = (patch) => set(p)(Object.assign({}, fresh, F.parseShadow(now(p)), patch));
      const o = LIM.offsetDp;
      el.append(row(label, onOff(api, p, text, { sel, fresh }),
        s ? number(s.dy, (v) => put({ dy: v ?? 0 }), { min: -o, max: o, step: 0.5, unit: 'dp 偏移' }) : null,
        s ? number(s.blur, (v) => put({ blur: v ?? 0 }), { min: 0, max: o, step: 0.5, unit: 'dp 模糊' }) : null,
        s ? color(s.color, (c) => (c ? put({ color: c }) : set(p)('none')), F) : null));
    }
    const depth = val('depth');
    el.append(row('厚度', number(depth ? parseFloat(depth) : null, set('depth'), { min: 0, max: LIM.depthDp, step: 0.5, unit: 'dp' }), h('span', { className: 'muted', textContent: '颜色 ' }), color(val('depth-color'), set('depth-color'), F)));

    panels.ui.propRows(el, api, F, sel, [{ prop: 'inset', label: '内缩', kind: 'inset' }], true);
    el.append(row('形状', select([['', '不设'], ['rect', '方块'], ['circle', '圆'], ['bar', '条']], val('shape') || '', (v) => set('shape')(v || null))));
    el.append(row('按下效果', select([['', '不设'], ['tint', '变色'], ['ripple', '涟漪'], ['face', '换面']], val('press') || '', (v) => set('press')(v || null))));
    el.append(row('按下时盖的颜色', color(val('overlay'), set('overlay'), F), clear(set('overlay'))));
    sourcesTable(el, api, '');
  }

  panels.push({ id: 'keys', label: '按键', ops, render, chooser, sourcesTable });
})(typeof window !== 'undefined' ? window : globalThis);
