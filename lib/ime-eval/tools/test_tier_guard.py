"""tier-guard.sh's verdicts and --rebaseline's hard conditions (R3-6), and --device's row-for-row check (R3-1):
run on a copy of the script beside a fake ime-eval and a fake run-on-device.sh that list what a plan says."""
import json
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / 'tier-guard.sh'
ROWS = 1000
# every input as long: a set's keys are ROWS * KEY_LENGTH
KEY_LENGTH = 6
KEYS = ROWS * KEY_LENGTH

# ime-eval and run-on-device.sh in one: per set and tier, the rows whose first candidate is the expected one, then
# those where it is second; at high the escalated keys, none printed where the plan has none; on the device the app's
# built-in phrases after the first of the rows in the plan's clock, and either side's rows in its listed as listed there
FAKE = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
NAMES = {"pinyin-chat": "pinyin", "chat-t9": "t9", "chat-xiaohe": "xiaohe", "pinyin": "main", "pinyin-long": "long"}

def samples(path):
    lines = [l for l in Path(path).read_text().splitlines() if l.strip() and not l.startswith("#")]
    return [l.split("\t") for l in lines]

def listed(set_path, tier, plan, count, timing, clock, side):
    name = NAMES[Path(set_path).stem]
    top1, top5 = plan[name][tier]
    rows = plan.get("listed", {}).get(side, {}).get("%s-%s" % (name, tier), {})
    out = []
    for i, (key, want, *_) in enumerate(samples(set_path)):
        others = ["x%d" % j for j in range(count)]
        cands = [want] + others[1:] if i < top1 else (others[:1] + [want] + others[2:] if i < top5 else others)
        phrases = clock.get(str(i), [])
        cands = rows.get(str(i), cands[:1] + phrases + cands[1:count - len(phrases)])
        out.append("\t".join([key, timing] + cands))
    return name, out

plan = json.loads(Path(os.environ["FAKE_PLAN"]).read_text())
if os.path.basename(sys.argv[0]) == "run-on-device.sh":
    set_path, ime, out_dir = sys.argv[1:4]
    level = os.environ["LEVEL"]
    with open(os.environ["FAKE_CALLS"], "a") as calls:
        calls.write("%s %s %s %s %s\n" % (Path(set_path).name, ime, level, os.environ.get("NINE", "-"), os.environ.get("INSTALL", "-")))
    name = NAMES[Path(set_path).stem]
    _, rows = listed(set_path, level, dict(plan.get("device", plan), listed=plan.get("listed", {})), 5, "900,800",
                     plan.get("clock", {}).get(name, {}), "device")
    os.makedirs(out_dir, exist_ok=True)
    result = os.path.join(out_dir, "%s-%s-%s.tsv" % (Path(set_path).stem, ime, level))
    Path(result).write_text("\n".join(rows) + "\n")
    print("installing")
    print(result)
elif sys.argv[1] in ("t9", "shuangpin"):
    src, dst = sys.argv[-2:]
    Path(dst).write_text(Path(src).read_text())
else:
    args = sys.argv[1:]
    set_path, result = args[2], args[3]
    tier = args[args.index("--tier") + 1]
    name, rows = listed(set_path, tier, plan, 10, "1,2", {}, "jvm")
    Path(result).write_text("\n".join(rows) + "\n")
    escalated = plan[name].get("escalated")
    if tier == "high" and escalated is not None:
        print("escalated %d of %d keys" % (escalated, len(rows) * len(rows[0].split("\t")[0])))
'''


def plan():
    """A gate as measured: 30% of full pinyin's and 小鹤's keys, 5% of the nine keys', high ahead where it must be."""
    return {
        'pinyin': {'low': [600, 900], 'medium': [650, 900], 'high': [670, 900], 'escalated': KEYS * 30 // 100},
        't9': {'low': [500, 800], 'medium': [520, 800], 'high': [520, 800], 'escalated': KEYS * 5 // 100},
        'xiaohe': {'low': [600, 900], 'medium': [650, 900], 'high': [670, 900], 'escalated': KEYS * 30 // 100},
        # 0.2 points of 1000 rows: two behind is still in
        'main': {'low': [700, 850], 'medium': [720, 850], 'high': [718, 850], 'escalated': 100},
        'long': {'low': [700, 850], 'medium': [720, 850], 'high': [720, 850], 'escalated': 100},
    }


def executable(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    path.chmod(path.stat().st_mode | stat.S_IXUSR)


class TierGuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        here = self.tmp / 'lib' / 'ime-eval'
        (here / 'tools').mkdir(parents=True)
        shutil.copy(SCRIPT, here / 'tools' / 'tier-guard.sh')
        executable(here / 'run-on-device.sh', FAKE)
        executable(self.tmp / 'ime-eval', FAKE)
        sets = here / 'data'
        sets.mkdir()
        for name in ('pinyin-chat', 'pinyin', 'pinyin-long'):
            rows = ''.join('k%05d\t词%d\ttag\n' % (i, i) for i in range(ROWS))
            (sets / f'{name}.tsv').write_text('# a set\n' + rows)
        self.baseline = sets / 'tier-baseline.tsv'
        self.data = self.tmp / 'pinyin.data'
        self.data.write_text('data')
        self.models = self.tmp / 'models'
        self.models.mkdir()
        for m in ('sentence-model.safetensors', 'sentence-model-large.safetensors'):
            (self.models / m).write_text(m)
        self.temp = self.tmp / 'temp'
        self.temp.mkdir()
        self.calls = self.tmp / 'calls'

    def guard(self, the_plan, *args):
        plan_file = self.tmp / 'plan.json'
        plan_file.write_text(json.dumps(the_plan))
        env = dict(os.environ, EVAL=str(self.tmp / 'ime-eval'), DATA=str(self.data), MODELS=str(self.models),
                   FAKE_PLAN=str(plan_file), FAKE_CALLS=str(self.calls), TMPDIR=str(self.temp), THREADS='1')
        env.pop('INSTALL', None)
        r = subprocess.run(['bash', str(self.tmp / 'lib' / 'ime-eval' / 'tools' / 'tier-guard.sh'), *args],
                           env=env, capture_output=True, text=True, timeout=120)
        return r.returncode, r.stdout + r.stderr

    def rebaselined(self):
        status, out = self.guard(plan(), '--rebaseline')
        self.assertEqual(0, status, out)
        return self.baseline.read_text()

    def test_a_rebaseline_is_written_only_where_the_gate_does_what_it_was_measured_to(self):
        def with_(name, key, value):
            p = plan()
            p[name][key] = value
            return p
        rows = (
            ('full pinyin escalating 25% exactly', with_('pinyin', 'escalated', KEYS // 4), 0),
            ('the nine keys escalating 10% exactly', with_('t9', 'escalated', KEYS // 10), 0),
            ('full pinyin escalating under 25%', with_('pinyin', 'escalated', KEYS // 4 - 1), 1),
            ('小鹤 escalating over 55%', with_('xiaohe', 'escalated', KEYS * 55 // 100 + 1), 1),
            ('the nine keys escalating under 0.5%', with_('t9', 'escalated', KEYS * 5 // 1000 - 1), 1),
            ('the nine keys escalating over 10%', with_('t9', 'escalated', KEYS // 10 + 1), 1),
            ('full pinyin high only level with medium', with_('pinyin', 'high', [650, 900]), 1),
            ('小鹤 high behind medium', with_('xiaohe', 'high', [640, 900]), 1),
            ('the main set high three rows behind medium (0.3 points)', with_('main', 'high', [717, 850]), 1),
            ('the main set medium behind low', with_('main', 'medium', [699, 850]), 1),
        )
        for why, the_plan, want in rows:
            with self.subTest(why):
                self.baseline.unlink(missing_ok=True)
                status, out = self.guard(the_plan, '--rebaseline')
                self.assertEqual(want, status, out)
                self.assertEqual(want == 0, self.baseline.exists(), out)

    def test_a_run_is_held_to_the_baseline_row_for_row(self):
        before = self.rebaselined()
        self.assertIn('pinyin\thigh\t1000\t670\t900\t1800\t6000', before)
        changed_top5 = plan()
        changed_top5['t9']['low'] = [500, 801]
        changed_escalation = plan()
        changed_escalation['main']['escalated'] = 101
        no_count = plan()
        no_count['xiaohe']['escalated'] = None
        rows = (
            ('as the baseline', plan(), 0),
            ('a first-five row more', changed_top5, 1),
            ('a key more escalated', changed_escalation, 1),
            ('no escalation count', no_count, 2),
        )
        for why, the_plan, want in rows:
            with self.subTest(why):
                status, out = self.guard(the_plan)
                self.assertEqual(want, status, out)
                self.assertEqual(before, self.baseline.read_text())

    def test_other_data_or_models_give_no_verdict(self):
        self.rebaselined()
        (self.models / 'sentence-model-large.safetensors').write_text('retrained')
        status, out = self.guard(plan())
        self.assertEqual(3, status, out)

    def test_without_a_work_dir_nothing_is_left_behind(self):
        self.rebaselined()
        status, out = self.guard(plan())
        self.assertEqual(0, status, out)
        self.assertEqual([], list(self.temp.iterdir()))

    def test_the_device_types_every_set_at_every_tier_as_the_app_reads_it(self):
        self.rebaselined()
        work = self.tmp / 'work'
        status, out = self.guard(plan(), '--device', str(work))
        self.assertEqual(0, status, out)
        self.assertIn('the device as the JVM', out)
        calls = self.calls.read_text().splitlines()
        self.assertEqual(15, len(calls), calls)
        # installed once, then typed on what it installed
        self.assertEqual(['1'] + ['0'] * 14, [c.split()[4] for c in calls])
        self.assertIn('chat-t9.tsv engine-pinyin medium 1 0', calls)
        self.assertIn('chat-xiaohe.tsv engine-shuangpin high - 0', calls)
        self.assertIn('pinyin.tsv engine-pinyin low - 0', calls)
        self.assertIn('pinyin-long.tsv engine-pinyin high - 0', calls)
        # no timing in what is compared
        self.assertEqual('k00000\t词0\tx1\tx2\tx3\tx4', (work / 'main-low.shown.tsv').read_text().splitlines()[0])

    def test_the_device_check_holds_each_row_to_the_jvms_less_the_apps_built_in_phrases(self):
        self.rebaselined()
        # one of each built-in shape (rq, sj, xq) after the first candidate on the device
        clock = {'main': {'3': ['15:06', '15时06分'], '4': ['2026年11月22日', '2026-11-22'], '5': ['二〇二六年十一月二十二日'],
                          '6': ['星期日', '周日'], '699': ['15:06', '15时06分']}}

        def device(name, tier, top):
            d = plan()
            d[name][tier] = top
            return dict(plan(), device=d)
        rows = (
            # the JVM's row cut by as many as the app put in, as they pushed that many off the device's five
            ('every shape of them left out', plan(), None,
             {3: 'k00003\t词3\tx1\tx2', 4: 'k00004\t词4\tx1\tx2', 5: 'k00005\t词5\tx1\tx2\tx3', 6: 'k00006\t词6\tx1\tx2'}),
            ('another first candidate on the nine keys', device('t9', 'medium', [519, 800]), 't9-medium', {}),
            # row 699 has the wanted one second on the device, after the phrases
            ('another first candidate on a row with them', device('main', 'low', [699, 850]), 'main-low', {}),
            ('a device row short of candidates, none of them in it',
             dict(plan(), listed={'device': {'main-low': {'7': ['词7', 'x1', 'x2']}}}), 'main-low', {}),
            # 周一 is a word too: where both sides list it the app added nothing, so where it stands is held
            ('a word of their shape the device places otherwise',
             dict(plan(), listed={'jvm': {'main-low': {'9': ['词9', '周一', 'x2', 'x3', 'x4']}},
                                  'device': {'main-low': {'9': ['周一', '词9', 'x2', 'x3', 'x4']}}}), 'main-low', {}),
        )
        for why, the_plan, differs, cut in rows:
            with self.subTest(why):
                work = self.tmp / 'work'
                shutil.rmtree(work, ignore_errors=True)
                status, out = self.guard(dict(the_plan, clock=clock), '--device', str(work))
                self.assertEqual(4 if differs else 0, status, out)
                self.assertEqual([differs + '.diff'] if differs else [], sorted(p.name for p in work.glob('*.diff') if p.stat().st_size))
                if differs:
                    self.assertIn('%s: the device shows 1 rows otherwise' % differs.replace('-', ' '), out)
                for r, want in cut.items():
                    self.assertEqual(want, (work / 'main-low.device.tsv').read_text().splitlines()[r])
                    self.assertEqual(want, (work / 'main-low.shown.tsv').read_text().splitlines()[r])

    def test_the_device_check_keeps_its_rows_in_a_work_dir(self):
        self.rebaselined()
        status, out = self.guard(plan(), '--device')
        self.assertEqual(2, status, out)
        self.assertFalse(self.calls.exists())


if __name__ == '__main__':
    unittest.main()
