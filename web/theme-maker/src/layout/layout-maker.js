// youmo-layout-maker: the page never reads or lays out a layout itself: each change goes back through the engine.
(function (root) {
  'use strict';

  const F = root.YoumoForm;
  const M = root.YoumoMaker;
  if (!F || !M) throw new Error('youmo-layout-maker needs youmo-form and youmo-maker loaded first');
  const { escape } = F;

  const ID = /^[a-z0-9]+(-[a-z0-9]+)*$/;

  const BASE_LABEL = { qwerty: '26 键', k14: '14 键', k18: '18 键', nine: '九键', number: '数字键盘', symbols: '符号面板' };
  const FAMILY_LABEL = { letters: '字母', grouped: '字母分组', nine: '九键', number: '数字', symbols: '符号' };
  const PAD_LABEL = {
    full: '完整', phone: '电话', datetime: '日期时间',
  };
  const ROLE_LABEL = { symbols: '符', back: '返回', numbers: '123' };
  const SLOT_LABEL = {
    'handwriting-pad': '手写区', 'nine-side': '九键左边的符号列', 'handwriting-candidates': '手写候选栏', 'symbol-categories': '符号面板的分类栏', 'symbol-grid': '符号面板的符号格子',
  };
  const KEY_LABEL = { backspace: '删除', enter: '回车', lang: '地球键' };
  const BOARD_LABEL = { Number: '数字键盘', Symbol: '符号面板' };
  const APP_BOARD_LABEL = {
    qwerty: '全键盘', k14: '14 键', k18: '18 键', nine: '九键', zhuyin: '注音全键盘', 'zhuyin-nine': '注音九键', number: '数字键盘', symbols: '符号面板',
    emoji: '表情', emoticon: '颜文字', 'emoji-search': '表情搜索', 'expanded-side': '展开候选', 'number-row': '数字行',
  };

  // LayoutCheck.KEYS in words: the test holds the table to the engine's list

  const q = (s) => '「' + s + '」';
  // as the app's LayoutTexts.spaced: a space beside a digit or a Latin letter, none beside a Chinese one
  const boardName = (board) => {
    const n = APP_BOARD_LABEL[board] || board;
    return (/^[0-9A-Za-z]/.test(n) ? ' ' : '') + n + (/[0-9A-Za-z]$/.test(n) ? ' ' : '');
  };
  const WORDS = {
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
    'no-way': (boards) => '没有键能切到' + boards.map((b) => BOARD_LABEL[b] || b).join('、'),
    narrow: ([key]) => '键' + q(key) + '很窄，可能不好按',
    commits: ([n]) => n + ' 个键点按打出写死的文字（导入时 App 会列给用户看）',
    'theme-blank': ([where]) => '预览主题下这个键什么都不显示：' + where,
    unnamed: ([key]) => '键' + q(key) + '没有按键名（shortcut=）：用户在 App 里改不了它的长按、上下滑',
    held: ([key, board]) => key + ' 是' + boardName(board) + '的键，这里写的长按、角标、滑动不起作用',
    'held-letters': ([key, board]) => key + ' 是' + boardName(board) + '的键，长按跟它走，不按这里写的字母；要用这些字母，给它起个自己的键名',
    'held-popups': ([board]) => '这里写的 popups= 不起作用，跟' + boardName(board) + '一样',
    'gap-look': () => '空位（gap）的 look=、class= 不起作用',
    // the full pad is every number field's, not one kind of field
    'pad-lacks': ([pad, keys]) => (pad === 'full'
      ? '数字键盘缺 ' + keys + '：数字框里打不出来（比如金额的负号、小数点）'
      : '数字键盘「' + (PAD_LABEL[pad] || pad) + '」缺 ' + keys + '：这种输入框里打不出来'),
    'bottom-left': ([want]) => '左下角通常是 ' + want.split(' ').map((r) => ROLE_LABEL[r] || r).join('、') + '（和 App 自带的一样）',
    'split-scaled': ([n, where]) => '分体时这一行在「' + where + '」有 ' + n + ' 个键，不是 9 或 10 个，分不成左右两半，只能整行缩小',
  };

  function words(f) {
    const say = WORDS[f.key];
    return say ? say(Array.from(f.args || [])) : f.key + (f.args && f.args.length ? '：' + Array.from(f.args).join(' ') : '');
  }

  function findings(report) {
    const out = [];
    for (const f of report.errors || []) out.push({ level: 'error', line: f.line || 0, message: words(f) });
    for (const f of report.warnings || []) out.push({ level: 'warning', line: f.line || 0, message: words(f) });
    return out;
  }


  const keyboardOf = (xml) => F.elements(xml, 'keyboard')[0] || null;

  function info(xml) {
    const k = keyboardOf(xml);
    if (!k) return null;
    const a = Object.fromEntries(k.attrs);
    const m = F.elements(xml, 'meta')[0];
    return { id: a.id ?? null, name: a.name ?? null, family: a.family ?? null, basedOn: a['based-on'] ?? null, meta: m ? Object.fromEntries(m.attrs) : {} };
  }

  // ' escaped too, so no reader takes part of the value for a quote
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

  /** A file's bytes as a layout's text (M.layoutText, the theme maker's opener too), one with a `<keyboard>`. */
  function openText(bytes, max, size = bytes.length) {
    const text = M.layoutText(bytes, max, size);
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
    const s = M.shape(E, { w: e.w, h: e.h, density: e.density, landscape: !!e.turned });
    return { widthDp: Math.round(s.width / e.density), landscape: s.landscape, width: s.width, height: s.height };
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

  // PackLayouts.convert's why in words; one not known here as it said it
  function refusal(why) {
    const w = why || '';
    let m;
    if (w === 'too big') return '文件太大';
    if (w === 'does not read') return '读不出来';
    if (w === 'not a keyboard listed') return '不是 App 布局列表里的键盘';
    if ((m = /^for a newer app \((.*)\)$/.exec(w))) return '要更新版本的 App（' + m[1] + '）';
    if ((m = /^its fragment (.*) does not read$/.exec(w))) return '它用的片段 ' + m[1] + ' 读不出来';
    if ((m = /^its id is (.*)$/.exec(w))) return '里面写的 id 是 ' + m[1] + '，和文件名不一样';
    if ((m = /^its family is (.*), the app's (.*)$/.exec(w))) return '种类是 ' + m[1] + '，App 的这块是 ' + m[2];
    if ((m = /^the app's (.*) does not read$/.exec(w))) return 'App 自带的 ' + m[1] + ' 读不出来';
    if ((m = /^made one that does not take: (.*)$/.exec(w))) return '转出来的布局检查不过：' + m[1];
    if ((m = /^its pads lack keys: (.*)$/.exec(w))) return '它写的数字键盘缺键：' + m[1].split(' ').map((p) => '「' + (PAD_LABEL[p] || p) + '」').join('、');
    if ((m = /^over (\S+) when= alternatives an include's and its rows' make$/.exec(w))) return '它用的片段和片段里的行，when= 条件合起来超过 ' + m[1] + ' 种';
    if (w === 'it serves no pad') return '哪种数字键盘都缺键，一种也用不了';
    if ((m = /^it has no slot \[(.*)\]$/.exec(w))) return '少了 App 这块必有的位置：' + m[1].split(', ').map((k) => SLOT_LABEL[k] || k).join('、');
    if ((m = /^it has no key \[(.*)\]$/.exec(w))) return '少了 App 这块必有的键：' + m[1].split(', ').map((k) => KEY_LABEL[k] || k).join('、');
    return w || '转不出来';
  }

  /**
   * A theme pack's boards a layout can start from (§12.4 打开 .theme): of [d] (the theme maker's document, M.open's), each
   * of [bases] (the app's listed boards, in their order) its `layouts/` has a file of, made one of the user's as the
   * app makes it on import (E.convertPackLayout, PackLayouts.convert, with the pack's fragments): `{board, ok, id, name,
   * text}`, or `{board, ok: false, why}`. [theme] the pack's theme read (its name and `<meta>` the layout's); [version]
   * the app's. The conversion's `renames` (the user's settings of a key renamed carried over) are the app's alone:
   * the page has none to carry.
   */
  function themeBoards(E, d, theme, bases, version) {
    const fragments = {};
    for (const [file, xml] of Object.entries(d.layouts)) if (!bases.includes(file.replace(/\.xml$/, ''))) fragments[file.replace(/\.xml$/, '')] = xml;
    const m = theme.info(false).meta;
    const meta = m ? { author: m.author || null, license: m.license || null } : null;
    return bases.filter((b) => typeof d.layouts[b + '.xml'] === 'string').map((board) => {
      const c = E.convertPackLayout(theme.name, meta, board, d.layouts[board + '.xml'], fragments, version);
      return c.ok ? { board, ok: true, id: c.id, name: c.name, text: c.text } : { board, ok: false, why: refusal(c.why) };
    });
  }

  root.YoumoLayoutMaker = {
    BASE_LABEL, FAMILY_LABEL, PAD_LABEL, IMES, SHIFTS, FIELDS, FORMS, AT, ENV, WORDS,
    words, findings, info, withAttr, setKeyboard, setMeta, idProblem, nameProblem, suggested, fromBuiltin, fromExample, openText, fileName, saveCheck,
    restoreEnv, shape, envJson, check, themeBoards, refusal,
  };
})(typeof window !== 'undefined' ? window : globalThis);
