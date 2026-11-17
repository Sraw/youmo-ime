// A headless Chromium for the theme maker's and the layout maker's browser tests (panels-smoke.mjs, layout-smoke.mjs,
// in CI; pixel-smoke.mjs, local): found (chromium) and driven over the DevTools protocol (browser).
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join } from 'node:path';

/** The Chromium to run: the command line's first argument, else CHROMIUM, else Playwright's newest headless shell; null where none. */
export function chromium() {
  const given = process.argv[2] || process.env.CHROMIUM;
  if (given) return given;
  for (const cache of [join(homedir(), 'Library', 'Caches', 'ms-playwright'), join(homedir(), '.cache', 'ms-playwright')]) {
    if (!existsSync(cache)) continue;
    const shells = readdirSync(cache).filter((d) => d.startsWith('chromium_headless_shell-')).sort().reverse();
    for (const d of shells) {
      for (const sub of ['chrome-mac/headless_shell', 'chrome-mac-arm64/headless_shell', 'chrome-linux/headless_shell', 'chrome-headless-shell-mac-arm64/chrome-headless-shell', 'chrome-headless-shell-linux64/chrome-headless-shell']) {
        if (existsSync(join(cache, d, sub))) return join(cache, d, sub);
      }
    }
  }
  return null;
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * [shell] (a headless Chromium) started on a page of its own, driven over the DevTools protocol: `{send(method, params),
 * close()}` (close: a promise, done once Chrome has exited and its profile is gone); its profile a temporary folder
 * named after [name], the caller. One that never comes up throws with `notRun` set (the smoke tests' "not run", exit 2).
 */
export async function browser(shell, name) {
  const dir = mkdtempSync(join(tmpdir(), name + '-'));
  // CI only: GitHub's Ubuntu 24.04 runners keep unprivileged user namespaces from Chrome's sandbox (AppArmor), and
  // the page is this checkout's own; a local run keeps the sandbox
  const sandbox = process.env.CI ? ['--no-sandbox'] : [];
  const proc = spawn(shell, ['--headless', '--disable-gpu', ...sandbox, '--remote-debugging-port=0', '--user-data-dir=' + dir, '--window-size=1400,1000', 'about:blank'], { stdio: 'ignore' });
  const file = join(dir, 'DevToolsActivePort');
  let ws;
  try {
    for (let i = 0; i < 100 && !existsSync(file); i++) await wait(100);
    if (!existsSync(file)) throw new Error(shell + ' did not start (no DevToolsActivePort in 10 s)');
    const port = readFileSync(file, 'utf8').split('\n')[0];
    const pages = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json();
    ws = new WebSocket(pages.find((p) => p.type === 'page').webSocketDebuggerUrl);
    await new Promise((r) => ws.addEventListener('open', r));
  } catch (e) {
    // a browser that never came up: no orphan Chromium, no profile left behind
    proc.kill();
    rmSync(dir, { recursive: true, force: true });
    e.notRun = true;
    throw e;
  }
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
  // the profile taken away once Chrome is gone: while it exits it still writes there (ENOTEMPTY now and then)
  const exited = new Promise((r) => proc.once('exit', r));
  const close = async () => {
    ws.close();
    proc.kill();
    await Promise.race([exited, wait(5000)]);
    rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  };
  return { send, close };
}
