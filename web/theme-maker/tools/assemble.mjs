// Builds index.html, the one file the site serves, from src/: page.html with style.css, lib.js and
// ui.js put in, the CSP's script hashes worked out, and the generated blocks (youmo-format,
// youmo-presets, youmo-layout, youmo-icons, youmo-engine-icons: the app's tests write them, never by
// hand) kept from the index.html there is, the page's fonts (fonts/, tools/subset-fonts.py) put in.
// The engine (060-render-p3 W1: lib/keyboard-scene's library, `./gradlew :lib:keyboard-scene:youmoEngine`)
// is not in git: index.html holds NO_ENGINE in its place, and the engine goes in only where the page is
// built (pages.yml; the tests, in memory), so a change to the engine's code never has to make the page
// again. Edit src/, then: node web/theme-maker/tools/assemble.mjs        (index.html, as git keeps it)
// The page with its engine: node web/theme-maker/tools/assemble.mjs --engine [out]   (out: build/index.html)
// The page's test fails when index.html is not what this makes of src/ and the fonts.
import { existsSync, mkdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
export const GENERATED = ['youmo-format', 'youmo-presets', 'youmo-layout', 'youmo-icons', 'youmo-engine-icons'];

/** The engine as the build writes it: what the page's youmo-engine script is. */
export const ENGINE = join(root, '..', '..', 'lib', 'keyboard-scene', 'build', 'youmo-engine', 'youmo-engine.js');

/** What index.html has for the engine's script, as git keeps it: the engine is put in when the page is built. */
export const NO_ENGINE = '/* the engine (060-render-p3 W1) is put in where the page is built: ./gradlew :lib:keyboard-scene:youmoEngine, then node web/theme-maker/tools/assemble.mjs --engine */';

/** Where `--engine` writes the page with its engine when it is given no file (build/: not in git). */
export const BUILT = join(root, 'build', 'index.html');

/** The page's fonts: [family, weight, file of fonts/]; tools/subset-fonts.py writes the files. */
export const FONTS = [['Youmo Latin', 400, 'latin-400.woff2'], ['Youmo Latin', 700, 'latin-700.woff2'], ['Youmo Han', 400, 'han-400.woff2'], ['Youmo Han', 700, 'han-700.woff2']];

/** The text of [html]'s script element of [id] (a json block or a script), null when it has none; the page's test reads blocks with it too. */
export function blockOf(html, id) {
  const m = new RegExp('<script(?: type="application/json")? id="' + id + '">\\n?([\\s\\S]*?)\\n?</script>').exec(html);
  return m ? m[1] : null;
}

/** The engine's text as the page has it: the build's file, refused where it would end or escape its script element. */
export function engineText(file = ENGINE) {
  if (!existsSync(file)) throw new Error('no ' + file + ': ./gradlew :lib:keyboard-scene:youmoEngine');
  const text = readFileSync(file, 'utf8').replace(/\n+$/, '');
  if (/<\/script|<!--|<script/i.test(text)) throw new Error(file + ' has text that ends or escapes a script element');
  return text;
}

/** The fonts' @font-face rules, each its file as a data: URL (the CSP's font-src). */
export function fontFaces(dir = join(root, 'fonts')) {
  return FONTS.map(([family, weight, file]) => {
    const data = readFileSync(join(dir, file)).toString('base64');
    return '@font-face { font-family: "' + family + '"; font-weight: ' + weight + '; font-display: block; src: url(data:font/woff2;base64,' + data + ') format("woff2"); }';
  }).join('\n');
}

/**
 * The page made of [src]'s files, the generated blocks taken from [old] (the index.html as it is), the
 * engine of [engine] (its file; null: NO_ENGINE, index.html as git keeps it), the fonts of [fonts] (their folder).
 */
export function assemble(src = join(root, 'src'), old = readFileSync(join(root, 'index.html'), 'utf8'), engine = null, fonts = join(root, 'fonts')) {
  const read = (f) => readFileSync(join(src, f), 'utf8').replace(/\n+$/, '');
  const text = { css: read('style.css'), lib: read('lib.js'), ui: read('ui.js'), engine: engine == null ? NO_ENGINE : engineText(engine), fonts: fontFaces(fonts) };
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
  const hashes = [...fill(text).matchAll(/<script id="youmo-(?:lib|ui|engine)">([\s\S]*?)<\/script>/g)]
    .map((m) => "'sha256-" + createHash('sha256').update(m[1]).digest('base64') + "'");
  if (hashes.length !== 3) throw new Error('not three scripts: ' + hashes.length);
  return fill({ ...text, 'script-hashes': hashes.join(' ') });
}

// run as a script (through a symlink or /tmp → /private/tmp too: both sides real paths)
const real = (p) => { try { return realpathSync(p); } catch (e) { return p; } };
if (process.argv[1] && real(process.argv[1]) === real(fileURLToPath(import.meta.url))) {
  // --engine [out]: the page with its engine (the site's), else index.html as git keeps it
  const at = process.argv.indexOf('--engine');
  const out = at < 0 ? join(root, 'index.html') : resolve(process.argv[at + 1] || BUILT);
  const html = at < 0 ? assemble() : assemble(undefined, undefined, ENGINE);
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, html);
  console.log(out, Buffer.byteLength(html));
}
