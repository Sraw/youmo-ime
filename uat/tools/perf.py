#!/usr/bin/env python3
"""Keyboard performance, two builds compared on one device (design 060-render-p3 §4.3).

  perf.py A [B] [--pairs 3] [--runs 20] [--only press,anim] [--trace] [--out DIR]

A and B are commits (built in a git worktree of their own under DIR) or source trees (built as they
are). Each is built as the benchmark type (specs/testing.md) with its text pack; then run alternately,
A1 B1 B2 A2 A3 B3 ...: installed, the text pack imported through PackImportProvider (not gradle's
connected task: it uninstalls the app, and the pack with it), KeyboardPerfBaselineTest run with
`am instrument`, its `KeyboardPerf` lines kept. Without B, A is run `--pairs` times alone.

Writes DIR/<run>.perf.log, DIR/rows.tsv (a line a row and run) and DIR/table.md: each row's mean ms,
p90 ms, KiB a step and GCs a step, the runs' mean, B against A in percent; the frame, idle and anim
lines as they are. --trace: built profileable, the process stopped before atrace starts, and the
`canvas anim` sections of the main thread (A1 on) summed up from DIR/<run>.trace.

Device: ANDROID_SERIAL (default 127.0.0.1:5599). --lock DIR: a mkdir mutex held for each run.
"""
import argparse
import json
import os
import re
import socket
import statistics
import subprocess
import sys
import time

PKG = 'io.github.sraw.youmo.bench'
ABI = 'arm64-v8a'
TEST = 'org.fcitx.fcitx5.android.input.KeyboardPerfBaselineTest'
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def sh(*cmd, out=None, check=True, **kw):
    print('+', ' '.join(cmd), file=sys.stderr)
    if out:
        with open(out, 'w') as f:
            return subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT, check=check, **kw)
    return subprocess.run(cmd, check=check, **kw)


def adb(*args, **kw):
    return sh('adb', *args, **kw)


def git(path, *args):
    return subprocess.run(['git', '-C', path, *args], check=True, capture_output=True, text=True).stdout.strip()


def tree(rev, name, out):
    """A source tree for rev and the commit it is at: itself if a directory (its HEAD, + if it has
    changes), else a worktree checked out at exactly that commit, every run."""
    if os.path.isdir(rev):
        path = os.path.abspath(rev)
        dirty = git(path, 'status', '--porcelain', '--untracked-files=no')
        return path, git(path, 'rev-parse', '--short=9', 'HEAD') + ('+' if dirty else '')
    sha = git(ROOT, 'rev-parse', '--verify', rev + '^{commit}')
    path = os.path.join(out, 'wt-' + name)
    if os.path.exists(path):
        # only a worktree of its own: git -C in a plain directory would act on the repository above it
        top = subprocess.run(['git', '-C', path, 'rev-parse', '--show-toplevel'], capture_output=True, text=True).stdout.strip()
        if not os.path.isfile(os.path.join(path, '.git')) or os.path.realpath(top) != os.path.realpath(path):
            sys.exit(f'{path} exists and is not a git worktree of its own: move it away or pick another --out')
        sh('git', '-C', path, 'checkout', '--detach', '--force', sha)
    else:
        sh('git', '-C', ROOT, 'worktree', 'add', '--detach', path, sha)
        # local.properties: the SDK's path (never printed)
        lp = os.path.join(ROOT, 'local.properties')
        if os.path.exists(lp):
            os.symlink(lp, os.path.join(path, 'local.properties'))
    if git(path, 'rev-parse', 'HEAD') != sha:
        sys.exit(f'{path} is not at {rev} ({sha})')
    return path, sha[:9]


def build(path, trace):
    args = ['./gradlew', ':app:assembleBenchmark', ':app:assembleBenchmarkAndroidTest', ':app:packs',
            '-Pyoumo.testBuildType=benchmark', '-PbuildABI=' + ABI]
    if trace:
        args.append('-PbuildProfileable=true')
    sh(*args, cwd=path)


def apks(path):
    out = os.path.join(path, 'app/build/outputs/apk')
    # the app is split by ABI: the phone's, exactly one; the test APK is not split
    app = [os.path.join(out, 'benchmark', f) for f in os.listdir(os.path.join(out, 'benchmark')) if f.endswith('.apk') and ABI in f]
    test = [os.path.join(out, 'androidTest/benchmark', f) for f in os.listdir(os.path.join(out, 'androidTest/benchmark')) if f.endswith('.apk')]
    if len(app) != 1 or len(test) != 1:
        raise RuntimeError(f'want one {ABI} benchmark APK and one test APK in {out}: {app} {test}')
    pack = os.path.join(path, 'app/build/packs/youmo-text-v1.youmopack')
    return app[0], test[0], pack


FAILED = re.compile(r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg|INSTRUMENTATION_CODE: 0')


def run(tag, path, a, out):
    app, test, pack = apks(path)
    adb('uninstall', PKG, check=False, stdout=subprocess.DEVNULL)
    adb('uninstall', PKG + '.test', check=False, stdout=subprocess.DEVNULL)
    adb('install', '-r', '-g', app, stdout=subprocess.DEVNULL)
    adb('install', '-r', '-g', test, stdout=subprocess.DEVNULL)
    name = os.path.basename(pack)
    with open(pack, 'rb') as f:
        adb('shell', 'content', 'write', '--uri', f'content://{PKG}.uat.packs/{name}', stdin=f)
    adb('shell', 'content', 'call', '--uri', f'content://{PKG}.uat.packs', '--method', 'import', '--arg', name,
        out=f'{out}/{tag}.import.log')
    extra = ['-e', 'perfRuns', str(a.runs)] + (['-e', 'perfOnly', a.only] if a.only else [])
    if a.trace:
        # a non-debuggable app's own sections show only when atrace names it, and it is running anew
        adb('shell', 'am', 'force-stop', PKG)
        adb('shell', 'atrace', '--async_start', '-b', '32768', '-a', PKG, 'view', 'gfx', 'dalvik', 'sched', 'input')
    try:
        adb('logcat', '-c')
        adb('shell', 'am', 'instrument', '-w', '-e', 'class', TEST, *extra, f'{PKG}.test/androidx.test.runner.AndroidJUnitRunner',
            out=f'{out}/{tag}.inst.log', check=False)
        adb('logcat', '-d', '-s', 'KeyboardPerf:I', out=f'{out}/{tag}.perf.log')
    finally:
        if a.trace:
            adb('shell', 'atrace', '--async_stop', out=f'{out}/{tag}.trace', check=False)
        # the anim rows' battery saver undone here too: a test killed midway leaves the phone "unplugged"
        adb('shell', 'cmd', 'power', 'set-mode', '0', check=False)
        adb('shell', 'dumpsys', 'battery', 'reset', check=False)
    inst = open(f'{out}/{tag}.inst.log', errors='ignore').read()
    if FAILED.search(inst) or 'OK (' not in inst:
        raise RuntimeError(f'{tag}: the test failed, see {tag}.inst.log')


ROW = re.compile(r'KeyboardPerf: (.*)$')


def parse(file):
    """{(what, label): (mean, p90, KiB, gcs)} and the other lines (frames, anim, memory, scene) as text."""
    rows, other = {}, []
    for line in open(file, errors='ignore'):
        m = ROW.search(line.rstrip('\n'))
        if not m:
            continue
        f = m.group(1).split('\t')
        if len(f) < 3:
            continue
        s = '\t'.join(f[2:])
        mean = re.search(r'\tmean ([\d.]+)', s)
        p90 = re.search(r'dispatch to done [\d.]+ / ([\d.]+)', s) or re.search(r'to frame done [\d.]+ / ([\d.]+)', s)
        alloc = re.search(r'allocated (-?\d+) KiB', s)
        gcs = re.search(r'gcs ([\d.]+)', s)
        if mean and p90:
            rows[(f[0], f[1])] = (float(mean.group(1)), float(p90.group(1)), float(alloc.group(1)) if alloc else 0.0,
                                  float(gcs.group(1)) if gcs else 0.0)
        else:
            other.append((f[0], f[1], s))
    return rows, other


def anim_slices(trace):
    """Durations (ms) of the `canvas anim` sections, each thread's begin/end matched."""
    pat = re.compile(r'-(\d+)\s+\(.*?\)\s+\[\d+\].*?\s([\d.]+):\s+tracing_mark_write:\s+([BE])\|\d+(?:\|(.*))?$')
    stacks, out = {}, []
    for line in open(trace, errors='ignore'):
        m = pat.search(line.rstrip('\n'))
        if not m:
            continue
        s = stacks.setdefault(m.group(1), [])
        if m.group(3) == 'B':
            s.append(((m.group(4) or '').strip(), float(m.group(2))))
        elif s:
            name, t = s.pop()
            if name == 'canvas anim':
                out.append((float(m.group(2)) - t) * 1000)
    return out


def median(d):
    n = len(d)
    return (d[n // 2] + d[(n - 1) // 2]) / 2


def pct(b, a):
    return '—' if not a else '%+.0f%%' % ((b - a) / a * 100)


def table(runs, out, commits, failed):
    missing = [t for t in runs if t not in failed and not os.path.exists(f'{out}/{t}.perf.log')]
    ok = [t for t in runs if t not in failed and t not in missing]
    sides = sorted({t[0] for t in runs})
    parsed = {t: parse(f'{out}/{t}.perf.log') for t in ok}
    runs = ok
    keys = []
    for t in runs:
        keys += [k for k in parsed[t][0] if k not in keys]
    with open(f'{out}/rows.tsv', 'w') as f:
        f.write('run\twhat\tlabel\tmean\tp90\tkib\tgcs\n')
        for t in runs:
            for k, v in parsed[t][0].items():
                f.write('\t'.join([t, *k, *map(str, v)]) + '\n')

    def avg(side, k):
        vs = [parsed[t][0][k] for t in runs if t[0] == side and k in parsed[t][0]]
        return (tuple(statistics.mean(x) for x in zip(*vs)), len(vs)) if vs else None

    def cell(c, side):
        if not c:
            return '—'
        (m, p, k, g), n = c
        return '%.1f / %.1f / %.0f / %.2f' % (m, p, k, g) + ('' if n == want[side] else f' (n {n})')

    want = {s: sum(1 for t in runs if t[0] == s) for s in sides}
    lines = ['Commits measured: ' + ', '.join(f'{s} = {commits.get(s, "?")}' for s in sides), '',
             'Each cell: mean ms / p90 ms / KiB a step / GCs a step, the mean of the runs that passed (%s);'
             ' (n k): a row only k of them have.' % ', '.join(runs)]
    for t, why in failed.items():
        lines.append(f'**{t} failed, not counted**: {why}')
    for t in missing:
        lines.append(f'**{t}: no log, not counted**')
    lines += ['',
             '| row | ' + ' | '.join(sides) + (' | B against A |' if len(sides) == 2 else ' |'),
             '|---|' + '---|' * (len(sides) + (len(sides) == 2))]
    for k in keys:
        cells = [avg(s, k) for s in sides]
        line = '| %s | %s |' % (' | '.join(k), ' | '.join(cell(c, s) for s, c in zip(sides, cells)))
        if len(sides) == 2:
            a, b = cells
            line += ' %s |' % (' / '.join(pct(b[0][i], a[0][i]) for i in range(3)) if a and b else '—')
        lines.append(line)
    lines += ['', 'The other lines (frames, idle, anim, memory, scene, ...), each run:', '']
    for t in runs:
        for what, label, s in parsed[t][1]:
            lines.append(f'- {t} {what} | {label}: {s.strip()}')
        if os.path.exists(f'{out}/{t}.trace'):
            d = sorted(anim_slices(f'{out}/{t}.trace'))
            lines.append(f'- {t} trace canvas anim: n {len(d)}' +
                         (' median %.2f p90 %.2f ms' % (median(d), d[max(0, -(-len(d) * 9 // 10) - 1)]) if d else ''))
    open(f'{out}/table.md', 'w').write('\n'.join(lines) + '\n')
    print('\n'.join(lines))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('a')
    p.add_argument('b', nargs='?')
    p.add_argument('--pairs', type=int, default=3)
    p.add_argument('--runs', type=int, default=20)
    p.add_argument('--only', help='perfOnly: theme,shift,build,press,popup,rebuild,idle,anim')
    p.add_argument('--trace', action='store_true')
    p.add_argument('--out', default=os.path.join(ROOT, 'build/perf'))
    p.add_argument('--lock', help='a directory made (mkdir) as a mutex for each run; waited for, never taken over')
    p.add_argument('--dry-run', action='store_true', help='check out the trees and print the commits and the order, nothing built or run')
    p.add_argument('--no-build', action='store_true', help='use what the trees have built')
    p.add_argument('--table', action='store_true', help='only write the table from the runs in --out')
    a = p.parse_args()
    os.environ.setdefault('ANDROID_SERIAL', '127.0.0.1:5599')
    os.makedirs(a.out, exist_ok=True)
    if a.b:
        order = [('A', 1), ('B', 1)] + [x for i in range(1, a.pairs) for x in
                                       ([('B', i + 1), ('A', i + 1)] if i % 2 else [('A', i + 1), ('B', i + 1)])]
    else:
        order = [('A', i + 1) for i in range(a.pairs)]
    runs = ['%s%d' % x for x in order]
    failed, commits = {}, {}
    if not a.table:
        trees = {'A': tree(a.a, 'a', a.out)}
        if a.b:
            trees['B'] = tree(a.b, 'b', a.out)
        commits = {s: c for s, (_, c) in trees.items()}
        open(f'{a.out}/commits.txt', 'w').write(''.join(f'{s} {c}\n' for s, c in commits.items()))
        print('commits:', commits, 'order:', ' '.join(runs))
        if a.dry_run:
            return
        if not a.no_build:
            for t, _ in trees.values():
                build(t, a.trace)
        try:
            for r in runs:
                for attempt in (1, 2):
                    if a.lock:
                        lock(a.lock)
                    try:
                        run(r, trees[r[0]][0], a, a.out)
                        failed.pop(r, None)
                        break
                    except Exception as e:  # a run failed: once more, then told in the table
                        failed[r] = f'{type(e).__name__}: {e}'
                        print(f'{r} attempt {attempt} failed: {failed[r]}', file=sys.stderr)
                    finally:
                        if a.lock:
                            unlock(a.lock)
        finally:
            json.dump(failed, open(f'{a.out}/failed.json', 'w'))
            table(runs, a.out, commits, failed)
        return
    if os.path.exists(f'{a.out}/commits.txt'):
        commits = dict(l.split() for l in open(f'{a.out}/commits.txt') if l.strip())
    if os.path.exists(f'{a.out}/failed.json'):
        failed = json.load(open(f'{a.out}/failed.json'))
    table(runs, a.out, commits, failed)


def lock(path):
    """mkdir as a mutex (other sessions use the same directory with mkdir/rmdir): waited for, never taken."""
    told = False
    while True:
        try:
            os.mkdir(path)
            break
        except FileExistsError:
            if not told:
                try:
                    who = open(os.path.join(path, 'owner')).read().strip()
                except OSError:
                    who = 'unknown (no owner file)'
                print(f'waiting for {path}, held by {who}', file=sys.stderr)
                told = True
            time.sleep(30)
    with open(os.path.join(path, 'owner'), 'w') as f:
        f.write(f'perf.py pid {os.getpid()} on {socket.gethostname()} since {time.strftime("%Y-%m-%d %H:%M:%S")}\n')


def unlock(path):
    try:
        os.remove(os.path.join(path, 'owner'))
    except FileNotFoundError:
        pass   # a lock taken by another tool (plain mkdir) has no owner file
    os.rmdir(path)

if __name__ == '__main__':
    main()
