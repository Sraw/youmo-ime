#!/usr/bin/env python3
"""Release gates 2, 3 and 5 of dev/design/fcitx-replacement.md §8 on a redroid of their own.

  gates.py upgrade <new.apk> [--old DIR]        gate 2: v0.3.0/0.3.1/0.3.2 upgraded in place
  gates.py backups <new.apk> --old DIR          gate 3: new backup → each old build; old backups → new
  gates.py perf <label>=<apk> [<label>=<apk>…]   gate 5: start, key latency, memory, side by side

DIR holds the released text APKs (`gh release download v0.3.x -p '*text-arm64*'`). Options:
--port (5566), --container (youmo-gates), --cpus (0-5), --keep (leave the container up).

The new build is the release tree's release APK at the release; before that, a .debug APK:
it cannot be installed over a released one (other id and key), so gate 2
installs it and puts each fixture's files into its own dirs before its first start, as an upgrade
would leave them, and gate 3 retargets the backups between the two ids (metadata.json's packageName
and the preferences file's name, which the import checks). Everything else is as at the release.

Results: uat/runs/<date>-gate-<name>-<new apk's file name>.md, items in uat/runs/gates/<stamp>/.
"""
import argparse
import datetime
import glob
import json
import os
import re
import statistics
import subprocess
import sys
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
UAT_DIR = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(UAT_DIR, "tools"))
import uat  # noqa: E402

RELEASED = "io.github.sraw.youmo"
VERSIONS = ("v0.3.0", "v0.3.1", "v0.3.2")
FIXTURES = os.path.join(UAT_DIR, "fixtures", "upgrade")


def aapt():
    """the newest build-tools' aapt2, looked up when first needed (the unit tests import this file
    on machines without build-tools); versions compared as numbers, so 36.1.0 is newer than 9.0.0"""
    sdk = os.environ.get("ANDROID_HOME", os.path.expanduser("~/android_sdk"))
    found = glob.glob(os.path.join(sdk, "build-tools/*/aapt2"))
    if not found:
        sys.exit(f"no build-tools/*/aapt2 under {sdk} (set ANDROID_HOME)")
    return max(found, key=lambda p: uat.version_key(os.path.basename(os.path.dirname(p))))


def badging(apk):
    out = subprocess.run([aapt(), "dump", "badging", apk], capture_output=True, text=True, check=True).stdout
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", out)
    return m.group(1), m.group(3)


def build_of(pkg):
    return "debug" if pkg.endswith(".debug") else "release"


class Gate:
    def __init__(self, a, name, new_apk):
        self.a = a
        self.serial = f"127.0.0.1:{a.port}"
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        self.dir = os.path.join(UAT_DIR, "runs", "gates", f"{stamp}-{name}")
        os.makedirs(self.dir, exist_ok=True)
        self.name = name
        self.rows = []
        self.new = new_apk
        self.new_pkg, self.new_ver = badging(new_apk) if new_apk else (None, None)

    # --- the device
    def up(self):
        c = self.a.container
        subprocess.run(["adb", "disconnect", self.serial], capture_output=True)
        subprocess.run(["docker", "rm", "-f", c], capture_output=True)
        subprocess.run(["docker", "run", "-d", "--name", c, "--privileged", "--memory", "2048m",
                        "--cpuset-cpus", self.a.cpus, "-p", f"{self.serial}:5555", uat.IMAGE,
                        "androidboot.redroid_width=1080", "androidboot.redroid_height=2340",
                        "androidboot.redroid_dpi=440", "androidboot.redroid_gpu_mode=host"],
                       check=True, capture_output=True)
        self.wait_boot()
        subprocess.run(["docker", "exec", c, "sh", "-c",
                        "setprop persist.sys.locale zh-CN; setprop sys.boot_completed 0; stop; start"])
        time.sleep(3)
        self.wait_boot()
        time.sleep(3)

    def wait_boot(self):
        for _ in range(240):
            subprocess.run(["adb", "connect", self.serial], capture_output=True)
            r = subprocess.run(["adb", "-s", self.serial, "shell", "getprop sys.boot_completed; pm path android"],
                               capture_output=True, text=True)
            if r.stdout.startswith("1") and "package:" in r.stdout:
                return
            time.sleep(1)
        sys.exit("redroid did not boot")

    def down(self):
        if not self.a.keep:
            subprocess.run(["adb", "disconnect", self.serial], capture_output=True)
            subprocess.run(["docker", "rm", "-f", self.a.container], capture_output=True)

    def uninstall(self, *pkgs):
        for p in pkgs:
            subprocess.run(["adb", "-s", self.serial, "uninstall", p], capture_output=True)

    def phase(self, pkg, *args):
        """phase.py as the build of pkg; (ok, its last line)"""
        env = dict(os.environ, UAT_BUILD=build_of(pkg),
                   UAT_CONTAINERS=f"{self.a.port}:{self.a.container}")
        t0 = time.time()
        r = subprocess.run([sys.executable, os.path.join(HERE, "phase.py"), str(self.a.port), *args],
                           capture_output=True, text=True, env=env)
        out = (r.stdout + r.stderr).strip().splitlines()
        last = out[-1] if out else ""
        print(f"  [{pkg}] {' '.join(map(os.path.basename, args))}: {last} ({time.time() - t0:.0f} s)", flush=True)
        if r.returncode:
            with open(os.path.join(self.dir, "errors.log"), "a") as f:
                f.write(f"--- {pkg} {args}\n" + "\n".join(out[-40:]) + "\n")
        return r.returncode == 0, last

    def checked(self, scenario, pkg, version=None):
        """version: gate 2's fixture, whose user_de (if it has a de.tar) the check also reads"""
        out = os.path.join(self.dir, f"{scenario}.json")
        ok, last = self.phase(pkg, "check", out, *([version] if version else []))
        items = json.load(open(out)) if os.path.exists(out) else []
        self.rows.append((scenario, ok and items and all(i["ok"] is not False for i in items), last, items))

    def blocked(self, scenario, last):
        self.rows.append((scenario, False, last, []))

    # --- the report
    def report(self, title, notes=""):
        date = datetime.date.today().isoformat()
        stem = os.path.splitext(os.path.basename(self.new))[0]
        path = os.path.join(UAT_DIR, "runs", f"{date}-gate-{self.name}-{stem}.md")
        lines = [f"# {title}", "", f"New build: `{os.path.basename(self.new)}` ({self.new_pkg} {self.new_ver})"
                 if self.new else "", f"Items: `{os.path.relpath(self.dir, UAT_DIR)}/`", "", notes, "",
                 "| scenario | result | detail |", "|---|---|---|"]
        for scenario, ok, last, items in self.rows:
            bad = [f"{i['item']}: {i['got']}" for i in items if i["ok"] is False]
            # ok None: an item the fixture could not exercise, shown so the PASS is not read as covering it
            unchecked = [f"NOT CHECKED {i['item']}: {i['got']}" for i in items if i["ok"] is None]
            detail = "; ".join(bad + unchecked) if bad or unchecked else last
            lines.append(f"| {scenario} | {'PASS' if ok else 'FAIL'} | {str(detail).replace('|', '/')} |")
        verdict = all(r[1] for r in self.rows) and self.rows
        lines += ["", f"Gate: **{'PASS' if verdict else 'FAIL'}**", ""]
        with open(path, "w") as f:
            f.write("\n".join(lines))
        print(f"{'PASS' if verdict else 'FAIL'}: {path}")
        return verdict


def old_apk(a, version):
    found = glob.glob(os.path.join(a.old or "", f"*{version.lstrip('v')}-text-*.apk"))
    if not found:
        sys.exit(f"no {version} text APK in {a.old}")
    return found[0]


def retarget(src, dst, pkg):
    """the backup as the build pkg's own: metadata.json's packageName and the preferences' file name"""
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            data = zin.read(info)
            name = info.filename
            if name == "metadata.json":
                meta = json.loads(data)
                meta["packageName"] = pkg
                data = json.dumps(meta, indent=4).encode()
            name = re.sub(r"^shared_prefs/io\.github\.sraw\.youmo(\.debug)?_preferences\.xml$",
                          f"shared_prefs/{pkg}_preferences.xml", name)
            info2 = zipfile.ZipInfo(name, info.date_time)
            info2.external_attr = info.external_attr
            zout.writestr(info2, data, zipfile.ZIP_DEFLATED)
    return dst


def upgrade_into(g, version):
    """the new build with fixture <version>'s files, as an in-place upgrade from it leaves them"""
    g.uninstall(RELEASED, RELEASED + ".debug")
    if g.new_pkg == RELEASED:
        steps = [(RELEASED, "install", old_apk(g.a, version)), (RELEASED, "restore-files", version),
                 (RELEASED, "install", g.new, "-r")]
    else:
        steps = [(g.new_pkg, "install", g.new), (g.new_pkg, "restore-files", version)]
    for pkg, *args in steps:
        ok, last = g.phase(pkg, *args)
        if not ok:
            return last
    return None


def cmd_upgrade(a):
    g = Gate(a, "upgrade", a.new)
    try:
        g.up()
        for v in VERSIONS:
            print(v, flush=True)
            err = upgrade_into(g, v)
            if err:
                g.blocked(f"{v} → new", err)
            else:
                g.checked(f"{v} → new", g.new_pkg, v)
    finally:
        g.down()
    mode = ("in place over the released APK (`install -r`)" if g.new_pkg == RELEASED else
            "debug build: installed, then the fixture's files put into its dirs before its first start")
    return g.report("Gate 2: in-place upgrade", f"Upgrade: {mode}.")


def cmd_backups(a):
    g = Gate(a, "backups", a.new)
    try:
        g.up()
        # new build → old builds: the new build's own backup, after it wrote the host's files itself
        print("new build's backup", flush=True)
        new_zip = os.path.join(g.dir, "new-backup.zip")
        err = upgrade_into(g, "v0.3.2")
        for step in ((g.new_pkg, "rewrite"), (g.new_pkg, "backup", new_zip)):
            if err:
                break
            ok, last = g.phase(*step)
            err = None if ok else last
        if not err:
            g.checked("new build itself (after its backup)", g.new_pkg)
        for v in VERSIONS:
            print(f"new → {v}", flush=True)
            if err:
                g.blocked(f"new → {v}", f"no backup: {err}")
                continue
            g.uninstall(RELEASED)
            z = retarget(new_zip, os.path.join(g.dir, f"new-for-{v}.zip"), RELEASED)
            ok, last = g.phase(RELEASED, "install", old_apk(a, v))
            if ok:
                ok, last = g.phase(RELEASED, "import", z)
            if ok:
                g.checked(f"new → {v}", RELEASED)
            else:
                g.blocked(f"new → {v}", last)
        g.uninstall(RELEASED)
        # old backups → new build
        for v in VERSIONS:
            print(f"{v} → new", flush=True)
            g.uninstall(g.new_pkg)
            z = retarget(os.path.join(FIXTURES, v, "backup.zip"), os.path.join(g.dir, f"{v}-for-new.zip"), g.new_pkg)
            ok, last = g.phase(g.new_pkg, "install", g.new)
            if ok:
                ok, last = g.phase(g.new_pkg, "import", z)
            if ok:
                g.checked(f"{v} backup → new", g.new_pkg)
            else:
                g.blocked(f"{v} backup → new", last)
    finally:
        g.down()
    return g.report("Gate 3: backups both ways",
                    "New → old: the new build upgraded from the v0.3.2 fixture, 繁体 and 性能档位 changed and "
                    "changed back from its keyboard and settings (so it wrote those files), 备份全部; that zip "
                    "restored into each released build on a clean install. Old → new: each fixture's backup.zip "
                    "restored into a clean new build." + ("" if g.new_pkg == RELEASED else
                                                          " Debug build: zips retargeted between the two ids."))


def cmd_perf(a):
    g = Gate(a, "perf", None)
    results = {}
    try:
        g.up()
        # two rounds, A B A B, the second kept: the first build measured after the boot was slower in
        # every round-one run (the device still settling), whichever build it was
        for rnd, spec in [(r, s) for r in (1, 2) for s in a.apks]:
            label, _, apk = spec.partition("=")
            pkg, ver = badging(apk)
            print(f"round {rnd} {label}: {pkg} {ver}", flush=True)
            g.uninstall(RELEASED, RELEASED + ".debug")
            out = os.path.join(g.dir, f"{label}-round{rnd}.json")
            ok, last = g.phase(pkg, "install", apk)
            if ok:
                ok, last = g.phase(pkg, "perf", out, str(a.runs))
            results[label] = (ver, json.load(open(out)) if ok and os.path.exists(out) else None, last)
    finally:
        g.down()
    date = datetime.date.today().isoformat()
    path = os.path.join(UAT_DIR, "runs", f"{date}-gate-perf-{'-'.join(r.split('=')[0] for r in a.apks)}.md")
    lines = ["# Gate 5: start, key latency, memory", "",
             f"Items: `{os.path.relpath(g.dir, UAT_DIR)}/`. redroid x86_64, cpus {a.cpus}, 2 GB. Cold start: "
             "from `am force-stop` (the field focused, tapped again), until the keyboard shows, the space key "
             "names the input method, and `ni` tapped then has a candidate (medians of "
             f"{a.runs}). Key: `input tap` to the composing row or the bar changing, 8 words twice, through "
             "adb and the UI dumper (its read cost is in every number, the same for both). Memory: dumpsys "
             "meminfo after a GC, 6 samples 1.5 s apart, the first dropped (it reads high). Builds measured "
             "A B A B on one device, the second round reported.", "",
             "| build | version | cold start ms: keyboard / host up / candidate | key ms p50 / p90 | PSS MB | Native heap MB | Java heap MB |",
             "|---|---|---|---|---|---|---|"]
    complete = bool(results)
    for label, (ver, r, last) in results.items():
        if not r:
            lines.append(f"| {label} | {ver} | blocked: {last} | | | | |")
            complete = False
            continue
        cold = r["cold_start_ms"]
        cols = perf_cols(r)
        complete = complete and None not in cols
        cols = ["n/a" if c is None else c for c in cols]
        host = (f" (Kotlin host's own: {median_of(cold, 'host_log')})"
                if any(c.get("host_log") for c in cold) else "")
        lines.append(f"| {label} | {ver} | {cols[0]} / {cols[1]} / {cols[2]}{host} | {cols[3]} / {cols[4]} | "
                     f"{cols[5]} | {cols[6]} | {cols[7]} |")
    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(path)
    return complete


def perf_cols(r):
    """a build's row: cold start keyboard / ready / candidate, key p50 / p90, PSS / native / Java MB.
    A column with no reading (every run timed out, meminfo unread) is None, shown n/a and failing the
    gate: a 0 there would read as a measurement"""
    cold, mem = r["cold_start_ms"], r["mem_kb"][1:]   # the first meminfo sample reads high
    k = sorted(r["key_ms"])
    return [median_of(cold, "keyboard"), median_of(cold, "ready"), median_of(cold, "candidate"),
            statistics.median(k) if k else None, k[int(len(k) * 0.9)] if k else None,
            *(median_of(mem, key, 1024) for key in ("pss_total", "native_heap", "java_heap"))]


def median_of(rows, key, scale=None):
    """the median of rows' readings of key, those that have one; None when none has"""
    got = [r[key] for r in rows if r.get(key)]
    if not got:
        return None
    m = statistics.median(got)
    return round(m / scale, 1) if scale else m


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--port", default="5566")
    p.add_argument("--container", default="youmo-gates")
    p.add_argument("--cpus", default="0-5")
    p.add_argument("--keep", action="store_true")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("upgrade")
    s.add_argument("new")
    s.add_argument("--old")
    s = sub.add_parser("backups")
    s.add_argument("new")
    s.add_argument("--old", required=True)
    s = sub.add_parser("perf")
    s.add_argument("apks", nargs="+")
    s.add_argument("--runs", type=int, default=5)
    a = p.parse_args()
    ok = {"upgrade": cmd_upgrade, "backups": cmd_backups, "perf": cmd_perf}[a.cmd](a)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
