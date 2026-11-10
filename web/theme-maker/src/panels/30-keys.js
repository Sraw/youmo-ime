// 按键 (060-render-p3 §5.1 panel 3): a range of keys (all, letters, function, space, Return, the picked key, the
// board's every key), a scene, a state, then the face's properties; the line on top says which select= the
// changes go to, the cascade check under it where each of the picked key's values comes from (the engine's own answer).
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const F = root.YoumoForm;

  // the properties' texts as theme.xml has them (060-render-v2 §5, ThemeReader)
  const TEXT = {
    radius: (v) => v + 'dp',
    depth: (v) => v + 'dp',
    border: (v) => F.borderText(v),
    shadow: (v) => F.shadowText(v),
    'inner-shadow': (v) => F.shadowText(v),
    inset: ([y, x]) => y + 'dp ' + x + 'dp',
  };

  const NONE = new Set(['border', 'shadow', 'inner-shadow']);

  const ops = {
    /** [d] with [select]'s fill [fill] (F.fillText's; null: none). */
    fill: (d, select, fill) => panels.next(d, F.setProp(d.xml, select, 'fill', fill ? F.fillText(fill) : null)),
    /** [d] with [select]'s [prop] [v] (a form's value; null: not set). */
    // a border or a shadow taken off is `none` (it overrides a wider style's), anything else not set
    set: (d, select, prop, v) => panels.next(d, F.setProp(d.xml, select, prop, v == null ? (NONE.has(prop) ? 'none' : null) : TEXT[prop] ? TEXT[prop](v) : String(v))),
    /**
     * The cascade check: where key [key] of [board] gets each property in [env] ([part], [pressed] as
     * E.sources takes them): `[{property, value, select, line}]`, the value as the winning style writes it.
     */
    sources(E, F, d, theme, board, env, key, part, pressed) {
      const r = E.sources(board, theme, env, key, part, pressed);
      if (!r.ok) return [];
      const byLine = new Map(F.styles(d.xml).map((s) => [s.line, s]));
      return r.sources.map((s) => {
        const style = byLine.get(s.line);
        const a = style && style.attrs.find((x) => x[0] === s.property);
        return { property: s.property, value: a ? a[1] : null, select: style ? style.select : null, line: s.line };
      });
    },
  };

  // what the page chose, kept while the page is open
  const chosen = { range: 'all', scene: '', state: '' };

  function selectorOf(api) {
    const picked = api.picked();
    return F.selector(Object.assign({}, chosen, { board: picked && picked.board, key: picked && picked.key }));
  }

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

  function sourcesTable(el, api, part) {
    const { h } = panels.ui;
    const picked = api.picked();
    const theme = api.theme();
    if (!picked || !theme) {
      el.append(h('p', { className: 'muted', textContent: '在预览上点一个键，这里列出它每个值来自哪条样式。' }));
      return;
    }
    const rows = ops.sources(api.engine, F, api.doc(), theme, picked.board, api.envJson(), picked.key, part || null, chosen.state === 'pressed');
    el.append(h('h3', { textContent: '「' + picked.key + '」' + (part ? ' ' + part : '') + (chosen.state === 'pressed' ? '（按下）' : '') + '实际生效的值' }));
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
    const out = h('div', { className: 'group' });
    const set = (fill) => api.apply((x) => ops.fill(x, sel, fill));
    const kinds = [['none', '不设'], ['solid', '纯色'], ['linear', '渐变'], ['picture', '透出背景图']];
    if (f.kind === 'other') kinds.push(['other', '图片（在源码里改）']);
    out.append(row('填充', select(kinds, f.kind, (k) => {
      if (k === 'none') set(null);
      else if (k === 'solid') set({ kind: 'solid', color: f.kind === 'solid' ? f.color : '@key' });
      else if (k === 'linear') set({ kind: 'linear', angle: 90, stops: [[f.kind === 'solid' ? f.color : '#ffffff', 0], ['#c0c0c0', 100]] });
      else if (k === 'picture') set({ kind: 'picture', blur: 0, brightness: 100, over: '#66ffffff' });
    })));
    if (f.kind === 'solid') out.append(row('颜色', color(f.color, (c) => set(c ? { kind: 'solid', color: c } : null), F)));
    if (f.kind === 'linear') {
      out.append(row('角度', number(f.angle, (a) => set(Object.assign({}, f, { angle: a ?? 90 })), { min: -360, max: 360, unit: '°' })));
      f.stops.forEach(([c, at], i) => {
        const stops = (fn) => set(Object.assign({}, f, { stops: f.stops.map((s, j) => (j === i ? fn(s) : s)) }));
        out.append(row('颜色 ' + (i + 1), color(c, (v) => stops(([, p]) => [v || '#000000', p]), F), number(at, (v) => stops(([cc]) => [cc, v ?? 0]), { min: 0, max: 100, unit: '%' }),
          f.stops.length > 2 ? clear(() => set(Object.assign({}, f, { stops: f.stops.filter((s, j) => j !== i) }))) : null));
      });
      out.append(h('button', { type: 'button', textContent: '加一个颜色', on: { click: () => set(Object.assign({}, f, { stops: f.stops.concat([['#808080', 100]]) })) } }));
    }
    if (f.kind === 'picture') {
      out.append(
        row('模糊', number(f.blur, (v) => set(Object.assign({}, f, { blur: v ?? 0 })), { min: 0, max: 25, unit: 'dp' })),
        row('亮度', number(f.brightness, (v) => set(Object.assign({}, f, { brightness: v ?? 100 })), { min: 0, max: 100, unit: '%' })),
        row('盖一层颜色', color(f.over, (v) => set(Object.assign({}, f, { over: v })), F)),
      );
    }
    return out;
  }

  function render(el, api) {
    const { h, row, select, number, color, clear } = panels.ui;
    const rerender = () => api.rerender();
    const sel = chooser(el, api, rerender);
    if (!sel) return;
    const xml = api.doc().xml;
    const val = (p) => F.valueOf(xml, sel, p);
    const set = (p) => (v) => api.apply((x) => ops.set(x, sel, p, v));
    el.append(fillForm(api, sel, val('fill')));
    const radius = val('radius');
    el.append(row('圆角', number(radius && /dp$/.test(radius) ? parseFloat(radius) : null, set('radius'), { min: 0, max: 64, unit: 'dp' }), radius && !/^[\d.]+dp$/.test(radius) ? h('code', { textContent: radius }) : null));
    const border = F.parseBorder(val('border'));
    el.append(row('描边', number(border ? border.width : null, (w) => set('border')(w ? { width: w, color: border ? border.color : '#33000000' } : null), { min: 0, max: 8, step: 0.5, unit: 'dp' }),
      border ? color(border.color, (c) => set('border')(c ? { width: border.width, color: c } : null), F) : null));
    for (const [p, label] of [['shadow', '阴影'], ['inner-shadow', '内阴影']]) {
      const s = F.parseShadow(val(p));
      const put = (patch) => set(p)(Object.assign({ dx: 0, dy: 1, blur: 2, color: '#33000000' }, s, patch));
      el.append(row(label, number(s ? s.dy : null, (v) => (v == null ? set(p)(null) : put({ dy: v })), { min: -16, max: 16, step: 0.5, unit: 'dp 偏移' }),
        number(s ? s.blur : null, (v) => put({ blur: v ?? 0 }), { min: 0, max: 24, step: 0.5, unit: 'dp 模糊' }),
        s ? color(s.color, (c) => (c ? put({ color: c }) : set(p)(null)), F) : null));
    }
    const depth = val('depth');
    el.append(row('厚度', number(depth ? parseFloat(depth) : null, set('depth'), { min: 0, max: 8, step: 0.5, unit: 'dp' }), h('span', { className: 'muted', textContent: '颜色 ' }), color(val('depth-color'), set('depth-color'), F)));
    const inset = (val('inset') || '').split(/\s+/).map(parseFloat);
    const insetOk = inset.length === 2 && inset.every(Number.isFinite);
    el.append(row('内缩', number(insetOk ? inset[0] : null, (v) => set('inset')(v == null ? null : [v, insetOk ? inset[1] : 3]), { min: 0, max: 24, step: 0.5, unit: 'dp 上下' }),
      number(insetOk ? inset[1] : null, (v) => set('inset')(v == null ? null : [insetOk ? inset[0] : 7, v]), { min: 0, max: 24, step: 0.5, unit: 'dp 左右' })));
    el.append(row('形状', select([['', '不设'], ['rect', '方块'], ['circle', '圆'], ['bar', '条']], val('shape') || '', (v) => set('shape')(v || null))));
    el.append(row('按下效果', select([['', '不设'], ['tint', '变色'], ['ripple', '涟漪'], ['face', '换面']], val('press') || '', (v) => set('press')(v || null))));
    el.append(row('按下时盖的颜色', color(val('overlay'), set('overlay'), F), clear(set('overlay'))));
    sourcesTable(el, api, '');
  }

  panels.push({ id: 'keys', label: '按键', ops, render, chooser, sourcesTable });
})(typeof window !== 'undefined' ? window : globalThis);
