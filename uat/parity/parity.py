#!/usr/bin/env python3
"""Parity on a device (fcitx-replacement §7.2): two hosts, native (a debug APK built at the tag
native-last) and Kotlin (this checkout), run the same
key sequences from the same starting state; what the user sees after each step, and every edit the
input method made to the field, written as an output file `ime-eval parity-compare` compares.

  parity.py build-editor                     the test editor APK (uat/parity/editor), as .a and .b
  parity.py prepare --port P --apk <apk>     the editors and <apk> installed, the base state made
                                             (the nine keys and 五笔 added), snapshotted as root
  parity.py run --port P --apk <apk> --host native|kotlin <sequences.jsonl> <out.jsonl>
                [--sample N --seed S | --ids a,b]
                                             <apk> swapped in (adb install -r -d: same package,
                                             same uid), each sequence from the snapshot
  parity.py uat-export <bare.jsonl>          the uat/ scripts' key sequences, no device (sealed by
                                             `ime-eval parity-seal`)
  parity.py dry-run <sequences.jsonl> [--first N]   what run would do on a device, printed, no device

The device is a redroid of uat.py's (UAT_CONTAINERS=<port>:<name> for one of your own). Only `adb shell
input tap/swipe/keyevent`, am/pm/ime and `docker exec` drive it: the keys are tapped where the uat
Dumper reads them, hardware keys are `input keyevent` (never a uinput device on redroid, it reaches
the host). Both APKs are the debug build (io.github.sraw.youmo.debug, debug-signed); a recorded native run
(dev/parity-runs/r3) can stand in for the native APK (record.sh --native-out). Output is kept short (counts and paths).
"""
import argparse
import json
import os
import random
import secrets
import shutil
import subprocess
import sys
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(REPO, "uat", "tools"))
import uat  # noqa: E402

OUT = os.path.join(REPO, "dev", "uat-out", "parity")
EDITORS = ("io.github.sraw.parity.a", "io.github.sraw.parity.b")
EDITOR_CLASS = "io.github.sraw.parity.ParityEditor"
MARK = "io.github.sraw.parity.MARK"
PKG = uat.PKG
SNAP = "/data/local/tmp/parity-snap"
DATA = f"/data/data/{PKG}"
MEDIA = f"/data/media/0/Android/data/{PKG}"
LOG = "files/ic.jsonl"
# a mark that only waits: not a step's (states() leaves it out)
IDLE_MARK = -2

# the space key's description is 空格，<name>: which names each input method (a startswith for the
# nine keys, whose name may be shortened)
IM_LABELS = {"engine-pinyin": ("拼音", False), "engine-t9": ("九键", True), "engine-wubi": ("五笔", False),
             "keyboard-us": ("英文", False)}
# what the name overlay shows: each listed input method's label (Engines.LISTED)
OVERLAYS = {"拼", "双", "九", "五", "倉", "自", "二", "手", "En"}
# the status area's labels of each toggle, either state
TOGGLE_LABELS = {"chttrans": ("简体", "繁體", "繁体"), "punctuation": ("中文标点", "英文标点"), "fullwidth": ("半角", "全角")}
HW = {"Left": "KEYCODE_DPAD_LEFT", "Right": "KEYCODE_DPAD_RIGHT", "BackSpace": "KEYCODE_DEL",
      "Return": "KEYCODE_ENTER"}
# the input methods prepare adds, after pinyin and English (a new install's): the header's ims order
ADDED = ("九键拼音", "五笔")


def newest(names):
    """the newest of SDK version dirs, compared as numbers (36.1.0 after 9.0.0, android-36 after android-9)"""
    return max(names, key=uat.version_key)


def sdk_tool(*parts):
    sdk = os.environ.get("ANDROID_HOME", os.path.expanduser("~/android_sdk"))
    if parts[0] == "build-tools":
        bt = newest(os.listdir(os.path.join(sdk, "build-tools")))
        return os.path.join(sdk, "build-tools", bt, *parts[1:])
    return os.path.join(sdk, *parts)


def build_editor():
    """the editor as two APKs (.a and .b, two apps), debug-signed, with the SDK's tools alone: javac,
    d8, aapt2 link (no resources), zipalign, apksigner. Rebuilt only when its sources changed"""
    src = os.path.join(HERE, "editor")
    newest = max(os.path.getmtime(os.path.join(src, f)) for f in os.listdir(src))
    apks = [os.path.join(OUT, f"editor-{p[-1]}.apk") for p in EDITORS]
    if all(os.path.exists(a) and os.path.getmtime(a) >= newest for a in apks):
        return apks
    sdk = os.environ.get("ANDROID_HOME", os.path.expanduser("~/android_sdk"))
    jar = os.path.join(sdk, "platforms", newest(os.listdir(os.path.join(sdk, "platforms"))), "android.jar")
    work = os.path.join(OUT, "editor-build")
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(os.path.join(work, "classes"))
    run = lambda *a: subprocess.run(a, check=True, capture_output=True)  # noqa: E731
    run("javac", "--release", "11", "-nowarn", "-cp", jar, "-d", os.path.join(work, "classes"),
        os.path.join(src, "ParityEditor.java"))
    classes = [os.path.join(dp, f) for dp, _, fs in os.walk(os.path.join(work, "classes")) for f in fs]
    run(sdk_tool("build-tools", "d8"), "--min-api", "29", "--lib", jar, "--output", work, *classes)
    for pkg, apk in zip(EDITORS, apks):
        base = os.path.join(work, f"{pkg}.base.apk")
        run(sdk_tool("build-tools", "aapt2"), "link", "-o", base, "-I", jar,
            "--manifest", os.path.join(src, "AndroidManifest.xml"), "--rename-manifest-package", pkg,
            "--min-sdk-version", "29", "--target-sdk-version", "34", "--version-code", "1")
        with zipfile.ZipFile(base, "a") as z:
            z.write(os.path.join(work, "classes.dex"), "classes.dex")
        aligned = os.path.join(work, f"{pkg}.aligned.apk")
        run(sdk_tool("build-tools", "zipalign"), "-f", "-p", "4", base, aligned)
        run(sdk_tool("build-tools", "apksigner"), "sign", "--ks", os.path.expanduser("~/.android/debug.keystore"),
            "--ks-pass", "pass:android", "--out", apk, aligned)
    return apks


def build_tapper():
    """Tapper.java to a dex (javac and d8), as uat.py builds its Dumper"""
    dex = os.path.join(OUT, "tapper", "classes.dex")
    src = os.path.join(HERE, "Tapper.java")
    if not os.path.exists(dex) or os.path.getmtime(dex) < os.path.getmtime(src):
        sdk = os.environ.get("ANDROID_HOME", os.path.expanduser("~/android_sdk"))
        jar = os.path.join(sdk, "platforms", newest(os.listdir(os.path.join(sdk, "platforms"))), "android.jar")
        od = os.path.dirname(dex)
        os.makedirs(od, exist_ok=True)
        subprocess.run(["javac", "--release", "11", "-nowarn", "-cp", jar, "-d", od, src], check=True, capture_output=True)
        subprocess.run([sdk_tool("build-tools", "d8"), "--min-api", "29", "--lib", jar, "--output", od,
                        os.path.join(od, "Tapper.class")], check=True, capture_output=True)
    return dex


# HostReplay.TAP_MILLIS: keep the two equal
KEY_GAP = 0.2


class Taps:
    """the Device's taps through Tapper (one app_process for all of them) instead of an `input tap`
    each: the same events, a tenth of the time"""

    def __init__(self, d):
        self.d = d
        d.adb("push", build_tapper(), "/data/local/tmp/parity-tapper.dex")
        old = d.sh("cat /data/local/tmp/parity-tapper.pid 2>/dev/null").strip()
        if old.isdigit():
            d.sh(f"kill {old}")
        self.token = secrets.token_hex(16)
        pid = d.sh(f"CLASSPATH=/data/local/tmp/parity-tapper.dex nohup app_process / Tapper 9311 {self.token} "
                   ">/data/local/tmp/parity-tapper.log 2>&1 & echo $!").strip()
        d.sh(f"echo {pid} > /data/local/tmp/parity-tapper.pid")
        self.fwd = 9320 + int(d.port) - 5555
        d.adb("forward", f"tcp:{self.fwd}", "tcp:9311")
        for _ in range(30):
            if "ready" in d.sh("cat /data/local/tmp/parity-tapper.log"):
                break
            time.sleep(0.2)
        else:
            raise uat.Blocked("Tapper did not start")
        # the seam: uat.Device's helpers (type, press, tap_key) all reach the screen through
        # d.tap/d.taps, so swapping these two on the instance moves every tap onto Tapper
        d.tap = self.tap
        d.taps = self.taps

    def send(self, points, gap):
        import socket
        lines = []
        for i, (x, y) in enumerate(points):
            if i:
                lines.append(f"sleep {int(gap * 1000)}")
            lines.append(f"{x} {y}")
        with socket.create_connection(("127.0.0.1", self.fwd), timeout=30) as so:
            so.sendall(("\n".join([self.token] + lines + ["end"]) + "\n").encode())
            reply = so.makefile("rb").readline().decode(errors="replace").strip()
        # a lost tap would read as a host difference; Blocked gets the run's one retry instead
        if reply != "ok":
            raise uat.Blocked(f"Tapper: {reply or 'no reply'}")

    def tap(self, xy, after=None, owe="tap"):
        self.send([xy], 0)
        time.sleep(uat.PAUSE[owe or "tap"] if after is None else after)
        if owe:
            self.d.owe(owe, after)

    def taps(self, points, gap=None, after=None, owe="taps"):
        # keys of one step KEY_GAP apart whatever the caller asks, as the JVM replay
        # (HostReplay.TAP_MILLIS): with taps this fast the refine budget would be cut short on the
        # device and not on the JVM, a timing difference made by the tool
        if points:
            self.send(points, KEY_GAP)
        # type()/press() pass their own owe: their pause, not taps' (F3961)
        time.sleep(uat.PAUSE[owe or "taps"] if after is None else after)
        if owe:
            self.d.owe(owe, after)


# ---------------------------------------------------------------- the device's state

def snapshot_cmd():
    """as root: the app stopped, its data and its external files directory archived with owners and
    modes, its uid noted"""
    # stopped again right before the tar: the system binds the input method again at once after a
    # force-stop, and a process started in that window could write while it is archived
    return (f"am force-stop {PKG}; rm -rf {SNAP}; mkdir -p {SNAP} && am force-stop {PKG} && "
            f"tar -cpf {SNAP}/data.tar -C /data/data {PKG} && "
            f"if [ -d {MEDIA} ]; then tar -cpf {SNAP}/media.tar -C /data/media/0/Android/data {PKG}; fi; "
            f"stat -c %u {DATA} > {SNAP}/uid && echo snapshot-ok")


def restore_cmd():
    """as root (§7.2's steps): the app stopped, its data and external files put back from the snapshot,
    owned by the app's uid now (an install -r keeps it; a path through an uninstall gives a new one),
    labelled again"""
    return (f"am force-stop {PKG}; test -f {SNAP}/data.tar || exit 3; "
            f"rm -rf {DATA} {MEDIA}; tar -xpf {SNAP}/data.tar -C /data/data && "
            f"if [ -f {SNAP}/media.tar ]; then tar -xpf {SNAP}/media.tar -C /data/media/0/Android/data; fi; "
            f"uid=$(pm list packages -U {PKG} | sed -n 's/.* uid:\\([0-9]*\\).*/\\1/p' | head -1); "
            f"[ -n \"$uid\" ] || exit 4; chown -R $uid:$uid {DATA}; "
            f"if [ -d {MEDIA} ]; then chown -R $uid {MEDIA}; fi; "
            f"restorecon -R {DATA}; if [ -d {MEDIA} ]; then restorecon -R {MEDIA}; fi; "
            # stopped again: a process the system bound during the seconds of rm and tar would keep
            # the last sequence's state in memory, or write over the files just put back
            f"am force-stop {PKG}; echo restore-ok")


def ime_cmd():
    return f"ime enable {uat.IME}; ime set {uat.IME}"


def install(d, apk):
    """the build swapped in place: same package, same signature, so the uid and data stay"""
    d.adb("install", "-r", "-d", apk, check=True, timeout=600)


def profile_cmd():
    return f"cat $(find {MEDIA} {DATA} -path '*/config/profile' 2>/dev/null | head -1) </dev/null 2>/dev/null"


def prepare(d, apk):
    for a in build_editor():
        d.adb("install", "-r", a, check=True, timeout=120)
    for e in EDITORS:
        # the debug idle provider asks for DUMP (a development permission): granted, not given at install
        d.sh(f"pm grant {e} android.permission.DUMP")
    install(d, apk)
    d.sh(f"pm clear {PKG}")
    uat.ensure_ime(d)
    drv = Driver(d)
    drv.editor("text", 0)   # the engine's first load waited for on the letters, in the editor
    uat.ime_ready(d)
    for name in ADDED:
        uat.add_im(d, name)
    d.hide_keyboard()
    # the host writes the profile on its own config thread, which the idle provider does not count:
    # a snapshot taken at once could miss the input methods just added
    for _ in range(50):
        # the host's config dir is getExternalFilesDir (EngineBridge.fcitxHome), files/ only without one
        prof = d.root(profile_cmd())
        if all(f"Name={im}" in prof for im in ("engine-t9", "engine-wubi")):
            break
        time.sleep(0.2)
    else:
        raise uat.Blocked("profile does not list the input methods added")
    d.sh(f"am force-stop {EDITORS[0]}")
    out = d.root(snapshot_cmd())
    if "snapshot-ok" not in out:
        raise uat.Blocked(f"snapshot: {out[-200:]}")
    print(f"snapshot of {PKG} in {SNAP}")


# ---------------------------------------------------------------- one sequence

def find_any(s, labels):
    """the keyboard's first node showing one of these labels"""
    for t in labels:
        n = s.find(text=t, where="ime")
        if n:
            return n
    return None


class Driver:
    """runs sequences on a device: [Device] (uat.py) for the keys and the screen, the editors for the field"""

    def __init__(self, d):
        self.d = d
        self.app = 0   # EDITORS[app] is in front

    def editor(self, kind, app=None):
        """the editor app in front with a new field of this kind"""
        if app is not None:
            self.app = app
        pkg = EDITORS[self.app]
        self.d.sh(f"am start -W -n {pkg}/{EDITOR_CLASS} --es field {kind}")
        self.d._keys = None
        self.d.need_keyboard()

    def mark(self, step):
        """a mark in both editors' logs after the step: the calls before it were the step's; the front
        one's has the field's state"""
        # the front one first: it waits for the input method to be idle
        order = [self.app, 1 - self.app]
        self.d.sh("; ".join(f"am broadcast -p {EDITORS[i]} -a {MARK} --ei step {step} --ez front {str(i == self.app).lower()}"
                            + (f" --es ime {PKG}" if i == self.app else "") + " >/dev/null" for i in order))

    def toggle(self, name):
        """the status area's entry tapped, by either of its labels, as uat's status_toggle does, the
        keys back after"""
        tb = uat.script("05-toolbar")
        tb.status_panel(self.d)
        n = find_any(self.d.screen(), TOGGLE_LABELS[name])
        if not n:
            # the learning entries push fcitx's toggles to a row under the fold
            self.d.swipe((540, 2000), (540, 1650), 300, 0.8)
            n = find_any(self.d.screen(), TOGGLE_LABELS[name])
        if not n:
            raise uat.Blocked(f"状态面板没有 {name}")
        self.d.tap(n.c, 0.8)
        tb.back_to_keys(self.d)

    def idle(self):
        """the input method idle, asked by the front editor (a mark of no step, which states() skips)"""
        self.mark(IDLE_MARK)

    def logs(self):
        return [self.d.sh(f"cat /sdcard/Android/data/{pkg}/{LOG} 2>/dev/null") for pkg in EDITORS]

    def start(self, seq, tries=3):
        """from the snapshot, the field empty: a tap of the IM switch can land on the keyboard that
        comes up under it (a newline in the field before the first step, either build, r3-1074);
        such a start is made again"""
        for _ in range(tries):
            self.start_once(seq)
            before = self.start_calls()
            if before is None:
                return
            print(f"{seq['id']}: the field not empty at the start after {before}: started again", file=sys.stderr)
        raise uat.Blocked("the field is not empty at the start")

    def start_calls(self):
        """None if the front editor's field was empty at mark -1, else the calls made before it"""
        calls = []
        for line in self.logs()[self.app].splitlines():
            if not line.strip():
                continue
            o = json.loads(line)
            if o.get("mark") == -1:
                return None if o.get("text", "") == "" else calls
            if "ic" in o:
                calls.append(o["ic"])
        return None

    def start_once(self, seq):
        d = self.d
        out = d.root(restore_cmd())
        if "restore-ok" not in out:
            raise uat.Blocked(f"restore: {out[-200:]}")
        d.sh(ime_cmd())
        for pkg in EDITORS:
            d.sh(f"am force-stop {pkg}; rm -f /sdcard/Android/data/{pkg}/{LOG}")
        self.editor(seq["field"], 0)
        self.ready()
        self.switch(seq["im"])
        self.idle()
        self.mark(-1)

    def ready(self, timeout=30):
        """until the host has started: the space key names an input method rather than （不可用）. A key
        before it is dropped (fcitx-s3-report), a globe tap too; idle says nothing of it. Measured on
        redroid after the keyboard shows: native 1.7-2.3 s, Kotlin about 5 s"""
        end = time.time() + timeout
        while time.time() < end:
            name = self.d.im_label().split("，", 1)[-1]
            if name and "不可用" not in name:
                self.idle()
                return
            time.sleep(0.2)
        raise uat.Blocked(f"输入法 {timeout}s 内没有就绪：{self.d.im_label()}")

    def switch(self, im):
        label, prefix = IM_LABELS[im]
        pressed = False
        for _ in range(8):
            desc = self.d.im_label()
            name = desc.split("，", 1)[-1]
            if name == label or (prefix and name.startswith(label)):
                self.d._keys = None
                if pressed:
                    self.overlay_gone()
                return
            pressed = True
            self.d.press("globe", after=0.3)
            self.d._keys = None
            # the space key's description is read late at times (fcitx-s3-report SET-08): until it
            # changes, not a fixed pause, or a stale read sends a tap too many
            end = time.time() + 3
            while time.time() < end and self.d.im_label() == desc:
                time.sleep(0.15)
        raise uat.Blocked(f"地球键切不到 {im}：{self.d.im_label()}")

    def overlay_gone(self):
        """fcitx's name overlay (a second after a switch) run out: a step soon after would read it, or
        not, by how fast the build is; and an app switch inside that second leaves it on the bar"""
        end = time.time() + 2.5
        while time.time() < end and self.d.preedit() in OVERLAYS:
            time.sleep(0.1)

    def step(self, op, arg):
        d = self.d
        if op == "type":
            d.type(arg)
        elif op == "space":
            d.space()
        elif op == "enter":
            d.enter()
        elif op == "bksp":
            d.bksp(int(arg))
        elif op == "pick":
            self.idle()
            n = int(arg)
            bar = d.candidates()
            if len(bar) >= n:
                d.pick(n)
            elif bar:
                # a long sentence fills the bar: the page's nth (the JVM's), from the expanded list
                every = d.all_cands()
                if len(every) >= n:
                    d.pick(every[n - 1])
        elif op == "pickText":
            self.idle()
            try:
                d.pick(arg)
            except uat.Fail:
                pass
        elif op == "hw":
            d.sh(f"input keyevent {HW[arg]}")
        elif op == "ime":
            self.switch(arg)
        elif op == "field":
            self.editor(arg)
        elif op == "app":
            self.editor("text", 1 - self.app)
        elif op == "pause":
            time.sleep(1.0)
        elif op == "toggle":
            self.toggle(arg)
        elif op in ("quick", "unicode"):
            # the 符 key held opens quick input, the 123 key Unicode (uat/11-quick-unicode)
            k = d.key_node("符" if op == "quick" else "123")
            if k is None:
                raise uat.Blocked(f"没有{'符' if op == 'quick' else '123'}键")
            d.hold(k.c, 900)
            d._keys = None
        else:
            raise ValueError(f"no step {op}")

    def run(self, seq):
        """the sequence's states, and the error that stopped it (None if none)"""
        d = self.d
        seen = []   # (candidates, preedit) after each step
        error = None
        try:
            self.start(seq)
            for i, st in enumerate(seq["steps"]):
                self.step(st[0], st[1] if len(st) > 1 else None)
                self.mark(i)   # returns once the input method is idle
                s = d.screen()
                seen.append((d.candidates(s), d.preedit(s)))
        except (uat.Blocked, uat.Fail, subprocess.TimeoutExpired) as e:
            error = f"{type(e).__name__}: {e}"
        return states(seen, self.logs()), error


def states(seen, logs):
    """each step's state: the field from the front editor's mark, the calls between the marks from both"""
    marks = {}   # step -> the front editor's mark: the field's state after it
    calls = {}   # step -> calls made during it
    for log in logs:
        # before an editor's first mark: the sequence's start if that mark is -1 (dropped), else the
        # step that brought the editor up (the other app's), whose mark it is
        step, early = None, []
        for line in log.splitlines():
            if not line.strip():
                continue
            o = json.loads(line)
            if "mark" in o and o["mark"] == IDLE_MARK:
                continue
            if "mark" in o:
                if step is None and o["mark"] >= 0:
                    calls.setdefault(o["mark"], []).extend(early)
                step = o["mark"] + 1
                if o["mark"] >= 0 and o["front"]:
                    marks[o["mark"]] = o
            elif "ic" in o:
                (early if step is None else calls.setdefault(step, [])).append(o["ic"])
    out = []
    for i, (cands, pre) in enumerate(seen):
        if i not in marks:
            break
        m = marks[i]
        st = {"text": m["text"], "sel": m["sel"], "pre": pre, "cands": cands, "ic": calls.get(i, [])}
        # the editor's 10 s wait for idle ran out: this step's state is a timing one
        if m.get("idle") is False:
            st["busy"] = True
        out.append(st)
    return out


# ---------------------------------------------------------------- sequences from the uat scripts

class Unsupported(Exception):
    pass


class Recorder:
    """a uat.py Device stand-in that writes down the keys a case's script sends and answers its reads
    with nothing; any other call (settings, a page, a glide) leaves the case out"""
    READS = ("cands", "all_cands", "candidates", "preedit", "field_text", "expect_field", "idle", "step",
             "shot", "settle", "toolbar", "im_label")

    def __init__(self, im):
        self.steps, self.im = [], im
        self.emu, self.flavor, self.after_case = False, "text", []

    def type(self, keys, after=None):
        for chunk in keys.split(" "):
            if chunk:
                self.steps.append(["type", chunk])
            self.steps.append(["space"])
        self.steps.pop()   # the split added one space too many

    def space(self):
        self.steps.append(["space"])

    def bksp(self, n=1):
        self.steps.append(["bksp", str(n)])

    def enter(self):
        self.steps.append(["enter"])

    def press(self, name, n=1, after=None):
        if name in ("space", "enter"):
            self.steps += [[name]] * n
        elif name == "bksp":
            self.bksp(n)
        elif name in (",", "."):
            self.steps += [["type", name]] * n
        else:
            raise Unsupported(f"press {name}")

    def pick(self, which, s=None, after=0.6):
        self.steps.append(["pick", str(which)] if isinstance(which, int) else ["pickText", which])
        return which if isinstance(which, str) else ""

    def switch_im(self, label):
        for im, (lab, prefix) in IM_LABELS.items():
            if label == lab or (prefix and label.startswith(lab)):
                self.steps.append(["ime", im])
                return
        raise Unsupported(f"switch_im {label}")

    def __getattr__(self, name):
        if name in self.READS:
            return lambda *a, **k: [] if name in ("cands", "all_cands", "candidates") else ""
        raise Unsupported(name)


# the baselines whose field is the test page's text field and whose input method is one of the header's
BASE_IMS = {"B0": "engine-pinyin", "B0-九键": "engine-t9", "B0-五笔": "engine-wubi", "B0-英文": "keyboard-us"}


def uat_export(path):
    uat.load_scripts()
    kept, left = [], 0
    for cid, c in sorted(uat.CASES.items()):
        if c.needs or c.page != "text" or c.base not in BASE_IMS:
            continue
        rec = Recorder(BASE_IMS[c.base])
        mod = sys.modules.get(c.fn.__module__)
        saved = getattr(mod, "check", None)
        if mod is not None:
            mod.check = lambda ok, msg="": None   # the script's own check: its reads are empty here
        try:
            c.fn(rec)
        except (uat.Blocked, uat.Fail):
            # the script's own check on a read, empty here: the keys up to it are the case's
            pass
        except Exception:   # noqa: BLE001 - an unsupported step (a popup, the toolbar, a page): left out
            left += 1
            continue
        finally:
            if mod is not None and saved is not None:
                mod.check = saved
        if rec.steps:
            kept.append({"id": cid, "im": rec.im, "field": "text", "steps": rec.steps})
    with open(path, "w", encoding="utf-8") as f:
        for s in kept:
            f.write(json.dumps(s, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"{len(kept)} uat sequences, {left} cases left out (steps the driver does not replay): {path}")


# ---------------------------------------------------------------- the files

def read_sequences(path):
    with open(path, encoding="utf-8") as f:
        lines = [x for x in f.read().splitlines() if x.strip()]
    return json.loads(lines[0]), [json.loads(x) for x in lines[1:]]


def choose(seqs, sample=None, seed=0, ids=None, shard=None):
    """the sequences picked (by ids, or a seeded sample), then shard "k/n" of them: every nth from
    the kth, so n containers run one choice between them"""
    if ids:
        want = set(ids.split(","))
        seqs = [s for s in seqs if s["id"] in want]
    elif sample and sample < len(seqs):
        picked = set(random.Random(seed).sample(range(len(seqs)), sample))
        seqs = [s for i, s in enumerate(seqs) if i in picked]
    if shard:
        k, n = (int(x) for x in shard.split("/"))
        if not 0 <= k < n:
            raise ValueError(f"shard {shard}: k/n with 0 <= k < n")
        seqs = seqs[k::n]
    return seqs


def merge(paths, out):
    """shards' outputs as one: the first's header, every shard's sequences in order of the shards"""
    lines = []
    for i, p in enumerate(paths):
        with open(p, encoding="utf-8") as f:
            body = [x for x in f.read().splitlines() if x.strip()]
        lines += body if i == 0 else body[1:]
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return len(lines) - 1


def done_ids(path):
    """the ids an output already has, None when there is none to go on from (no file, or no header);
    a last line cut off by a kill is dropped from the file, so its sequence runs again"""
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")
    kept, ids = [], set()
    for x in lines:
        if not x.strip():
            continue
        try:
            o = json.loads(x)
        except ValueError:
            break
        kept.append(x)
        if "id" in o:
            ids.add(o["id"])
    if not kept:
        return None
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(kept) + "\n")
    return ids


def git_commit():
    return subprocess.run(["git", "-C", REPO, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()


def cmd_run(a):
    header, seqs = read_sequences(a.sequences)
    seqs = choose(seqs, a.sample, a.seed, a.ids, a.shard)
    d = uat.Device(a.port)
    install(d, a.apk)
    d.start_dumper()
    Taps(d)
    drv = Driver(d)
    out = dict(header, host=a.host, commit=git_commit(), apk=os.path.basename(a.apk))
    stopped, t0 = 0, time.time()
    done = done_ids(a.out) if a.resume else None
    # written as it goes: a run of hours stopped half-way keeps what it did, and --resume goes on from it
    with open(a.out, "a" if done is not None else "w", encoding="utf-8") as f:
        if done is None:
            f.write(json.dumps(out, ensure_ascii=False, separators=(",", ":")) + "\n")
        else:
            seqs = [s for s in seqs if s["id"] not in done]
        for i, s in enumerate(seqs):
            st, err = drv.run(s)
            if err and err.startswith("Blocked"):
                # the driver could not do a step (a globe tap lost on a loaded host): the sequence
                # again from the snapshot, once, rather than a stop counted against the host
                st, err = drv.run(s)
            o = {"id": s["id"], "states": st}
            if err:
                o["error"] = err
                stopped += 1
            f.write(json.dumps(o, ensure_ascii=False, separators=(",", ":")) + "\n")
            f.flush()
            if (i + 1) % 25 == 0:
                print(f"{i + 1}/{len(seqs)}, {stopped} stopped, {(time.time() - t0) / (i + 1):.1f}s each", flush=True)
    d.stop_dumper()
    print(f"{len(seqs)} sequences on {a.host} in {time.time() - t0:.0f}s, {stopped} stopped: {a.out}")


class Echo:
    """a device that prints what it is told (dry-run): every adb and root command, the taps by key name"""

    def __init__(self):
        self.lines = []
        self.screen_n = 0

    def sh(self, cmd):
        self.lines.append(f"adb shell {cmd}")
        return ""

    def root(self, cmd):
        self.lines.append(f"docker exec <container> sh -c '{cmd[:70]}…'")
        return "restore-ok snapshot-ok"

    def __getattr__(self, name):
        def f(*a, **k):
            self.lines.append(f"{name}{a if a else ''}")
            if name == "im_label":
                return "空格，?"
            if name == "candidates":
                return ["c1", "c2", "c3"]
            return None
        return f


def cmd_dry_run(a):
    header, seqs = read_sequences(a.sequences)
    for s in seqs[:a.first]:
        e = Echo()
        drv = Driver(e)
        drv.switch = lambda im, e=e: e.lines.append(f"globe until 空格，{IM_LABELS[im][0]}")
        drv.start(s)
        for i, st in enumerate(s["steps"]):
            drv.step(st[0], st[1] if len(st) > 1 else None)
            drv.mark(i)
        print(f"== {s['id']} ({s['im']}, {len(s['steps'])} steps): {len(e.lines)} device calls")
        for x in e.lines[:a.lines]:
            print("  " + x)
    print(f"header: engine {header['engine'][:12]}…, data {header['data']}, ims {header['ims']}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("build-editor")
    pr = sub.add_parser("prepare")
    pr.add_argument("--port", required=True)
    pr.add_argument("--apk", required=True)
    r = sub.add_parser("run")
    r.add_argument("--port", required=True)
    r.add_argument("--apk", required=True)
    r.add_argument("--host", required=True, choices=["native", "kotlin"])
    r.add_argument("--sample", type=int)
    r.add_argument("--seed", type=int, default=0)
    r.add_argument("--ids")
    r.add_argument("--shard", help="k/n: this container's part of the sequences chosen")
    r.add_argument("--resume", action="store_true", help="go on from the output's sequences done")
    r.add_argument("sequences")
    r.add_argument("out")
    m = sub.add_parser("merge")
    m.add_argument("out")
    m.add_argument("shards", nargs="+")
    u = sub.add_parser("uat-export")
    u.add_argument("out")
    dr = sub.add_parser("dry-run")
    dr.add_argument("sequences")
    dr.add_argument("--first", type=int, default=2)
    dr.add_argument("--lines", type=int, default=40)
    a = p.parse_args()
    if a.cmd == "build-editor":
        print("\n".join(build_editor()))
    elif a.cmd == "prepare":
        d = uat.Device(a.port)
        d.start_dumper()
        prepare(d, a.apk)
        d.stop_dumper()
    elif a.cmd == "run":
        cmd_run(a)
    elif a.cmd == "merge":
        print(f"{merge(a.shards, a.out)} sequences: {a.out}")
    elif a.cmd == "uat-export":
        uat_export(a.out)
    elif a.cmd == "dry-run":
        cmd_dry_run(a)


if __name__ == "__main__":
    main()
