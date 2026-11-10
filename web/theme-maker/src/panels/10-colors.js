// 配色 (060-render-p3 §5.1 panel 1): the named colours by group and the theme's own, a picker or an expression
// each; the dark version's switch; colours worked out of the background picture.
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const F = root.YoumoForm;

  // the version a colour is written to: the dark one where the theme has it and the preview shows it
  const version = (xml, dark) => (dark && F.variants(xml).includes('dark') ? 'dark' : 'light');

  /**
   * Colours for keys over a picture whose pixels are [samples] (`[[r, g, b]]`): see-through keys of the
   * picture's lightness, text that reads on it, the accent its most vivid colour. `{name: "#…"}`.
   */
  function fromPicture(samples) {
    if (!samples.length) return {};
    const lum = ([r, g, b]) => (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
    const mean = samples.reduce((s, p) => s + lum(p), 0) / samples.length;
    const hex = ([r, g, b]) => '#' + [r, g, b].map((v) => Math.round(v).toString(16).padStart(2, '0')).join('');
    // the most vivid: saturation times value, as HSV has them
    const vivid = samples.reduce((best, p) => {
      const max = Math.max(...p);
      const sat = max ? (max - Math.min(...p)) / max : 0;
      const score = sat * max;
      return score > best[0] ? [score, p] : best;
    }, [-1, samples[0]])[1];
    const light = mean > 0.5;
    const ink = light ? '#202124' : '#f1f3f4';
    const accentInk = lum(vivid) > 0.55 ? '#202124' : '#ffffff';
    return {
      key: light ? '#66ffffff' : '#4d000000',
      altKey: light ? '#40ffffff' : '#66000000',
      spaceBar: light ? '#66ffffff' : '#4d000000',
      accentKey: hex(vivid),
      keyText: ink,
      altKeyText: ink,
      accentKeyText: accentInk,
      keyHint: light ? '#99202124' : '#99f1f3f4',
      keyShadow: '#00000000',
      keyPressHighlight: light ? '#1f000000' : '#33ffffff',
    };
  }

  const ops = {
    /** [d] with colour [name] [value] (null: taken out) in the version [dark] shows. */
    set: (d, name, value, dark) => panels.next(d, F.setColor(d.xml, name, value, version(d.xml, dark))),
    /** [d] with its dark version on or off. */
    dark: (d, on) => panels.next(d, F.withDark(d.xml, on)),
    /** [d] with [colors] (fromPicture's) written in the version [dark] shows. */
    many: (d, colors, dark) => {
      let xml = d.xml;
      for (const [n, v] of Object.entries(colors)) xml = F.setColor(xml, n, v, version(xml, dark));
      return panels.next(d, xml);
    },
    fromPicture,
  };

  function render(el, api) {
    const { h, row, color } = panels.ui;
    const d = api.doc();
    const which = version(d.xml, api.env().dark);
    const own = F.colors(d.xml, which);
    const hasDark = F.variants(d.xml).includes('dark');
    const darkBox = h('input', { type: 'checkbox', checked: hasDark, on: { change: () => api.apply((x) => ops.dark(x, darkBox.checked)) } });
    el.append(
      h('p', { className: 'muted' }, '写的是' + (which === 'dark' ? '深色' : '浅色') + '版的颜色' + (hasDark ? '（顶栏切浅/深）' : '') + '。可以用取色器，也可以写表达式：@key、darker(@key, 8%)、system(primary, #6750a4)。'),
      row('这个主题有深色版', darkBox),
    );
    const pictureButton = h('button', { type: 'button', textContent: '从背景图取色', disabled: !F.picture(d.xml), on: { click: async () => {
      const samples = await api.pictureSamples();
      if (!samples || !samples.length) { api.toast('背景图还没读进来'); return; }
      api.apply((x) => ops.many(x, fromPicture(samples), api.env().dark));
      api.toast('已按背景图重算按键和文字的颜色（可撤销）');
    } } });
    el.append(h('p', {}, pictureButton));
    const named = new Set();
    for (const [group, names] of F.COLOR_GROUPS) {
      el.append(h('h3', { textContent: group }));
      for (const n of names) {
        named.add(n);
        el.append(row(n, color(own[n] ?? null, (v) => api.apply((x) => ops.set(x, n, v, api.env().dark)), F)));
      }
    }
    const extra = Object.keys(own).filter((n) => !named.has(n));
    el.append(h('h3', { textContent: '自己加的' }));
    for (const n of extra) el.append(row(n, color(own[n], (v) => api.apply((x) => ops.set(x, n, v, api.env().dark)), F)));
    const name = h('input', { type: 'text', placeholder: '名字（字母开头）' });
    el.append(row('加一个', name, h('button', { type: 'button', textContent: '加', on: { click: () => {
      const n = name.value.trim();
      if (!/^[A-Za-z][A-Za-z0-9_-]*$/.test(n)) { api.toast('名字要字母开头，只用字母、数字、- 和 _'); return; }
      api.apply((x) => ops.set(x, n, '#808080', api.env().dark));
    } } })));
  }

  panels.push({ id: 'colors', label: '配色', ops, render });
})(typeof window !== 'undefined' ? window : globalThis);
