// 配色 (060-render-p3 §5.1 panel 1): the named colours by group and the theme's own, a picker or an expression
// each; the dark version's switch; colours worked out of the background picture.
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const F = root.YoumoForm;

  // the version a colour is written to: the dark one where the theme has it and the preview shows it
  const version = (xml, dark) => (dark && F.variants(xml).includes('dark') ? 'dark' : 'light');

  const lum = ([r, g, b]) => (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
  // WCAG's contrast of two colours' relative luminance (sRGB made linear)
  const linear = (c) => { const v = c / 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
  const rel = ([r, g, b]) => 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
  const contrast = (x, y) => { const [hi, lo] = [rel(x), rel(y)].sort((m, n) => n - m); return (hi + 0.05) / (lo + 0.05); };
  // [over] (`[r, g, b, a]`, a 0–1) laid on [under]
  const over = ([r, g, b, a], u) => [r * a + u[0] * (1 - a), g * a + u[1] * (1 - a), b * a + u[2] * (1 - a)];
  const DARK_INK = [32, 33, 36];
  const LIGHT_INK = [241, 243, 244];

  /**
   * Colours for keys over a picture whose pixels are [samples] (`[[r, g, b]]`), for its light or [dark] version:
   * see-through keys (white veils light, black veils dark), the text whichever of the two inks reads better on a key
   * as laid over the picture's middle, the accent the mean of its most vivid tenth (one noisy pixel is never it),
   * its text the ink that reads on it. `{name: "#…"}`.
   */
  function fromPicture(samples, dark = null) {
    if (!samples.length) return {};
    const sorted = samples.map(lum).sort((x, y) => x - y);
    const median = sorted[Math.floor(sorted.length / 2)];
    const light = dark == null ? median > 0.5 : !dark;
    const mean = [0, 1, 2].map((i) => samples.reduce((t, px) => t + px[i], 0) / samples.length);
    const hex = ([r, g, b]) => '#' + [r, g, b].map((v) => Math.round(v).toString(16).padStart(2, '0')).join('');
    const score = (px) => { const max = Math.max(...px); return max ? ((max - Math.min(...px)) / max) * max : 0; };
    const vivid = [...samples].sort((x, y) => score(y) - score(x)).slice(0, Math.max(1, Math.ceil(samples.length / 10)));
    const accent = [0, 1, 2].map((i) => vivid.reduce((t, px) => t + px[i], 0) / vivid.length);
    const veil = light ? [255, 255, 255, 0.4] : [0, 0, 0, 0.3];
    const keyOn = over(veil, mean);
    const ink = contrast(DARK_INK, keyOn) >= contrast(LIGHT_INK, keyOn) ? DARK_INK : LIGHT_INK;
    const inkHex = hex(ink);
    const accentInk = contrast(DARK_INK, accent) >= contrast([255, 255, 255], accent) ? '#202124' : '#ffffff';
    return {
      key: light ? '#66ffffff' : '#4d000000',
      altKey: light ? '#40ffffff' : '#66000000',
      spaceBar: light ? '#66ffffff' : '#4d000000',
      accentKey: hex(accent),
      keyText: inkHex,
      altKeyText: inkHex,
      accentKeyText: accentInk,
      keyHint: '#99' + inkHex.slice(1),
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
    /** [d] with fromPicture's colours of [samples] in each version it has, each worked out for its own (light, dark; a theme of one version: [dark], null as the picture is). */
    fromPictureAll: (d, samples, dark = null) => {
      let xml = d.xml;
      const vs = F.variants(xml);
      for (const v of vs) for (const [n, c] of Object.entries(fromPicture(samples, vs.length > 1 ? v === 'dark' : dark))) xml = F.setColor(xml, n, c, v);
      return panels.next(d, xml);
    },
    fromPicture, contrast,
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
      api.apply((x) => ops.fromPictureAll(x, samples));
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
      // a name the theme has already: set it above, not reset to grey here
      if (n in own || named.has(n)) { api.toast('已经有「' + n + '」了，在上面改它'); return; }
      api.apply((x) => ops.set(x, n, '#808080', api.env().dark));
    } } })));
  }

  panels.push({ id: 'colors', label: '配色', ops, render });
})(typeof window !== 'undefined' ? window : globalThis);
