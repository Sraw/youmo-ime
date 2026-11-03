// Builds index.html, the one file the site serves, from src/: page.html with style.css, lib.js and
// ui.js put in, the CSP's script hashes worked out, and the generated blocks (youmo-format,
// youmo-presets, youmo-layout, youmo-icons: the app's tests write them, never by hand) kept from the
// index.html there is. Edit src/, then: node web/theme-maker/tools/assemble.mjs
// The page's test fails when index.html is not what this makes of src/.
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
export const GENERATED = ['youmo-format', 'youmo-presets', 'youmo-layout', 'youmo-icons'];

/** index.html made of [src]'s files, the generated blocks taken from [old] (the index.html as it is). */
export function assemble(src = join(root, 'src'), old = readFileSync(join(root, 'index.html'), 'utf8')) {
  const read = (f) => readFileSync(join(src, f), 'utf8').replace(/\n+$/, '');
  // put in as they are: a function, so a `$&` in the code is not a replacement pattern
  const put = (html, slot, text) => {
    if (!html.includes('{{' + slot + '}}')) throw new Error('page.html has no {{' + slot + '}}');
    return html.replace('{{' + slot + '}}', () => text);
  };
  let html = readFileSync(join(src, 'page.html'), 'utf8');
  for (const id of GENERATED) {
    const m = new RegExp('<script type="application/json" id="' + id + '">\\n?([\\s\\S]*?)\\n?</script>').exec(old);
    if (!m) throw new Error('index.html has no ' + id + ' block to keep');
    html = put(html, id, m[1]);
  }
  html = put(html, 'css', read('style.css'));
  html = put(html, 'lib', read('lib.js'));
  html = put(html, 'ui', read('ui.js'));
  // the CSP lets only these run: each script element's text, as the browser hashes it
  const hashes = [...html.matchAll(/<script id="youmo-(?:lib|ui)">([\s\S]*?)<\/script>/g)]
    .map((m) => "'sha256-" + createHash('sha256').update(m[1]).digest('base64') + "'");
  if (hashes.length !== 2) throw new Error('not two scripts: ' + hashes.length);
  return put(html, 'script-hashes', hashes.join(' '));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const out = join(root, 'index.html');
  const html = assemble();
  writeFileSync(out, html);
  console.log(out, Buffer.byteLength(html));
}
