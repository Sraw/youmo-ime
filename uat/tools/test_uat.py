"""uat.py's record keeping, without a device: python3 -m unittest test_uat (in uat/tools)"""
import contextlib
import io
import json
import os
import tempfile
import threading
import time
import unittest
from types import SimpleNamespace

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


def case(cid, needs=None, heavy=False):
    return uat.Case(fn=None, id=cid, base="B0", page="text", mutates=False, clean=False, visual=False,
                    needs=needs, heavy=heavy)


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

    def test_a_test_failing_in_one_flavor_fails_whichever_is_read_last(self):
        # the failing flavor read first, then last: the worse result counts either way
        for failing in ("text", "voice"):
            for flavor in ("text", "voice"):
                failure = f'<failure message="{failing} only">x</failure>' if flavor == failing else ""
                self.write(f"{failing}-first/{flavor}/TEST-org.x.KeysTest.xml", '<testsuite name="org.x.KeysTest">'
                           f'<testcase name="bar" classname="org.x.KeysTest" time="1.0">{failure}</testcase></testsuite>')
            for order in (("text", "voice"), ("voice", "text")):
                results = uat.junit_results([os.path.join(self.tmp.name, f"{failing}-first", f) for f in order])
                self.assertEqual(("失败", 1.0, f"{failing} only"), results[("org.x.KeysTest", "bar")], (failing, order))

    def test_an_empty_failure_message_is_no_message(self):
        self.write("e/TEST-org.x.A.xml", '<testsuite><testcase name="a" classname="org.x.A"><failure message=""/></testcase></testsuite>')
        self.assertEqual(("失败", 0.0, ""), uat.junit_results([os.path.join(self.tmp.name, "e")])[("org.x.A", "a")])

    def test_counts_place_each_case_once_and_flag_disagreements(self):
        cases = {"KB-01": None, "KB-02": "jvm:KeyboardUatTest", "KB-03": "jvm:KeyboardUatTest", "HK-01": "emu:x",
                 "VO-01": "emu,voice:x", "PR-13": "release:x", "MN-13": "manual:x", "TB-36": "skip:x", "XX-01": None}
        priority = {c: "P0" for c in cases if c != "XX-01"} | {"KB-01": "P1"}
        n, problems = uat.tally(cases, priority, {"KB-01", "KB-02"})
        self.assertEqual((9, 3, 2, 1, 1, 1, 1, 1), (n["total"], n["redroid"], n["jvm"], n["emu"], n["voice"],
                                                     n["release"], n["manual"], n["smoke"]))
        self.assertEqual({"P0": 7, "P1": 1}, n["prios"])
        self.assertEqual(["KB-03: needs=jvm but no **JVM** line in its .md", "XX-01: has a script but no case in the .md"],
                         problems)
        self.assertIn("redroid 上跑 3 条（其中 1 条", uat.counts_line(n))

    def test_a_case_in_the_md_without_a_script_is_a_problem(self):
        _, problems = uat.tally({"HK-36": uat.NO_SCRIPT}, {"HK-36": "P1"}, set())
        self.assertEqual(["HK-36: in the .md but no script (register it, needs= where it runs)"], problems)

    def test_two_redroids_split_the_host_cores_unless_told_otherwise(self):
        self.assertEqual(["0-5", "6-11"], uat.cpusets(2, 12, ""))
        self.assertEqual(["0-3", "4-8"], uat.cpusets(2, 9, ""))   # the odd core to the last
        self.assertEqual(["0-11"], uat.cpusets(1, 12, ""))   # one device: a split before undone
        with self.assertRaises(SystemExit):
            uat.cpusets(2, 1, "")
        self.assertIsNone(uat.cpusets(2, 12, "off"))
        self.assertEqual(["0-3", "4-11"], uat.cpusets(2, 12, "0-3/4-11"))
        with self.assertRaises(SystemExit):
            uat.cpusets(2, 12, "0-3")

    def test_a_jvm_case_runs_elsewhere_on_a_device(self):
        c = case("KB-22", "jvm:KeyboardUatTest")
        state, why = c.unmet(SimpleNamespace(emu=False, flavor="text"))
        self.assertIs(uat.Elsewhere, state)
        self.assertTrue(why.startswith("JVM"))

    def test_a_voice_case_on_the_emulator_is_reported_as_voice_where_place_puts_it(self):
        # emu,voice on redroid's text build: the voice reason, as place() files it under voice (F4263)
        c = case("VO-01", "emu,voice:x")
        state, why = c.unmet(SimpleNamespace(emu=False, flavor="text"))
        self.assertEqual((uat.Elsewhere, "voice"), (state, uat.place(c.needs)))
        self.assertTrue(why.startswith(uat.ELSEWHERE["voice"]), why)

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


if __name__ == "__main__":
    unittest.main()
