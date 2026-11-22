// 宠物: the theme's one <pet>, a sprite sheet for each kind of key (PetReader's format), its sheets held to the
// app's PetLimits (youmo-format.json's limits.pet) before they go in and again as the pack is saved (petProblems,
// which saveProblems calls). The preview plays a state's frames by its fps here only: the keyboard preview draws no pet.
(function (root) {
  'use strict';
  const panels = root.YoumoPanels;
  const F = root.YoumoForm;
  const STATES = [
    ['idle', '平时（必须有）'], ['typing', '按字母、数字、符号'], ['delete', '退格'], ['enter', '回车'], ['space', '空格'],
    ['sleep', '睡着（30 秒没按键）'], ['wake', '醒来（睡着时按了键）'],
  ];
  const NAMES = STATES.map((s) => s[0]);
  const RESTING = ['idle', 'sleep'];
  const ALIGNS = [['top-end', '右上'], ['top-start', '左上'], ['bottom-end', '右下'], ['bottom-start', '左下']];

  const get = (attrs, k) => (attrs.find((a) => a[0] === k) || [])[1];
  // in its place where it was, at the end where new; null takes it out
  const put = (attrs, k, v) => {
    const i = attrs.findIndex((a) => a[0] === k);
    const out = attrs.filter((a) => a[0] !== k);
    if (v != null) out.splice(i < 0 ? out.length : i, 0, [k, String(v)]);
    return out;
  };
  const tag = (name, attrs, close) => '<' + name + attrs.map(([k, v]) => ' ' + k + '="' + F.escape(v) + '"').join('') + close;

  /** The <pet> as written: `{attrs, states: [attrs]}`, every attribute kept (a later app's too), or null. */
  function read(xml) {
    const el = F.elements(xml, 'pet')[0];
    if (!el) return null;
    const inner = xml.slice(el.start + el.tag.length, el.end);
    return { el, attrs: el.attrs, states: F.elements(inner, 'state').map((s) => s.attrs) };
  }

  /** [xml] with [pet] in place of its <pet> (at the end, ThemeReader's order, where it had none); null: none. */
  function write(xml, pet) {
    const old = read(xml);
    const text = pet ? tag('pet', pet.attrs, '>') + pet.states.map((s) => '\n    ' + tag('state', s, '/>')).join('') + '\n  </pet>' : '';
    if (old && pet) return xml.slice(0, old.el.start) + text + xml.slice(old.el.end);
    if (old) return xml.slice(0, old.el.start).replace(/[ \t]*$/, '') + xml.slice(old.el.end).replace(/^[ \t]*\n/, '');
    if (!pet) return xml;
    const end = F.uncommented(xml).lastIndexOf('</theme>');
    if (end < 0) throw new Error('no </theme>');
    return xml.slice(0, end).replace(/\s*$/, '') + '\n  ' + text + '\n' + xml.slice(end);
  }

  const stateOf = (pet, name) => pet && pet.states.find((s) => get(s, 'name') === name);
  const int = (v, d) => (v != null && /^[0-9]{1,6}$/.test(v) ? +v : d);

  /** A state's numbers as the app reads them: frames, columns, rows, fps (idle's and sleep's at most restingFps), loop. */
  function numbers(s, lim) {
    const name = get(s, 'name');
    const frames = int(get(s, 'frames'), 1);
    const columns = int(get(s, 'columns'), frames);
    const fps = Math.min(Math.max(int(get(s, 'fps'), 8), 1), lim.fps, RESTING.includes(name) ? lim.restingFps : lim.fps);
    const loop = name === 'idle' || (get(s, 'loop') != null ? get(s, 'loop') === 'true' : RESTING.includes(name));
    return { frames, columns, rows: Math.ceil(frames / Math.max(columns, 1)), fps, loop };
  }

  /** What is wrong with [name]'s sheet [file] of [bytes] by PetLimits.check, in words, or null. */
  function sheetProblem(L, lim, name, file, bytes, s) {
    const at = '宠物 ' + name + ' 的 ' + file;
    const r = panels.raster(L, bytes);
    if (!r) return at + ' 不是 PNG 或 WebP';
    if (r.animated) return at + ' 是动图：帧图要是静态的，一帧一格排在一张图上';
    if (bytes.length > lim.sheetBytes) return at + ' 有 ' + Math.ceil(bytes.length / 1024) + ' KB，最多 ' + lim.sheetBytes / 1024 + ' KB';
    if (!r.size) return at + ' 不是 App 认得的图片';
    const [w, h] = r.size;
    const n = numbers(s, lim);
    if (Math.max(w, h) > lim.sheetSide) return at + ' 是 ' + w + '×' + h + ' px，边长最多 ' + lim.sheetSide + ' px';
    if (w % n.columns) return at + ' 宽 ' + w + ' px，不能分成 ' + n.columns + ' 列';
    if (h % n.rows) return at + ' 高 ' + h + ' px，不能分成 ' + n.rows + ' 行';
    if (Math.max(w / n.columns, h / n.rows) > lim.frameSide) return at + ' 每帧 ' + w / n.columns + '×' + h / n.rows + ' px，最多 ' + lim.frameSide + ' px';
    return null;
  }

  /**
   * The sheets of the pet the app reads, held to PetLimits as the app's import holds them (PetFiles): a file missing
   * or wrong refuses the pack. A <pet> the engine leaves out ([read]'s warning) the app copies nothing of, so no error.
   */
  function petProblems(L, lim, read0, d) {
    const pet = read(d.xml);
    if (!pet || !lim || (read0.warnings || []).some((w) => /^<pet> left out/.test(w.message))) return [];
    const out = [];
    for (const s of pet.states) {
      const name = get(s, 'name');
      const file = get(s, 'sheet');
      if (!NAMES.includes(name) || !file) continue;
      const bytes = d.files[file];
      const why = bytes ? sheetProblem(L, lim, name, file, bytes, s) : '缺少文件 ' + file;
      if (why) out.push({ level: 'error', line: 0, message: why });
    }
    return out;
  }

  const ops = {
    STATES,
    read,

    /** [d] with the pet's [attr] (place=, align=, size=, offset-x=, offset-y=) [value]; null takes it out (the default). */
    set(d, attr, value) {
      const pet = read(d.xml);
      if (!pet) return d;
      return panels.next(d, write(d.xml, { attrs: put(pet.attrs, attr, value), states: pet.states }));
    },

    /** [d] with [name]'s [attr] (frames=, columns=, fps=, loop=) [value]; null: the default. */
    setState(d, name, attr, value) {
      const pet = read(d.xml);
      if (!stateOf(pet, name)) return d;
      return panels.next(d, write(d.xml, { attrs: pet.attrs, states: pet.states.map((s) => (get(s, 'name') === name ? put(s, attr, value) : s)) }));
    },

    /**
     * [d] with the image [file] ([bytes]) [name]'s sheet, kept as `pet-<name>.<type>` (`-2`, `-3` where taken), a pet
     * made where it has none; a new state of square frames in a row is told so. Throws why where the app refuses it.
     */
    setSheet(d, L, lim, name, file, bytes) {
      const r = panels.raster(L, bytes);
      if (!r || !r.size) throw new Error(file + ' 不是 PNG 或 WebP');
      if (r.animated) throw new Error(file + ' 是动图：帧图要是静态的，一帧一格排在一张图上');
      let pet = read(d.xml);
      if (!pet && name !== 'idle') throw new Error('先选「平时」的帧图');
      pet = pet || { attrs: [], states: [] };
      let files = panels.filesOf(d);
      const kept = panels.fileName(files, 'pet-', name, r.type);
      const old = stateOf(pet, name);
      const [w, h] = r.size;
      const frames = w % h === 0 ? Math.min(w / h, lim.frames) : 1;
      const state = old ? put(old, 'sheet', kept) : put(put([['name', name], ['sheet', kept]], 'frames', frames), 'columns', frames);
      const why = sheetProblem(L, lim, name, kept, bytes, state);
      if (why) throw new Error(why);
      files[kept] = bytes;
      const states = old ? pet.states.map((s) => (s === old ? state : s)) : pet.states.concat([state]);
      const xml = write(d.xml, { attrs: pet.attrs, states });
      const was = old && get(old, 'sheet');
      files = panels.without(files, was && !F.isNamed(xml, was) ? [was] : []);
      return { xml, files, layouts: d.layouts };
    },

    /** [d] without [name]'s state; without idle, no pet. Its sheets go where nothing else names them. */
    remove(d, name) {
      const pet = read(d.xml);
      if (!pet) return d;
      const left = name === 'idle' ? [] : pet.states.filter((s) => get(s, 'name') !== name);
      const gone = pet.states.filter((s) => !left.includes(s)).map((s) => get(s, 'sheet')).filter(Boolean);
      const xml = write(d.xml, left.length ? { attrs: pet.attrs, states: left } : null);
      return { xml, files: panels.without(d.files, gone.filter((f) => !F.isNamed(xml, f))), layouts: d.layouts };
    },
  };

  root.YoumoPet = { petProblems, numbers, sheetProblem };

  // one playing at a time: a state's frames once (a looping one's three rounds), then idle's first frame again
  let playing = null;
  function draw(canvas, bmp, n, i) {
    const c = canvas.getContext('2d');
    const fw = bmp.width / n.columns;
    const fh = bmp.height / n.rows;
    const s = Math.min(canvas.width / fw, canvas.height / fh);
    c.clearRect(0, 0, canvas.width, canvas.height);
    c.imageSmoothingEnabled = true;
    c.drawImage(bmp, (i % n.columns) * fw, Math.floor(i / n.columns) * fh, fw, fh, (canvas.width - fw * s) / 2, (canvas.height - fh * s) / 2, fw * s, fh * s);
  }

  async function bitmap(L, lim, bytes) {
    const r = panels.raster(L, bytes);
    // the app's bounds before the browser decodes untrusted bytes
    if (!r || !r.size || Math.max(...r.size) > lim.sheetSide) return null;
    try { return await createImageBitmap(new Blob([bytes])); } catch (e) { return null; }
  }

  async function play(canvas, api, lim, pet, name) {
    if (playing) clearTimeout(playing);
    playing = null;
    const d = api.doc();
    const idle = stateOf(pet, 'idle');
    const s = stateOf(pet, name);
    const [ib, sb] = await Promise.all([idle, s].map((x) => x && d.files[get(x, 'sheet')] ? bitmap(api.lib, lim, d.files[get(x, 'sheet')]) : null));
    if (!sb) return;
    const n = numbers(s, lim);
    const total = n.frames * (n.loop ? 3 : 1);
    let i = 0;
    const step = () => {
      if (i >= total) {
        playing = null;
        if (ib) draw(canvas, ib, numbers(idle, lim), 0);
        return;
      }
      draw(canvas, sb, n, i % n.frames);
      i++;
      playing = setTimeout(step, 1000 / n.fps);
    };
    step();
  }

  function render(el, api) {
    const { h, row, select, number } = panels.ui;
    const d = api.doc();
    const lim = api.limits().pet;
    el.append(h('p', { className: 'muted', textContent: '主题可以带一只小宠物，待在键盘角上或候选栏右边：按字母、退格、回车、空格时各做一个小动作，30 秒不按会睡着。' +
      '每个动作一张图，帧从左到右、从上到下排，每帧一样大（PNG 或静态 WebP，单张不超过 ' + lim.sheetBytes / 1024 + ' KB、边长 ' + lim.sheetSide +
      ' px，每帧不超过 ' + lim.frameSide + ' px，所有帧合计不超过 ' + lim.totalFrames + ' 帧）。没给的动作就不动。App 里由「键盘动效」开关管：关着时只显示「平时」的第一帧。键盘预览上不画宠物。' }));
    const pet = read(d.xml);
    const pick = async (name) => {
      const f = await api.pickFile('.png,.webp,image/png,image/webp');
      if (!f) return;
      const picked = new Uint8Array(f.bytes);
      api.apply((x) => ops.setSheet(x, api.lib, lim, name, f.name, picked));
    };
    if (!pet) {
      el.append(row('平时', h('button', { type: 'button', textContent: '选帧图…', on: { click: () => pick('idle') } })));
      return;
    }
    const place = get(pet.attrs, 'place') || 'keyboard';
    const dp = (k) => (get(pet.attrs, k) != null ? parseFloat(get(pet.attrs, k)) : null);
    const len = (k) => (v) => api.apply((x) => ops.set(x, k, v == null ? null : v + 'dp'));
    el.append(row('位置', select([['keyboard', '键盘的角上'], ['bar-end', '候选栏右边']], place, (v) => api.apply((x) => ops.set(x, 'place', v === 'keyboard' ? null : v)))));
    if (place === 'keyboard') {
      el.append(row('哪个角', select(ALIGNS, get(pet.attrs, 'align') || 'top-end', (v) => api.apply((x) => ops.set(x, 'align', v === 'top-end' ? null : v)))));
      el.append(row('往里挪', number(dp('offset-x'), len('offset-x'), { min: -lim.offsetDp, max: lim.offsetDp, step: 1, unit: 'dp 横' }),
        number(dp('offset-y'), len('offset-y'), { min: -lim.offsetDp, max: lim.offsetDp, step: 1, unit: 'dp 竖' })));
    }
    el.append(row('大小', number(dp('size'), len('size'), { min: lim.minSizeDp, max: lim.maxSizeDp, step: 1, unit: 'dp（默认 40，候选栏右边时不超过栏高减 4）' })));
    const canvas = h('canvas', { width: 96, height: 96, className: 'pet-preview' });
    el.append(row('预览', canvas));
    const idle = stateOf(pet, 'idle');
    if (idle) bitmap(api.lib, lim, d.files[get(idle, 'sheet')] || new Uint8Array(0)).then((b) => b && draw(canvas, b, numbers(idle, lim), 0));
    const problems = petProblems(api.lib, lim, { warnings: [] }, d);
    for (const [name, label] of STATES) {
      const s = stateOf(pet, name);
      const file = s && get(s, 'sheet');
      const bad = file && problems.find((p) => p.message.includes(' ' + file) && p.message.includes(' ' + name + ' '));
      const status = !s ? '没有' : !d.files[file] ? file + '：缺少文件' : bad ? bad.message : file + '：' + numbers(s, lim).frames + ' 帧';
      el.append(row(label, h('span', { className: bad || (s && !d.files[file]) ? 'bad' : 'muted', textContent: status }),
        h('button', { type: 'button', textContent: '选帧图…', on: { click: () => pick(name) } }),
        s ? h('button', { type: 'button', textContent: '播放', on: { click: () => play(canvas, api, lim, pet, name) } }) : null,
        s ? h('button', { type: 'button', textContent: '去掉', on: { click: () => api.apply((x) => ops.remove(x, name)) } }) : null));
      if (!s) continue;
      const n = numbers(s, lim);
      const whole = (attr) => (v) => api.apply((x) => ops.setState(x, name, attr, v == null ? null : String(Math.round(v))));
      el.append(row('', number(n.frames, whole('frames'), { min: 1, max: lim.frames, unit: '帧' }),
        number(n.columns, whole('columns'), { min: 1, max: n.frames, unit: '列' }),
        number(n.fps, whole('fps'), { min: 1, max: RESTING.includes(name) ? lim.restingFps : lim.fps, unit: '帧/秒' }),
        name === 'idle' ? null : select([['', RESTING.includes(name) ? '循环' : '播一遍'], ['true', '循环'], ['false', '播一遍']], get(s, 'loop') || '',
          (v) => api.apply((x) => ops.setState(x, name, 'loop', v || null)))));
    }
  }

  panels.push({ id: 'pet', label: '宠物', ops, render });
})(typeof window !== 'undefined' ? window : globalThis);
