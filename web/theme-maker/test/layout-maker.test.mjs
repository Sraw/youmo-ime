// The layout maker (061-custom-layout §12): its page assembled, its library (src/layout/layout-maker.js) against the
// engine as the build writes it: a new layout from each of the app's boards checks clean and draws, a file opened and
// saved as the app imports it, every finding the checker may give said in words, the preview's environment as the app's;
// its editing (src/layout/layout-edit.js): moves, keys added and taken out, widths, rows=, long-press lists, merges, rows
// and the app's bottom row made the layout's own, each read back by the engine as meant.
// Run: node --test web/theme-maker/test/layout-maker.test.mjs   (needs ./gradlew :lib:keyboard-scene:youmoEngine)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, statSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { createHash } from 'node:crypto';
import vm from 'node:vm';
import { assemble, blockOf, ENGINE, LAYOUT_SRC, NO_ENGINE } from '../tools/assemble.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const src = join(here, '..', 'src');
for (const f of ['form.js', 'maker.js', join('layout', 'layout-maker.js'), join('layout', 'layout-edit.js')]) vm.runInThisContext(readFileSync(join(src, f), 'utf8'), { filename: f });
const M = globalThis.YoumoMaker;
const LM = globalThis.YoumoLayoutMaker;
globalThis.Path2D = class { constructor(d) { this.d = d; } };
const E = createRequire(import.meta.url)(ENGINE).YoumoEngine;
const FORMAT = E.layoutFormat();
const fixed = E.fixedMeasurer();
const material = E.readTheme(E.themeSource('material'), null, null).theme;
const enc = (t) => new TextEncoder().encode(t);
const fresh = (base = 'k18') => LM.fromBuiltin(E, base, 'my-' + base, '我的' + LM.BASE_LABEL[base]);

test('the page is made of src/layout/ and the shared scripts, runs only its own by their hashes, and links the theme maker (not back yet)', () => {
  const page = assemble(LAYOUT_SRC);
  assert.equal(blockOf(page, 'youmo-engine'), NO_ENGINE);
  assert.equal(blockOf(page, 'youmo-lib'), null, 'no theme library: the layout maker reads no theme files');
  for (const [id, file] of [['youmo-form', 'form.js'], ['youmo-maker', 'maker.js'], ['youmo-layout-maker', join('layout', 'layout-maker.js')], ['youmo-layout-edit', join('layout', 'layout-edit.js')], ['youmo-ui', join('layout', 'ui.js')]]) {
    assert.equal(blockOf(page, id), readFileSync(join(src, file), 'utf8').replace(/\n+$/, ''), id);
  }
  // the theme maker's style first, the page's own after it
  assert.ok(page.includes(readFileSync(join(src, 'style.css'), 'utf8').replace(/\n+$/, '') + '\n' + readFileSync(join(LAYOUT_SRC, 'style.css'), 'utf8').replace(/\n+$/, '')));
  const csp = /<meta http-equiv="Content-Security-Policy" content="([^"]*)">/.exec(page)[1];
  const run = [...page.matchAll(/<script(?![^>]*type="application\/json")[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
  assert.equal(run.length, 6);
  assert.match(csp, /default-src 'none'/);
  assert.ok(!/unsafe-inline'[^;]*;/.test(/script-src [^;]*;/.exec(csp)[0]));
  assert.deepEqual(/script-src ([^;]*);/.exec(csp)[1].split(' '), run.map((t) => "'sha256-" + createHash('sha256').update(t).digest('base64') + "'"));
  assert.match(page, /<a class="link" href="\.\.\/">主题制作<\/a>/);
  // the theme maker links it once it goes up with its help and examples (§14, W3): till then Pages leaves it out too
  assert.doesNotMatch(assemble(), /href="layout\//, 'the theme maker links it only from W3');
  // built: the build's engine, the rest the same
  const built = assemble(LAYOUT_SRC, ENGINE);
  const rest = (p) => p.replace(/<script id="youmo-engine">[\s\S]*?<\/script>/, '').replace(/script-src [^;]*;/, '');
  assert.equal(rest(built), rest(page));
});

test("the engine says what the page offers: the app's version, families, bases, pads, prefixes, limits, every finding", () => {
  assert.equal(FORMAT.version, '0.6.1');
  assert.deepEqual([...FORMAT.families], ['letters', 'grouped', 'nine', 'number', 'symbols']);
  assert.deepEqual([...FORMAT.bases], ['qwerty', 'k14', 'k18', 'nine', 'number', 'symbols']);
  assert.deepEqual([...FORMAT.pads], ['full', 'digits', 'signed', 'decimal', 'signed-decimal', 'phone', 'datetime']);
  assert.deepEqual([...FORMAT.prefixes], ['full', 'g14', 'g18', 't9', 'zy9', 'zyfull', 'num', 'fn']);
  assert.deepEqual({ ...FORMAT.limits }, { bytes: 65536, id: 32, name: 24 });
  // the ids the checker's id-built-in refuses: the app's boards and the fragments they include
  for (const id of [...E.layouts(), 'grouped-bottom', 'number-row']) assert.ok(FORMAT.builtIn.includes(id), id);
  // the page's words cover the checker's every finding, and no more (a finding added to LayoutCheck.KEYS fails here)
  assert.deepEqual(Object.keys(LM.WORDS).sort(), [...FORMAT.keys].sort());
  for (const b of FORMAT.bases) assert.ok(LM.BASE_LABEL[b], b);
  for (const f of FORMAT.families) assert.ok(LM.FAMILY_LABEL[f], f);
  for (const p of FORMAT.pads) assert.ok(LM.PAD_LABEL[p], p);
});

test('each finding is said in Chinese with what it names; one the table lacks as the engine has it', () => {
  for (const key of FORMAT.keys) {
    const said = LM.words({ line: 0, key, args: ['x-1', '9', 'y'] });
    assert.match(said, /[一-鿿]/, key + ': ' + said);
  }
  assert.equal(LM.words({ key: 'no-way', args: ['Number', 'Symbol'] }), '没有键能切到数字键盘、符号面板');
  assert.equal(LM.words({ key: 'bottom-left', args: ['back numbers'] }), '左下角通常是 返回、123（和 App 自带的一样）');
  assert.equal(LM.words({ key: 'label-long', args: ['hjk', '16'] }), '键「hjk」显示的字太长：最多 16 个字符');
  assert.equal(LM.words({ key: 'gap-look', args: [] }), '空位（gap）的 look=、class= 不起作用');
  assert.equal(LM.words({ key: 'later', args: ['a'] }), 'later：a');
});

test('a new layout from each of the app boards is the board as written, under its own id, name and based-on: clean, and drawn', () => {
  for (const base of FORMAT.bases) {
    const xml = fresh(base);
    const i = LM.info(xml);
    assert.deepEqual([i.id, i.name, i.basedOn, i.family], ['my-' + base, '我的' + LM.BASE_LABEL[base], base, E.layout(base).family], base);
    const r = LM.check(E, xml, LM.ENV, material, fixed, FORMAT.version);
    assert.deepEqual(r.problems.filter((p) => p.level === 'error'), [], base);
    assert.ok(r.scene, base + ': drawn');
    // drawn as the app draws its own board (the keys' names, the marks of its variant: 18 keys' of k18)
    const app = E.buildScene(base, material, LM.envJson(E, LM.ENV, i.family, E.layout(base)), fixed);
    assert.equal(r.scene.text(), app.scene.text(), base);
  }
  // includes kept: the app's bottom row followed when it changes
  assert.match(fresh('k18'), /<include src="grouped-bottom"\/>/);
  assert.throws(() => LM.fromBuiltin(E, 'nope', 'my-x', 'x'), /no built-in layout/);
});

test("a layout's marks are its base's: own 18 keys of k18 have the 18 keys' corners, as the app gives them (§5.3)", () => {
  const r = E.readOwnLayout(fresh('k18'));
  assert.ok(r.ok, JSON.stringify(r.problems));
  assert.deepEqual(E.defaultMarks(r.layout, {}), E.defaultMarks('k18', {}));
  assert.deepEqual(E.defaultMarks(E.readOwnLayout(fresh('k14')).layout, {}), E.defaultMarks('k14', {}));
  // read as a theme pack's file is (its own id, my-k18: not k18), its corners would be the 14 keys' variant's
  assert.notDeepEqual(E.defaultMarks(E.readLayout(fresh('k18'), null).layout, {}), E.defaultMarks('k18', {}));
});

test('the 新建 dialog refuses an id or a name the app would', () => {
  assert.equal(LM.idProblem('my-k17', FORMAT), null);
  for (const bad of ['My', 'a_b', '-a', 'a-', 'a--b', '', 'a'.repeat(33), 'k18', 'qwerty', 'g18', 'fn']) assert.ok(LM.idProblem(bad, FORMAT), bad);
  // a fragment's id too, as the checker: the dialog never makes a layout 保存 refuses
  assert.equal(LM.idProblem('grouped-bottom', FORMAT), 'App 自带的键盘或片段叫 grouped-bottom，换一个');
  assert.ok(E.checkLayout(LM.setKeyboard(fresh('k18'), 'id', 'grouped-bottom'), FORMAT.version, null).errors.some((f) => f.key === 'id-built-in'));
  for (const id of FORMAT.builtIn) assert.ok(LM.idProblem(id, FORMAT), id);
  assert.equal(LM.nameProblem('17 键', FORMAT), null);
  assert.ok(LM.nameProblem('  ', FORMAT));
  assert.ok(LM.nameProblem('x'.repeat(25), FORMAT));
  assert.deepEqual(LM.suggested('nine'), { id: 'my-nine', name: '我的九键' });
  for (const b of FORMAT.bases) assert.equal(LM.idProblem(LM.suggested(b).id, FORMAT), null, b);
});

test('the <keyboard> and <meta> are changed in place, a <meta> made first and taken out when left with nothing', () => {
  const xml = fresh('k14');
  const renamed = LM.setKeyboard(xml, 'name', 'A & "B"');
  assert.equal(LM.info(renamed).name, 'A & "B"');
  assert.equal(renamed.split('\n').length, xml.split('\n').length);
  const withMeta = LM.setMeta(xml, 'author', '某人');
  assert.deepEqual(LM.info(withMeta).meta, { author: '某人' });
  assert.match(withMeta, /<keyboard [^>]*>\n {2}<meta author="某人"\/>\n {2}<defaults/);
  assert.equal(LM.setMeta(LM.setMeta(withMeta, 'version', '2'), 'version', null), withMeta);
  assert.equal(LM.setMeta(withMeta, 'author', ''), xml, 'a <meta> left with nothing goes, its line too');
  assert.equal(LM.setMeta(xml, 'author', null), xml);
  assert.ok(E.checkLayout(LM.setMeta(withMeta, 'license', 'CC-BY-4.0'), FORMAT.version, null).ok);
});

test("an attribute is found by its name, never inside another's value; a value with ' or \" written so it reads back", () => {
  const xml = fresh('k18');
  // a name that reads like an attribute: the name changed, based-on left as it is
  const odd = LM.setKeyboard(xml, 'name', "a based-on='x'");
  assert.equal(LM.info(odd).name, "a based-on='x'");
  assert.equal(LM.info(odd).basedOn, 'k18');
  assert.match(odd, /name="a based-on=&apos;x&apos;"/);
  assert.equal(LM.info(LM.setKeyboard(odd, 'based-on', 'k14')).name, "a based-on='x'");
  assert.equal(LM.info(LM.setKeyboard(odd, 'based-on', 'k14')).basedOn, 'k14');
  const back = LM.setKeyboard(odd, 'name', '我的18 键');
  assert.equal(back, xml.replace('name="我的18 键"', 'name="我的18 键"'));
  // a value in single quotes holding a ' x=' and a ": changed as one, the rest of the tag as it was
  const single = xml.replace(/name="[^"]*"/, "name='b x=\"1\" id=\"k18\"'");
  assert.equal(LM.info(single).name, 'b x="1" id="k18"');
  assert.equal(LM.info(LM.setKeyboard(single, 'id', 'my-x')).name, 'b x="1" id="k18"');
  assert.equal(LM.info(LM.setKeyboard(single, 'id', 'my-x')).id, 'my-x');
  assert.equal(LM.info(LM.setKeyboard(single, 'name', null)).name, null);
  assert.equal(LM.info(LM.setKeyboard(single, 'name', null)).id, 'my-k18');
  // the same name inside an earlier attribute's value: only the real one changed (a pattern over the whole tag took
  // the first it found, inside name=)
  const hand = '<keyboard name="a based-on=\'x\'" id="my" family="letters" based-on="k18">\n</keyboard>';
  assert.equal(LM.setKeyboard(hand, 'based-on', 'k14'), '<keyboard name="a based-on=\'x\'" id="my" family="letters" based-on="k14">\n</keyboard>');
  assert.equal(LM.setKeyboard(hand, 'based-on', null), '<keyboard name="a based-on=\'x\'" id="my" family="letters">\n</keyboard>');
  const handMeta = '<keyboard id="my">\n  <meta description="min-app=\'0.1\'" min-app="0.6.0"/>\n</keyboard>';
  assert.equal(LM.setMeta(handMeta, 'min-app', '0.6.1'), '<keyboard id="my">\n  <meta description="min-app=\'0.1\'" min-app="0.6.1"/>\n</keyboard>');
  // <meta>: a description that holds min-app='0.1' is a description; min-app is written as its own
  const described = LM.setMeta(xml, 'description', "needs min-app='0.1' x=1");
  const minApp = LM.setMeta(described, 'min-app', '0.6.1');
  assert.deepEqual(LM.info(minApp).meta, { description: "needs min-app='0.1' x=1", 'min-app': '0.6.1' });
  assert.ok(E.checkLayout(minApp, FORMAT.version, null).ok);
  assert.deepEqual(LM.info(LM.setMeta(minApp, 'min-app', null)).meta, { description: "needs min-app='0.1' x=1" });
});

test('a file is opened as the app imports one: UTF-8 with or without BOM, within 64 KB, a layout; else said why', () => {
  const xml = fresh('nine');
  assert.equal(LM.openText(enc(xml), FORMAT.limits.bytes), xml);
  assert.equal(LM.openText(enc('﻿' + xml), FORMAT.limits.bytes), xml);
  assert.throws(() => LM.openText(new Uint8Array([0x50, 0x4b, 3, 4]), FORMAT.limits.bytes), /主题包/);
  assert.throws(() => LM.openText(enc(xml), FORMAT.limits.bytes, 70000), /70000 字节/);
  assert.throws(() => LM.openText(enc(xml + ' '.repeat(FORMAT.limits.bytes)), FORMAT.limits.bytes), /64 KB/);
  assert.throws(() => LM.openText(new Uint8Array([0xff, 0xfe, 0x3c]), FORMAT.limits.bytes), /UTF-8/);
  assert.throws(() => LM.openText(enc('<theme/>'), FORMAT.limits.bytes), /<keyboard>/);
  // Windows' line ends read as \n: a <meta> written into it later does not mix the two
  const crlf = LM.openText(enc(xml.replace(/\n/g, '\r\n')), FORMAT.limits.bytes);
  assert.equal(crlf, xml);
  assert.ok(!LM.setMeta(crlf, 'author', 'x').includes('\r'));
});

test('保存: errors keep it from being saved; else min-app written as the checker works it out, the file named by its name', () => {
  const xml = fresh('k18');
  const ok = LM.saveCheck(E, xml, FORMAT.version, material);
  assert.deepEqual(ok.errors, []);
  assert.equal(LM.info(ok.xml).meta['min-app'], E.checkLayout(xml, FORMAT.version, null).minApp);
  // what is saved checks as it was, the same min-app: saved again, the same file
  assert.equal(LM.saveCheck(E, ok.xml, FORMAT.version, material).xml, ok.xml);
  assert.ok(E.checkLayout(ok.xml, FORMAT.version, null).ok);
  // an id of the app's: an error, nothing written
  const bad = LM.setKeyboard(xml, 'id', 'k18');
  const no = LM.saveCheck(E, bad, FORMAT.version, material);
  assert.equal(no.errors.length > 0, true);
  assert.ok(no.errors.some((p) => /和 App 自带的键盘或片段重名/.test(p.message)), JSON.stringify(no.errors));
  assert.equal(no.xml, bad);
  // only warnings: saved (the page asks first)
  const narrow = xml.replace('<key id="r1c1" ', '<key id="r1c1" width="2" ');
  const w = LM.saveCheck(E, narrow, FORMAT.version, material);
  assert.deepEqual(w.errors, []);
  assert.ok(w.warnings.some((p) => p.message === '键「r1c1」很窄，可能不好按' && p.line > 0), JSON.stringify(w.warnings));
  // newer than the app: refused
  assert.ok(LM.saveCheck(E, LM.setMeta(xml, 'min-app', '9.0'), FORMAT.version, null).errors.some((p) => /9\.0/.test(p.message)));
  assert.equal(LM.fileName(xml), '我的18 键.layout');
  assert.equal(LM.fileName(LM.setKeyboard(xml, 'name', 'a/b:c')), 'a_b_c.layout');
  assert.equal(LM.fileName('<x/>'), 'layout.layout');
  // a <meta> description that reads like min-app: saved as one file the app reads, min-app its own attribute
  const described = LM.setMeta(xml, 'description', "min-app='0.1'");
  const d = LM.saveCheck(E, described, FORMAT.version, material);
  assert.deepEqual(d.errors, []);
  assert.deepEqual(LM.info(d.xml).meta, { description: "min-app='0.1'", 'min-app': ok.minApp ?? LM.info(ok.xml).meta['min-app'] });
  assert.ok(E.checkLayout(d.xml, FORMAT.version, null).ok);
  // the text with min-app is checked again and given only where it passes: else its errors, the text as it was
  let calls = 0;
  const twice = { checkLayout: () => (++calls === 1 ? { errors: [], warnings: [], minApp: '0.6.1' } : { errors: [{ line: 3, key: 'read', args: ['bad'] }], warnings: [] }) };
  const r = LM.saveCheck(twice, xml, FORMAT.version, null);
  assert.equal(calls, 2);
  assert.deepEqual(r.errors, [{ level: 'error', line: 3, message: '写进 min-app 以后：读不进来：bad' }]);
  assert.equal(r.xml, xml);
});

test('the problems: the checker\'s with their lines, the preview environment\'s apart; a text the engine cannot read draws nothing', () => {
  const xml = fresh('qwerty');
  const broken = xml.replace('<row>', '<row><nope/>');
  const r = LM.check(E, broken, LM.ENV, material, fixed, FORMAT.version);
  assert.equal(r.scene, null);
  assert.ok(r.problems.length && r.problems.every((p) => p.level === 'error'));
  assert.ok(r.problems.some((p) => p.line > 0), JSON.stringify(r.problems));
  // a key with no long press name: a warning with its line
  const unnamed = LM.check(E, xml.replace(/(<key id="q"[^>]*?) shortcut="[^"]*"/, '$1'), LM.ENV, material, fixed, FORMAT.version);
  const u = unnamed.problems.find((p) => /没有按键名/.test(p.message));
  assert.ok(u && u.line === LM.lineOfKey(xml, 'q'), JSON.stringify(unnamed.problems));
  // an engine that throws is a problem, not a page that stops
  const thrown = LM.check({ checkLayout: () => { throw new Error('boom'); } }, xml, LM.ENV, material, fixed, FORMAT.version);
  assert.deepEqual(thrown.problems, [{ level: 'error', line: 0, message: '引擎出错：boom' }]);
  assert.equal(LM.lineOfKey(xml, 'nope'), 0);
  // the checker's problems as it says them, each once; the preview environment's after them, said as its own
  const clean = LM.check(E, xml, LM.ENV, material, fixed, FORMAT.version);
  assert.deepEqual(clean.problems, LM.findings(clean.report));
  const thin = LM.check(E, xml.replace('<key id="q" ', '<key id="q" width="2" '), LM.ENV, material, fixed, FORMAT.version);
  const said = thin.problems.map((p) => p.line + ' ' + p.message);
  assert.equal(new Set(said).size, said.length, JSON.stringify(said));
  // what the reader says and the checker does not (an attribute that does nothing): a warning with its line
  const retired = xml.replace('<key id="q" ', '<key id="q" role="reading" ');
  const rr = LM.check(E, retired, LM.ENV, material, fixed, FORMAT.version);
  const w = rr.problems.find((p) => /^读取：/.test(p.message));
  assert.ok(w && w.level === 'warning' && w.line === LM.lineOfKey(retired, 'q'), JSON.stringify(rr.problems));
  // past the app's size: that one error, nothing read or drawn
  const big = xml.replace('</keyboard>', '<!--' + 'x'.repeat(FORMAT.limits.bytes) + '--></keyboard>');
  const b = LM.check(E, big, LM.ENV, material, fixed, FORMAT.version);
  assert.deepEqual(b.problems.map((p) => p.message), ['文件 ' + enc(big).length + ' 字节，超过 64 KB']);
  assert.equal(b.layout, null);
  assert.equal(b.scene, null);
  assert.equal(LM.lineOfKey(xml, 'q'), xml.split('\n').findIndex((l) => l.includes('<key id="q"')) + 1);
});

test('横屏 is never a toggle: the device and its turn say it, as the app (ScreenShape); the keyboard as tall as the app makes it', () => {
  const phone = LM.ENV;
  assert.deepEqual(LM.shape(E, phone), { widthDp: 411, landscape: false, width: 1080, height: Math.round(267 * 2.625) });
  assert.deepEqual(LM.shape(E, { ...phone, turned: true }), { widthDp: 891, landscape: true, width: 2340, height: Math.round(192 * 2.625) });
  const tablet = M.DEVICES.find((d) => d.id === 'tablet');
  // a tablet upright is 800 dp wide: laid out as 横屏, the large screen's height
  assert.deepEqual(LM.shape(E, { ...phone, ...tablet }), { widthDp: 800, landscape: true, width: 1600, height: 224 * 2 });
  assert.equal(E.screenLandscape(false, 599), false);
  assert.equal(E.screenLandscape(false, 600), true);
  assert.equal(E.screenLandscape(true, 300), true);
});

test("the environment as the engine's: the form, the input method and its scheme, the field or the number pad, the marks' places", () => {
  const nine = E.readOwnLayout(fresh('nine')).layout;
  const plain = LM.envJson(E, LM.ENV, 'nine', nine);
  assert.equal(plain.landscape, undefined);
  assert.deepEqual(plain.input, { ime: 'pinyin' });
  assert.deepEqual(plain.texts, { punctuation: M.ZH_PUNCTUATION });
  assert.deepEqual(plain.marks, E.defaultMarks(nine, M.ZH_PUNCTUATION));
  const e = { ...LM.ENV, turned: true, form: 'split', ime: 'shuangpin-ms', shift: 'lock', field: 'url', upAt: 'top', downAt: 'none', dark: true };
  const j = LM.envJson(E, e, 'letters', null);
  assert.equal(j.landscape, true);
  assert.equal(j.split, true);
  assert.equal(j.dark, true);
  assert.equal(j.shift, 'lock');
  assert.deepEqual(j.input, { ime: 'shuangpin', scheme: 'MS', schemeTypes: ['semicolon'], field: 'url' });
  assert.deepEqual(j.marks, { upAt: 'top', downAt: 'none' });
  assert.equal(LM.envJson(E, { ...LM.ENV, ime: 'english' }, 'letters', null).texts, undefined, 'English: as written');
  for (const [form, key, value] of [['compact', 'compact', true], ['floating', 'floating', 1], ['left', 'oneHanded', 'left'], ['right', 'oneHanded', 'right']]) {
    assert.equal(LM.envJson(E, { ...LM.ENV, form }, 'letters', null)[key], value, form);
  }
  // a number board: the field a number's, its pad the environment's, whatever field is chosen
  assert.deepEqual(LM.envJson(E, { ...LM.ENV, field: 'url', pad: 'phone' }, 'number', null).input, { ime: 'pinyin', field: 'number', pad: 'phone' });
  // every choice the bar offers draws: each input method, form, pad and place of the marks
  const qwerty = fresh('qwerty');
  const number = fresh('number');
  for (const ime of LM.IMES) assert.ok(LM.check(E, qwerty, { ...LM.ENV, ime: ime.id }, material, fixed, FORMAT.version).scene, ime.id);
  for (const [form] of LM.FORMS) assert.ok(LM.check(E, qwerty, { ...LM.ENV, form }, material, fixed, FORMAT.version).scene, form);
  for (const [at] of LM.AT) assert.ok(LM.check(E, qwerty, { ...LM.ENV, upAt: at, downAt: at }, material, fixed, FORMAT.version).scene, at);
  for (const pad of FORMAT.pads) assert.ok(LM.check(E, number, { ...LM.ENV, pad }, material, fixed, FORMAT.version).scene, pad);
  // the phone pad is the phone's keys, not the full pad's
  const phone = LM.check(E, number, { ...LM.ENV, pad: 'phone' }, material, fixed, FORMAT.version).scene.text();
  assert.notEqual(phone, LM.check(E, number, { ...LM.ENV, pad: 'full' }, material, fixed, FORMAT.version).scene.text());
  // the marks' places reach the drawing
  const top = LM.check(E, qwerty, { ...LM.ENV, upAt: 'top' }, material, fixed, FORMAT.version).scene.text();
  assert.notEqual(top, LM.check(E, qwerty, LM.ENV, material, fixed, FORMAT.version).scene.text());
});

test("a draft's environment is taken only as the bar offers it: another device, theme or pad the default's", () => {
  const tablet = M.DEVICES.find((d) => d.id === 'tablet');
  const kept = { device: 'tablet', w: 1, h: 1, density: 9, turned: true, form: 'split', ime: 'english', shift: 'lock', field: 'url', pad: 'phone', upAt: 'top', downAt: 'none', theme: 'nord', dark: true };
  assert.deepEqual(LM.restoreEnv(kept, [...E.themes()], [...FORMAT.pads]), { ...kept, w: tablet.w, h: tablet.h, density: tablet.density });
  const odd = { device: 'gone', turned: 'yes', form: 'x', ime: 'x', shift: 'x', field: 'x', pad: 'x', upAt: 'x', downAt: 'x', theme: 'gone', dark: 1 };
  assert.deepEqual(LM.restoreEnv(odd, [...E.themes()], [...FORMAT.pads]), LM.ENV);
  assert.deepEqual(LM.restoreEnv(null, [...E.themes()], [...FORMAT.pads]), LM.ENV);
  for (const [v] of LM.SHIFTS) assert.equal(LM.restoreEnv({ shift: v }, [], []).shift, v);
});

// as the engine's (format.test.mjs): just above what it is, so it grows only on purpose (the engine is most of it)
test('the layout maker built is no bigger than its budget', () => {
  const page = assemble(LAYOUT_SRC, ENGINE);
  assert.ok(Buffer.byteLength(page) <= 1_880_000, 'the page is ' + Buffer.byteLength(page) + ' bytes');
  assert.ok(statSync(ENGINE).size < Buffer.byteLength(page));
});

test("the draft is the theme maker's, in the layout maker's own database", async () => {
  const m = new Map();
  const store = { get: async (k) => (m.has(k) ? structuredClone(m.get(k)) : undefined), put: async (k, v) => { m.set(k, structuredClone(v)); }, remove: async (k) => { m.delete(k); }, keys: async () => [...m.keys()] };
  const xml = fresh('symbols');
  assert.ok(await M.keepDraft(store, M.doc(xml), { ...LM.ENV, turned: true }));
  const back = await M.draft(store);
  assert.equal(back.doc.xml, xml);
  assert.equal(back.env.turned, true);
  // the IndexedDB store opens the name it is given, its tabs' lock after it
  const opened = [];
  const idb = { open: (name) => { opened.push(name); return {}; } };
  const s = M.idbStore(idb, 'youmo-layout-maker');
  s.get('x').catch(() => {});
  assert.deepEqual(opened, ['youmo-layout-maker']);
  M.idbStore(idb).get('x').catch(() => {});
  assert.deepEqual(opened, ['youmo-layout-maker', 'youmo-theme-maker']);
});

// ---- the editing (W2, §12.3): each change made on the text, read back by the engine as meant ----

const LE = globalThis.YoumoLayoutEdit;
const ids = (xml) => LE.parse(xml).rows.map((r) => r.cells.map((c) => c.a.id || '(' + c.name + ')').join(' '));
// what the engine reads of [xml], written back as it writes a layout: its rows' cells by id
const engineIds = (xml) => {
  const r = E.readOwnLayout(xml);
  assert.ok(r.ok, 'the engine reads it: ' + JSON.stringify(r.problems));
  return ids(E.writeLayout(r.layout));
};
const errorsOf = (xml) => LM.saveCheck(E, xml, FORMAT.version, material).errors.map((p) => p.message);

test('a key moved: in its row, to another row, a step at a time; the rest of the text as it was', () => {
  const xml = fresh('qwerty').replace('<row split="halves">', '<row split="halves">\n    <!-- 第一行 -->');
  let r = LE.move(xml, { row: 0, index: 0 }, { row: 0, index: 3 });
  assert.deepEqual(r.at, { row: 0, index: 2 });
  assert.equal(engineIds(r.xml)[0], 'w e q r t y u i o p');
  assert.ok(r.xml.includes('<!-- 第一行 -->'), 'a comment kept');
  assert.equal(LE.move(xml, { row: 0, index: 0 }, { row: 0, index: 1 }).xml, xml, 'to where it is: nothing');
  r = LE.move(xml, { row: 0, index: 9 }, { row: 1, index: 0 });
  assert.deepEqual(engineIds(r.xml).slice(0, 2), ['q w e r t y u i o', 'p a s d f g h j k l semicolon star']);
  assert.deepEqual(errorsOf(LE.move(xml, { row: 1, index: 0 }, { row: 1, index: 9 }).xml), []);
  r = LE.step(xml, { row: 0, index: 0 }, 1, 0);
  assert.deepEqual([ids(r.xml)[0], r.at], ['w q e r t y u i o p', { row: 0, index: 1 }]);
  assert.equal(LE.step(xml, { row: 0, index: 0 }, -1, 0).xml, xml, 'the first key goes no further left');
  r = LE.step(xml, { row: 1, index: 0 }, 0, -1);
  assert.deepEqual([ids(r.xml)[0], r.at], ['q w e r t y u i o p a', { row: 0, index: 10 }]);
  // the last of a row moved out: the row goes, the target's index counted without it
  const one = LE.addRow(xml, 0, '<key id="x1" label="x" send="x"/>');
  r = LE.move(one, { row: 0, index: 0 }, { row: 2, index: 0 });
  assert.deepEqual([ids(r.xml).length, ids(r.xml)[1].split(' ')[0], r.at], [ids(xml).length, 'x1', { row: 1, index: 0 }]);
});

test('keys added and taken out: letters, characters of their own name, the base\'s function keys, a gap', () => {
  const xml = fresh('qwerty');
  assert.equal(LE.letterKey(xml, 'q'), '<key id="q-2" label="{case:q}" send="q" shortcut="my-qwerty/q-2"/>', 'a letter the app\'s name is taken for');
  const free = LE.remove(xml, { row: 0, index: 0 });
  assert.equal(LE.letterKey(free, 'q'), '<key id="q" label="{case:q}" send="q" shortcut="full/q"/>');
  assert.equal(LE.letterKey(xml, 'we'), '<key id="we" label="{group:we}" letters="we" shortcut="my-qwerty/we"/>');
  assert.equal(LE.letterKey(xml, 'l;'), '<key id="ling" label="{group:l;}" letters="l;" shortcut="my-qwerty/ling"/>');
  assert.equal(LE.lettersProblem('abcdef'), '分组字母是 1–5 个 a–z 或 ;');
  assert.equal(LE.lettersProblem('aa'), '分组里有重复的字母');
  assert.equal(LE.charKey(xml, '你好'), '<key id="c1" label="你好" send="commit:你好" shortcut="my-qwerty/c1"/>');
  assert.equal(LE.charKey(xml, '@'), '<key id="c1" label="@" send="@" shortcut="my-qwerty/c1"/>');
  assert.equal(LE.charKey(xml, 'a"<'), '<key id="c1" label="a&quot;&lt;" send="commit:a&quot;&lt;" shortcut="my-qwerty/c1"/>');
  let out = LE.insert(xml, { row: 0, index: 2 }, LE.charKey(xml, '你好'));
  assert.equal(engineIds(out)[0], 'q w c1 e r t y u i o p');
  out = LE.insert(out, { row: 0, index: 11 }, '<gap/>');
  assert.equal(engineIds(out)[0], 'q w c1 e r t y u i o p (gap)');
  // the base's function keys: each once, without where the base showed it
  const fns = LE.functionKeys([E.layoutSource('k18'), E.layoutSource('grouped-bottom')]);
  for (const id of ['apostrophe', 'backspace', 'symbols', 'numbers', 'comma', 'space', 'period', 'lang', 'enter']) assert.ok(fns.some((f) => f.id === id), id);
  assert.ok(!fns.some((f) => /when=/.test(f.text) || f.text.includes('letters=')));
  assert.equal(fns.find((f) => f.id === 'backspace').label, '⌫');
  // every function key added to a board with the app's bottom row: an id the fragment's keys have not either, and
  // the board saved clean (the include's comma is the board's: a second "comma" the checker refuses)
  const source = (s) => E.layoutSource(s);
  for (const base of ['k14', 'k18']) {
    const k = fresh(base);
    const texts = [E.layoutSource(base), ...LE.parse(E.layoutSource(base)).includes.map((x) => E.layoutSource(x.a.src))];
    for (const f of LE.functionKeys(texts)) {
      // a row of its own: a full row has no room
      const added = LE.addRow(k, 0, LE.withFreeId(k, f.text, source));
      assert.deepEqual(errorsOf(added), [], base + ' + ' + f.id);
    }
    const comma = LE.addRow(k, 0, LE.withFreeId(k, fns.find((f) => f.id === 'comma').text, source));
    assert.equal(engineIds(comma)[0].split(' ')[0], 'comma-2', base + ': the bottom row\'s comma taken');
  }
  assert.equal(LE.freeId(fresh('k18'), 'comma'), 'comma', 'without the fragments: the file\'s own ids only');
  assert.equal(LE.charKey(fresh('k18'), 'x', source), LE.charKey(fresh('k18'), 'x'), 'c1 no fragment has');
  // a lone ; on a letters board: semicolon, of the layout's own name (the app has no full/;), saved clean
  const semi = LE.letterKey(LE.remove(xml, { row: 1, index: 9 }), ';');
  assert.equal(semi, '<key id="semicolon" label=";" send=";" shortcut="my-qwerty/semicolon"/>');
  assert.equal(LE.letterKey(xml, ';'), '<key id="semicolon-2" label=";" send=";" shortcut="my-qwerty/semicolon-2"/>');
  assert.deepEqual(errorsOf(LE.addRow(xml, 0, LE.letterKey(xml, ';'))), []);
  // taken out: a row's last key takes the row with it
  assert.equal(ids(LE.remove(xml, { row: 0, index: 0 }))[0], 'w e r t y u i o p');
  let gone = LE.addRow(xml, 0, '<gap/>');
  assert.equal(ids(gone).length, ids(xml).length + 1);
  gone = LE.remove(gone, { row: 0, index: 0 });
  assert.equal(gone, xml);
});

test('a key\'s attributes: width (snapped as dragged), rows=, kind, label and actions as the panel says them', () => {
  const xml = fresh('qwerty');
  const w = LE.setAttrs(xml, { row: 0, index: 1 }, { width: '1.5', rows: '2', kind: 'function' });
  assert.match(w, /<key id="w" label="\{case:w\}" send="w" shortcut="full\/w" width="1.5" rows="2" kind="function">/);
  assert.ok(E.readOwnLayout(w).ok);
  assert.equal(LE.setAttrs(w, { row: 0, index: 1 }, { width: null, rows: '', kind: null }), xml, 'taken out again: as it was');
  assert.equal(LE.widthOf(xml, LE.parse(xml), LE.parse(xml).rows[0].cells[0]), '1', 'a width none says: 1');
  const n = fresh('number');
  const cell = LE.parse(n).rows.flatMap((r) => r.cells).find((c) => !c.a.width);
  assert.equal(cell ? LE.widthOf(n, LE.parse(n), cell) : '22', '22', 'the board\'s <defaults>');
  assert.deepEqual([LE.snap(1.26, 10), LE.snap(1.24, 10), LE.snap(0.1, 10), LE.snap(14.4, 100), LE.snap(400, 10)], [1.5, 1, 0.5, 14, 100]);
  for (const raw of [null, 'x', '{case:x}', '{punct:,}', '{ime-name}']) {
    const p = LE.labelParts(raw);
    assert.equal(LE.labelText(p.type, p.arg), raw, String(raw));
  }
  assert.equal(LE.labelParts('{ime-name}').type, 'other');
  // braces are a binding's: refused as a text, said why
  assert.match(LE.labelProblem('text', 'a{b'), /不能有 \{ 或 \}/);
  assert.match(LE.labelProblem('case', 'x}'), /不能有/);
  assert.equal(LE.labelProblem('other', '{ime-name}'), null);
  assert.equal(LE.labelProblem('text', '你好'), null);
  for (const raw of [null, 'q', 'commit:你好', 'sym:BackSpace', 'layout:Number', 'shortcut:stroke_h', 'keypad:3', 'lang']) {
    const p = LE.actionParts(raw);
    assert.equal(LE.actionText(p.type, p.arg), raw, String(raw));
  }
  assert.deepEqual(LE.actionParts('lang'), { type: 'other', arg: 'lang' });
  assert.equal(LE.actionText('commit', ''), null, 'a kind without its parameter: nothing written');
  // whose actions are the layout's (§9, Q11)
  const P = FORMAT.prefixes;
  assert.equal(LE.sharedName({ shortcut: 'g18/we' }, P), '这个键的长按、滑动跟内置的18 键 WE，在 App 的按键字符和快捷键里改');
  assert.match(LE.sharedName({ shortcut: 'backspace' }, P), /内置的功能键 backspace/);
  assert.match(LE.sharedName({}, P), /没有自己的名字/);
  assert.equal(LE.sharedName({ shortcut: 'my-k17/hjk' }, P), null);
});

test('a key\'s long-press list: written, changed, taken out, the key closing itself again', () => {
  const xml = fresh('qwerty');
  const at = { row: 0, index: 0 };
  const plain = LE.insert(xml, at, '<key id="k9" label="k" send="k"/>');
  let out = LE.setPopup(plain, at, ['甲', '"乙"']);
  assert.match(out, /<key id="k9" label="k" send="k">\n {6}<popup from="list"><item text="甲"\/><item text="&quot;乙&quot;"\/><\/popup>\n {4}<\/key>/);
  assert.deepEqual(LE.popupOf(out, at), { from: 'list', preset: null, items: [{ text: '甲', send: null }, { text: '"乙"', send: null }] });
  assert.ok(E.readOwnLayout(out).ok);
  out = LE.setPopup(out, at, ['丙']);
  assert.deepEqual(LE.popupOf(out, at).items.map((x) => x.text), ['丙']);
  assert.equal(LE.setPopup(out, at, []), plain);
  assert.equal(LE.popupOf(plain, at), null, 'no <popup>: its character\'s own');
  // the <popup>'s own attributes kept, an item still there keeping its send=; another kind's said and made a list
  const own = LE.addRow(xml, 0, '<key id="k9" label="k" send="k"><popup from="list" as-is="true" in-row="3" transform-punctuation="false" preview="k"><item text="甲" send="commit:甲乙"/><item text="丙"/></popup></key>');
  out = LE.setPopup(own, at, ['甲', '丁']);
  assert.match(out, /<popup from="list" as-is="true" in-row="3" transform-punctuation="false" preview="k"><item text="甲" send="commit:甲乙"\/><item text="丁"\/><\/popup>/);
  assert.deepEqual(errorsOf(out), []);
  const nine = LE.addRow(xml, 0, '<key id="k9" label="k" send="k"><popup from="preset" preset="q" in-row="2"/></key>');
  assert.deepEqual(LE.popupOf(nine, at), { from: 'preset', preset: 'q', items: [] });
  out = LE.setPopup(nine, at, ['甲']);
  assert.match(out, /<popup from="list" in-row="2"><item text="甲"\/><\/popup>/);
  assert.deepEqual(errorsOf(out), []);
  // a key with children of its own: the list added among them, taken out leaving them
  const q = LE.setPopup(xml, { row: 0, index: 0 }, ['1']);
  assert.match(q, /<key id="q"[^>]*>\n {6}<popup from="list"><item text="1"\/><\/popup>\n {6}<variant when="roots"/);
  assert.equal(LE.setPopup(q, { row: 0, index: 0 }, []), xml);
});

test('letter keys merged into a group key: the board grouped, every letter a group, checked clean', () => {
  const xml = fresh('qwerty');
  const r = LE.merge(xml, [{ row: 0, index: 1 }, { row: 0, index: 0 }]);
  assert.deepEqual(r.at, { row: 0, index: 0 });
  assert.match(r.xml, /<keyboard[^>]* family="grouped"/);
  assert.match(r.xml, /<key id="qw" label="\{group:qw\}" letters="qw" shortcut="my-qwerty\/qw" width="2"\/>/);
  assert.match(r.xml, /<key id="e" label="\{group:e\}" shortcut="full\/e" letters="e">/);
  assert.equal(engineIds(r.xml)[0], 'qw e r t y u i o p');
  assert.deepEqual(errorsOf(r.xml), []);
  assert.deepEqual(LE.coverage(r.xml).q, 1);
  assert.equal(Object.keys(LE.coverage(r.xml)).filter((c) => c !== ';').length, 26);
  // across rows: where the first is; its id free again
  const across = LE.merge(xml, [{ row: 0, index: 9 }, { row: 1, index: 0 }]);
  assert.match(across.xml, /<key id="pa" label="\{group:pa\}"/);
  assert.equal(ids(across.xml)[1].split(' ')[0], 's');
  assert.deepEqual(LE.merge(xml, [{ row: 0, index: 0 }]), { error: '选两个以上的字母键再合并' });
  assert.deepEqual(LE.merge(xml, [{ row: 0, index: 0 }, { row: 2, index: 0 }]), { error: '只有字母键能合并' });
  const six = [0, 1, 2, 3, 4, 5].map((index) => ({ row: 0, index }));
  assert.deepEqual(LE.merge(xml, six), { error: '分组字母是 1–5 个 a–z 或 ;' });
  assert.match(LE.MERGED, /^变成分组键盘：只有拼音能用/);
});

test('rows: added, moved, taken out; their height and indent; the app\'s bottom row made the layout\'s own', () => {
  const xml = fresh('k18');
  const parts = (t) => LE.parse(t).parts.map((x) => (x.row ? x.row.cells.length : 'include:' + x.include.a.src)).join(' ');
  assert.equal(parts(xml), '7 6 7 include:grouped-bottom');
  assert.equal(LE.partOfRow(xml, 2), 2);
  assert.equal(parts(LE.movePart(xml, 3, 0)), 'include:grouped-bottom 7 6 7');
  assert.equal(parts(LE.movePart(xml, 0, 4)), '6 7 include:grouped-bottom 7');
  assert.equal(LE.movePart(xml, 1, 2), xml, 'to where it is: nothing');
  assert.equal(LE.movePart(LE.movePart(xml, 0, 2), 1, 0), xml, 'moved back: as it was');
  assert.equal(parts(LE.addRow(xml, 4, '<gap/>')), '7 6 7 include:grouped-bottom 1');
  assert.equal(parts(LE.removePart(xml, 1)), '7 7 include:grouped-bottom');
  const tall = LE.setRow(xml, 0, { height: '1.2', indent: '0.5' });
  assert.match(tall, /<row height="1.2" indent="0.5">/);
  assert.ok(E.readOwnLayout(tall).ok);
  // the bottom row: which include draws a key, its rows copied in (indented, the include's when= on them)
  const src = (s) => E.layoutSource(s);
  assert.equal(LE.includeOf(xml, 'space', src), 0);
  assert.equal(LE.includeOf(xml, 'r1c1', src), -1);
  assert.equal(LE.locate(xml, 'space', () => []), null);
  const own = LE.ownRows(xml, 0, src('grouped-bottom'));
  assert.ok(!own.includes('<include'));
  assert.match(own, /\n {2}<row>\n {4}<key id="symbols"[^\n]*\n {9}send="layout:Symbol"/);
  assert.deepEqual(errorsOf(own), []);
  assert.deepEqual(engineIds(own), [...ids(xml), ...ids(src('grouped-bottom'))], 'the same keys as the include drew');
  const when = LE.ownRows(xml.replace('<include src="grouped-bottom"/>', '<include src="grouped-bottom" when="ime=pinyin"/>'), 0, src('grouped-bottom'));
  assert.match(when, /<row when="ime=pinyin">/);
  assert.equal(LE.ownRows(xml, 0, null), xml, 'no fragment text: nothing');
  // a fragment's own <defaults> (under its rows', over the board's) carried into each row's, a row's own winning
  const frag = '<fragment id="f1">\n<defaults kind="function" width="2"/>\n<row>\n<key id="za" label="a" send="a"/>\n</row>\n<row>\n<defaults width="3"/>\n<key id="zb" label="b" send="b"/>\n</row>\n</fragment>';
  const carried = LE.ownRows(xml, 0, frag);
  assert.match(carried, /<row>\n {4}<defaults kind="function" width="2"\/>\n {4}<key id="za"/);
  assert.match(carried, /<defaults width="3" kind="function"\/>\n {4}<key id="zb"/);
  assert.ok(E.readOwnLayout(carried).ok, "the engine reads it");
});

test('a key the scene shows found where it is written: once, twice (each its when=), by its problem\'s line', () => {
  const xml = fresh('number');
  const drawn = (pad) => (t) => {
    const r = E.readOwnLayout(t);
    const s = E.buildScene(r.layout, material, LM.envJson(E, { ...LM.ENV, pad }, 'number', r.layout), fixed);
    return [...s.scene.json().keys].map((k) => k.id);
  };
  const full = LE.locate(xml, '*', drawn('full'));
  const phone = LE.locate(xml, '*', drawn('phone'));
  assert.notDeepEqual(full, phone);
  const tag = (at) => LE.cellAt(LE.parse(xml), at).tag;
  assert.match(tag(full), /when="pad=full"/);
  assert.match(tag(phone), /when="pad=phone"/);
  assert.deepEqual(LE.locate(fresh('qwerty'), 'q', () => assert.fail('not asked for a key written once')), { row: 0, index: 0 });
  const q = fresh('qwerty');
  assert.deepEqual(LE.atLine(q, LM.lineOfKey(q, 'e')), { row: 0, index: 2 });
  assert.equal(LE.atLine(q, 1), null);
});
