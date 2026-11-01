"""The gate scripts' device-free parts: python3 -m unittest test_gates (in uat/gates)."""
import json
import os
import tempfile
import unittest
import zipfile

os.environ.setdefault("UAT_BUILD", "debug")
import gates  # noqa: E402
import phase  # noqa: E402

FIXTURE = os.path.join(gates.FIXTURES, "v0.3.2", "backup.zip")


class RetargetTest(unittest.TestCase):
    def test_a_release_backup_becomes_the_debug_builds_and_back(self):
        with tempfile.TemporaryDirectory() as tmp:
            debug = gates.retarget(FIXTURE, os.path.join(tmp, "d.zip"), "io.github.sraw.youmo.debug")
            with zipfile.ZipFile(debug) as z:
                self.assertEqual("io.github.sraw.youmo.debug", json.loads(z.read("metadata.json"))["packageName"])
                self.assertIn("shared_prefs/io.github.sraw.youmo.debug_preferences.xml", z.namelist())
                self.assertNotIn("shared_prefs/io.github.sraw.youmo_preferences.xml", z.namelist())
            back = gates.retarget(debug, os.path.join(tmp, "r.zip"), "io.github.sraw.youmo")
            with zipfile.ZipFile(FIXTURE) as a, zipfile.ZipFile(back) as b:
                self.assertEqual(sorted(a.namelist()), sorted(b.namelist()))
                for n in a.namelist():
                    if n != "metadata.json":
                        self.assertEqual(a.read(n), b.read(n), n)


class IniValuesTest(unittest.TestCase):
    def test_reads_as_fcitx_does(self):
        text = "# ModelLevel=High\nModelLevel=Medium\n# [Fuzzy]\n[EnabledIM]\n0=engine-pinyin\n[A/B]\nk = v\n"
        self.assertEqual({"ModelLevel": "Medium", "EnabledIM/0": "engine-pinyin", "A/B/k ": " v"},
                         phase.ini_values(text))


class FileChecksTest(unittest.TestCase):
    """phase.file_checks on a device that holds the v0.3.2 fixture's files as restore-files leaves them"""

    class FakeDevice:
        """the files restore-files left, the first start's move into host-config/host-data made"""

        def __init__(self, tars, de_left=(), ext_left=(), moved=True):
            """moved False: a v0.3.x build installed, nothing moved (gate 3's new → v0.3.x)"""
            self.tars, self.de_left, self.ext_left, self.moved = tars, de_left, ext_left, moved

        def root(self, cmd):
            if cmd.startswith("ls -d "):   # what of the old copies is left on the external storage
                return " ".join(self.ext_left)
            if cmd.startswith("ls "):   # what of the old native data is left in user_de
                return " ".join(self.de_left)
            path = cmd.split()[1]
            # external.tar has config/ and data/: the move put them here
            if self.moved:
                path = path.replace(phase.HOST_CONFIG, phase.EXTERNAL + "/config").replace(
                    phase.HOST_DATA, phase.EXTERNAL + "/data")
            for prefix, t in self.tars.items():
                if path.startswith(prefix + "/"):
                    rel = path[len(prefix) + 1:]
                    # the preferences file is named after the package (restore_files renames it)
                    rel = rel.replace(f"{phase.PKG}_preferences", "io.github.sraw.youmo_preferences")
                    try:
                        return t.extractfile("./" + rel).read().decode()
                    except KeyError:
                        return ""
            return ""

    def test_the_fixtures_files_pass_every_item(self):
        import tarfile
        v = os.path.join(gates.FIXTURES, "v0.3.2")
        with tarfile.open(os.path.join(v, "data.tar")) as data, tarfile.open(os.path.join(v, "external.tar")) as ext:
            res = phase.file_checks(self.FakeDevice({phase.DATA: data, phase.EXTERNAL: ext}))
        self.assertEqual(7, len(res))
        self.assertEqual([], [r for r in res if not r["ok"]])

    def test_an_old_build_s_files_are_read_where_it_keeps_them(self):
        import tarfile
        v = os.path.join(gates.FIXTURES, "v0.3.2")
        rows = [
            # a v0.3.x build (gate 3's new → v0.3.x) keeps config/ and data/ on the external storage (F4975)
            (True, []),
            # read where this build keeps them, the old build's files are not found: every host item fails
            (False, ["性能档位", "输入法", "繁体", "中文标点", "自定义短语"]),
        ]
        for old, failed in rows:
            with self.subTest(old=old), tarfile.open(os.path.join(v, "data.tar")) as data, \
                    tarfile.open(os.path.join(v, "external.tar")) as ext:
                res = phase.file_checks(self.FakeDevice({phase.DATA: data, phase.EXTERNAL: ext}, moved=False), old=old)
                self.assertEqual(failed, [r["item"].split()[0] for r in res if not r["ok"]])

    def test_user_de_is_checked_only_after_a_fixture_with_a_de_tar(self):
        de = "旧原生数据已删 (user_de descriptor.json, usr)"
        left = (f"{phase.DE}/descriptor.json",)
        with tempfile.TemporaryDirectory() as tmp:
            for v, tars in (("with", ("data", "external", "de")), ("without", ("data", "external"))):
                os.makedirs(os.path.join(tmp, v))
                for n in tars:
                    open(os.path.join(tmp, v, f"{n}.tar"), "w").close()
            saved, phase.FIXTURES = phase.FIXTURES, tmp
            try:
                self.assertEqual(["data", "external", "de"], phase.names("with"))
                self.assertEqual(["data", "external"], phase.names("without"))
                rows = [
                    # gate 2, data synced by the old build gone: passes
                    ("with", (), [True]),
                    # left behind after the first start: fails, naming what is left
                    ("with", left, [False]),
                    # no de.tar: nothing put there, so listed as not checked (a pass would be unexercised)
                    ("without", left, [None]),
                    # gate 3 (no version): a 0.3.x build installed keeps its own synced data there
                    (None, left, []),
                ]
                for version, de_left, want in rows:
                    with self.subTest(version=version, de_left=de_left):
                        res = phase.file_checks(self.FakeDevice({}, de_left), version)
                        self.assertEqual(want, [r["ok"] for r in res if r["item"] == de])
                ext = "外部存储的旧副本已删 (Android/data config、data、theme)"
                rows = [
                    # gate 2, the move read back and the old copies deleted: passes
                    ("with", (), [True]),
                    # an old copy left after the first start: fails, naming it
                    ("with", (f"{phase.EXTERNAL}/config",), [False]),
                    # gate 3 (no version): not checked, the restore is what is under test
                    (None, (f"{phase.EXTERNAL}/config",), []),
                ]
                for version, ext_left, want in rows:
                    with self.subTest(version=version, ext_left=ext_left):
                        res = phase.file_checks(self.FakeDevice({}, ext_left=ext_left), version)
                        self.assertEqual(want, [r["ok"] for r in res if r["item"] == ext])
            finally:
                phase.FIXTURES = saved

    def test_a_missing_file_fails_its_items(self):
        res = phase.file_checks(self.FakeDevice({}))
        self.assertEqual([], [r["item"] for r in res if r["ok"]])

    def test_the_input_methods_out_of_order_fail(self):
        import io
        import tarfile
        profile = b"[Groups/0/Items/0]\nName=keyboard-us\n[Groups/0/Items/1]\nName=engine-pinyin\n"
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w") as t:
            info = tarfile.TarInfo("./config/profile")
            info.size = len(profile)
            t.addfile(info, io.BytesIO(profile))
        buf.seek(0)
        with tarfile.open(fileobj=buf) as t:
            res = {r["item"]: r for r in phase.file_checks(self.FakeDevice({phase.EXTERNAL: t}))}
        self.assertEqual((False, ["keyboard-us", "engine-pinyin"]), (res["输入法 拼音、英文、双拼 (profile)"]["ok"],
                                                                    res["输入法 拼音、英文、双拼 (profile)"]["got"]))


class TypedChecksTest(unittest.TestCase):
    """phase.typed_checks' learned-word item on a keyboard that offers [cands] for jingmiaoyu"""

    class FakeKeyboard:
        def __init__(self, cands):
            self.cands_, self.typed = cands, ""

        def open_page(self, page):
            self.typed = ""

        def im_label(self):
            return "拼音"

        def type(self, keys):
            self.typed = keys

        def cands(self):
            return self.cands_ if self.typed == "jingmiaoyu" else []

        def space(self):
            pass

        def field_text(self):
            return ""

    def learned(self, version, cands):
        saved = phase.home, phase.time.sleep

        def blocked(*args):
            raise phase.Blocked("no settings here")
        phase.home, phase.time.sleep = blocked, lambda s: None
        try:
            res = phase.typed_checks(self.FakeKeyboard(cands), version)
        finally:
            phase.home, phase.time.sleep = saved
        return [r["ok"] for r in res if r["item"].startswith("学到的词")]

    def test_the_learned_word_as_each_fixture_should_have_it(self):
        rows = [
            # a version whose learning this build reads: the learned word first
            ("v0.3.2", ["鲸喵屿", "鲸"], [True]),
            ("v0.3.2", ["鲸", "鲸喵屿"], [False]),
            # v0.3.0's learning is not read: another word first passes
            ("v0.3.0", ["鲸", "鲸喵屿"], [True]),
            ("v0.3.0", ["鲸喵屿"], [False]),
            # no candidates at all is typing broken, not the learning left unread
            ("v0.3.0", [], [False]),
        ]
        for version, cands, want in rows:
            with self.subTest(version=version, cands=cands):
                self.assertEqual(want, self.learned(version, cands))


class NotCheckedTest(unittest.TestCase):
    """ok None: an item the fixture could not exercise; neither a failure nor a silent pass (F4312)"""
    ITEMS = [{"item": "a", "ok": True, "got": "x"}, {"item": "b", "ok": None, "got": "no de.tar"}]

    def gate(self, tmp, items):
        g = object.__new__(gates.Gate)
        g.dir, g.rows, g.name = tmp, [], "t"
        g.new, g.new_pkg, g.new_ver = "new.apk", "pkg", "1"

        def phase_(pkg, *args):
            with open(args[1], "w") as f:
                json.dump(items, f)
            return True, "done"
        g.phase = phase_
        return g

    def report(self, items):
        with tempfile.TemporaryDirectory() as tmp:
            os.makedirs(os.path.join(tmp, "runs"))
            g = self.gate(tmp, items)
            g.checked("s", "pkg")
            saved, gates.UAT_DIR = gates.UAT_DIR, tmp
            try:
                verdict = g.report("t")
            finally:
                gates.UAT_DIR = saved
            [name] = os.listdir(os.path.join(tmp, "runs"))
            with open(os.path.join(tmp, "runs", name)) as f:
                return bool(verdict), f.read()

    def test_the_verdict_and_its_row(self):
        for why, items, passed, row in (
            ("not checked is no failure, but it is shown", self.ITEMS, True, "| s | PASS | NOT CHECKED b: no de.tar |"),
            ("a false item still fails beside it", self.ITEMS + [{"item": "c", "ok": False, "got": "left"}], False,
             "| s | FAIL | c: left; NOT CHECKED b: no de.tar |"),
        ):
            with self.subTest(why):
                verdict, text = self.report(items)
                self.assertEqual(passed, verdict)
                self.assertIn(row, text)

    def test_the_phase_summary_counts_them_apart(self):
        res = self.ITEMS + [{"item": "c", "ok": False, "got": "left"}]
        saved = phase.with_page
        phase.with_page = lambda d, run: res
        try:
            with tempfile.TemporaryDirectory() as tmp:
                line = phase.check(None, os.path.join(tmp, "out.json"))
        finally:
            phase.with_page = saved
        self.assertEqual("1/3 ok; failed: ['c']; not checked: ['b']", line)


class PerfTest(unittest.TestCase):
    def test_a_column_without_a_reading_is_none(self):
        cold = [{"keyboard": 300, "ready": 0}, {"keyboard": 500}, {"keyboard": 400, "ready": 900}]
        mem = [{"pss_total": 1}, {"pss_total": 102400}, {"pss_total": 153600}]
        for rows, key, scale, want in (
            (cold, "keyboard", None, 400),   # the median of those that have one
            (cold, "ready", None, 900),      # a 0 is a missing reading, not a measurement
            (cold, "candidate", None, None),  # none: None, shown n/a
            (mem[1:], "pss_total", 1024, 125.0),  # kB to MB, one decimal
            ([], "keyboard", None, None),
        ):
            self.assertEqual(want, gates.median_of(rows, key, scale), (key, rows))

    def test_a_build_with_no_key_reading_is_incomplete(self):
        r = {"cold_start_ms": [{"keyboard": 1, "ready": 2, "candidate": 3}], "key_ms": [],
             "mem_kb": [{}, {"pss_total": 1024, "native_heap": 2048, "java_heap": 3072}]}
        self.assertEqual([1, 2, 3, None, None, 1.0, 2.0, 3.0], gates.perf_cols(r))
        r["key_ms"] = [30, 10, 20]
        self.assertEqual([20, 30], gates.perf_cols(r)[3:5])


if __name__ == "__main__":
    unittest.main()
