// The layout maker: its page, its library against the built engine, and its editing, each change read back by the engine.
// Run: node --test web/theme-maker/test/layout-maker.test.mjs   (needs ./gradlew :lib:keyboard-scene:youmoEngine)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { createHash } from 'node:crypto';
import vm from 'node:vm';
import { assemble, blockOf, ENGINE, LAYOUT_SRC, layoutExamplesJson, NO_ENGINE } from '../tools/assemble.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const src = join(here, '..', 'src');
for (const f of ['lib.js', 'form.js', 'maker.js', join('layout', 'layout-maker.js'), join('layout', 'layout-edit.js')]) vm.runInThisContext(readFileSync(join(src, f), 'utf8'), { filename: f });
const M = globalThis.YoumoMaker;
const L = globalThis.YoumoLib;
const LM = globalThis.YoumoLayoutMaker;
globalThis.Path2D = class { constructor(d) { this.d = d; } };
const E = createRequire(import.meta.url)(ENGINE).YoumoEngine;
const FORMAT = E.layoutFormat();
const fixed = E.fixedMeasurer();
const material = E.readTheme(E.themeSource('material'), null, null).theme;
const enc = (t) => new TextEncoder().encode(t);
const lineOfKey = (xml, id) => xml.split('\n').findIndex((l) => l.includes('<key id="' + id + '"')) + 1;
const fresh = (base = 'k18') => LM.fromBuiltin(E, base, 'my-' + base, '我的' + LM.BASE_LABEL[base]);

test('the page is made of src/layout/ and the shared scripts, runs only its own by their hashes, and the theme maker and it link each other', () => {
  const page = assemble(LAYOUT_SRC);
  assert.equal(blockOf(page, 'youmo-engine'), NO_ENGINE);
  assert.equal(blockOf(page, 'youmo-lib'), readFileSync(join(src, 'lib.js'), 'utf8').replace(/\n+$/, ''));
  assert.equal(JSON.parse(blockOf(page, 'youmo-format')).limits.fileBytes > 0, true);
  for (const [id, file] of [['youmo-form', 'form.js'], ['youmo-maker', 'maker.js'], ['youmo-layout-maker', join('layout', 'layout-maker.js')], ['youmo-layout-edit', join('layout', 'layout-edit.js')], ['youmo-ui', join('layout', 'ui.js')]]) {
    assert.equal(blockOf(page, id), readFileSync(join(src, file), 'utf8').replace(/\n+$/, ''), id);
  }
  assert.ok(page.includes(readFileSync(join(src, 'style.css'), 'utf8').replace(/\n+$/, '') + '\n' + readFileSync(join(LAYOUT_SRC, 'style.css'), 'utf8').replace(/\n+$/, '')));
  const csp = /<meta http-equiv="Content-Security-Policy" content="([^"]*)">/.exec(page)[1];
  const run = [...page.matchAll(/<script(?![^>]*type="application\/json")[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
  assert.equal(run.length, 7);
  assert.match(csp, /default-src 'none'/);
  assert.ok(!/unsafe-inline'[^;]*;/.test(/script-src [^;]*;/.exec(csp)[0]));
  assert.deepEqual(/script-src ([^;]*);/.exec(csp)[1].split(' '), run.map((t) => "'sha256-" + createHash('sha256').update(t).digest('base64') + "'"));
  assert.match(page, /<a class="link" href="\.\.\/">主题制作<\/a>/);
  // up with its help and examples (§14, W3): the theme maker's top bar links it, and Pages puts it up
  assert.match(assemble(), /<a class="link" href="layout\/">键盘布局<\/a>/);
  assert.match(page, /<button id="help-button">帮助<\/button>/);
  const help = /<dialog id="help-panel">[\s\S]*?<\/dialog>/.exec(page)[0];
  assert.ok(help.includes('docs/layout-format.md') && help.includes('docs/layout-maker.md'), 'the help links the docs');
  assert.equal(blockOf(page, 'youmo-layout-examples'), layoutExamplesJson());
  // built: the build's engine, the rest the same
  const built = assemble(LAYOUT_SRC, ENGINE);
  const rest = (p) => p.replace(/<script id="youmo-engine">[\s\S]*?<\/script>/, '').replace(/script-src [^;]*;/, '');
  assert.equal(rest(built), rest(page));
});

test("the engine says what the page offers: the app's version, families, bases, pads, prefixes, limits, every finding", () => {
  assert.equal(FORMAT.version, '0.6.3');
  assert.deepEqual([...FORMAT.families], ['letters', 'grouped', 'nine', 'number', 'symbols']);
  assert.deepEqual([...FORMAT.bases], ['qwerty', 'k14', 'k18', 'nine', 'number', 'symbols']);
  assert.deepEqual([...FORMAT.pads], ['full', 'phone', 'datetime']);
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
  // the full pad is every number field's: said as no kind of field, as the app says it
  assert.equal(LM.words({ key: 'pad-lacks', args: ['full', '- .'] }), '数字键盘缺 - .：数字框里打不出来（比如金额的负号、小数点）');
  assert.equal(LM.words({ key: 'pad-lacks', args: ['phone', '#'] }), '数字键盘「电话」缺 #：这种输入框里打不出来');
  assert.equal(LM.words({ key: 'held', args: ['g18/h', 'k18'] }), 'g18/h 是 18 键的键，这里写的长按、角标、滑动不起作用');
  assert.equal(LM.words({ key: 'held', args: ['fn/x', 'number-row'] }), 'fn/x 是数字行的键，这里写的长按、角标、滑动不起作用');
  assert.equal(LM.words({ key: 'held-letters', args: ['g18/h', 'k18'] }), 'g18/h 是 18 键的键，长按跟它走，不按这里写的字母；要用这些字母，给它起个自己的键名');
  assert.equal(LM.words({ key: 'held-popups', args: ['k18'] }), '这里写的 popups= 不起作用，跟 18 键一样');
  // a name of Latin letters at both ends spaced on both sides; a Chinese one on neither (R26 F8374, as the app's)
  assert.equal(LM.words({ key: 'held-popups', args: ['mine'] }), '这里写的 popups= 不起作用，跟 mine 一样');
  assert.equal(LM.words({ key: 'held-popups', args: ['number-row'] }), '这里写的 popups= 不起作用，跟数字行一样');
  assert.equal(LM.words({ key: 'later', args: ['a'] }), 'later：a');
  assert.equal(LM.words({ key: 'pad-letters', args: ['k1'] }), '键「k1」打出的是字母：数字输入框多半不收字母');
  assert.equal(LM.words({ key: 'base', args: ['nine'] }), 'App 没有这块底稿：九键');
});

// as the app's LayoutTextsTest: the same words for what a board lacks
test('what a board lacks is said with what it costs; a pad named only where the others have it', () => {
  const lacks = (...args) => LM.words({ key: 'lacks', args });
  assert.equal(lacks('backspace'), '没有 ⌫：删不了字，打拼音时打错的字母去不掉（不在打字时可以用工具栏的文本编辑面板删）');
  assert.equal(lacks('lang-no-space'), '没有地球键：换输入法要把空格键长按行为设成切换输入法；也没有空格，空格长按也用不了');
  assert.equal(lacks('digits', '0'), '没有数字键 0');
  assert.equal(lacks('pad-digits', '0'), '数字输入框里键盘直接是这块，打不出 0');
  assert.equal(lacks('pad-digits', '5', 'phone'), '数字键盘「电话」打不出 5：这种输入框里键盘直接是这块');
  assert.equal(lacks('back', '', 'phone datetime'), '没有返回键：回字母键盘要收起键盘再点输入框；数字输入框里一直是这块（数字键盘「电话、日期时间」）');
  assert.equal(lacks('letters', 'q x'), '没有字母 q x（密码框里也用这块键盘）');
  assert.equal(lacks('hyper'), '少了键：hyper');
});

// the nine keys with no 0 and a Return two rows tall: saved with the one warning, for the app that takes it
test('保存 the nine keys lacking 0: no errors, one warning, min-app 0.6.3', () => {
  const xml = readFileSync(join(here, '..', '..', '..', 'lib', 'keyboard-scene', 'src', 'commonTest', 'data', 'layout-check', 'nine-big-enter.layout'), 'utf8');
  const r = LM.saveCheck(E, xml, FORMAT.version, material);
  assert.deepEqual(r.errors, []);
  assert.deepEqual(r.warnings.map((p) => p.message), ['没有数字键 0']);
  assert.equal(LM.info(r.xml).meta['min-app'], '0.6.3');
  assert.ok(E.checkLayout(r.xml, FORMAT.version, null).ok);
});

// an older app reads the command as nothing, so the key would be dead there: the layout asks for the app that has it
test('保存 a key sending 切输入法 or 选输入法: min-app 0.6.3', () => {
  const base = FORMAT.bases.find((b) => LM.info(LM.saveCheck(E, fresh(b), FORMAT.version, material).xml).meta['min-app'] !== '0.6.3');
  for (const name of ['switch_input_method', 'choose_input_method']) {
    // a function key: one that types (letters, space, 分词) takes only its base key's commands
    const xml = fresh(base).replace('<key id="backspace" ', `<key id="backspace" swipe-up="shortcut:${name}" `);
    assert.ok(xml.includes(name), base);
    const r = LM.saveCheck(E, xml, FORMAT.version, material);
    assert.deepEqual(r.errors, [], name);
    assert.equal(LM.info(r.xml).meta['min-app'], '0.6.3', name);
  }
});

test('a new layout from each of the app boards is the board as written, under its own id, name and based-on: clean, and drawn', () => {
  for (const base of FORMAT.bases) {
    const xml = fresh(base);
    const i = LM.info(xml);
    assert.deepEqual([i.id, i.name, i.basedOn, i.family], ['my-' + base, '我的' + LM.BASE_LABEL[base], base, E.layout(base).family], base);
    const r = LM.check(E, xml, LM.ENV, material, fixed, FORMAT.version);
    assert.deepEqual(r.problems.filter((p) => p.level === 'error'), [], base);
    assert.ok(r.scene, base + ': drawn');
    const app = E.buildScene(base, material, LM.envJson(E, LM.ENV, i.family, E.layout(base)), fixed);
    assert.equal(r.scene.text(), app.scene.text(), base);
  }
  assert.match(fresh('k18'), /<include src="grouped-bottom"\/>/);
  assert.throws(() => LM.fromBuiltin(E, 'nope', 'my-x', 'x'), /no built-in layout/);
});

test("a layout's marks are its base's: own 18 keys of k18 have the 18 keys' corners, as the app gives them (§5.3)", () => {
  const r = E.readOwnLayout(fresh('k18'));
  assert.ok(r.ok, JSON.stringify(r.problems));
  assert.deepEqual(E.defaultMarks(r.layout, {}), E.defaultMarks('k18', {}));
  assert.deepEqual(E.defaultMarks(E.readOwnLayout(fresh('k14')).layout, {}), E.defaultMarks('k14', {}));
  // read as a theme pack's file (its own id, my-k18: not k18), its corners would be the 14 keys' variant's
  assert.notDeepEqual(E.defaultMarks(E.readLayout(fresh('k18'), null).layout, {}), E.defaultMarks('k18', {}));
});

test('the 新建 dialog refuses an id or a name the app would', () => {
  assert.equal(LM.idProblem('my-k17', FORMAT), null);
  for (const bad of ['My', 'a_b', '-a', 'a-', 'a--b', '', 'a'.repeat(33), 'k18', 'qwerty', 'g18', 'fn']) assert.ok(LM.idProblem(bad, FORMAT), bad);
  assert.equal(LM.idProblem('grouped-bottom', FORMAT), 'App 自带的键盘或片段叫 grouped-bottom，换一个');
  assert.ok(E.checkLayout(LM.setKeyboard(fresh('k18'), 'id', 'grouped-bottom'), FORMAT.version, null).errors.some((f) => f.key === 'id-built-in'));
  for (const id of FORMAT.builtIn) assert.ok(LM.idProblem(id, FORMAT), id);
  assert.equal(LM.nameProblem('17 键', FORMAT), null);
  assert.ok(LM.nameProblem('  ', FORMAT));
  assert.ok(LM.nameProblem('x'.repeat(25), FORMAT));
  assert.deepEqual(LM.suggested('nine', FORMAT), { id: 'my-nine', name: '我的九键' });
  for (const b of FORMAT.bases) assert.equal(LM.idProblem(LM.suggested(b, FORMAT).id, FORMAT), null, b);
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
  const odd = LM.setKeyboard(xml, 'name', "a based-on='x'");
  assert.equal(LM.info(odd).name, "a based-on='x'");
  assert.equal(LM.info(odd).basedOn, 'k18');
  assert.match(odd, /name="a based-on=&apos;x&apos;"/);
  assert.equal(LM.info(LM.setKeyboard(odd, 'based-on', 'k14')).name, "a based-on='x'");
  assert.equal(LM.info(LM.setKeyboard(odd, 'based-on', 'k14')).basedOn, 'k14');
  const back = LM.setKeyboard(odd, 'name', '我的18 键');
  assert.equal(back, xml.replace('name="我的18 键"', 'name="我的18 键"'));
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
  const crlf = LM.openText(enc(xml.replace(/\n/g, '\r\n')), FORMAT.limits.bytes);
  assert.equal(crlf, xml);
  assert.ok(!LM.setMeta(crlf, 'author', 'x').includes('\r'));
});

test('保存: errors keep it from being saved; else min-app written as the checker works it out, the file named by its name', () => {
  const xml = fresh('k18');
  const ok = LM.saveCheck(E, xml, FORMAT.version, material);
  assert.deepEqual(ok.errors, []);
  assert.equal(LM.info(ok.xml).meta['min-app'], E.checkLayout(xml, FORMAT.version, null).minApp);
  assert.equal(LM.saveCheck(E, ok.xml, FORMAT.version, material).xml, ok.xml);
  assert.ok(E.checkLayout(ok.xml, FORMAT.version, null).ok);
  const bad = LM.setKeyboard(xml, 'id', 'k18');
  const no = LM.saveCheck(E, bad, FORMAT.version, material);
  assert.equal(no.errors.length > 0, true);
  assert.ok(no.errors.some((p) => /和 App 自带的键盘或片段重名/.test(p.message)), JSON.stringify(no.errors));
  assert.equal(no.xml, bad);
  const narrow = xml.replace('<key id="r1c1" ', '<key id="r1c1" width="2" ');
  const w = LM.saveCheck(E, narrow, FORMAT.version, material);
  assert.deepEqual(w.errors, []);
  assert.ok(w.warnings.some((p) => p.message === '键「r1c1」很窄，可能不好按' && p.line > 0), JSON.stringify(w.warnings));
  assert.ok(LM.saveCheck(E, LM.setMeta(xml, 'min-app', '9.0'), FORMAT.version, null).errors.some((p) => /9\.0/.test(p.message)));
  assert.equal(LM.fileName(xml), '我的18 键.layout');
  assert.equal(LM.fileName(LM.setKeyboard(xml, 'name', 'a/b:c')), 'a_b_c.layout');
  assert.equal(LM.fileName('<x/>'), 'layout.layout');
  const described = LM.setMeta(xml, 'description', "min-app='0.1'");
  const d = LM.saveCheck(E, described, FORMAT.version, material);
  assert.deepEqual(d.errors, []);
  assert.deepEqual(LM.info(d.xml).meta, { description: "min-app='0.1'", 'min-app': E.checkLayout(xml, FORMAT.version, null).minApp });
  assert.ok(E.checkLayout(d.xml, FORMAT.version, null).ok);
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
  const unnamed = LM.check(E, xml.replace(/(<key id="q"[^>]*?) shortcut="[^"]*"/, '$1'), LM.ENV, material, fixed, FORMAT.version);
  const u = unnamed.problems.find((p) => /没有按键名/.test(p.message));
  assert.ok(u && u.line === lineOfKey(xml, 'q'), JSON.stringify(unnamed.problems));
  // a key of the app's name writing a swipe of its own: told by its line (the app's is taken)
  const held = LM.check(E, xml.replace(/(<key id="q"[^>]*?) shortcut="full\/q"/, '$1 shortcut="full/q" swipe-down="x"'), LM.ENV, material, fixed, FORMAT.version);
  const h = held.problems.find((p) => p.message === 'full/q 是全键盘的键，这里写的长按、角标、滑动不起作用');
  assert.ok(h && h.level === 'warning' && h.line === lineOfKey(xml, 'q'), JSON.stringify(held.problems));
  // an engine that throws is a problem, not a page that stops
  const thrown = LM.check({ checkLayout: () => { throw new Error('boom'); } }, xml, LM.ENV, material, fixed, FORMAT.version);
  assert.deepEqual(thrown.problems, [{ level: 'error', line: 0, message: '引擎出错：boom' }]);
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
  assert.ok(w && w.level === 'warning' && w.line === lineOfKey(retired, 'q'), JSON.stringify(rr.problems));
  // past the app's size: that one error, nothing read or drawn
  const big = xml.replace('</keyboard>', '<!--' + 'x'.repeat(FORMAT.limits.bytes) + '--></keyboard>');
  const b = LM.check(E, big, LM.ENV, material, fixed, FORMAT.version);
  assert.deepEqual(b.problems.map((p) => p.message), ['文件 ' + enc(big).length + ' 字节，超过 64 KB']);
  assert.equal(b.layout, null);
  assert.equal(b.scene, null);
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

// L8 (§12.4): a .theme opened, its boards the app lists made layouts of the user's as the app makes them on import
test("a theme pack's boards are offered made layouts of the user's, as the app's import makes them", async () => {
  const data = join(here, '..', '..', '..', 'lib', 'keyboard-scene', 'src', 'commonTest', 'data');
  const k18 = readFileSync(join(data, 'mint-k17', 'layouts', 'k18.xml'), 'utf8');
  const xml = readFileSync(join(data, 'mint-k17', 'theme.xml'), 'utf8').replace('<colors>', '<meta author="小明" license="CC-BY-4.0"/>\n  <colors>');
  // a pack as the page opens it: zipped, its zhuyin.xml (a board the app still has replaced) and a broken number.xml beside
  const tf = JSON.parse(readFileSync(join(here, '..', 'generated', 'youmo-format.json'), 'utf8'));
  const zip = L.writeZip([['theme.xml', enc(xml)], ['layouts/k18.xml', enc(k18)], ['layouts/zhuyin.xml', enc(E.layoutSource('zhuyin'))], ['layouts/number.xml', enc('<keyboard')]]);
  const d = (await M.open(E, L, tf, zip)).doc;
  const theme = E.readTheme(d.xml, null, null).theme;
  const boards = LM.themeBoards(E, d, theme, [...FORMAT.bases], FORMAT.version);
  assert.deepEqual(boards.map((b) => [b.board, b.ok]), [['k18', true], ['number', false]], 'the listed boards it has, in their order; 注音 not one');
  const made = E.convertPackLayout('薄荷 17 键', { author: '小明', license: 'CC-BY-4.0' }, 'k18', k18, null, '0.6.1');
  assert.equal(boards[0].text, made.text, 'the app\'s import\'s');
  assert.equal(boards[0].text, readFileSync(join(data, 'convert', 'k17-motion.layout'), 'utf8'));
  assert.equal(boards[0].name, '薄荷 17 键 · 17 键');
  assert.equal(boards[0].id, made.id);
  assert.equal(boards[1].why, '读不出来', 'in words, not PackLayouts\' own (review S2)');
  assert.equal(LM.refusal("the app's k18 does not read"), 'App 自带的 k18 读不出来');
  assert.equal(LM.refusal('made one that does not take: a letters board lacks the keys x'), '转出来的布局检查不过：a letters board lacks the keys x');
  assert.equal(LM.refusal('its fragment grouped-bottom does not read'), '它用的片段 grouped-bottom 读不出来');
  assert.equal(LM.refusal("over 256 when= alternatives an include's and its rows' make"), '它用的片段和片段里的行，when= 条件合起来超过 256 种');
  assert.equal(LM.refusal('it has no slot [nine-side, symbol-grid]'), '少了 App 这块必有的位置：九键左边的符号列、符号面板的符号格子');
  assert.equal(LM.refusal('it has no key [backspace, enter]'), '少了 App 这块必有的键：删除、回车');
  assert.equal(LM.refusal('it has no key [2, 3]'), '少了 App 这块必有的键：2、3');
  const nine = E.layoutSource('nine');
  // made the user's own, a pack's board lacking what the app's has is judged as one they import: made, what it lacks told
  const lacks = (text) => {
    const c = E.convertPackLayout('t', null, 'nine', text, null, FORMAT.version);
    assert.ok(c.ok, c.why);
    return c.problems.filter((p) => p.key === 'lacks').map((p) => [...p.args]);
  };
  assert.deepEqual(lacks(nine), []);
  assert.deepEqual(lacks(nine.replace('kind="nine-side"', 'kind="symbol-grid"')), [['nine-side']]);
  assert.deepEqual(lacks(nine.replace('role="backspace"', '')), [['backspace']]);
  // each why the Kotlin gives, read from it: one added there and not here comes out in English
  const kotlin = (f) => readFileSync(join(here, '..', '..', '..', 'lib', 'keyboard-scene', 'src', 'commonMain', 'kotlin', 'org', 'fcitx', 'fcitx5', 'android', 'scene', 'layout', f), 'utf8');
  const fun = (text, file, name) => {
    const m = new RegExp('fun ' + name + '\\([\\s\\S]*?\\n    }\\n').exec(text);
    assert.ok(m, file + ': no fun ' + name + '( closed by a "    }" line');
    return m[0];
  };
  const literal = '"((?:\\$\\{[^}]*\\}|[^"\\\\]|\\\\.)*)"';
  const pack = kotlin('PackLayouts.kt');
  const found = (what, list, least) => {
    assert.ok(new Set(list).size >= least, what + ': ' + new Set(list).size + ' whys read, fewer than its ' + least + ': ' + list.join(' | '));
    return list;
  };
  // the floors are each one's current distinct whys (one fewer means the pattern no longer finds one)
  const whys = new Set([
    ...found('PackLayouts Converted.Not', [...pack.matchAll(new RegExp('Converted\\.Not\\(' + literal, 'g'))].map((m) => m[1]), 6),
    ...found('PackLayouts.judge', [...fun(pack, 'PackLayouts.kt', 'judge').matchAll(new RegExp('return ' + literal, 'g'))].map((m) => m[1]), 3),
    ...found('LayoutCheck.kept', [...fun(kotlin('LayoutCheck.kt'), 'LayoutCheck.kt', 'kept').matchAll(new RegExp('return ' + literal, 'g'))].map((m) => m[1].replace('$it', '[$it]')), 2),
  ]);
  for (const w of whys) {
    const said = LM.refusal(w.replace(/\$\{[^}]*\}|\$\w+/g, 'k18'));
    assert.doesNotMatch(said.replaceAll('k18', '').replaceAll('App', '').replace(/[a-z-]+=/g, ''), /[A-Za-z]{3,}/, w);
  }
  assert.deepEqual(LM.saveCheck(E, boards[0].text, FORMAT.version, theme).errors, []);
  const bottom = E.layoutSource('grouped-bottom').replace('<fragment', '<!-- mine -->\n<fragment');
  const withBottom = M.doc(d.xml, {}, { 'k18.xml': k18, 'grouped-bottom.xml': bottom });
  assert.equal(LM.themeBoards(E, withBottom, theme, [...FORMAT.bases], FORMAT.version)[0].text,
    E.convertPackLayout('薄荷 17 键', { author: '小明', license: 'CC-BY-4.0' }, 'k18', k18, { 'grouped-bottom': bottom }, '0.6.1').text);
  assert.deepEqual(LM.themeBoards(E, M.doc(d.xml), theme, [...FORMAT.bases], FORMAT.version), []);
});

// just above its size, so it grows only on purpose
test('the layout maker built is no bigger than its budget', () => {
  const page = assemble(LAYOUT_SRC, ENGINE);
  assert.ok(Buffer.byteLength(page) <= 2_668_500, 'the page is ' + Buffer.byteLength(page) + ' bytes');
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
  const opened = [];
  const idb = { open: (name) => { opened.push(name); return {}; } };
  const s = M.idbStore(idb, 'youmo-layout-maker');
  s.get('x').catch(() => {});
  assert.deepEqual(opened, ['youmo-layout-maker']);
  M.idbStore(idb).get('x').catch(() => {});
  assert.deepEqual(opened, ['youmo-layout-maker', 'youmo-theme-maker']);
});


const LE = globalThis.YoumoLayoutEdit;
const ids = (xml) => LE.parse(xml).rows.map((r) => r.cells.map((c) => c.a.id || '(' + c.name + ')').join(' '));
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
  const fns = LE.functionKeys([E.layoutSource('k18'), E.layoutSource('grouped-bottom')]);
  for (const id of ['apostrophe', 'backspace', 'symbols', 'numbers', 'comma', 'space', 'period', 'lang', 'enter']) assert.ok(fns.some((f) => f.id === id), id);
  assert.ok(!fns.some((f) => /when=/.test(f.text) || f.text.includes('letters=')));
  assert.equal(fns.find((f) => f.id === 'backspace').label, '⌫');
  // the include's comma is the board's: a second "comma" the checker refuses
  const source = (s) => E.layoutSource(s);
  for (const base of ['k14', 'k18']) {
    const k = fresh(base);
    const texts = [E.layoutSource(base), ...LE.parse(E.layoutSource(base)).includes.map((x) => E.layoutSource(x.a.src))];
    for (const f of LE.functionKeys(texts)) {
      const added = LE.addRow(k, 0, LE.withFreeId(k, f.text, source));
      assert.deepEqual(errorsOf(added), [], base + ' + ' + f.id);
    }
    const comma = LE.addRow(k, 0, LE.withFreeId(k, fns.find((f) => f.id === 'comma').text, source));
    assert.equal(engineIds(comma)[0].split(' ')[0], 'comma-2', base + ': the bottom row\'s comma taken');
  }
  assert.equal(LE.freeId(fresh('k18'), 'comma'), 'comma', 'without the fragments: the file\'s own ids only');
  assert.equal(LE.charKey(fresh('k18'), 'x', source), LE.charKey(fresh('k18'), 'x'), 'c1 no fragment has');
  const semi = LE.letterKey(LE.remove(xml, { row: 1, index: 9 }), ';');
  assert.equal(semi, '<key id="semicolon" label=";" send=";" shortcut="my-qwerty/semicolon"/>');
  assert.equal(LE.letterKey(xml, ';'), '<key id="semicolon-2" label=";" send=";" shortcut="my-qwerty/semicolon-2"/>');
  assert.deepEqual(errorsOf(LE.addRow(xml, 0, LE.letterKey(xml, ';'))), []);
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
  for (const raw of [null, 'x', '{case:x}', '{punct:,}', '{ime-name}', '{punct:,}{punct:.}']) {
    const p = LE.labelParts(raw);
    assert.equal(LE.labelText(p.type, p.arg), raw, String(raw));
  }
  assert.equal(LE.labelParts('{ime-name}').type, 'other');
  assert.deepEqual(LE.labelParts('{punct:,}{punct:.}'), { type: 'other', arg: '{punct:,}{punct:.}' });
  assert.equal(LE.labelProblem('other', '{punct:,}{punct:.}'), null);
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
  const own = LE.addRow(xml, 0, '<key id="k9" label="k" send="k"><popup from="list" as-is="true" in-row="3" transform-punctuation="false" preview="k"><item text="甲" send="commit:甲乙"/><item text="丙"/></popup></key>');
  out = LE.setPopup(own, at, ['甲', '丁']);
  assert.match(out, /<popup from="list" as-is="true" in-row="3" transform-punctuation="false" preview="k"><item text="甲" send="commit:甲乙"\/><item text="丁"\/><\/popup>/);
  assert.deepEqual(errorsOf(out), []);
  const nine = LE.addRow(xml, 0, '<key id="k9" label="k" send="k"><popup from="preset" preset="q" in-row="2"/></key>');
  assert.deepEqual(LE.popupOf(nine, at), { from: 'preset', preset: 'q', items: [] });
  out = LE.setPopup(nine, at, ['甲']);
  assert.match(out, /<popup from="list" in-row="2"><item text="甲"\/><\/popup>/);
  assert.deepEqual(errorsOf(out), []);
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
  const across = LE.merge(xml, [{ row: 0, index: 9 }, { row: 1, index: 0 }]);
  assert.match(across.xml, /<key id="pa" label="\{group:pa\}"/);
  assert.equal(ids(across.xml)[1].split(' ')[0], 's');
  assert.deepEqual(LE.merge(xml, [{ row: 0, index: 0 }]), { error: '选两个以上的字母键再合并' });
  assert.deepEqual(LE.merge(xml, [{ row: 0, index: 0 }, { row: 2, index: 0 }]), { error: '只有字母键能合并' });
  const six = [0, 1, 2, 3, 4, 5].map((index) => ({ row: 0, index }));
  assert.deepEqual(LE.merge(xml, six), { error: '分组字母是 1–5 个 a–z 或 ;' });
  assert.match(LE.MERGED, /^变成分组键盘：只有拼音能用/);
});

test('a group of three letters or more is written out in capitals, the reader taking it', () => {
  assert.equal(LE.groupLabel('qw'), '{group:qw}');
  assert.equal(LE.groupLabel('l;'), '{group:l;}');
  assert.equal(LE.groupLabel('hjk'), 'HJK');
  const xml = fresh('qwerty');
  assert.equal(LE.letterKey(xml, 'hjk'), '<key id="hjk" label="HJK" letters="hjk" shortcut="my-qwerty/hjk"/>');
  const r = LE.merge(xml, [{ row: 1, index: 5 }, { row: 1, index: 6 }, { row: 1, index: 7 }]);
  assert.match(r.xml, /<key id="hjk" label="HJK" letters="hjk" shortcut="my-qwerty\/hjk" width="3"\/>/);
  assert.doesNotMatch(r.xml, /\{group:[a-z;]{3,}\}/);
  assert.deepEqual(errorsOf(r.xml), []);
  assert.equal(engineIds(r.xml)[1], 'a s d f g hjk l semicolon star');
  const five = LE.merge(xml, [0, 1, 2, 3, 4].map((index) => ({ row: 0, index })));
  assert.match(five.xml, /<key id="qwert" label="QWERT" letters="qwert"/);
  assert.deepEqual(errorsOf(five.xml), []);
  const gone = LE.remove(r.xml, { row: 1, index: 5 });
  assert.deepEqual(errorsOf(LE.addRow(gone, 0, LE.letterKey(gone, 'hjk'))), []);
  assert.equal(LE.groupLabel('h jk'), 'HJK');
  assert.equal(LE.groupLabel('q w'), '{group:qw}');
});

test('group letters set: the face the group\'s own follows them, a face written over stays, an app key\'s named its own', () => {
  const k18 = fresh('k18');
  const h = { row: 1, index: 3 };
  const a = (x) => LE.cellAt(LE.parse(x), h).a;
  const held = (x) => [...E.checkLayout(x, FORMAT.version, null).warnings].filter((f) => f.key === 'held-letters').map((f) => f.line);
  const hjk = LE.setLetters(k18, h, 'hjk', held);
  assert.deepEqual([a(hjk).label, a(hjk).letters, a(hjk).shortcut], ['HJK', 'hjk', 'my-k18/hjk'], 'named as the app names a pack\'s (L6)');
  assert.deepEqual(held(hjk), []);
  assert.deepEqual(held(LE.setLetters(k18, h, 'hjk')), [LE.parse(k18).rows[1].cells[3].line], 'not named: told');
  assert.equal(a(LE.setLetters(hjk, h, 'hj', held)).shortcut, 'my-k18/hjk');
  assert.equal(a(LE.setLetters(k18, h, 'h', held)).shortcut, 'g18/h');
  const taken = LE.setAttrs(k18, { row: 0, index: 0 }, { shortcut: 'my-k18/hjk' });
  assert.equal(a(LE.setLetters(taken, h, 'hjk', held)).shortcut, 'my-k18/hjk-2');
  const l = { row: 1, index: 5 };
  assert.equal(LE.cellAt(LE.parse(LE.setLetters(k18, l, 'kl;', held)), l).a.shortcut, 'my-k18/kl');
  assert.equal(LE.cellAt(LE.parse(LE.setLetters(k18, l, ';', held)), l).a.shortcut, 'my-k18/keys');
  const inVariant = k18.replace(/(<key id="r1c1"[^>]*)\/>/, '$1><variant when="landscape" shortcut="my-k18/hjk"/></key>');
  assert.notEqual(inVariant, k18);
  assert.equal(a(LE.setLetters(inVariant, h, 'hjk', held)).shortcut, 'my-k18/hjk-2');
  const inDefaults = k18.replace('<row>', '<row>\n    <defaults shortcut="my-k18/hjk"/>');
  assert.notEqual(inDefaults, k18);
  assert.equal(a(LE.setLetters(inDefaults, h, 'hjk', held)).shortcut, 'my-k18/hjk-2');
  const own = hjk.replace(/(<key id="r2c4"[^>]*)\/>/, '$1><variant when="landscape" shortcut="my-k18/hjk"/></key>');
  assert.notEqual(own, hjk);
  assert.equal(a(LE.setLetters(own, h, 'hjk', held)).shortcut, 'my-k18/hjk');
  const old = LE.setAttrs(hjk, h, { label: '{group:hjk}' });
  assert.equal(a(LE.setLetters(old, h, 'hjkl', held)).label, 'HJKL');
  assert.notDeepEqual(errorsOf(old), []);
  const back = M.oldGroupFaces(old);
  assert.equal(back.keys, 1);
  assert.equal(back.xml, hjk);
  assert.deepEqual(errorsOf(back.xml), []);
  assert.deepEqual(M.oldGroupFaces(hjk), { xml: hjk, keys: 0 });
  const other = LE.setAttrs(hjk, h, { label: '{group:xyz}' });
  assert.deepEqual(M.oldGroupFaces(other), { xml: other, keys: 0 });
  // a `label="…"` inside an earlier attribute's value is that value, left alone
  assert.deepEqual(M.oldGroupFaces(old.replace('label="{group:hjk}"', "label='{group:hjk}'")), { xml: hjk, keys: 1 });
  const inValue = (xml, label) => xml.replace(label, `swipe-down='commit:label="{group:hjk}"' ${label}`);
  const face = /label="[^"]*"/.exec(hjk.slice(hjk.indexOf('id="r2c4"')))[0];
  assert.deepEqual(M.oldGroupFaces(inValue(old, 'label="{group:hjk}"')), { xml: inValue(hjk, face), keys: 1 });
  // the face written as this page writes it (M's own copy of LE.groupLabel's capitals), by rows or not
  const rowsOld = LE.setAttrs(LE.setLetters(hjk, h, 'h jk', held), h, { label: '{group:hjk}' });
  assert.equal(a(M.oldGroupFaces(rowsOld).xml).label, LE.groupLabel('h jk'));
  // and the theme maker's 打开 .layout (F8459): such a file drawn as the engine reads it now, how many said (as it is,
  // the reader refuses it: a line error, nothing said why)
  assert.equal(E.readOwnLayout(old).ok, false);
  const opened = M.openLayout(E, enc(old));
  assert.deepEqual([opened.id, opened.oldFaces], ['my-k18', 1]);
  assert.equal(M.openLayout(E, enc(hjk)).oldFaces, 0);
  // letters by rows: the face followed after, and again
  const rows = LE.setLetters(hjk, h, 'h jk', held);
  assert.deepEqual([a(rows).label, a(rows).letters], ['HJK', 'h jk']);
  assert.equal(a(LE.setLetters(rows, h, 'hj', held)).label, '{group:hj}');
  // a face written over: kept
  assert.equal(a(LE.setLetters(LE.setAttrs(hjk, h, { label: '好' }), h, 'hj', held)).label, '好');
  // taken out: no letters, the face as it was
  assert.deepEqual([a(LE.setLetters(hjk, h, null, held)).letters, a(LE.setLetters(hjk, h, null, held)).label], [undefined, 'HJK']);
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
  // both when= held, each of the include's `, ` groups with the row's (review R26 F8368: `a, b c` read `a, (b c)`)
  const rowWhen = '<fragment id="f2">\n<row when="landscape">\n<key id="zc" label="c" send="c"/>\n</row>\n</fragment>';
  const anded = LE.ownRows(xml.replace('<include src="grouped-bottom"/>', '<include src="grouped-bottom" when="ime=pinyin, ime=wubi"/>'), 0, rowWhen);
  assert.match(anded, /<row when="ime=pinyin landscape, ime=wubi landscape">/);
  assert.ok(E.readOwnLayout(anded).ok, 'the engine reads it');
  assert.equal(LE.ownRows(xml, 0, null), xml, 'no fragment text: nothing');
  // a fragment's own <defaults> (under its rows', over the board's) carried into each row's, a row's own winning
  const frag = '<fragment id="f1">\n<defaults kind="function" width="2"/>\n<row>\n<key id="za" label="a" send="a"/>\n</row>\n<row>\n<defaults width="3"/>\n<key id="zb" label="b" send="b"/>\n</row>\n</fragment>';
  const carried = LE.ownRows(xml, 0, frag);
  assert.match(carried, /<row>\n {4}<defaults kind="function" width="2"\/>\n {4}<key id="za"/);
  assert.match(carried, /<defaults width="3" kind="function"\/>\n {4}<key id="zb"/);
  assert.ok(E.readOwnLayout(carried).ok, "the engine reads it");
});

test('a key the scene shows found where it is written: once, twice (each its when=), by its problem\'s line', () => {
  // 0.6.2's number board, its * written once for each of two pads (the app's has its marks in a scroll column now)
  const xml = readFileSync(join(here, '..', '..', '..', 'lib', 'keyboard-scene', 'src', 'commonTest', 'data', 'layout-check', 'number-062.xml'), 'utf8')
    .replace('id="number"', 'id="my-number" based-on="number"');
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
  assert.deepEqual(LE.atLine(q, lineOfKey(q, 'e')), { row: 0, index: 2 });
  assert.equal(LE.atLine(q, 1), null);
});

// §12.4 从示例开始 (W3): examples/layouts/, each a layout the app takes as it is, put in the page by assemble.mjs
const EXAMPLES = JSON.parse(layoutExamplesJson());

test('an example\'s label is its keyboard\'s name= as XML reads it: not one in a comment, entities undone', () => {
  const dir = mkdtempSync(join(tmpdir(), 'layout-examples-'));
  try {
    writeFileSync(join(dir, 'layouts.json'), JSON.stringify([{ id: 'x', note: 'n' }]));
    writeFileSync(join(dir, 'x.layout'), '<?xml version="1.0"?>\n<!-- <keyboard id="x" name="not this"> -->\n' +
      '<keyboard xmlns="https://youmo-ime/layout/1" id="x" name="A &amp; B &#x4e2d;" family="letters"/>');
    const [x] = JSON.parse(layoutExamplesJson(dir));
    assert.equal(x.label, 'A & B 中');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
const example = (id) => EXAMPLES.find((x) => x.id === id);
const drawn = (l, input) => {
  const s = E.buildScene(l, material, { width: 1080, height: 700, density: 2.625, input }, fixed);
  assert.ok(s.ok, JSON.stringify(s.problems));
  return s.scene.json().keys;
};

test('the examples: 17 键, AZERTY, 大按键数字键盘, each its name its label, checked clean where it says it is used', () => {
  assert.deepEqual(EXAMPLES.map((x) => [x.id, x.label]), [['k17', '17 键'], ['azerty', 'AZERTY'], ['big-number', '大按键数字键盘']]);
  const used = { k17: [['pinyin'], []], azerty: [['pinyin', 'shuangpin', 'english', 'table'], []], 'big-number': [[], ['full']] };
  for (const x of EXAMPLES) {
    assert.ok(x.note, x.id);
    assert.equal(LM.info(x.xml).id, x.id, 'the file is its id');
    const r = E.checkLayout(x.xml, FORMAT.version, material);
    assert.deepEqual([...r.errors].map((f) => f.key), [], x.id);
    assert.deepEqual([...r.warnings].map((f) => f.key), [], x.id + ': not even a warning, as an example');
    assert.deepEqual([[...r.ims], [...r.pads]], used[x.id], x.id);
    assert.equal(r.minApp, '0.6.1');
    const m = LM.info(x.xml).meta;
    assert.equal(m.license, 'CC0-1.0', x.id);
    assert.equal(m['min-app'], '0.6.1', x.id);
    assert.ok(m.description, x.id);
    assert.ok(Buffer.byteLength(x.xml) < FORMAT.limits.bytes);
  }
});

test('17 键: 18 键 with H and JK one key of its own name, its own corner; the rest the app\'s 18 键 keys', () => {
  const x = example('k17').xml;
  const keys = drawn(E.readOwnLayout(x).layout, { ime: 'pinyin' });
  const letters = keys.filter((k) => k.letters);
  assert.equal(letters.length, 17);
  const hjk = keys.find((k) => k.id === 'hjk');
  assert.equal(hjk.label, 'HJK');
  assert.equal(hjk.alt, '(');
  assert.equal(hjk.shortcut, 'k17/hjk');
  for (const k of letters.filter((k) => k.id !== 'hjk')) assert.match(k.shortcut, /^g18\//, k.id);
  assert.equal(letters.map((k) => k.letters || '').join('').replace(/;/g, '').split('').sort().join(''), 'abcdefghijklmnopqrstuvwxyz');
});

test("AZERTY: the French rows, the app's letter keys, ' after N; ; and * after it where 双拼 or a table asks; 拼 only where the table has it", () => {
  const x = example('azerty').xml;
  const l = E.readOwnLayout(x).layout;
  const rows = (keys) => [0, 175, 350].map((y) => keys.filter((k) => k.bounds[1] === y).map((k) => k.id).join(' '));
  const pinyin = drawn(l, { ime: 'pinyin' });
  assert.deepEqual(rows(pinyin), ['a z e r t y u i o p', 'q s d f g h j k l m', 'shift w x c v b n quote backspace']);
  for (const k of pinyin.filter((k) => /^[a-z]$/.test(k.id))) assert.equal(k.shortcut, 'full/' + k.id);
  assert.deepEqual(rows(drawn(l, { ime: 'shuangpin', schemeTypes: ['semicolon'] })), ['a z e r t y u i o p', 'q s d f g h j k l m', 'shift w x c v b n quote semicolon backspace']);
  assert.deepEqual(rows(drawn(l, { ime: 'table', table: 'cangjie', tableExtra: 'star', tableLookUp: true })), ['a z e r t y u i o p', 'q s d f g h j k l m', 'lookup w x c v b n quote star backspace']);
  // 五笔 looks up behind z: ⇧ comes back where 拼 goes, though the file was written before `table-lookup`
  assert.deepEqual(rows(drawn(l, { ime: 'table', table: 'wubi', tableExtra: 'semicolon' })), ['a z e r t y u i o p', 'q s d f g h j k l m', 'shift w x c v b n quote semicolon backspace']);
  assert.equal(drawn(l, { ime: 'english' }).find((k) => k.id === 'a').label, 'a');
});

test('大按键数字键盘: four columns, ⏎ two rows high, . and - half a column each, 符 返回 bottom left', () => {
  const l = E.readOwnLayout(example('big-number').xml).layout;
  const full = drawn(l, { field: 'number', pad: 'full' });
  assert.deepEqual(full.map((k) => k.id), ['1', '2', '3', 'backspace', '4', '5', '6', '.', '-', '7', '8', '9', 'enter', 'symbols', 'back', '0', 'space']);
  const enter = full.find((k) => k.id === 'enter');
  assert.deepEqual(enter.bounds.map(Math.round), [810, 350, 1080, 700]);
  assert.deepEqual(full.find((k) => k.id === '5').bounds.map(Math.round), [270, 175, 540, 350]);
  // every number field has the full pad: the minus an amount takes beside the point
  assert.deepEqual(full.find((k) => k.id === '-').bounds.map(Math.round), [945, 175, 1080, 350]);
  assert.deepEqual([full[13].label, full[14].label], ['符', '返回']);
});

test('the id and name offered are cut to the format\'s limits', () => {
  const long = LM.suggested({ id: 'a'.repeat(28) + '-bcd', label: '很'.repeat(30) }, FORMAT);
  assert.equal(long.id, 'my-' + 'a'.repeat(28));
  assert.equal(long.name.length, FORMAT.limits.name);
  assert.equal(LM.idProblem(long.id, FORMAT), null);
  assert.equal(LM.nameProblem(long.name, FORMAT), null);
});

test('a layout from an example: its id and name, the example\'s notes and <meta> gone, its own keys named by the new id', () => {
  for (const x of EXAMPLES) {
    const s = LM.suggested(x, FORMAT);
    assert.deepEqual(s, { id: 'my-' + x.id, name: '我的' + x.label });
    assert.equal(LM.idProblem(s.id, FORMAT), null);
    assert.equal(LM.nameProblem(s.name, FORMAT), null);
    const xml = LM.fromExample(E, x, s.id, s.name);
    const i = LM.info(xml);
    assert.deepEqual([i.id, i.name, i.family, i.basedOn], [s.id, s.name, LM.info(x.xml).family, LM.info(x.xml).basedOn]);
    assert.doesNotMatch(xml, /<!--|<meta/);
    assert.doesNotMatch(xml, new RegExp('shortcut="' + x.id + '/'));
    const r = E.checkLayout(xml, FORMAT.version, null);
    assert.deepEqual([...r.errors].map((f) => f.key), [], x.id);
  }
  assert.match(LM.fromExample(E, example('k17'), 'my-k17', '我的17 键'), /<key id="hjk"[^>]* shortcut="my-k17\/hjk"/);
  assert.match(LM.fromExample(E, example('azerty'), 'fr', 'FR'), /<key id="a"[^>]* shortcut="full\/a"/);
});

// the 「滑动列」 tab: a column put left of the chosen cell, its category, direction and show written; saved for 0.6.3
test('滑动列: added where it can go, its options written, saved with min-app 0.6.3; 加一列 off where the app has none', () => {
  // a row of its own above the bottom one, its placeholder gap gone: the full rows have no room for another cell
  const part = LE.parse(fresh('qwerty')).rows[3];
  const xml = LE.addRow(fresh('qwerty'), LE.partOfRow(fresh('qwerty'), 3), '<gap/>');
  assert.ok(part && LE.parse(xml).rows.length === 5);
  const at = { row: 3, index: 0 };
  assert.equal(LE.scrollProblem(xml, 'letters', at, FORMAT), null);
  const r = LE.addScroll(xml, at, 'letters', FORMAT);
  assert.ok(!r.error, r.error);
  const p = LE.parse(LE.remove(r.xml, { row: 3, index: 1 }));
  // 16 of a hundred columns, as the app's columns are: 1.5 of qwerty's 10
  assert.deepEqual(LE.scrolls(p).map((s) => s.a), [{ id: 'scroll', kind: 'scroll', list: 'nine', width: '1.5', rows: '1' }]);
  let out = LE.setAttrs(LE.remove(r.xml, { row: 3, index: 1 }), LE.scrolls(p)[0].at, { list: 'url', direction: 'horizontal', show: '6', width: '10' });
  assert.match(out, /<slot id="scroll" kind="scroll" list="url" width="10" rows="1" direction="horizontal" show="6"\/>/);
  const saved = LM.saveCheck(E, out, FORMAT.version, material);
  assert.deepEqual(saved.errors, []);
  assert.equal(LM.info(saved.xml).meta['min-app'], '0.6.3');
  // a second, then no third
  out = LE.addScroll(out, { row: 3, index: 0 }, 'letters', FORMAT).xml;
  assert.equal(LE.scrolls(LE.parse(out)).length, 2);
  assert.match(LE.scrollProblem(out, 'letters', at, FORMAT), /最多 2/);
  // the families the reader refuses one on, and a row split in halves
  for (const family of ['symbols', 'emoji-search']) assert.ok(LE.addScroll(xml, at, family, FORMAT).error, family);
  assert.match(LE.scrollProblem(xml, 'letters', { row: 0, index: 0 }, FORMAT), /两半/);
  assert.deepEqual(FORMAT.scroll.families, ['letters', 'grouped', 'nine', 'number']);
  // on the number pad across the rows under it, three at most as the app's: four items a column not under 20 dp
  const pad = fresh('number');
  const added = (xml, row, family = 'number') => LE.scrolls(LE.parse(LE.addScroll(xml, { row, index: 1 }, family, FORMAT).xml)).find((s) => s.a.id === 'scroll').a.rows;
  assert.deepEqual([added(pad, 0), added(pad, 2), added(pad, 3)], ['3', '2', '1']);
  assert.equal(added(pad.replace(/(<\/row>\s*<row)/, '$1 when="landscape"'), 0), '1', 'not across a row of a when=');
  // the nine keys' rows under it are full: across them it would make each too wide
  assert.equal(added(fresh('nine'), 0, 'nine'), '1');
  for (const id of FORMAT.scroll.lists) assert.ok(LM.SCROLL_LABEL[id], id);
  assert.ok(LM.SLOT_LABEL.scroll);
});

// the phone's and the date's fields: a number field naming its pad, as the app sets it
test('输入框 电话、日期: field=number and its fieldPad', () => {
  for (const pad of ['phone', 'datetime']) {
    assert.ok(LM.FIELDS.some(([f]) => f === pad), pad);
    assert.deepEqual(LM.envJson(E, { ...LM.ENV, field: pad }, 'nine', null).input, { ime: 'pinyin', field: 'number', fieldPad: pad });
    assert.deepEqual(M.envJson(E, { ...M.ENV, field: pad }).input, { field: 'number', ime: 'pinyin', fieldPad: pad });
  }
});
