// The editing's tabs in a real browser (060-render-p3 M2): a field's edit commits on leaving it, and what the user
// does next is kept: the characters typed into the next field, the box clicked right after. A tab drawn anew under
// the user (every change did that) took the field just entered away and the click with it. CI runs it on every push
// and PR (unit_test.yml, with the runner's Chrome; R25 B13); it needs a Chromium (no jsdom here: no npm).
// Run: node web/theme-maker/test/panels-smoke.mjs [chromium]
// (default: Playwright's headless shell, as cdp.mjs finds it; CHROMIUM=<path> too). Exit 0 all kept, 1 not, 2 not run.
import { readFileSync } from 'node:fs';
import { serve } from '../dev/serve.mjs';
import { browser, chromium } from './cdp.mjs';

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const shell = chromium();
  if (!shell) {
    console.log('no headless Chromium (Playwright\'s headless shell, or CHROMIUM=<path>): not run');
    return 2;
  }
  let server = null;
  let b = null;
  try {
    server = await serve(0);
    b = await browser(shell, 'panels-smoke');
  } catch (e) {
    if (b) await b.close();
    if (server) server.close();
    if (!e.notRun) throw e;
    console.log(e.message + ': not run');
    return 2;
  }
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
    // the page's start is done once it opens 新建 itself (its fonts loaded, no draft found, the first document set):
    // a theme picked before that (the buttons are there from the first moment) had 新建 opened over it a moment
    // later on a loaded machine, and the click meant for 大小 landed on the panel
    // (?.: the navigation may not have put the page in yet)
    const started = () => js(`!!document.getElementById('new-panel')?.open`);
    for (let i = 0; i < 300 && !(await started()); i++) await wait(100);
    if (!(await started())) throw new Error('the page did not start (新建 not opened in 30 s)');
    await js(`document.querySelector('#builtins button').click()`);
    await js(`document.querySelector('#tabs [data-tab=text]').click()`);
    await wait(300);

    // 061-toolbar-icons: the icons by where they are drawn, a name in two places noted in each
    const groups = JSON.parse(await js(`JSON.stringify([...document.querySelectorAll('#edit .tab:not([hidden]) h4')].map((e) => e.textContent))`));
    check(['回车键', '键', '快捷键的命令', '栏', '状态面板和编辑面板'].every((g) => groups.includes(g)), 'the icons grouped: ' + groups.join(' '));
    const undo = JSON.parse(await js(`JSON.stringify([...document.querySelectorAll('#edit .tab:not([hidden]) label.row')].filter((r) => r.querySelector('.what').textContent === 'undo').map((r) => r.textContent))`));
    check(undo.length === 2 && undo.every((t) => t.includes('也在')), 'undo in the commands and the bar, each noting the other: ' + undo.join(' | '));
    const hide = await js(`[...document.querySelectorAll('#edit .tab:not([hidden]) label.row')].find((r) => r.querySelector('.what').textContent === 'hide-keyboard').textContent`);
    check(hide.includes('栏上 App 自带的图和键上的不同'), 'hide-keyboard notes its bar image differs: ' + hide);

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
    for (const id of ['bar', 'popup', 'window', 'size', 'motion', 'sounds', 'info']) {
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
    // 061-custom-layout §10 (W3): 环境 › 键盘 › 打开 .layout: the theme drawn on a layout of the user's, a key of it picked
    // by its own id, its values' sources found on it
    const k17 = readFileSync(new URL('../examples/layouts/k17.layout', import.meta.url), 'utf8');
    await js(`(() => {
      for (const id of ['env-bar', 'env-show', 'env-sheet']) { const s = document.getElementById(id); s.value = ''; s.dispatchEvent(new Event('change', { bubbles: true })); }
      const inp = document.getElementById('layout-input');
      inp.click = function () { const dt = new DataTransfer(); dt.items.add(new File([${JSON.stringify(k17)}], 'k17.layout')); this.files = dt.files; this.onchange(); };
      const s = document.getElementById('env-board'); s.value = '@open'; s.dispatchEvent(new Event('change', { bubbles: true }));
    })()`);
    await wait(1000);
    const board = await js(`[document.getElementById('env-board').value, document.getElementById('env-board').selectedOptions[0].textContent]`);
    check(board[0] === '@own' && /^17 键（k17\.layout）$/.test(board[1]), '环境 › 键盘 › 打开 .layout: 17 键 drawn: ' + board);
    await js(`(() => {
      const c = document.getElementById('preview'); const r = c.getBoundingClientRect();
      const at = { clientX: r.left + r.width * 0.7, clientY: r.top + r.height * 0.375, bubbles: true, pointerId: 1 };
      c.dispatchEvent(new PointerEvent('pointerdown', at)); c.dispatchEvent(new PointerEvent('pointerup', at));
    })()`);
    await wait(500);
    const pickedOwn = await js(`document.getElementById('picked').textContent`);
    check(/选中的键：hjk（17 键）/.test(pickedOwn), 'a key of it picked: ' + pickedOwn);
    await js(`document.querySelector('#tabs [data-tab=keys]').click()`);
    await wait(300);
    // 范围 › 选中的键: the changes written for the picked key by the layout's own id
    await js(`(() => {
      const r = [...document.querySelectorAll('#tab-keys select')].find((s) => [...s.options].some((o) => o.value === 'picked'));
      r.value = 'picked'; r.dispatchEvent(new Event('change', { bubbles: true }));
    })()`);
    await wait(300);
    const keysTab = await js(`[document.querySelector('#tab-keys .selector code') ? document.querySelector('#tab-keys .selector code').textContent : '', document.querySelectorAll('#tab-keys table.sources tr').length]`);
    check(String(keysTab[0]).includes('k17/hjk'), '按键: the key picked named by the layout\'s id: ' + keysTab);
    check(keysTab[1] > 0, '按键: the sources of hjk on it: ' + keysTab);
    await js(`(() => { const s = document.getElementById('env-board'); s.value = 'qwerty'; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
    await wait(500);
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
    // a phone's width with the top bar's link to the layout maker (W3): on two lines, nothing past the screen, each link one line
    await b.send('Emulation.setDeviceMetricsOverride', { width: 360, height: 760, deviceScaleFactor: 2, mobile: true });
    await wait(500);
    const top = await js(`[document.documentElement.scrollWidth, ...[...document.querySelectorAll('#top a.link')].map((a) => Math.round(a.getBoundingClientRect().height))]`);
    check(top[0] <= 360 && top.slice(1).every((h) => h < 24), '360 px: the top bar within the screen, its links one line each: ' + top);
    await b.send('Emulation.clearDeviceMetricsOverride');

    // a draft restored (review R26 F8152, F8447): its environment through the page's own selects (`form` is
    // env-form-kind's, `panel` env-sheet's), a board the form does not offer now (0.6.0's 手写) or a layout opened (not
    // kept: @own, @open) the default's, the rest as kept. A select id wrong threw inside the restore, and the page
    // offered to drop the draft instead
    await b.send('Page.enable');
    await b.send('Page.addScriptToEvaluateOnNewDocument', { source: 'window.confirm = (m) => { window.__asked = m; return false; };' });
    const url = 'http://127.0.0.1:' + server.address().port + '/page.html';
    const offered = (q) => js(`(async () => { for (let i = 0; i < 300 && !document.getElementById('restore-panel')?.open; i++) await new Promise((r) => setTimeout(r, 50)); return ${q}; })()`);
    for (const board of ['handwriting', '@own', '@open']) {
      // the page's own pending draft written first, so it does not replace the one put in below on leaving; the theme
      // without the error put in above (a theme with an error draws nothing)
      await wait(800);
      await js(`window.YoumoMaker.keepDraft(window.YoumoMaker.idbStore(indexedDB), window.YoumoMaker.doc(document.getElementById('source').value.replace('<style select="key:pressed" fill="image(nope.png, 0dp)"/>', '')),
        Object.assign({}, window.YoumoMaker.ENV, { board: ${JSON.stringify(board)}, form: 'split', panel: 'voice', bar: 'typing' }))`);
      await b.send('Page.navigate', { url });
      check(await offered(`document.getElementById('restore-panel').open`), board + ': the draft offered on the next visit');
      await js(`document.querySelector('#restore-panel button[value=restore]').click()`);
      await wait(1500);
      const got = await js(`[window.__asked || '', ...['env-board', 'env-form-kind', 'env-sheet', 'env-bar'].map((id) => document.getElementById(id).value)]`);
      check(got[0] === '' && got[1] === 'qwerty' && got[2] === 'split' && got[3] === 'voice' && got[4] === 'typing',
        board + ': the draft restored, 26 键 drawn, the rest of its environment kept: ' + JSON.stringify(got));
      const blank = await js(`(() => { const c = document.getElementById('preview'); const e = document.createElement('canvas'); e.width = c.width; e.height = c.height; return c.width === 0 || c.toDataURL() === e.toDataURL(); })()`);
      check(!blank, board + ': the preview drawn: ' + await js(`document.getElementById('problems').textContent`));
    }
  } finally {
    await b.close();
    server.close();
  }
  return failed ? 1 : 0;
}

process.exit(await main());
