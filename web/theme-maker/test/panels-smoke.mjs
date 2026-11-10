// The editing's tabs in a real browser (060-render-p3 M2): a field's edit commits on leaving it, and what the user
// does next is kept: the characters typed into the next field, the box clicked right after. A tab drawn anew under
// the user (every change did that) took the field just entered away and the click with it. Local only, not CI: it
// needs a Chromium (no jsdom here: no npm). Run: node web/theme-maker/test/panels-smoke.mjs [chromium]
// (default: Playwright's headless shell, as pixel-smoke.mjs finds it; CHROMIUM=<path> too). Exit 0 all kept, 1 not, 2 not run.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { serve } from '../dev/serve.mjs';
import { chromium } from './pixel-smoke.mjs';

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function browser(shell) {
  const dir = mkdtempSync(join(tmpdir(), 'panels-smoke-'));
  const proc = spawn(shell, ['--headless', '--disable-gpu', '--remote-debugging-port=0', '--user-data-dir=' + dir, '--window-size=1400,1000', 'about:blank'], { stdio: 'ignore' });
  const file = join(dir, 'DevToolsActivePort');
  for (let i = 0; i < 100 && !existsSync(file); i++) await wait(100);
  const port = readFileSync(file, 'utf8').split('\n')[0];
  const pages = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json();
  const ws = new WebSocket(pages.find((p) => p.type === 'page').webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener('open', r));
  let id = 0;
  const waiting = new Map();
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(e.data);
    if (m.id && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); }
  });
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const n = ++id;
    waiting.set(n, (m) => (m.error ? reject(new Error(method + ': ' + m.error.message)) : resolve(m.result)));
    ws.send(JSON.stringify({ id: n, method, params }));
  });
  const close = () => { ws.close(); proc.kill(); rmSync(dir, { recursive: true, force: true }); };
  return { send, close };
}

async function main() {
  const shell = chromium();
  if (!shell) {
    console.log('no headless Chromium (Playwright\'s headless shell, or CHROMIUM=<path>): not run');
    return 2;
  }
  const server = await serve(0);
  const b = await browser(shell);
  const js = async (expr) => {
    const r = await b.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
    if (r.exceptionDetails) throw new Error(expr + ': ' + JSON.stringify(r.exceptionDetails.exception && r.exceptionDetails.exception.description));
    return r.result.value;
  };
  // the field or box in the row labelled [label] of the tab shown: its middle on the page
  const at = (label, tag = 'input') => js(`(() => {
    const row = [...document.querySelectorAll('#edit .tab:not([hidden]) label.row')].find((r) => r.querySelector('.what').textContent === ${JSON.stringify(label)});
    const e = row.querySelector(${JSON.stringify(tag)});
    e.scrollIntoView({ block: 'center' });
    const r = e.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  })()`);
  const click = async (label, tag) => {
    const { x, y } = await at(label, tag);
    for (const type of ['mousePressed', 'mouseReleased']) await b.send('Input.dispatchMouseEvent', { type, x, y, button: 'left', clickCount: 1 });
  };
  const type = async (text) => { await b.send('Input.insertText', { text }); };
  const source = () => js(`document.getElementById('source').value`);
  const style = async () => (/<style select="key label"[^>]*\/>/.exec(await source()) || [''])[0];
  let failed = 0;
  const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) failed++; };
  try {
    await b.send('Page.navigate', { url: 'http://127.0.0.1:' + server.address().port + '/page.html' });
    for (let i = 0; i < 100 && !(await js(`!!document.querySelector('#builtins button')`)); i++) await wait(100);
    await js(`document.fonts.ready.then(() => 1)`);
    await js(`(document.getElementById('new-panel').open || document.getElementById('new-panel').showModal(), document.querySelector('#builtins button').click())`);
    await js(`document.querySelector('#tabs [data-tab=text]').click()`);
    await wait(300);

    // typed into 大小, then into 字距 (clicked): both kept, the second's characters not wiped
    await click('大小');
    await type('120');
    await click('字距');
    await type('0.1');
    await wait(500);
    const typed = await js(`document.activeElement.tagName + ' ' + document.activeElement.value + ' ' + (document.activeElement.closest('label.row') || {}).textContent`);
    check(/ 0.1 /.test(typed), 'the next field keeps what is typed into it after an edit: ' + typed);
    await click('颜色');
    for (let i = 0; i < 20 && !/scale="120%"/.test(await style()); i++) await wait(100);
    const s = await style();
    check(/scale="120%"/.test(s) && /letter-spacing="0.1em"/.test(s), 'both edits written: ' + s);

    // typed into 大小, then the box clicked: the click acts
    await click('大小');
    await js(`document.activeElement.select()`);
    await type('110');
    await click('藏起来');
    await wait(300);
    const t = await style();
    check(/scale="110%"/.test(t) && /hidden="true"/.test(t), 'a click right after an edit acts: ' + t);

    // the same on touch: there the old field's change comes after the new field's pointerup
    const tap = async (label) => {
      const { x, y } = await at(label);
      await b.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
      await wait(60);
      await b.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      await wait(60);
    };
    await b.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    await click('藏起来');
    await tap('大小');
    await js(`document.activeElement.select()`);
    await type('115');
    await tap('字距');
    await js(`document.activeElement.select()`);
    await type('0.2');
    await wait(500);
    const touched = await js(`document.activeElement.value + ' ' + (document.activeElement.closest('label.row') || {}).textContent`);
    check(/^0.2 字距/.test(touched), 'touch: the next field keeps what is typed into it: ' + touched);
    await tap('颜色');
    await wait(300);
    const u = await style();
    check(/scale="115%"/.test(u) && /letter-spacing="0.2em"/.test(u), 'touch: both edits written: ' + u);
    await tap('大小');
    await js(`document.activeElement.select()`);
    await type('105');
    await tap('藏起来');
    await wait(300);
    const v = await style();
    check(/scale="105%"/.test(v) && /hidden="true"/.test(v), 'touch: a tap right after an edit acts: ' + v);

    // undo with a field focused: every field shows the document undone, the focused one too
    await tap('大小');
    await js(`document.getElementById('undo').click()`);
    await js(`document.getElementById('undo').click()`);
    await wait(300);
    const shown = await js(`[...document.querySelectorAll('#edit .tab:not([hidden]) label.row')].find((r) => r.querySelector('.what').textContent === '大小').querySelector('input').value`);
    check(/scale="115%"/.test(await style()) && shown === '115', 'undo: the focused field shows the document\'s ' + shown);
    await b.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'ArrowUp', code: 'ArrowUp', windowsVirtualKeyCode: 38 });
    await b.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'ArrowUp', code: 'ArrowUp', windowsVirtualKeyCode: 38 });
    await tap('颜色');
    await wait(300);
    check(/scale="120%"/.test(await style()), 'undo: a step after it goes from the document\'s value: ' + await style());
    await b.send('Emulation.setTouchEmulationEnabled', { enabled: false });

    // Ctrl+Z in a field typed in and not committed is the field's own undo, not the document's (m3 L-a)
    await click('大小');
    await js(`document.activeElement.select()`);
    await type('133');
    const steps = await js(`document.getElementById('undo').disabled`);
    for (const t of ['rawKeyDown', 'keyUp']) await b.send('Input.dispatchKeyEvent', { type: t, key: 'z', code: 'KeyZ', windowsVirtualKeyCode: 90, modifiers: 2 });
    await wait(300);
    check(/scale="120%"/.test(await style()) && steps === await js(`document.getElementById('undo').disabled`), 'Ctrl+Z in a field typed in leaves the document: ' + await style());
    await js(`document.activeElement.blur()`);
    await wait(300);

    // a colour renamed in the source, then undone on 配色: its row edits the colour it names, not the one it was drawn for
    await js(`document.querySelector('#tabs [data-tab=colors]').click()`);
    await wait(200);
    await click('加一个');
    await type('aaa');
    await js(`[...document.querySelectorAll('#tab-colors button')].find((x) => x.textContent === '加').click()`);
    await wait(300);
    await js(`(() => { const s = document.getElementById('source'); s.value = s.value.replace('name="aaa"', 'name="bbb"'); s.dispatchEvent(new Event('input')); })()`);
    await wait(700);
    await js(`document.querySelector('#tabs [data-tab=colors]').click()`);
    await wait(200);
    await js(`document.getElementById('undo').click()`);
    await wait(300);
    await click('aaa', 'input.expr');
    await js(`document.activeElement.select()`);
    await type('#123456');
    await click('加一个');
    await wait(300);
    const src = await source();
    check(/name="aaa" value="#123456"/.test(src) && !/name="bbb"/.test(src), 'undo a rename: the row edits the colour it shows');

    // a picture replaced by another of its size: the crop shows the new one
    await js(`document.querySelector('#tabs [data-tab=background]').click()`);
    await wait(200);
    const choose = (color) => js(`(async () => {
      const oc = new OffscreenCanvas(800, 400); const g = oc.getContext('2d'); g.fillStyle = '${color}'; g.fillRect(0, 0, 800, 400);
      const blob = await oc.convertToBlob({ type: 'image/png' });
      const inp = document.getElementById('image-input');
      inp.click = function () { const dt = new DataTransfer(); dt.items.add(new File([blob], 'p.png', { type: 'image/png' })); this.files = dt.files; this.onchange(); };
      [...document.querySelectorAll('#tab-background button')].find((x) => /图片…/.test(x.textContent)).click();
      return 1;
    })()`);
    const centre = () => js(`(() => { const c = document.querySelector('#tab-background canvas.crop-canvas'); if (!c) return 'none'; const d = c.getContext('2d').getImageData(c.width / 2 | 0, c.height / 2 | 0, 1, 1).data; return d[0] + ',' + d[1] + ',' + d[2]; })()`);
    await choose('#ff0000');
    for (let i = 0; i < 50 && (await centre()) === 'none'; i++) await wait(200);
    await wait(800);
    const red = await centre();
    await choose('#0000ff');
    await wait(2500);
    const blue = await centre();
    check(/^25[45],0,0$/.test(red) && /^0,0,25[45]$/.test(blue), 'a picture replaced: the crop shows the new one (' + red + ' then ' + blue + ')');

    // 用这个裁切 replaces the crop's box whole: the focus goes to the new box's button, not to the page (R8)
    await js(`(() => { const c = document.querySelector('#tab-background canvas.crop-canvas'); c.dispatchEvent(new PointerEvent('pointerdown', { clientX: 10, clientY: 10, bubbles: true })); c.dispatchEvent(new PointerEvent('pointermove', { clientX: 30, clientY: 20, bubbles: true })); c.dispatchEvent(new PointerEvent('pointerup', { clientX: 30, clientY: 20, bubbles: true })); })()`);
    await js(`(() => { const x = [...document.querySelectorAll('#tab-background button')].find((y) => y.textContent === '用这个裁切'); x.focus(); x.click(); })()`);
    await wait(1500);
    const focused = await js(`document.activeElement === document.body ? 'body' : document.activeElement.tagName + ' ' + document.activeElement.textContent`);
    check(focused !== 'body' && !!(await js(`document.getElementById('tab-background').contains(document.activeElement)`)), '用这个裁切: the focus stays in the tab (' + focused + ')');

    // M3's tabs: each draws; an edit on one writes its style and keeps the tab (the row synced in place)
    for (const id of ['bar', 'popup', 'window', 'size', 'motion', 'info']) {
      await js(`document.querySelector('#tabs [data-tab=${id}]').click()`);
      await wait(300);
      const rows = await js(`document.querySelectorAll('#tab-${id} label.row').length`);
      check(rows > 0, id + ': the tab draws (' + rows + ' rows)');
    }
    await js(`document.querySelector('#tabs [data-tab=info]').click()`);
    await wait(200);
    await click('作者');
    await type('smoke');
    await click('版本');
    await wait(400);
    check(/<meta [^>]*author="smoke"/.test(await source()), '信息: the author written to <meta>');
    check(await js(`document.activeElement.closest('label.row') && document.activeElement.closest('label.row').querySelector('.what').textContent === '版本'`), '信息: the next field keeps the focus after the edit');
    await js(`document.querySelector('#tabs [data-tab=motion]').click()`);
    await wait(200);
    const played = await js(`(async () => { [...document.querySelectorAll('#tab-motion button')].find((x) => x.textContent === '试一下').click(); await new Promise((ok) => setTimeout(ok, 1200)); return document.getElementById('preview').width; })()`);
    check(played > 0, '动效: 试一下 plays and ends with the keys drawn');
    // the environment's bar sample: the preview grows by the bar
    const h0 = await js(`document.getElementById('preview').height`);
    await js(`(() => { const s = document.getElementById('env-bar'); s.value = 'typing'; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
    await wait(500);
    const h1 = await js(`document.getElementById('preview').height`);
    await js(`(() => { const s = document.getElementById('env-show'); s.value = 'preview'; s.dispatchEvent(new Event('change', { bubbles: true })); const t = document.getElementById('env-sheet'); t.value = 'clipboard'; t.dispatchEvent(new Event('change', { bubbles: true })); })()`);
    await wait(500);
    check(h1 > h0, '环境: typing: the code strip over the bar (' + h0 + ' → ' + h1 + ')');

    // an edit that adds controls (描边's colour once it has a width) draws the tab anew: the field tapped or clicked
    // next keeps its focus and what is typed into it (touch and mouse)
    await js(`document.querySelector('#tabs [data-tab=popup]').click()`);
    await wait(300);
    const pos = (label, nth) => js(`(() => { const row = [...document.querySelectorAll('#edit .tab:not([hidden]) label.row')].find((r) => r.querySelector('.what').textContent === ${JSON.stringify(label)}); const e = row.querySelectorAll('input')[${nth}]; e.scrollIntoView({ block: 'center' }); const r = e.getBoundingClientRect(); return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; })()`);
    const press = async (touch, label, nth = 0) => {
      const { x, y } = await pos(label, nth);
      if (touch) {
        await b.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
        await wait(60);
        await b.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      } else for (const t of ['mousePressed', 'mouseReleased']) await b.send('Input.dispatchMouseEvent', { type: t, x, y, button: 'left', clickCount: 1 });
      await wait(60);
    };
    const bubble = async () => (/<style select="preview"[^>]*\/>/.exec(await source()) || [''])[0];
    for (const touch of [true, false]) {
      const how = touch ? 'touch' : 'mouse';
      await b.send('Emulation.setTouchEmulationEnabled', { enabled: touch, maxTouchPoints: 5 });
      while (/border=|shadow=/.test(await bubble()) && !(await js(`document.getElementById('undo').disabled`))) { await js(`document.getElementById('undo').click()`); await wait(200); }
      await press(touch, '描边');
      await js(`document.activeElement.select && document.activeElement.select()`);
      await type('1');
      await press(touch, '阴影', 0);
      await js(`document.activeElement.select && document.activeElement.select()`);
      await type('2');
      await wait(400);
      const on = await js(`document.activeElement.value + ' ' + document.activeElement.closest('label.row')?.querySelector('.what')?.textContent`);
      check(/^2 阴影$/.test(on), how + ': a tab drawn anew keeps the field tapped and what is typed into it: ' + on);
      await press(touch, '圆角');
      for (let i = 0; i < 20 && !/shadow=/.test(await bubble()); i++) await wait(100);
      const st = await bubble();
      check(/border="1dp/.test(st) && /shadow="0dp 2dp/.test(st), how + ': both written: ' + st);
    }
    await b.send('Emulation.setTouchEmulationEnabled', { enabled: false });

    // 从一张图片开始 twice, with pictures of two colours: each time the colours are the new picture's, in one step
    const fromImage = (colour) => js(`(async () => {
      const panel = document.getElementById('new-panel'); if (!panel.open) panel.showModal();
      document.querySelector('input[name=image-tone][value=dark]').click();
      const oc = new OffscreenCanvas(800, 400); const g = oc.getContext('2d'); g.fillStyle = '${colour}'; g.fillRect(0, 0, 800, 400);
      const blob = await oc.convertToBlob({ type: 'image/png' });
      const inp = document.getElementById('image-input');
      inp.click = function () { const dt = new DataTransfer(); dt.items.add(new File([blob], 'p.png', { type: 'image/png' })); this.files = dt.files; this.onchange(); };
      document.getElementById('from-image').click();
      return 1;
    })()`);
    for (const [colour, accent] of [['#e05010', /#e0501[01]/], ['#1040e0', /#1040e[01]/]]) {
      await fromImage(colour);
      for (let i = 0; i < 50 && !accent.test((/<color name="accentKey"[^>]*>/.exec(await source()) || [''])[0]); i++) await wait(100);
      const got = (/<color name="accentKey"[^>]*>/.exec(await source()) || [''])[0];
      check(accent.test(got) && (await source()).includes('#4d000000'), '从一张图片开始 ' + colour + ': the colours are the new picture\'s (' + got + ')');
    }
    await js(`document.getElementById('undo').click()`);
    await wait(500);
    check(/#e0501[01]/.test((/<color name="accentKey"[^>]*>/.exec(await source()) || [''])[0]), '从一张图片开始: the picture and its colours one step (one undo: the first picture\'s)');

    // 信息 › 从预览截一张 over a photo-like picture (noise: its PNG is past 512 KB): taken smaller or as WebP, within the App's limit
    await js(`(async () => {
      const panel = document.getElementById('new-panel'); if (!panel.open) panel.showModal();
      const oc = new OffscreenCanvas(1600, 1000); const g = oc.getContext('2d'); const im = g.createImageData(1600, 1000);
      for (let i = 0; i < im.data.length; i++) im.data[i] = (i & 3) === 3 ? 255 : Math.random() * 256;
      g.putImageData(im, 0, 0);
      const blob = await oc.convertToBlob({ type: 'image/png' });
      const inp = document.getElementById('image-input');
      inp.click = function () { const dt = new DataTransfer(); dt.items.add(new File([blob], 'n.png', { type: 'image/png' })); this.files = dt.files; this.onchange(); };
      document.getElementById('from-image').click();
      return 1;
    })()`);
    await wait(4000);
    await js(`document.querySelector('#tabs [data-tab=info]').click()`);
    await wait(300);
    await js(`[...document.querySelectorAll('#tab-info button')].find((x) => x.textContent === '从预览截一张').click()`);
    for (let i = 0; i < 50 && !/preview="/.test(await source()); i++) await wait(100);
    check(/<meta [^>]*preview="preview\.(png|webp)"/.test(await source()), '信息: a preview taken over a noisy picture fits the App\'s 512 KB: ' + ((/preview="[^"]*"/.exec(await source()) || ['none'])[0]));

    // M4: an example started from; help; the check before a save (a warning asks, an error stops)
    await js(`(() => { const p = document.getElementById('new-panel'); if (!p.open) p.showModal(); document.querySelectorAll('#examples button')[0].click(); })()`);
    await wait(1500);
    check(/name="晚霞"/.test(await source()) && !(await js(`document.getElementById('new-panel').open`)), '从示例开始: 晚霞 opened');
    await js(`document.getElementById('help-button').click()`);
    await wait(200);
    const help = await js(`document.getElementById('help-panel').open ? [...document.querySelectorAll('#help-panel a')].map((a) => a.href).join(' ') : ''`);
    check(/docs\/theme-maker\.md/.test(help) && /docs\/theme-format\.md/.test(help), 'help: open with its links: ' + help);
    await js(`document.getElementById('help-panel').close()`);
    await js(`(() => { window.__saved = 0; const u = URL.createObjectURL; URL.createObjectURL = (b) => { window.__saved++; return u(b); }; })()`);
    const edit = (f) => js(`(() => { const s = document.getElementById('source'); s.value = (${f})(s.value); s.dispatchEvent(new Event('input')); })()`);
    await edit(`(t) => t.replace('<style select="key"', '<style select="key" sticker="none"')`);
    await wait(800);
    const ask = async (button) => {
      await js(`document.querySelector('#file-menu [data-act=save]').click()`);
      await wait(300);
      const open = await js(`document.getElementById('export-panel').open`);
      if (button === 'esc') await js(`document.getElementById('export-panel').close()`);
      else await js(`document.querySelector('#export-panel button[value=${button}]').click()`);
      await wait(300);
      return open;
    };
    check(await ask('cancel') && (await js(`window.__saved`)) === 0, '保存 with a warning: asked; 回去改 saves nothing');
    check(await ask('esc') && (await js(`window.__saved`)) === 0, '保存 with a warning: Esc saves nothing');
    check(await ask('save') && (await js(`window.__saved`)) === 1, '保存 with a warning: 仍然保存 saves');
    await edit(`(t) => t.replace('<style select="key"', '<style select="key:pressed" fill="image(nope.png, 0dp)"/><style select="key"')`);
    await wait(800);
    await js(`document.querySelector('#file-menu [data-act=save]').click()`);
    await wait(300);
    const stopped = await js(`[!document.getElementById('export-panel').open, window.__saved, document.getElementById('problems').textContent]`);
    check(stopped[0] && stopped[1] === 1 && /nope\.png/.test(stopped[2]), '保存 with an error: nothing saved, the error in 问题: ' + JSON.stringify(stopped.slice(0, 2)));
  } finally {
    b.close();
    server.close();
  }
  return failed ? 1 : 0;
}

process.exit(await main());
