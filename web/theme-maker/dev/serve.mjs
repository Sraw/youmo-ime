// The engine's preview page (dev/engine.html, 060-render-p3 W1) served from this checkout, for a developer and
// for test/pixel-smoke.mjs: the engine as the build writes it (./gradlew :lib:keyboard-scene:youmoEngine), the
// page's fonts, the app's icons (index.html's youmo-engine-icons block) and lib/keyboard-scene's test data (the
// snapshots' themes and how each was asked for, facade/shots.json); /page.html the theme maker with its engine
// put in, as pages.yml publishes it. Not published (pages.yml leaves dev/ out).
// Run: node web/theme-maker/dev/serve.mjs [port], then open http://127.0.0.1:<port>/
import { createServer } from 'node:http';
import { existsSync, readFileSync, realpathSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assemble, blockOf, ENGINE } from '../tools/assemble.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const maker = join(here, '..');
const data = join(maker, '..', '..', 'lib', 'keyboard-scene', 'src', 'commonTest', 'data');

const TYPES = { html: 'text/html; charset=utf-8', js: 'text/javascript; charset=utf-8', json: 'application/json', xml: 'text/xml; charset=utf-8', woff2: 'font/woff2' };

/** What a path is: [type, body], or null for none. Names only of the folders below: no `..`, no other folder. */
export function answer(path) {
  const name = (prefix) => {
    let rest;
    try {
      rest = decodeURIComponent(path.slice(prefix.length));
    } catch (e) {
      return null; // a malformed escape (`%E0%A4%A`): no such file, not a server down
    }
    return /^[\w.-]+$/.test(rest) && !rest.startsWith('.') ? rest : null;
  };
  const file = (f) => (existsSync(f) ? [TYPES[f.split('.').pop()] || 'application/octet-stream', readFileSync(f)] : null);
  if (path === '/' || path === '/engine.html') return file(join(here, 'engine.html'));
  if (path === '/engine.js') return file(ENGINE);
  // the theme maker as the site has it: index.html with the engine put in
  if (path === '/page.html') return [TYPES.html, assemble(undefined, undefined, ENGINE)];
  if (path === '/icons.json') return [TYPES.json, blockOf(readFileSync(join(maker, 'index.html'), 'utf8'), 'youmo-engine-icons')];
  if (path === '/shots.json') return file(join(data, 'facade', 'shots.json'));
  if (path.startsWith('/fonts/')) { const n = name('/fonts/'); return n && n.endsWith('.woff2') ? file(join(maker, 'fonts', n)) : null; }
  if (path.startsWith('/data/')) { const n = name('/data/'); return n && n.endsWith('.xml') ? file(join(data, n)) : null; }
  return null;
}

/** A server of [answer]'s, on 127.0.0.1 only. */
export function serve(port = 0) {
  const server = createServer((req, res) => {
    let found;
    try {
      found = answer(new URL(req.url, 'http://x').pathname);
    } catch (e) {
      // the engine not built (/page.html), a file gone as it was read: said, the server kept
      res.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' }).end(String(e.message));
      return;
    }
    if (!found) { res.writeHead(404).end('no such file'); return; }
    res.writeHead(200, { 'content-type': found[0], 'cache-control': 'no-store' }).end(found[1]);
  });
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve(server)));
}

const real = (p) => { try { return realpathSync(p); } catch (e) { return p; } };
if (process.argv[1] && real(process.argv[1]) === real(fileURLToPath(import.meta.url))) {
  const server = await serve(Number(process.argv[2] || 8765));
  console.log('http://127.0.0.1:' + server.address().port + '/');
}
