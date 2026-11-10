// youmo-ui: the page. Everything it draws or writes goes through youmo-lib (the app's rules and file
// format, held to the app by test/format.test.mjs); this part is the controls and the SVG preview.
(function () {
  'use strict';
  const L = window.YoumoLib;
  const json = (id) => JSON.parse(document.getElementById(id).textContent);
  const FORMAT = json('youmo-format');
  const PRESETS = json('youmo-presets');
  const LAYOUT = json('youmo-layout');
  const ICONS = json('youmo-icons');
  // youmo-layout (format 1, dp): each item's r [x, y, w, h] relative to the keyboard; the page keeps a box [l, t, r, b] beside it
  const boxOf = (r) => [r[0], r[1], r[0] + r[2], r[1] + r[3]];
  for (const p of LAYOUT.phones) [p.w, p.h] = p.px;
  for (const v of LAYOUT.views) {
    for (const e of v.items) {
      e.cell = boxOf(e.r);
      if (e.t === 'key') Object.assign(e, { kind: e.k, name: e.n });
    }
    v.els = v.items;
    const base = v.items.find((e) => e.t === 'base');
    v.frame = base ? base.cell : [0, 0, v.keyboard[2], v.keyboard[3]];
  }
  const NAMES = LAYOUT.names;
  const ROLES = NAMES.roles;
  const KINDS = L.KINDS;
  const SVGNS = 'http://www.w3.org/2000/svg';
  const STORE = 'youmo-theme-maker';

  // ---- words the page uses ----
  const PRESET_LABEL = {
    MaterialLight: 'Material 浅色', MaterialDark: 'Material 深色', PixelLight: 'Pixel 浅色', PixelDark: 'Pixel 深色',
    NordLight: 'Nord 浅色', NordDark: 'Nord 深色', DeepBlue: '深蓝', Monokai: 'Monokai', AMOLEDBlack: 'AMOLED 黑',
    TransparentDark: '透明深色', TransparentLight: '透明浅色',
  };
  const BOARD_LABEL = {
    qwerty: '26 键', nine: '九键', k14: '14 键', k18: '18 键', zhuyin: '注音全键盘', 'zhuyin-nine': '注音九键',
    number: '数字', symbols: '符号', emoji: '表情', handwriting: '手写',
  };
  const MODE_LABEL = { normal: '普通', 'one-handed': '单手', floating: '悬浮' };
  const STATE_LABEL = { idle: '空闲', typing: '打字中', longpress: '长按', clipboard: '剪贴板' };
  const KIND_LABEL = { letter: '字母键', function: '功能键', space: '空格', enter: '回车' };
  const ROLE_LABEL = {
    shift: '⇧', backspace: '⌫', space: '空格', enter: '回车', confirm: '确定', symbols: '符', numbers: '123', back: '返回',
    lang: '🌐', comma: '，', period: '。', semicolon: '；', star: '*', apostrophe: '分词', reset: '重输', lookup: '查',
    'lookup-on': '查（开）', slash: '/', at: '@', dot: '.', domain: '.com', search: '搜索', lock: '锁', emoji: '表情',
    emoticon: '颜文字', reading: '读音',
  };
  const COLOR_GROUPS = [
    ['底色', [
      ['backgroundColor', '键盘底色', '分块的按键时整个键盘的底'],
      ['keyboardColor', '平铺时的键盘底色', '按键不分块时用'],
      ['barColor', '候选栏底色', '候选栏选「颜色」时用'],
    ]],
    ['按键', [
      ['keyBackgroundColor', '字母键底色', ''], ['keyTextColor', '字母键文字', ''],
      ['altKeyBackgroundColor', '功能键底色', '⇧ ⌫ 123 等'], ['altKeyTextColor', '功能键文字', ''],
      ['accentKeyBackgroundColor', '回车键底色', '强调色'], ['accentKeyTextColor', '回车键文字', ''],
      ['spaceBarColor', '空格底色', ''], ['keyShadowColor', '按键阴影', ''], ['keyBorderColor', '按键边框', ''],
      ['keyPressHighlightColor', '按下变深', '「变深」时盖在键上的一层'], ['keyHintColor', '键角小字', ''],
    ]],
    ['候选栏', [
      ['candidateTextColor', '候选字', ''], ['candidateLabelColor', '候选序号', ''], ['candidateCommentColor', '候选注释', ''],
      ['dividerColor', '分隔线', ''], ['toolbarIconColor', '工具栏图标', ''],
    ]],
    ['弹窗与其他', [
      ['popupBackgroundColor', '弹窗底色', '长按弹出的字'], ['popupTextColor', '弹窗文字', ''],
      ['genericActiveBackgroundColor', '选中底色', '弹窗里选中的字、⋯ 面板'], ['genericActiveForegroundColor', '选中文字', ''],
      ['clipboardEntryColor', '剪贴板条目', ''],
    ]],
  ];
  const COLOR_LABEL = Object.fromEntries(COLOR_GROUPS.flatMap(([, rows]) => rows.map(([k, n]) => [k, n])));
  const SIMPLE = [['bg', '底色'], ['key', '按键'], ['text', '文字'], ['fn', '功能键'], ['accent', '强调色']];
  const ERR = {
    'not-theme': '这不是幽默输入法的主题文件',
    newer: '这个主题来自更新的版本，网页还不认识',
    older: '这是旧格式的主题：先导入 App，再从 App 导出一次，就能在这里打开',
    bad: '主题文件有错，App 也不会收',
    missing: '主题里缺文件',
    limit: '主题超出 App 的上限',
    image: '主题里有图片读不出大小（只认 PNG、JPEG、WebP、GIF），App 也可能不收',
  };
  const LIM = FORMAT.limits;
  const MB = 1024 * 1024;
  // a size in the words: 32 MB, 1.5 MB
  const mbWords = (n) => +(n / MB).toFixed(1) + ' MB';
  // the limits' words made of the limits, so the number told is the number checked
  const LIMIT_WORDS = {
    entries: '文件太多', fileBytes: `单个文件超过 ${mbWords(LIM.fileBytes)}`, totalBytes: `文件合计超过 ${mbWords(LIM.totalBytes)}`,
    overrides: `单独设置的键超过 ${LIM.overrides} 个`, pictureSide: `主题图边长超过 ${LIM.pictureSide}`,
    keyImageSide: `按键图片边长超过 ${LIM.keyImageSide}`, keyImageBytes: `按键图片超过 ${mbWords(LIM.keyImageBytes)}`,
  };
  /** What a ThemeError says to the user: its code's words, and for a limit which, for a file what is wrong with which. */
  function themeErrorText(e) {
    if (e.code === 'limit') return ERR.limit + '：' + (LIMIT_WORDS[e.detail] || e.detail);
    return ERR[e.code] + (e.code === 'bad' || e.code === 'missing' || e.code === 'image' ? '：' + e.detail : '');
  }
  // a font past this is taken, with a word that the zip will be big
  const FONT_WARN_BYTES = 10 * MB;

  // ---- state ----
  const S = {
    theme: null,
    // by file name, without a prototype: a theme's file may be named constructor or __proto__
    files: Object.create(null), // name → Uint8Array
    urls: Object.create(null), // name → blob: URL
    sizes: Object.create(null), // image name → [w, h] (the source picture's own, though its bitmap is smaller)
    bitmaps: Object.create(null), // image name → ImageBitmap
    avg: Object.create(null), // picture cut → its average colour
    blurred: Object.create(null), // `${file}|${radius}` → {url} or 'pending'
    blurUsed: new Set(), // the blurred ones the last preview drew: the others are let go
    fonts: Object.create(null), // font file → family
    faces: Object.create(null), // font file → its FontFace
    view: { phone: LAYOUT.phones[0].id, land: false, board: 'qwerty', mode: 'normal', state: 'idle', sys: 'light' },
    tab: 'picture',
    keyTab: 'all',
    kind: 'letter',
    colorView: 'simple',
    see: {}, // L.seeThrough's kept alphas: the colours laid over the picture → the alpha to give back
    manual: [], // palette fields set by hand in the full view: not followed from the simple five
    autoDark: true,
    selected: [], // element names (qwerty/q)
    allBoards: true,
    multi: false,
    pressed: -1,
  };

  // ---- small DOM helpers ----
  function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(props || {})) {
      if (v == null || v === false) continue;
      if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'class') el.className = v;
      else if (k === 'style') el.setAttribute('style', v);
      else if (k in el && typeof v !== 'string') el[k] = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
    for (const c of kids.flat(9)) if (c != null && c !== false) el.append(c instanceof Node ? c : String(c));
    return el;
  }
  function s(tag, attrs, ...kids) {
    const el = document.createElementNS(SVGNS, tag);
    for (const [k, v] of Object.entries(attrs || {})) if (v != null) el.setAttribute(k, v);
    for (const c of kids.flat(9)) if (c != null && c !== false) el.append(c instanceof Node ? c : document.createTextNode(String(c)));
    return el;
  }
  function seg(options, value, onPick, disabled) {
    return h('div', { class: 'seg', role: 'group' }, options.map(([v, label]) =>
      h('button', { type: 'button', 'aria-pressed': String(v === value), disabled: disabled && disabled(v), onclick: () => onPick(v) }, label)));
  }
  function radios(name, options, value, onPick) {
    return h('div', { class: 'row' }, options.map(([v, label]) => h('label', {},
      h('input', { type: 'radio', name, checked: v === value, onchange: () => onPick(v) }), label)));
  }
  function slider(label, value, min, max, unit, onInput, step) {
    const out = h('span', { class: 'val' }, value + unit);
    return h('div', { class: 'row' }, h('span', { class: 'k' }, label),
      h('input', { type: 'range', min, max, step: step || 1, value, 'aria-label': label,
        oninput: (e) => { out.textContent = e.target.value + unit; onInput(+e.target.value); } }), out);
  }
  let toastTimer = 0;
  function toast(text, err) {
    document.querySelectorAll('.toast').forEach((t) => t.remove());
    const t = h('div', { class: 'toast' + (err ? ' err' : ''), role: err ? 'alert' : 'status' }, text);
    // a modal dialog is above everything in the page: one said while it is open goes in it
    (document.querySelector('dialog[open]') || document.body).append(t);
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.remove(), err ? 9000 : 5000);
  }
  function pickFile(accept, multiple) {
    return new Promise((resolve) => {
      const i = h('input', { type: 'file', accept, multiple: !!multiple, style: 'display:none' });
      i.addEventListener('change', () => { resolve([...i.files]); i.remove(); });
      // the dialog closed without a file: settled with none, so a radio set to 「图片」 goes back
      i.addEventListener('cancel', () => { resolve([]); i.remove(); });
      document.body.append(i);
      i.click();
    });
  }
  const readBytes = async (file) => new Uint8Array(await file.arrayBuffer());
  const clone = (x) => JSON.parse(JSON.stringify(x));
  const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 3) | 8).toString(16);
  }));
  const mb = (n) => (n / MB).toFixed(n < MB ? 2 : 1);

  // ---- files ----
  function extOf(bytes) {
    return L.imageType(bytes) || L.fontType(bytes) || 'bin';
  }
  /** A file the page adds: named by the theme, its role and its content, so new content is a new name and the same is kept once. */
  async function addFile(role, bytes, caps) {
    const tail = '-' + role + '-' + L.contentId(bytes) + '.' + extOf(bytes);
    // a file name is at most 255 bytes: a long theme name (an imported one's) is cut, never a character in half
    let head = S.theme.name;
    while (new TextEncoder().encode(head + tail).length > 255) head = [...head].slice(0, -1).join('');
    const name = head + tail;
    await putFile(name, bytes, caps);
    return name;
  }
  /**
   * A file kept by name. An image's size is read from its header first, and one past [caps]
   * (L.decodeCaps: a cut's by default) is not decoded; its bitmap is no bigger than caps.keep (the
   * source: kept whole only as bytes).
   */
  async function putFile(name, bytes, caps = L.decodeCaps(FORMAT, 'picture')) {
    if (Object.hasOwn(S.files, name)) return;
    S.files[name] = bytes;
    S.urls[name] = URL.createObjectURL(new Blob([bytes]));
    if (L.imageType(bytes)) {
      const size = L.imageSize(bytes);
      if (size) S.sizes[name] = size;
      if (!L.decodable(size, caps)) return; // not drawn here (a cut or key image past its side: export refuses it)
      try {
        const bm = await decodeShrunk(new Blob([bytes]), size, caps.keep);
        S.bitmaps[name] = bm;
        if (!S.sizes[name]) S.sizes[name] = [bm.width, bm.height];
      } catch (e) { /* not drawable here: the size stays the header's */ }
    } else if (L.fontType(bytes)) {
      const family = 'youmo-' + L.contentId(bytes);
      try {
        const face = new FontFace(family, bytes.slice().buffer);
        await face.load();
        document.fonts.add(face);
        S.fonts[name] = family;
        S.faces[name] = face;
      } catch (e) { /* the browser can't read it: the app may still */ }
    }
  }
  /** An image decoded no bigger than [side] on its longer side (and the canvas limit iOS has): the full one let go. */
  async function decodeShrunk(blob, size, side) {
    const fit = (w, hh) => {
      const k = Math.min(1, side / Math.max(w, hh), Math.sqrt(CANVAS_PIXELS / (w * hh)));
      return [Math.max(1, Math.floor(w * k)), Math.max(1, Math.floor(hh * k))];
    };
    let bm;
    if (size && (size[0] > side || size[1] > side || size[0] * size[1] > CANVAS_PIXELS)) {
      const [w, hh] = fit(size[0], size[1]);
      try { bm = await createImageBitmap(blob, { resizeWidth: w, resizeHeight: hh, resizeQuality: 'high' }); } catch (e) { bm = null; }
    }
    if (!bm) bm = await createImageBitmap(blob);
    if (bm.width <= side && bm.height <= side && bm.width * bm.height <= CANVAS_PIXELS) return bm;
    // the browser took no size to decode to: drawn smaller, the full one closed
    const [w, hh] = fit(bm.width, bm.height);
    const c = canvasOf(w, hh);
    const g = c.getContext('2d');
    g.imageSmoothingQuality = 'high';
    g.drawImage(bm, 0, 0, w, hh);
    bm.close();
    return createImageBitmap(c);
  }
  function dropFiles() {
    for (const n of Object.keys(S.files)) dropFile(n);
    for (const k of Object.keys(S.blurred)) dropBlurred(k);
  }
  /** A file let go: its URL, bitmap, font and blurred copies with it. */
  function dropFile(n) {
    if (S.urls[n]) URL.revokeObjectURL(S.urls[n]);
    if (S.bitmaps[n]) S.bitmaps[n].close();
    if (S.faces[n]) document.fonts.delete(S.faces[n]);
    for (const m of [S.files, S.urls, S.sizes, S.bitmaps, S.avg, S.fonts, S.faces]) delete m[n];
    for (const k of Object.keys(S.blurred)) if (k.startsWith(n + '|')) dropBlurred(k);
  }
  function dropBlurred(k) {
    const b = S.blurred[k];
    if (b && b.url) URL.revokeObjectURL(b.url);
    if (b !== 'pending') delete S.blurred[k];
  }
  /** The files the theme no longer names let go (an old cut, a key image before it was made smaller). */
  function dropUnused() {
    const used = new Set(L.lookFiles(S.theme));
    for (const n of Object.keys(S.files)) if (!used.has(n)) dropFile(n);
  }
  function canvasOf(w, h) {
    const c = document.createElement('canvas');
    c.width = w;
    c.height = h;
    return c;
  }
  // a canvas past this many pixels does not draw on iOS (16.7 MP): toBlob gives null
  const CANVAS_PIXELS = 16777216;
  const pngOf = (canvas) => new Promise((resolve, reject) => canvas.toBlob((b) => {
    if (!b) return reject(new Error('图太大，浏览器画不出来'));
    b.arrayBuffer().then((x) => resolve(new Uint8Array(x)), reject);
  }, 'image/png'));
  /** The picture's pixels as the app's ints (0xAARRGGBB). */
  function argbPixels(img) {
    const d = img.data;
    const out = new Int32Array(d.length / 4);
    for (let i = 0; i < out.length; i++) out[i] = (d[i * 4 + 3] << 24) | (d[i * 4] << 16) | (d[i * 4 + 1] << 8) | d[i * 4 + 2];
    return out;
  }
  function averageOf(bm) {
    const c = canvasOf(16, 16);
    const g = c.getContext('2d');
    g.drawImage(bm, 0, 0, 16, 16);
    const d = g.getImageData(0, 0, 16, 16).data;
    let r = 0, gg = 0, b = 0;
    for (let i = 0; i < d.length; i += 4) { r += d[i]; gg += d[i + 1]; b += d[i + 2]; }
    const n = d.length / 4;
    return L.argb(255, Math.round(r / n), Math.round(gg / n), Math.round(b / n));
  }
  /**
   * The picture blurred as the app blurs it (BoxBlur): shrunk to a quarter, three box passes of
   * ThemeAssets.blurRadius (the dp in the bitmap's pixels, [widthPx] the keyboard's width as ThemePicture
   * passes it, not the cropped picture's), drawn back at
   * full size. Made once per picture and radius.
   */
  function blurredPicture(file, blurDp, density, widthPx) {
    const bitmap = S.bitmaps[file];
    const radius = L.blurRadius(blurDp, widthPx, bitmap ? bitmap.width : widthPx, density);
    const key = file + '|' + radius;
    S.blurUsed.add(key);
    const got = S.blurred[key];
    if (got && got.url) return got.url;
    if (!got && S.bitmaps[file]) {
      S.blurred[key] = 'pending';
      setTimeout(async () => {
        const bm = S.bitmaps[file];
        // a radius the preview has moved past (a slider dragged), or a picture let go meanwhile: not made
        if (!bm || !S.blurUsed.has(key)) { delete S.blurred[key]; return; }
        const c = canvasOf(bm.width, bm.height);
        const g = c.getContext('2d');
        g.drawImage(bm, 0, 0);
        const px = argbPixels(g.getImageData(0, 0, bm.width, bm.height));
        const small = L.blurShrink(px, bm.width, bm.height, 4);
        const w = Math.max(1, Math.floor(bm.width / 4));
        const hh = Math.max(1, Math.floor(bm.height / 4));
        const out = L.blur(small, w, hh, radius);
        const sc = canvasOf(w, hh);
        const sg = sc.getContext('2d');
        const id = sg.createImageData(w, hh);
        for (let i = 0; i < out.length; i++) {
          const v = out[i];
          id.data[i * 4] = (v >>> 16) & 255; id.data[i * 4 + 1] = (v >>> 8) & 255; id.data[i * 4 + 2] = v & 255; id.data[i * 4 + 3] = (v >>> 24) & 255;
        }
        sg.putImageData(id, 0, 0);
        const blob = await new Promise((r) => sc.toBlob(r, 'image/png'));
        if (!S.bitmaps[file] || !blob || !S.blurUsed.has(key)) { delete S.blurred[key]; return; } // let go meanwhile, or not drawable
        S.blurred[key] = { url: URL.createObjectURL(blob) };
        renderPreview();
      }, 0);
    }
    return S.urls[file]; // the sharp one till the blurred one is made
  }

  // ---- the theme ----
  // a key name's kinds, from the measured keys: a role on all boards is of each its keys are (the
  // space a function key on the number board), and the app lays each key's own colour
  const KIND_OF = (() => {
    const m = new Map();
    const add = (n, k) => m.set(n, [...new Set([...(m.get(n) || []), k])]);
    for (const v of LAYOUT.views) for (const e of v.items) if (e.t === 'key' && e.n) {
      add(e.n, e.k);
      const key = e.n.substring(e.n.indexOf('/') + 1);
      if (ROLES.includes(key)) add('*/' + key, e.k);
    }
    return m;
  })();
  const kindOfName = (n) => KIND_OF.get(n);
  // the colours over the picture now, as they are: none to give back
  const seenNow = () => Object.fromEntries([...L.seeThroughFields(S.theme, kindOfName)].map((f) => [f, null]));
  function presetTheme(p) {
    return L.defaultLook(FORMAT, uuid(), p.isDark, clone(p.palette), null);
  }
  /** [theme] as the page's, what follows the five worked out for it: the fields unlike the rule kept as they are. */
  function resetFollowing(theme) {
    S.theme = theme;
    S.autoDark = true;
    const sim = L.deriveSimple(L.simpleOf(theme.palette), theme.isDark);
    S.manual = Object.keys(theme.palette).filter((k) => theme.palette[k] !== sim[k]);
    S.see = seenNow();
  }
  function setTheme(theme, files) {
    dropFiles();
    S.selected = [];
    resetFollowing(theme);
    return Promise.all(Object.entries(files || {}).map(([n, b]) => putFile(n, b, L.decodeCaps(FORMAT, L.fileRole(theme, n))))).then(() => {
      for (const c of pictureCuts()) if (S.bitmaps[c]) S.avg[c] = averageOf(S.bitmaps[c]);
    });
  }
  /** The source picture's own size (its bitmap may be smaller). */
  const srcSize = (src) => S.sizes[src] || [S.bitmaps[src].width, S.bitmaps[src].height];
  function pictureCuts() {
    const p = S.theme.picture;
    return p ? [p.portrait.file, p.landscape && p.landscape.file].filter(Boolean) : [];
  }
  function changed(opts) {
    S.see = L.seeThrough(S.theme, kindOfName, S.see);
    dropUnused();
    saveSoon();
    previewSoon();
    if (opts && opts.settings) renderSettings();
  }
  // a slider or a colour drag sends an input event per step: the preview drawn once a frame, the save once it rests
  let previewFrame = 0;
  function previewSoon() {
    if (!previewFrame) previewFrame = requestAnimationFrame(() => { previewFrame = 0; renderPreview(); });
  }
  let saveTimer = 0;
  function saveSoon() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(save, 300);
  }
  function save() {
    clearTimeout(saveTimer);
    try {
      // the colours and options; the files are not kept (too big): a theme with them comes back without
      const t = clone(S.theme);
      if (L.lookFiles(t).length) stripFiles(t);
      localStorage.setItem(STORE, JSON.stringify({ theme: L.writeTheme(FORMAT, t), view: S.view, manual: S.manual, autoDark: S.autoDark, see: S.see, colorView: S.colorView, tab: S.tab }));
    } catch (e) { /* private mode, full, or off: the page works the same */ }
  }
  function stripFiles(t) {
    t.picture = null;
    t.base = { type: 'color' };
    if (t.bar.type === 'picture') t.bar = { type: 'transparent' };
    const keep = (x) => x && (x.type === 'image' || x.type === 'picture') ? null : x;
    for (const k of KINDS) {
      t.keys[k].fill = keep(t.keys[k].fill) || { type: 'color' };
      if (t.keys[k].pressed.type === 'image') t.keys[k].pressed = { type: 'darken' };
    }
    for (const [n, o] of Object.entries(t.keys.overrides)) {
      o.fill = keep(o.fill);
      if (o.pressed && o.pressed.type === 'image') o.pressed = null;
      if (!o.fill && !o.pressed && o.label === 'show') delete t.keys.overrides[n];
    }
    t.text.keyFont = null;
    t.text.candidateFont = null;
  }
  function restore() {
    try {
      const saved = JSON.parse(localStorage.getItem(STORE) || 'null');
      if (!saved) return false;
      setView(saved.view);
      if (chosen('colors', saved.colorView)) S.colorView = saved.colorView;
      if (chosen('tab', saved.tab)) S.tab = saved.tab;
      const { theme } = L.readTheme(FORMAT, saved.theme);
      S.theme = theme;
      S.manual = saved.manual || [];
      // a dark box ticked by hand stays so: a new background no longer sets it
      if (typeof saved.autoDark === 'boolean') S.autoDark = saved.autoDark;
      // saved without its files, what showed the picture shows a colour now: the alphas turned down for it go back
      S.see = L.seeThrough(S.theme, kindOfName, saved.see || {});
      return true;
    } catch (e) {
      return false;
    }
  }
  function setPalette(field, color) {
    S.theme.palette[field] = color;
    if (!S.manual.includes(field)) S.manual.push(field);
  }
  function setSimple(which, color) {
    const e = L.simpleEdit(S.theme.palette, S.manual, which, color, S.theme.isDark, S.autoDark);
    S.theme.palette = e.palette;
    S.theme.isDark = e.isDark;
    S.manual = e.manual;
    for (const k of e.set) delete S.see[k]; // a new colour: turned down again if over the picture
  }

  // ---- the view the preview draws ----
  function phone() {
    return LAYOUT.phones.find((p) => p.id === S.view.phone) || LAYOUT.phones[0];
  }
  const density = () => phone().dpi / 160;
  /** How the page lists a phone: its short name when the layout gives one, else its size and density. */
  const phoneLabel = (p) => p.label || `${p.w}×${p.h} · ${p.dpi}`;
  function views(match) {
    return LAYOUT.views.filter((v) => Object.entries(match).every(([k, x]) => v[k] === x));
  }
  function currentView() {
    const v = S.view;
    const tries = [
      { phone: v.phone, land: v.land, board: v.board, mode: v.mode, state: v.state },
      { phone: v.phone, land: v.land, board: v.board, mode: v.mode, state: 'idle' },
      // every phone has every board and mode, but one-handed on its side and the states but the 26 keys'
      { phone: v.phone, land: v.land, board: v.board, mode: 'normal', state: 'idle' },
    ];
    for (const t of tries) {
      const got = views(t);
      if (got.length) return got[0];
    }
    // not another phone's: drawn at this one's density it would be wrong without a word (the node test holds every phone whole)
    return views({ phone: v.phone })[0] || LAYOUT.views[0];
  }
  function cutFor(land) {
    const p = S.theme.picture;
    if (!p) return null;
    const c = land && p.landscape ? p.landscape : p.portrait;
    return S.sizes[c.file] ? c.file : null;
  }
  const kindField = (kind) => L.faceField(kind, S.theme.keys.blocks);
  const kindColor = (kind) => S.theme.palette[kindField(kind)];
  /** An item's colour: its palette field (or "#AARRGGBB"), times its alpha a. */
  function colorOf(e) {
    let argb = e.c && e.c[0] === '#' ? parseInt(e.c.slice(1), 16) >>> 0 : S.theme.palette[e.c];
    if (argb == null) argb = 0xff000000;
    if (e.a != null) argb = ((Math.round((argb >>> 24) * e.a) << 24) | (argb & 0xffffff)) >>> 0;
    return L.css(argb);
  }
  /**
   * A text or a key's label at its baseline y. With al (l | c | r) and x, the line's left end, middle or
   * right end is at x; without, the view is the text's width and it is centred in r. [scale] (a key's
   * text size) grows it about its middle, or, a corner label ([corner]: the key's cell), about the
   * corner it keeps to.
   */
  function drawText(e, font, weight, scale, family, corner) {
    const [l, t, r, b] = e.cell || boxOf(e.r);
    let fx = (l + r) / 2;
    let fy = (t + b) / 2;
    if (corner) {
      fx = (l + r) / 2 > (corner[0] + corner[2]) / 2 ? r : l;
      fy = (t + b) / 2 > (corner[1] + corner[3]) / 2 ? b : t;
    }
    const al = e.al;
    const anchor = { l: 'start', c: 'middle', r: 'end' }[al] || 'middle';
    const x0 = al ? e.x : (l + r) / 2;
    return s('text', {
      x: fx + (x0 - fx) * scale, y: fy + (e.y - fy) * scale, 'text-anchor': anchor, 'font-size': e.z * scale,
      'font-family': family(font), 'font-weight': weight, fill: colorOf(e),
    }, e.s);
  }
  /** An icon in its box: the vector at its own size (24 dp for 24 units), smaller only if the box is. */
  function drawIcon(e) {
    const ic = ICONS[e.i];
    if (!ic) return s('g');
    const [l, t, r, b] = e.cell || boxOf(e.r);
    const k = Math.min(1, (r - l) / ic.vw, (b - t) / ic.vh);
    const fill = colorOf(e);
    // rot: the view turned about its middle (the bar's menu arrow is expand_more at -90°)
    const turn = e.rot ? `rotate(${e.rot} ${(l + r) / 2} ${(t + b) / 2}) ` : '';
    return s('g', { transform: `${turn}translate(${(l + r) / 2 - (ic.vw * k) / 2},${(t + b) / 2 - (ic.vh * k) / 2}) scale(${k})` },
      ic.paths.map((p) => s('path', {
        d: p.d, transform: p.transform, 'fill-rule': p.rule === 'evenodd' ? 'evenodd' : undefined,
        fill: p.stroke ? 'none' : fill, stroke: p.stroke ? fill : undefined, 'stroke-width': p.stroke, opacity: p.alpha,
      })));
  }

  // ---- the preview ----
  const preview = { svg: null, box: null, note: null };
  const NARROW = window.matchMedia('(max-width: 600px)');
  let ids = 0;
  function renderPreview() {
    S.blurUsed = new Set();
    drawPreview();
    // a blur radius, a cut no longer drawn: its copy let go (made again if it comes back)
    for (const k of Object.keys(S.blurred)) if (!S.blurUsed.has(k)) dropBlurred(k);
  }
  function drawPreview() {
    const v = currentView();
    const d = density();
    const T = S.theme;
    const P = T.palette;
    const land = v.land;
    // the screen's width, the keyboard where the app puts it across it; above it what floats over it (preedit, a popup)
    const W = v.screen[0];
    const X = -v.keyboard[0];
    let top = 0;
    let maxY = v.keyboard[3];
    for (const e of v.els) { top = Math.min(top, e.cell[1]); maxY = Math.max(maxY, e.cell[3]); }
    const narrow = NARROW.matches;
    const appH = narrow ? 50 : 110; // on a phone only the text field: the keyboard has the room
    const dark = S.view.sys === 'dark';
    const defs = s('defs');
    const Y = top - appH;
    const H = maxY - Y;
    const svg = s('svg', { viewBox: `${X} ${Y} ${W} ${H}`, width: W, height: H, role: 'img', 'aria-label': '键盘预览' }, defs);
    ids = 0;
    const clip = (shape) => {
      const id = 'c' + ++ids;
      defs.append(s('clipPath', { id }, shape));
      return `url(#${id})`;
    };
    // the app behind: the page's light or dark, to see translucent keys against
    svg.append(s('rect', { x: X, y: Y, width: W, height: H, fill: dark ? '#202124' : '#f1f3f4' }));
    if (!narrow) for (let i = 0; i < 4; i++) svg.append(s('rect', { x: X + 16, y: Y + 14 + i * 16, width: W * (0.75 - i * 0.12), height: 8, rx: 4, fill: dark ? '#3c4043' : '#dadce0' }));
    svg.append(s('rect', { x: X + 10, y: top - 40, width: W - 20, height: 32, rx: 16, fill: dark ? '#303134' : '#ffffff', stroke: dark ? '#5f6368' : '#c4c7c5' }));
    svg.append(s('text', { x: X + 26, y: top - 24, 'dominant-baseline': 'central', 'font-size': 13, fill: dark ? '#9aa0a6' : '#80868b' }, '说点什么…'));

    const fr = v.frame;
    const frW = fr[2] - fr[0];
    const frH = fr[3] - fr[1];
    const board = s('g', { 'clip-path': clip(s('rect', { x: fr[0], y: fr[1], width: frW, height: frH })) });
    svg.append(board);
    // the picture over the whole keyboard (bar and keys), centre cropped as PictureWindow does
    const cut = cutFor(land);
    const window_ = cut ? (() => {
      const [pw, ph] = S.sizes[cut];
      const c = L.centerCrop(pw, ph, frW * d, frH * d);
      return { x: fr[0] + c.dx / d, y: fr[1] + c.dy / d, w: (pw * c.scale) / d, h: (ph * c.scale) / d };
    })() : null;
    const pictureIn = (g, blurDp, brightness, clipPath) => {
      if (!window_) return;
      const href = blurDp > 0 ? blurredPicture(cut, blurDp, d, frW * d) : S.urls[cut];
      const grp = s('g', { 'clip-path': clipPath });
      grp.append(s('image', { href, x: window_.x, y: window_.y, width: window_.w, height: window_.h, preserveAspectRatio: 'none' }));
      const a = L.darkenAlpha(brightness);
      if (a > 0) grp.append(s('rect', { x: window_.x, y: window_.y, width: window_.w, height: window_.h, fill: `rgba(0,0,0,${a / 255})` }));
      g.append(grp);
    };
    const baseColor = P[L.boardField(T.keys)];
    if (T.base.type === 'picture' && window_) pictureIn(board, T.picture.blurDp, T.picture.brightness, null);
    else board.append(s('rect', { x: fr[0], y: fr[1], width: frW, height: frH, fill: L.css(baseColor) }));
    const barItem = v.els.find((e) => e.t === 'bar');
    const bar = barItem && barItem.cell;
    if (bar && L.barField(T.bar)) board.append(s('rect', { x: bar[0], y: bar[1], width: bar[2] - bar[0], height: bar[3] - bar[1], fill: L.css(P[L.barField(T.bar)]) }));
    if (bar && T.bar.type === 'picture') pictureIn(board, T.bar.blurDp, T.bar.brightness, clip(s('rect', { x: bar[0], y: bar[1], width: bar[2] - bar[0], height: bar[3] - bar[1] })));

    const keyFont = T.text.keyFont && S.fonts[T.text.keyFont];
    const candFont = T.text.candidateFont && S.fonts[T.text.candidateFont];
    const family = (f) => (f ? `'${f}', ` : '') + 'system-ui, sans-serif';
    v.els.forEach((e, i) => {
      if (e.t === 'key') svg.append(drawKey(e, i, v, d, clip, pictureIn, keyFont, family));
      else if (e.t === 'icon') svg.append(drawIcon(e));
      else if (e.t === 'fill') {
        const [l, t, r, b] = e.cell;
        svg.append(s('rect', { x: l, y: t, width: r - l, height: b - t, rx: e.rad || 0, fill: colorOf(e) }));
      } else if (e.t === 'text') {
        // candidates and the preedit in the candidate font, unscaled; the rest is the keyboard's
        const cand = e.p === 'preedit' || (e.p === 'bar' && e.c === 'candidateTextColor');
        const el = drawText(e, cand ? candFont : keyFont, e.w || (cand ? T.text.candidateWeight : T.text.keyWeight), 1, family);
        // a line in a wider view over the keys (a clipboard entry) is cut at the view's edge; a title in
        // the bar is not, its view as wide as the line was in the app's font
        if (e.al && e.p === 'keyboard') el.setAttribute('clip-path', clip(s('rect', { x: e.r[0], y: e.r[1], width: e.r[2], height: e.r[3] })));
        svg.append(el);
      }
    });
    if (S.box) svg.append(s('rect', { x: Math.min(S.box[0], S.box[2]), y: Math.min(S.box[1], S.box[3]), width: Math.abs(S.box[2] - S.box[0]),
      height: Math.abs(S.box[3] - S.box[1]), fill: 'rgba(255,138,0,.15)', stroke: '#ff8a00', 'stroke-dasharray': '4 3' }));
    // the size: 1 dp a CSS pixel, unless the column is narrower (a phone, a landscape board)
    const room = preview.stage.clientWidth || W;
    if (W > room) {
      svg.setAttribute('width', room);
      svg.setAttribute('height', (H * room) / W);
    }
    preview.stage.replaceChildren(svg);
    preview.svg = svg;
    preview.view = v;
    preview.note.textContent = (W > room ? '已缩小显示 · ' : '实际尺寸 · ') +
      (S.tab === 'keys' && S.keyTab === 'single' ? '点键选中，Shift+点多选，拖框框选（手机上长按进入多选）' : '按住预览里的键看按下的样子');
  }

  function drawKey(e, i, v, d, clip, pictureIn, keyFont, family) {
    const T = S.theme;
    const P = T.palette;
    const [l, t, r, b] = e.cell;
    const parsed = e.name ? L.parseKeyName(e.name) : null;
    const style = L.resolveStyle(T.keys, parsed && parsed.board, parsed && parsed.key, e.kind, ROLES);
    const g0 = L.keyFace((r - l) * d, (b - t) * d, e.kind, T.keys, v.land, d, e.m !== false, e.b || 'default');
    const dp = (a) => a && [l + a[0] / d, t + a[1] / d, l + a[2] / d, t + a[3] / d];
    const face = dp(g0.face);
    const pressedArea = dp(g0.pressed);
    const radius = g0.radius / d;
    const shape = (a) => g0.oval
      ? s('ellipse', { cx: (a[0] + a[2]) / 2, cy: (a[1] + a[3]) / 2, rx: (a[2] - a[0]) / 2, ry: (a[3] - a[1]) / 2 })
      : s('rect', { x: a[0], y: a[1], width: Math.max(0, a[2] - a[0]), height: Math.max(0, a[3] - a[1]), rx: radius });
    const paint = (a, fill) => { const sh = shape(a); sh.setAttribute('fill', fill); return sh; };
    const g = s('g', { 'data-i': i, class: 'key' });
    const isPressed = S.pressed === i;
    const area = face || pressedArea;
    if (g0.shadow) g.append(paint(dp(g0.shadow), L.css(P.keyShadowColor)));
    const drawFill = (fill, a) => {
      if (fill.type === 'color') {
        if (face) g.append(paint(a, L.css(kindColor(e.kind))));
      } else if (fill.type === 'image') {
        g.append(nine(fill, a, d, clip(shape(a))));
      } else if (fill.type === 'picture') {
        pictureIn(g, fill.blurDp, fill.brightness, clip(shape(a)));
        // and the kind's colour over it, as KeyFaceDrawable lays it: a translucent one makes the glass
        g.append(paint(a, L.css(kindColor(e.kind))));
      }
    };
    if (isPressed && style.pressed.type === 'color') g.append(paint(pressedArea, L.css(style.pressed.argb)));
    else if (isPressed && style.pressed.type === 'image') g.append(nine(style.pressed, pressedArea, d, clip(shape(pressedArea))));
    else drawFill(style.fill, area);
    if (isPressed && style.pressed.type === 'darken') {
      if (T.keys.press === 'ripple') {
        const cx = (pressedArea[0] + pressedArea[2]) / 2;
        const cy = (pressedArea[1] + pressedArea[3]) / 2;
        const rr = Math.hypot(pressedArea[2] - pressedArea[0], pressedArea[3] - pressedArea[1]) / 3;
        g.append(s('circle', { cx, cy, r: rr, fill: L.css(P.keyPressHighlightColor), 'clip-path': clip(shape(pressedArea)) }));
      } else g.append(paint(pressedArea, L.css(P.keyPressHighlightColor)));
    }
    if (face && g0.border > 0) {
      const bw = g0.border / d;
      const inset = [face[0] + bw / 2, face[1] + bw / 2, face[2] - bw / 2, face[3] - bw / 2];
      const sh = shape(inset);
      sh.setAttribute('fill', 'none');
      sh.setAttribute('stroke', L.css(P.keyBorderColor));
      sh.setAttribute('stroke-width', bw);
      g.append(sh);
    }
    if (style.label !== 'hide') {
      for (const x of e.l || []) g.append(drawText(x, keyFont, x.w || T.text.keyWeight, T.text.keyScale / 100, family, x.role === 'main' ? null : e.cell));
      for (const x of e.i || []) g.append(drawIcon(x));
    }
    if (S.tab === 'keys' && S.keyTab === 'single' && e.name && S.selected.includes(e.name)) {
      g.append(s('rect', { x: l + 1, y: t + 1, width: r - l - 2, height: b - t - 2, rx: 4, fill: 'none', stroke: '#ff8a00', 'stroke-width': 2 }));
    }
    // the whole cell takes the pointer, as the app's key does
    g.append(s('rect', { x: l, y: t, width: r - l, height: b - t, fill: 'transparent' }));
    return g;
  }

  /** An image key face: NineSliceLayout's pieces, each a nested svg showing its part of the image. */
  function nine(fill, a, d, clipPath) {
    const g = s('g', { 'clip-path': clipPath });
    const size = S.sizes[fill.file];
    if (!size) return g;
    const target = [a[0] * d, a[1] * d, a[2] * d, a[3] * d];
    for (const p of L.nineSlice(size[0], size[1], fill.insets, fill.pxPerDp, target, d)) {
      const [sl, st, sr, sb] = p.src;
      const [dl, dt, dr, db] = p.dst;
      if (sr <= sl || sb <= st || dr <= dl || db <= dt) continue;
      g.append(s('svg', { x: dl / d, y: dt / d, width: (dr - dl) / d, height: (db - dt) / d, viewBox: `${sl} ${st} ${sr - sl} ${sb - st}`, preserveAspectRatio: 'none' },
        s('image', { href: S.urls[fill.file], x: 0, y: 0, width: size[0], height: size[1] })));
    }
    return g;
  }

  // the preview's pointer: hold to press; on the single-key tab, click, Shift+click and a box select
  function svgPoint(ev) {
    const pt = preview.svg.createSVGPoint();
    pt.x = ev.clientX;
    pt.y = ev.clientY;
    return pt.matrixTransform(preview.svg.getScreenCTM().inverse());
  }
  function bindPreview(stage) {
    let start = null;
    let longTimer = 0;
    const selecting = () => S.tab === 'keys' && S.keyTab === 'single';
    stage.addEventListener('pointerdown', (ev) => {
      if (!preview.svg) return;
      const k = ev.target.closest && ev.target.closest('[data-i]');
      const p = svgPoint(ev);
      start = { x: p.x, y: p.y, i: k ? +k.dataset.i : -1, moved: false, shift: ev.shiftKey, touch: ev.pointerType === 'touch' };
      stage.setPointerCapture(ev.pointerId);
      if (k) {
        S.pressed = +k.dataset.i;
        renderPreview();
        if (start.touch && selecting()) {
          // this press's own: a second finger within the time makes a start of its own
          const press = start;
          const name = preview.view.els[press.i].name;
          clearTimeout(longTimer);
          longTimer = setTimeout(() => {
            press.i = -2;
            if (!name) return toast('这个键没有名字（比如符号面板的格子），只能按它那一类设置');
            S.multi = true;
            toggle(name, true);
            if (navigator.vibrate) navigator.vibrate(20);
          }, 500);
        }
      }
    });
    stage.addEventListener('pointermove', (ev) => {
      if (!start || !selecting()) return;
      const p = svgPoint(ev);
      if (Math.hypot(p.x - start.x, p.y - start.y) > 6) {
        start.moved = true;
        clearTimeout(longTimer); // a finger that moves scrolls or drags: not a long press
      }
      if (start.moved && !start.touch) {
        clearTimeout(longTimer);
        S.pressed = -1;
        S.box = [start.x, start.y, p.x, p.y];
        previewSoon();
      }
    });
    const end = () => {
      clearTimeout(longTimer);
      if (!start) return;
      const st = start;
      start = null;
      if (S.box) {
        const [x0, x1] = [Math.min(S.box[0], S.box[2]), Math.max(S.box[0], S.box[2])];
        const [y0, y1] = [Math.min(S.box[1], S.box[3]), Math.max(S.box[1], S.box[3])];
        const names = preview.view.els.filter((e) => e.t === 'key' && e.name && e.cell[0] < x1 && e.cell[2] > x0 && e.cell[1] < y1 && e.cell[3] > y0).map((e) => e.name);
        S.box = null;
        S.selected = st.shift ? [...new Set([...S.selected, ...names])] : [...new Set(names)];
        S.pressed = -1;
        renderPreview();
        renderSettings();
        return;
      }
      S.pressed = -1;
      if (selecting() && st.i >= 0 && !st.moved) {
        const e = preview.view.els[st.i];
        if (e.name) toggle(e.name, st.shift || S.multi);
        else toast('这个键没有名字（比如符号面板的格子），只能按它那一类设置');
      } else if (selecting() && st.i === -1 && !st.moved && !S.multi) {
        S.selected = [];
        renderSettings();
      }
      renderPreview();
    };
    stage.addEventListener('pointerup', end);
    stage.addEventListener('pointercancel', end);
  }
  function toggle(name, add) {
    if (!add) S.selected = [name];
    else if (S.selected.includes(name)) S.selected = S.selected.filter((n) => n !== name);
    else S.selected = [...S.selected, name];
    renderPreview();
    renderSettings();
  }

  // ---- the controls over the preview ----
  function renderControls(box) {
    const v = S.view;
    const has = (m) => views({ phone: v.phone, land: v.land, ...m }).length > 0;
    const boards = [...new Set(LAYOUT.views.filter((x) => x.phone === v.phone && x.land === v.land).map((x) => x.board))];
    const set = (k, x) => {
      S.view[k] = x;
      if (k === 'sys') document.documentElement.dataset.theme = x;
      S.selected = [];
      save();
      renderControls(box);
      renderPreview();
      if (S.tab === 'keys') renderSettings();
    };
    box.replaceChildren(
      h('label', { class: 'k', for: 'phone' }, '手机'),
      h('div', {}, h('select', { id: 'phone', onchange: (e) => set('phone', e.target.value) },
        // narrow to wide, the default among them
        [...LAYOUT.phones].sort((a, b) => a.w / a.dpi - b.w / b.dpi).map((p) => h('option', { value: p.id, selected: p.id === v.phone }, phoneLabel(p)))), ' ',
      seg([[false, '竖屏'], [true, '横屏']], v.land, (x) => set('land', x))),
      // the phones of about its width under the select, not in it: there they widened the preview on a phone
      ...(phone().examples ? [h('span'), h('div', { class: 'hint' }, phone().examples + '（按常见出厂设置举例，没有逐台核实）')] : []),
      h('label', { class: 'k' }, '键盘'),
      seg(boards.map((b) => [b, BOARD_LABEL[b] || b]), currentView().board, (x) => set('board', x)),
      h('label', { class: 'k' }, '模式'),
      seg(Object.entries(MODE_LABEL), currentView().mode, (x) => set('mode', x), (x) => !has({ board: currentView().board, mode: x })),
      h('label', { class: 'k' }, '状态'),
      seg(Object.entries(STATE_LABEL), currentView().state, (x) => set('state', x), (x) => !has({ board: currentView().board, mode: currentView().mode, state: x })),
      h('label', { class: 'k' }, '系统'),
      seg([['light', '浅色'], ['dark', '深色']], v.sys, (x) => set('sys', x)),
    );
    const cv = currentView();
    summaryBox.textContent = '预览 · ' + [phoneLabel(phone()), v.land ? '横屏' : '竖屏', BOARD_LABEL[cv.board] || cv.board, MODE_LABEL[cv.mode], STATE_LABEL[cv.state]].join(' · ');
    if (v.land && currentView().board === 'qwerty' && currentView().mode === 'normal') {
      box.append(h('span'), h('div', { class: 'hint' }, '横屏的 26 键按 App 默认分成左右两半（分体）'));
    }
  }

  // ---- the settings ----
  const TABS = [['picture', '主题图'], ['colors', '颜色'], ['keys', '按键'], ['text', '文字'], ['bar', '候选栏'], ['popup', '弹窗']];
  let settingsBox = null;
  let tabsBox = null;
  function renderSettings() {
    tabsBox.replaceChildren(...TABS.map(([id, label]) => h('button', { type: 'button', role: 'tab', 'aria-selected': String(S.tab === id),
      onclick: () => { S.tab = id; save(); renderSettings(); renderPreview(); } }, label)));
    const body = { picture: pictureTab, colors: colorsTab, keys: keysTab, text: textTab, bar: barTab, popup: popupTab }[S.tab]();
    settingsBox.replaceChildren(...[body].flat().filter(Boolean));
  }

  function colorRow(field, note) {
    const P = S.theme.palette;
    const c = P[field];
    const { a } = L.channels(c);
    const text = h('input', { type: 'text', value: L.toHex(c), 'aria-label': COLOR_LABEL[field] + ' #AARRGGBB', spellcheck: false });
    const pick = h('input', { type: 'color', value: '#' + L.toHex(c).slice(3).toLowerCase(), 'aria-label': COLOR_LABEL[field] });
    const alpha = h('input', { type: 'range', min: 0, max: 255, value: a, 'aria-label': COLOR_LABEL[field] + ' 透明度', title: '透明度' });
    const apply = (col) => {
      setPalette(field, col);
      text.value = L.toHex(col);
      pick.value = '#' + L.toHex(col).slice(3).toLowerCase();
      alpha.value = L.channels(col).a;
      changed();
      warnBox.replaceChildren(...contrastWarnings(field));
      followBox.replaceChildren(...follow(field));
    };
    text.addEventListener('change', () => {
      const col = L.fromHex(text.value);
      if (col == null) { text.value = L.toHex(S.theme.palette[field]); toast('颜色写成 #AARRGGBB 或 #RRGGBB'); return; }
      apply(col);
    });
    pick.addEventListener('input', () => apply((L.fromHex(pick.value) & 0xffffff) | (alpha.value << 24)));
    alpha.addEventListener('input', () => apply((S.theme.palette[field] & 0xffffff) | (alpha.value << 24)));
    const warnBox = h('div', {}, contrastWarnings(field));
    const followBox = h('span', { class: 'follow' }, follow(field));
    return h('div', {}, h('div', { class: 'color-row' },
      h('div', { class: 'name' }, COLOR_LABEL[field], ' ', followBox, note ? h('small', {}, note) : null), pick, alpha, text), warnBox);
  }
  function follow(field) {
    if (S.colorView !== 'all' || S.tab !== 'colors') return [];
    if (!S.manual.includes(field)) return ['· 跟随'];
    return ['· 已单独改 ', h('button', { type: 'button', onclick: () => {
      S.manual = S.manual.filter((k) => k !== field);
      S.theme.palette[field] = L.deriveSimple(L.simpleOf(S.theme.palette), S.theme.isDark)[field];
      delete S.see[field];
      changed({ settings: true });
    } }, '恢复跟随')];
  }
  /** What a colour sits on: its own background, over the picture darkened (its average) or the board's colour. */
  function underColor(bgField) {
    const T = S.theme;
    const base = T.palette[L.boardField(T.keys)];
    let under = base;
    const cut = T.picture && T.base.type === 'picture' && S.avg[T.picture.portrait.file];
    if (cut != null && cut !== false) under = L.over(L.argb(L.darkenAlpha(T.picture.brightness), 0, 0, 0), cut);
    return bgField ? L.over(T.palette[bgField], under) : under;
  }
  const PAIRS = [
    ['keyTextColor', 'keyBackgroundColor', '字母键文字和底色'],
    ['altKeyTextColor', 'altKeyBackgroundColor', '功能键文字和底色'],
    ['accentKeyTextColor', 'accentKeyBackgroundColor', '回车键文字和底色'],
    ['candidateTextColor', null, '候选字和底色'],
    ['popupTextColor', 'popupBackgroundColor', '弹窗文字和底色'],
  ];
  function contrastWarnings(field) {
    const out = [];
    for (const [fg, bg, words] of PAIRS) {
      if (field && field !== fg && field !== bg && !(bg == null && field === 'barColor')) continue;
      const back = bg ? underColor(bg) : S.theme.bar.type === 'color' ? L.over(S.theme.palette.barColor, underColor(null)) : underColor(null);
      const ratio = L.contrast(S.theme.palette[fg], back);
      if (ratio < 3) out.push(h('div', { class: 'warn' }, `⚠ ${words}对比太低（${ratio.toFixed(1)}:1，建议至少 3:1）`));
    }
    return out;
  }

  function pictureTab() {
    const T = S.theme;
    const p = T.picture;
    const out = [h('h3', {}, '主题图')];
    out.push(h('div', { class: 'row' },
      h('button', { type: 'button', class: 'primary', onclick: choosePicture }, p ? '换一张图…' : '选图片…'),
      p ? h('button', { type: 'button', onclick: removePicture }, '去掉图片') : null));
    if (!p) {
      out.push(h('p', { class: 'hint' }, 'jpg / png / webp。选好后裁出竖屏、横屏各一块（比例是整个键盘区的宽高），可以转 90°。'));
      return out;
    }
    if (S.urls[p.portrait.file]) out.push(h('img', { class: 'thumb', src: S.urls[p.portrait.file], alt: '竖屏裁好的图' }));
    out.push(h('div', { class: 'row' },
      h('button', { type: 'button', onclick: () => cropDialog(false) }, '竖屏裁剪…'),
      h('label', {}, h('input', { type: 'checkbox', checked: !!p.landscape, onchange: (e) => (e.target.checked ? cropDialog(true) : (T.picture.landscape = null, changed({ settings: true }))) }), '横屏单独裁'),
      p.landscape ? h('button', { type: 'button', onclick: () => cropDialog(true) }, '横屏裁剪…') : null));
    if (!p.landscape) out.push(h('p', { class: 'hint' }, '横屏没有单独裁时，App 把竖屏那张居中裁满（预览同样）。'));
    if (!S.files[p.src]) out.push(h('div', { class: 'warn' }, '原图不在这里（从浏览器存的进度恢复时不带图片）：重新选一张图。'));
    out.push(slider('亮度', p.brightness, 0, 100, ' %', (x) => { T.picture.brightness = x; changed(); }));
    out.push(slider('模糊', p.blurDp, 0, LIM.blurDp, ' dp', (x) => { T.picture.blurDp = x; changed(); }));
    out.push(h('p', { class: 'hint' }, '亮度和 App 一样：图不改，显示时盖一层黑。模糊只用在键盘底。'));
    const used = [];
    if (T.base.type === 'picture') used.push('键盘底');
    if (T.bar.type === 'picture') used.push('候选栏');
    for (const k of KINDS) if (T.keys[k].fill.type === 'picture') used.push(KIND_LABEL[k]);
    const singles = Object.values(T.keys.overrides).filter((o) => o.fill && o.fill.type === 'picture').length;
    if (singles) used.push(singles + ' 个单独设置的键');
    out.push(h('h3', {}, '用在哪里'), h('p', {}, used.length ? used.join('、') : '还没有用：在「候选栏」「按键」里选「透出主题图」'));
    out.push(radios('base', [['picture', '键盘底用这张图'], ['color', '键盘底用颜色']], T.base.type, (x) => { T.base = { type: x }; changed({ settings: true }); }));
    return out;
  }

  async function choosePicture() {
    const [file] = await pickFile('image/*');
    if (!file) return;
    if (file.size > LIM.fileBytes) return toast(`图片太大（超过 ${mbWords(LIM.fileBytes)}）`, true);
    const bytes = await readBytes(file);
    const size = L.imageSize(bytes);
    const caps = L.decodeCaps(FORMAT, 'source');
    if (size && !L.decodable(size, caps)) return toast(`图片太大（${size[0]} × ${size[1]}）：先缩小一点`, true);
    let bm;
    try {
      // as small as a cut may be: a 48 MP photo kept whole would take 200 MB
      bm = await decodeShrunk(new Blob([bytes]), size, caps.keep);
    } catch (e) {
      return toast('浏览器打不开这张图' + (/heic|heif/i.test(file.name) ? '：这个格式浏览器打不开，先转成 jpg' : ''), true);
    }
    const T = S.theme;
    let src;
    try {
      // one the app may not read (HEIC, AVIF) as a PNG of the bitmap: no bigger than a canvas draws
      src = await addFile('src', L.imageType(bytes) ? bytes : await pngOf(drawn(bm)), caps);
    } catch (e) {
      bm.close();
      return toast('这张图存不下来：' + e.message, true);
    }
    const avg = averageOf(bm);
    bm.close();
    if (!T.picture) {
      // as the app's new theme from a picture: the translucent keys, dark or light by the picture
      const dark = L.luminance(avg) < 0.4;
      const preset = PRESETS.find((x) => x.name === (dark ? 'TransparentDark' : 'TransparentLight'));
      if (preset) {
        T.palette = clone(preset.palette);
        T.isDark = preset.isDark;
        S.manual = [];
        S.see = {}; // new colours: no alpha of the old ones to give back to them
      }
      T.base = { type: 'picture' };
    }
    T.picture = { src, portrait: { file: src, cropRect: null, rotation: 0 }, landscape: null, brightness: T.picture ? T.picture.brightness : 70, blurDp: T.picture ? T.picture.blurDp : 0 };
    await cutPicture(false, null, 0); // centred at first: the dialog moves it
    cropDialog(false);
  }
  function drawn(bm) {
    const c = canvasOf(bm.width, bm.height);
    c.getContext('2d').drawImage(bm, 0, 0);
    return c;
  }
  function removePicture() {
    const T = S.theme;
    T.picture = null;
    T.base = { type: 'color' };
    if (T.bar.type === 'picture') T.bar = { type: 'transparent' };
    for (const k of KINDS) if (T.keys[k].fill.type === 'picture') T.keys[k].fill = { type: 'color' };
    for (const o of Object.values(T.keys.overrides)) if (o.fill && o.fill.type === 'picture') o.fill = null;
    changed({ settings: true });
  }
  /** The keyboard's whole area on this phone, portrait or landscape, in px: what a cut is shaped to. */
  function boardPx(land) {
    const v = views({ phone: S.view.phone, land, board: 'qwerty', mode: 'normal', state: 'idle' })[0] || currentView();
    const d = density();
    return [Math.round((v.frame[2] - v.frame[0]) * d), Math.round((v.frame[3] - v.frame[1]) * d)];
  }
  /** Cuts [box] (on the picture turned [rotation], null: centred) to a PNG the keyboard's size at most; the crop kept as the source's box. */
  async function cutPicture(land, box, rotation) {
    const T = S.theme;
    const bm = S.bitmaps[T.picture.src];
    if (!bm) return toast('原图不在这里：重新选一张图', true);
    const [sw, sh] = srcSize(T.picture.src);
    const turned = rotation % 180 ? [sh, sw] : [sw, sh];
    const [bw, bh] = boardPx(land);
    if (!box) {
      const k = Math.min(turned[0] / bw, turned[1] / bh);
      const w = bw * k, hh = bh * k;
      box = [Math.round((turned[0] - w) / 2), Math.round((turned[1] - hh) / 2), Math.round((turned[0] + w) / 2), Math.round((turned[1] + hh) / 2)];
    }
    const [ow, oh] = L.resizeInside(box[2] - box[0], box[3] - box[1], bw, bh);
    const c = canvasOf(ow, oh);
    const g = c.getContext('2d');
    g.imageSmoothingQuality = 'high';
    g.scale(ow / (box[2] - box[0]), oh / (box[3] - box[1]));
    g.translate(-box[0], -box[1]);
    // the source turned clockwise [rotation] into the turned picture's space
    g.translate(turned[0] / 2, turned[1] / 2);
    g.rotate((rotation * Math.PI) / 180);
    g.drawImage(bm, -sw / 2, -sh / 2, sw, sh);
    let file;
    try {
      file = await addFile(land ? 'landscape' : 'portrait', await pngOf(c));
    } catch (e) {
      return toast('裁不出来：' + e.message, true);
    }
    const src = L.cropToSource(box, sw, sh, rotation);
    const crop = { file, cropRect: { bottom: src[3], left: src[0], right: src[2], top: src[1] }, rotation };
    if (land) T.picture.landscape = crop;
    else T.picture.portrait = crop;
    S.avg[file] = averageOf(S.bitmaps[file]);
    changed({ settings: true });
  }
  function cropDialog(land) {
    const T = S.theme;
    const bm = S.bitmaps[T.picture.src];
    if (!bm) return toast('原图不在这里：重新选一张图', true);
    const [sw, sh] = srcSize(T.picture.src);
    const cur = land ? T.picture.landscape : T.picture.portrait;
    let rotation = cur ? cur.rotation : 0;
    const [bw, bh] = boardPx(land);
    const aspect = bw / bh;
    let box = null;
    const turnedSize = () => (rotation % 180 ? [sh, sw] : [sw, sh]);
    const fit = () => {
      const [tw, th] = turnedSize();
      const k = Math.min(tw / bw, th / bh);
      box = [(tw - bw * k) / 2, (th - bh * k) / 2, (tw + bw * k) / 2, (th + bh * k) / 2];
    };
    if (cur && cur.cropRect && cur.cropRect.left >= 0 && cur.file !== T.picture.src) {
      const r = cur.cropRect;
      box = L.cropFromSource([r.left, r.top, r.right, r.bottom], sw, sh, rotation);
    } else fit();
    const area = h('div', { class: 'crop-area' });
    const canvas = canvasOf(1, 1);
    const frame = h('div', { class: 'crop-frame' }, h('div', { class: 'h' }));
    area.append(canvas, frame);
    let scale = 1;
    const draw = () => {
      const [tw, th] = turnedSize();
      const maxW = Math.min(window.innerWidth * 0.86, 860);
      const maxH = window.innerHeight * 0.6;
      scale = Math.min(maxW / tw, maxH / th);
      canvas.width = Math.round(tw * scale);
      canvas.height = Math.round(th * scale);
      area.style.width = canvas.width + 'px';
      area.style.height = canvas.height + 'px';
      const g = canvas.getContext('2d');
      g.save();
      g.translate(canvas.width / 2, canvas.height / 2);
      g.rotate((rotation * Math.PI) / 180);
      g.drawImage(bm, (-sw * scale) / 2, (-sh * scale) / 2, sw * scale, sh * scale);
      g.restore();
      place();
    };
    const place = () => {
      Object.assign(frame.style, { left: box[0] * scale + 'px', top: box[1] * scale + 'px', width: (box[2] - box[0]) * scale + 'px', height: (box[3] - box[1]) * scale + 'px' });
    };
    const clamp = () => {
      const [tw, th] = turnedSize();
      let w = box[2] - box[0];
      let hh = w / aspect;
      if (w > tw) { w = tw; hh = w / aspect; }
      if (hh > th) { hh = th; w = hh * aspect; }
      w = Math.max(w, 16); hh = w / aspect;
      const x = Math.min(Math.max(0, box[0]), tw - w);
      const y = Math.min(Math.max(0, box[1]), th - hh);
      box = [x, y, x + w, y + hh];
    };
    let drag = null;
    frame.addEventListener('pointerdown', (ev) => {
      ev.preventDefault();
      frame.setPointerCapture(ev.pointerId);
      drag = { x: ev.clientX, y: ev.clientY, box: box.slice(), resize: ev.target.classList.contains('h') };
    });
    frame.addEventListener('pointermove', (ev) => {
      if (!drag) return;
      const dx = (ev.clientX - drag.x) / scale;
      const dy = (ev.clientY - drag.y) / scale;
      if (drag.resize) {
        const w = Math.max(16, drag.box[2] - drag.box[0] + Math.max(dx, dy * aspect));
        box = [drag.box[0], drag.box[1], drag.box[0] + w, drag.box[1] + w / aspect];
      } else box = [drag.box[0] + dx, drag.box[1] + dy, drag.box[2] + dx, drag.box[3] + dy];
      clamp();
      place();
    });
    frame.addEventListener('pointerup', () => (drag = null));
    frame.addEventListener('pointercancel', () => (drag = null));
    area.addEventListener('wheel', (ev) => {
      ev.preventDefault();
      const k = ev.deltaY > 0 ? 1.06 : 1 / 1.06;
      const cx = (box[0] + box[2]) / 2, cy = (box[1] + box[3]) / 2;
      const w = (box[2] - box[0]) * k, hh = w / aspect;
      box = [cx - w / 2, cy - hh / 2, cx + w / 2, cy + hh / 2];
      clamp();
      place();
    }, { passive: false });
    const dlg = h('dialog', { 'aria-label': land ? '横屏裁剪' : '竖屏裁剪' },
      h('h3', {}, (land ? '横屏' : '竖屏') + '裁剪 · 框的比例是这台手机整个键盘区的宽高'),
      h('div', { class: 'row' },
        h('button', { type: 'button', onclick: () => { rotation = (rotation + 90) % 360; fit(); draw(); } }, '转 90°'),
        h('button', { type: 'button', onclick: () => { fit(); place(); } }, '放到最大'),
        h('span', { class: 'hint' }, '拖动框移动，拖右下角圆点或滚轮缩放')),
      area,
      h('div', { class: 'row' },
        h('button', { type: 'button', class: 'primary', onclick: async () => {
          dlg.close();
          await cutPicture(land, box.map(Math.round), rotation);
        } }, '用这一块'),
        h('button', { type: 'button', onclick: () => dlg.close() }, '取消')));
    dlg.addEventListener('close', () => {
      dlg.remove();
      // closed without a landscape cut (取消, Esc): the 横屏单独裁 box unticked again
      if (land && T.picture && !T.picture.landscape) renderSettings();
    });
    document.body.append(dlg);
    dlg.showModal();
    clamp();
    draw();
  }

  function colorsTab() {
    const T = S.theme;
    const out = [h('div', { class: 'row' },
      h('label', {}, h('input', { type: 'checkbox', checked: T.isDark, onchange: (e) => {
        T.isDark = e.target.checked;
        S.autoDark = false;
        // the press, shadow and border that follow are dark's or light's
        for (const k of L.followDark(T.palette, S.manual, T.isDark)) delete S.see[k];
        changed({ settings: true });
      } }), '深色主题'),
      h('span', { class: 'hint' }, '只管系统导航栏图标的深浅和行内建议的底色；按底色亮度自动勾，手动改过就不再自动')),
    seg([['simple', '简单 5 色'], ['all', '全部 24 色']], S.colorView, (x) => { S.colorView = x; save(); renderSettings(); })];
    if (S.colorView === 'simple') {
      const sim = L.simpleOf(T.palette);
      for (const [k, label] of SIMPLE) {
        const c = sim[k];
        const pick = h('input', { type: 'color', value: '#' + L.toHex(c).slice(3).toLowerCase(), 'aria-label': label });
        const text = h('input', { type: 'text', value: L.toHex(c), spellcheck: false, 'aria-label': label + ' #AARRGGBB' });
        const apply = (col) => { setSimple(k, col); changed(); text.value = L.toHex(col); warn.replaceChildren(...contrastWarnings()); };
        pick.addEventListener('input', () => apply((L.fromHex(pick.value) & 0xffffff) | (L.channels(L.simpleOf(T.palette)[k]).a << 24)));
        text.addEventListener('change', () => { const col = L.fromHex(text.value); if (col == null) return toast('颜色写成 #AARRGGBB 或 #RRGGBB'); apply(col); pick.value = '#' + L.toHex(col).slice(3).toLowerCase(); });
        out.push(h('div', { class: 'color-row' }, h('div', { class: 'name' }, label), pick, h('span'), text));
      }
      const warn = h('div', {}, contrastWarnings());
      out.push(warn, h('p', { class: 'hint' }, '其余的颜色按 App 动态取色主题的规则从这 5 个算出来；「全部」里与规则不同的不跟着变。'));
      if (S.manual.length) out.push(h('p', { class: 'hint' }, `有 ${S.manual.length} 个颜色与规则不同，在「全部」里看。`));
    } else {
      for (const [group, rows] of COLOR_GROUPS) {
        out.push(h('h3', {}, group));
        for (const [field, , note] of rows) out.push(colorRow(field, note));
      }
    }
    return out;
  }

  // ---- keys ----
  function keysTab() {
    const out = [h('div', { class: 'subtabs' }, [['all', '整体'], ['kinds', '四类'], ['single', '单个键']].map(([id, label]) =>
      h('button', { type: 'button', 'aria-pressed': String(S.keyTab === id), onclick: () => { S.keyTab = id; renderSettings(); renderPreview(); } }, label)))];
    if (S.keyTab === 'all') out.push(...keysAll());
    else if (S.keyTab === 'kinds') out.push(...keysKinds());
    else out.push(...keysSingle());
    return out;
  }
  function keysAll() {
    const K = S.theme.keys;
    return [
      radios('blocks', [[true, '分块（每个键一块底）'], [false, '平铺（字母键没有底）']], K.blocks, (x) => { K.blocks = x; changed({ settings: true }); }),
      slider('圆角', K.radiusDp, 0, LIM.radiusDp, ' dp', (x) => { K.radiusDp = x; changed(); }),
      slider('边框', K.borderDp, 0, LIM.borderDp, ' dp', (x) => { K.borderDp = x; changed(); }),
      slider('阴影', K.shadowDp, 0, LIM.shadowDp, ' dp', (x) => { K.shadowDp = x; changed(); }),
      h('h3', {}, '间距'),
      slider('竖屏 横', K.gap.h, 0, LIM.gapDp, ' dp', (x) => { K.gap.h = x; changed(); }),
      slider('竖屏 纵', K.gap.v, 0, LIM.gapDp, ' dp', (x) => { K.gap.v = x; changed(); }),
      slider('横屏 横', K.gap.hLand, 0, LIM.gapDp, ' dp', (x) => { K.gap.hLand = x; changed(); }),
      slider('横屏 纵', K.gap.vLand, 0, LIM.gapDp, ' dp', (x) => { K.gap.vLand = x; changed(); }),
      h('h3', {}, '按下'),
      radios('press', [['tint', '变色'], ['ripple', '水波纹']], K.press, (x) => { K.press = x; changed(); }),
      h('p', { class: 'hint' }, '平铺时空格画成一条、回车画成圆，和 App 一样。'),
    ];
  }
  function keysKinds() {
    const K = S.theme.keys;
    const kind = S.kind;
    const st = K[kind];
    return [
      seg(KINDS.map((k) => [k, KIND_LABEL[k]]), kind, (x) => { S.kind = x; renderSettings(); }),
      h('h3', {}, '底'),
      fillEditor(st.fill, false, (f) => { st.fill = f; changed({ settings: true }); }, 'k-' + kind),
      h('h3', {}, '按下'),
      pressedEditor(st.pressed, false, (p) => { st.pressed = p; changed({ settings: true }); }, 'k-' + kind),
      h('h3', {}, '颜色'),
      colorRow(kindField(kind)),
      colorRow({ letter: 'keyTextColor', function: 'altKeyTextColor', space: 'keyTextColor', enter: 'accentKeyTextColor' }[kind]),
      kind === 'letter' ? colorRow('keyBorderColor') : null,
      budgetBars(),
    ];
  }
  /** A face: the kind's colour, an image (nine-slice), or the picture through it; [inherit]: also "as its kind" (null). */
  function fillEditor(fill, inherit, set, id) {
    const type = fill ? fill.type : 'inherit';
    const opts = [['color', '颜色'], ['image', '图片'], ['picture', '透出主题图']];
    if (inherit) opts.unshift(['inherit', '跟随它那一类']);
    const out = [radios('fill-' + id, opts, type, async (x) => {
      if (x === 'inherit') return set(null);
      if (x === 'color') return set({ type: 'color' });
      if (x === 'picture') {
        if (!S.theme.picture) { toast('先在「主题图」里选一张图'); return renderSettings(); }
        return set({ type: 'picture', blurDp: 0, brightness: 100 });
      }
      const img = await chooseKeyImage();
      if (img) set(img);
      else renderSettings();
    })];
    if (fill && fill.type === 'picture') {
      out.push(slider('模糊', fill.blurDp, 0, LIM.blurDp, ' dp', (x) => { fill.blurDp = x; changed(); }));
      out.push(slider('亮度', fill.brightness, 0, 100, ' %', (x) => { fill.brightness = x; changed(); }));
      out.push(h('p', { class: 'hint' }, '键里透出它下面那块主题图（毛玻璃：调模糊），上面再盖一层这类键的底色。选透出时底色自动调到 30% 不透明，在下面的「颜色」里可以再调（0 = 完全透明）；改回别的，恢复原来的不透明度。'));
    }
    if (fill && fill.type === 'image') out.push(imageEditor(fill, set));
    return h('div', {}, out);
  }
  function pressedEditor(pressed, inherit, set, id) {
    const type = pressed ? pressed.type : 'inherit';
    const opts = [['darken', '变深'], ['color', '颜色'], ['image', '图片']];
    if (inherit) opts.unshift(['inherit', '跟随它那一类']);
    const out = [radios('pressed-' + id, opts, type, async (x) => {
      if (x === 'inherit') return set(null);
      if (x === 'darken') return set({ type: 'darken' });
      if (x === 'color') return set({ type: 'color', argb: S.theme.palette.keyPressHighlightColor | 0xff000000 });
      const img = await chooseKeyImage();
      if (img) set(img);
      else renderSettings();
    })];
    if (pressed && pressed.type === 'color') {
      const text = h('input', { type: 'text', value: L.toHex(pressed.argb), spellcheck: false, 'aria-label': '按下的颜色 #AARRGGBB' });
      const pick = h('input', { type: 'color', value: '#' + L.toHex(pressed.argb).slice(3).toLowerCase(), 'aria-label': '按下的颜色' });
      pick.addEventListener('input', () => { pressed.argb = (L.fromHex(pick.value) & 0xffffff) | (pressed.argb & 0xff000000); text.value = L.toHex(pressed.argb); changed(); });
      text.addEventListener('change', () => { const c = L.fromHex(text.value); if (c == null) return toast('颜色写成 #AARRGGBB 或 #RRGGBB'); pressed.argb = c; changed({ settings: true }); });
      out.push(h('div', { class: 'row' }, h('span', { class: 'k' }, '按下的颜色'), pick, text));
    }
    if (pressed && pressed.type === 'image') out.push(imageEditor(pressed, set));
    return h('div', {}, out);
  }
  /** A key image: within the app's limits (LIM.keyImageSide, LIM.keyImageBytes), shrunk here if over. */
  async function chooseKeyImage() {
    const [file] = await pickFile('image/png,image/jpeg,image/webp');
    if (!file) return null;
    if (file.size > LIM.fileBytes) { toast(`图片太大（超过 ${mbWords(LIM.fileBytes)}）`, true); return null; }
    let bytes = await readBytes(file);
    const size = L.imageSize(bytes);
    // bigger than a key image may be is made smaller; bigger than a source picture may be, refused
    if (size && !L.decodable(size, L.decodeCaps(FORMAT, 'source'))) { toast(`图片太大（${size[0]} × ${size[1]}）`, true); return null; }
    let bm;
    // decoded no bigger than a key image may be: then made again from that, if it was bigger
    try { bm = await decodeShrunk(new Blob([bytes]), size, LIM.keyImageSide); } catch (e) { toast('浏览器打不开这张图', true); return null; }
    const drawnSize = [bm.width, bm.height];
    let again = !size || Math.max(size[0], size[1]) > LIM.keyImageSide;
    let shrunk = again && !!size;
    let k = 1;
    try {
      for (let tries = 0; (again || bytes.length > LIM.keyImageBytes || !L.imageType(bytes)) && tries < 6; tries++) {
        const c = canvasOf(Math.max(1, Math.round(bm.width * k)), Math.max(1, Math.round(bm.height * k)));
        c.getContext('2d').drawImage(bm, 0, 0, c.width, c.height);
        bytes = await pngOf(c);
        shrunk = shrunk || k < 1;
        again = false;
        k *= 0.75;
      }
    } catch (e) {
      toast('这张图存不下来：' + e.message, true);
      return null;
    } finally {
      bm.close();
    }
    if (shrunk) toast(`图片超过 App 的上限（边长 ${LIM.keyImageSide}、${mbWords(LIM.keyImageBytes)}），已缩小`);
    const name = await addFile('key', bytes, L.decodeCaps(FORMAT, 'key'));
    const [w, hh] = S.sizes[name] || drawnSize;
    return { type: 'image', file: name, insets: [0, 0, 0, 0], pxPerDp: Math.min(LIM.pxPerDp, Math.max(1, Math.round(Math.max(w, hh) / 48))) };
  }
  /** The nine-slice editor: four lines on the image to drag (what is not stretched), and a wide and a narrow key drawn with it. */
  function imageEditor(img, set) {
    const size = S.sizes[img.file];
    const box = h('div', {});
    if (!size) {
      box.append(h('div', { class: 'warn' }, '这张图不在这里（从浏览器存的进度恢复时不带图片）：重新选一张。'));
      return box;
    }
    const [w, hh] = size;
    const k = Math.min(160 / w, 160 / hh, 4);
    const ed = s('svg', { width: w * k, height: hh * k, viewBox: `0 0 ${w} ${hh}` });
    ed.append(s('image', { href: S.urls[img.file], x: 0, y: 0, width: w, height: hh }));
    const lines = [];
    const draw = () => {
      lines.forEach((l) => l.remove());
      lines.length = 0;
      const [il, it, ir, ib] = img.insets;
      const add = (x1, y1, x2, y2, side) => {
        const ln = s('line', { x1, y1, x2, y2, stroke: '#ff8a00', 'stroke-width': Math.max(1, 2 / k), 'stroke-dasharray': `${4 / k} ${3 / k}`, 'data-side': side, style: `cursor:${side % 2 ? 'ns' : 'ew'}-resize` });
        const hit = s('line', { x1, y1, x2, y2, stroke: 'transparent', 'stroke-width': 14 / k, 'data-side': side, style: `cursor:${side % 2 ? 'ns' : 'ew'}-resize` });
        lines.push(ln, hit);
        ed.append(ln, hit);
      };
      add(il, 0, il, hh, 0);
      add(0, it, w, it, 1);
      add(w - ir, 0, w - ir, hh, 2);
      add(0, hh - ib, w, hh - ib, 3);
      demo.replaceChildren(...demoKeys(img));
      nums.forEach((n, i) => (n.value = img.insets[i]));
    };
    let drag = -1;
    ed.addEventListener('pointerdown', (ev) => { const sd = ev.target.dataset && ev.target.dataset.side; if (sd != null) { drag = +sd; ed.setPointerCapture(ev.pointerId); } });
    ed.addEventListener('pointermove', (ev) => {
      if (drag < 0) return;
      const r = ed.getBoundingClientRect();
      const x = Math.round((ev.clientX - r.left) / k);
      const y = Math.round((ev.clientY - r.top) / k);
      const ins = img.insets.slice();
      if (drag === 0) ins[0] = Math.max(0, Math.min(x, w - ins[2]));
      if (drag === 1) ins[1] = Math.max(0, Math.min(y, hh - ins[3]));
      if (drag === 2) ins[2] = Math.max(0, Math.min(w - x, w - ins[0]));
      if (drag === 3) ins[3] = Math.max(0, Math.min(hh - y, hh - ins[1]));
      img.insets = ins;
      draw();
      renderPreview();
    });
    ed.addEventListener('pointerup', () => { if (drag >= 0) { drag = -1; save(); } });
    const nums = ['左', '上', '右', '下'].map((label, i) => h('input', { type: 'number', min: 0, max: i % 2 ? hh : w, value: img.insets[i], 'aria-label': '九宫格 ' + label,
      onchange: (e) => {
        // as a drag keeps it: no further than the opposite line, so the middle never goes
        const ins = img.insets.slice();
        ins[i] = Math.max(0, Math.min(+e.target.value | 0, (i % 2 ? hh : w) - ins[(i + 2) % 4]));
        img.insets = ins;
        draw();
        changed();
      } }));
    const demo = h('div', { class: 'ninebox' });
    box.append(
      h('div', { class: 'row' }, h('button', { type: 'button', onclick: async () => { const n = await chooseKeyImage(); if (n) set(n); } }, '换一张…'),
        h('span', { class: 'hint' }, `${w}×${hh} px · ${Math.ceil(S.files[img.file].length / 1024)} KB`)),
      h('div', { class: 'ninebox' }, ed, demo),
      h('div', { class: 'row' }, h('span', { class: 'k' }, '九宫格'), ...nums.map((n, i) => h('label', {}, ['左', '上', '右', '下'][i], n))),
      h('p', { class: 'hint' }, '虚线外的四角和四边不拉伸，中间拉伸；四条都在边上就是整张拉伸。'),
      h('div', { class: 'row' }, h('span', { class: 'k' }, '清晰度'),
        h('select', { 'aria-label': '每 dp 几个像素', onchange: (e) => { img.pxPerDp = +e.target.value; draw(); changed(); } },
          Array.from({ length: LIM.pxPerDp }, (_, i) => h('option', { value: i + 1, selected: img.pxPerDp === i + 1 }, `${i + 1} 像素 = 1 dp`))),
        h('span', { class: 'hint' }, '四角按这个比例画；图越大选越大')));
    draw();
    return box;
  }
  function demoKeys(img) {
    const d = density();
    const size = S.sizes[img.file];
    return [[80, 44], [36, 44]].map(([w, hh]) => {
      const svg = s('svg', { width: w + 8, height: hh + 8, viewBox: `-4 -4 ${w + 8} ${hh + 8}` });
      if (size) for (const p of L.nineSlice(size[0], size[1], img.insets, img.pxPerDp, [0, 0, w * d, hh * d], d)) {
        const [sl, st, sr, sb] = p.src;
        const [dl, dt, dr, db] = p.dst;
        if (sr <= sl || sb <= st || dr <= dl || db <= dt) continue;
        svg.append(s('svg', { x: dl / d, y: dt / d, width: (dr - dl) / d, height: (db - dt) / d, viewBox: `${sl} ${st} ${sr - sl} ${sb - st}`, preserveAspectRatio: 'none' },
          s('image', { href: S.urls[img.file], width: size[0], height: size[1] })));
      }
      return svg;
    });
  }

  /** Where a selected key's override goes: its board's, or every board's for a function key's role when [S.allBoards]. */
  function targets() {
    const out = new Set();
    for (const n of S.selected) {
      const { key } = L.parseKeyName(n);
      out.add(S.allBoards && ROLES.includes(key) ? '*/' + key : n);
    }
    return [...out];
  }
  function keysSingle() {
    const K = S.theme.keys;
    const out = [];
    const tg = targets();
    if (!S.selected.length) {
      out.push(h('p', { class: 'hint' }, '在预览里点键选中；Shift+点多选；拖框框选；手机上长按进入多选。选中几个键一起配一张图时只存一份文件。'));
    } else {
      const roleSel = S.selected.some((n) => ROLES.includes(L.parseKeyName(n).key));
      out.push(h('div', { class: 'row' }, h('span', { class: 'k' }, '已选'), h('span', {}, S.selected.map(prettyName).join('、')),
        h('button', { type: 'button', onclick: () => { for (const t of tg) delete K.overrides[t]; changed({ settings: true }); } }, '清除这些键'),
        S.multi ? h('button', { type: 'button', onclick: () => { S.multi = false; renderSettings(); } }, '结束多选') : null));
      if (roleSel) {
        out.push(radios('where', [[true, '用在：所有键盘'], [false, '只在这个键盘']], S.allBoards, (x) => { S.allBoards = x; renderSettings(); }));
      }
      const first = K.overrides[tg[0]] || { fill: null, pressed: null, label: 'show' };
      const kindName = KIND_LABEL[(preview.view.els.find((e) => e.name === S.selected[0]) || {}).kind] || '它那一类';
      const write = (field, value) => {
        if (Object.keys(K.overrides).length + tg.filter((t) => !K.overrides[t]).length > LIM.overrides) return toast(`单独设置的键最多 ${LIM.overrides} 个`, true);
        for (const t of tg) {
          const o = K.overrides[t] || { fill: null, pressed: null, label: 'show' };
          o[field] = value && typeof value === 'object' ? clone(value) : value;
          if (!o.fill && !o.pressed && o.label === 'show') delete K.overrides[t];
          else K.overrides[t] = o;
        }
        changed({ settings: true });
      };
      out.push(h('h3', {}, '底'), fillEditor(first.fill, true, (f) => write('fill', f), 'single'));
      out.push(h('p', { class: 'hint' }, `「跟随」是${kindName}的样子；只给常态配了图时，按下仍按那一类。`));
      const seeKinds = (first.fill && first.fill.type === 'picture' && kindOfName(tg[0])) || [];
      if (seeKinds.length) {
        const words = seeKinds.map((k) => KIND_LABEL[k]).join('、');
        out.push(h('p', { class: 'hint' }, `盖在图上的是${words}的底色：改它，${words}都跟着变。`));
        for (const f of new Set(seeKinds.map(kindField))) out.push(colorRow(f));
      }
      out.push(h('h3', {}, '按下'), pressedEditor(first.pressed, true, (p) => write('pressed', p), 'single'));
      out.push(h('h3', {}, '键上的字'), radios('label', [['show', '显示'], ['hide', '隐藏（键角小字也隐藏）']], first.label, (x) => write('label', x)));
    }
    const names = Object.keys(K.overrides);
    out.push(h('h3', {}, `已单独设置的键（${names.length}）`));
    out.push(h('div', { class: 'chips' }, names.map((n) => {
      const known = L.isKnownKeyName(NAMES, n);
      return h('button', { type: 'button', class: 'chip' + (known ? '' : ' unknown'), title: known ? '点了跳过去' : '这个版本的网页不认识',
        onclick: () => jumpTo(n) }, n + (known ? '' : '（不认识）'));
    })));
    if (names.some((n) => !L.isKnownKeyName(NAMES, n))) out.push(h('p', { class: 'hint' }, '标「不认识」的键名来自更新的 App：照存、照导出，这个版本的网页不画它。'));
    out.push(budgetBars());
    return out;
  }
  function prettyName(n) {
    const { board, key } = L.parseKeyName(n);
    return (ROLE_LABEL[key] || key.toUpperCase()) + '（' + (BOARD_LABEL[board] || board) + '）';
  }
  function jumpTo(n) {
    const { board, key } = L.parseKeyName(n);
    if (!L.isKnownKeyName(NAMES, n)) return toast('这个版本的网页不认识这个键名');
    if (board === '*') {
      const v = currentView();
      const here = v.els.find((e) => e.name && L.parseKeyName(e.name).key === key);
      if (here) S.selected = [here.name];
      S.allBoards = true;
    } else {
      S.view.board = board;
      S.selected = [n];
      S.allBoards = false;
    }
    renderControls(controlsBox);
    renderPreview();
    renderSettings();
  }
  /** KeyImageBudget per board, the default phone's portrait boards: what the app decodes for them. */
  function boardBudgets() {
    const out = [];
    for (const v of views({ phone: LAYOUT.phones[0].id, land: false, mode: 'normal', state: 'idle' })) {
      const names = v.els.filter((e) => e.t === 'key' && e.name).map((e) => L.parseKeyName(e.name).key);
      const files = L.imagesOn(S.theme.keys, v.board, names, ROLES);
      const images = files.map((f) => ({ file: f, width: (S.sizes[f] || [1, 1])[0], height: (S.sizes[f] || [1, 1])[1] }));
      out.push({ board: v.board, files, images, bytes: L.budgetBytes(images) });
    }
    return out;
  }
  function budgetBars() {
    const rows = boardBudgets().filter((b) => b.bytes > 0);
    if (!rows.length) return null;
    const limit = LIM.boardImageBytes;
    return h('div', {}, h('h3', {}, '按键图片内存'), rows.map((b) => h('div', { class: 'row' },
      h('span', { class: 'k' }, BOARD_LABEL[b.board] || b.board),
      h('span', { class: 'meter' + (b.bytes > limit ? ' over' : '') }, h('i', { style: `width:${Math.min(100, (b.bytes / limit) * 100)}%` })),
      h('span', {}, `${mb(b.bytes)} / ${mb(limit)} MB`), b.bytes > limit ? h('span', { class: 'err' }, '导出时会缩小图片') : null)));
  }

  function textTab() {
    const T = S.theme.text;
    const fontRow = (field, label) => h('div', { class: 'row' }, h('span', { class: 'k' }, label),
      h('button', { type: 'button', onclick: () => chooseFont(field) }, T[field] ? '换字体文件…' : '选字体文件…'),
      T[field] ? h('span', { class: 'hint' }, `${(S.files[T[field]] ? S.files[T[field]].length / MB : 0).toFixed(1)} MB` + (S.fonts[T[field]] ? '' : ' · 浏览器读不了，预览用系统字体')) : h('span', { class: 'hint' }, '系统字体'),
      T[field] ? h('button', { type: 'button', onclick: () => { T[field] = null; changed({ settings: true }); } }, '用系统字体') : null);
    const weights = (field, label) => h('div', { class: 'row' }, h('span', { class: 'k' }, label),
      seg(LIM.weights.map((w) => [w, { 300: '细', 400: '常规', 700: '粗' }[w] || w]), T[field], (x) => { T[field] = x; changed({ settings: true }); }));
    const missing = ['keyFont', 'candidateFont'].some((f) => T[f] && !S.files[T[f]]);
    return [
      h('h3', {}, '字体'),
      fontRow('keyFont', '按键'),
      fontRow('candidateFont', '候选'),
      missing ? h('div', { class: 'warn' }, '字体文件不在这里（从浏览器存的进度恢复时不带文件）：重新选。') : null,
      h('p', { class: 'warn' }, '分享主题时字体文件也在里面，只用允许转发的字体（比如 SIL OFL 授权的）。'),
      h('h3', {}, '粗细'),
      weights('keyWeight', '按键'),
      weights('candidateWeight', '候选'),
      h('h3', {}, '按键字大小'),
      slider('大小', T.keyScale, LIM.keyScale[0], LIM.keyScale[1], ' %', (x) => { T.keyScale = x; changed(); }),
    ];
  }
  async function chooseFont(field) {
    const [file] = await pickFile('.ttf,.otf,font/ttf,font/otf');
    if (!file) return;
    if (file.size > LIM.fileBytes) return toast(`字体文件超过 ${mbWords(LIM.fileBytes)}，App 不收`, true);
    const bytes = await readBytes(file);
    if (!L.fontType(bytes)) return toast('字体文件读不了：只收 .ttf / .otf（不收 .ttc 合集）', true);
    S.theme.text[field] = await addFile(field === 'keyFont' ? 'font' : 'candidates', bytes);
    changed({ settings: true });
    if (bytes.length > FONT_WARN_BYTES) toast(`字体超过 ${mbWords(FONT_WARN_BYTES)}：主题文件会很大`);
  }

  function barTab() {
    const T = S.theme;
    const out = [h('h3', {}, '候选栏的底'),
      radios('bar', [['transparent', '透明（看到键盘底）'], ['color', '颜色'], ['picture', '透出主题图']], T.bar.type, (x) => {
        if (x === 'picture' && !T.picture) { toast('先在「主题图」里选一张图'); return renderSettings(); }
        T.bar = x === 'picture' ? { type: 'picture', blurDp: 0, brightness: T.picture.brightness } : { type: x };
        changed({ settings: true });
      })];
    if (T.bar.type === 'picture') {
      out.push(slider('模糊', T.bar.blurDp, 0, LIM.blurDp, ' dp', (x) => { T.bar.blurDp = x; changed(); }));
      out.push(slider('亮度', T.bar.brightness, 0, 100, ' %', (x) => { T.bar.brightness = x; changed(); }));
    }
    out.push(h('h3', {}, '颜色'));
    for (const f of ['barColor', 'candidateTextColor', 'candidateLabelColor', 'candidateCommentColor', 'dividerColor', 'toolbarIconColor']) out.push(colorRow(f));
    out.push(h('div', { class: 'row' }, h('button', { type: 'button', onclick: () => { S.view.state = 'typing'; renderControls(controlsBox); renderPreview(); } }, '预览打字中')));
    return out;
  }
  function popupTab() {
    const out = [h('h3', {}, '弹窗颜色')];
    for (const f of ['popupBackgroundColor', 'popupTextColor', 'genericActiveBackgroundColor', 'genericActiveForegroundColor', 'clipboardEntryColor']) out.push(colorRow(f));
    out.push(h('div', { class: 'row' },
      h('button', { type: 'button', onclick: () => { Object.assign(S.view, { board: 'qwerty', mode: 'normal', state: 'longpress' }); renderControls(controlsBox); renderPreview(); } }, '预览长按'),
      h('button', { type: 'button', onclick: () => { Object.assign(S.view, { board: 'qwerty', mode: 'normal', state: 'clipboard' }); renderControls(controlsBox); renderPreview(); } }, '预览剪贴板')));
    return out;
  }

  // ---- open, new, dark copy, export ----
  /** A theme zip's bytes opened as the page's theme: whether it was migrated. */
  async function openZipBytes(bytes) {
    const { theme, migrated, files } = await L.openZip(FORMAT, bytes);
    await setTheme(theme, files);
    renderAll();
    return migrated;
  }
  async function openZipFile() {
    // no accept: iOS greys out a suffix it has no type for, so the check is after the pick, as the app's
    const [file] = await pickFile('');
    if (!file) return;
    if (!/\.theme$/i.test(file.name)) return toast('只能打开 .theme 主题文件；以前导出的 .zip 改名成 .theme 再打开', true);
    try {
      const migrated = await openZipBytes(await readBytes(file));
      toast(migrated ? '这是老版本 App 导出的主题，已按新格式读进来；导出后在 App 里导入会替换原来那个' : '已打开；导出后在 App 里导入会替换原来那个主题');
    } catch (e) {
      if (e instanceof L.ThemeError) toast(themeErrorText(e), true);
      else toast(ERR['not-theme'], true);
    }
  }
  async function newFrom(p) {
    await setTheme(presetTheme(p), {});
    renderAll();
  }
  async function darkCopy() {
    const T = clone(S.theme);
    const old = T.name;
    const name = uuid();
    // its own file names: the app deletes a theme's files with it, so two themes never share one
    const names = new Map();
    const files = Object.create(null);
    const p = T.picture;
    for (const f of [...L.lookFiles(T), p && p.src].filter(Boolean)) {
      names.set(f, f.startsWith(old + '-') ? name + f.slice(old.length) : name + '-' + f);
      if (Object.hasOwn(S.files, f)) files[names.get(f)] = S.files[f];
    }
    const mapped = L.mapFiles(T, (f) => names.get(f) || f);
    mapped.name = name;
    // isDark by the new background: a dark theme's copy is a light one; the shadow and border stay black
    const dark = L.darkPalette(mapped.palette, S.manual);
    mapped.palette = dark.palette;
    mapped.isDark = dark.isDark;
    const see = { ...S.see }; // the alphas to give back: invertLightness keeps an alpha as it is
    // what follows is worked out again for the turned colours (setTheme): the source's list counted its own
    await setTheme(mapped, files);
    S.see = see;
    S.autoDark = false;
    renderAll();
    toast(`已复制成${mapped.isDark ? '深' : '浅'}色版（新主题，颜色明暗翻过来）：导出后在 App 里是另一个主题`);
  }
  /**
   * Key images as the app wants them before a zip is written: at most 3 × the largest key's dp
   * that draws them (pxPerDp lowered with it, corners the same size), and every board's within
   * KeyImageBudget; the page's theme changes with it, so what it shows is what it exports.
   */
  async function fitKeyImages() {
    const T = S.theme;
    const largest = Object.create(null);
    for (const v of LAYOUT.views) {
      if (v.phone !== LAYOUT.phones[0].id) continue; // dp sizes: one phone's are enough
      for (const e of v.els) {
        if (e.t !== 'key') continue;
        const pk = e.name ? L.parseKeyName(e.name) : null;
        const st = L.resolveStyle(T.keys, pk && pk.board, pk && pk.key, e.kind, ROLES);
        for (const x of [st.fill, st.pressed]) {
          if (!x || x.type !== 'image') continue;
          const w = e.cell[2] - e.cell[0], hh = e.cell[3] - e.cell[1];
          const m = largest[x.file] || [0, 0];
          largest[x.file] = [Math.max(m[0], w), Math.max(m[1], hh)];
        }
      }
    }
    let changedCount = 0;
    // fit: L.fitKeyImage's {scale, pxPerDp, insets}
    const rescale = async (img, fit) => {
      const bm = S.bitmaps[img.file];
      if (!bm) return null;
      const c = canvasOf(Math.max(1, Math.floor(bm.width * fit.scale)), Math.max(1, Math.floor(bm.height * fit.scale)));
      const g = c.getContext('2d');
      g.imageSmoothingQuality = 'high';
      g.drawImage(bm, 0, 0, c.width, c.height);
      const file = await addFile('key', await pngOf(c), L.decodeCaps(FORMAT, 'key'));
      return { type: img.type, file, insets: fit.insets, pxPerDp: fit.pxPerDp };
    };
    const replace = async (want) => {
      const done = new Map();
      const each = async (x) => {
        if (!x || x.type !== 'image') return x;
        const fit = want(x);
        if (!fit) return x;
        const key = JSON.stringify([x.file, x.pxPerDp, x.insets, fit]);
        if (!done.has(key)) done.set(key, await rescale(x, fit));
        if (done.get(key)) { changedCount++; return done.get(key); }
        return x;
      };
      for (const k of KINDS) { T.keys[k].fill = await each(T.keys[k].fill); T.keys[k].pressed = await each(T.keys[k].pressed); }
      for (const o of Object.values(T.keys.overrides)) { o.fill = await each(o.fill); o.pressed = await each(o.pressed); }
    };
    await replace((x) => {
      const m = largest[x.file];
      const size = S.sizes[x.file];
      return m && size ? L.fitKeyImage(size, m, x) : null;
    });
    for (let round = 0; round < 4; round++) {
      const over = boardBudgets().filter((b) => b.bytes > LIM.boardImageBytes);
      if (!over.length) break;
      const files = new Set(over.flatMap((b) => b.files));
      await replace((x) => {
        if (!files.has(x.file) || x.pxPerDp <= 1) return null;
        const p2 = Math.max(1, Math.floor(x.pxPerDp / 2));
        const scale = p2 / x.pxPerDp;
        return { scale, pxPerDp: p2, insets: x.insets.map((v) => Math.floor(v * scale)) };
      });
    }
    return changedCount;
  }
  async function exportTheme() {
    const T = S.theme;
    try {
      const shrunk = await fitKeyImages();
      L.readTheme(FORMAT, L.writeTheme(FORMAT, T)); // what the app would refuse, refused here first
      // the app refuses a theme named as a built-in one (a zip opened here may be)
      if (PRESETS.some((p) => p.name === T.name)) return toast('这个主题和内置主题同名，App 不收：先「' + copyLabel() + '」或新建一个再导出', true);
      const missing = L.lookFiles(T).filter((f) => !S.files[f]);
      if (missing.length) return toast('主题里用到的文件不在这里（从浏览器存的进度恢复时不带图片和字体）：重新选 ' + missing.length + ' 个文件', true);
      const breaches = L.limitBreaches(FORMAT, T, S.files, S.sizes);
      if (breaches.length) return toast('App 不会收这个主题：' + breaches.map((b) => LIMIT_WORDS[b.what] + '（' + b.detail + '）').join('；'), true);
      const over = boardBudgets().filter((b) => b.bytes > LIM.boardImageBytes);
      const zip = L.exportZip(FORMAT, T, S.files);
      const date = new Date();
      const stamp = date.getFullYear() + String(date.getMonth() + 1).padStart(2, '0') + String(date.getDate()).padStart(2, '0');
      const a = h('a', { href: URL.createObjectURL(new Blob([zip], { type: 'application/octet-stream' })), download: `youmo-theme-${stamp}.theme` });
      document.body.append(a);
      a.click();
      setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
      changed({ settings: true });
      toast((shrunk ? `按键图片缩小了 ${shrunk} 处（App 只用到这么大）。` : '') + (over.length ? `有键盘的按键图片仍超过 ${mbWords(LIM.boardImageBytes)}，App 会糊一点地画。` : '') +
        '在手机上：设置 › 外观 › 主题 › 新增主题 › 导入文件…，选这个 .theme 文件。');
    } catch (e) {
      toast(e instanceof L.ThemeError ? themeErrorText(e) : '导出失败：' + e.message, true);
    }
  }

  // ---- the page ----
  let controlsBox = null;
  let copyButton = null;
  // a dark theme's copy is a light one
  const copyLabel = () => `复制成${S.theme.isDark ? '浅' : '深'}色版`;
  let summaryBox = null;
  function renderAll() {
    if (copyButton) copyButton.textContent = copyLabel();
    renderControls(controlsBox);
    renderPreview();
    renderSettings();
    save();
  }
  function build() {
    const menu = h('select', { 'aria-label': '新建：从内置主题开始', onchange: async (e) => {
      const p = PRESETS.find((x) => x.name === e.target.value);
      e.target.value = '';
      if (p) await newFrom(p);
    } }, h('option', { value: '' }, '新建 ▾'), PRESETS.map((p) => h('option', { value: p.name }, PRESET_LABEL[p.name] || p.name)));
    const header = h('header', { class: 'top' }, h('h1', {}, '幽默输入法 · 主题制作'), h('div', { class: 'actions' },
      h('button', { type: 'button', onclick: openZipFile }, '打开主题'), menu,
      copyButton = h('button', { type: 'button', onclick: darkCopy }, copyLabel()),
      h('button', { type: 'button', class: 'primary', onclick: exportTheme }, '导出主题')));
    controlsBox = h('div', { class: 'controls' });
    summaryBox = h('summary', {});
    // on a phone the view's options fold away, so the keyboard and the settings both fit
    const options = h('details', { class: 'view-opts', open: !window.matchMedia('(max-width: 900px)').matches }, summaryBox, controlsBox);
    preview.stage = h('div', { class: 'stage' });
    preview.note = h('div', { class: 'stage-note' });
    tabsBox = h('div', { class: 'tabs', role: 'tablist' });
    settingsBox = h('div', { role: 'tabpanel' });
    document.body.append(header,
      h('div', { class: 'privacy' }, '图片和颜色都只在你的浏览器里处理，不会上传。'),
      h('main', {}, h('section', { class: 'preview-col', 'aria-label': '预览' }, options, preview.stage, preview.note),
        h('section', { class: 'settings-col', 'aria-label': '设置' }, tabsBox, settingsBox)));
    fitPreview(document.querySelector('.preview-col'), options);
    bindPreview(preview.stage);
    // a long press on the keys selects them: not the system's menu for the picture under them
    preview.stage.addEventListener('contextmenu', (ev) => ev.preventDefault());
    // the save waits for a pause: the page closed first, it is made now
    window.addEventListener('pagehide', save);
    let resizeTimer = 0;
    window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(renderPreview, 100); });
  }

  /**
   * The preview kept in view as the settings scroll (sticky) only while it fits: the whole screen
   * wide, about half of it on a phone, where the settings are under it. Taller (its options open, a
   * landscape view), it goes by with the page: a column scrolling in itself fought the page's scroll.
   */
  function fitPreview(col, options) {
    const fit = () => {
      const room = window.matchMedia('(max-width: 900px)').matches ? window.innerHeight * 0.55 : window.innerHeight;
      col.classList.toggle('stuck', col.offsetHeight <= room);
    };
    new ResizeObserver(fit).observe(col);
    window.addEventListener('resize', fit);
    options.addEventListener('toggle', fit);
    fit();
  }

  // #board=nine&land=1&tab=keys&keys=single…: a view to open at (a link, a screenshot); what a link (#tab=…) or a saved view may set: only these, else a typo leaves the page blank
  const CHOICES = {
    phone: LAYOUT.phones.map((p) => p.id),
    board: [...new Set(LAYOUT.views.map((v) => v.board))],
    mode: [...new Set(LAYOUT.views.map((v) => v.mode))],
    state: [...new Set(LAYOUT.views.map((v) => v.state))],
    sys: ['light', 'dark'],
    tab: TABS.map((t) => t[0]),
    keys: ['all', 'kinds', 'single'],
    kind: KINDS,
    colors: ['simple', 'all'],
  };
  const chosen = (k, v) => (typeof v === 'string' && CHOICES[k].includes(v) ? v : null);
  function setView(view) {
    for (const k of ['phone', 'board', 'mode', 'state', 'sys']) if (view && chosen(k, view[k])) S.view[k] = view[k];
    if (view && typeof view.land === 'boolean') S.view.land = view.land;
  }
  function fromHash(restored) {
    const q = new URLSearchParams(location.hash.slice(1));
    setView(Object.fromEntries(['phone', 'board', 'mode', 'state', 'sys'].map((k) => [k, q.get(k)])));
    if (q.get('land')) S.view.land = q.get('land') === '1';
    if (chosen('tab', q.get('tab'))) S.tab = q.get('tab');
    if (chosen('keys', q.get('keys'))) S.keyTab = q.get('keys');
    if (chosen('kind', q.get('kind'))) S.kind = q.get('kind');
    if (chosen('colors', q.get('colors'))) S.colorView = q.get('colors');
    // only names the layout has (one keys may be selected by): another would leave the settings blank
    const known = (n) => { try { return !n.startsWith('*/') && L.isKnownKeyName(NAMES, n); } catch (e) { return false; } };
    if (q.get('select')) S.selected = q.get('select').split(',').filter(known);
    const p = q.get('preset') && PRESETS.find((x) => x.name === q.get('preset'));
    // over work brought back from the browser: only if the user says so
    if (p && (!restored || confirm('链接要打开内置主题「' + (PRESET_LABEL[p.name] || p.name) + '」，浏览器里存的进度会被替换。打开吗？'))) resetFollowing(presetTheme(p));
  }

  async function start() {
    const restored = restore();
    if (!restored) {
      const p = PRESETS.find((x) => x.name === 'PixelLight') || PRESETS[0];
      S.theme = p ? presetTheme(p) : L.defaultLook(FORMAT, uuid(), false, L.deriveSimple({ bg: -1118482, key: -1, text: -14606047, fn: -2039584, accent: -12417548 }, false), null);
    }
    fromHash(restored);
    if (S.view.sys) document.documentElement.dataset.theme = S.view.sys;
    build();
    renderAll();
  }
  // for the page's own checks (screenshots, a console): the state and the actions
  window.YoumoUI = { S, setTheme, renderAll, exportTheme, fitKeyImages, openZipBytes };
  start();
})();
