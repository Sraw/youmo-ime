"""parity.py without a device: python3 -m unittest test_parity (in uat/parity)"""
import contextlib
import io
import json
import os
import tempfile
import unittest

import parity


def line(**o):
    return json.dumps(o, ensure_ascii=False)


class StatesTest(unittest.TestCase):
    def test_calls_split_by_marks_and_the_front_editors_field(self):
        a = "\n".join([
            line(field="text"),
            line(ic=["beginBatchEdit"], ok=True, text="", sel=[0, 0]),   # the editor starting: no step's
            line(mark=-1, front=True, text="", sel=[0, 0]),
            line(ic=["commitText", "你好", 1], ok=True, text="你好", sel=[2, 2]),
            line(mark=0, front=True, text="你好", sel=[2, 2]),
            line(ic=["finishComposingText"], ok=True, text="你好", sel=[2, 2]),   # left for b's field
            line(mark=1, front=False, text="你好", sel=[2, 2]),
        ])
        b = "\n".join([
            line(field="text"),
            line(ic=["beginBatchEdit"], ok=True, text="", sel=[0, 0]),   # b brought up by step 1
            line(mark=1, front=True, text="", sel=[0, 0]),
        ])
        seen = [(["啊"], ""), ([], "")]
        st = parity.states(seen, [a, b])
        self.assertEqual(2, len(st))
        self.assertEqual({"text": "你好", "sel": [2, 2], "pre": "", "cands": ["啊"], "ic": [["commitText", "你好", 1]]}, st[0])
        # step 1 went to the other app: its field, and the call the first made on the way out
        self.assertEqual("", st[1]["text"])
        self.assertEqual([["finishComposingText"], ["beginBatchEdit"]], st[1]["ic"])

    def test_a_mark_whose_idle_wait_ran_out_is_busy(self):
        a = "\n".join([line(mark=-1, front=True, text="", sel=[0, 0]),
                       line(mark=0, front=True, text="", sel=[0, 0], idle=False),
                       line(mark=1, front=True, text="", sel=[0, 0], idle=True),
                       line(mark=2, front=True, text="", sel=[0, 0])])
        st = parity.states([([], "")] * 3, [a])
        self.assertEqual([True, None, None], [s.get("busy") for s in st])

    def test_a_step_without_its_mark_ends_the_states(self):
        a = "\n".join([line(mark=-1, front=True, text="", sel=[0, 0]), line(mark=0, front=True, text="x", sel=[1, 1])])
        self.assertEqual(1, len(parity.states([([], ""), ([], "")], [a, ""])))


class RecorderTest(unittest.TestCase):
    def test_spaces_in_typed_keys_are_the_space_key(self):
        r = parity.Recorder("engine-pinyin")
        r.type("ni hao ")
        r.type(" ")
        self.assertEqual([["type", "ni"], ["space"], ["type", "hao"], ["space"], ["space"]], r.steps)

    def test_picks_reads_and_what_is_left_out(self):
        r = parity.Recorder("engine-pinyin")
        r.pick(2)
        r.pick("你好")
        r.press("bksp", 2)
        r.switch_im("五笔")
        self.assertEqual([], r.cands())
        self.assertEqual([["pick", "2"], ["pickText", "你好"], ["bksp", "2"], ["ime", "engine-wubi"]], r.steps)
        with self.assertRaises(parity.Unsupported):
            r.glide("nihao")
        with self.assertRaises(parity.Unsupported):
            r.press("123")
        # 九键's label may be shortened or suffixed (a prefix match); the others must match whole
        r.switch_im("九键拼音")
        self.assertEqual(["ime", "engine-t9"], r.steps[-1])
        with self.assertRaises(parity.Unsupported):
            r.switch_im("拼音双拼")


class ExportTest(unittest.TestCase):
    def test_the_uat_scripts_give_sequences_of_known_steps(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "uat.jsonl")
            with contextlib.redirect_stdout(io.StringIO()):
                parity.uat_export(path)
            seqs = [json.loads(x) for x in open(path, encoding="utf-8")]
        ids = {s["id"] for s in seqs}
        self.assertTrue({"PY-01", "PY-64", "KB-14", "TBL-01"} <= ids, ids)
        ops = {"type", "space", "enter", "bksp", "pick", "pickText", "hw", "ime", "field", "app", "pause", "toggle", "quick", "unicode"}
        for s in seqs:
            self.assertIn(s["im"], parity.IM_LABELS)
            self.assertTrue(all(st[0] in ops for st in s["steps"]), s)
        py01 = next(s for s in seqs if s["id"] == "PY-01")
        self.assertEqual([["type", "nihao"], ["space"]], py01["steps"])


class ChooseTest(unittest.TestCase):
    seqs = [{"id": f"r1-{i}"} for i in range(100)]

    def test_a_sample_is_the_same_for_a_seed_and_keeps_the_files_order(self):
        a = parity.choose(self.seqs, 10, 3)
        self.assertEqual(a, parity.choose(self.seqs, 10, 3))
        self.assertNotEqual(a, parity.choose(self.seqs, 10, 4))
        self.assertEqual(a, sorted(a, key=lambda s: int(s["id"].split("-")[1])))
        self.assertEqual(["r1-5", "r1-7"], [s["id"] for s in parity.choose(self.seqs, ids="r1-7,r1-5")])
        self.assertEqual(100, len(parity.choose(self.seqs)))


class CommandsTest(unittest.TestCase):
    def test_shards_split_one_choice_and_merge_back(self):
        seqs = [{"id": f"r-{i}"} for i in range(20)]
        chosen = parity.choose(seqs, 10, 3)
        shards = [parity.choose(seqs, 10, 3, shard=f"{k}/3") for k in range(3)]
        self.assertEqual(sorted(s["id"] for p in shards for s in p), sorted(s["id"] for s in chosen))
        self.assertEqual([len(p) for p in shards], [4, 3, 3])
        with self.assertRaises(ValueError):
            parity.choose(seqs, shard="3/3")
        with tempfile.TemporaryDirectory() as d:
            paths = []
            for k, p in enumerate(shards):
                paths.append(os.path.join(d, f"{k}.jsonl"))
                with open(paths[-1], "w", encoding="utf-8") as f:
                    f.write("\n".join([line(parity=1, shard=k)] + [line(id=s["id"], states=[]) for s in p]) + "\n")
            out = os.path.join(d, "all.jsonl")
            self.assertEqual(parity.merge(paths, out), 10)
            header, merged = parity.read_sequences(out)
            self.assertEqual(header["shard"], 0)
            self.assertEqual(sorted(s["id"] for s in merged), sorted(s["id"] for s in chosen))

    def test_resume_keeps_whole_lines_and_their_ids(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "out.jsonl")
            self.assertIsNone(parity.done_ids(p))
            with open(p, "w", encoding="utf-8") as f:
                f.write(line(parity=1) + "\n" + line(id="a", states=[]) + "\n" + '{"id":"b","sta')
            self.assertEqual(parity.done_ids(p), {"a"})
            with open(p, encoding="utf-8") as f:
                self.assertEqual(len(f.read().splitlines()), 2)

    def test_restore_follows_the_designs_steps(self):
        c = parity.restore_cmd()
        order = ["am force-stop", "tar -xpf", "pm list packages -U", "chown -R $uid:$uid", "restorecon -R"]
        at = [c.index(x) for x in order]
        self.assertEqual(sorted(at), at)
        # the second force-stop: a process started while restoring is stopped after restorecon
        self.assertGreater(c.rindex("am force-stop"), c.rindex("restorecon -R"))
        self.assertIn(f"/data/media/0/Android/data/{parity.PKG}", c)
        s = parity.snapshot_cmd()
        self.assertIn("tar -cpf", s)
        self.assertTrue(s.startswith(f"am force-stop {parity.PKG}"))
        self.assertEqual(2, s[:s.index("tar -cpf")].count("am force-stop"))

    def test_the_profile_is_looked_for_in_external_files_first(self):
        c = parity.profile_cmd()
        self.assertLess(c.index(parity.MEDIA), c.index(parity.DATA))
        self.assertIn("</dev/null", c)   # no profile found: cat must not wait on stdin

    def test_the_dry_run_prints_without_a_device(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "s.jsonl")
            with open(path, "w", encoding="utf-8") as f:
                f.write(line(engine="e", data={}, ims=["engine-pinyin"]) + "\n")
                f.write(line(id="x", im="engine-pinyin", field="text",
                             steps=[["type", "nihao"], ["pick", "2"], ["hw", "Left"], ["app"], ["field", "password"]]) + "\n")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                parity.cmd_dry_run(type("A", (), {"sequences": path, "first": 1, "lines": 100})())
        text = out.getvalue()
        self.assertIn("input keyevent KEYCODE_DPAD_LEFT", text)
        self.assertIn("io.github.sraw.parity.b/io.github.sraw.parity.ParityEditor --es field text", text)
        self.assertIn("--es field password", text)
        self.assertNotIn("uinput", text)


if __name__ == "__main__":
    unittest.main()
