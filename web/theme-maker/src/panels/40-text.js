// 字和图标: the main word, the mark, the shortcut, the root, the hint, the badge apart
// (a key's parts), each its colour, font (the theme's own, at most 4 uploaded), weight, size, place, spacing, outline
// and shadow; the app's icons replaced by name, each an SVG of filled paths (what the app imports: SvgSubset, the
// engine's readIcon says what is wrong with one) or a PNG or still WebP, its colours kept or only its shape drawn in
// the icon colour (tint=); names of the theme's own that only content= draws; content= (the icon a key draws in a
// state). Controls read the document when used (30-keys.js says why).
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const F = root.YoumoForm;
  const LIM = F.LIMITS;
  const MAX_FONTS = 4;
  const PARTS = [['', '整个键（各部分都跟着）'], ['label', '主字'], ['mark', '角标'], ['shortcut', '快捷图标'], ['root', '字根'], ['hint', '提示字'], ['badge', '徽标'], ['icon', '图标']];
  // a font's name as the engine reads one (Values' FONT): a letter, then at most 31 of letters, digits, - and _
  const FONT_NAME = 32;
  // an icon's name as the engine reads one (ThemeReader's ICON)
  const ICON_NAME = /^[a-z0-9]+(-[a-z0-9]+)*$/;
  const ICON_NAME_LENGTH = 64;
  const TINT = /\s+tint\s*=\s*("[^"]*"|'[^']*')/;

  const TEXT = {
    scale: (v) => F.clamp(v, LIM.scale[0], LIM.scale[1]) + '%',
    'letter-spacing': (v) => F.clamp(v, LIM.spacingEm[0], LIM.spacingEm[1], 3) + 'em',
    stroke: (v) => F.borderText(v),
    'text-shadow': (v) => F.shadowText(v),
  };

  const raster = panels.raster;

  // what is wrong with [bytes] as an <icon>, by the app's rules ([lim]: iconBytes, rasterIconBytes, rasterIconSide) and,
  // for an SVG, the engine's own (SvgSubset): `[message]`
  function iconProblems(E, L, bytes, lim) {
    if (raster(L, bytes)) {
      const why = root.YoumoMaker.rasterProblem(L, bytes, lim.rasterIconSide, lim.rasterIconBytes);
      return why ? [why] : [];
    }
    if (lim.iconBytes != null && bytes.length > lim.iconBytes) return ['超过 ' + lim.iconBytes + ' 字节'];
    let text;
    try {
      text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    } catch (e) {
      return ['不是 SVG、PNG 或 WebP'];
    }
    const svg = E.readIcon(text);
    return svg.ok ? [] : svg.problems.map((p) => (p.line ? '第 ' + p.line + ' 行：' : '') + p.message);
  }

  // the reader refuses icon(name) where no <icon> nor the app has it: each value naming [name] taken out, a style left bare gone
  function unnamed(xml, name) {
    for (const s of F.styles(xml).reverse()) {
      const props = s.attrs.filter((a) => a[1].replace(/\s+/g, '') === 'icon(' + name + ')').map((a) => a[0]);
      if (!props.length) continue;
      let tag = s.tag;
      for (const p of props) tag = tag.replace(new RegExp('\\s+' + p.replace(/[^a-z0-9-]/g, '\\$&') + '\\s*=\\s*("[^"]*"|\'[^\']*\')'), '');
      const bare = /\/>$/.test(tag) && F.attrsOf(tag).every((a) => a[0] === 'select');
      xml = bare ? xml.slice(0, s.start).replace(/[ \t]*$/, '').replace(/\n$/, '') + xml.slice(s.end) : xml.slice(0, s.start) + tag + xml.slice(s.start + s.tag.length);
    }
    return xml;
  }

  const iconOf = (xml, name) => F.elements(xml, 'icon').find((e) => e.attrs.some((a) => a[0] === 'name' && a[1] === name));

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
      const files = panels.filesOf(d);
      files[name + '.' + type] = bytes;
      return { xml: F.insert(d.xml, 'font', '<font name="' + name + '" src="' + name + '.' + type + '"/>'), files, layouts: d.layouts };
    },

    /**
     * [d] with the icon [name] (the app's, or a name of the theme's own) drawn as [bytes] (an SVG's, a PNG's or a WebP's;
     * a raster one only its shape in the icon colour where [tint]); null bytes: the app's again, or a name of its own gone.
     */
    setIcon(d, L, name, bytes, tint = false) {
      const own = iconOf(d.xml, name);
      let xml = d.xml;
      let files = panels.filesOf(d);
      if (own) {
        const src = (own.attrs.find((a) => a[0] === 'src') || [])[1];
        xml = xml.slice(0, own.start).replace(/[ \t]*$/, '').replace(/\n$/, '') + xml.slice(own.end);
        if (src && !F.isNamed(xml, src)) files = panels.without(files, [src]);
      }
      if (bytes) {
        // never over a file the theme has (another style may name it)
        const type = raster(L, bytes) ? L.imageType(bytes) : 'svg';
        const file = panels.fileName(files, 'icon-', name, type);
        files[file] = bytes;
        xml = F.insert(xml, 'icon', '<icon name="' + name + '" src="' + file + '"' + (tint && type !== 'svg' ? ' tint="true"' : '') + '/>');
      }
      return { xml, files, layouts: d.layouts };
    },

    /** [d] without the theme's own icon [name] (none of the app's) nor any 画的图标 naming it. */
    dropIcon(d, name) {
      const r = ops.setIcon(d, null, name, null);
      return { xml: unnamed(r.xml, name), files: r.files, layouts: r.layouts };
    },

    /** [d] with its raster icon [name] drawn as its shape in the icon colour ([on]) or in its own colours. */
    setTint(d, name, on) {
      const own = iconOf(d.xml, name);
      if (!own) return d;
      const tag = own.tag.replace(TINT, '').replace(/\s*(\/?>)$/, (all, close) => (on ? ' tint="true"' : '') + close);
      return panels.next(d, d.xml.slice(0, own.start) + tag + d.xml.slice(own.start + own.tag.length));
    },

    /** Whether [name] can be a new icon of the theme's own: as the engine reads a name, none of the app's or the theme's. */
    newIconName: (xml, appNames, name) => ICON_NAME.test(name) && name.length <= ICON_NAME_LENGTH && !appNames.includes(name) && !iconOf(xml, name),

    /** The theme's own icons: `{name: src}`. */
    icons: (xml) => Object.fromEntries(F.elements(xml, 'icon').map((e) => Object.fromEntries(e.attrs)).map((a) => [a.name, a.src])),
    /** The names of raster icons drawn in the icon colour (tint="true"). */
    tinted: (xml) => F.elements(xml, 'icon').map((e) => Object.fromEntries(e.attrs)).filter((a) => a.tint === 'true').map((a) => a.name),
    iconProblems,
    raster,
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
    const groups = api.iconGroups();
    const appNames = [...new Set(Object.values(groups).flat())];
    const own = ops.icons(d.xml);
    if (chosen.part === 'icon') {
      const names = [...new Set(appNames.concat(Object.keys(own)))].sort();
      const content = val('content');
      const choices = [['', '不设（App 本来画的）']].concat(names.map((n) => ['icon(' + n + ')', n + (own[n] ? '（主题的）' : '')]));
      if (content && !choices.some(([v]) => v === content)) choices.push([content, content]);
      el.append(row('画的图标', select(choices, content || '', (v) => set('content')(v || null))));
      el.append(h('p', { className: 'muted', textContent: '这个键在选的状态下画哪个图标（比如按下时换一张）；只对画图标的键有效，字母键没有图标。回车键先看 return-icon-…，没写才用这里的。要 0.6.3 以上的输入法。' }));
    }
    keys.sourcesTable(el, api, chosen.part);

    el.append(h('h3', { textContent: '换图标（SVG、PNG、WebP，按名字）' }));
    el.append(h('p', { className: 'muted', textContent: 'App 只收只有填充路径的 SVG：<svg>、<g>、<path> 和 fill 一类的属性，没有 transform、style、描边、渐变；不能用的会在这里说，也存不了。' }));
    const lim = api.limits();
    el.append(h('p', { className: 'muted', textContent: 'PNG 和静态 WebP：边长不超过 ' + lim.rasterIconSide + '、' + Math.round(lim.rasterIconBytes / 1024) + ' KB，放进图标的框里、不放大到原图的 3 倍以上；勾「上色」只用图的形状，颜色是图标颜色，不勾保留图自己的颜色。要 0.6.3 以上的输入法。' }));
    el.append(h('p', { className: 'muted', textContent: '一个名字一个意思：同一个名字在键上、栏上、面板里都换成这一张。“栏”是候选栏、工具栏、剪贴板条和悬浮键盘的条；“状态面板”是工具栏 ⋯ 打开的那一页。' }));
    const tinted = ops.tinted(d.xml);
    // by where each is drawn (the app's BarIcons and the scene's Icons: youmo-bar-icons); a name in two groups is one icon
    const GROUPS = [['return', '回车键'], ['keys', '键'], ['commands', '快捷键的命令'], ['bar', '栏'], ['panel', '状态面板和编辑面板']];
    for (const [g, label] of GROUPS) {
      el.append(h('h4', { textContent: label }));
      for (const name of groups[g]) {
        const also = GROUPS.filter(([o]) => o !== g && groups[o].includes(name)).map(([, l]) => l);
        const notes = [also.length ? '也在' + also.join('、') : null, api.barOwnImage(name) ? '栏上 App 自带的图和键上的不同，换了就是同一张' : null].filter(Boolean);
        el.append(iconRow(name, notes));
      }
    }

    el.append(h('h4', { textContent: '主题自己的图标（给「画的图标」用）' }));
    for (const name of Object.keys(own).filter((n) => !appNames.includes(n)).sort()) el.append(iconRow(name, []));
    const fresh = h('input', { type: 'text', placeholder: '名字，比如 backspace-pressed', maxLength: ICON_NAME_LENGTH });
    el.append(row('加一个', fresh, h('button', { type: 'button', textContent: '选图…', on: { click: async () => {
      const name = fresh.value.trim();
      if (!ops.newIconName(api.doc().xml, appNames, name)) {
        api.toast('名字只能用小写字母、数字和 -，不能和 App 的或主题已有的图标同名');
        return;
      }
      await pickIcon(name, false);
    } } })));

    async function pickIcon(name, tint) {
      const f = await api.pickFile('image/svg+xml,.svg,image/png,.png,image/webp,.webp');
      if (!f) return;
      const bytes = new Uint8Array(f.bytes);
      const wrong = iconProblems(api.engine, api.lib, bytes, api.limits());
      if (wrong.length) api.toast(f.name + ' 用不了：' + wrong[0]);
      else api.apply((x) => ops.setIcon(x, api.lib, name, bytes, tint));
    }

    function iconRow(name, notes) {
      const bytes = own[name] && d.files[own[name]];
      const problems = bytes ? iconProblems(api.engine, api.lib, bytes, lim) : [];
      const isRaster = !!(bytes && raster(api.lib, bytes));
      const thumb = isRaster && !problems.length ? h('img', { className: 'icon-thumb', alt: '', width: 24, height: 24 }) : null;
      if (thumb) {
        // revoked once shown: one URL per draw of the tab
        thumb.src = URL.createObjectURL(new Blob([bytes], { type: 'image/' + raster(api.lib, bytes).type }));
        thumb.addEventListener('load', () => URL.revokeObjectURL(thumb.src), { once: true });
        thumb.addEventListener('error', () => URL.revokeObjectURL(thumb.src), { once: true });
      }
      // after the buttons: the row is a label, and a click on it goes to its first control
      const tintBox = isRaster ? h('input', { type: 'checkbox', checked: tinted.includes(name), title: '上色：只用图的形状，颜色是图标颜色', on: { change: () => api.apply((x) => ops.setTint(x, name, tintBox.checked)) } }) : null;
      return row(name, thumb, h('span', { className: problems.length ? 'bad' : 'muted', textContent: (own[name] ? own[name] + (problems.length ? '：' + problems[0] : '') : 'App 自带') + (notes.length ? '（' + notes.join('；') + '）' : '') }),
        h('button', { type: 'button', textContent: '换…', on: { click: () => pickIcon(name, ops.tinted(api.doc().xml).includes(name)) } }),
        own[name] ? h('button', { type: 'button', textContent: appNames.includes(name) ? '用 App 的' : '删掉', on: { click: () => api.apply((x) => (appNames.includes(name) ? ops.setIcon(x, api.lib, name, null) : ops.dropIcon(x, name))) } }) : null,
        tintBox, tintBox ? h('span', { textContent: '上色' }) : null);
    }
  }

  panels.push({ id: 'text', label: '字和图标', ops, render });
})(typeof window !== 'undefined' ? window : globalThis);
