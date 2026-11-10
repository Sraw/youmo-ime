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
    await wait(300);
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
  } finally {
    b.close();
    server.close();
  }
  return failed ? 1 : 0;
}

process.exit(await main());
