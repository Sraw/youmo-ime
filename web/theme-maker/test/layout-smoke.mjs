// The layout maker in a real browser (061-custom-layout §12): 新建 from a board, the preview and its environment bar
// (横屏 derived, the number pads), the source read again on typing, a problem's click to its line, undo, 保存 (min-app
// written, the file named; Ctrl+S in the source), a text past the size limit, and the draft offered back (its
// environment too; one that does not open offered to be dropped); the editing (W2): a key selected and outlined, moved
// by the buttons and dragged (a finger only after 300 ms), its width by its right edge, a character key added, its
// actions (a shared name's read-only), keys merged into a group, the app's bottom row made the layout's own, rows,
// 整块, 信息, the page at a phone's 360 px; by the keyboard (Delete, the arrows, Ctrl+Z in a field the field's), a field
// kept focused over its commit, the selection following its key over an undo, a finger's tap and swipe. CI runs it with the runner's Chrome, as panels-smoke.mjs.
// Run: node web/theme-maker/test/layout-smoke.mjs [chromium]   (SHOT=<file.png>: the page's picture saved there)
// Exit 0 all kept, 1 not, 2 not run (no Chromium).
import { writeFileSync } from 'node:fs';
import { serve } from '../dev/serve.mjs';
import { browser, chromium } from './pixel-smoke.mjs';

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
    b = await browser(shell);
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
  const until = async (expr, what, ms = 30000) => {
    for (let i = 0; i < ms / 100; i++) {
      if (await js(expr)) return;
      await wait(100);
    }
    throw new Error(what + ' (not in ' + ms / 1000 + ' s)');
  };
  let failed = 0;
  const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) failed++; };
  const url = 'http://127.0.0.1:' + server.address().port + '/layout.html';
  const source = () => js(`document.getElementById('source').value`);
  const count = () => js(`document.getElementById('count').textContent`);
  const canvas = () => js(`[document.getElementById('preview').width, document.getElementById('preview').height]`);
  const choose = async (id, value) => js(`(() => { const s = document.getElementById(${JSON.stringify(id)}); s.value = ${JSON.stringify(value)}; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
  const type = async (f) => {
    await js(`(() => { const t = document.getElementById('source'); t.value = (${f})(t.value); t.dispatchEvent(new Event('input')); })()`);
    await wait(900);
  };
  const make = async (base, id) => {
    await js(`document.querySelector('#bases [data-base=${base}]').click()`);
    if (id) await js(`document.getElementById('new-id').value = ${JSON.stringify(id)}`);
    await js(`document.getElementById('new-make').click()`);
  };
  try {
    await b.send('Page.navigate', { url });
    await until(`!!document.getElementById('new-panel')?.open`, 'the page did not start (新建 not opened)');

    // 新建: an id the app has refused in the dialog, a good one made
    await make('k18', 'k18');
    const refused = await js(`[document.getElementById('new-panel').open, document.getElementById('new-problem').textContent]`);
    check(refused[0] && /App 自带的键盘或片段叫 k18/.test(refused[1]), '新建 refuses an id of the app\'s: ' + refused[1]);
    await make('k18', 'grouped-bottom');
    const fragment = await js(`[document.getElementById('new-panel').open, document.getElementById('new-problem').textContent]`);
    check(fragment[0] && /grouped-bottom/.test(fragment[1]), '新建 refuses a fragment\'s id: ' + fragment[1]);
    await make('k18', 'my-k18');
    await until(`!document.getElementById('new-panel').open`, '新建 did not close');
    const made = await source();
    check(/id="my-k18"/.test(made) && /name="我的18 键"/.test(made) && /based-on="k18"/.test(made), 'a new layout from 18 键 under its id, name and based-on');
    check((await js(`document.getElementById('name').textContent`)) === '我的18 键', 'its name in the top bar');
    check(/^0 个错误/.test(await count()) || (await count()) === '没有', 'no errors: ' + (await count()));
    const upright = await canvas();
    check(upright[0] === 1080 && upright[1] > 600, 'drawn the phone\'s width: ' + upright);

    // 横屏 is the device's turn, shown, not a toggle
    check(/411 dp 宽，按竖屏排/.test(await js(`document.getElementById('env-shape').textContent`)), 'upright: 按竖屏排');
    await choose('env-turned', 'turned');
    check(/按横屏排/.test(await js(`document.getElementById('env-shape').textContent`)) && (await canvas())[0] === 2340, 'turned: 按横屏排, drawn 2340 wide');
    await choose('env-device', 'tablet');
    await choose('env-turned', '');
    check(/800 dp 宽，按横屏排/.test(await js(`document.getElementById('env-shape').textContent`)), 'a tablet upright: 800 dp, 按横屏排 (as the app)');
    await choose('env-device', 'oneplus-6t');

    // the source read again on typing: an error listed with its line, a click there selects it; undo takes it back
    await type(`(t) => t.replace('<row>', '<row>\\n    <nope/>')`);
    const listed = await js(`[...document.querySelectorAll('#problems li.error')].map((li) => li.textContent)`);
    check(listed.length > 0 && listed.some((t) => /第 \d+ 行/.test(t)), 'an error typed in is listed with its line: ' + listed[0]);
    await js(`[...document.querySelectorAll('#problems li.error')].find((li) => /第 \\d+ 行/.test(li.textContent)).querySelector('button').click()`);
    const selected = await js(`(() => { const t = document.getElementById('source'); return t.value.slice(t.selectionStart, t.selectionEnd); })()`);
    check(/nope/.test(selected), 'its click selects the line: ' + selected.trim());
    await js(`document.getElementById('undo').click()`);
    check((await source()) === made && !(await js(`document.querySelector('#problems li.error')`)), 'undo: the layout as it was, no error');
    await js(`document.getElementById('redo').click()`);
    check(/<nope\/>/.test(await source()), 'redo');
    await js(`document.getElementById('undo').click()`);

    // a number board: its pads, not the fields
    await js(`document.querySelector('#file-menu [data-act=new]').click()`);
    await make('number', 'my-number');
    await wait(300);
    const pads = await js(`[document.getElementById('env-pad-label').hidden, document.getElementById('env-field-label').hidden, document.getElementById('env-pad').options.length]`);
    check(!pads[0] && pads[1] && pads[2] === 7, 'a number board: the 7 pads offered, not the fields');
    const full = await js(`document.getElementById('preview').toDataURL()`);
    await choose('env-pad', 'phone');
    check((await js(`document.getElementById('preview').toDataURL()`)) !== full, 'the phone pad drawn apart from the full one');

    // 保存: min-app written, the file named by the layout's name
    await js(`window.__saved = []; HTMLAnchorElement.prototype.click = function () { window.__saved.push(this.download); }`);
    await js(`document.querySelector('#file-menu [data-act=save]').click()`);
    await wait(500);
    const saved = await js(`window.__saved`);
    check(saved.length === 1 && saved[0] === '我的数字键盘.layout', '保存: ' + JSON.stringify(saved));
    check(/<meta min-app="0\.6\.1"\/>/.test(await source()), '保存 wrote min-app');
    // Ctrl+S in the source: what is typed now saved, not waiting for the pause to read it
    await js(`(() => { const t = document.getElementById('source'); t.value = t.value.replace('name="我的数字键盘"', 'name="键盘 2"'); t.dispatchEvent(new Event('input')); t.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true, bubbles: true })); })()`);
    await wait(500);
    const typedSave = await js(`window.__saved`);
    check(typedSave.length === 2 && typedSave[1] === '键盘 2.layout', 'Ctrl+S in the source saves what is typed: ' + JSON.stringify(typedSave));
    // an error: nothing saved, the error listed by 保存 itself (the list emptied first, nothing typed since)
    await type(`(t) => t.replace('id="my-number"', 'id="number"')`);
    await js(`document.getElementById('problems').textContent = ''`);
    await js(`document.querySelector('#file-menu [data-act=save]').click()`);
    await wait(300);
    check((await js(`window.__saved.length`)) === 2 && /重名/.test(await js(`document.getElementById('problems').textContent`)) && /App 导入不了/.test(await js(`document.getElementById('toast').textContent`)),
      '保存 with an error: nothing saved, the error listed by 保存');

    // past the size limit: that one error, nothing drawn, not kept as the draft
    await type(`(t) => t.replace('id="number"', 'id="my-number"')`);
    await wait(800);
    const keptBefore = await js(`window.YoumoMaker.draft(window.YoumoMaker.idbStore(indexedDB, 'youmo-layout-maker')).then((d) => d && d.doc.xml)`);
    await type(`(t) => t.replace('</keyboard>', '<!--' + 'x'.repeat(70000) + '--></keyboard>')`);
    await wait(800);
    const big = await js(`[...document.querySelectorAll('#problems li')].map((li) => li.textContent)`);
    const blank = await js(`(() => { const c = document.getElementById('preview'); const e = document.createElement('canvas'); e.width = c.width; e.height = c.height; return c.toDataURL() === e.toDataURL(); })()`);
    check(big.length === 1 && /超过 64 KB/.test(big[0]) && blank, 'past 64 KB: one error, nothing drawn: ' + JSON.stringify(big).slice(0, 120));
    const keptAfter = await js(`window.YoumoMaker.draft(window.YoumoMaker.idbStore(indexedDB, 'youmo-layout-maker')).then((d) => d && d.doc.xml)`);
    check(keptAfter === keptBefore && keptAfter.length < 65536, 'past 64 KB: the draft not replaced');
    await js(`document.getElementById('undo').click()`);

    // the draft: offered back on the next visit, the environment it was drawn in too (the phone pad chosen above)
    await wait(800);
    const before = await source();
    await b.send('Page.navigate', { url });
    await until(`!!document.getElementById('restore-panel')?.open`, 'no draft offered on the next visit', 15000);
    await js(`document.querySelector('#restore-panel button[value=restore]').click()`);
    await wait(500);
    check((await source()) === before, 'the draft restored as it was');
    check((await js(`document.getElementById('env-pad').value`)) === 'phone', 'its environment restored: the phone pad');

    // a draft that does not open: offered to be dropped, and dropped, the page starting anew
    await js(`window.YoumoMaker.keepDraft(window.YoumoMaker.idbStore(indexedDB, 'youmo-layout-maker'), window.YoumoMaker.doc('<x/>'), {})`);
    await b.send('Page.enable');
    await b.send('Page.addScriptToEvaluateOnNewDocument', { source: 'window.confirm = (m) => { window.__asked = m; return true; };' });
    await b.send('Page.navigate', { url });
    await until(`!!document.getElementById('restore-panel')?.open`, 'the broken draft not offered', 15000);
    await js(`document.querySelector('#restore-panel button[value=restore]').click()`);
    await until(`!!document.getElementById('new-panel')?.open`, 'no 新建 after a draft that does not open', 15000);
    check(/^草稿打不开/.test(await js(`window.__asked || ''`)), 'a draft that does not open: asked to drop it: ' + (await js(`window.__asked || ''`)));
    check((await js(`window.YoumoMaker.draft(window.YoumoMaker.idbStore(indexedDB, 'youmo-layout-maker'))`)) === null, 'and dropped');

    // ---- the editing (W2, §12.3, §12.5) ----
    await js(`document.querySelector('#file-menu [data-act=new]').click()`);
    await make('qwerty', 'my-qwerty');
    await wait(300);
    const fresh = await source();
    // a key's middle in the page's pixels, by the scene the page draws (the bounds do not hang on the fonts)
    const at = (id, dx = 0.5) => js(`(() => {
      const E = window['youmo-engine'].YoumoEngine, LM = window.YoumoLayoutMaker;
      const t = document.getElementById('source').value;
      const r = LM.check(E, t, LM.ENV, E.readTheme(E.themeSource('material'), null, null).theme, E.fixedMeasurer(), E.layoutFormat().version);
      const k = r.scene.json().keys.find((x) => x.id === ${JSON.stringify(id)});
      const c = document.getElementById('preview'), rc = c.getBoundingClientRect(), s = rc.width / c.width;
      return { x: rc.left + (k.bounds[0] + (k.bounds[2] - k.bounds[0]) * ${dx}) * s, y: rc.top + (k.bounds[1] + k.bounds[3]) / 2 * s, cx: k.bounds[0] + 4, cy: (k.bounds[1] + k.bounds[3]) / 2 };
    })()`);
    const mouse = async (type, p, modifiers = 0) => b.send('Input.dispatchMouseEvent', { type, x: p.x, y: p.y, button: 'left', buttons: type === 'mouseReleased' ? 0 : 1, clickCount: 1, modifiers });
    const click = async (id, modifiers = 0) => { const p = await at(id); await mouse('mousePressed', p, modifiers); await mouse('mouseReleased', p, modifiers); await wait(150); };
    const drag = async (from, to) => {
      await mouse('mousePressed', from);
      for (let i = 1; i <= 5; i++) await mouse('mouseMoved', { x: from.x + (to.x - from.x) * i / 5, y: from.y + (to.y - from.y) * i / 5 });
      await mouse('mouseReleased', to);
      await wait(400);
    };
    const rowIds = (n) => js(`window.YoumoLayoutEdit.parse(document.getElementById('source').value).rows[${n}].cells.map((c) => c.a.id).join(' ')`);
    const keyTab = () => js(`document.getElementById('tab-key').textContent`);
    const press = (text) => js(`[...document.querySelectorAll('#edit button')].find((x) => x.textContent === ${JSON.stringify(text)} || x.getAttribute('aria-label') === ${JSON.stringify(text)}).click()`);

    await click('q');
    check(/键 q/.test(await keyTab()), 'a click on Q selects it: its panel');
    const q = await at('q');
    const px = await js(`Array.from(document.getElementById('preview').getContext('2d').getImageData(${q.cx}, ${q.cy}, 1, 1).data).slice(0, 3).join(',')`);
    check(px === '59,111,216', 'the selected key outlined in the accent: ' + px);
    await press('右移');
    await wait(300);
    check((await rowIds(0)).startsWith('w q e'), '→ moves it right: ' + await rowIds(0));
    await drag(await at('q'), await at('a', 0.1));
    check((await rowIds(1)).startsWith('q a s') && (await rowIds(0)).startsWith('w e'), 'Q dragged before A: ' + await rowIds(1));
    await press('上一行');
    await wait(300);
    check((await rowIds(0)).endsWith('p q'), '上一行: at the end of the row above: ' + await rowIds(0));
    await js(`document.getElementById('undo').click(); document.getElementById('undo').click(); document.getElementById('undo').click()`);
    check((await source()) === fresh, 'undone three times: as it was');

    // its width by its right edge, snapped to half a unit
    await click('w');
    const w0 = await at('w', 0.98);
    const unit = (await at('e')).x - (await at('w')).x;
    await drag(w0, { x: w0.x + unit * 0.55, y: w0.y });
    check(/<key id="w"[^>]* width="1\.5"/.test(await source()), 'W\'s right edge dragged half a key: width="1.5": ' + /<key id="w"[^>]*>/.exec(await source())[0]);
    await js(`document.getElementById('undo').click()`);

    // a shared name: its actions but the tap read-only, said why
    await click('e');
    const shared = await js(`[document.getElementById('tab-key').textContent, document.querySelector('#tab-key select[aria-label=长按]').disabled, document.querySelector('#tab-key select[aria-label=点按]').disabled]`);
    check(/跟内置的26 键 E，在 App 的按键字符和快捷键里改/.test(shared[0]) && shared[1] && !shared[2], 'E (full/e): its long press read-only, said why; its tap not');

    // 加键: a character key left of E, its own name; its long press its layout's
    await js(`(() => { const i = document.querySelector('#tab-key input[aria-label=字符键打出的文字]'); i.value = '你好'; })()`);
    await js(`[...document.querySelectorAll('#tab-key button')].filter((x) => x.textContent === '加')[1].click()`);
    await wait(300);
    check(/<key id="c1" label="你好" send="commit:你好" shortcut="my-qwerty\/c1"\/>/.test(await source()) && (await rowIds(0)).startsWith('q w c1 e'), '字符键 加 left of E, its own name: ' + await rowIds(0));
    await js(`(() => { const s = document.querySelector('#tab-key select[aria-label=长按]'); s.value = 'commit'; s.dispatchEvent(new Event('change')); })()`);
    await wait(200);
    await js(`(() => { const i = document.querySelector('#tab-key input[aria-label=长按的参数]'); i.value = '您好'; i.dispatchEvent(new Event('change')); })()`);
    await wait(300);
    check(/<key id="c1"[^>]* long-press="commit:您好"/.test(await source()), 'its long press set: commit:您好');
    await js(`(() => { const i = document.querySelector('#tab-key input[aria-label=长按列表]'); i.value = '你 好'; i.dispatchEvent(new Event('change')); })()`);
    await wait(300);
    check(/<popup from="list"><item text="你"\/><item text="好"\/><\/popup>/.test(await source()), 'its long-press list written');
    await press('删除');
    await wait(300);
    check(!/id="c1"/.test(await source()), '删除 takes it out');

    // 合并: Q and W one group key, the board grouped, said what it means
    await js(`document.getElementById('source').value = ${JSON.stringify(fresh)}`);
    await js(`document.getElementById('source').dispatchEvent(new Event('change'))`);
    await wait(500);
    await click('q');
    await click('w', 8);
    await press('合并成一个分组键');
    await wait(500);
    const merged = await source();
    check(/family="grouped"/.test(merged) && /<key id="qw" label="\{group:qw\}" letters="qw" shortcut="my-qwerty\/qw" width="2"\/>/.test(merged) && /变成分组键盘/.test(await js(`document.getElementById('toast').textContent`)),
      '合并 Q W: one group key, the board grouped, the note shown');
    check(/^0 个错误/.test(await count()) || (await count()) === '没有', 'merged: no errors: ' + (await count()));

    // the keyboard: several keys selected and taken out by 删除这些, one by Delete on the preview, none by Backspace in a
    // field; the arrows move the selection; Ctrl+Z in a field is the field's
    const keyPress = async (k, code, vk, modifiers = 0) => {
      for (const type of ['keyDown', 'keyUp']) await b.send('Input.dispatchKeyEvent', { type, key: k, code, windowsVirtualKeyCode: vk, modifiers });
      await wait(250);
    };
    const reset = async () => {
      await js(`document.getElementById('source').value = ${JSON.stringify(fresh)}`);
      await js(`document.getElementById('source').dispatchEvent(new Event('change'))`);
      await wait(500);
    };
    await reset();
    await click('q');
    await click('w', 8);
    await press('删除这些');
    await wait(300);
    check((await rowIds(0)).startsWith('e r t'), '删除这些: Q and W taken out: ' + await rowIds(0));
    // (a letter board without them is refused, nothing drawn: back first)
    await js(`document.getElementById('undo').click()`);
    await wait(300);
    await click('e');
    check((await js(`document.activeElement.id`)) === 'preview', 'the preview takes the focus on a click');
    await keyPress('Delete', 'Delete', 46);
    check((await rowIds(0)).startsWith('q w r t'), 'Delete on the preview: E taken out: ' + await rowIds(0));
    await js(`document.getElementById('undo').click()`);
    await wait(300);
    await click('r');
    await js(`document.querySelector('#tab-key input[aria-label=字母]').focus()`);
    await keyPress('Backspace', 'Backspace', 8);
    check((await rowIds(0)).startsWith('q w e r t'), 'Backspace in a field: no key taken out');
    await reset();
    await click('q');
    await keyPress('ArrowRight', 'ArrowRight', 39);
    check(/键 w/.test(await keyTab()), '→ on the preview: W selected');
    await keyPress('ArrowDown', 'ArrowDown', 40);
    check(/键 [as]\b/.test(await keyTab()), '↓: a key of the row below: ' + /键 \S+/.exec(await keyTab()));
    // the selection follows its key over an undo (its place another key's now)
    await click('e');
    await press('右移');
    await wait(300);
    await js(`document.getElementById('undo').click()`);
    await wait(300);
    check((await rowIds(0)).startsWith('q w e') && /键 e/.test(await keyTab()), 'undo: E selected still, where it is again');
    // a field committed: the same field focused, not drawn anew (a touch keyboard stays open)
    await js(`(() => { const i = document.querySelector('#tab-key input[aria-label=读屏念什么]'); window.__f = i; i.focus(); i.value = '一'; i.dispatchEvent(new Event('input', { bubbles: true })); i.dispatchEvent(new Event('change', { bubbles: true })); })()`);
    await wait(300);
    check(/<key id="e"[^>]* a11y="一"/.test(await source()) && (await js(`document.activeElement === window.__f && window.__f.isConnected`)), 'a field committed: written, the field kept and focused');
    const unUndone = await source();
    await js(`document.querySelector('#tab-key input[aria-label=读屏念什么]').focus()`);
    await keyPress('z', 'KeyZ', 90, 2);
    check((await source()) === unUndone, 'Ctrl+Z in a field: the layout not undone');
    // a select has no undo of its own: Ctrl+Z there is the layout's
    await js(`(() => { const s = document.querySelector('#tab-key select[aria-label=种类]'); s.focus(); s.value = 'function'; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
    await wait(300);
    const kinded = /<key id="e"[^>]* kind="function"/.test(await source());
    await js(`document.querySelector('#tab-key select[aria-label=种类]').focus()`);
    await keyPress('z', 'KeyZ', 90, 2);
    check(kinded && (await source()) === unUndone, '种类 changed, Ctrl+Z on its select: undone');
    // a label with braces refused, said why
    await js(`(() => { const s = document.querySelector('#tab-key select[aria-label=显示的字是]'); s.value = 'text'; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
    await wait(300);
    const plain = await source();
    await js(`(() => { const i = document.querySelector('#tab-key input[aria-label=显示的字]'); i.value = 'e{'; i.dispatchEvent(new Event('change', { bubbles: true })); })()`);
    await wait(300);
    check((await source()) === plain && /不能有 \{ 或 \}/.test(await js(`document.getElementById('toast').textContent`)), 'a label with { refused, said why');
    await js(`(() => { const s = document.querySelector('#tab-key select[aria-label=显示的字是]'); s.value = 'none'; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
    await wait(300);
    check(await js(`document.querySelector('#tab-key input[aria-label=显示的字]').disabled`), '不显示字: the label field off');
    // a long press from elsewhere said, not an empty list over it
    await type(`(t) => t.replace('</keyboard>', '  <row>\\n    <key id="z9" label="z" send="z"><popup from="preset" preset="q" in-row="2"/></key>\\n  </row>\\n</keyboard>')`);
    await click('z9');
    check(/这个键的长按来自键 q/.test(await keyTab()), 'a preset long press: where it comes from said');
    await js(`(() => { const i = document.querySelector('#tab-key input[aria-label=长按列表]'); i.value = '甲'; i.dispatchEvent(new Event('change', { bubbles: true })); })()`);
    await wait(300);
    check(/<popup from="list" in-row="2"><item text="甲"\/><\/popup>/.test(await source()), 'a list written over it: its in-row kept');

    // an icon key: its icon chosen from the app's, shown
    await js(`document.querySelector('#file-menu [data-act=new]').click()`);
    await make('k18', 'my-k18');
    await wait(300);
    await click('backspace');
    const icon = await js(`(() => { const c = document.querySelector('#tab-key canvas.icon'); return [document.querySelector('#tab-key select[aria-label=图标]').value, Array.from(c.getContext('2d').getImageData(0, 0, c.width, c.height).data).some((v, i) => i % 4 === 3 && v > 0)]; })()`);
    check(icon[0] === 'backspace' && icon[1], '⌫: its icon chosen and drawn beside the list: ' + icon);
    // a function key the app's bottom row has too, in a row of its own: an id of its own, saved clean
    await js(`document.querySelector('#tabs [data-tab=row]').click()`);
    await press('加一行');
    await wait(300);
    await js(`document.querySelector('#tabs [data-tab=key]').click()`);
    await js(`document.querySelector('#tab-key button[title=comma]').click()`);
    await wait(500);
    check(/<key id="comma-2"/.test(await source()) && (/^0 个错误/.test(await count()) || (await count()) === '没有'), '，added on 18 keys: comma-2, no errors: ' + (await count()));
    await js(`document.getElementById('undo').click(); document.getElementById('undo').click()`);
    await wait(300);

    // the app's bottom row made the layout's own, asked first
    await click('space');
    check(/在 App 的底行里/.test(await keyTab()), 'space: in the app\'s bottom row');
    await press('改成这块键盘自己的底行…');
    await js(`(() => { const d = document.getElementById('own-panel'); d.returnValue = 'yes'; d.close('yes'); })()`);
    await wait(500);
    check(!/<include/.test(await source()) && /<key id="space"/.test(await source()) && /键 space/.test(await keyTab()), 'yes: its rows copied in, space selected');

    // 行: a row added and moved up; 整块 and 信息 written
    await js(`document.querySelector('#tabs [data-tab=row]').click()`);
    const rows = async () => js(`window.YoumoLayoutEdit.parse(document.getElementById('source').value).rows.length`);
    const n = await rows();
    await press('加一行');
    await wait(300);
    check((await rows()) === n + 1 && /<row>\s*<gap\/>\s*<\/row>\s*<\/keyboard>/.test(await source()), '加一行: a row of a gap at the end');
    await js(`[...document.querySelectorAll('#tab-row button[aria-label=上移]')].pop().click()`);
    await wait(300);
    check(/<row>\s*<gap\/>\s*<\/row>\s*<row>/.test(await source()), '↑: moved up a row');
    // its ☰ dragged below the next row
    const order = async () => js(`window.YoumoLayoutEdit.parse(document.getElementById('source').value).rows.map((r) => r.cells.length).join(' ')`);
    const was = await order();
    await js(`(() => {
      const items = [...document.querySelectorAll('#tab-row .parts li')];
      const from = items.findIndex((li) => /· 1 个/.test(li.textContent));
      const dt = new DataTransfer();
      items[from].querySelector('.handle').dispatchEvent(new DragEvent('dragstart', { bubbles: true, dataTransfer: dt }));
      items[from + 1].dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt }));
    })()`);
    await wait(300);
    const now = (await order()).split(' ');
    const then = was.split(' ');
    const i = then.indexOf('1');
    check(now[i] === then[i + 1] && now[i + 1] === '1', 'the gap row\'s ☰ dragged onto the next: moved below it: ' + was + ' -> ' + now.join(' '));
    await js(`document.querySelector('#tabs [data-tab=meta]').click()`);
    await js(`(() => { const i = document.querySelector('#tab-meta input[aria-label=作者]'); i.value = '某人'; i.dispatchEvent(new Event('change')); })()`);
    await wait(300);
    check(/<meta author="某人"\/>/.test(await source()), '信息: the author in <meta>');
    await js(`document.querySelector('#tabs [data-tab=board]').click()`);
    await js(`(() => { const s = document.querySelector('#tab-board select[aria-label=分体方式]'); s.value = 'none'; s.dispatchEvent(new Event('change')); })()`);
    await wait(300);
    check(/<keyboard[^>]* on-split="none"/.test(await source()), '整块: on-split="none"');

    // a phone's width (§12.5): nothing wider than the screen, the buttons doing what a drag does
    await b.send('Emulation.setDeviceMetricsOverride', { width: 360, height: 760, deviceScaleFactor: 2, mobile: true });
    await wait(500);
    await js(`document.querySelector('#tabs [data-tab=key]').click()`);
    await click('r1c2');
    const narrow = await js(`[document.documentElement.scrollWidth, document.getElementById('preview').getBoundingClientRect().bottom <= document.getElementById('edit').getBoundingClientRect().top + 1]`);
    check(narrow[0] <= 360 && narrow[1], '360 px wide: no sideways scroll, the preview above the editing: ' + narrow);
    await press('左移');
    await wait(300);
    check((await rowIds(0)).startsWith('r1c2 r1c1 r1c3'), '← on a phone: ' + await rowIds(0));
    // a finger: a quick move scrolls, a 300 ms press then a move drags
    const finger = async (from, to, hold) => {
      await b.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: from.x, y: from.y }] });
      await wait(hold);
      for (let i = 1; i <= 4; i++) await b.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: from.x + (to.x - from.x) * i / 4, y: from.y + (to.y - from.y) * i / 4 }] });
      await b.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      await wait(400);
    };
    const unmoved = await rowIds(0);
    await finger(await at('r1c7'), await at('r1c2', 0.1), 0);
    check((await rowIds(0)) === unmoved, 'a finger moved at once: nothing dragged');
    await finger(await at('r1c7'), await at('r1c2', 0.1), 450);
    check((await rowIds(0)).startsWith('r1c7 r1c2 r1c1'), 'held 300 ms, then moved: dragged: ' + await rowIds(0));
    // a tap selects on lifting; a swipe over the preview leaves the tab as it was
    const tap = async (p) => {
      await b.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: p.x, y: p.y }] });
      await wait(50);
      await b.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      await wait(300);
    };
    await tap(await at('r1c3'));
    check(/键 r1c3/.test(await keyTab()), 'a tap selects: r1c3');
    await js(`document.querySelector('#tabs [data-tab=row]').click()`);
    await finger(await at('r1c5'), { ...(await at('r1c5')), y: (await at('r1c5')).y + 120 }, 0);
    check((await js(`document.getElementById('tab-row').hidden`)) === false, 'a swipe over the preview: the 行 tab still shown');
    await b.send('Emulation.clearDeviceMetricsOverride');
    await wait(300);

    // the picture: a new 18 keys upright
    await js(`document.querySelector('#file-menu [data-act=new]').click()`);
    await make('k18', 'my-k18');
    await choose('env-pad', 'full');
    await wait(500);
    if (process.env.SHOT) {
      const shot = await b.send('Page.captureScreenshot', { format: 'png' });
      writeFileSync(process.env.SHOT, Buffer.from(shot.data, 'base64'));
      console.log('picture: ' + process.env.SHOT);
    }
  } finally {
    await b.close();
    server.close();
  }
  return failed ? 1 : 0;
}

process.exit(await main());
