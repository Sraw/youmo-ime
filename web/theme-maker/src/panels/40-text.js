// 字和图标 (060-render-p3 §5.1 panel 4): the main word, the mark, the shortcut, the root apart (a key's parts),
// each its colour, font (the theme's own, at most 4 uploaded), weight, size, place, spacing, outline and shadow;
// the app's icons replaced by name (PNG or SVG).
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const F = root.YoumoForm;
  const MAX_FONTS = 4;
  const PARTS = [['', '整个键（各部分都跟着）'], ['label', '主字'], ['mark', '角标'], ['shortcut', '快捷图标'], ['root', '字根'], ['icon', '图标']];
  /** The app's icons a theme may replace (theme-4.xsd's iconName). */
  const ICONS = ['backspace', 'shift', 'shift-locked', 'enter', 'space', 'lang', 'voice', 'emoji', 'symbols', 'clipboard', 'settings'];

  const TEXT = {
    scale: (v) => v + '%',
    'letter-spacing': (v) => v + 'em',
    stroke: (v) => F.borderText(v),
    'text-shadow': (v) => F.shadowText(v),
  };

  const ops = {
    /** [d] with [select]'s [prop] [v] (a form's value; null: not set). */
    set: (d, select, prop, v) => panels.next(d, F.setProp(d.xml, select, prop, v == null ? null : TEXT[prop] ? TEXT[prop](v) : String(v))),

    /** [d] with font file [file] ([bytes]) as `<font name="…">`, its name the file's without its type. At most 4. */
    addFont(d, file, bytes) {
      const fonts = F.elements(d.xml, 'font');
      if (fonts.length >= MAX_FONTS) throw new Error('最多 ' + MAX_FONTS + ' 个字体');
      const type = (/\.(ttf|otf)$/i.exec(file) || [, 'ttf'])[1].toLowerCase();
      let name = file.replace(/\.[^.]*$/, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'font';
      if (!/^[a-z]/.test(name)) name = 'f-' + name;
      while (fonts.some((f) => f.attrs.some((a) => a[0] === 'name' && a[1] === name))) name += '-2';
      const files = Object.assign(Object.create(null), d.files);
      files[name + '.' + type] = bytes;
      return { xml: F.insert(d.xml, 'font', '<font name="' + name + '" src="' + name + '.' + type + '"/>'), files, layouts: d.layouts };
    },

    /** [d] with the app's icon [name] replaced by [bytes] (a [type] png or svg file); null bytes: the app's again. */
    setIcon(d, name, type, bytes) {
      const own = F.elements(d.xml, 'icon').find((e) => e.attrs.some((a) => a[0] === 'name' && a[1] === name));
      let xml = d.xml;
      let files = Object.assign(Object.create(null), d.files);
      if (own) {
        const src = (own.attrs.find((a) => a[0] === 'src') || [])[1];
        xml = xml.slice(0, own.start).replace(/[ \t]*$/, '').replace(/\n$/, '') + xml.slice(own.end);
        if (src && !F.uncommented(xml).includes('"' + src + '"')) files = panels.without(files, [src]);
      }
      if (bytes) {
        const file = 'icon-' + name + '.' + type;
        files[file] = bytes;
        xml = F.insert(xml, 'icon', '<icon name="' + name + '" src="' + file + '"/>');
      }
      return { xml, files, layouts: d.layouts };
    },
    MAX_FONTS, ICONS,
  };

  const chosen = { part: 'label' };

  function render(el, api) {
    const { h, row, select, number, color } = panels.ui;
    const keys = panels.find((p) => p.id === 'keys');
    el.append(row('哪部分', select(PARTS, chosen.part, (v) => { chosen.part = v; api.rerender(); })));
    const sel = keys.chooser(el, api, () => api.rerender(), chosen.part);
    if (!sel) return;
    const d = api.doc();
    const val = (p) => F.valueOf(d.xml, sel, p);
    const set = (p) => (v) => api.apply((x) => ops.set(x, sel, p, v));
    el.append(row('颜色', color(val('color'), set('color'), F)));
    el.append(row('图标颜色', color(val('icon-color'), set('icon-color'), F)));
    const fonts = F.elements(d.xml, 'font').map((f) => Object.fromEntries(f.attrs).name).filter(Boolean);
    el.append(row('字体', select([['', '不设'], ['system', '系统']].concat(fonts.map((f) => [f, f])), val('font') || '', (v) => set('font')(v || null)),
      fonts.length < MAX_FONTS ? h('button', { type: 'button', textContent: '上传字体…', on: { click: async () => {
        const f = await api.pickFile('.ttf,.otf,font/ttf,font/otf');
        if (!f) return;
        try {
          api.apply((x) => ops.addFont(x, f.name, new Uint8Array(f.bytes)));
        } catch (e) {
          api.toast(e.message || String(e));
        }
      } } }) : h('span', { className: 'muted', textContent: '（最多 4 个）' })));
    el.append(row('字重', select([['', '不设'], ['300', '细'], ['400', '常规'], ['700', '粗']], val('weight') || '', (v) => set('weight')(v || null))));
    const scale = val('scale');
    el.append(row('大小', number(scale ? parseFloat(scale) : null, set('scale'), { min: 50, max: 200, step: 5, unit: '%' })));
    el.append(row('位置', select([['', '不设'], ['center', '居中'], ['top-left', '左上'], ['top-center', '上中']], val('align') || '', (v) => set('align')(v || null))));
    const ls = val('letter-spacing');
    el.append(row('字距', number(ls ? parseFloat(ls) : null, set('letter-spacing'), { min: -0.5, max: 1, step: 0.01, unit: 'em' })));
    const stroke = F.parseBorder(val('stroke'));
    el.append(row('描边', number(stroke ? stroke.width : null, (w) => set('stroke')(w ? { width: w, color: stroke ? stroke.color : '#000000' } : null), { min: 0, max: 4, step: 0.5, unit: 'dp' }),
      stroke ? color(stroke.color, (c) => set('stroke')(c ? { width: stroke.width, color: c } : null), F) : null));
    const sh = F.parseShadow(val('text-shadow'));
    const putShadow = (patch) => set('text-shadow')(Object.assign({ dx: 0, dy: 1, blur: 2, color: '#80000000' }, sh, patch));
    el.append(row('投影', number(sh ? sh.dy : null, (v) => (v == null ? set('text-shadow')(null) : putShadow({ dy: v })), { min: -8, max: 8, step: 0.5, unit: 'dp 偏移' }),
      number(sh ? sh.blur : null, (v) => putShadow({ blur: v ?? 0 }), { min: 0, max: 16, step: 0.5, unit: 'dp 模糊' }), sh ? color(sh.color, (c) => (c ? putShadow({ color: c }) : set('text-shadow')(null)), F) : null));
    const hidden = h('input', { type: 'checkbox', checked: val('hidden') === 'true', on: { change: () => set('hidden')(hidden.checked ? true : null) } });
    el.append(row('藏起来', hidden));
    keys.sourcesTable(el, api, chosen.part);

    el.append(h('h3', { textContent: '换图标（PNG 或 SVG，按名字）' }));
    const own = Object.fromEntries(F.elements(d.xml, 'icon').map((e) => Object.fromEntries(e.attrs)).map((a) => [a.name, a.src]));
    for (const name of api.iconNames ? api.iconNames() : ICONS) {
      el.append(row(name, h('span', { className: 'muted', textContent: own[name] || 'App 自带' }),
        h('button', { type: 'button', textContent: '换…', on: { click: async () => {
          const f = await api.pickFile('image/png,image/svg+xml,.svg,.png');
          if (!f) return;
          const type = /\.svg$/i.test(f.name) ? 'svg' : 'png';
          api.apply((x) => ops.setIcon(x, name, type, new Uint8Array(f.bytes)));
        } } }),
        own[name] ? h('button', { type: 'button', textContent: '用 App 的', on: { click: () => api.apply((x) => ops.setIcon(x, name, null, null)) } }) : null));
    }
  }

  panels.push({ id: 'text', label: '字和图标', ops, render });
})(typeof window !== 'undefined' ? window : globalThis);
