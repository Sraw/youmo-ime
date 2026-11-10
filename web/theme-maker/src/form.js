// youmo-form: what the editing's forms do to theme.xml (060-render-p3 §5.1, M2), apart from the page so node
// tests it. A form edit finds the <style> whose select= is exactly the text it writes and changes that
// attribute; none: a new style at the end. A style left with nothing but its select= goes. Everything else of
// the text (comments, the user's own styles, their spacing) is kept as it was: only the attribute changed moves.
(function (root) {
  'use strict';

  // the comments of [xml] blanked (same length), so what is looked for is never found inside one
  const uncommented = (xml) => xml.replace(/<!--[\s\S]*?-->/g, (c) => c.replace(/[^\n]/g, ' '));
  const unescape = (v) => v.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
  const escape = (v) => String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  const lineAt = (xml, i) => xml.slice(0, i).split('\n').length;
  const ATTR = /([\w:-]+)\s*=\s*("([^"]*)"|'([^']*)')/g;

  /** [tag]'s attributes in order: `[[name, value]]`, values unescaped. */
  function attrsOf(tag) {
    const out = [];
    for (const m of tag.matchAll(ATTR)) out.push([m[1], unescape(m[3] ?? m[4])]);
    return out;
  }

  /** The elements named [name] of [xml], not in comments: `[{start, end, line, tag, attrs}]` (end: past the element). */
  function elements(xml, name) {
    const plain = uncommented(xml);
    // a '>' inside a quoted value does not end the tag
    const re = new RegExp('<' + name + '\\b(?:[^>"\']|"[^"]*"|\'[^\']*\')*?(\\/>|>)', 'g');
    const out = [];
    for (const m of plain.matchAll(re)) {
      let end = m.index + m[0].length;
      if (m[1] === '>') {
        const close = plain.indexOf('</' + name + '>', end);
        end = close < 0 ? end : close + name.length + 3;
      }
      out.push({ start: m.index, end, line: lineAt(xml, m.index), tag: xml.slice(m.index, m.index + m[0].length), attrs: attrsOf(xml.slice(m.index, m.index + m[0].length)) });
    }
    return out;
  }

  /** The theme's styles in order: `[{start, end, line, select, attrs: [[name, value]]}]` (attrs without select). */
  function styles(xml) {
    return elements(xml, 'style').map((e) => ({ ...e, select: (e.attrs.find((a) => a[0] === 'select') || [])[1] ?? null, attrs: e.attrs.filter((a) => a[0] !== 'select') }));
  }

  // [xml] with [start, end) replaced by [text]; a removal takes its line's indent and its line break with it
  function splice(xml, start, end, text) {
    if (text === '') {
      const before = xml.slice(0, start).replace(/[ \t]*$/, '');
      const after = xml.slice(end);
      if (/\n$/.test(before) || before === '') return before + after.replace(/^[ \t]*\n/, '');
      return before + after;
    }
    return xml.slice(0, start) + text + xml.slice(end);
  }

  // an element's tag with attribute [name] [value] (null: gone): changed in place, a new one at its end
  function withAttr(tag, name, value) {
    const re = new RegExp('(\\s)' + name.replace(/[-]/g, '\\-') + '\\s*=\\s*("[^"]*"|\'[^\']*\')');
    if (re.test(tag)) {
      if (value == null) return tag.replace(new RegExp('\\s+' + name.replace(/[-]/g, '\\-') + '\\s*=\\s*("[^"]*"|\'[^\']*\')'), '');
      return tag.replace(re, (all, sp) => sp + name + '="' + escape(value) + '"');
    }
    if (value == null) return tag;
    return tag.replace(/\s*(\/?>)$/, (all, close) => ' ' + name + '="' + escape(value) + '"' + close);
  }

  // two select= the same text but for their spacing (the engine reads " key  .letter " as "key .letter")
  const spaced = (s) => String(s).trim().replace(/\s+/g, ' ');
  const sameSelect = (a, b) => a != null && b != null && spaced(a) === spaced(b);

  /** The style [select] gives [prop], null where no style of that select= (but for spacing) gives it (the last such style's). */
  function valueOf(xml, select, prop) {
    for (const s of styles(xml).reverse()) {
      if (!sameSelect(s.select, select)) continue;
      const a = s.attrs.find((x) => x[0] === prop);
      if (a) return a[1];
    }
    return null;
  }

  // the order theme.xml's elements go in (ThemeReader): one put in goes after the last of its kind or before
  const ORDER = ['meta', 'colors', 'picture', 'font', 'style', 'icon', 'sounds'];

  /** [xml] with [text] (an element) put in where [kind]'s go: after the last of it or of a kind before it, else first in <theme>. */
  function insert(xml, kind, text) {
    const plain = uncommented(xml);
    let at = -1;
    for (const k of ORDER.slice(0, ORDER.indexOf(kind) + 1)) for (const e of elements(xml, k)) at = Math.max(at, e.end);
    if (at < 0) {
      const open = /<theme\b[^>]*>/.exec(plain);
      if (!open) throw new Error('no <theme>');
      at = open.index + open[0].length;
    }
    return xml.slice(0, at) + '\n  ' + text + xml.slice(at);
  }

  /**
   * [xml] with the style of exactly [select] giving [prop] [value] (null: not giving it). The last style of that
   * select= that gives it is changed, else the last of that select=, else a new one at the end of the styles.
   * A style left with only its select= is taken out.
   */
  function setProp(xml, select, prop, value) {
    const all = styles(xml).filter((s) => sameSelect(s.select, select));
    const target = [...all].reverse().find((s) => s.attrs.some((a) => a[0] === prop)) || all[all.length - 1];
    if (!target) {
      if (value == null) return xml;
      return insert(xml, 'style', '<style select="' + escape(select) + '" ' + prop + '="' + escape(value) + '"/>');
    }
    if (value == null && !target.attrs.some((a) => a[0] === prop)) return xml;
    const tag = withAttr(target.tag, prop, value);
    const left = attrsOf(tag).filter((a) => a[0] !== 'select');
    const selfClosing = /\/>$/.test(target.tag);
    if (!left.length && selfClosing) return splice(xml, target.start, target.end, '');
    return splice(xml, target.start, target.start + target.tag.length, tag);
  }

  /** Several of [setProp]: `{prop: value}` on [select]. */
  function setProps(xml, select, values) {
    for (const [p, v] of Object.entries(values)) xml = setProp(xml, select, p, v);
    return xml;
  }

  // ---- colours ----

  /** The named colours (060-render-v2 §6), in the groups the 配色 page shows them. */
  const COLOR_GROUPS = [
    ['键盘', ['background', 'keyboard', 'spaceBar']],
    ['按键', ['key', 'altKey', 'accentKey', 'keyPressHighlight', 'keyShadow', 'keyBorder']],
    ['文字', ['keyText', 'altKeyText', 'accentKeyText', 'keyHint']],
    ['候选栏', ['bar', 'candidateText', 'candidateLabel', 'candidateComment', 'toolbarIcon']],
    ['弹出层', ['popupBackground', 'popupText', 'genericActiveBackground', 'genericActiveForeground']],
    ['其它', ['divider', 'clipboardEntry']],
  ];

  // the <colors> of [variant] ('light': the one without variant=)
  function colorsBlock(xml, variant) {
    return elements(xml, 'colors').find((e) => ((e.attrs.find((a) => a[0] === 'variant') || [])[1] || 'light') === variant) || null;
  }

  /** [variant]'s colours as written: `{name: value}` (the text: `#…`, `@name`, `darker(@key, 8%)`…). */
  function colors(xml, variant = 'light') {
    const b = colorsBlock(xml, variant);
    const out = {};
    if (!b) return out;
    for (const c of elements(xml.slice(0, b.end), 'color').filter((e) => e.start > b.start)) {
      const a = Object.fromEntries(c.attrs);
      if (a.name) out[a.name] = a.value;
    }
    return out;
  }

  /** [xml] with colour [name] of [variant] [value] (null: taken out); a <colors> made where there is none. */
  function setColor(xml, name, value, variant = 'light') {
    let b = colorsBlock(xml, variant);
    // a <colors …/> with nothing in it: an open and a close, so a colour has where to go
    if (b && /\/>$/.test(b.tag)) {
      if (value == null) return xml;
      xml = splice(xml, b.start, b.end, b.tag.replace(/\s*\/>$/, '>') + '\n  </colors>');
      b = colorsBlock(xml, variant);
    }
    if (!b) {
      if (value == null) return xml;
      xml = insert(xml, 'colors', variant === 'light' ? '<colors>\n  </colors>' : '<colors variant="' + variant + '">\n  </colors>');
      b = colorsBlock(xml, variant);
    }
    const inside = elements(xml.slice(0, b.end), 'color').filter((e) => e.start > b.start);
    const own = inside.find((c) => c.attrs.some((a) => a[0] === 'name' && a[1] === name));
    if (own) return value == null ? splice(xml, own.start, own.end, '') : splice(xml, own.start, own.start + own.tag.length, withAttr(own.tag, 'value', value));
    if (value == null) return xml;
    const close = xml.lastIndexOf('</colors>', b.end);
    const indent = inside.length ? (/[ \t]*$/.exec(xml.slice(0, inside[0].start))[0]) : '    ';
    const at = inside.length ? inside[inside.length - 1].end : close;
    const text = '<color name="' + escape(name) + '" value="' + escape(value) + '"/>';
    return inside.length ? xml.slice(0, at) + '\n' + indent + text + xml.slice(at) : xml.slice(0, at).replace(/[ \t]*$/, '') + indent + text + '\n  ' + xml.slice(at);
  }

  /** The <theme>'s variants= (`["light"]` where it says none). */
  function variants(xml) {
    const t = elements(xml, 'theme')[0];
    const v = t && (t.attrs.find((a) => a[0] === 'variants') || [])[1];
    return v ? v.split(/\s+/).filter(Boolean) : ['light'];
  }

  /**
   * [xml] with a dark version or without: on, variants="light dark" and a <colors variant="dark"> as a copy of the
   * light colours where it has none; off, variants="light" and the dark colours gone.
   */
  function withDark(xml, on) {
    const t = elements(xml, 'theme')[0];
    if (!t) throw new Error('no <theme>');
    xml = splice(xml, t.start, t.start + t.tag.length, withAttr(t.tag, 'variants', on ? 'light dark' : 'light'));
    const dark = colorsBlock(xml, 'dark');
    if (on && !dark) {
      const light = colors(xml, 'light');
      xml = insert(xml, 'colors', '<colors variant="dark">\n  </colors>');
      for (const [n, v] of Object.entries(light)) xml = setColor(xml, n, v, 'dark');
    } else if (!on && dark) xml = splice(xml, dark.start, dark.end, '');
    return xml;
  }

  /** `#rrggbb` (or `#aarrggbb`) of a colour picker's `#rrggbb` and an opacity 0–100. */
  function hexOf(rgb, opacity = 100) {
    const a = Math.round(Math.max(0, Math.min(100, opacity)) * 255 / 100);
    return a === 255 ? rgb.toLowerCase() : '#' + a.toString(16).padStart(2, '0') + rgb.slice(1).toLowerCase();
  }

  /** `{rgb: "#rrggbb", opacity}` of a literal colour, null for any other text (a name, an expression). */
  function parseHex(v) {
    const m = /^#([0-9a-f]{6}|[0-9a-f]{8})$/i.exec(String(v || '').trim());
    if (!m) return null;
    const h = m[1].toLowerCase();
    return h.length === 6 ? { rgb: '#' + h, opacity: 100 } : { rgb: '#' + h.slice(2), opacity: Math.round(parseInt(h.slice(0, 2), 16) * 100 / 255) };
  }

  // ---- the picture ----

  /** The theme's <picture>: `{src, brightness, blur, cuts: {portrait: {file, rect}, landscape: …}}`, null for none. */
  function picture(xml) {
    const p = elements(xml, 'picture')[0];
    if (!p) return null;
    const a = Object.fromEntries(p.attrs);
    const cuts = {};
    for (const c of elements(xml.slice(0, p.end), 'cut').filter((e) => e.start > p.start)) {
      const ca = Object.fromEntries(c.attrs);
      if (ca.orientation) cuts[ca.orientation] = { file: ca.file, rect: ca.rect ? ca.rect.split(/\s+/).map(Number) : null };
    }
    return { src: a.src, brightness: a.brightness ? parseFloat(a.brightness) : 100, blur: a.blur ? parseFloat(a.blur) : 0, cuts };
  }

  /**
   * [xml] with [p] as its <picture> (null: none). One there is changed in place: its src=, brightness=, blur= and
   * each cut's file= and rect=, so what else it says (an attribute of a later app's, a comment) is kept; none there, one
   * written. `{xml, gone}`: the files the old picture named that nothing names any more.
   */
  function setPicture(xml, p) {
    const old = elements(xml, 'picture')[0];
    const before = old ? xml.slice(old.start, old.end) : '';
    const cutText = (o, c) => '<cut orientation="' + o + '" file="' + escape(c.file) + '"' + (c.rect ? ' rect="' + c.rect.map(Math.round).join(' ') + '"' : '') + '/>';
    if (old && p) {
      let tag = withAttr(old.tag, 'src', p.src);
      tag = withAttr(tag, 'brightness', p.brightness != null && p.brightness !== 100 ? p.brightness + '%' : null);
      tag = withAttr(tag, 'blur', p.blur ? p.blur + 'dp' : null);
      // a picture put in place of the old one says whether it is animated; one only tuned or cut keeps what it had
      if ('animated' in p) tag = withAttr(tag, 'animated', p.animated ? 'true' : null);
      // a <picture …/>: an open and a close, so a cut has where to go
      const closed = /\/>$/.test(old.tag);
      let body = closed ? '\n  </picture>' : xml.slice(old.start + old.tag.length, old.end);
      if (closed) tag = tag.replace(/\s*\/>$/, '>');
      for (const o of ['portrait', 'landscape']) {
        const c = p.cuts[o];
        const cut = elements(body, 'cut').find((e) => e.attrs.some((a) => a[0] === 'orientation' && a[1] === o));
        if (cut && !c) body = splice(body, cut.start, cut.end, '');
        else if (cut) {
          const t = withAttr(withAttr(cut.tag, 'file', c.file), 'rect', c.rect ? c.rect.map(Math.round).join(' ') : null);
          body = body.slice(0, cut.start) + t + body.slice(cut.start + cut.tag.length);
        } else if (c) {
          const close = body.lastIndexOf('</picture>');
          body = body.slice(0, close).replace(/\s*$/, '') + '\n    ' + cutText(o, c) + '\n  ' + body.slice(close);
        }
      }
      xml = xml.slice(0, old.start) + tag + body + xml.slice(old.end);
    } else {
      let text = '';
      if (p) {
        text = '<picture src="' + escape(p.src) + '"' + (p.brightness != null && p.brightness !== 100 ? ' brightness="' + p.brightness + '%"' : '') +
          (p.blur ? ' blur="' + p.blur + 'dp"' : '') + (p.animated ? ' animated="true"' : '') + '>';
        for (const o of ['portrait', 'landscape']) if (p.cuts[o]) text += '\n    ' + cutText(o, p.cuts[o]);
        text += '\n  </picture>';
      }
      if (old) xml = splice(xml, old.start, old.end, text);
      else if (text) xml = insert(xml, 'picture', text);
    }
    const gone = [...before.matchAll(/\b(?:src|file)\s*=\s*(?:"([^"]+)"|'([^']+)')/g)].map((m) => unescape(m[1] ?? m[2])).filter((f) => !isNamed(xml, f));
    return { xml, gone: [...new Set(gone)] };
  }

  // the <sound> of [key] in [xml], null where none
  const soundOf = (xml, key) => elements(xml, 'sound').find((e) => e.attrs.some((a) => a[0] === 'key' && a[1] === key)) || null;

  /** The theme's key sounds: `{key: src}` (a key the app does not know yet too: it keeps those). */
  const sounds = (xml) => Object.fromEntries(elements(xml, 'sound').map((e) => Object.fromEntries(e.attrs)).filter((a) => a.key).map((a) => [a.key, a.src]));

  /**
   * [xml] without the <sound> of [key]; its <sounds> taken out too once it holds none (060-theme-sounds-icons §1.5).
   * `{xml, gone}`: the file it named where nothing names it any more.
   */
  function removeSound(xml, key) {
    const old = soundOf(xml, key);
    if (!old) return { xml, gone: [] };
    const src = (old.attrs.find((a) => a[0] === 'src') || [])[1];
    xml = splice(xml, old.start, old.end, '');
    const box = elements(xml, 'sounds')[0];
    if (box && !elements(xml.slice(box.start, box.end), 'sound').length) xml = splice(xml, box.start, box.end, '');
    return { xml, gone: src && !isNamed(xml, src) ? [src] : [] };
  }

  /**
   * [xml] with [src] the sound of [key], in its <sounds> (one made where there is none, in ThemeReader's order).
   * `{xml, gone}` as removeSound's: the file the key had before.
   */
  function setSound(xml, key, src) {
    const before = soundOf(xml, key);
    const was = before && (before.attrs.find((a) => a[0] === 'src') || [])[1];
    const tag = '<sound key="' + escape(key) + '" src="' + escape(src) + '"/>';
    if (before) xml = xml.slice(0, before.start) + tag + xml.slice(before.end);
    else {
      const box = elements(xml, 'sounds')[0];
      if (!box) xml = insert(xml, 'sounds', '<sounds>\n    ' + tag + '\n  </sounds>');
      else if (/\/>$/.test(box.tag)) xml = xml.slice(0, box.start) + box.tag.replace(/\s*\/>$/, '>') + '\n    ' + tag + '\n  </sounds>' + xml.slice(box.end);
      else {
        const close = box.end - '</sounds>'.length;
        xml = xml.slice(0, close).replace(/\s*$/, '') + '\n    ' + tag + '\n  ' + xml.slice(close);
      }
    }
    return { xml, gone: was && was !== src && !isNamed(xml, was) ? [was] : [] };
  }

  /**
   * The crop on a picture [w]×[h] for a keyboard [ratio] (width / height): the biggest box of that ratio, made
   * [zoom] (≥ 1) times smaller, its centre at ([cx], [cy]) (0–1 of the picture) kept inside it. `[l, t, r, b]` px.
   */
  function cropRect(w, h, ratio, zoom = 1, cx = 0.5, cy = 0.5) {
    let cw = Math.min(w, h * ratio);
    let ch = cw / ratio;
    const z = Math.max(1, zoom);
    cw /= z;
    ch /= z;
    const l = Math.max(0, Math.min(w - cw, cx * w - cw / 2));
    const t = Math.max(0, Math.min(h - ch, cy * h - ch / 2));
    return [l, t, l + cw, t + ch].map((v) => Math.round(v));
  }

  // ---- the forms' values ----

  /**
   * The bounds the engine reads a style's numbers in (its Values; YoumoEngine.limits() says the same, form.test
   * holds the two together): what the forms offer and write.
   */
  const LIMITS = {
    lengthDp: 24, offsetDp: 16, borderDp: 3, blurDp: 25, depthDp: 8, scale: [85, 130], spacingEm: [-0.1, 0.5], stops: [2, 8],
  };

  /** [v] kept within [lo]..[hi] and rounded to [digits] decimals: a number as the engine reads it. */
  function clamp(v, lo, hi, digits = 0) {
    const k = 10 ** digits;
    return Math.round(Math.max(lo, Math.min(hi, +v || 0)) * k) / k;
  }

  /**
   * A fill's text: `{kind: solid, color}`, `{kind: linear, angle, stops: [[color, at%]]}`, `{kind: picture, blur?,
   * brightness?, over?}`; its numbers kept to what the engine reads (an angle of 3 digits, 0–100 %, a blur to 25 dp).
   */
  function fillText(f) {
    if (f.kind === 'solid') return f.color;
    if (f.kind === 'linear') return 'linear(' + clamp(f.angle, -360, 360) + 'deg, ' + f.stops.map(([c, at]) => c + ' ' + clamp(at, 0, 100) + '%').join(', ') + ')';
    if (f.kind === 'picture') return 'picture(' + clamp(f.blur ?? 0, 0, LIMITS.blurDp, 1) + 'dp, ' + clamp(f.brightness ?? 100, 0, 100) + '%' + (f.over ? ', ' + f.over : '') + ')';
    if (f.kind === 'image') {
      return 'image(' + f.file + ', ' + f.insets.map((v) => clamp(v, 0, 9999)).join(' ') + (f.scale && f.scale !== 1 ? ', ' + clamp(f.scale, 1, 8) + 'x' : '') + (f.fallback ? ', ' + f.fallback : '') + ')';
    }
    throw new Error('no fill of kind ' + f.kind);
  }

  /**
   * Whether [xml] (not its comments) still names the file [file]: as an attribute's whole value (src=, file=,
   * preview=, an icon's), or as a function's first argument (image(…), ripple-image(…), particles(…)). The one rule
   * every page uses before a file goes with what named it.
   */
  function isNamed(xml, file) {
    const plain = uncommented(xml);
    const lit = (v) => v.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return new RegExp('(["\'])(?:' + lit(file) + '|' + lit(escape(file)) + ')\\1|\\(\\s*(["\']?)' + lit(file) + '\\2\\s*[,)]').test(plain);
  }

  /** [text] (a fill=) as fillText takes it; `{kind: other, text}` for what the form does not edit (an image). */
  function parseFill(text) {
    if (text == null) return null;
    const t = text.trim();
    let m = /^linear\((-?\d{1,3}(?:\.\d+)?)deg,\s*(.+)\)$/.exec(t);
    if (m) {
      const stops = splitTop(m[2]).map((s) => { const x = /^(.*\S)\s+(-?[\d.]+)%$/.exec(s.trim()); return x ? [x[1], +x[2]] : null; });
      if (stops.every(Boolean)) return { kind: 'linear', angle: +m[1], stops };
    }
    // a bare picture: the engine's, unblurred and undimmed
    if (t === 'picture') return { kind: 'picture', blur: 0, brightness: 100, over: null };
    m = /^picture\(([\s\S]*)\)$/.exec(t);
    if (m) {
      const [blur, brightness, over] = splitTop(m[1]).map((s) => s.trim());
      return { kind: 'picture', blur: parseFloat(blur) || 0, brightness: brightness ? parseFloat(brightness) : 100, over: over || null };
    }
    m = /^image\(([^,()]+),\s*(\d{1,4}) (\d{1,4}) (\d{1,4}) (\d{1,4})(?:,\s*([1-8])x)?(?:,\s*(.+))?\)$/.exec(t);
    if (m) return { kind: 'image', file: m[1].trim(), insets: [+m[2], +m[3], +m[4], +m[5]], scale: m[6] ? +m[6] : 1, fallback: m[7] ? m[7].trim() : null };
    if (/^(image|nine)\(/.test(t)) return { kind: 'other', text: t };
    return { kind: 'solid', color: t };
  }

  // [s] split at its commas not inside brackets
  function splitTop(s) {
    const out = [];
    let depth = 0;
    let from = 0;
    for (let i = 0; i < s.length; i++) {
      if (s[i] === '(') depth++;
      else if (s[i] === ')') depth--;
      else if (s[i] === ',' && depth === 0) { out.push(s.slice(from, i)); from = i + 1; }
    }
    out.push(s.slice(from));
    return out;
  }

  /** `dx dy blur colour` (dp) of a shadow, `none` where it has no colour. */
  function shadowText(s) {
    const o = LIMITS.offsetDp;
    return s && s.color ? clamp(s.dx, -o, o, 1) + 'dp ' + clamp(s.dy, -o, o, 1) + 'dp ' + clamp(s.blur, 0, o, 1) + 'dp ' + s.color : 'none';
  }

  /** A shadow= text read: `{dx, dy, blur, color}`, null for none or what is not one. */
  function parseShadow(t) {
    const m = /^(-?[\d.]+)(?:dp)?\s+(-?[\d.]+)(?:dp)?\s+([\d.]+)(?:dp)?\s+(.+)$/.exec(String(t || '').trim());
    return m ? { dx: +m[1], dy: +m[2], blur: +m[3], color: m[4] } : null;
  }

  /** A border=/stroke= text: `width colour`, `none` for no width. */
  function borderText(b) {
    return b && b.width > 0 && b.color ? clamp(b.width, 0, LIMITS.borderDp, 1) + 'dp ' + b.color : 'none';
  }

  function parseBorder(t) {
    const m = /^([\d.]+)(?:dp)?\s+(.+)$/.exec(String(t || '').trim());
    return m ? { width: +m[1], color: m[2] } : null;
  }

  /** An inset= (or padding=) text read: `[top, right, bottom, left]` dp, null for what is none (CSS's 1, 2 or 4 values). */
  function parseInset(t) {
    const v = String(t ?? '').trim().split(/\s+/).map((x) => (x === '0' ? 0 : /^-?[\d.]+dp$/.test(x) ? parseFloat(x) : NaN));
    if (!t || !v.every(Number.isFinite)) return null;
    if (v.length === 1) return [v[0], v[0], v[0], v[0]];
    if (v.length === 2) return [v[0], v[1], v[0], v[1]];
    return v.length === 4 ? v : null;
  }

  /** `[top, right, bottom, left]` as the shortest inset= text that says it. */
  function insetText(v, max = LIMITS.lengthDp) {
    const [t, r, b, l] = v.map((x) => clamp(x, 0, max, 1) + 'dp');
    if (t === b && r === l) return t === r ? t : t + ' ' + r;
    return [t, r, b, l].join(' ');
  }

  /** A radius= text read: its four corners dp (top left, top right, bottom right, bottom left); null for none or `50%`. */
  function parseRadius(t) {
    const v = String(t ?? '').trim().split(/\s+/).map((x) => (x === '0' ? 0 : /^[\d.]+dp$/.test(x) ? parseFloat(x) : NaN));
    if (!t || !v.every(Number.isFinite)) return null;
    if (v.length === 1) return [v[0], v[0], v[0], v[0]];
    return v.length === 4 ? v : null;
  }

  /** Four corners as the shortest radius= text. */
  function radiusText(v) {
    const c = v.map((x) => clamp(x, 0, LIMITS.lengthDp, 1) + 'dp');
    return c.every((x) => x === c[0]) ? c[0] : c.join(' ');
  }

  // ---- the theme's <meta> ----

  /** The <meta>'s attributes (author, version, license, min-app, preview): `{name: value}`, {} for none. */
  function meta(xml) {
    const m = elements(xml, 'meta')[0];
    return m ? Object.fromEntries(m.attrs) : {};
  }

  /** [xml] with <meta>'s [name] [value] (null or empty: taken out); a <meta/> made first where none, one left empty taken out. */
  function setMeta(xml, name, value) {
    if (value === '') value = null;
    let m = elements(xml, 'meta')[0];
    if (!m) {
      if (value == null) return xml;
      xml = insert(xml, 'meta', '<meta/>');
      m = elements(xml, 'meta')[0];
    }
    const tag = withAttr(m.tag, name, value);
    if (!attrsOf(tag).length && /\/>$/.test(tag)) return splice(xml, m.start, m.end, '');
    return splice(xml, m.start, m.start + m.tag.length, tag);
  }

  // ---- selectors ----

  /** A key's name in a selector: `\` before what would be read as the grammar's (a comma, a star, a colon…). */
  const keyName = (id) => id.replace(/[\\,*:.[\]@\s/]/g, (c) => '\\' + c);

  /** The ranges the 按键 and 字和图标 pages edit: what of the keyboard a style selects. */
  const RANGES = [
    ['all', '全部键'], ['letter', '字母'], ['function', '功能'], ['space', '空格'], ['enter', '回车'], ['picked', '选中的这个键'], ['board', '这块键盘的全部'],
  ];
  /** The scenes a range may be narrowed to (`@…`). */
  const SCENES = [['', '任何时候'], ['compact', '紧凑时'], ['landscape', '横屏时'], ['flat', '平铺时'], ['floating', '悬浮时'], ['split', '分体时'], ['one-handed', '单手时'], ['dark', '深色时'], ['field=password', '密码框里']];
  /** The states (`:…`). */
  const STATES = [['', '普通'], ['pressed', '按下'], ['locked', '锁定'], ['shifted', 'Shift']];

  /**
   * The selector a page's choices write: [range] (RANGES) of [board] (and [key], the picked one's id), in [scene]
   * (SCENES), in [state] (STATES), of [part] (a key's part, none: its face). Null where the range needs a key or a board it has not.
   */
  function selector({ range, board, key, scene = '', state = '', part = '' }) {
    let w;
    if (range === 'all') w = 'key';
    else if (range === 'picked') { if (!board || !key) return null; w = board + '/' + keyName(key); }
    else if (range === 'board') { if (!board) return null; w = board + '/*'; }
    else w = 'key.' + range;
    return (scene ? '@' + scene + ' ' : '') + w + (state ? ':' + state : '') + (part ? ' ' + part : '');
  }

  root.YoumoForm = {
    uncommented, styles, elements, valueOf, setProp, setProps, insert, sameSelect, LIMITS, clamp,
    COLOR_GROUPS, colors, setColor, variants, withDark, hexOf, parseHex,
    picture, setPicture, cropRect, isNamed, sounds, setSound, removeSound,
    fillText, parseFill, shadowText, parseShadow, borderText, parseBorder, parseInset, insetText, parseRadius, radiusText,
    meta, setMeta,
    keyName, RANGES, SCENES, STATES, selector,
  };
})(typeof window !== 'undefined' ? window : globalThis);
