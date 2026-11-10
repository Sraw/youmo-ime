// 信息 (060-render-p3 §5.1 panel 10): the theme's name (its file's: what the app lists and an import keys on) and its
// <meta>: author, version, license, the least app version, a preview image (taken from the preview by default).
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const F = root.YoumoForm;
  // the app's (ThemeAssetLimits.MAX_PREVIEW_SIDE, MAX_PREVIEW_BYTES); a preview is taken at most PREVIEW_TAKE across
  const PREVIEW = { side: 2048, bytes: 512 * 1024 };
  const PREVIEW_TAKE = 720;
  const META = [['author', '作者'], ['version', '版本'], ['license', '许可']];

  const ops = {
    /** [d] named [name] (the <theme>'s name=). */
    name: (d, name) => panels.next(d, root.YoumoMaker.withName(d.xml, name)),
    /** [d] with <meta>'s [attr] [value] (empty: taken out). */
    meta: (d, attr, value) => panels.next(d, F.setMeta(d.xml, attr, value == null ? null : String(value).trim())),
    /**
     * [d] with [bytes] (a PNG or WebP) its preview image, `preview.<type>`; null: none, its file gone where nothing else
     * names it. Refused (throws) where the app would refuse it.
     */
    preview(d, L, bytes) {
      const old = F.meta(d.xml).preview;
      let files = panels.filesOf(d);
      let xml = d.xml;
      if (bytes) {
        const type = L.imageType(bytes);
        if (type !== 'png' && type !== 'webp') throw new Error('预览图只能是 PNG 或 WebP');
        const s = L.imageSize(bytes);
        if (!s || Math.max(s[0], s[1]) > PREVIEW.side) throw new Error('预览图最大 ' + PREVIEW.side + '×' + PREVIEW.side);
        if (bytes.length > PREVIEW.bytes) throw new Error('预览图不能超过 512 KB');
        const file = 'preview.' + type;
        files[file] = bytes;
        xml = F.setMeta(xml, 'preview', file);
        if (old && old !== file && !F.isNamed(xml, old)) files = panels.without(files, [old]);
      } else {
        xml = F.setMeta(xml, 'preview', null);
        if (old && !F.isNamed(xml, old)) files = panels.without(files, [old]);
      }
      return { xml, files, layouts: d.layouts };
    },
    PREVIEW, PREVIEW_TAKE,
  };

  function render(el, api) {
    const { h, row } = panels.ui;
    const d = api.doc();
    const m = F.meta(d.xml);
    const text = (value, change, placeholder = '') => {
      const i = h('input', { type: 'text', value: value ?? '', placeholder, on: { change: () => change(i.value) } });
      return i;
    };
    // a name emptied is not taken (a theme has one): the field shows the theme's again
    const nameField = text(root.YoumoMaker.themeName(d.xml), (v) => {
      if (v.trim()) api.apply((x) => ops.name(x, v.trim()));
      else nameField.value = root.YoumoMaker.themeName(api.doc().xml);
    });
    el.append(row('名称', nameField,
      h('span', { className: 'muted', textContent: '也是文件名；和 App 内置主题同名的 App 不收；和自己导入过的同名会覆盖它' })));
    for (const [a, label] of META) el.append(row(label, text(m[a], (v) => api.apply((x) => ops.meta(x, a, v)), '最多 64 个字')));
    el.append(row('最低 App 版本', text(m['min-app'], (v) => api.apply((x) => ops.meta(x, 'min-app', v)), '如 0.6.0')));
    el.append(row('预览图', h('span', { className: 'muted', textContent: m.preview ? m.preview : '没有（App 用自己画的）' }),
      h('button', { type: 'button', textContent: '从预览截一张', on: { click: async () => {
        try {
          const bytes = await api.previewImage(PREVIEW_TAKE, PREVIEW.bytes);
          api.apply((x) => ops.preview(x, api.lib, bytes));
        } catch (e) {
          api.toast('截不了：' + (e.message || e));
        }
      } } }),
      h('button', { type: 'button', textContent: '上传…', on: { click: async () => {
        const f = await api.pickFile('image/png,image/webp,.png,.webp');
        if (f) api.apply((x) => ops.preview(x, api.lib, new Uint8Array(f.bytes)));
      } } }),
      m.preview ? h('button', { type: 'button', textContent: '去掉', on: { click: () => api.apply((x) => ops.preview(x, api.lib, null)) } }) : null));
  }

  panels.push({ id: 'info', label: '信息', ops, render });
})(typeof window !== 'undefined' ? window : globalThis);
