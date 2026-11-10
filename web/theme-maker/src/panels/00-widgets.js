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

  /** [files] without [names]: a copy. */
  panels.without = (files, names) => {
    const out = Object.create(null);
    for (const k of Object.keys(files)) if (!names.includes(k)) out[k] = files[k];
    return out;
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

  /** A number field ([unit] after it); empty means not set (null). */
  const number = (value, change, { min, max, step = 1, unit = '' } = {}) => {
    const i = h('input', { type: 'number', value: value ?? '', on: { change: () => change(i.value === '' ? null : +i.value) } });
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
    const fromPicker = () => change(F.hexOf(pick.value, +alpha.value));
    pick.addEventListener('change', fromPicker);
    alpha.addEventListener('change', fromPicker);
    text.addEventListener('change', () => change(text.value.trim() === '' ? null : text.value.trim()));
    return h('span', { className: 'color' }, pick, alpha, text);
  };

  const clear = (change) => h('button', { type: 'button', className: 'clear', textContent: '×', title: '不设（用别的样式的）', on: { click: () => change(null) } });

  panels.ui = { h, row, select, number, color, clear };
})(typeof window !== 'undefined' ? window : globalThis);
