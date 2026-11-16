// The layout maker in a real browser (061-custom-layout §12): 新建 from a board, the preview and its environment bar
// (横屏 derived, the number pads), the source read again on typing, a problem's click to its line, undo, 保存 (min-app
// written, the file named; Ctrl+S in the source), a text past the size limit, and the draft offered back (its
// environment too; one that does not open offered to be dropped). CI runs it with the runner's Chrome, as panels-smoke.mjs.
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
    await js(`[...document.querySelectorAll('#problems li.error')].find((li) => /第 \\d+ 行/.test(li.textContent)).click()`);
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
