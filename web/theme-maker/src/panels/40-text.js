// 字和图标 (060-render-p3 §5.1 panel 4): the main word, the mark, the shortcut, the root, the hint, the badge apart
// (a key's parts), each its colour, font (the theme's own, at most 4 uploaded), weight, size, place, spacing, outline
// and shadow; the app's icons replaced by name, each an SVG of filled paths (what the app imports: SvgSubset, the
// engine's readIcon says what is wrong with one). Controls read the document when used (30-keys.js says why).
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const F = root.YoumoForm;
  const LIM = F.LIMITS;
  const MAX_FONTS = 4;
  const PARTS = [['', '整个键（各部分都跟着）'], ['label', '主字'], ['mark', '角标'], ['shortcut', '快捷图标'], ['root', '字根'], ['hint', '提示字'], ['badge', '徽标'], ['icon', '图标']];
  // a font's name as the engine reads one (Values' FONT): a letter, then at most 31 of letters, digits, - and _
  const FONT_NAME = 32;

  const TEXT = {
    scale: (v) => F.clamp(v, LIM.scale[0], LIM.scale[1]) + '%',
    'letter-spacing': (v) => F.clamp(v, LIM.spacingEm[0], LIM.spacingEm[1], 3) + 'em',
    stroke: (v) => F.borderText(v),
    'text-shadow': (v) => F.shadowText(v),
  };

  // what is wrong with [bytes] as an <icon>, by the engine's own rule (SvgSubset) and the app's size: `[message]`
  function iconProblems(E, bytes, maxBytes) {
    if (maxBytes != null && bytes.length > maxBytes) return ['超过 ' + maxBytes + ' 字节'];
    let text;
    try {
      text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    } catch (e) {
      return ['不是 SVG（App 只收 SVG 图标）'];
    }
    const r = E.readIcon(text);
    return r.ok ? [] : r.problems.map((p) => (p.line ? '第 ' + p.line + ' 行：' : '') + p.message);
  }

  const ops = {
    /** [d] with [select]'s [prop] [v] (a form's value; null: not set), kept to what the engine reads. */
    set: (d, select, prop, v) => panels.next(d, F.setProp(d.xml, select, prop, v == null ? null : TEXT[prop] ? TEXT[prop](v) : String(v))),

    /**
     * [d] with font file [file] ([bytes]) as `<font name="…">`, its name the file's without its type (as the engine
     * reads a font's name), its type what its bytes are (a WOFF named .ttf is none). At most 4.
     */
    addFont(d, file, bytes, L) {
      const fonts = F.elements(d.xml, 'font');
      if (fonts.length >= MAX_FONTS) throw new Error('最多 ' + MAX_FONTS + ' 个字体');
      const type = L ? L.fontType(bytes) : (/\.(ttf|otf)$/i.exec(file) || [, null])[1];
      if (!type) throw new Error('不是 TrueType 或 OpenType 字体（App 只收 .ttf、.otf）');
      let name = file.replace(/\.[^.]*$/, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'font';
      if (!/^[a-z]/.test(name)) name = 'f-' + name;
      name = name.slice(0, FONT_NAME - 2).replace(/-+$/, '');
      const taken = (n) => fonts.some((f) => f.attrs.some((a) => a[0] === 'name' && a[1] === n)) || (n + '.' + type) in d.files;
      for (let i = 2; taken(name); i++) name = name.replace(/-\d+$/, '') + '-' + i;
      const files = Object.assign(Object.create(null), d.files);
      files[name + '.' + type] = bytes;
      return { xml: F.insert(d.xml, 'font', '<font name="' + name + '" src="' + name + '.' + type + '"/>'), files, layouts: d.layouts };
    },

    /** [d] with the app's icon [name] replaced by [bytes] (an SVG file's); null bytes: the app's again. */
    setIcon(d, name, bytes) {
      const own = F.elements(d.xml, 'icon').find((e) => e.attrs.some((a) => a[0] === 'name' && a[1] === name));
      let xml = d.xml;
      let files = Object.assign(Object.create(null), d.files);
      if (own) {
        const src = (own.attrs.find((a) => a[0] === 'src') || [])[1];
        xml = xml.slice(0, own.start).replace(/[ \t]*$/, '').replace(/\n$/, '') + xml.slice(own.end);
        if (src && !F.uncommented(xml).includes('"' + src + '"')) files = panels.without(files, [src]);
      }
      if (bytes) {
        const file = 'icon-' + name + '.svg';
        files[file] = bytes;
        xml = F.insert(xml, 'icon', '<icon name="' + name + '" src="' + file + '"/>');
      }
      return { xml, files, layouts: d.layouts };
    },

    /** The theme's own icons: `{name: src}`. */
    icons: (xml) => Object.fromEntries(F.elements(xml, 'icon').map((e) => Object.fromEntries(e.attrs)).map((a) => [a.name, a.src])),
    iconProblems,
    MAX_FONTS,
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
    const now = (p) => F.valueOf(api.doc().xml, sel, p);
    const set = (p) => (v) => api.apply((x) => ops.set(x, sel, p, v));
    el.append(row('颜色', color(val('color'), set('color'), F)));
    el.append(row('图标颜色', color(val('icon-color'), set('icon-color'), F)));
    const fonts = F.elements(d.xml, 'font').map((f) => Object.fromEntries(f.attrs).name).filter(Boolean);
    el.append(row('字体', select([['', '不设'], ['system', '系统']].concat(fonts.map((f) => [f, f])), val('font') || '', (v) => set('font')(v || null)),
      fonts.length < MAX_FONTS ? h('button', { type: 'button', textContent: '上传字体…', on: { click: async () => {
        const f = await api.pickFile('.ttf,.otf,font/ttf,font/otf');
        if (f) api.apply((x) => ops.addFont(x, f.name, new Uint8Array(f.bytes), api.lib));
      } } }) : h('span', { className: 'muted', textContent: '（最多 4 个）' })));
    el.append(row('字重', select([['', '不设'], ['300', '细'], ['400', '常规'], ['700', '粗']], val('weight') || '', (v) => set('weight')(v || null))));
    const scale = val('scale');
    el.append(row('大小', number(scale ? parseFloat(scale) : null, set('scale'), { min: LIM.scale[0], max: LIM.scale[1], step: 5, unit: '%' })));
    el.append(row('位置', select([['', '不设'], ['center', '居中'], ['top-left', '左上'], ['top-center', '上中']], val('align') || '', (v) => set('align')(v || null))));
    const ls = val('letter-spacing');
    el.append(row('字距', number(ls ? parseFloat(ls) : null, set('letter-spacing'), { min: LIM.spacingEm[0], max: LIM.spacingEm[1], step: 0.01, unit: 'em' })));
    const stroke = F.parseBorder(val('stroke'));
    const putStroke = (patch) => set('stroke')(Object.assign({ width: 1, color: '#000000' }, F.parseBorder(now('stroke')), patch));
    el.append(row('描边', number(stroke ? stroke.width : null, (w) => (w ? putStroke({ width: w }) : set('stroke')(null)), { min: 0, max: LIM.borderDp, step: 0.5, unit: 'dp' }),
      stroke ? color(stroke.color, (c) => (c ? putStroke({ color: c }) : set('stroke')(null)), F) : null));
    const sh = F.parseShadow(val('text-shadow'));
    const putShadow = (patch) => set('text-shadow')(Object.assign({ dx: 0, dy: 1, blur: 2, color: '#80000000' }, F.parseShadow(now('text-shadow')), patch));
    const o = LIM.offsetDp;
    el.append(row('投影', number(sh ? sh.dy : null, (v) => (v == null ? set('text-shadow')(null) : putShadow({ dy: v })), { min: -o, max: o, step: 0.5, unit: 'dp 偏移' }),
      number(sh ? sh.blur : null, (v) => putShadow({ blur: v ?? 0 }), { min: 0, max: o, step: 0.5, unit: 'dp 模糊' }), sh ? color(sh.color, (c) => (c ? putShadow({ color: c }) : set('text-shadow')(null)), F) : null));
    const hidden = h('input', { type: 'checkbox', checked: val('hidden') === 'true', on: { change: () => set('hidden')(hidden.checked ? true : null) } });
    el.append(row('藏起来', hidden));
    keys.sourcesTable(el, api, chosen.part);

    el.append(h('h3', { textContent: '换图标（SVG，按名字）' }));
    el.append(h('p', { className: 'muted', textContent: 'App 只收只有填充路径的 SVG：<svg>、<g>、<path> 和 fill 一类的属性，没有 transform、style、描边、渐变；不能用的会在这里说，也存不了。' }));
    const own = ops.icons(d.xml);
    for (const name of api.iconNames()) {
      const problems = own[name] && d.files[own[name]] ? iconProblems(api.engine, d.files[own[name]], api.limits().iconBytes) : [];
      el.append(row(name, h('span', { className: problems.length ? 'bad' : 'muted', textContent: own[name] ? own[name] + (problems.length ? '：' + problems[0] : '') : 'App 自带' }),
        h('button', { type: 'button', textContent: '换…', on: { click: async () => {
          const f = await api.pickFile('image/svg+xml,.svg');
          if (!f) return;
          const bytes = new Uint8Array(f.bytes);
          const wrong = iconProblems(api.engine, bytes, api.limits().iconBytes);
          if (wrong.length) api.toast(f.name + ' 用不了：' + wrong[0]);
          else api.apply((x) => ops.setIcon(x, name, bytes));
        } } }),
        own[name] ? h('button', { type: 'button', textContent: '用 App 的', on: { click: () => api.apply((x) => ops.setIcon(x, name, null)) } }) : null));
    }
  }

  panels.push({ id: 'text', label: '字和图标', ops, render });
})(typeof window !== 'undefined' ? window : globalThis);
