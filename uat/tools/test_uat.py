"""uat.py's record keeping, without a device: python3 -m unittest test_uat (in uat/tools)"""
import contextlib
import importlib.util
import io
import json
import os
import re
import subprocess
import tempfile
import threading
import time
import unittest
from types import SimpleNamespace
from unittest import mock

import uat


class AttemptsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old_out = uat.OUT
        uat.OUT = self.tmp.name

    def tearDown(self):
        uat.OUT = self.old_out
        self.tmp.cleanup()

    def case(self, cid):
        return SimpleNamespace(id=cid, group="G")

    def test_rerun_keeps_first_failure(self):
        run = uat.Run("r", ["T9-02", "TBL-12c"])
        with contextlib.redirect_stdout(io.StringIO()):
            run.started("5556", "TBL-12c")
            run.started("5557", "T9-02")
            run.record(self.case("T9-02"), "失败", "第一个是「你」", "5557", 3)
            run.record(self.case("TBL-12c"), "通过", "", "5556", 130)
        self.assertEqual(run.results["T9-02"][4][0]["beside"], ["TBL-12c"])
        run = uat.Run("r", ["T9-02"])   # the rerun
        self.assertNotIn("T9-02", run.results)
        with contextlib.redirect_stdout(io.StringIO()):
            run.started("5557", "T9-02")
            run.record(self.case("T9-02"), "通过", "", "5557", 2)
        saved = uat.read_json(os.path.join(uat.OUT, "r", "results.json"))
        att = saved["T9-02"][4]
        self.assertEqual([a["state"] for a in att], ["失败", "通过"])
        self.assertEqual(saved["T9-02"][0], "通过")   # the gate still reads the latest
        self.assertEqual(uat.result_cell(saved["T9-02"]), "通过（第 2 次；第 1 次 失败：第一个是「你」）")
        self.assertEqual(uat.result_cell(saved["TBL-12c"]), "通过")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            uat.flakes([])
        self.assertIn("r T9-02: 通过 on attempt 2", out.getvalue())
        self.assertIn("TBL-12c 130s", out.getvalue())

    def test_old_results_without_attempts(self):
        os.makedirs(os.path.join(uat.OUT, "o"))
        with open(os.path.join(uat.OUT, "o", "results.json"), "w", encoding="utf-8") as f:
            json.dump({"EN-04": ["阻塞", "超时", "5557", 9.0]}, f)
        self.assertEqual(uat.table(uat.read_json(os.path.join(uat.OUT, "o", "results.json")))[2],
                         "| EN-04 | 阻塞 | 超时 |")
        run = uat.Run("o", ["EN-04"])
        with contextlib.redirect_stdout(io.StringIO()):
            run.record(self.case("EN-04"), "通过", "", "5557", 1)
        self.assertEqual([a["state"] for a in run.results["EN-04"][4]], ["阻塞", "通过"])

    def test_no_overlap_not_beside(self):
        run = uat.Run("n", ["A-1", "B-1"])
        with contextlib.redirect_stdout(io.StringIO()):
            run.record(self.case("B-1"), "通过", "", "5556", 1)
            time.sleep(0.05)
            run.started("5557", "A-1")
            run.record(self.case("A-1"), "通过", "", "5557", 0.01)
        self.assertEqual(run.results["A-1"][4][0]["beside"], [])

    def test_a_rerun_stopped_half_way_keeps_the_old_results_it_did_not_reach(self):
        run = uat.Run("h", ["A-1", "B-1"])
        with contextlib.redirect_stdout(io.StringIO()):
            run.record(self.case("A-1"), "失败", "x", "5556", 1)
            run.record(self.case("B-1"), "失败", "y", "5556", 1)
        run = uat.Run("h", ["A-1", "B-1"])   # the rerun, stopped after A-1
        with contextlib.redirect_stdout(io.StringIO()):
            run.record(self.case("A-1"), "通过", "", "5556", 1)
        saved = uat.read_json(os.path.join(uat.OUT, "h", "results.json"))
        self.assertEqual([a["state"] for a in saved["A-1"][4]], ["失败", "通过"])
        self.assertEqual(saved["B-1"][0], "失败")
        self.assertEqual([a["state"] for a in saved["B-1"][4]], ["失败"])

    def test_mark_changes_the_last_attempt_too(self):
        run = uat.Run("m", ["A-1"])
        with contextlib.redirect_stdout(io.StringIO()):
            run.record(self.case("A-1"), "待看图", "", "5556", 1)
        os.makedirs(os.path.join(self.tmp.name, "runs"))
        old_uat = uat.UAT
        uat.UAT = self.tmp.name
        try:
            with open(os.path.join(self.tmp.name, "runs", "m.md"), "w", encoding="utf-8") as f:
                f.write("# m\n\n- 跑法：x 共 1 条：待看图 1\n\n| 编号 | 结果 | 看到的 |\n")
            with contextlib.redirect_stdout(io.StringIO()):
                uat.mark("m", "A-1", "失败", "错位")
        finally:
            uat.UAT = old_uat
        saved = uat.read_json(os.path.join(uat.OUT, "m", "results.json"))
        self.assertEqual(("失败", "看图：错位"), (saved["A-1"][4][-1]["state"], saved["A-1"][4][-1]["seen"]))

    def test_a_short_old_result_keeps_its_fields_in_place(self):
        self.assertEqual({"at": "", "dev": "5557", "state": "阻塞", "seen": "超时", "secs": 0, "beside": []},
                         uat.attempts_of(["阻塞", "超时", "5557"])[0])


class ScreenTest(unittest.TestCase):
    def test_keyboard_found_in_both_dumps(self):
        for t in ("2", "TYPE_INPUT_METHOD"):   # Dumper's, uiautomator dump --windows'
            x = (f'<hierarchy><window type="1"><node text="app" bounds="[0,0][1,1]"/></window>'
                 f'<window type="{t}"><node text="键" package="{uat.PKG}" bounds="[0,0][1,1]"/></window></hierarchy>')
            s = uat.Screen(x)
            self.assertEqual([n.text for n in s.ime() if n.text], ["键"], t)

    def test_the_keys_found_alike_on_the_views_and_the_canvas(self):
        # the old drawing: a view a key, its letter and corner its children's texts; the canvas
        # (060-render-p2 S3): a node a key, its label the text, its corner and id Dumper's alt and key
        def keys(nodes):
            x = (f'<hierarchy><window type="2"><node resource-id="{uat.PKG}:id/keyboard_view" package="{uat.PKG}" '
                 f'bounds="[0,0][300,100]">{nodes}</node></window></hierarchy>')
            d = uat.Device("5556")
            d._keys = None
            d.need_keyboard = lambda: uat.Screen(x).find(rid="keyboard_view", where="ime")
            return d.keys()
        views = ('<node clickable="true" bounds="[0,0][100,50]"><node text="1" bounds="[60,0][100,20]"/>'
                 '<node text="q" bounds="[30,10][70,40]"/></node>'
                 '<node clickable="true" resource-id="x:id/button_space" content-desc="空格，拼音" bounds="[0,50][200,100]">'
                 '<node text="拼音" bounds="[80,60][120,90]"/></node>'
                 '<node clickable="true" resource-id="x:id/button_backspace" content-desc="退格" bounds="[200,50][300,100]"/>')
        canvas = ('<node class="android.widget.FrameLayout" bounds="[0,0][300,100]">'
                  '<node clickable="true" text="q" key="q" alt="1" bounds="[0,0][100,50]"/>'
                  '<node clickable="true" text="拼音" key="space" resource-id="x:id/button_space" content-desc="空格，拼音" bounds="[0,50][200,100]"/>'
                  '<node clickable="true" key="backspace" resource-id="x:id/button_backspace" content-desc="退格" bounds="[200,50][300,100]"/></node>')
        old, new = keys(views), keys(canvas)
        for k in ("q", "1", "space", "bksp"):
            self.assertEqual(old[k], new[k], k)
        # and by the scene's id
        self.assertEqual(new["bksp"], new["backspace"])
        # an id is an alias only where no label or corner took it, whichever node came first: a key
        # whose id is "1" drawn before q does not take q's corner's 1 (as on the views)
        first = '<node clickable="true" text="①" key="1" bounds="[0,0][10,10]"/>'
        clash = keys(canvas.replace('<node clickable="true" text="q"', first + '<node clickable="true" text="q"'))
        self.assertEqual(new["1"], clash["1"])
        self.assertEqual(new["q"], clash["q"])

    def test_the_input_methods_touchable_region_is_read_from_dumpsys_input(self):
        # InputDispatcher's window line as redroid 14 printed it; the strip's band and the keyboard's
        # joined by |, an empty one as <empty>, the list ended by a comma
        d = uat.Device("5556")
        for dump, want in (
                ("  3: name='Window{1 u0 InputMethod}', frame=[0,1203][1080,2340], touchableRegion="
                 "[0,1370][100,1436]|[0,1436][1080,2340], ownerPid=1, ownerUid=2\n",
                 [(0, 1370, 100, 1436), (0, 1436, 1080, 2340)]),
                ("  3: name='Window{1 u0 InputMethod}', touchableRegion=<empty>, ownerPid=1\n", []),
                # InputMethodManager's own lines never count, nor a window without the input method
                ("  InputMethodManager touchableRegion=[0,0][1,1]\n  1: name='Window{2 u0 com.app}', "
                 "touchableRegion=[0,0][1080,2340], ownerPid=3\n", [])):
            d.sh = lambda cmd, dump=dump: dump
            self.assertEqual(want, d.ime_touchable_region(), dump)

    def test_the_usb_keyboard_is_asked_of_the_device_once_then_known_from_plugging_it(self):
        d = uat.Device("5556")
        asked = []
        d.sh = lambda cmd: asked.append(cmd) or ""
        with mock.patch.object(uat.subprocess, "run", lambda *a, **k: SimpleNamespace(returncode=0, stdout="")):
            self.assertFalse(d.kb_plugged())
            self.assertFalse(d.kb_plugged())
            d.kb("up")
            self.assertTrue(d.kb_plugged())
            d.kb("type", "a")
            self.assertTrue(d.kb_plugged())
            d.kb("down")
            self.assertFalse(d.kb_plugged())
        self.assertEqual(1, len(asked), asked)


class TestbedStateTest(unittest.TestCase):
    @staticmethod
    def row(batch):
        return "Row: 0 json=" + json.dumps([{"name": "a", "text": "你好", "batch": batch}]) + "\n"

    def adb(self, outputs):
        """a stand-in adb answering the queries with outputs in turn: (stdout, stderr)"""
        calls = []

        def run(cmd, **kw):
            calls.append(cmd)
            out, err = outputs[min(len(calls), len(outputs)) - 1]
            return SimpleNamespace(returncode=0, stdout=out, stderr=err)
        return calls, mock.patch.object(uat.subprocess, "run", run)

    def test_read_again_while_a_field_is_mid_batch_edit(self):
        calls, patch = self.adb([(self.row(1), ""), (self.row(2), ""), (self.row(0), "")])
        with patch, mock.patch.object(uat.time, "sleep", lambda s: None):
            state = uat.Device("5556").testbed_state()
        self.assertEqual(3, len(calls))
        self.assertEqual("你好", state["a"]["text"])

    def test_blocked_when_the_batch_never_ends(self):
        calls, patch = self.adb([(self.row(1), "")])
        with patch, mock.patch.object(uat.time, "sleep", lambda s: None), self.assertRaises(uat.Blocked):
            uat.Device("5556").testbed_state()
        self.assertEqual(uat.TESTBED_READS, len(calls))

    def test_blocked_names_adbs_error(self):
        _, patch = self.adb([("", "Error: no provider org.fcitx.fcitx5.android.testbed.state")])
        with patch, self.assertRaises(uat.Blocked) as e:
            uat.Device("5556").testbed_state()
        self.assertIn("no provider", str(e.exception))


def case(cid, needs=None, heavy=False):
    return uat.Case(fn=None, id=cid, base="B0", page="text", mutates=False, clean=False, visual=False,
                    needs=needs, heavy=heavy)


class DevicesTest(unittest.TestCase):
    def test_ports_are_even_and_skip_taken_pairs(self):
        self.assertEqual(uat.free_ports(set(), 3), ["5554", "5556", "5558"])
        self.assertEqual(uat.free_ports({5558}, 3), ["5554", "5556", "5560"])
        self.assertEqual(uat.free_ports({5555}, 1), ["5556"])   # adb's half of 5554 taken
        self.assertEqual(len(uat.free_ports(set(), 99)), 16)    # 5554-5584

    def test_each_port_has_its_own_page_server(self):
        got = [uat.http_port(p) for p in uat.free_ports(set(), 16)]
        self.assertEqual(got, list(range(8800, 8816)))

    def test_count_from_memory(self):
        G = uat.GB
        self.assertEqual(uat.device_count(20 * G, 0, budget=3 * G), 6)      # (20-2)/3
        self.assertEqual(uat.device_count(20 * G, 1, budget=3 * G), 3)      # (20-2-7)/3
        self.assertEqual(uat.device_count(20 * G, 0, budget=3 * G, cap=3), 3)
        self.assertEqual(uat.device_count(4 * G, 2, budget=3 * G), 1)       # never under 1
        self.assertEqual(uat.device_count(99 * G, 0, budget=3 * G, ports=2), 2)


class HeavyTest(unittest.TestCase):
    def case(self, cid, heavy=False):
        return case(cid, heavy=heavy)

    def test_heavy_cases_run_last_and_alone(self):
        sch = uat.Scheduler([self.case("A-1"), self.case("H-1", True), self.case("H-2", True)], None, ["a", "b"])
        g, _ = sch.next_group("a")
        self.assertEqual([c.id for c in g], ["A-1"])
        sch.take(g)
        got = []
        # b asks while a still runs A-1: it must wait for a, then take a heavy case on its own
        t = threading.Thread(target=lambda: got.append(sch.next_group("b")[0]))
        t.start()
        time.sleep(0.2)
        self.assertEqual(got, [])
        h, _ = sch.next_group("a")   # a done with A-1: the first heavy case is a's, b still waits
        self.assertEqual([c.id for c in h], ["H-1"])
        time.sleep(0.2)
        self.assertEqual(got, [])
        sch.done("a")   # a stops (device_loop's finally): b may go
        t.join(5)
        self.assertFalse(t.is_alive(), "b still waiting after a stopped")
        self.assertEqual([c.id for c in got[0]], ["H-2"])

    def test_a_heavy_case_out_of_this_run_waits_for_no_one(self):
        # a release-only heavy case in an everyday run: with its group, not in the queue that waits for every device
        r = case("R-1", "release,scripted:x", heavy=True)
        def queued(sch):
            return [[c.id for c in g] for g in sch.heavy], sorted(c.id for g in sch.pending for c in g)
        sch = uat.Scheduler([self.case("A-1"), r, self.case("H-1", True)], None, ["a"])
        self.assertEqual(([["H-1"]], ["A-1", "R-1"]), queued(sch))
        was = uat.BUILD
        try:
            uat.BUILD = "release"
            self.assertEqual(([["R-1"]], ["A-1"]), queued(uat.Scheduler([self.case("A-1"), r], None, ["a"])))
        finally:
            uat.BUILD = was


class JvmTest(unittest.TestCase):
    """uat.py jvm: the @Uat ids in the test sources, and their JUnit results"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, rel, text):
        path = os.path.join(self.tmp.name, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        return path

    def test_ids_are_found_on_their_methods(self):
        self.write("src/a/KeysTest.kt", 'package org.x\n\nclass KeysTest {\n    @Test\n    @Uat("KB-22")\n    fun address() {}\n\n'
                   '    @Uat("TB-01", "KB-01")\n    @Config(qualifiers = "land")\n    fun bar() = Unit\n}\n')
        found = uat.uat_tests([os.path.join(self.tmp.name, "src")])
        self.assertEqual({"KB-22": [("org.x.KeysTest", "address")], "TB-01": [("org.x.KeysTest", "bar")],
                          "KB-01": [("org.x.KeysTest", "bar")]}, found)

    def test_a_backtick_name_with_a_quote_a_comma_or_a_paren_is_read_whole(self):
        # such names were read as no test at all, and their cases dropped without a word
        self.write("src/c/HostTest.kt", 'package org.x\n\nclass HostTest {\n'
                   "    @Uat(\"PY-01\")\n    fun `the nine keys' bar shows it`() {}\n\n"
                   '    @Uat("PY-02")\n    fun `as it is under traditional, a built-in one converted`() {}\n\n'
                   '    @Uat("PY-03")\n    fun `a b`() {}\n\n'
                   '    @Uat("PY-04")\n    fun `a key (held) types it`() {}\n}\n')
        found = uat.uat_tests([os.path.join(self.tmp.name, "src")])
        self.assertEqual({"PY-01": [("org.x.HostTest", "the nine keys' bar shows it")],
                          "PY-02": [("org.x.HostTest", "as it is under traditional, a built-in one converted")],
                          "PY-03": [("org.x.HostTest", "a b")],
                          "PY-04": [("org.x.HostTest", "a key (held) types it")]}, found)

    def test_a_nested_class_is_outer_dollar_inner_and_the_outer_resumes_after_it(self):
        self.write("src/b/PopupTest.kt", 'package org.x\n\nclass PopupTest {\n'
                   '    @Uat("ST-01")\n    fun before() {}\n\n'
                   '    class Dialog {\n        @Uat("ST-22")\n        fun inner() {}\n    }\n\n'
                   '    @Uat("ST-02")\n    fun after() {}\n}\n\n'
                   'class SecondTest {\n    @Uat("ST-03")\n    fun other() {}\n}\n')
        found = uat.uat_tests([os.path.join(self.tmp.name, "src")])
        self.assertEqual({"ST-01": [("org.x.PopupTest", "before")], "ST-22": [("org.x.PopupTest$Dialog", "inner")],
                          "ST-02": [("org.x.PopupTest", "after")], "ST-03": [("org.x.SecondTest", "other")]}, found)

    def test_needs_names_each_test_by_its_own_uat(self):
        found = {"WD-19": [("org.x.LexiconUatTest", "aPhrase")], "WD-35": [("org.x.LexiconUatTest", "clear")]}
        cases = {"WD-35": "jvm:LexiconUatTest.clear、LexiconUatTest.aPhrase、LexiconUatTest.gone、LexiconUatTest",
                 "WD-36": "release:LexiconUatTest.gone"}   # not jvm: not checked
        self.assertEqual(["WD-35: needs= names LexiconUatTest.aPhrase, whose @Uat is WD-19's",
                          "WD-35: needs= names LexiconUatTest.gone, which has no @Uat"],
                         uat.needs_problems(cases, found))

    def test_a_case_fails_with_any_of_its_tests_and_is_blocked_without_a_result(self):
        self.write("r/TEST-org.x.KeysTest.xml", '<testsuite name="org.x.KeysTest">'
                   '<testcase name="address" classname="org.x.KeysTest" time="0.5"/>'
                   '<testcase name="bar" classname="org.x.KeysTest" time="1.0"><failure message="no .com&#10;at">x</failure></testcase>'
                   '</testsuite>')
        results = uat.junit_results([os.path.join(self.tmp.name, "r")])
        rec = uat.jvm_record({"KB-22": [("org.x.KeysTest", "address")], "TB-01": [("org.x.KeysTest", "bar")],
                              "PY-01": [("org.x.Gone", "typed")]}, results)
        self.assertEqual("通过", rec["KB-22"][0])
        self.assertEqual(("失败", "KeysTest.bar 1.0 s：no .com"), rec["TB-01"])
        self.assertEqual("阻塞", rec["PY-01"][0])

    def test_a_jvm_case_without_an_uat_test_is_blocked_not_dropped(self):
        rec = uat.jvm_record({"KB-22": [("org.x.KeysTest", "address")]}, {}, expected=["KB-22", "HW-09"])
        self.assertEqual(("阻塞", "没有 @Uat 测试"), rec["HW-09"])
        self.assertEqual(["HW-09", "KB-22"], list(rec))

    def test_a_test_failing_at_one_sdk_fails_whichever_is_read_last(self):
        # the failing run (SDK 35's or 29's) read first, then last: the worse result counts either way
        for failing in ("text", "voice"):
            for flavor in ("text", "voice"):
                failure = f'<failure message="{failing} only">x</failure>' if flavor == failing else ""
                self.write(f"{failing}-first/{flavor}/TEST-org.x.KeysTest.xml", '<testsuite name="org.x.KeysTest">'
                           f'<testcase name="bar" classname="org.x.KeysTest" time="1.0">{failure}</testcase></testsuite>')
            for order in (("text", "voice"), ("voice", "text")):
                results = uat.junit_results([os.path.join(self.tmp.name, f"{failing}-first", f) for f in order])
                self.assertEqual(("失败", 1.0, f"{failing} only"), results[("org.x.KeysTest", "bar")], (failing, order))

    def test_the_packs_state_is_read_off_the_providers_bundle(self):
        d = mock.MagicMock()
        d.sh.return_value = "Result: Bundle[{result=imported:text:v1, voice=missing, text=v1}]\n"
        self.assertEqual({"text": "v1", "voice": "missing"}, uat.packs_state(d))
        d.sh.return_value = "Result: Bundle[{text=needs-update:v1, voice=v2}]\n"
        self.assertEqual({"text": "needs-update:v1", "voice": "v2"}, uat.packs_state(d))

    def test_the_packs_a_run_imports_are_the_newest_the_build_listed(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(uat, "PACKS", tmp):
            for pid, v, t in (("text", 1, 1), ("text", 2, 2), ("voice", 1, 1)):
                with open(os.path.join(tmp, f"youmo-{pid}-v{v}.json"), "w") as f:
                    json.dump({"pack": pid, "version": v, "file": f"youmo-{pid}-v{v}.youmopack"}, f)
                os.utime(os.path.join(tmp, f"youmo-{pid}-v{v}.json"), (t, t))
            files = uat.pack_files()
            self.assertEqual(2, files["text"][1])
            self.assertTrue(files["text"][0].endswith("youmo-text-v2.youmopack"))
            self.assertEqual(["text"], list(uat.pack_files(voice=False)))
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(uat, "PACKS", tmp), self.assertRaises(SystemExit):
            uat.pack_files()

    def test_an_empty_failure_message_is_no_message(self):
        self.write("e/TEST-org.x.A.xml", '<testsuite><testcase name="a" classname="org.x.A"><failure message=""/></testcase></testsuite>')
        self.assertEqual(("失败", 0.0, ""), uat.junit_results([os.path.join(self.tmp.name, "e")])[("org.x.A", "a")])

    def test_counts_place_each_case_once_and_flag_disagreements(self):
        cases = {"KB-01": None, "KB-02": "jvm:KeyboardUatTest", "KB-03": "jvm:KeyboardUatTest", "HK-01": None,
                 "VO-01": "voice:x", "PR-13": "release:x", "MN-13": "manual:x", "TB-36": "skip:x", "XX-01": None}
        priority = {c: "P0" for c in cases if c != "XX-01"} | {"KB-01": "P1"}
        n, problems = uat.tally(cases, priority, {"KB-01", "KB-02"})
        self.assertEqual((9, 4, 2, 1, 1, 1, 1), (n["total"], n["device"], n["jvm"], n["voice"],
                                                  n["release"], n["manual"], n["smoke"]))
        self.assertEqual({"P0": 7, "P1": 1}, n["prios"])
        self.assertEqual(["KB-03: needs=jvm but no **JVM** line in its .md", "XX-01: has a script but no case in the .md"],
                         problems)
        self.assertIn("模拟器上跑 4 条（其中 1 条", uat.counts_line(n))

    def test_a_case_in_the_md_without_a_script_is_a_problem(self):
        _, problems = uat.tally({"HK-36": uat.NO_SCRIPT}, {"HK-36": "P1"}, set())
        self.assertEqual(["HK-36: in the .md but no script (register it, needs= where it runs)"], problems)

    def test_devices_are_emulator_console_ports_each_with_its_own_page_server(self):
        self.assertEqual(["5554", "5556", "5584"], [uat.emu_port(p) for p in ("5554", "5556", "5584")])
        for bad in ("5555", "5557", "5552", "5586", "127.0.0.1:5556", ""):
            with self.assertRaises(SystemExit, msg=bad):
                uat.emu_port(bad)
        # 8800-8815: past 8780, a port forwarded to this Mac
        self.assertEqual([8800, 8801, 8815], [uat.http_port(p) for p in ("5554", "5556", "5584")])

    def test_a_jvm_case_runs_elsewhere_on_a_device(self):
        c = case("KB-22", "jvm:KeyboardUatTest")
        state, why = c.unmet(SimpleNamespace(voice=False))
        self.assertIs(uat.Elsewhere, state)
        self.assertTrue(why.startswith("JVM"))

    def test_a_scripted_release_case_runs_only_in_a_release_run(self):
        # the big-lexicon cases (WD-14d, 14h, TBL-12c; user 2026-10-30): out of the everyday run, in the release's
        c = case("WD-14d", "release,scripted:x")
        device = SimpleNamespace(voice=False)
        self.assertEqual("release", uat.place(c.needs))
        self.assertIs(uat.Elsewhere, c.unmet(device)[0])
        was = uat.BUILD
        try:
            uat.BUILD = "release"
            self.assertIsNone(c.unmet(device))
            # one run by hand stays by hand
            self.assertIs(uat.Elsewhere, case("WD-14r", "release:x").unmet(device)[0])
            # and the reasons say which is by hand
            self.assertTrue(case("WD-14r", "release:x").unmet(device)[1].startswith("发版时手动"))
        finally:
            uat.BUILD = was

    def test_a_voice_case_without_the_voice_pack_is_reported_as_voice_where_place_puts_it(self):
        # the voice reason, as place() files it under voice (F4263); a run with the voice pack runs it
        c = case("VO-01", "voice:x")
        state, why = c.unmet(SimpleNamespace(voice=False))
        self.assertEqual((uat.Elsewhere, "voice"), (state, uat.place(c.needs)))
        self.assertTrue(why.startswith(uat.ELSEWHERE["voice"]), why)
        self.assertIsNone(c.unmet(SimpleNamespace(voice=True)))

    def test_sdk_versions_sort_as_numbers_a_pre_release_before_its_release(self):
        self.assertEqual("36.1.0", max(["9.0.0", "36.1.0", "36.0.0"], key=uat.version_key))
        self.assertEqual("android-36", max(["android-9", "android-36"], key=uat.version_key))
        self.assertEqual("36.0.0", max(["36.0.0-rc1", "36.0.0"], key=uat.version_key))

    def test_tap_text_by_default_sleeps_its_pause_and_owes_the_old_ones_rest(self):
        d = uat.Device.__new__(uat.Device)
        d._quiet_at, slept = 0, []
        d.sh = lambda cmd: ""
        node = SimpleNamespace(c=(1, 2))
        d.screen = lambda: SimpleNamespace(texts=lambda: [])
        d.visible = lambda *a: node
        real = time.sleep
        time.sleep = slept.append
        try:
            t0 = time.time()
            d.tap_text("x")
        finally:
            time.sleep = real
        self.assertEqual([uat.PAUSE["tap_text"]], slept)
        self.assertGreaterEqual(d._quiet_at, t0 + uat.OLD["tap_text"] - uat.PAUSE["tap_text"])


# adb for UpTest: answers emu avd name from $FAKE_DIR/avd (an error when empty, a hang with
# $FAKE_DIR/hang), sys.boot_completed 1 after $FAKE_DIR/boot_after asks, whoami from $FAKE_DIR/whoami
# (root without it), the SDK from $FAKE_DIR/sdk (36 without it), nothing to the rest; logs each call
FAKE_ADB = r"""#!/bin/sh
D=$FAKE_DIR
[ "$1" = -s ] && shift 2
echo "$*" >> "$D/log"
case "$*" in
  "emu avd name")
    [ -f "$D/hang" ] && exec sleep 30
    A=$(cat "$D/avd" 2>/dev/null)
    if [ -n "$A" ]; then printf '%s\r\nOK\r\n' "$A"; else echo "error: no emulator detected" >&2; exit 1; fi ;;
  "shell whoami")
    cat "$D/whoami" 2>/dev/null || echo root ;;
  "shell getprop ro.build.version.sdk")
    cat "$D/sdk" 2>/dev/null || echo 36 ;;
  "shell getprop sys.boot_completed")
    n=$(($(cat "$D/boots" 2>/dev/null || echo 0) + 1)); echo $n > "$D/boots"
    if [ $n -gt "$(cat "$D/boot_after" 2>/dev/null || echo 0)" ]; then echo 1; else echo; fi ;;
esac
"""


class UpTest(unittest.TestCase):
    """up() on a port another project's emulator holds: it exits before touching the device"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name
        adb = os.path.join(self.dir, "adb")
        with open(adb, "w") as f:
            f.write(FAKE_ADB)
        os.chmod(adb, 0o755)
        self.popen = mock.MagicMock()   # the emulator's start; adb's (subprocess.run's) go through
        real = uat.subprocess.Popen
        popen = lambda args, *a, **k: self.popen(args, *a, **k) if args[0] == "EMULATOR" else real(args, *a, **k)  # noqa: E731
        for p in (mock.patch.dict(os.environ, {"PATH": self.dir + os.pathsep + os.environ["PATH"],
                                               "FAKE_DIR": self.dir}),
                  mock.patch.object(uat, "OUT", self.dir), mock.patch.object(uat, "AVD_NAME_TIMEOUT", 1),
                  mock.patch.object(uat.time, "sleep", lambda s: None),
                  mock.patch.object(uat, "emulator_bin", lambda: "EMULATOR"),
                  mock.patch.object(uat.subprocess, "Popen", popen)):
            p.start()
            self.addCleanup(p.stop)
        self.addCleanup(self.tmp.cleanup)

    def put(self, name, text=""):
        with open(os.path.join(self.dir, name), "w") as f:
            f.write(text)

    def calls(self):
        with open(os.path.join(self.dir, "log")) as f:
            return f.read().splitlines()

    def up(self, port="5556"):
        with self.assertRaises(SystemExit) as e, contextlib.redirect_stdout(io.StringIO()):
            uat.up(port)
        return str(e.exception.code)

    def test_another_avd_still_booting_is_not_started_over_nor_waited_for(self):
        self.put("avd", "dsapp")
        self.put("boot_after", "1000")
        self.assertIn("AVD dsapp", self.up())
        self.popen.assert_not_called()
        self.assertEqual(["shell getprop sys.boot_completed", "emu avd name"], self.calls())

    def test_an_emulator_that_does_not_answer_its_console_is_left(self):
        self.put("hang")
        self.put("boot_after", "1000")
        self.assertIn("not answering", self.up())
        self.popen.assert_not_called()
        self.assertEqual(["shell getprop sys.boot_completed", "emu avd name"], self.calls())
        with self.assertRaises(SystemExit):   # ours alone: an exit, not TimeoutExpired
            uat.ours(uat.Device("5556"))

    def test_another_avd_that_took_the_port_after_the_look_is_refused_before_root(self):
        self.put("boot_after", "2")
        self.popen.side_effect = lambda *a, **k: self.put("avd", "dsapp")
        self.assertIn("AVD dsapp", self.up("05556"))
        args = self.popen.call_args[0][0]
        self.assertEqual("5556", args[args.index("-port") + 1])   # the normalised port, in the log name too
        self.assertTrue(os.path.exists(os.path.join(self.dir, "emu", "emulator-5556.log")))
        self.assertEqual("emu avd name", self.calls()[-1])
        self.assertFalse([c for c in self.calls() if c == "root" or "setprop" in c], self.calls())

    def test_this_avd_still_booting_is_waited_for_not_started_again(self):
        self.put("avd", "youmo")
        self.put("boot_after", "3")
        self.assertIn("webview_shell", self.up())   # the fake has no packages: past the checks
        self.popen.assert_not_called()
        self.assertIn("shell whoami", self.calls())

    def test_below_api_34_no_test_page_browser_is_said_not_an_exit(self):
        self.put("avd", "youmo29")
        self.put("sdk", "29")
        out = io.StringIO()
        with mock.patch.object(uat, "AVD", "youmo29"), contextlib.redirect_stdout(out):
            uat.up("5556")   # the shell's minSdk is 34: youmo29 comes up without it
        self.assertIn("below API 34", out.getvalue())
        self.assertIn("emulator-5556 up", out.getvalue())

    def test_adb_root_that_leaves_the_shell_not_root_stops_before_the_cases_run_as_shell(self):
        self.put("avd", "youmo")
        self.put("whoami", "shell")
        self.assertIn("did not make the shell root", self.up())
        self.assertIn("root", self.calls())
        self.assertFalse([c for c in self.calls() if "setprop" in c or "pm path" in c], self.calls())

    def test_an_emulator_that_exits_before_booting_fails_at_once_with_its_log(self):
        self.put("boot_after", "1000")
        self.popen.return_value = mock.MagicMock(**{"poll.return_value": 1, "returncode": 1})
        self.assertIn("exited (1)", self.up())
        self.assertLess(self.calls().count("shell getprop sys.boot_completed"), 5)

    def test_an_emulator_this_started_that_never_boots_is_killed(self):
        self.put("boot_after", "1000")
        emu = mock.MagicMock(**{"poll.return_value": None})
        self.popen.return_value = emu
        self.assertIn("did not boot", self.up())
        emu.kill.assert_called_once()

    def test_down_leaves_another_avds_emulator_and_kills_its_own(self):
        os.makedirs(os.path.join(self.dir, "tools"))
        self.put("tools/emu-keyboard.sh", "#!/bin/sh\n")
        os.chmod(os.path.join(self.dir, "tools", "emu-keyboard.sh"), 0o755)
        with mock.patch.object(uat, "UAT", self.dir), contextlib.redirect_stdout(io.StringIO()):
            self.put("avd", "dsapp")
            with self.assertRaises(SystemExit) as e:
                uat.down("5556")
            self.assertIn("AVD dsapp", str(e.exception.code))
            self.assertNotIn("emu kill", self.calls())
            self.put("avd", "youmo")
            uat.down("5556")
            self.assertIn("emu kill", self.calls())

    def test_run_refuses_another_avds_emulator_before_installing(self):
        self.put("avd", "dsapp")
        a = SimpleNamespace(areas=None, ids="ST-48", devices="5556", no_voice=False)
        with mock.patch.object(uat, "install", mock.MagicMock()) as install, \
                self.assertRaises(SystemExit) as e:
            uat.cmd_run(a)
        self.assertIn("AVD dsapp", str(e.exception.code))
        install.assert_not_called()
        self.assertFalse([c for c in self.calls() if c in ("root", "shell whoami") or c.startswith("reverse")])


class ResultsFileTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.dir, "runs"))
        for p in (mock.patch.object(uat, "OUT", self.dir), mock.patch.object(uat, "UAT", self.dir)):
            p.start()
            self.addCleanup(p.stop)

    def test_the_device_line_closes_its_bracket_then_goes_on(self):
        # dropping a clause left "{ABI}，）1080×2340": a comma before the bracket, none after it
        p = uat.write_results(uat.Run("r", []), {}, "v", ["5554"], 1, [])
        with open(p, encoding="utf-8") as f:
            dev = f.read().splitlines()[2]
        self.assertIn(f"google_apis {uat.ABI}），1080×2340，", dev)
        self.assertNotIn("，）", dev)

    @unittest.skipUnless(importlib.util.find_spec("PIL"), "needs Pillow")
    def test_sheets_are_made_with_neither_font_on_the_host(self):
        # a host without the fonts crashed here and the whole run's results were lost
        with mock.patch.object(uat, "FONT", None), mock.patch.object(uat, "FONT_LATIN", None):
            self.assertEqual([], uat.build_sheets(uat.Run("r", []), {}))


class ClipMicTest(unittest.TestCase):
    def device(self, states, idle="on=true talking=false playing=false queued="):
        d = uat.Device.__new__(uat.Device)
        d.calls = []

        def sh(cmd):
            d.calls.append(cmd)
            method = re.search(r"--method (\w+)", cmd).group(1)
            st = states.pop(0) if method == "state" and states else idle
            return "Result: Bundle[{state=%s}]\n" % st
        d.sh = sh
        return d

    def test_clip_call_reads_the_state_line(self):
        d = self.device(["on=true talking=true playing=true queued=a.wav,800ms"])
        self.assertEqual("on=true talking=true playing=true queued=a.wav,800ms", d.clip_call("state"))
        self.assertIn("content call --uri content://%s.voiceclip --method state" % uat.PKG, d.calls[0])

    def test_clip_call_with_no_state_is_blocked(self):
        d = uat.Device.__new__(uat.Device)
        d.sh = lambda cmd: "Error while accessing provider"
        with self.assertRaises(uat.Blocked):
            d.clip_call("state")

    def test_done_once_nothing_is_queued_and_nothing_plays_or_no_talk_is_on(self):
        self.assertTrue(uat.clips_done("on=true talking=true playing=false queued="))
        self.assertTrue(uat.clips_done("on=true talking=false playing=true queued="))
        self.assertFalse(uat.clips_done("on=true talking=true playing=true queued="))
        self.assertFalse(uat.clips_done("on=true talking=false playing=false queued=b.wav"))

    def test_poll_waits_for_the_clips_time_then_the_app(self):
        d = self.device(["on=true talking=true playing=true queued=", "on=true talking=true playing=false queued="])
        p = uat.ClipPlay(d, 0)
        p.end = 0
        self.assertIsNone(p.poll())
        self.assertEqual(0, p.poll())
        p.end = time.time() + 60
        self.assertIsNone(p.poll())   # its time not passed: the app not even asked
        self.assertEqual(2, len(d.calls))

    def test_a_wait_that_times_out_kills_it(self):
        d = self.device([], idle="on=true talking=true playing=true queued=")
        p = uat.ClipPlay(d, 0)
        p.end = 0
        with mock.patch.object(uat.time, "sleep", lambda s: None), \
                self.assertRaises(uat.subprocess.TimeoutExpired):
            p.wait(0.01)
        self.assertEqual(["clear", "on"], [re.search(r"--method (\w+)", c).group(1) for c in d.calls[-2:]])

    def test_clip_mic_clears_before_it_turns_on(self):
        d = self.device([])
        d._clips_in = {"a"}
        d.clip_mic()
        self.assertEqual(["state", "clear", "on"], [re.search(r"--method (\w+)", c).group(1) for c in d.calls])
        self.assertEqual({"a"}, d._clips_in)


class PlugTest(unittest.TestCase):
    def device(self, plugged, nav_on):
        d = mock.Mock()
        d.kb_plugged.return_value = plugged
        d.sh.side_effect = lambda c: (f"[x] com.android.internal.systemui.navbar.{nav_on}\n"
                                      if c.startswith("cmd overlay list") else "")
        return d

    def enabled(self, d):
        return [a.args[0] for a in d.sh.call_args_list if "enable-exclusive" in a.args[0]]

    def test_a_keyboard_already_plugged_still_gets_gesture_nav(self):
        d = self.device(True, "threebutton")
        with mock.patch.object(uat.time, "sleep"):
            uat.plug(d, "B0-实体")
        d.kb.assert_not_called()
        self.assertEqual(1, len(self.enabled(d)))
        self.assertIn("gestural", self.enabled(d)[0])

    def test_the_nav_is_set_even_when_the_keyboard_fails(self):
        d = self.device(True, "gestural")
        d.kb.side_effect = uat.Blocked("no")
        with mock.patch.object(uat.time, "sleep"), self.assertRaises(uat.Blocked):
            uat.plug(d, "B0")
        self.assertIn("threebutton", self.enabled(d)[0])

    def test_nothing_to_change_changes_nothing(self):
        d = self.device(False, "threebutton")
        with mock.patch.object(uat.time, "sleep") as sleep:
            uat.plug(d, "B0")
        self.assertEqual([], self.enabled(d))
        sleep.assert_not_called()


class CanvasOnTest(unittest.TestCase):
    def test_the_debug_build_writes_as_the_app(self):
        cmd = uat.canvas_on_cmd("debug")
        self.assertIn(f"run-as {uat.PKG} sh -c '", cmd)
        self.assertIn('canvas_keyboard', cmd)
        self.assertNotIn("chown", cmd)
        self.assertTrue(cmd.startswith(f"am force-stop {uat.PKG};"))

    def prefs_after(self, before):
        with tempfile.TemporaryDirectory() as d:
            f = "shared_prefs/p.xml"
            if before is not None:
                os.makedirs(os.path.join(d, "shared_prefs"))
                with open(os.path.join(d, f), "w") as out:
                    out.write(before)
            subprocess.run(["sh", "-c", uat.canvas_prefs_script(f)], cwd=d, check=True)
            with open(os.path.join(d, f)) as got:
                return got.read()

    def test_the_canvas_turned_on_keeps_what_the_packs_import_wrote(self):
        packs = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                 '    <string name="packs_seen">text</string>\n'
                 '    <boolean name="canvas_keyboard" value="false" />\n</map>\n')
        after = self.prefs_after(packs)
        self.assertIn('<string name="packs_seen">text</string>', after)
        self.assertEqual(1, after.count("canvas_keyboard"))
        self.assertIn(uat.CANVAS_SET, after)
        for before in (None, "", "<map />"):
            after = self.prefs_after(before)
            self.assertIn(uat.CANVAS_SET, after)
            self.assertTrue(after.startswith("<map>") and after.rstrip().endswith("</map>"), after)

    def test_the_canvas_goes_on_after_the_clear_and_the_packs_before_the_input_method(self):
        calls = []
        d = mock.Mock()
        with mock.patch.object(uat, "CANVAS", True), \
                mock.patch.object(uat, "clear_keeping_packs", lambda d: calls.append("clear")), \
                mock.patch.object(uat, "canvas_on", lambda d: calls.append("canvas_on")), \
                mock.patch.object(uat, "ensure_ime", lambda d: calls.append("ime")), \
                mock.patch.object(uat, "canvas_check", lambda d: calls.append("check")), \
                mock.patch.object(uat, "plug", lambda d, b: (_ for _ in ()).throw(uat.Blocked("stop"))):
            with self.assertRaises(uat.Blocked):
                uat.reset(d, "B0", "text")
        self.assertEqual(["clear", "canvas_on", "ime", "check"], calls)

    def test_the_release_build_writes_as_root_with_the_apps_owner_and_context(self):
        cmd = uat.canvas_on_cmd("release")
        self.assertNotIn("run-as", cmd)
        for part in ("chown -R $u:$u", "chmod 660", "chcon -R"):
            self.assertIn(part, cmd)

    def test_the_canvas_off_after_the_app_started_blocks_the_case(self):
        d = mock.Mock()
        d.sh.return_value = "<map><boolean name=\"canvas_keyboard\" value=\"false\" /></map>"
        with self.assertRaises(uat.Blocked):
            uat.canvas_check(d)
        d.sh.return_value = "<map><boolean name=\"canvas_keyboard\" value=\"true\" /></map>"
        uat.canvas_check(d)


if __name__ == "__main__":
    unittest.main()
