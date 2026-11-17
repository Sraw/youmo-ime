// youmo-layout-maker: what the layout maker's page does to a .layout (061-custom-layout §12), apart from the page so node
// tests it. The page never reads or lays out a layout itself: each change is the engine's to read again (readOwnLayout),
// check (checkLayout) and draw (buildScene). Here: a new layout from the app's boards, the file opened and saved, the
// checker's findings in words, the preview's environment as the engine's EnvJson.
(function (root) {
  'use strict';

  const F = root.YoumoForm;
  const M = root.YoumoMaker;
  if (!F || !M) throw new Error('youmo-layout-maker needs youmo-form and youmo-maker loaded first');
  const { escape } = F;

  const BOM = '\uFEFF';
  const ID = /^[a-z0-9]+(-[a-z0-9]+)*$/;

  /** The app's boards one starts from (LayoutCheck.BASES), as the 新建 dialog names them. */
  const BASE_LABEL = { qwerty: '26 键', k14: '14 键', k18: '18 键', nine: '九键', number: '数字键盘', symbols: '符号面板' };
  const FAMILY_LABEL = { letters: '字母', grouped: '字母分组', nine: '九键', number: '数字', symbols: '符号' };
  /** The number pads (LayoutReader.PADS): what a field asks for, the pad shown. */
  const PAD_LABEL = {
    full: '完整', digits: '只有数字', signed: '数字和正负号', decimal: '数字和小数点', 'signed-decimal': '正负号和小数点',
    phone: '电话', datetime: '日期时间',
  };
  const ROLE_LABEL = { symbols: '符', back: '返回', numbers: '123' };
  const BOARD_LABEL = { Number: '数字键盘', Symbol: '符号面板' };
  /** The app's boards a key's name is of (LayoutCheck's held), as the app's key page names them. */
  const APP_BOARD_LABEL = {
    qwerty: '全键盘', k14: '14 键', k18: '18 键', nine: '九键', zhuyin: '注音全键盘', 'zhuyin-nine': '注音九键', number: '数字键盘', symbols: '符号面板',
    emoji: '表情', emoticon: '颜文字', 'emoji-search': '表情搜索', 'expanded-side': '展开候选', 'number-row': '数字行',
  };

  // ---- the checker's findings in words (LayoutCheck.KEYS: the test holds the table to the engine's list) ----

  const q = (s) => '「' + s + '」';
  const WORDS = {
    // errors
    read: ([msg]) => '读不进来：' + msg,
    newer: ([v]) => '需要幽默输入法 ' + v + ' 或更新（min-app）',
    'id-length': ([id, max]) => 'id' + q(id) + '太长：最多 ' + max + ' 个字符',
    'id-built-in': ([id]) => 'id' + q(id) + '和 App 自带的键盘或片段重名，换一个',
    'id-prefix': ([id]) => 'id' + q(id) + '是 App 按键名的前缀，换一个',
    'name-length': ([, max]) => '名字（name）要 1–' + max + ' 个字符',
    family: ([v]) => 'family' + q(v) + '不认：只能是 letters、grouped、nine、number、symbols',
    'based-on': ([v]) => (v ? 'based-on' + q(v) + '不认：只能是 qwerty、k14、k18、nine、number、symbols' : '缺 based-on：从哪块内置键盘改来'),
    'too-big': ([bytes, max]) => '文件 ' + bytes + ' 字节，超过 ' + Math.round(max / 1024) + ' KB',
    'too-many-keys': ([n, max]) => '键太多：' + n + ' 个，最多 ' + max,
    'commit-long': ([max]) => '点按打出的文字太长：一个最多 ' + max + ' 个字符',
    'too-many-commits': ([n, max]) => '打出一段文字的动作太多：' + n + ' 个，最多 ' + max,
    'label-long': ([key, max]) => '键' + q(key) + '显示的字太长：最多 ' + max + ' 个字符',
    include: ([src]) => 'App 没有叫' + q(src) + '的片段（include 只能引用 App 自带的）',
    'layout-target': ([action]) => q(action) + '切换到的面板 App 没有',
    base: ([why]) => '和底稿（based-on）对不上：' + why,
    scene: ([where, msg]) => '在「' + where + '」排不出来：' + msg,
    blank: ([key, where]) => '键' + q(key) + '在「' + where + '」什么都不显示（没有字也没有图标）',
    'name-no-slash': ([name]) => '按键名' + q(name) + '不是 App 的：新键的名字写成「布局 id/键 id」',
    'name-prefix': ([name]) => '按键名' + q(name) + '用了 App 的前缀，App 却没有这个键',
    lacks: ([what]) => '少了必需的键：' + what,
    groups: ([why]) => '字母分组不对：' + why,
    'hidden-chars': ([field]) => field + '里有看不见的字符（控制字符、方向字符、零宽字符或换行）',
    command: ([key, action]) => '键' + q(key) + '的 ' + action + ' 是命令：字母键和空格只能用底稿同一个键上有的命令',
    // warnings
    'no-way': (boards) => '没有键能切到' + boards.map((b) => BOARD_LABEL[b] || b).join('、'),
    narrow: ([key]) => '键' + q(key) + '很窄，可能不好按',
    commits: ([n]) => n + ' 个键点按打出写死的文字（导入时 App 会列给用户看）',
    'theme-blank': ([where]) => '预览主题下这个键什么都不显示：' + where,
    unnamed: ([key]) => '键' + q(key) + '没有按键名（shortcut=）：用户在 App 里改不了它的长按、上下滑',
    held: ([key, board]) => key + ' 是' + (APP_BOARD_LABEL[board] || board) + '的键，这里写的长按、角标、滑动不起作用',
    'held-letters': ([key, board]) => key + ' 是' + (APP_BOARD_LABEL[board] || board) + '的键，长按跟它走，不按这里写的字母；要用这些字母，给它起个自己的键名',
    'held-popups': ([board]) => '这里写的 popups= 不起作用，跟' + (APP_BOARD_LABEL[board] || board) + '一样',
    'gap-look': () => '空位（gap）的 look=、class= 不起作用',
    'pad-lacks': ([pad, keys]) => '数字键盘「' + (PAD_LABEL[pad] || pad) + '」缺 ' + keys + '：这种输入框里打不出来',
    'bottom-left': ([want]) => '左下角通常是 ' + want.split(' ').map((r) => ROLE_LABEL[r] || r).join('、') + '（和 App 自带的一样）',
  };

  /** A finding of checkLayout (`{line, key, args}`) in words; one of a key the table lacks, as the engine has it. */
  function words(f) {
    const say = WORDS[f.key];
    return say ? say(Array.from(f.args || [])) : f.key + (f.args && f.args.length ? '：' + Array.from(f.args).join(' ') : '');
  }

  /** A report's findings as the page lists them: `[{level, line, message}]`, errors first. */
  function findings(report) {
    const out = [];
    for (const f of report.errors || []) out.push({ level: 'error', line: f.line || 0, message: words(f) });
    for (const f of report.warnings || []) out.push({ level: 'warning', line: f.line || 0, message: words(f) });
    return out;
  }

  // ---- the <keyboard> and its <meta> ----

  const keyboardOf = (xml) => F.elements(xml, 'keyboard')[0] || null;

  /** The <keyboard>'s id, name, family, based-on and <meta>'s attributes; null where there is no <keyboard>. */
  function info(xml) {
    const k = keyboardOf(xml);
    if (!k) return null;
    const a = Object.fromEntries(k.attrs);
    const m = F.elements(xml, 'meta')[0];
    return { id: a.id ?? null, name: a.name ?? null, family: a.family ?? null, basedOn: a['based-on'] ?? null, meta: m ? Object.fromEntries(m.attrs) : {} };
  }

  // a value written between double quotes: ' escaped as well, so no reader takes part of it for a quote of its own
  const attrValue = (v) => escape(v).replace(/'/g, '&apos;');

  // [tag] with attribute [name] [value] (null: gone): changed in place, else added at its end. Its attributes gone
  // through one by one, each value whole: a name="a based-on='x'" is a name, never a based-on (a pattern looked for
  // anywhere in the tag found the one inside the value and wrote over it)
  function withAttr(tag, name, value) {
    for (const m of tag.matchAll(/(\s+)([\w:.-]+)\s*=\s*("[^"]*"|'[^']*')/g)) {
      if (m[2] !== name) continue;
      const rest = tag.slice(m.index + m[0].length);
      return tag.slice(0, m.index) + (value == null ? '' : m[1] + name + '="' + attrValue(value) + '"') + rest;
    }
    if (value == null) return tag;
    return tag.replace(/\s*(\/?>)$/, (all, close) => ' ' + name + '="' + attrValue(value) + '"' + close);
  }

  /** [xml] with its <keyboard>'s [name] [value] (null: taken out); as it was where there is no <keyboard>. */
  function setKeyboard(xml, name, value) {
    const k = keyboardOf(xml);
    if (!k) return xml;
    return xml.slice(0, k.start) + withAttr(k.tag, name, value) + xml.slice(k.start + k.tag.length);
  }

  /**
   * [xml] with <meta>'s [name] [value] (null or empty: taken out): a <meta/> made first in <keyboard> where there is
   * none (LayoutWriter puts it there), one left with nothing taken out.
   */
  function setMeta(xml, name, value) {
    if (value === '') value = null;
    let m = F.elements(xml, 'meta')[0];
    if (!m) {
      if (value == null) return xml;
      const k = keyboardOf(xml);
      if (!k || /\/>$/.test(k.tag)) return xml;
      const at = k.start + k.tag.length;
      xml = xml.slice(0, at) + '\n  <meta/>' + xml.slice(at);
      m = F.elements(xml, 'meta')[0];
    }
    const tag = withAttr(m.tag, name, value);
    if (/^<meta\s*\/>$/.test(tag)) {
      // the line it was on goes with it
      const before = xml.slice(0, m.start).replace(/\n[ \t]*$/, '');
      return before + xml.slice(m.end);
    }
    return xml.slice(0, m.start) + tag + xml.slice(m.start + m.tag.length);
  }

  // ---- 新建 ----

  /**
   * What is wrong with [id] as a new layout's (§2.2), null for nothing: its form, its length, the app's boards and
   * fragments and key-name prefixes ([format]: the engine's layoutFormat, its `builtIn` the ids the checker refuses).
   * The checker says the same of a file; this is the 新建 dialog's, before there is one.
   */
  function idProblem(id, format) {
    if (!ID.test(id)) return 'id 只能用小写字母、数字和连字符（如 my-k17）';
    if (id.length > format.limits.id) return 'id 最多 ' + format.limits.id + ' 个字符';
    if (format.builtIn.includes(id)) return 'App 自带的键盘或片段叫 ' + id + '，换一个';
    if (format.prefixes.includes(id)) return id + ' 是 App 按键名的前缀，换一个';
    return null;
  }
  function nameProblem(name, format) {
    const n = name.trim();
    return n.length && n.length <= format.limits.name ? null : '名字要 1–' + format.limits.name + ' 个字符';
  }

  /**
   * The id and name a layout from [base] (or an example, `{id, label}`) is offered first, each within [format]'s limits
   * (an example's long id cut, no hyphen left at its end).
   */
  function suggested(base, format) {
    const from = typeof base === 'string' ? { id: base, label: BASE_LABEL[base] } : base;
    return {
      id: ('my-' + from.id).slice(0, format.limits.id).replace(/-+$/, ''),
      name: ('我的' + from.label).slice(0, format.limits.name),
    };
  }

  /**
   * A new layout from the app's board [base] (§12.4 从内置键盘开始): the board as LayoutWriter writes it (its includes
   * kept: the app's bottom row followed when it changes), under [id] and [name], `based-on` [base]. Its family the board's.
   */
  function fromBuiltin(E, base, id, name) {
    const l = E.layout(base);
    if (!l) throw new Error('no built-in layout ' + base);
    let xml = E.writeLayout(l);
    xml = setKeyboard(xml, 'id', id);
    xml = setKeyboard(xml, 'name', name.trim());
    xml = setKeyboard(xml, 'based-on', base);
    return xml;
  }

  /**
   * A new layout from an example (§12.4 从示例开始; [example]: `{id, xml}` of examples/layouts/) under [id] and [name]: the
   * example as LayoutWriter writes it (its notes gone), its `<meta>` gone (the author's and the version are the example's,
   * not the new layout's), its own keys' names (`<example id>/<key>`, §9) the new id's.
   */
  function fromExample(E, example, id, name) {
    const r = E.readOwnLayout(example.xml);
    if (!r.ok) throw new Error('example ' + example.id + ' unread');
    let xml = E.writeLayout(r.layout);
    const meta = F.elements(xml, 'meta')[0];
    if (meta) xml = xml.slice(0, meta.start).replace(/\n[ \t]*$/, '') + xml.slice(meta.end);
    xml = xml.split(' shortcut="' + example.id + '/').join(' shortcut="' + id + '/');
    xml = setKeyboard(xml, 'id', id);
    xml = setKeyboard(xml, 'name', name.trim());
    return xml;
  }

  // ---- 打开、保存 ----

  /**
   * A file's bytes as a layout's text: UTF-8 (its byte-order mark gone, its line ends \n), within the app's [max] bytes (§2.6); throws
   * with what the page says otherwise. A zip (a .theme) is said to be one. [size]: the file's, where [bytes] is only
   * its start (a file far past the limit is not read whole to be refused).
   */
  function openText(bytes, max, size = bytes.length) {
    if (bytes.length >= 2 && bytes[0] === 0x50 && bytes[1] === 0x4b) throw new Error('这是主题包（.theme）：这里打开的是 .layout 布局文件');
    if (size > max) throw new Error('文件 ' + size + ' 字节，布局最大 ' + Math.round(max / 1024) + ' KB');
    let text;
    try {
      text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    } catch (e) {
      throw new Error('不是 UTF-8 文本');
    }
    if (text.startsWith(BOM)) text = text.slice(1);
    // Windows' line ends as the page's: the lines it adds (a <meta>) are \n, a textarea gives \n back
    text = text.replace(/\r\n?/g, '\n');
    if (!keyboardOf(text)) throw new Error('不是布局文件：没有 <keyboard>');
    return text;
  }

  /** A layout's file name: its name (what the file system refuses taken out), `.layout`. */
  function fileName(xml) {
    const i = info(xml);
    const base = ((i && (i.name || i.id)) || 'layout').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim() || 'layout';
    return base + '.layout';
  }

  /**
   * What 保存 does with [xml] (§12.4): checked as the app checks an import, for [version]; `{errors, warnings, xml}`,
   * [xml] with `min-app` the checker worked out (the newest feature it uses), errors meaning not saved. The text with
   * min-app written is checked again and given only where it passes: what is downloaded is what was checked. [theme]:
   * the preview's, for the keys it leaves blank.
   */
  function saveCheck(E, xml, version, theme) {
    const said = (report, prefix = '') => {
      const all = findings(report).map((p) => Object.assign(p, { message: prefix + p.message }));
      return { errors: all.filter((p) => p.level === 'error'), warnings: all.filter((p) => p.level === 'warning') };
    };
    const report = E.checkLayout(xml, version, theme || null);
    const { errors, warnings } = said(report);
    if (errors.length) return { errors, warnings, xml };
    const out = setMeta(xml, 'min-app', report.minApp);
    if (out === xml) return { errors, warnings, xml };
    const again = said(E.checkLayout(out, version, theme || null), '写进 min-app 以后：');
    return again.errors.length ? { errors: again.errors, warnings: again.warnings, xml } : { errors, warnings, xml: out };
  }

  // ---- the preview's environment (§12.2 环境栏) ----

  /** The input methods offered: each as the engine's Input (`{case:}`, `scheme=`, `scheme-types=`), LayoutCheck's set. */
  const IMES = [
    { id: 'pinyin', label: '拼音', input: { ime: 'pinyin' } },
    { id: 'shuangpin', label: '双拼（自然码）', input: { ime: 'shuangpin', scheme: 'Ziranma' } },
    { id: 'shuangpin-xiaohe', label: '双拼（小鹤）', input: { ime: 'shuangpin', scheme: 'Xiaohe' } },
    { id: 'shuangpin-ms', label: '双拼（微软）', input: { ime: 'shuangpin', scheme: 'MS', schemeTypes: ['semicolon'] } },
    { id: 'english', label: '英文', input: { ime: 'english' } },
  ];
  /** The fields a letters board is previewed in (LayoutCheck's); a number board's are its pads. */
  const FIELDS = [['text', '普通'], ['url', '网址'], ['email', '邮箱']];
  const SHIFTS = [['none', '关'], ['once', '一次'], ['lock', '锁定']];
  const FORMS = [['normal', '普通'], ['compact', '紧凑'], ['floating', '悬浮'], ['left', '单手（左）'], ['right', '单手（右）'], ['split', '分体']];
  /** Where the up and down swipes' marks go (EnvJson.AT): '' the app's default. */
  const AT = [['', '默认'], ['top-right', '右上'], ['bottom-right', '右下'], ['top-left', '左上'], ['bottom-left', '左下'], ['top', '顶部'], ['bottom', '底部'], ['none', '不显示']];

  const ENV = {
    device: 'oneplus-6t', w: 1080, h: 2340, density: 2.625, turned: false, form: 'normal', ime: 'pinyin', shift: 'none',
    field: 'text', pad: 'full', upAt: '', downAt: '', theme: 'material', dark: false,
  };

  /**
   * A draft's environment [kept] as the bar offers it: each choice one of the bar's (the device's size its own, the
   * theme one of [themes], the pad one of [pads]), else the default's: a draft of another page, or another app's
   * themes, still draws.
   */
  function restoreEnv(kept, themes, pads) {
    const k = kept && typeof kept === 'object' ? kept : {};
    const e = Object.assign({}, ENV);
    const dev = M.DEVICES.find((d) => d.id === k.device);
    if (dev) Object.assign(e, { device: dev.id, w: dev.w, h: dev.h, density: dev.density });
    const one = (key, list) => { if (list.includes(k[key])) e[key] = k[key]; };
    const first = (list) => list.map((x) => x[0]);
    one('form', first(FORMS));
    one('ime', IMES.map((x) => x.id));
    one('shift', first(SHIFTS));
    one('field', first(FIELDS));
    one('pad', pads);
    one('upAt', first(AT));
    one('downAt', first(AT));
    one('theme', themes);
    for (const key of ['turned', 'dark']) if (typeof k[key] === 'boolean') e[key] = k[key];
    return e;
  }

  /**
   * The window [e] is drawn in: its width across in dp, 横屏 or not as the app has it (ScreenShape: turned, or 600 dp
   * wide), and the keyboard's px. Held as it is shown, never a 横屏 the device would not lay out (§12.2).
   */
  function shape(E, e) {
    const across = e.turned ? Math.max(e.w, e.h) : Math.min(e.w, e.h);
    const widthDp = Math.round(across / e.density);
    const landscape = !!E.screenLandscape(!!e.turned, widthDp);
    const dp = !landscape ? M.KEYBOARD_DP.portrait : Math.min(e.w, e.h) / e.density >= 600 ? M.KEYBOARD_DP.landscapeLarge : M.KEYBOARD_DP.landscape;
    return { widthDp, landscape, width: across, height: Math.round(dp * e.density) };
  }

  /**
   * [e] as the engine's EnvJson for a layout of [family] ([layout]: what readOwnLayout gave, for its default marks):
   * the window's shape, the form, the input method and field (a number board: the field a number's, its pad [e]'s),
   * Chinese punctuation but for English (as on a new install), the marks a new install has with [e]'s places for them.
   */
  function envJson(E, e, family, layout) {
    const s = shape(E, e);
    const out = { width: s.width, height: s.height, density: e.density };
    if (s.landscape) out.landscape = true;
    if (e.dark) out.dark = true;
    if (e.shift && e.shift !== 'none') out.shift = e.shift;
    if (e.form === 'compact') out.compact = true;
    else if (e.form === 'floating') out.floating = 1;
    else if (e.form === 'left' || e.form === 'right') out.oneHanded = e.form;
    else if (e.form === 'split') out.split = true;
    const ime = (IMES.find((x) => x.id === e.ime) || IMES[0]).input;
    const input = Object.assign({}, ime);
    if (family === 'number') Object.assign(input, { field: 'number', pad: e.pad || 'full' });
    else if (e.field && e.field !== 'text') input.field = e.field;
    out.input = input;
    const punctuation = ime.ime === 'english' ? null : M.ZH_PUNCTUATION;
    if (punctuation) out.texts = { punctuation };
    let marks = null;
    try {
      marks = layout ? E.defaultMarks(layout, punctuation) : null;
    } catch (err) {
      // a layout the engine gives no marks: drawn without them
    }
    if (marks || e.upAt || e.downAt) {
      out.marks = Object.assign({}, marks || {});
      if (e.upAt) out.marks.upAt = e.upAt;
      if (e.downAt) out.marks.downAt = e.downAt;
    }
    return out;
  }

  /**
   * [xml] checked and drawn: `{problems, info, layout, scene, report}`. The problems the checker's (every scene the app
   * shows it in), the reader's own warnings, then what the preview's own environment [e] adds (said as the
   * environment's: the checker's scenes are the app's, this one only the bar's); the scene drawn by [theme] where the
   * engine reads the layout at all, even with the checker's errors (a wrong id still draws). A text past the app's
   * size (the checker's too-big, §13) is neither read nor drawn: that one error is all. [version]: the app's the file
   * is held to.
   */
  function check(E, xml, e, theme, measurer, version) {
    try {
      return checked(E, xml, e, theme, measurer, version);
    } catch (err) {
      return { problems: [{ level: 'error', line: 0, message: '引擎出错：' + (err && err.message ? err.message : err) }], info: info(xml), layout: null, scene: null, report: null };
    }
  }
  // the last text's report and reading: a change of the environment draws again without checking again (the
  // checker builds every scene the app shows a layout in, a tenth of a second and more)
  let read = { E: null, xml: null, version: null, theme: null, report: null, r: null };
  function checked(E, xml, e, theme, measurer, version) {
    if (read.E !== E || read.xml !== xml || read.version !== version || read.theme !== (theme || null)) {
      const report = E.checkLayout(xml, version, theme || null);
      const big = (report.errors || []).find((f) => f.key === 'too-big');
      read = { E, xml, version, theme: theme || null, report: big ? { errors: [big], warnings: [] } : report, r: big ? null : E.readOwnLayout(xml) };
    }
    const { report, r } = read;
    const problems = findings(report);
    const out = { problems, info: info(xml), layout: r && r.ok ? r.layout : null, scene: null, report };
    if (!r) return out;
    if (!r.ok) {
      // the checker said why (read, include): its own words where it did not
      if (!problems.some((p) => p.level === 'error')) for (const p of r.problems) problems.push({ level: 'error', line: p.line || 0, message: '读不进来：' + p.message });
      return out;
    }
    // what the reader let pass but says (another key's shortcut=, an attribute that does nothing): the checker does not
    for (const p of r.warnings || []) problems.push({ level: 'warning', line: p.line || 0, message: '读取：' + p.message });
    if (!theme) return out;
    const s = E.buildScene(r.layout, theme, envJson(E, e, r.layout.family, r.layout), measurer || E.fixedMeasurer());
    const add = (level, list) => { for (const p of list || []) problems.push({ level, line: p.line || 0, message: '这个预览环境：' + p.message }); };
    add('error', s.problems);
    add('warning', s.ok ? s.scene.json().warnings : s.warnings);
    out.scene = s.ok ? s.scene : null;
    return out;
  }

  /** The line of [xml] where key [id] is (its `id="…"`), 0 where none: what a finding of a key without a line goes to. */
  function lineOfKey(xml, id) {
    const at = F.uncommented(xml).search(new RegExp('<key\\b[^>]*\\sid\\s*=\\s*["\']' + id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '["\']'));
    return at < 0 ? 0 : xml.slice(0, at).split('\n').length;
  }

  root.YoumoLayoutMaker = {
    BASE_LABEL, FAMILY_LABEL, PAD_LABEL, IMES, SHIFTS, FIELDS, FORMS, AT, ENV, WORDS,
    words, findings, info, withAttr, setKeyboard, setMeta, idProblem, nameProblem, suggested, fromBuiltin, fromExample, openText, fileName, saveCheck,
    restoreEnv, shape, envJson, check, lineOfKey,
  };
})(typeof window !== 'undefined' ? window : globalThis);
