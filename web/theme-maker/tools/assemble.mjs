// Builds index.html, the one file the site serves, from src/: page.html with style.css, lib.js and
// ui.js put in, the CSP's script hashes worked out, and the generated blocks (youmo-format,
// youmo-presets, youmo-layout, youmo-icons: the app's tests write them, never by hand) kept from the
// index.html there is. Edit src/, then: node web/theme-maker/tools/assemble.mjs
// The page's test fails when index.html is not what this makes of src/.
import { readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
export const GENERATED = ['youmo-format', 'youmo-presets', 'youmo-layout', 'youmo-icons'];

/** The text of [html]'s script element of [id] (a json block or a script), null when it has none; the page's test reads blocks with it too. */
export function blockOf(html, id) {
  const m = new RegExp('<script(?: type="application/json")? id="' + id + '">\\n?([\\s\\S]*?)\\n?</script>').exec(html);
  return m ? m[1] : null;
}

/** index.html made of [src]'s files, the generated blocks taken from [old] (the index.html as it is). */
export function assemble(src = join(root, 'src'), old = readFileSync(join(root, 'index.html'), 'utf8')) {
  const read = (f) => readFileSync(join(src, f), 'utf8').replace(/\n+$/, '');
  const text = { css: read('style.css'), lib: read('lib.js'), ui: read('ui.js') };
  for (const id of GENERATED) {
    const kept = blockOf(old, id);
    if (kept == null) throw new Error('index.html has no ' + id + ' block to keep');
    text[id] = kept;
  }
  // every slot of page.html filled in one pass, each exactly once: what is put in (a script that says
  // {{lib}}, a `$&`) is never read as a slot or a replacement pattern
  const page = readFileSync(join(src, 'page.html'), 'utf8');
  const seen = new Map();
  for (const m of page.matchAll(/\{\{([\w-]+)\}\}/g)) seen.set(m[1], (seen.get(m[1]) || 0) + 1);
  for (const slot of [...Object.keys(text), 'script-hashes']) {
    if (seen.get(slot) !== 1) throw new Error('page.html has ' + (seen.get(slot) || 'no') + ' {{' + slot + '}}');
  }
  const fill = (values) => page.replace(/\{\{([\w-]+)\}\}/g, (all, slot) => (slot in values ? values[slot] : all));
  // the CSP lets only these run: each script element's text, as the browser hashes it
  const hashes = [...fill(text).matchAll(/<script id="youmo-(?:lib|ui)">([\s\S]*?)<\/script>/g)]
    .map((m) => "'sha256-" + createHash('sha256').update(m[1]).digest('base64') + "'");
  if (hashes.length !== 2) throw new Error('not two scripts: ' + hashes.length);
  return fill({ ...text, 'script-hashes': hashes.join(' ') });
}

// run as a script (through a symlink or /tmp → /private/tmp too: both sides real paths)
const real = (p) => { try { return realpathSync(p); } catch (e) { return p; } };
if (process.argv[1] && real(process.argv[1]) === real(fileURLToPath(import.meta.url))) {
  const out = join(root, 'index.html');
  const html = assemble();
  writeFileSync(out, html);
  console.log(out, Buffer.byteLength(html));
}
