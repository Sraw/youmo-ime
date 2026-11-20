#!/usr/bin/env python3
"""Release gates 2 and 3 of dev/design/fcitx-replacement.md §8 on an emulator of their own (uat.py's AVD).

  gates.py upgrade <new.apk> [--old DIR]        gate 2: v0.3.0/0.3.1/0.3.2/0.4.0 upgraded in place (paused: README)
  gates.py backups <new.apk> --old DIR          gate 3: new backup → each old build; old backups → new

DIR holds the released text APKs (`gh release download v0.3.x -p '*text-arm64*'`; those versions had
one). Options: --port (5560: emulator-5560, clear of a UAT run's 5554/5556; `uat.py --devices auto`
can reach it, and a gate does not take an emulator it did not start), --keep (leave it up, still the
gates': the next gate starts it again, unless a uat.py run took it since).
Gate 5 (start, key latency, memory) is gone: uat/tools/perf.py measures that (DECISIONS.md B14).

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
import subprocess
import sys
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
UAT_DIR = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(UAT_DIR, "tools"))
import uat  # noqa: E402

RELEASED = "io.github.sraw.youmo"
VERSIONS = ("v0.3.0", "v0.3.1", "v0.3.2", "v0.4.0")
# on a device a gate brought up: up() kills only such an emulator, and a uat.py run on it drops the mark
GATE_MARK = uat.GATE_MARK


def released_before_pack_page(version):
    """a released build before 0.6.0: no 资源包 page (no pack imported before its first start) and its data where
    0.3/0.4 kept it: phase.py's --old, for gate 3's imports into it and its checks alike"""
    return tuple(int(n) for n in version.lstrip("v").split(".")) < (0, 6, 0)


# old builds whose backups this build refuses (exception_user_data_too_old: 0.3.0's learning is not read)
REFUSED = ("v0.3.0",)
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
        self.serial = f"emulator-{a.port}"
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        self.dir = os.path.join(UAT_DIR, "runs", "gates", f"{stamp}-{name}")
        os.makedirs(self.dir, exist_ok=True)
        self.name = name
        self.rows = []
        self.new = new_apk
        self.new_pkg, self.new_ver = badging(new_apk) if new_apk else (None, None)

    def up(self):
        # an emulator left up would bring the last gate's packages and marks: restarted from the snapshot, if a gate's
        d = uat.Device(self.a.port)
        if uat.avd_name(d) == uat.AVD:
            if d.sh(f"cat {GATE_MARK} 2>/dev/null").strip() != "gate":
                sys.exit(f"{self.serial} is up and not a gate's (a uat.py run's?): another --port, "
                         f"or `uat/tools/uat.py down --port {self.a.port}` once it is not in use")
            uat.down(self.a.port)
        uat.up(self.a.port)
        # the snapshot is started read-only: the mark is this emulator's only, gone when it is killed
        uat.Device(self.a.port).sh(f"echo gate > {GATE_MARK}")

    def down(self):
        if not self.a.keep:
            uat.down(self.a.port)

    def uninstall(self, *pkgs):
        for p in pkgs:
            subprocess.run(["adb", "-s", self.serial, "uninstall", p], capture_output=True)

    def phase(self, pkg, *args):
        """phase.py as the build of pkg; (ok, its last line)"""
        env = dict(os.environ, UAT_BUILD=build_of(pkg))
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

    def checked(self, scenario, pkg, version=None, old=False):
        """version: gate 2's fixture;
        old: a released v0.3.x build is installed, its files still on the external storage"""
        out = os.path.join(self.dir, f"{scenario}.json")
        ok, last = self.phase(pkg, "check", out, *([version] if version else []), *(["--old"] if old else []))
        items = json.load(open(out)) if os.path.exists(out) else []
        self.rows.append((scenario, ok and items and all(i["ok"] is not False for i in items), last, items))

    def blocked(self, scenario, last):
        self.rows.append((scenario, False, last, []))

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
            old = released_before_pack_page(v)
            if ok:
                ok, last = g.phase(RELEASED, "import", z, *(["--old"] if old else []))
            if ok:
                g.checked(f"new → {v}", RELEASED, old=old)
            else:
                g.blocked(f"new → {v}", last)
        g.uninstall(RELEASED)
        # old backups → new build
        for v in VERSIONS:
            print(f"{v} → new", flush=True)
            g.uninstall(g.new_pkg)
            z = retarget(os.path.join(FIXTURES, v, "backup.zip"), os.path.join(g.dir, f"{v}-for-new.zip"), g.new_pkg)
            ok, last = g.phase(g.new_pkg, "install", g.new)
            if ok and v in REFUSED:
                ok, last = g.phase(g.new_pkg, "import-refused", z)
                g.rows.append((f"{v} backup → new (refused)", ok, last, []))
                continue
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
                    "restored into a clean new build; v0.3.0's refused (its learning no longer read), nothing of it restored." + ("" if g.new_pkg == RELEASED else
                                                          " Debug build: zips retargeted between the two ids."))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--port", default="5560", type=uat.emu_port)
    p.add_argument("--keep", action="store_true",
                   help="leave the emulator up, still the gates': the next gate restarts it, unless a uat.py run took it")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("upgrade")
    s.add_argument("new")
    s.add_argument("--old")
    s = sub.add_parser("backups")
    s.add_argument("new")
    s.add_argument("--old", required=True)
    a = p.parse_args()
    ok = {"upgrade": cmd_upgrade, "backups": cmd_backups}[a.cmd](a)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
