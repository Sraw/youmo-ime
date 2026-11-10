// 动效 (060-render-p3 §5.1 panel 9, §4.2): a key's state changes (`transition`), its press (`press-scale`,
// `press-sink`), the popups' appearing (`appear`) and a press's effect (`effect`: a ripple image, particles). The
// properties and where each goes are MOTION's; whether this engine draws one is the engine's own answer (supported:
// a style of it read with no "not supported" warning), so a stage that makes one work (A1–A4) shows here as drawn
// with no change to this file. 试一下 plays it on the preview (ui.js play: an approximation, not the app's pixels).
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const F = root.YoumoForm;
  const EASINGS = ['linear', 'ease', 'ease-in', 'ease-out', 'ease-in-out'];
  const APPEARS = [['fade', '淡入'], ['scale', '放大'], ['rise', '升起']];
  // A3's limits of an effect's image (060-render-p3 A3): A3 moves them into youmo-format's limits
  const EFFECT_IMAGE = { side: 256, bytes: 128 * 1024 };

  /**
   * The motion properties (§4.2): [prop] on [select]s, [sample] a value of it the engine must read (the support probe).
   * `kind`: transition `ms easing`, scale %, sink dp, appear `kind ms`, effect.
   */
  const MOTION = [
    { prop: 'transition', label: '状态过渡', selects: [['key', '按键'], ['popup', '长按弹出'], ['preview', '按键气泡']], kind: 'transition', sample: '120ms ease-out' },
    { prop: 'press-scale', label: '按下缩放', selects: [['key:pressed', '按下的键']], kind: 'percent', min: 85, max: 110, sample: '95%' },
    { prop: 'press-sink', label: '按下下沉', selects: [['key:pressed', '按下的键']], kind: 'dp', min: 0, max: 4, sample: '1dp' },
    { prop: 'appear', label: '出现动画', selects: [['preview', '按键气泡'], ['popup', '长按弹出'], ['menu', '菜单']], kind: 'appear', sample: 'fade 120ms' },
    { prop: 'effect', label: '按键特效', selects: [['key:pressed', '按下的键']], kind: 'effect', sample: 'none' },
  ];

  /** `{ms, easing}` of a transition= text, null for none. */
  const parseTransition = (t) => { const m = /^(\d{1,3})ms (\S+)$/.exec(String(t ?? '').trim()); return m ? { ms: +m[1], easing: m[2] } : null; };
  const transitionText = (v) => (v ? F.clamp(v.ms, 0, 600) + 'ms ' + (EASINGS.includes(v.easing) ? v.easing : 'ease') : null);
  /** `{kind, ms}` of an appear= text. */
  const parseAppear = (t) => { const m = /^(fade|scale|rise) (\d{1,3})ms$/.exec(String(t ?? '').trim()); return m ? { kind: m[1], ms: +m[2] } : null; };
  const appearText = (v) => (v ? v.kind + ' ' + F.clamp(v.ms, 0, 250) + 'ms' : null);
  /** An effect= read: `{kind: none}`, `{kind: ripple-image, file, radius, ms}`, `{kind: particles, file, count, spread, ms}`. */
  function parseEffect(t) {
    const s = String(t ?? '').trim();
    if (s === 'none') return { kind: 'none' };
    let m = /^ripple-image\(([^,()]+), ?([\d.]+)%, ?(\d{1,3})ms\)$/.exec(s);
    if (m) return { kind: 'ripple-image', file: m[1], radius: +m[2], ms: +m[3] };
    m = /^particles\(([^,()]+), ?(\d{1,2}), ?(-?[\d.]+)dp, ?(\d{1,3})ms\)$/.exec(s);
    if (m) return { kind: 'particles', file: m[1], count: +m[2], spread: +m[3], ms: +m[4] };
    return null;
  }
  function effectText(v) {
    if (!v) return null;
    if (v.kind === 'none') return 'none';
    if (v.kind === 'ripple-image') return 'ripple-image(' + v.file + ', ' + F.clamp(v.radius, 0, 300) + '%, ' + F.clamp(v.ms, 0, 600) + 'ms)';
    return 'particles(' + v.file + ', ' + F.clamp(v.count, 1, 12) + ', ' + F.clamp(v.spread, 0, 48, 1) + 'dp, ' + F.clamp(v.ms, 0, 600) + 'ms)';
  }

  const TEXT = { transition: transitionText, appear: appearText, effect: effectText };

  // which MOTION properties this engine draws: a theme with each read, a warning naming it "not supported" says no
  const drawn = new WeakMap();
  function supported(E, base) {
    if (drawn.has(E)) return drawn.get(E);
    const out = {};
    for (const m of MOTION) {
      const xml = F.setProp(base, m.selects[0][0], m.prop, m.sample);
      const r = E.readTheme(xml, null, null);
      out[m.prop] = r.ok && !r.warnings.some((w) => w.message.startsWith(m.prop + '= is not supported'));
    }
    drawn.set(E, out);
    return out;
  }

  /** What is wrong with [bytes] ([name]) as an effect's image (A3: PNG or WebP, ≤ 256×256, ≤ 128 KB); null for nothing. */
  function effectImageProblem(L, name, bytes) {
    const type = L.imageType(bytes);
    if (type !== 'png' && type !== 'webp') return name + ' 不是 PNG 或 WebP';
    if (bytes.length > EFFECT_IMAGE.bytes) return name + ' 超过 128 KB';
    const s = L.imageSize(bytes);
    if (!s || Math.max(s[0], s[1]) > EFFECT_IMAGE.side) return name + ' 超过 256×256';
    return null;
  }

  const ops = {
    /** [d] with [select]'s motion [prop] the form's [v] (null: not set). */
    set: (d, select, prop, v) => {
      const m = MOTION.find((x) => x.prop === prop);
      const text = v == null ? null : TEXT[m.kind] ? TEXT[m.kind](v) : panels.text(F, m.kind, v, m);
      return panels.next(d, F.setProp(d.xml, select, prop, text));
    },
    /** [d] with an effect image [name] ([bytes]) as a file of its own: `effect-<name>`; refused (throws) where A3 would refuse it. */
    addEffectImage(d, L, name, bytes) {
      const wrong = effectImageProblem(L, name, bytes);
      if (wrong) throw new Error(wrong);
      const file = 'effect-' + name.toLowerCase().replace(/[^a-z0-9.]+/g, '-').replace(/^-+/, '');
      const files = Object.assign(Object.create(null), d.files);
      files[file] = bytes;
      return { xml: d.xml, files, layouts: d.layouts, file };
    },
    MOTION, parseTransition, parseAppear, parseEffect, transitionText, appearText, effectText, supported, effectImageProblem, EFFECT_IMAGE,
    /** What 试一下 plays of [xml]: the motion values the key's and the bubble's styles give (the plain select='s). */
    sample(xml) {
      return {
        transition: parseTransition(F.valueOf(xml, 'key', 'transition')),
        scale: parseFloat(F.valueOf(xml, 'key:pressed', 'press-scale')) || 100,
        sink: parseFloat(F.valueOf(xml, 'key:pressed', 'press-sink')) || 0,
        appear: parseAppear(F.valueOf(xml, 'preview', 'appear')),
      };
    },
  };

  const chosen = {};

  function render(el, api) {
    const { h, row, select, number, warnings } = panels.ui;
    const d = api.doc();
    const ok = supported(api.engine, root.YoumoMaker.fromBuiltin(api.engine, 'material').xml);
    el.append(h('p', {}, h('button', { type: 'button', textContent: '试一下', on: { click: () => api.play(ops.sample(api.doc().xml)) } }),
      h('span', { className: 'muted', textContent: ' 在预览上按一个键、弹一个气泡（近似效果，不是手机上的像素）' })));
    el.append(h('p', { className: 'muted', textContent: '按下那一刻不渐变：过渡只用在松开、Shift、锁定这些状态变化上。内置主题不带动效；用户在 App 里关了「键盘动效」或开了省电时都不播。' }));
    for (const m of MOTION) {
      const sel = chosen[m.prop] || m.selects[0][0];
      const now = () => F.valueOf(api.doc().xml, sel, m.prop);
      const put = (v) => api.apply((x) => ops.set(x, sel, m.prop, v));
      el.append(h('h3', { textContent: m.label }));
      if (!ok[m.prop]) el.append(h('p', { className: 'muted', textContent: '这个版本的引擎还不画它：写上会被现在的 App 忽略（有一条警告），以后的版本会用。' }));
      if (m.selects.length > 1) el.append(row('用在', select(m.selects, sel, (v) => { chosen[m.prop] = v; api.rerender(); })));
      const v = F.valueOf(d.xml, sel, m.prop);
      if (m.kind === 'transition') {
        const t = parseTransition(v);
        el.append(row(m.label, number(t ? t.ms : null, (ms) => put(ms == null ? null : { ms, easing: (parseTransition(now()) || {}).easing || 'ease' }), { min: 0, max: 600, step: 10, unit: 'ms' }),
          select(EASINGS.map((e) => [e, e]), t ? t.easing : 'ease', (e) => put({ ms: (parseTransition(now()) || { ms: 120 }).ms, easing: e }))));
      } else if (m.kind === 'appear') {
        const a = parseAppear(v);
        el.append(row(m.label, select([['', '不设']].concat(APPEARS), a ? a.kind : '', (k) => put(k ? { kind: k, ms: (parseAppear(now()) || { ms: 120 }).ms } : null)),
          a ? number(a.ms, (ms) => put({ kind: (parseAppear(now()) || a).kind, ms: ms ?? 0 }), { min: 0, max: 250, step: 10, unit: 'ms' }) : null));
      } else if (m.kind === 'effect') {
        const e = parseEffect(v);
        const files = Object.keys(d.files).filter((n) => n.startsWith('effect-'));
        const patch = (p) => { const c = parseEffect(now()); if (c && c.kind !== 'none') put(Object.assign({}, c, p)); };
        el.append(row('特效', select([['', '不设'], ['none', '无'], ['ripple-image', '涟漪图片'], ['particles', '粒子']], e ? e.kind : '', (k) => {
          const file = (e && e.file) || files[0];
          if (!k) put(null);
          else if (k === 'none') put({ kind: 'none' });
          else if (!file) api.toast('先上传一张特效图片');
          else if (k === 'ripple-image') put({ kind: k, file, radius: 100, ms: 300 });
          else put({ kind: k, file, count: 8, spread: 24, ms: 400 });
        }), h('button', { type: 'button', textContent: '上传特效图片…', on: { click: async () => {
          const f = await api.pickFile('image/png,image/webp,.png,.webp');
          if (!f) return;
          let made = null;
          api.apply((x) => { made = ops.addEffectImage(x, api.lib, f.name, new Uint8Array(f.bytes)); return made; });
          if (made) patch({ file: made.file });
        } } })));
        if (e && e.kind !== 'none') {
          el.append(row('图片', select(files.map((n) => [n, n]).concat(files.includes(e.file) ? [] : [[e.file, e.file + '（缺）']]), e.file, (file) => patch({ file }))));
          if (e.kind === 'ripple-image') el.append(row('最大半径', number(e.radius, (r) => patch({ radius: r ?? 100 }), { min: 0, max: 300, step: 10, unit: '% 键宽' })));
          else {
            el.append(row('个数', number(e.count, (c) => patch({ count: c ?? 8 }), { min: 1, max: 12, unit: '个' })));
            el.append(row('散开', number(e.spread, (s) => patch({ spread: s ?? 0 }), { min: 0, max: 48, unit: 'dp' })));
          }
          el.append(row('时长', number(e.ms, (ms) => patch({ ms: ms ?? 0 }), { min: 0, max: 600, step: 10, unit: 'ms' })));
        }
        el.append(h('p', { className: 'muted', textContent: '特效图片：PNG 或 WebP，不超过 256×256、128 KB。' }));
      } else {
        el.append(row(m.label, number(v == null ? null : parseFloat(v), put, { min: m.min, max: m.max, step: m.kind === 'dp' ? 0.5 : 1, unit: m.kind === 'dp' ? 'dp' : '%' })));
      }
      warnings(el, api, F, sel);
    }
  }

  panels.push({ id: 'motion', label: '动效', ops, render });
})(typeof window !== 'undefined' ? window : globalThis);
