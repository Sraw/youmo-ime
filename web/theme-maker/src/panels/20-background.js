// 背景 (060-render-p3 §5.1 panel 2): the keyboard's picture, a crop for portrait and one for landscape (a box of
// the keyboard's shape, moved and zoomed on the picture; each cut its own file, as the app draws the cut), its
// brightness and blur, the keys see-through to it.
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const F = root.YoumoForm;
  const ORIENTATIONS = [['portrait', '竖屏'], ['landscape', '横屏']];
  const THROUGH = 'picture(0dp, 100%, #66ffffff)';
  // the keys 透出 makes see-through (060-render-p3 §5.1 panel 2), each with the colour it has again when turned off
  const THROUGH_KEYS = [['key', 'key'], ['key.function', 'altKey'], ['key.enter', 'accentKey'], ['key.space', 'key']];

  // [xml] with every THROUGH_KEYS style see-through ([on]), or those whose fill [was] says back to their colour
  // (the theme's colour of that name; none: the fill taken out, the wider style's shows)
  function seeThrough(xml, on, was = (f) => f === THROUGH) {
    const colors = F.colors(xml);
    for (const [sel, color] of THROUGH_KEYS) {
      if (on) xml = F.setProp(xml, sel, 'fill', THROUGH);
      else {
        const f = F.valueOf(xml, sel, 'fill');
        if (f != null && was(f)) xml = F.setProp(xml, sel, 'fill', color in colors ? '@' + color : null);
      }
    }
    return xml;
  }

  // a file's content id, worked out once per bytes (the tab is drawn again after every change)
  const ids = new WeakMap();
  const contentIdOf = (lib, bytes) => { if (!ids.has(bytes)) ids.set(bytes, lib.contentId(bytes)); return ids.get(bytes); };

  const ops = {
    /**
     * [d] with [p] its picture: `{type, src: bytes, cuts: {portrait: {bytes, type, rect}, landscape: …}, animated?}`
     * (the picture and its cuts made as the page encodes pictures; an animated one kept as picked), the keyboard showing it; null: none, the keyboard its
     * colour again. Files the old one had that nothing else names go.
     */
    setPicture(d, p) {
      let files = panels.filesOf(d);
      let xml = d.xml;
      let written = null;
      if (p) {
        const src = 'picture.' + p.type;
        const cuts = {};
        for (const [o] of ORIENTATIONS) {
          const c = p.cuts[o];
          if (!c) continue;
          cuts[o] = { file: 'picture-' + o + '.' + c.type, rect: c.rect };
        }
        // a picture picked says whether it is animated (an animated WebP the app plays, kept as it is: ui.js
        // choosePicture); an animated="true" of the one it replaces goes with it otherwise
        written = { src, brightness: 100, blur: 0, cuts, animated: !!p.animated };
        const old = F.picture(xml);
        if (old) Object.assign(written, { brightness: old.brightness, blur: old.blur });
      }
      const set = F.setPicture(xml, written);
      xml = set.xml;
      files = panels.without(files, set.gone);
      if (p) {
        files[written.src] = p.src;
        for (const [o, c] of Object.entries(written.cuts)) files[c.file] = p.cuts[o].bytes;
        xml = F.setProp(xml, 'keyboard', 'fill', 'picture');
      } else {
        // keys see-through to a picture there no longer is: their colours again
        xml = seeThrough(xml, false, (f) => /^\s*picture\(/.test(f));
      }
      if (!p && F.valueOf(xml, 'keyboard', 'fill') === 'picture') {
        xml = F.setProp(xml, 'keyboard', 'fill', 'background' in F.colors(xml) ? '@background' : null);
      }
      return { xml, files, layouts: d.layouts };
    },

    /** [d] with one orientation's cut made anew: [cut] `{bytes, type, rect}`. */
    setCut(d, orientation, cut) {
      const p = F.picture(d.xml);
      if (!p) return d;
      const file = 'picture-' + orientation + '.' + cut.type;
      p.cuts[orientation] = { file, rect: cut.rect };
      const set = F.setPicture(d.xml, p);
      const files = panels.without(d.files, set.gone);
      files[file] = cut.bytes;
      return { xml: set.xml, files, layouts: d.layouts };
    },

    /** [d] with its picture's brightness (%) and blur (dp) as [t] has them. */
    tune(d, t) {
      const p = F.picture(d.xml);
      if (!p) return d;
      return panels.next(d, F.setPicture(d.xml, Object.assign(p, t)).xml);
    },

    /** [d] with every key see-through to the picture (a light veil over it), or its fill its colour again. */
    through(d, on) {
      return panels.next(d, seeThrough(d.xml, on));
    },

    /** The keyboard's width over its height in [env] (the page's: its device and orientation): the crop's shape. */
    ratio(env) {
      const j = root.YoumoMaker.envJson(Object.assign({}, env, { form: 'normal' }));
      return j.width / j.height;
    },
  };

  // a crop moved or zoomed and not yet cut (用这个裁切), by its box's key: the tab drawn again (another edit, a key
  // tapped on the preview) shows it where it was left
  const moved = new Map();

  // the crop box on the picture: a canvas of it, the box dragged, a slider zooms
  function cropper(api, d, orientation) {
    const { h, row } = panels.ui;
    const p = F.picture(d.xml);
    const bmp = api.bitmap(p.src);
    const box = h('div', { className: 'crop' });
    if (!bmp) return h('p', { className: 'muted', textContent: '图片还没读进来' });
    const env = Object.assign({}, api.env(), { landscape: orientation === 'landscape' });
    const ratio = ops.ratio(env);
    const was = p.cuts[orientation] && p.cuts[orientation].rect;
    const bytes = d.files[p.src];
    const key = [orientation, p.src, bytes ? contentIdOf(api.lib, bytes) : '', bmp.width + 'x' + bmp.height, ratio, String(was)].join('|');
    const state = moved.get(key) || { zoom: 1, cx: 0.5, cy: 0.5 };
    moved.set(key, state);
    if (was && state.zoom === 1 && state.cx === 0.5 && state.cy === 0.5) {
      const full = F.cropRect(bmp.width, bmp.height, ratio);
      state.zoom = Math.max(1, (full[2] - full[0]) / Math.max(1, was[2] - was[0]));
      state.cx = (was[0] + was[2]) / 2 / bmp.width;
      state.cy = (was[1] + was[3]) / 2 / bmp.height;
    }
    const c = h('canvas', { className: 'crop-canvas' });
    const scale = Math.min(1, 480 / Math.max(bmp.width, bmp.height));
    c.width = Math.round(bmp.width * scale);
    c.height = Math.round(bmp.height * scale);
    const g = c.getContext('2d');
    const rect = () => F.cropRect(bmp.width, bmp.height, ratio, state.zoom, state.cx, state.cy);
    const draw = () => {
      g.drawImage(bmp, 0, 0, c.width, c.height);
      const [l, t, r, b] = rect().map((v) => v * scale);
      g.fillStyle = 'rgba(0,0,0,.5)';
      g.fillRect(0, 0, c.width, t);
      g.fillRect(0, b, c.width, c.height - b);
      g.fillRect(0, t, l, b - t);
      g.fillRect(r, t, c.width - r, b - t);
      g.strokeStyle = '#fff';
      g.lineWidth = 2;
      g.strokeRect(l, t, r - l, b - t);
    };
    let drag = null;
    c.addEventListener('pointerdown', (e) => { drag = { x: e.clientX, y: e.clientY, cx: state.cx, cy: state.cy }; c.setPointerCapture(e.pointerId); });
    c.addEventListener('pointermove', (e) => {
      if (!drag) return;
      const r = c.getBoundingClientRect();
      state.cx = drag.cx + (e.clientX - drag.x) / r.width;
      state.cy = drag.cy + (e.clientY - drag.y) / r.height;
      // kept where the box can be: its centre inside the room it has
      const [l, t, rr, b] = rect();
      state.cx = (l + rr) / 2 / bmp.width;
      state.cy = (t + b) / 2 / bmp.height;
      draw();
    });
    c.addEventListener('pointerup', () => { drag = null; });
    const zoom = h('input', { type: 'range', min: 1, max: 4, step: 0.05, value: state.zoom, on: { input: () => { state.zoom = +zoom.value; draw(); } } });
    const done = h('button', { type: 'button', textContent: '用这个裁切', on: { click: async () => {
      try {
        const cut = await api.cutPicture(p.src, rect());
        moved.delete(key);
        api.apply((x) => ops.setCut(x, orientation, cut));
      } catch (e) {
        api.toast('裁切失败：' + (e.message || e));
      }
    } } });
    draw();
    box.append(c, row('放大', zoom), done);
    // what its handlers hold (the picture decoded, its crop, the keyboard's shape): another, and the tab's in-place
    // update (ui.js sync) puts this box in whole instead of keeping the old one's canvas and handlers
    box.dataset.key = key;
    return box;
  }

  function render(el, api) {
    const { h, row, number } = panels.ui;
    const d = api.doc();
    const p = F.picture(d.xml);
    const lim = api.limits();
    el.append(h('p', {}, h('button', { type: 'button', textContent: p ? '换一张图片…' : '选图片…', on: { click: async () => {
      const made = await api.choosePicture();
      if (made) api.apply((x) => ops.setPicture(x, made));
    } } }), ' ', p ? h('button', { type: 'button', textContent: '去掉图片', on: { click: () => api.apply((x) => ops.setPicture(x, null)) } }) : null));
    el.append(h('p', { className: 'muted', textContent: '图片：PNG、JPEG、WebP；保存时重新编码，长边不超过 2560，JPEG（有透明的用 PNG）。动画 WebP（在 App 的限制内：' + Math.round(lim.animatedBytes / 1048576) + ' MB、长边 ' + lim.animatedSide + '、' + lim.animatedFrames + ' 帧、每帧至少 ' + lim.animatedFrameMs + ' ms）原样保留成动态背景，App 里居中铺满播放、不按裁切框；裁切框只管静态图（第一帧，键盘不动时显示）。超过限制的存成静态图（第一帧）。' }));
    if (!p) return;
    el.append(
      row('亮度', number(p.brightness, (v) => api.apply((x) => ops.tune(x, { brightness: Math.max(0, Math.min(100, v ?? 100)) })), { min: 0, max: 100, unit: '%' })),
      row('模糊', number(p.blur, (v) => api.apply((x) => ops.tune(x, { blur: Math.max(0, Math.min(25, v ?? 0)) })), { min: 0, max: 25, unit: 'dp' })),
    );
    const through = F.valueOf(d.xml, 'key', 'fill') === THROUGH;
    const box = h('input', { type: 'checkbox', checked: through, on: { change: () => api.apply((x) => ops.through(x, box.checked)) } });
    el.append(row('键透出背景（字母、功能、回车、空格键都半透明，透出图片）', box));
    for (const [o, label] of ORIENTATIONS) {
      el.append(h('h3', { textContent: label + '裁切（框是这台设备' + label + '键盘的形状，拖动、放大）' }));
      el.append(cropper(api, d, o));
    }
  }

  panels.push({ id: 'background', label: '背景', ops, render });
})(typeof window !== 'undefined' ? window : globalThis);
