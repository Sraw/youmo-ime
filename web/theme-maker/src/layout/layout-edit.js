// The layout maker's editing (061-custom-layout §12.3, §12.5): a layout's text changed as the forms and the preview
// ask. A key moved, added, taken out, its attributes set; a row's; letter keys merged into a group; the app's bottom
// row made the layout's own. Every change is on the text, the rest of it kept as it was (comments, what the forms do
// not know), as YoumoForm's on a theme. A key is where it is written: `{row, index}`, its row the n-th <row> of the
// file, its index among the row's keys, gaps and slots.
(function (root) {
  'use strict';

  const F = root.YoumoForm;
  const LM = root.YoumoLayoutMaker;

  const LETTER = /^[a-z;]$/;
  // the ids a probe gives a key written twice (when=) to see which of them the scene draws
  const PROBE = 'youmo-probe';

  const attrs = (el) => Object.fromEntries(el.attrs);

  /**
   * The layout's parts in order: `{rows, includes, parts}`. A row `{start, end, line, tag, a, index, cells}`, each cell
   * a key, gap or slot `{start, end, line, tag, name, a, row, index}` (`a`: its attributes); an include `{…, a, index}`;
   * `parts` the rows and includes as the file has them (what the row order moves).
   */
  function parse(xml) {
    const rows = F.elements(xml, 'row').map((r, index) => ({ ...r, a: attrs(r), index, cells: [] }));
    const cells = ['key', 'gap', 'slot'].flatMap((name) => F.elements(xml, name).map((c) => ({ ...c, name, a: attrs(c) })));
    cells.sort((x, y) => x.start - y.start);
    for (const c of cells) {
      const row = rows.find((r) => r.start < c.start && c.start < r.end);
      if (!row) continue;
      c.row = row.index;
      c.index = row.cells.length;
      row.cells.push(c);
    }
    const includes = F.elements(xml, 'include').map((i, index) => ({ ...i, a: attrs(i), index }));
    const parts = [...rows.map((row) => ({ row, start: row.start, end: row.end })), ...includes.map((include) => ({ include, start: include.start, end: include.end }))];
    parts.sort((x, y) => x.start - y.start);
    return { rows, includes, parts };
  }

  const cellAt = (p, at) => (at && p.rows[at.row] ? p.rows[at.row].cells[at.index] || null : null);
  const put = (xml, start, end, text) => xml.slice(0, start) + text + xml.slice(end);
  // the white space the line of [at] starts with
  const indentAt = (xml, at) => /^[ \t]*/.exec(xml.slice(xml.lastIndexOf('\n', at - 1) + 1))[0];
  // [text] (an element, its later lines indented as at [from]) with its later lines at [to]
  const reindent = (text, from, to) => text.split('\n').map((l, i) => (i && l.startsWith(from) ? to + l.slice(from.length) : l)).join('\n');

  // [xml] without [start, end): its whole lines with it where nothing else is on them
  function cut(xml, start, end) {
    const s = xml.lastIndexOf('\n', start - 1) + 1;
    let e = xml.indexOf('\n', end);
    if (e < 0) e = xml.length;
    if (/^[ \t]*$/.test(xml.slice(s, start)) && /^[ \t]*$/.test(xml.slice(end, e))) return xml.slice(0, s) + xml.slice(Math.min(e + 1, xml.length));
    return xml.slice(0, start) + xml.slice(end);
  }

  // [xml] with the tag of the element at [el] changed by [values] ({name: value, null: taken out})
  function retag(xml, el, values) {
    let tag = el.tag;
    for (const [name, value] of Object.entries(values)) tag = LM.withAttr(tag, name, value === '' ? null : value);
    return put(xml, el.start, el.start + el.tag.length, tag);
  }

  /** [xml] with the cell at [at]'s attributes set ({name: value}; null or '': taken out). */
  function setAttrs(xml, at, values) {
    const c = cellAt(parse(xml), at);
    return c ? retag(xml, c, values) : xml;
  }

  /** [xml] with the [row]-th row's attributes set, as setAttrs. */
  function setRow(xml, row, values) {
    const r = parse(xml).rows[row];
    return r ? retag(xml, r, values) : xml;
  }

  // [xml] with the rows a change left without a cell taken out (the reader refuses an empty <row>)
  function dropEmpty(xml) {
    const empty = parse(xml).rows.filter((r) => !r.cells.length).reverse();
    for (const r of empty) xml = cut(xml, r.start, r.end);
    return xml;
  }

  /** [xml] with [text] (one element) put in the [at.row]-th row before its [at.index]-th cell (its count: at the end). */
  function insert(xml, at, text) {
    const row = parse(xml).rows[at.row];
    if (!row) return xml;
    const next = row.cells[at.index];
    if (next) {
      const ind = indentAt(xml, next.start);
      return put(xml, next.start, next.start, reindent(text, '', ind) + '\n' + ind);
    }
    const last = row.cells[row.cells.length - 1];
    if (last) {
      const ind = indentAt(xml, last.start);
      return put(xml, last.end, last.end, '\n' + ind + reindent(text, '', ind));
    }
    const outer = indentAt(xml, row.start);
    const ind = outer + '  ';
    if (row.tag.endsWith('/>')) return put(xml, row.start, row.end, row.tag.replace(/\s*\/>$/, '>') + '\n' + ind + reindent(text, '', ind) + '\n' + outer + '</row>');
    const after = row.start + row.tag.length;
    return put(xml, after, after, '\n' + ind + reindent(text, '', ind));
  }

  /** [xml] without the cell at [at]; its row with it where it was the row's only one. */
  function remove(xml, at) {
    const p = parse(xml);
    const c = cellAt(p, at);
    if (!c) return xml;
    const row = p.rows[at.row];
    return row.cells.length === 1 ? cut(xml, row.start, row.end) : cut(xml, c.start, c.end);
  }

  // the cell's text, its later lines without the indent it has where it is
  const textOf = (xml, c) => reindent(xml.slice(c.start, c.end), indentAt(xml, c.start), '');

  /**
   * [xml] with the cell at [from] moved before the [to.index]-th cell of the [to.row]-th row (as they are before the
   * move; the row's count: at its end): `{xml, at}`, [at] where it is now. A row it leaves empty goes.
   */
  function move(xml, from, to) {
    const p = parse(xml);
    const c = cellAt(p, from);
    if (!c || !p.rows[to.row]) return { xml, at: from };
    let index = to.index;
    if (to.row === from.row) {
      if (index === from.index || index === from.index + 1) return { xml, at: from };
      if (index > from.index) index--;
    }
    let out = insert(cut(xml, c.start, c.end), { row: to.row, index }, textOf(xml, c));
    const left = parse(out).rows[from.row];
    let row = to.row;
    if (left && !left.cells.length) {
      out = cut(out, left.start, left.end);
      if (to.row > from.row) row--;
    }
    return { xml: out, at: { row, index } };
  }

  /** The cell's place one to the left (-1) or right (1) in its row, or into the row above (-1) or below (1) at its end or start. */
  function step(xml, at, dx, dy) {
    const p = parse(xml);
    if (!cellAt(p, at)) return { xml, at };
    if (dx) {
      const index = at.index + (dx < 0 ? -1 : 2);
      if (index < 0 || index > p.rows[at.row].cells.length) return { xml, at };
      return move(xml, at, { row: at.row, index });
    }
    const row = at.row + dy;
    if (!p.rows[row]) return { xml, at };
    return move(xml, at, { row, index: dy < 0 ? p.rows[row].cells.length : 0 });
  }

  // ---- rows ----

  /** [xml] with a row of [text] (one element) put before the [at]-th part (rows and includes; their count: at the end). */
  function addRow(xml, at, text) {
    const p = parse(xml);
    const ind = p.parts.length ? indentAt(xml, p.parts[0].start) : '  ';
    const row = '<row>\n' + ind + '  ' + reindent(text, '', ind + '  ') + '\n' + ind + '</row>';
    const next = p.parts[at];
    if (next) return put(xml, next.start, next.start, row + '\n' + ind);
    const last = p.parts[p.parts.length - 1];
    if (last) return put(xml, last.end, last.end, '\n' + ind + row);
    const close = xml.lastIndexOf('</keyboard>');
    return close < 0 ? xml : put(xml, close, close, ind + row + '\n');
  }

  /** [xml] without its [at]-th part (a row or an include). */
  function removePart(xml, at) {
    const part = parse(xml).parts[at];
    return part ? cut(xml, part.start, part.end) : xml;
  }

  /** [xml] with its [from]-th part (rows and includes) moved before the [to]-th (as they are before; their count: at the end). */
  function movePart(xml, from, to) {
    const p = parse(xml);
    const part = p.parts[from];
    if (!part || to < 0 || to > p.parts.length || to === from || to === from + 1) return xml;
    const ind = indentAt(xml, part.start);
    const text = reindent(xml.slice(part.start, part.end), ind, '');
    const out = cut(xml, part.start, part.end);
    const q = parse(out);
    const index = to > from ? to - 1 : to;
    const next = q.parts[index];
    if (next) return put(out, next.start, next.start, reindent(text, '', ind) + '\n' + ind);
    const last = q.parts[q.parts.length - 1];
    return put(out, last.end, last.end, '\n' + ind + reindent(text, '', ind));
  }

  /** The [row]-th row's place among the parts (what addRow, removePart and movePart count), -1 for none. */
  const partOfRow = (xml, row) => parse(xml).parts.findIndex((x) => x.row && x.row.index === row);

  /**
   * [xml] with its [at]-th include (the app's bottom row, §12.3) replaced by the rows of [fragment] (the fragment's
   * text): the rows the layout's own from now on, each with the include's when= (with its own, both), indented as the
   * include was.
   */
  function ownRows(xml, at, fragment) {
    const inc = parse(xml).includes[at];
    if (!inc || !fragment) return xml;
    const ind = indentAt(xml, inc.start);
    const fragRows = F.elements(fragment, 'row');
    // the fragment's own <defaults> (under its rows', over the board's: KeyData) go into each row's, a row's own winning
    const own = F.elements(fragment, 'defaults').find((d) => !fragRows.some((r) => d.start > r.start && d.start < r.end));
    const under = own ? Object.entries(attrs(own)) : [];
    const rows = fragRows.map((r) => {
      let text = pretty(fragment.slice(r.start, r.end), ind);
      if (under.length) {
        const rowTag = /^<row\b(?:[^>"']|"[^"]*"|'[^']*')*>/.exec(text)[0];
        const d = F.elements(text, 'defaults')[0];
        if (d && d.start > 0) {
          const mine = attrs(d);
          const tag = under.reduce((t, [k, v]) => (k in mine ? t : LM.withAttr(t, k, v)), d.tag);
          text = put(text, d.start, d.start + d.tag.length, tag);
        } else if (!rowTag.endsWith('/>')) {
          text = rowTag + '\n' + ind + '  ' + el('defaults', Object.fromEntries(under)) + text.slice(rowTag.length);
        }
      }
      if (inc.a.when) {
        const own = attrs(r).when;
        const tag = /^<row\b(?:[^>"']|"[^"]*"|'[^']*')*>/.exec(text)[0];
        text = LM.withAttr(tag, 'when', own ? inc.a.when + ' ' + own : inc.a.when) + text.slice(tag.length);
      }
      return text;
    });
    return put(xml, inc.start, inc.end, rows.join('\n' + ind));
  }

  // a fragment's row (the app's files come without their indents) laid out as LayoutWriter does, the row at [ind]
  function pretty(text, ind) {
    return text.split('\n').map((l, i) => {
      const t = l.trim();
      if (!i || /^<\/?row\b/.test(t)) return (i ? ind : '') + t;
      if (/^<\/?(key|gap|slot)\b/.test(t)) return ind + '  ' + t;
      return ind + (t.startsWith('<') ? '    ' : '       ') + t;
    }).join('\n');
  }

  /** The include among [xml]'s that draws key [id]: its index, -1 for none. [source](src): a fragment's text. */
  function includeOf(xml, id, source) {
    return parse(xml).includes.findIndex((i) => {
      const text = source(i.a.src);
      return !!text && F.elements(text, 'key').some((k) => attrs(k).id === id);
    });
  }

  /**
   * Where the scene's key [id] is written: `{row, index}`, null where the file has no such key (a fragment's).
   * A key written twice (each with its when=) is the one the scene draws: [drawn](text) the ids the preview's
   * scene of a text has, asked with each in turn renamed.
   */
  function locate(xml, id, drawn) {
    const all = parse(xml).rows.flatMap((r) => r.cells.filter((c) => c.name === 'key' && c.a.id === id));
    if (all.length <= 1) return all.length ? { row: all[0].row, index: all[0].index } : null;
    const found = all.find((c) => drawn(put(xml, c.start, c.start + c.tag.length, LM.withAttr(c.tag, 'id', PROBE))).includes(PROBE)) || all[0];
    return { row: found.row, index: found.index };
  }

  /** The cell whose element starts on [line] (a problem's), null for none. */
  function atLine(xml, line) {
    for (const r of parse(xml).rows) for (const c of r.cells) if (c.line === line) return { row: r.index, index: c.index };
    return null;
  }

  // ---- new keys ----

  // the keys of [xml] and of the fragments it includes ([source](src): a fragment's text): the bottom row's keys are
  // the board's as much as its own, an id or a name taken there taken here
  const allKeys = (xml, source) => [xml, ...(source ? parse(xml).includes.map((i) => source(i.a.src) || '') : [])].flatMap((t) => F.elements(t, 'key').map(attrs));
  const ids = (xml, source) => new Set(allKeys(xml, source).map((a) => a.id));

  /** [want] where no key of [xml] (and of its includes', [source]) has it, else it with -2, -3… [except]: an id about to go. */
  function freeId(xml, want, except = null, source = null) {
    const taken = ids(xml, source);
    taken.delete(except);
    if (!taken.has(want)) return want;
    for (let n = 2; ; n++) if (!taken.has(want + '-' + n)) return want + '-' + n;
  }

  const q = (v) => '"' + F.escape(String(v)).replace(/'/g, '&apos;') + '"';
  const el = (name, values) => '<' + name + Object.entries(values).filter(([, v]) => v != null && v !== '').map(([k, v]) => ' ' + k + '=' + q(v)).join('') + '/>';
  const shortcuts = (xml, source) => new Set(allKeys(xml, source).map((a) => a.shortcut).filter(Boolean));

  /**
   * A new letter key of [letters] for [xml] (§12.3 加键): one letter on a `letters` board types it and follows
   * Shift, named as the app's (`full/x`) where no other key is; a group (or any on a `grouped` board) a group key of
   * its own name (`<layout id>/<key id>`, §9). A lone ; (双拼's ing) is `semicolon` of its own name: the app has no
   * `full/;` (the checker refuses it). [source]: the included fragments' texts, their ids taken too.
   */
  function letterKey(xml, letters, source = null) {
    const i = LM.info(xml) || {};
    if (letters.length === 1 && i.family !== 'grouped') {
      const semicolon = letters === ';';
      const id = freeId(xml, semicolon ? 'semicolon' : letters, null, source);
      const name = semicolon || shortcuts(xml, source).has('full/' + letters) ? i.id + '/' + id : 'full/' + letters;
      return el('key', { id, label: semicolon ? ';' : '{case:' + letters + '}', send: letters, shortcut: name });
    }
    const id = freeId(xml, letters.replace(/;/g, 'ing') || 'g', null, source);
    return el('key', { id, label: '{group:' + letters + '}', letters, shortcut: i.id + '/' + id });
  }

  /** What is wrong with [letters] as a key's group, null for nothing. */
  function lettersProblem(letters) {
    if (!/^[a-z;]{1,5}$/.test(letters)) return '分组字母是 1–5 个 a–z 或 ;';
    return new Set(letters).size === letters.length ? null : '分组里有重复的字母';
  }

  /** A new character key typing [text] (one character: as typed; more: commit:), its own name (§12.3). */
  function charKey(xml, text, source = null) {
    const i = LM.info(xml) || {};
    const id = freeId(xml, 'c1', null, source);
    const one = [...text].length === 1 && !/\s/.test(text);
    return el('key', { id, label: text, send: one ? text : 'commit:' + text, shortcut: i.id + '/' + id });
  }

  // a function key's face in the palette, where its label is a binding or an icon
  const FN_LABEL = {
    shift: '⇧', backspace: '⌫', enter: '⏎', space: '空格', lang: '地球', symbols: '符', numbers: '123', apostrophe: '分词',
    comma: '，', period: '。', emoji: '表情', voice: '语音', back: '返回',
  };

  /**
   * The function keys of [texts] (a board's file and its fragments'): `[{id, label, text}]` each once, [text] the
   * element without its when= (where it shows is the new layout's). A function key: of a role, or kind function,
   * space or enter, or a name of no `/`; not a letter.
   */
  function functionKeys(texts) {
    const out = new Map();
    for (const t of texts) {
      for (const k of F.elements(t, 'key')) {
        const a = attrs(k);
        if (out.has(a.id) || a.letters || LETTER.test(a.send || '')) continue;
        const fn = a.role || ['function', 'space', 'enter'].includes(a.kind) || (a.shortcut && (!a.shortcut.includes('/') || a.shortcut.startsWith('fn/')));
        if (!fn) continue;
        const text = reindent(LM.withAttr(k.tag, 'when', null) + t.slice(k.start + k.tag.length, k.end), indentAt(t, k.start), '');
        const face = a.role || a.id;
        const label = a.label && !/[{}]/.test(a.label) ? a.label : Object.hasOwn(FN_LABEL, face) ? FN_LABEL[face] : a.id;
        out.set(a.id, { id: a.id, label, text });
      }
    }
    return [...out.values()];
  }

  /** [text] (a key's element) with an id no key of [xml] (nor of its includes', [source]) has. */
  function withFreeId(xml, text, source = null) {
    const m = /\sid\s*=\s*("([^"]*)"|'([^']*)')/.exec(text);
    if (!m) return text;
    const id = F.unescape(m[2] ?? m[3]);
    const free = freeId(xml, id, null, source);
    return free === id ? text : text.replace(m[0], ' id=' + q(free));
  }

  // ---- merging (§12.3 合并键) ----

  /** The letters a cell types: its group's, or the one letter its tap sends; null for any other. */
  function lettersOf(c) {
    if (!c || c.name !== 'key') return null;
    if (c.a.letters) return c.a.letters.replace(/ /g, '');
    return LETTER.test(c.a.send || '') ? c.a.send : null;
  }

  // a cell's width as written: its own, its row's defaults', the board's; '1' where none says
  function widthOf(xml, p, c) {
    if (c.a.width) return c.a.width;
    const row = p.rows[c.row];
    const d = F.elements(xml, 'defaults').map((x) => ({ ...x, a: attrs(x) }));
    const own = d.find((x) => x.start > row.start && x.start < row.end && x.a.width);
    const board = d.find((x) => !p.rows.some((r) => x.start > r.start && x.start < r.end) && x.a.width);
    return (own || board || { a: { width: '1' } }).a.width;
  }

  const num = (n) => String(Math.round(n * 100) / 100);

  /**
   * [xml] with the letter keys at [ats] made one group key where the first of them is (§12.3): its letters theirs in
   * order, its width theirs summed (fill where one fills), a name of its own (`<layout id>/<key id>`). The board becomes
   * `grouped`, every other letter key a group of its one letter (a grouped board's keys type groups). `{xml, at}` or
   * `{error}` in words.
   */
  function merge(xml, ats, source = null) {
    const p = parse(xml);
    const cells = ats.map((at) => cellAt(p, at)).filter(Boolean).sort((x, y) => x.start - y.start);
    if (cells.length < 2) return { error: '选两个以上的字母键再合并' };
    const letters = cells.map(lettersOf);
    if (letters.some((l) => !l)) return { error: '只有字母键能合并' };
    const group = letters.join('');
    const why = lettersProblem(group);
    if (why) return { error: why };
    const widths = cells.map((c) => widthOf(xml, p, c));
    const width = widths.includes('fill') ? 'fill' : num(widths.reduce((s, w) => s + (parseFloat(w) || 0), 0));
    const i = LM.info(xml) || {};
    // the merged keys' ids free for it: its id the first letters
    let out = xml;
    for (const c of cells.slice(1).reverse()) out = cut(out, c.start, c.end);
    const id = freeId(out, group.replace(/;/g, 'ing'), cells[0].a.id, source);
    out = put(out, cells[0].start, cells[0].end, el('key', { id, label: '{group:' + group + '}', letters: group, shortcut: i.id + '/' + id, width }));
    for (const r of parse(out).rows.slice().reverse()) {
      for (const c of r.cells.slice().reverse()) {
        if (c.name === 'key' && !c.a.letters && lettersOf(c)) out = retag(out, c, { letters: c.a.send, label: '{group:' + c.a.send + '}', send: null });
      }
    }
    out = LM.setKeyboard(dropEmpty(out), 'family', 'grouped');
    return { xml: out, at: { row: cells[0].row, index: cells[0].index } };
  }

  /** The note a merge shows (§12.3). */
  const MERGED = '变成分组键盘：只有拼音能用（双拼只在分组和 14 键或 18 键的组一样时能用，英文不能用）';

  /** How many keys type each letter a–z and ; as a group (§12.3 分组字母): `{a: 1, …}`, a letter no key types absent. */
  function coverage(xml) {
    const n = {};
    for (const r of parse(xml).rows) {
      for (const c of r.cells) for (const ch of c.name === 'key' ? (c.a.letters || '').replace(/ /g, '') : '') n[ch] = (n[ch] || 0) + 1;
    }
    return n;
  }

  // ---- what a key shows and does ----

  /** A label as the panel edits it: `{type: none|text|case|punct|other, arg}` (other: a binding said as written). */
  function labelParts(raw) {
    if (raw == null || raw === '') return { type: 'none', arg: '' };
    const m = /^\{(case|punct):(.+)\}$/.exec(raw);
    if (m) return { type: m[1], arg: m[2] };
    return { type: /[{}]/.test(raw) ? 'other' : 'text', arg: raw };
  }
  /** Why [arg] cannot be a label of [type], null where it can: braces are a binding's, written as one (照写). */
  function labelProblem(type, arg) {
    return type !== 'other' && type !== 'none' && /[{}]/.test(arg) ? '显示的字里不能有 { 或 }：绑定请选「绑定（照写）」' : null;
  }
  function labelText(type, arg) {
    if (type === 'none' || !arg) return null;
    return type === 'case' || type === 'punct' ? '{' + type + ':' + arg + '}' : arg;
  }

  /** The kinds of action the panel offers (§12.3 动作), each with what its parameter is. */
  const ACTION_TYPES = [
    ['', '无'], ['char', '打一个字'], ['commit', '打一段文字（commit:）'], ['sym', '功能（sym:）'], ['layout', '切换面板（layout:）'],
    ['shortcut', '快捷（shortcut:）'], ['keypad', '选第 N 个候选（keypad:）'], ['other', '其它（照写）'],
  ];
  /** The attributes of a key's actions, in the panel's order, with their names. */
  const ACTIONS = [['send', '点按'], ['long-press', '长按'], ['swipe-up', '上滑'], ['swipe-down', '下滑'], ['double-tap', '双击']];

  /** An action as the panel edits it: `{type, arg}`. */
  function actionParts(raw) {
    if (!raw) return { type: '', arg: '' };
    const m = /^(commit|sym|layout|shortcut|keypad):([\s\S]*)$/.exec(raw);
    if (m) return { type: m[1], arg: m[2] };
    return { type: [...raw].length === 1 ? 'char' : 'other', arg: raw };
  }
  function actionText(type, arg) {
    if (!type || !arg) return null;
    return type === 'char' || type === 'other' ? arg : type + ':' + arg;
  }

  const BOARD_OF = { full: '26 键', g14: '14 键', g18: '18 键', t9: '九键', zy9: '注音九键', zyfull: '注音全键盘', num: '数字键盘', fn: '功能键' };

  /**
   * Why a key's actions but its tap are not the layout's to say (§9, §19.2, Q11): it shares a name with the app's
   * keys (or has none: its base's key's), what the user sets in the app goes for both. Null: its own name, all of them
   * its layout's defaults. [prefixes]: the app's name prefixes (layoutFormat's).
   */
  function sharedName(a, prefixes) {
    const s = a.shortcut;
    if (!s) return '这个键没有自己的名字（shortcut=），长按、滑动跟底稿里同 id 的键，在 App 的按键字符和快捷键里改';
    const at = s.indexOf('/');
    const form = at < 0 ? 'fn' : s.slice(0, at);
    if (at >= 0 && !prefixes.includes(form)) return null;
    const key = at < 0 ? s : s.slice(at + 1);
    return '这个键的长按、滑动跟内置的' + (BOARD_OF[form] || form) + ' ' + (form === 'fn' ? key : key.toUpperCase()) + '，在 App 的按键字符和快捷键里改';
  }

  /**
   * The cell's long press as written: null where it has no <popup> (its character's built-in list), else
   * `{from, preset, items: [{text, send}]}` (`from` preset where it says none, as the reader takes it).
   */
  function popupOf(xml, at) {
    const c = cellAt(parse(xml), at);
    if (!c) return null;
    const text = xml.slice(c.start, c.end);
    const pop = F.elements(text, 'popup')[0];
    if (!pop) return null;
    const a = attrs(pop);
    const items = F.elements(text.slice(pop.start, pop.end), 'item').map((x) => ({ text: attrs(x).text || '', send: attrs(x).send ?? null }));
    return { from: a.from || 'preset', preset: a.preset ?? null, items };
  }

  /**
   * [xml] with the cell's long-press list [items] (texts), what the app shows unless the user set one: a <popup> of
   * its own changed in place (its tag's other attributes, as-is= and the rest, kept; only its items new), else one
   * from="list" put in, an item whose text stays keeping its send=; no items: the <popup> gone (a list of none is no list).
   */
  function setPopup(xml, at, items) {
    const c = cellAt(parse(xml), at);
    if (!c || c.name !== 'key') return xml;
    const ind = indentAt(xml, c.start);
    let text = xml.slice(c.start, c.end);
    const pop = F.elements(text, 'popup')[0];
    // an item kept (its text still there) keeps what it sends
    const sends = new Map(pop ? F.elements(text.slice(pop.start, pop.end), 'item').map((x) => [attrs(x).text, attrs(x).send]) : []);
    const inner = items.map((t) => el('item', { text: t, send: sends.get(t) })).join('');
    if (pop && !items.length) text = cut(text, pop.start, pop.end);
    else if (pop) {
      const tag = LM.withAttr(LM.withAttr(pop.tag, 'from', 'list'), 'preset', null).replace(/\s*\/>$/, '>');
      text = put(text, pop.start, pop.end, tag + inner + '</popup>');
    } else if (items.length) {
      const list = '<popup from="list">' + inner + '</popup>';
      if (c.tag.endsWith('/>')) text = c.tag.replace(/\s*\/>$/, '>') + '\n' + ind + '  ' + list + '\n' + ind + '</key>';
      else text = put(text, c.tag.length, c.tag.length, '\n' + ind + '  ' + list);
    }
    // a key left with nothing inside closes itself
    const open = F.elements(text, 'key')[0].tag;
    if (!open.endsWith('/>') && !text.slice(open.length, -'</key>'.length).trim()) text = open.replace(/>$/, '/>');
    return put(xml, c.start, c.end, text);
  }

  /** [units] snapped as a width is dragged (§12.3): to 0.5, to 1 (1 %) on a board of [columns] 100; 0.5 to 100. */
  function snap(units, columns) {
    const stepBy = Number(columns) === 100 ? 1 : 0.5;
    return Math.min(100, Math.max(stepBy, Math.round(units / stepBy) * stepBy));
  }

  root.YoumoLayoutEdit = {
    PROBE, MERGED, ACTION_TYPES, ACTIONS, FN_LABEL,
    parse, cellAt, setAttrs, setRow, insert, remove, move, step, addRow, removePart, movePart, partOfRow, ownRows, includeOf, locate, atLine,
    freeId, letterKey, lettersProblem, charKey, functionKeys, withFreeId, lettersOf, merge, coverage,
    labelParts, labelProblem, labelText, actionParts, actionText, sharedName, popupOf, setPopup, snap, num, widthOf,
  };
})(typeof window !== 'undefined' ? window : globalThis);
