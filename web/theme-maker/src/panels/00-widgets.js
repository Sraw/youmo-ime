// youmo-panels: the editing's tabs (060-render-p3 §5.1, panels 1–11), one file each under src/panels/, put in
// the page in their files' order (tools/assemble.mjs). Each pushes `{id, label, ops, render(el, api)}` onto
// YoumoPanels: ops are what it does to a document (`{xml, files, layouts}` in, a new one out; node tests them),
// render draws its form into el from api's document and environment, again after every change. This file: the
// small form controls they share (none of them in node: no document there).
(function (root) {
  'use strict';
  const panels = root.YoumoPanels || (root.YoumoPanels = []);

  /** A document like [d] with [xml] and [files] (d's own where none). */
  panels.next = (d, xml, files = d.files) => ({ xml, files, layouts: d.layouts });

  /** [d]'s files, a copy to change (own keys only: a file named __proto__ stays a file). */
  panels.filesOf = (d) => Object.assign(Object.create(null), d.files);

  /** [files] without [names]: a copy. */
  panels.without = (files, names) => {
    const out = Object.create(null);
    for (const k of Object.keys(files)) if (!names.includes(k)) out[k] = files[k];
    return out;
  };

  /**
   * A form's value as the text theme.xml has for a property of [kind] (panels 5–9, the widgets' rows): null where it
   * is not set. `dp`/`percent` a number kept to [o].min..[o].max, `signed` too; `flag` true or false; `radius` one
   * corner or four, `inset` four sides (F's), `twin` portrait and landscape (`[p, l]`, [o].unit); `ms` a duration.
   * Every other kind (a colour, a word) is its text as it is.
   */
  panels.text = (F, kind, v, o = {}) => {
    if (v == null || v === '') return null;
    const n = (x) => F.clamp(x, o.min ?? 0, o.max ?? 9999, o.digits ?? 1);
    switch (kind) {
      case 'dp': return n(v) + 'dp';
      case 'percent': return n(v) + '%';
      case 'ms': return n(v) + 'ms';
      case 'flag': return v ? 'true' : 'false';
      case 'radius': return F.radiusText(Array.isArray(v) ? v : [v, v, v, v]);
      case 'inset': return F.insetText(v, o.max);
      case 'twin': return v[0] === v[1] ? n(v[0]) + o.unit : n(v[0]) + o.unit + ' ' + n(v[1]) + o.unit;
      case 'border': return F.borderText(v);
      case 'shadow': return F.shadowText(v);
      default: return String(v);
    }
  };

  /** [d] with [select]'s [prop] [v] (a form's value of [kind]; null: not set). */
  panels.setProp = (d, F, select, prop, kind, v, o) => panels.next(d, F.setProp(d.xml, select, prop, panels.text(F, kind, v, o)));

  // [base] (a picked file's name) made a theme file's: `<prefix><base cleaned>.<type>`, a -2, -3 … where another file
  // has that name already (never overwritten: another style may name it)
  panels.fileName = (files, prefix, base, type) => {
    let name = base.toLowerCase().replace(/\.[^.]*$/, '').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'image';
    for (let i = 2; (prefix + name + '.' + type) in files; i++) name = name.replace(/-\d+$/, '') + '-' + i;
    return prefix + name + '.' + type;
  };

  /**
   * A tab of widgets' styles (栏, 弹出, 面板和窗口): [def] `{id, label, parts: [{id, title, select, props, note?}],
   * scenes?}` (props as ui.propRows takes them). The page chooses a part and a scene (`@…`, [def].scenes of F.SCENES:
   * where the widget may differ), and edits the style of exactly that select=. Its ops (node tests them): `set(d,
   * select, prop, kind, v, o)`, `selector(part, scene)`, the parts.
   */
  panels.widgetTab = (def) => {
    const F = root.YoumoForm;
    const chosen = { part: def.parts[0].id, scene: '' };
    const selector = (part, scene) => (scene ? '@' + scene + ' ' : '') + part.select;
    const ops = { set: (d, select, prop, kind, v, o) => panels.setProp(d, F, select, prop, kind, v, o), selector, parts: def.parts };
    const scenes = F.SCENES.filter(([v]) => !def.scenes || v === '' || def.scenes.includes(v));
    function render(el, api) {
      const { h, row, select, propRows } = panels.ui;
      const part = def.parts.find((x) => x.id === chosen.part) || def.parts[0];
      el.append(row('部件', select(def.parts.map((x) => [x.id, x.title]), part.id, (v) => { chosen.part = v; api.rerender(); })));
      if (!part.fixed) el.append(row('场景', select(scenes, chosen.scene, (v) => { chosen.scene = v; api.rerender(); })));
      const sel = part.fixed ? part.select : selector(part, chosen.scene);
      el.append(h('p', { className: 'selector' }, '这些改动写成 ', h('code', { textContent: sel })));
      if (part.note) el.append(h('p', { className: 'muted', textContent: part.note }));
      propRows(el, api, F, sel, part.props);
      if (def.after) def.after(el, api, part);
    }
    panels.push({ id: def.id, label: def.label, ops, render });
    return ops;
  };

  if (typeof document === 'undefined') return;

  const h = (tag, props = {}, ...kids) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(props)) {
      if (k === 'on') for (const [ev, f] of Object.entries(v)) e.addEventListener(ev, f);
      else if (k in e && k !== 'list') e[k] = v;
      else e.setAttribute(k, v);
    }
    e.append(...kids.flat().filter((x) => x != null));
    return e;
  };

  /** A labelled row. */
  const row = (label, ...controls) => h('label', { className: 'row' }, h('span', { className: 'what' }, label), ...controls);

  /** A select of [[value, text]], [value] chosen, [change] its value. */
  const select = (options, value, change) => {
    const s = h('select', { on: { change: () => change(s.value) } }, options.map(([v, t]) => h('option', { value: v, textContent: t })));
    s.value = value;
    return s;
  };

  /**
   * A number field ([unit] after it); empty means not set (null). What is typed past [min]..[max] is taken as
   * the bound (min= and max= do not stop typing); what is not a number (`1.2.3`, `-`) is not taken at all.
   */
  const number = (value, change, { min, max, step = 1, unit = '' } = {}) => {
    const i = h('input', { type: 'number', value: value ?? '', on: { change: () => {
      if (i.validity.badInput) return;
      if (i.value === '') { change(null); return; }
      let v = +i.value;
      if (min != null && v < min) v = min;
      if (max != null && v > max) v = max;
      if (v !== +i.value) i.value = v;
      change(v);
    } } });
    if (min != null) i.min = min;
    if (max != null) i.max = max;
    i.step = step;
    return h('span', { className: 'num' }, i, unit ? ' ' + unit : '');
  };

  /**
   * A colour: a picker and its opacity for a literal colour, a text field for anything (`@key`,
   * `darker(@key, 8%)`, `system(primary, #…)`); [change] the text, null where it was emptied.
   */
  const color = (value, change, F) => {
    const hex = F.parseHex(value);
    const text = h('input', { type: 'text', className: 'expr', value: value ?? '', placeholder: '#rrggbb、@名字、darker(@key, 8%)…' });
    const pick = h('input', { type: 'color', value: hex ? hex.rgb : '#000000' });
    const alpha = h('input', { type: 'range', min: 0, max: 100, value: hex ? hex.opacity : 100, title: '不透明度' });
    // an expression (`@key`, `darker(@key, 8%)`) is no colour the picker could show: touching it would write black
    if (value != null && !hex) {
      pick.disabled = true;
      alpha.disabled = true;
      pick.title = '这是表达式，在右边的文字框里改';
    }
    const fromPicker = () => change(F.hexOf(pick.value, +alpha.value));
    pick.addEventListener('change', fromPicker);
    alpha.addEventListener('change', fromPicker);
    text.addEventListener('change', () => change(text.value.trim() === '' ? null : text.value.trim()));
    return h('span', { className: 'color' }, pick, alpha, text);
  };

  const clear = (change) => h('button', { type: 'button', className: 'clear', textContent: '×', title: '不设（用别的样式的）', on: { click: () => change(null) } });

  /**
   * The rows of [props] for the style of exactly [sel] (`[{prop, label, kind, …}]`, kinds as panels.text has them,
   * `choice` with [options], `flag`), each control writing when used and reading the document then (a tab is left
   * as it is while a field of it is typed in: ui.js refreshTab). Under them what the engine warns of those styles
   * (a property the widget does not read, in that state): the theme's own warnings of their lines.
   */
  const propRows = (el, api, F, sel, props, quiet = false) => {
    const d = api.doc();
    const val = (p) => F.valueOf(d.xml, sel, p);
    const now = (p) => F.valueOf(api.doc().xml, sel, p);
    const put = (p, kind, o) => (v) => api.apply((x) => panels.setProp(x, F, sel, p.prop, kind, v, o));
    for (const p of props) {
      const v = val(p.prop);
      const o = p;
      let controls;
      if (p.kind === 'color') controls = [color(v, put(p, 'color'), F)];
      else if (p.kind === 'dp' || p.kind === 'percent' || p.kind === 'ms') {
        const unit = { dp: 'dp', percent: '%', ms: 'ms' }[p.kind];
        controls = [number(v == null ? null : parseFloat(v), put(p, p.kind, o), { min: p.min ?? 0, max: p.max, step: p.step ?? 1, unit }), v != null && !Number.isFinite(parseFloat(v)) ? h('code', { textContent: v }) : null];
      } else if (p.kind === 'choice') controls = [select([['', '不设']].concat(p.options), v ?? '', (x) => put(p, 'choice')(x || null))];
      else if (p.kind === 'flag') controls = [select([['', '不设'], ['true', '是'], ['false', '否']], v ?? '', (x) => put(p, 'choice')(x || null))];
      else if (p.kind === 'radius') {
        const r = F.parseRadius(v);
        const one = r && r.every((c) => c === r[0]);
        // one corner while they are alike, the four (top left, top right, bottom right, bottom left) once they are not
        const corner = (k) => (x) => {
          if (x == null) { put(p, 'radius')(null); return; }
          const was = F.parseRadius(now(p.prop)) || [0, 0, 0, 0];
          was[k] = x;
          put(p, 'radius')(was);
        };
        const opts = { min: 0, max: F.LIMITS.lengthDp, step: 0.5 };
        controls = r && !one ? ['左上', '右上', '右下', '左下'].map((c, k) => number(r[k], corner(k), Object.assign({ unit: 'dp ' + c }, opts)))
          : [number(one ? r[0] : null, put(p, 'radius'), Object.assign({ unit: 'dp' }, opts)), v != null && !r ? h('code', { textContent: v }) : null];
      } else if (p.kind === 'inset') {
        const i = F.parseInset(v);
        controls = ['上', '右', '下', '左'].map((side, k) => number(i ? i[k] : null, (x) => {
          if (x == null) { put(p, 'inset')(null); return; }
          const was = F.parseInset(now(p.prop)) || [0, 0, 0, 0];
          was[k] = x;
          put(p, 'inset', o)(was);
        }, { min: 0, max: p.max ?? F.LIMITS.lengthDp, step: 0.5, unit: 'dp ' + side }));
      } else if (p.kind === 'twin') {
        const parts = (v || '').trim().split(/\s+/).map(parseFloat);
        const tw = v ? [parts[0], parts[parts.length - 1]] : null;
        controls = ['竖', '横'].map((side, k) => number(tw ? tw[k] : null, (x) => {
          if (x == null) { put(p, 'twin', o)(null); return; }
          const t = (now(p.prop) || '').trim().split(/\s+/).map(parseFloat);
          const was = t.length && Number.isFinite(t[0]) ? [t[0], t[t.length - 1]] : [x, x];
          was[k] = x;
          put(p, 'twin', o)(was);
        }, { min: p.min ?? 0, max: p.max, step: p.step ?? 1, unit: p.unit + ' ' + side }));
      } else if (p.kind === 'border') {
        const b = F.parseBorder(v);
        const putB = (patch) => put(p, 'border')(Object.assign({ width: 1, color: '#33000000' }, F.parseBorder(now(p.prop)), patch));
        controls = [number(b ? b.width : null, (w) => (w ? putB({ width: w }) : put(p, 'choice')(w === 0 ? 'none' : null)), { min: 0, max: F.LIMITS.borderDp, step: 0.5, unit: 'dp' }),
          b ? color(b.color, (c) => (c ? putB({ color: c }) : put(p, 'choice')(null)), F) : null];
      } else if (p.kind === 'shadow') {
        const sh = F.parseShadow(v);
        const putS = (patch) => put(p, 'shadow')(Object.assign({ dx: 0, dy: 1, blur: 2, color: '#33000000' }, F.parseShadow(now(p.prop)), patch));
        const lim = F.LIMITS.offsetDp;
        controls = [number(sh ? sh.dy : null, (x) => (x == null ? put(p, 'choice')(null) : putS({ dy: x })), { min: -lim, max: lim, step: 0.5, unit: 'dp 偏移' }),
          number(sh ? sh.blur : null, (x) => putS({ blur: x ?? 0 }), { min: 0, max: lim, step: 0.5, unit: 'dp 模糊' }),
          sh ? color(sh.color, (c) => (c ? putS({ color: c }) : put(p, 'choice')(null)), F) : null];
      } else controls = [h('input', { type: 'text', value: v ?? '', on: { change: (e) => put(p, 'text')(e.target.value.trim() || null) } })];
      el.append(row(p.label, ...controls, p.note ? h('span', { className: 'muted', textContent: p.note }) : null));
    }
    if (!quiet) warnings(el, api, F, sel);
  };

  /** What the engine warns of the styles of exactly [sel] (their lines), one line each; nothing where none. */
  const warnings = (el, api, F, sel) => {
    const lines = new Set(F.styles(api.doc().xml).filter((s) => F.sameSelect(s.select, sel)).map((s) => s.line));
    const said = api.problems().filter((p) => lines.has(p.line));
    if (!said.length) return;
    const ul = h('ul', { className: 'warnings' });
    for (const p of said) ul.append(h('li', { className: p.level, textContent: '第 ' + p.line + ' 行：' + p.message }));
    el.append(ul);
  };

  panels.ui = { h, row, select, number, color, clear, propRows, warnings };
})(typeof window !== 'undefined' ? window : globalThis);
