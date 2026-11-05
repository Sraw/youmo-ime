#!/usr/bin/env python3
"""UAT runner for the Android emulator: groups of cases per baseline, machine checks, contact sheets, any number of devices.

  uat.py up | down [--port P]      an emulator (emulator-P, default the second: 5556) of the AVD youmo
                                   from its snapshot "clean", read-only: every up a clean device
  uat.py emu-up | emu-down         the same on 5554 (the first)
  uat.py up --count N|auto          N emulators (auto: as many as memory allows) on free even ports
  uat.py run [--areas 14,06] [--ids ST-01,PY-02] [--devices 5554,5556|auto] [--flavor text|voice]
                                   auto: start as many as memory allows, split the run, bring them down
  uat.py mark <run> <id> 通过|失败 [note]
  uat.py list [--areas …]          the registered cases, their group and kind

Cases live in uat/scripts/<file stem>.py (one per uat/*.md); the .md stays the source of truth:
a script follows its case's 前提/操作/预期, and the caption under a screenshot is the case's 预期.
Python 3 stdlib + PIL. `adb shell input tap/swipe/motionevent/keyevent/text`, uiautomator,
am/pm/ime/settings drive the device. The devices are emulators (AVD youmo, android-36 google_apis
arm64-v8a: the release ABI, native on Apple silicon; uat/tools/avd.sh makes it; UAT_AVD=youmo29 an
Android 10 one, minSdk, without the test page's browser), VMs with their own
kernel: root over `adb root`, and 17 实体键盘's USB keyboard a uinput one (uat/tools/emu-keyboard.sh),
which is never made anywhere else. The voice cases' microphone is the emulator's gRPC injectAudio
(`Device.say`, `-grpc` from up(); grpcio needed, else those cases are blocked).
Output is kept short on purpose (counts and paths): an agent reading it re-reads its context.
"""
import argparse
import datetime
import importlib.util
import io
import json
import os
import re
import shlex
import subprocess
import sys
import threading
import time
import traceback
import unicodedata
import warnings
import xml.etree.ElementTree as ET

warnings.filterwarnings("ignore", category=DeprecationWarning)

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
UAT = os.path.join(REPO, "uat")
OUT = os.path.join(REPO, "dev", "uat-out")
# UAT_BUILD=release runs the cases on the arm64 release APK (a release's gate: R8's code, its own package)
BUILD = os.environ.get("UAT_BUILD", "debug")
if BUILD not in ("debug", "release"):
    sys.exit(f"UAT_BUILD={BUILD}: debug or release")
PKG = "io.github.sraw.youmo" + (".debug" if BUILD == "debug" else "")
IME = f"{PKG}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
MAIN = f"{PKG}/org.fcitx.fcitx5.android.ui.main.MainActivity"
BROWSER = "org.chromium.webview_shell/.WebViewBrowserActivity"
# the ABI of the APKs installed: the emulator's, which is the release's
ABI = "arm64-v8a"
# adb 37's mDNS bridge aborts on a service name seen on this LAN (the server dies every few seconds):
# a server this tool starts has it off
os.environ.setdefault("ADB_MDNS", "0")
# the devices: emulators of one AVD, each on its console port (serial emulator-<port>). 5554 and 5556
# are the two of a run; a worktree may start its own on another even port up to 5584 (uat.py up --port)
AVD = os.environ.get("UAT_AVD", "youmo")
SNAPSHOT = "clean"   # the AVD's state after uat/tools/avd.sh: zh-CN, three-button nav, the WebView shell
# the default pair; any number runs (--devices 5554,5556,5558,… or auto: device_count from free memory)
PORTS = ("5554", "5556")


def emu_port(port):
    """port as the emulator's console port, or exit: even, 5554-5584 (adb's emulator range)"""
    if not (port.isascii() and port.isdigit() and 5554 <= int(port) <= 5584 and int(port) % 2 == 0):
        sys.exit(f"device {port}: an emulator console port, even, 5554-5584")
    return str(int(port))


AVD_NAME_TIMEOUT = 20   # s: the console answers at once, booting or not


def avd_name(d):
    """the AVD emulator-<port> runs: its name, "" when no emulator answers on the port, None when adb
    hangs (an emulator there that does not answer its console)"""
    try:
        out = d.adb("emu", "avd", "name", timeout=AVD_NAME_TIMEOUT)
    except subprocess.TimeoutExpired:
        return None
    lines = out.strip().splitlines()
    return lines[0].strip() if lines else ""


def not_ours(d, name):
    what = (f"not answering (adb emu avd name hung {AVD_NAME_TIMEOUT} s)" if name is None
            else "not up" if not name else f"AVD {name}")
    sys.exit(f"{d.serial} is {what}: not this runner's ({AVD}), left as it is")


def ours(d):
    """exit unless emulator-<port> runs this AVD: another project's emulator on the port is never
    installed on, cleared or killed (redroid's runner refused other containers the same way)"""
    name = avd_name(d)
    if name != AVD:
        not_ours(d, name)


def version_key(name):
    """an SDK version dir's sort key, as numbers (36.1.0 after 9.0.0, android-36 after android-9); a
    pre-release (36.0.0-rc1: text after the first number) before its release"""
    parts = re.split(r"[.-]", name)
    nums = [i for i, x in enumerate(parts) if x.isdigit()]
    pre = bool(nums) and any(not x.isdigit() for x in parts[nums[0]:])
    return tuple(int(parts[i]) for i in nums), not pre


def http_port(port):
    """host side of `adb reverse tcp:8000` for the first device on this serial port: one per port,
    so runs and gates on different devices never share a server (5554 → 8800, 5556 → 8801). 8800-8815:
    clear of the ports forwarded to this Mac (8780 is one)"""
    return 8800 + (int(port) - 5554) // 2


# how many emulators a run may start (uat.py up --count auto, run --devices auto): the memory free now,
# less some headroom (what runs now is already out of it) and ~7 GB per heavy Gradle job running
# (heavy.sh's slots taken), over one emulator's budget, measured on the Mac mini: four -gpu host
# emulators running cases took the ~23 GB free, ~1.5 GB of it compressed, nothing swapped
# (see uat/README.md「设备」). UAT_MAX_DEVICES caps it; never under 1
GB = 1 << 30
RESERVE = 2 * GB
HEAVY_JOB = 7 * GB
EMU_BUDGET = int(float(os.environ.get("UAT_EMU_GB", "5")) * GB)
HEAVY_SLOTS = "/private/tmp/claude-501/youmo-heavy"


def device_count(free, heavy_jobs, budget=EMU_BUDGET, cap=None, ports=16):
    """emulators to start for [free] bytes available and [heavy_jobs] heavy jobs running: at least 1,
    at most [cap] and the [ports] free"""
    n = (free - RESERVE - heavy_jobs * HEAVY_JOB) // budget
    if cap is not None:
        n = min(n, cap)
    return max(1, min(int(n), ports))


def free_memory():
    """bytes the system can hand out now: vm_stat's free, inactive and speculative pages"""
    out = subprocess.run(["vm_stat"], capture_output=True, text=True).stdout
    page = int(re.search(r"page size of (\d+)", out).group(1))
    pages = {k: int(v) for k, v in re.findall(r"Pages (free|inactive|speculative):\s+(\d+)", out)}
    return sum(pages.values()) * page


def heavy_jobs():
    """heavy.sh slots held now (each a lockf lock on a slot file)"""
    import fcntl
    n = 0
    for f in (os.listdir(HEAVY_SLOTS) if os.path.isdir(HEAVY_SLOTS) else []):
        try:
            fd = os.open(os.path.join(HEAVY_SLOTS, f), os.O_RDONLY)
        except OSError:
            continue
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            fcntl.flock(fd, fcntl.LOCK_UN)
        except OSError:
            n += 1
        finally:
            os.close(fd)
    return n


def free_ports(taken, n):
    """the first [n] console ports, 5554, 5556, … up to 5584, with neither port of the pair in [taken]
    (ports adb lists or something listens on: another worktree's or project's emulator is skipped)"""
    out = []
    for p in range(5554, 5586, 2):
        if p not in taken and p + 1 not in taken:
            out.append(str(p))
        if len(out) == n:
            break
    return out


def taken_ports():
    """console/adb ports in use: emulators adb lists, and anything listening on 127.0.0.1 there"""
    import socket
    taken = {int(m) for m in re.findall(r"emulator-(\d+)",
                                        subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout)}
    for p in range(5554, 5586):
        s = socket.socket()
        s.settimeout(0.2)
        if s.connect_ex(("127.0.0.1", p)) == 0:
            taken.add(p - p % 2)
        s.close()
    return taken


def auto_ports(count="auto"):
    """console ports for [count] new emulators ("auto": as many as memory allows), printing the sum"""
    taken = taken_ports()
    room = len(free_ports(taken, 16))
    if count == "auto":
        cap = int(os.environ["UAT_MAX_DEVICES"]) if os.environ.get("UAT_MAX_DEVICES") else None
        free, jobs = free_memory(), heavy_jobs()
        n = device_count(free, jobs, cap=cap, ports=room)
        print(f"{n} emulators: {free / GB:.1f} GB free - {RESERVE // GB} reserve - {jobs} heavy jobs x "
              f"{HEAVY_JOB // GB} GB, / {EMU_BUDGET / GB:.1f} GB each" + (f", cap {cap}" if cap else ""))
    else:
        n = int(count)
    ports = free_ports(taken, n)
    if len(ports) < n:
        sys.exit(f"only {len(ports)} console ports free of {n}")
    return ports
CLIPS = os.path.join(OUT, "voice-clips")
# host side of `adb reverse tcp:8000`: one per first device, so two runs at once (on 5554 and on 5556,
# from two checkouts) each serve their own fixtures rather than the other's
HTTP_PORT = int(os.environ.get("UAT_HTTP_PORT", "0")) or None
# the sheets' captions: a CJK font (it draws no Latin there) and a Latin one, the first present
FONT = next((f for f in ("/System/Library/Fonts/Hiragino Sans GB.ttc", "/System/Library/Fonts/STHeiti Medium.ttc",
                         "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf") if os.path.exists(f)), None)
FONT_LATIN = next((f for f in ("/System/Library/Fonts/Helvetica.ttc", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
                   if os.path.exists(f)), None)


# The pause after a touch. It was all slept after every touch (OLD); now a short part after it
# (PAUSE) spaces the touches, and the rest is owed only by the next read of the screen (Device.screen):
# a field read right after typing still sees what the old pause let it see (a busy host delays a web
# field's text: PY-01 read 「你抱」 at 0.25 s under a load of 20), while touches with no read between
# them, and waits that poll for their outcome (goto, choose), do not pay it. An explicit after= is
# slept as given. UAT_SLOW=1 sleeps the old pauses in full after each touch
SLOW = os.environ.get("UAT_SLOW") == "1"
OLD = {"tap": 0.3, "taps": 0.4, "swipe": 0.4, "type": 0.5, "press": 0.5, "tap_text": 0.8, "back": 0.8}
PAUSE = OLD if SLOW else {"tap": 0.15, "taps": 0.2, "swipe": 0.2, "type": 0.25, "press": 0.25, "tap_text": 0.3, "back": 0.4}


class Fail(Exception):
    pass


class Blocked(Exception):
    pass


class Skip(Exception):
    pass


class Elsewhere(Exception):
    """the case has its own place to run (the emulator, the voice build, a release by hand, a real
    phone): listed as 在别处跑 apart from the run's counts, not as 跳过 here"""
    pass


# needs kinds that name where a case runs instead, in place()'s order; unmet, tally and counts_line
# read it too. (There was an "emu" kind while the runs were on redroid: the hardware keyboard needs the
# emulator's own kernel; every device is an emulator now)
ELSEWHERE = {"jvm": "JVM", "release": "发版时", "manual": "真机或手工", "voice": "语音版"}
# when a case of an ELSEWHERE kind runs on this device and build after all (a kind not here never does).
# release,scripted: scripted but too long for every run (a big lexicon), so only in a release's own run
# (UAT_BUILD=release)
RUNS_HERE = {"voice": lambda d, kinds: d.flavor == "voice",
             "release": lambda d, kinds: "scripted" in kinds and BUILD == "release"}
# what a case in the .md with no script is registered as: tally reports each
NO_SCRIPT = "block:没有脚本"


# ---------------------------------------------------------------- the case registry

CASES = {}


class Case:
    def __init__(self, fn, id, base, page, mutates, clean, visual, needs, heavy=False):
        self.fn, self.id, self.base, self.page, self.heavy = fn, id, base, page, heavy
        self.mutates, self.clean, self.visual, self.needs = mutates, clean, visual, needs
        self.area = ""
        self.title = self.expect = ""

    def unmet(self, d):
        """(Skip or Blocked, why) when this device or build cannot run the case, else None: checked
        before the reset, so a case for the emulator or the voice build costs nothing elsewhere"""
        if not self.needs:
            return None
        kinds, why = self.needs.split(":", 1)
        kinds = kinds.split(",")
        if "skip" in kinds:
            return Skip, why
        if "block" in kinds:
            return Blocked, why
        for k in ELSEWHERE:
            if k not in kinds or RUNS_HERE.get(k, lambda d, kinds: False)(d, kinds):
                continue
            if k == "voice":
                return Elsewhere, f"{ELSEWHERE[k]}：文字版没有语音输入（--flavor voice 装语音版再跑）"
            if k == "release":
                where = "发版时脚本跑（UAT_BUILD=release）" if "scripted" in kinds else "发版时手动"
                return Elsewhere, f"{where}：{why}"
            return Elsewhere, f"{ELSEWHERE[k]}：{why}"
        return None

    def heavy_here(self):
        """whether this run may run the case at all, before a device is picked: a heavy case
        that runs elsewhere goes with its group instead of waiting for every device to go idle only to be
        listed as 在别处跑. The release,scripted ones as RUNS_HERE has them: BUILD=release; any other
        ELSEWHERE kind counts as elsewhere (it would then run with its group, not alone)"""
        where = place(self.needs)
        if where == "release":
            return "scripted" in self.needs.split(":", 1)[0].split(",") and BUILD == "release"
        return where == "device"

    @property
    def group(self):
        return f"{self.base}·{self.page}" if self.page else self.base

    def order(self):
        # read-only first; then cases that need no learning before them; then ones that only learn
        # from what they commit; last the ones that change settings or words on purpose
        if not self.mutates:
            return 0
        if self.mutates == "learn":
            return 1 if self.clean else 2
        return 3


def case(id, base="B0", page=None, mutates=False, clean=False, visual=False, needs=None, heavy=False):
    """mutates: False (no commit, nothing changed), "learn" (commits text the engine may learn
    from), True (changes settings, words, the system). clean: the case needs no learning before it.
    visual: part of the 预期 is only seen on a screenshot -> 待看图 unless a machine check failed.
    needs: "skip:<why>" / "block:<why>" when the emulator cannot run it at all; "voice:<why>" for the
    voice build only; "release:<why>" run by hand at a release ("release,scripted:<why>": scripted,
    run only when UAT_BUILD=release), "manual:<why>" on a real phone, "jvm:<tests>" asserted by JVM tests carrying its @Uat (`uat.py jvm`
    records it from those; the Class.method names, separated by 、, must each carry it too): those four are 在别处跑 where unmet, no reset. heavy: the case loads the host (a big
    lexicon compiled, an upgrade, the heap measured): run last, alone, with the other devices idle,
    so its load does not break the timing (long presses, quick taps) of a case beside it."""
    if page is None:
        page = "设置" if base == "B0-设置" else "text"

    def deco(fn):
        CASES[id] = Case(fn, id, base, page, mutates, clean, visual, needs, heavy)
        return fn
    return deco


def parse_md(path):
    """{id: (title, base text, expected text)} from a uat/*.md"""
    out, cur = {}, None
    for line in open(path, encoding="utf-8"):
        m = re.match(r"### (\S+) \[P\d\] (.*)", line)
        if m:
            cur = m.group(1)
            out[cur] = [m.group(2).strip(), "", ""]
        elif cur and line.startswith("- **前提**"):
            out[cur][1] = line.split("：", 1)[-1].strip()
        elif cur and line.startswith("- **预期**"):
            out[cur][2] = line.split("：", 1)[-1].strip()
    return out


_LOADING = threading.RLock()   # two device threads must not see a module half-executed


def script(stem):
    """another case script's module (its helpers), e.g. script("13-words"): loaded once, by
    load_scripts or here"""
    name = f"uat_{stem.replace('-', '_')}"
    with _LOADING:
        if name not in sys.modules:
            load_scripts(files=[f"{stem}.py"])
        return sys.modules[name]


def load_scripts(areas=None, files=None):
    with _LOADING:
        _load_scripts(areas, files)


def _load_scripts(areas, files):
    files = files or sorted(f for f in os.listdir(os.path.join(UAT, "scripts")) if re.match(r"\d\d-.*\.py$", f))
    for f in files:
        if areas and f[:2] not in areas:
            continue
        if f"uat_{f[:-3].replace('-', '_')}" in sys.modules:
            continue
        spec = importlib.util.spec_from_file_location(f"uat_{f[:-3].replace('-', '_')}",
                                                      os.path.join(UAT, "scripts", f))
        mod = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = mod   # a script may use another's helpers (uat_13_words …)
        before = set(CASES)
        try:
            spec.loader.exec_module(mod)
        except BaseException:
            sys.modules.pop(spec.name, None)   # not cached half-done: the next load retries
            raise
        for cid in set(CASES) - before:
            CASES[cid].area = f[:2]
        md = parse_md(os.path.join(UAT, f[:-3] + ".md"))
        for cid in md:
            if cid not in CASES:
                # a case in the .md with no script would not show in a run at all and look covered
                CASES[cid] = Case(lambda d: None, cid, "B0", "text", False, False, False, NO_SCRIPT)
                CASES[cid].area = f[:2]
        for c in list(CASES.values()):
            if c.id in md:
                c.title, _, c.expect = md[c.id]


# ---------------------------------------------------------------- one device

class Node:
    __slots__ = ("text", "desc", "rid", "cls", "pkg", "b", "checked", "focused", "selected",
                 "clickable", "parent", "kids", "enabled", "scrollable", "wtype")

    def __init__(self, e, parent):
        # an empty field reports its hint as its text
        # fields.html's placeholder is zero-width spaces, read as text where the hint is not told apart
        self.text = "" if e.get("hint") == "true" else (e.get("text") or "").replace("\u200b", "")
        self.desc = e.get("content-desc") or ""
        self.rid = (e.get("resource-id") or "").split("/")[-1]
        self.cls = (e.get("class") or "").split(".")[-1]
        self.pkg = e.get("package") or ""
        x0, y0, x1, y1 = map(int, re.findall(r"-?\d+", e.get("bounds") or "[0,0][0,0]"))
        self.b = (x0, y0, x1, y1)
        self.checked = e.get("checked") == "true"
        self.focused = e.get("focused") == "true"
        self.selected = e.get("selected") == "true"
        self.clickable = e.get("clickable") == "true"
        self.enabled = e.get("enabled") == "true"
        self.scrollable = e.get("scrollable") == "true"
        self.parent, self.kids = parent, []

    @property
    def c(self):
        return ((self.b[0] + self.b[2]) // 2, (self.b[1] + self.b[3]) // 2)

    def walk(self):
        yield self
        for k in self.kids:
            yield from k.walk()

    def texts(self):
        return [n.text for n in self.walk() if n.text]

    def row(self):
        """the clickable ancestor (a preference row, a list item)"""
        n = self
        while n.parent is not None and not n.clickable:
            n = n.parent
        return n

    def __repr__(self):
        return f"<{self.cls} {self.text or self.desc or self.rid} {self.b}>"


class Screen:
    def __init__(self, xml):
        self.nodes = []
        root = ET.fromstring(xml[xml.index("<"):])

        def build(e, parent, wtype):
            for k in e:
                if k.tag == "node":
                    if k.get("visible") == "false":
                        continue
                    n = Node(k, parent)
                    n.wtype = wtype
                    self.nodes.append(n)
                    if parent:
                        parent.kids.append(n)
                    build(k, n, wtype)
                else:
                    build(k, parent, k.get("type", wtype))
        build(root, None, None)

    def ime(self):
        return [n for n in self.nodes if n.pkg == PKG and self._in_ime(n)]

    @staticmethod
    def _in_ime(n):
        if n.wtype is not None:
            # AccessibilityWindowInfo.TYPE_INPUT_METHOD: Dumper writes the number, `uiautomator
            # dump --windows` (the fallback) the name
            return n.wtype in ("2", "TYPE_INPUT_METHOD")
        while n.parent is not None:
            n = n.parent
        return any(k.rid == "inputArea" for k in n.walk())

    def app(self):
        """nodes of the app window(s): not the keyboard, not the status bar"""
        return [n for n in self.nodes if n.pkg != "com.android.systemui" and not
                (n.pkg == PKG and self._in_ime(n))]

    def find(self, text=None, desc=None, rid=None, contains=None, where="app"):
        pool = self.app() if where == "app" else self.ime() if where == "ime" else self.nodes
        for n in pool:
            if text is not None and n.text != text:
                continue
            if desc is not None and n.desc != desc:
                continue
            if rid is not None and n.rid != rid:
                continue
            if contains is not None and contains not in n.text and contains not in n.desc:
                continue
            return n
        return None

    def texts(self, where="app"):
        pool = self.app() if where == "app" else self.ime() if where == "ime" else self.nodes
        return [n.text for n in pool if n.text] + [n.desc for n in pool if n.desc]


_DUMPER_LOCK = threading.Lock()


def build_dumper():
    """Dumper.java to a dex, once for all device threads (javac and d8 into one directory)"""
    dex = os.path.join(OUT, "dumper", "classes.dex")
    src = os.path.join(UAT, "tools", "Dumper.java")
    with _DUMPER_LOCK:
        if not os.path.exists(dex) or os.path.getmtime(dex) < os.path.getmtime(src):
            sdk = os.environ.get("ANDROID_HOME", os.path.expanduser("~/android_sdk"))
            jar = sorted(os.listdir(os.path.join(sdk, "platforms")))[-1]
            jar = os.path.join(sdk, "platforms", jar, "android.jar")
            d8 = os.path.join(sdk, "build-tools", sorted(os.listdir(os.path.join(sdk, "build-tools")))[-1], "d8")
            od = os.path.dirname(dex)
            os.makedirs(od, exist_ok=True)
            subprocess.run(["javac", "--release", "11", "-nowarn", "-cp", jar, "-d", od, src],
                           check=True, capture_output=True)
            subprocess.run([d8, "--min-api", "30", "--lib", jar, "--output", od,
                            os.path.join(od, "Dumper.class")], check=True, capture_output=True)
    return dex


class Device:
    def __init__(self, port, run=None, flavor="text"):
        self.port = str(port)
        self.flavor = flavor   # the build under test (--flavor): voice-only cases, SET-01's reinstall
        self.serial = f"emulator-{self.port}"
        self.run = run
        self.case = None
        self._keys = None
        self._plugged = None   # the USB keyboard plugged in, as kb up/down left it; None: not known yet
        # unique page URLs across runs: WebView caches a URL it loaded before (heuristic freshness)
        self.reload_n = int(time.time() * 1000) * 10 + int(port) % 10
        self.page = None
        self.host = None   # the test host's extras while it is the field (open_host)
        self._step = None
        self.after_case = []
        # scrolled down since the last scroll to the top (tap_text); known to be at the top
        self._scrolled, self._at_top = False, False
        self._quiet_at = 0.0   # a read waits till then (owe)
        self.fwd = None        # the host port forwarded to Dumper while it runs (start_dumper)
        self._kb_at = None     # the keyboard's bounds once settled (keys)
        self._tapped_on = None   # the screen's texts as tap_text tapped (goto waits for a change)

    # --- adb
    def adb(self, *args, binary=False, check=False, timeout=60):
        r = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True,
                           text=not binary, timeout=timeout)
        if check and r.returncode:
            raise Blocked(f"adb {' '.join(args)}: {(r.stderr or '')[:200]}")
        return r.stdout

    def sh(self, cmd):
        return self.adb("shell", cmd)

    def dpx(self):
        """Pixels per dp, from the device's density."""
        return int(self.sh("wm density").split()[-1]) / 160

    def ime_touchable_region(self):
        """The input method window's touchable region from dumpsys input, as (left, top, right, bottom)
        rectangles: where a touch goes to the keyboard rather than the app under it"""
        for line in self.sh("dumpsys input").splitlines():
            if "InputMethod" in line and "touchableRegion=" in line and "InputMethodManager" not in line:
                part = line.split("touchableRegion=", 1)[1]
                # "[0,1370][100,1436]|[0,1436][1080,2340], ownerPid=…": rectangles as [l,t][r,b], joined by |
                return [tuple(map(int, m)) for m in re.findall(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", part.split(" ", 1)[0])]
        return []

    def screen_h(self):
        """The screen's height in pixels in its natural (portrait) orientation, from wm size (an override wins); not the rotated height."""
        return int(self.sh("wm size").strip().splitlines()[-1].split()[-1].split("x")[1])

    def root(self, cmd):
        return self.sh(cmd)   # adbd runs as root (up)

    # --- the emulator's hardware keyboard and microphone
    def kb(self, *args):
        """uat/tools/emu-keyboard.sh up|down|type …|key …|combo …: the script refuses anything but an
        emulator"""
        r = subprocess.run([os.path.join(UAT, "tools", "emu-keyboard.sh"), *args], capture_output=True,
                           text=True, env={**os.environ, "ANDROID_SERIAL": self.serial}, timeout=60)
        if r.returncode:
            raise Blocked(f"emu-keyboard.sh {' '.join(args)}: {(r.stderr or r.stdout).strip()[:200]}")
        self._keys = None
        if args[0] in ("up", "down"):
            self._plugged = args[0] == "up"
        return r.stdout

    def kb_plugged(self):
        """asked of the device once, then known from kb up/down (every field's focus asks)"""
        if self._plugged is None:
            self._plugged = "Test USB Keyboard" in self.sh("dumpsys input | grep -m1 'Test USB Keyboard'")
        return self._plugged

    def say(self, clip, wait=True):
        """play dev/uat-out/voice-clips/<clip>.wav into the emulator's microphone (the emulator's gRPC
        injectAudio, Mic below); wait=False: in the background (returns the player: poll/wait/kill)"""
        f = os.path.join(CLIPS, f"{clip}.wav")
        if not os.path.exists(f):
            raise Blocked(f"没有 {f}：先跑 uat/tools/voice-clips.py")
        need_mic()
        # the emulator (37.2) segfaults (audio_forwarder_enable) on an injectAudio stream that starts
        # with nothing recording in the guest, or as the recording is only just opened (VO-28: said at
        # the press); so the stream waits till the app's AudioRecord is MIC_SETTLE_S old
        for _ in range(50):
            age = self.recording()
            if age is not None:
                break
            time.sleep(0.1)
        else:
            raise Blocked("设备上没有在录音（AudioRecord）：没把片段放进麦克风（空放会让模拟器崩溃）")
        time.sleep(max(0.0, MIC_SETTLE_S - age))
        p = Mic(grpc_port(self.port), f)
        if wait:
            p.wait(30)
        return p

    def recording(self):
        """seconds the active AudioRecord has been recording (dumpsys audio: the monitor's active riid,
        its first event against the device's clock), None if none is"""
        return record_age(self.sh("date +'%m-%d %H:%M:%S.%N'; dumpsys audio | sed -n "
                                  "'/^RecordActivityMonitor dump time/,/^Events log: recording activity/p;"
                                  "/^Events log: recording activity/,/^$/p'").splitlines())

    # --- reading the screen
    # the dump server (Dumper.java): one UiAutomation connection kept open, ~0.1 s a dump
    def start_dumper(self):
        dex = build_dumper()
        self.stop_dumper()
        self.adb("push", dex, "/data/local/tmp/uat-dumper.dex")
        pid = self.sh("CLASSPATH=/data/local/tmp/uat-dumper.dex nohup app_process / Dumper 9301 "
                      ">/data/local/tmp/uat-dumper.log 2>&1 & echo $!").strip()
        self.sh(f"echo {pid} > /data/local/tmp/uat-dumper.pid")
        self.fwd = 9300 + int(self.port) - 5555
        self.adb("forward", f"tcp:{self.fwd}", "tcp:9301")
        for _ in range(30):
            if "ready" in self.sh("cat /data/local/tmp/uat-dumper.log"):
                return True
            time.sleep(0.2)
        return False

    def stop_dumper(self):
        pid = self.sh("cat /data/local/tmp/uat-dumper.pid 2>/dev/null").strip()
        if pid.isdigit():
            self.sh(f"kill {pid}; rm -f /data/local/tmp/uat-dumper.pid")
        self.fwd = None

    def _fast_dump(self):
        import socket
        with socket.create_connection(("127.0.0.1", self.fwd), timeout=10) as so:
            buf = b""
            while True:
                x = so.recv(1 << 16)
                if not x:
                    break
                buf += x
        return buf.decode("utf-8")

    def owe(self, kind, after):
        """after a touch slept PAUSE[kind] (after is None: the default): the rest of the old pause is
        owed by the next read"""
        if after is None:
            self._quiet_at = max(self._quiet_at, time.time() + OLD[kind] - PAUSE[kind])

    def settle(self):
        """sleep what the last touches owe; every read of the screen (a dump or a screenshot) first"""
        wait = self._quiet_at - time.time()
        if wait > 0:
            time.sleep(wait)

    def screen(self):
        self.settle()
        if self.fwd:
            for _ in range(3):
                try:
                    x = self._fast_dump()
                    if "<hierarchy" in x:
                        return Screen(x)
                except OSError:
                    pass
                time.sleep(0.3)
            if self.start_dumper():
                try:
                    x = self._fast_dump()
                    if "<hierarchy" in x:
                        return Screen(x)
                except OSError:
                    pass
            self.stop_dumper()   # the slow way from here on
        # `uiautomator dump`: ~2 s a read, and without Dumper's hint/visible attributes, so an empty
        # field showing its hint reads as that text (the app's dialogs, search boxes: fields.html's
        # fields have none) and hidden nodes count as shown. Checks on those are unreliable here.
        for _ in range(4):
            x = self.adb("exec-out", "sh", "-c",
                         "uiautomator dump --windows /sdcard/w.xml >/dev/null && cat /sdcard/w.xml")
            if "<hierarchy" in x:
                return Screen(x)
            time.sleep(0.5)
        raise Blocked("uiautomator dump failed")

    def idle(self, timeout=10):
        """wait till the debug build says it is idle (UatIdleProvider: no fcitx job waiting, no refine
        due, no background work, the main thread's queue empty), twice running: a key `input` sent
        may not have reached the input method at the first. Instead of reading what shows the
        moment it is there, which a refine or a late background result changes after (Espresso's
        IdlingResource). The release build has no provider and is taken as idle; a debug build still
        busy at timeout too, the read then goes on as before. A query with no answer (an adb error,
        the provider not yet published after a reinstall or pm clear) is not idle: tried again"""
        if BUILD == "release":
            return
        self.settle()
        # both reads in one shell: each adb shell plus content's app_process costs about a second
        q = f"content query --uri content://{PKG}.uat.idle"
        end = time.time() + timeout
        while time.time() < end:
            if self.sh(f"{q}; sleep 0.1; {q}").count("idle=1") >= 2:
                return
            time.sleep(0.1)

    def candidates(self, s=None):
        s = s or self.screen()
        bar = s.find(rid="candidate_view", where="ime")
        if not bar:
            return []
        out = []
        for item in sorted(bar.kids, key=lambda n: n.b[0]):
            t = [n.text for n in item.walk() if n.text]
            out.append(t[0] if t else "")
        return out

    def code_row(self, s=None):
        """the composing row's text (its left part, code_row_text), None when it is not up. A build
        from before the id has the row's one HorizontalScrollView; the syllable strip beside it
        (code_row_syllables) scrolls too, so the id is looked for first"""
        s = s or self.screen()
        return s.find(rid="code_row_text", where="ime") or \
            next((n for n in s.ime() if n.cls == "HorizontalScrollView"), None)

    def preedit(self, s=None):
        row = self.code_row(s)
        t = row.texts() if row else []
        return t[0] if t else ""

    def syllables(self, s=None):
        """the syllable strip on the composing row's right (the 14 keys), left to right: [] when hidden"""
        s = s or self.screen()
        strip = s.find(rid="code_row_syllables", where="ime")
        if not strip:
            return []
        return [n.text for n in sorted(strip.walk(), key=lambda n: n.b[0]) if n.text]

    def syllable_tap(self, text, s=None):
        """tap the syllable [text] on the strip"""
        s = s or self.screen()
        strip = s.find(rid="code_row_syllables", where="ime")
        n = next((n for n in strip.walk() if n.text == text), None) if strip else None
        if not n:
            raise Blocked(f"音节条上没有 {text}：{self.syllables(s)}")
        self.tap(n.c, 0.6)

    def field(self, s=None):
        """the focused input field (a web field or the app's own EditText), None when none"""
        s = s or self.screen()
        for n in s.app():
            if n.focused and "Edit" in n.cls and n.rid != "url_field":
                return n
        return None

    def field_text(self, s=None):
        f = self.field(s)
        return f.text if f else None

    def wait(self, pred, timeout=20, step=0.3, what="the screen"):
        end = time.time() + timeout
        s = None
        while time.time() < end:
            s = self.screen()
            r = pred(s)
            if r:
                return r
            time.sleep(step)
        raise Blocked(f"timed out waiting for {what}")

    def step(self, name):
        """name the stage a long case is in: a failure or block from here on says which step it was
        (a merged case keeps every check of the cases merged into it, so its message must say where)"""
        self._step = name

    def expect_field(self, want, timeout=3.0, msg=None):
        """the focused field's text, polled until it is want (a str, or a predicate on the text) or
        timeout: instead of a fixed sleep before reading it. Fail with msg (its literal "{got}" is
        replaced by the text there at the end), or with what was there"""
        ok = want if callable(want) else (lambda t: t == want)
        self.idle()
        end = time.time() + timeout
        while True:
            got = self.field_text()
            # no focused field is not an empty one: the focus gone or the keyboard closed fails
            if got is not None and ok(got):
                return got
            if time.time() >= end:
                if got is None:
                    raise Fail("没有获得焦点的输入框")
                # msg may name what was there as {got}: read at the failure, not before the wait
                raise Fail(msg.replace("{got}", got) if msg else
                           f"框里是「{got}」，不是「{want}」" if isinstance(want, str) else f"框里是「{got}」")
            time.sleep(0.15)

    def wait_text(self, text, timeout=15, where="app", contains=False):
        return self.wait(lambda s: s.find(contains=text, where=where) if contains
                         else s.find(text=text, where=where) or s.find(desc=text, where=where),
                         timeout, what=f"「{text}」")

    # --- touching
    def tap(self, xy, after=None, owe="tap"):
        self.sh(f"input tap {xy[0]} {xy[1]}")
        # PAUSE of its own kind: tap_text's default is its own 0.3 s, and only a default after owes OLD's rest
        time.sleep(PAUSE[owe or "tap"] if after is None else after)
        if owe:
            self.owe(owe, after)

    def taps(self, points, gap=0.05, after=None, owe="taps"):
        """owe, here and in tap and tap_text: the PAUSE/OLD kind the touch sleeps and owes, or None
        when the caller polls for what the touch brings"""
        cmd = f"; sleep {gap}; ".join(f"input tap {x} {y}" for x, y in points)
        if cmd:
            self.sh(cmd)
        time.sleep(PAUSE[owe or "taps"] if after is None else after)
        if owe:
            self.owe(owe, after)

    def swipe(self, a, b, ms=300, after=None):
        self.sh(f"input swipe {a[0]} {a[1]} {b[0]} {b[1]} {ms}")
        self._at_top = False   # a script's own swipe may have scrolled the page; scroll() sets it after
        time.sleep(PAUSE["swipe"] if after is None else after)
        self.owe("swipe", after)

    def hold(self, xy, ms):
        self.swipe(xy, xy, ms)

    def tap_drift(self, xy, dx=2, dy=1, ms=60, after=None):
        """a tap whose finger moves (dx, dy) before the lift, all in one input swipe: DOWN, a few
        MOVE, UP within ms, short of a long press. input tap sends DOWN and UP alone"""
        self.swipe(xy, (xy[0] + dx, xy[1] + dy), ms, after=after)

    def touch(self, *steps):
        """('down', x, y) / ('move', x, y) / ('up', x, y) / ('sleep', s), one shell; the finger can
        stay down across a dump when split into calls"""
        parts = []
        for st in steps:
            if st[0] == "sleep":
                parts.append(f"sleep {st[1]}")
            else:
                parts.append(f"input motionevent {st[0].upper()} {st[1]} {st[2]}")
        self.sh("; ".join(parts))

    def visible(self, text, s, where="app", contains=False):
        """a node with this text whose centre is inside all its ancestors (not scrolled under a bar)"""
        pool = s.app() if where == "app" else s.ime() if where == "ime" else s.nodes
        found = []
        for n in pool:
            if not (contains and (text in n.text or text in n.desc) or
                    not contains and (n.text == text or n.desc == text)):
                continue
            x, y = n.c
            a, ok = n.parent, n.b[2] > n.b[0] and n.b[3] > n.b[1]
            while a is not None and ok:
                ok = a.b[0] <= x < a.b[2] and a.b[1] <= y < a.b[3]
                if a.cls in ("RecyclerView", "ListView", "ScrollView") and ok:
                    ok = a.b[1] + 20 <= y < a.b[3] - 20
                a = a.parent
            if ok:
                found.append(n)
        # a category header and its row can carry the same title (词库与短语 → 用户词库): prefer the
        # one that is, or sits in, something clickable
        def tappable(n):
            a = n
            for _ in range(3):
                if a is None:
                    return False
                if a.clickable:
                    return True
                a = a.parent
            return False
        return next((n for n in found if tappable(n)), found[0] if found else None)

    def tap_text(self, text, where="app", contains=False, scroll=True, after=None, owe="tap_text"):
        """owe=None: the caller polls for what the tap brings (goto, choose), no old pause owed"""
        s = self.screen()
        n = self.visible(text, s, where, contains)
        if not n and scroll:
            # not scrolled down since the last time at the top: a fresh page is at its top already, so
            # look down from here first (a swipe up at the top costs a second for nothing); a page
            # come back to scrolled (the back stack keeps its place) is found by the top-and-down after
            if self._scrolled:
                self.scroll_top()
            n, s = self._scan_down(text, where, contains)
            if not n and not self._at_top:
                self.scroll_top()
                n, s = self._scan_down(text, where, contains)
        if n:
            # what was on screen as it was tapped (the screen it was found on): goto waits for a change from this
            self._tapped_on = s.texts()
            # owe=None sleeps tap_text's pause still (nothing owed: the caller polls)
            self.tap(n.c, after if owe or after is not None else PAUSE["tap_text"], owe=owe)
            self._at_top = False
            return n
        raise Blocked(f"no 「{text}」 on screen")

    def _scan_down(self, text, where, contains):
        """(the node, the screen it is on), scrolling down to it; (None, the last screen) when not there"""
        s = None
        for _ in range(10):
            s = self.screen()
            n = self.visible(text, s, where, contains)
            if n or not self.scroll(s):
                return n, s
        return None, s

    def scroll(self, s=None, up=False):
        """scroll the app's biggest scrollable list one screen; False when it did not move"""
        s = s or self.screen()
        lists = [n for n in s.app() if n.scrollable]
        if not lists:
            return False
        n = max(lists, key=lambda n: (n.b[2] - n.b[0]) * (n.b[3] - n.b[1]))
        x0, y0, x1, y1 = n.b
        before = s.texts()
        a, b = (x1 // 2, y0 + (y1 - y0) * 3 // 4), (x1 // 2, y0 + (y1 - y0) // 5)
        self.swipe(b if up else a, a if up else b, 400, after=0.6)
        moved = self.screen().texts() != before
        if moved:
            self._scrolled, self._at_top = not up, False
        return moved

    def scroll_top(self):
        for _ in range(10):
            if not self.scroll(up=True):
                break
        self._scrolled, self._at_top = False, True

    def all_texts(self):
        """texts of the whole scrolling page, top to bottom, scrolling down to its end"""
        self.scroll_top()
        seen = []
        for _ in range(12):
            s = self.screen()
            for t in s.texts():
                if t not in seen:
                    seen.append(t)
            if not self.scroll(s):
                break
        return seen

    # --- the keyboard
    def keys(self, refresh=False):
        """label -> centre, from the keyboard on screen: a letter key by its letter, the others by
        name: space bksp shift enter globe , . 符 123 (and the nine keys' digits)"""
        if self._keys and not refresh:
            return self._keys
        kb = self.need_keyboard()
        names = {"button_space": "space", "button_backspace": "bksp", "button_caps": "shift",
                 "button_return": "enter", "button_lang": "globe", "button_comma": ",",
                 "button_period": ".", "button_confirm": "enter"}
        k = {}
        for n in kb.walk():
            if not n.clickable:
                continue
            if n.rid in names:
                k[names[n.rid]] = n.c
                continue
            byd = {"退格": "bksp", "切换语言": "globe", "Shift": "shift"}
            if n.desc in byd or n.desc.startswith("空格"):
                k.setdefault(byd.get(n.desc, "space"), n.c)
                continue
            t = [m.text for m in n.walk() if m.text]
            if t and t[0] not in k:
                k[t[0].lower()] = n.c
            for x in t[1:]:   # the nine keys: "ABC" over "2"; a table's roots over their letter (日 over a)
                if len(x) == 1 and (x.isdigit() or "a" <= x <= "z"):
                    k.setdefault(x, n.c)
        self._keys = k
        return k

    def need_keyboard(self, timeout=20):
        """our keyboard's view, waited for. Not up after a few seconds: another input method may have
        taken over (the system falls back to LatinIME after a `pm clear`, at times late) or the field
        lost the request; ours set again and the focused field tapped, once. Blocked with what was
        current, so a run's log tells a lost keyboard from a dead process"""
        # where it stays, too: a case reads the keys (or the floating handle) next, and the window
        # sliding up has them lower than they end. Up once its bounds read the same twice, or once
        # when they are where the keyboard last settled: keys(refresh=True) comes here on every key,
        # and a second read each time cost a dump and a wait's step per key
        kb = [self._kb_at] if self._kb_at else []

        def up(s):
            v = s.find(rid="keyboard_view", where="ime")
            settled = v is not None and kb == [v.b]
            kb[:] = [v.b] if v else []
            if settled:
                self._kb_at = v.b
            return v if settled else None
        try:
            return self.wait(up, min(6, timeout), what="the keyboard")
        except Blocked:
            pass
        diag = ""
        if self.sh("settings get secure default_input_method").strip() != IME:
            try:
                ensure_ime(self)
            except Blocked as e:   # its own block would hide what is said below
                diag = f"；{e}"
        f = self.field()
        if f:
            # open_page's point: a tall field's text keeps its cursor at the end
            self.tap((f.b[2] - 40, f.b[3] - 25) if f.b[3] - f.b[1] > 150 else f.c, 0.5)
        kb.clear()   # bounds from before the tap do not count as the first of two
        self._kb_at = None
        try:
            return self.wait(up, max(1, timeout - 6), what="the keyboard")
        except Blocked:
            cur = self.sh("settings get secure default_input_method").strip().split("/")[0]
            alive = bool(self.sh(f"pidof {PKG}").strip())
            raise Blocked(f"timed out waiting for the keyboard（当前输入法 {cur}，进程{'在' if alive else '不在'}，"
                          f"前台 {self.top_activity().split('/')[-1]}{diag}）")

    def key_node(self, label, s=None):
        s = s or self.screen()
        kb = s.find(rid="keyboard_view", where="ime")
        for n in kb.walk() if kb else []:
            if n.clickable:
                t = [m.text for m in n.walk() if m.text]
                if t and (t[0].lower() == label or label.isdigit() and label in t[1:]):
                    return n
        return None

    def type(self, keys, after=None):
        """tap letters (and , . on the bottom row); ' ' is the space key"""
        # the keys read afresh: one-handed, floating, a panel or another layout move them
        k = self.keys(refresh=True)
        pts = []
        for ch in keys:
            name = "space" if ch == " " else ch
            if name not in k:
                k = self.keys(refresh=True)
                if name not in k:
                    raise Blocked(f"no key {ch!r}")
            pts.append(k[name])
        self.taps(pts, after=after, owe="type")

    def glide(self, word, nine=False, after=0.8):
        """a finger over the word's keys without lifting (input motionevent moves, ~6 per hop);
        nine: over the nine keys' digit keys"""
        k = self.keys(refresh=True)
        digit = {c: dg for dg, ls in zip("23456789", ["abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"])
                 for c in ls}
        pts = []
        for c in word.lower():
            p = k[digit[c]] if nine else k[c]
            if not pts or pts[-1] != p:
                pts.append(p)
        self.touch(*path_steps(pts), ("up", *pts[-1]))
        time.sleep(after)

    def press(self, name, n=1, after=None):
        k = self.keys(refresh=True)
        if name not in k:
            k = self.keys(refresh=True)
        if name not in k:
            raise Blocked(f"no key {name}")
        self.taps([k[name]] * n, after=after, owe="press")

    def space(self):
        self.press("space")

    def bksp(self, n=1):
        self.press("bksp", n)

    def enter(self):
        self.press("enter")

    def cands(self, timeout=8, want=None):
        """the candidate bar, waiting for it to show (and, with want, to contain want), once the
        input method is idle: the first candidate a refine changes is not read before it"""
        self.idle()
        end = time.time() + timeout
        c = []
        while time.time() < end:
            c = self.candidates()
            if c and (want is None or any(want in x for x in c)):
                return c
            time.sleep(0.3)
        return c

    def all_cands(self, pages=1):
        """the bar and the expanded list's first pages, the list closed again"""
        self.idle()
        s = self.screen()
        bar = self.candidates(s)
        b = s.find(desc="展开候选词列表", where="ime")
        if b:
            self.tap(b.c, 0.6)
        elif not s.find(rid="expanded_candidate_view", where="ime"):   # not already open
            return bar
        more = []
        for i in range(pages):
            s = self.screen()
            grid = s.find(rid="expanded_candidate_view", where="ime")
            more += [n.text for n in (grid.walk() if grid else []) if n.text and n.cls == "TextView"
                     and n.text not in more]
            nxt = s.find(desc="更多候选词", where="ime")
            if i + 1 < pages and nxt:
                self.tap(nxt.c, 0.5)
            else:
                break
        c = s.find(desc="隐藏候选词列表", where="ime")
        if c:
            self.tap(c.c, 0.5)
        return self.candidates(s) + more

    def preedit_glyphs(self):
        """x spans of the glyphs drawn in the composing row (from a screenshot), and the row's y"""
        from PIL import Image
        row = self.code_row()
        if not row:
            raise Blocked("no composing row")
        im = Image.open(io.BytesIO(self.adb("exec-out", "screencap", "-p", binary=True))).convert("L")
        x0, y0, x1, y1 = row.b
        bg = im.getpixel((x1 - 3, y0 + 3))
        ink = [any(abs(im.getpixel((x, y)) - bg) > 90 for y in range(y0 + 4, y1 - 4)) for x in range(x0, x1)]
        spans, start = [], None
        for i, v in enumerate(ink + [False]):
            if v and start is None:
                start = i
            elif not v and start is not None:
                spans.append((x0 + start, x0 + i - 1))
                start = None
        return spans, (y0 + y1) // 2

    def _grid_pick(self, which, after, pages=10):
        """the open expanded list: which on this page, then the pages after it, then before it"""
        def hit(s2):
            grid = s2.find(rid="expanded_candidate_view", where="ime")
            for n in (grid.walk() if grid else []):
                if n.cls == "TextView" and (n.text == which or n.text.split(" ", 1)[0] == which):
                    return n
            return None
        for step in ("更多候选词", "上一页候选词"):
            for _ in range(pages):
                s2 = self.screen()
                n = hit(s2)
                if n:
                    self.tap(n.c, after)
                    return n.text
                b = s2.find(desc=step, where="ime")
                if not b or not b.enabled:
                    break
                self.tap(b.c, 0.5)
        return None

    def pick(self, which, s=None, after=0.6):
        """tap a candidate: by 1-based position or by text (exact first, then prefix). The expanded
        list counts too: after a pick from it while more is left to convert, the list stays open
        (the bar's button then reads 隐藏候选词列表), so an open list is searched, and paged"""
        s = s or self.screen()
        bar = s.find(rid="candidate_view", where="ime")
        is_open = bool(s.find(rid="expanded_candidate_view", where="ime"))
        if not bar and not is_open:
            raise Fail("no candidates to pick from")
        items = sorted(bar.kids, key=lambda n: n.b[0]) if bar else []
        if isinstance(which, int):
            if which > len(items):
                raise Fail(f"no candidate {which}: {self.candidates(s)}")
            self.tap(items[which - 1].c, after)
            return (items[which - 1].texts() or [""])[0]
        for it in items:
            t = (it.texts() or [""])[0]
            if t == which or t.split(" ", 1)[0] == which:
                self.tap(it.c, after)
                return t
        if not is_open:   # further along: open the expanded list
            b = s.find(desc="展开候选词列表", where="ime")
            if b:
                self.tap(b.c, 0.6)
                is_open = True
        if is_open:
            got = self._grid_pick(which, after)
            if got is not None:
                return got
            c = self.screen().find(desc="隐藏候选词列表", where="ime")
            if c:
                self.tap(c.c, 0.5)
        raise Fail(f"「{which}」 not in candidates {self.candidates(s)}")

    def long_pick(self, which, s=None):
        """long-press a candidate; returns the menu's texts"""
        s = s or self.screen()
        bar = s.find(rid="candidate_view", where="ime")
        items = sorted(bar.kids, key=lambda n: n.b[0]) if bar else []
        it = None
        if isinstance(which, int):
            it = items[which - 1] if which <= len(items) else None
        else:
            it = next((i for i in items if (i.texts() or [""])[0] == which), None)
        if not it:
            raise Fail(f"no candidate {which}: {self.candidates(s)}")
        self.hold(it.c, 900)
        end = time.time() + 4
        while time.time() < end:
            s = self.screen()
            if any(n.wtype not in (None, "2") and n.pkg == PKG for n in s.nodes) or \
                    s.find(contains="不再显示", where="all"):
                break
            time.sleep(0.3)
        return s.texts(where="all")

    def toolbar(self, s=None):
        """the bar above the keys shows the toolbar (no candidates, no preedit)"""
        s = s or self.screen()
        return bool(s.find(desc="展开工具栏", where="ime")) and not self.candidates(s)

    def clear(self):
        """empty the composing text and the field: backspace while there is something"""
        for _ in range(6):
            s = self.screen()
            c = s.find(desc="隐藏候选词列表", where="ime")
            if c:
                self.tap(c.c, 0.5)
                s = self.screen()
            p, t = self.preedit(s), self.field_text(s) or ""
            if not p and not t:
                return
            # committed text alone: reload the page (or start the test host afresh) instead of
            # backspacing it, as a backspace right after a commit takes the commit back and unlearns
            # it (PY-64)
            top = self.top_activity()
            on_page = self.page and "webview_shell" in top or self.host is not None and "TypingHostActivity" in top
            if not p and on_page:
                break
            if "|" in p:   # the cursor inside the composing row: backspace stops at its start
                break
            if p and on_page:
                # the composing text only, a key at a time until it is gone: what it shows is not
                # the keys typed (双拼 or 九键 show full pinyin, longer), and a backspace past it
                # takes the commit back (PY-64); the committed text is reloaded away above
                for _ in range(40):
                    self.press("bksp", 1, after=0.15)
                    if not self.preedit():
                        break
                continue
            self.press("bksp", min(len(p) + len(t) + 1, 40), after=0.3)
        if self.page or self.host is not None:
            if self.page:
                self.open_page(self.page)
            else:
                open_host(self, **self.host)
            if not self.preedit() and not self.field_text():
                return
        raise Blocked("could not empty the field")

    # --- pages
    def open_page(self, page="text"):
        """the test page showing one field, tapped: the keyboard is up"""
        if "package:" not in self.sh("pm path org.chromium.webview_shell"):
            raise Blocked("这台没有测试页的浏览器（webview_shell 要 API 34+）")
        self.reload_n += 1
        url = f"http://127.0.0.1:8000/fields.html?r={self.reload_n}#{page}"
        self.sh(f"am start -a android.intent.action.VIEW -d '{url}' -n {BROWSER}")
        # the field where it stays: coming from another app the browser's window slides in, and
        # bounds read on the way are off the screen (seen: x 1633 on a 1080 screen, three taps
        # into nothing, the keyboard never asked for) — so twice the same before it is tapped
        last = []

        def ready(s):
            for n in s.app():
                if n.cls == "EditText" and n.pkg == "org.chromium.webview_shell" and n.rid != "url_field":
                    settled = last == [n.b]
                    last[:] = [n.b]
                    return n if settled else None
        try:
            f = self.wait(ready, 20, what="the test page")
        except Blocked:   # seen once after the system file picker: the browser did not come back
            self.sh(f"am force-stop org.chromium.webview_shell; am start -a android.intent.action.VIEW "
                    f"-d '{url}' -n {BROWSER}")
            f = self.wait(ready, 20, what="the test page")
        for i in range(3):
            if i:   # where the field is now, not where it was on the first try
                f = self.wait(ready, 10, what="the test page")
            # near the field's bottom-right corner: in a field with text the cursor lands at its end
            at = (f.b[2] - 40, f.b[3] - 25) if f.b[3] - f.b[1] > 150 else f.c
            self.tap(at, 0.8)
            if self.kb_plugged():
                # a hardware keyboard typed on: a tap in a web field tells the input method nothing,
                # the touch keyboard comes back by the button by the cursor (HK-03)
                s = self.screen()
                b = s.find(desc="显示屏幕键盘", where="ime")
                if b and not s.find(rid="keyboard_view", where="ime"):
                    self.tap(b.c, 0.8)
            try:
                self.need_keyboard(8 + 8 * i)
                break
            except Blocked:
                if i == 2:
                    raise
                ensure_ime(self)
        self.page = page
        self.host = None
        self._keys = None
        self._at_top = False

    def at_home(self, s):
        """the settings home: the app's name as the title and the search button"""
        return (s.find(desc="搜索设置") or s.find(desc="Search settings")) and \
            any(n.text.startswith(("幽默输入法", "Youmo")) and n.parent and n.parent.rid == "toolbar"
                for n in s.app())

    def open_settings(self):
        """the settings home: back out of the app's pages rather than relaunching it (a relaunch
        with CLEAR_TASK over itself left a finishing activity that took the next tap away)"""
        self._at_top = False
        for _ in range(8):
            if "MainActivity" not in self.top_activity():
                self.sh(f"am start -W -n {MAIN}")
                time.sleep(0.5)
            s = self.screen()
            if self.at_home(s):
                return
            self.back()
        raise Blocked("could not reach the settings home")

    def back(self, after=None):
        self._at_top = False   # the page come back to keeps its place: may be scrolled
        self.sh("input keyevent KEYCODE_BACK")
        time.sleep(PAUSE["back"] if after is None else after)
        self.owe("back", after)

    def im_label(self, s=None):
        s = s or self.screen()
        sp = s.find(rid="button_space", where="ime") or next(
            (n for n in s.ime() if n.desc.startswith("空格")), None)
        return sp.desc if sp else ""

    def switch_im(self, label):
        """the globe key until the space key names this input method"""
        for _ in range(8):
            # the name after 「空格，」 whole: 拼音 is in 五笔拼音 too
            if label == self.im_label().split("，", 1)[-1]:
                self._keys = None
                return
            s = self.screen()
            abc = s.find(text="ABC", where="ime")   # a number/symbol layout up: the letters first
            if abc and not s.find(rid="button_lang", where="ime"):
                self.tap(abc.c, 0.6)
                self._keys = None
            self.press("globe", after=0.8)
            self._keys = None
        raise Blocked(f"地球键切不到「{label}」：{self.im_label()}")

    def rotate(self, r):
        """0 portrait, 1 landscape. Landscape is the display resized to 2340x1080 (as on redroid, whose
        display did not rotate; the cases' landscape coordinates were measured so): the apps get a landscape configuration
        (w851dp, "land") and go through the same configuration change as on a turn"""
        self.sh("wm size 2340x1080" if r else "wm size reset")
        self.rot = r
        self._keys = None
        self._kb_at = None   # the keyboard is laid out again: its old bounds are not where it settles
        # until the apps are laid out for the new size (a window as wide as landscape, or none wider
        # than portrait) rather than a fixed 2 s; then a moment for the keyboard to come back
        wide = lambda s: max((n.b[2] for n in s.nodes), default=0)
        try:
            self.wait(lambda s: wide(s) > 1200 if r else 0 < wide(s) <= 1080, 6, step=0.2,
                      what="the new screen size")
        except Blocked:
            pass
        time.sleep(0.6 if not SLOW else 2.0)

    def expand_toolbar(self):
        s = self.screen()
        if not s.find(desc="隐藏工具栏", where="ime") and not s.find(desc="展开工具栏", where="ime") \
                and not self.preedit(s):
            # the next-word suggestions hold the bar after a commit: the keyboard hidden and shown
            # again brings the toolbar back
            self.back(0.8)
            f = next((n for n in self.screen().app() if n.focused and "Edit" in n.cls), None) or \
                next((n for n in self.screen().app() if "Edit" in n.cls and n.rid != "url_field"), None)
            if f:
                self.tap((f.b[2] - 30, f.b[3] - 20) if f.b[3] - f.b[1] > 150 else (f.b[2] - 30, f.c[1]), 1.0)
            s = self.screen()
            if not s.find(desc="展开工具栏", where="ime") and "q" in self.keys(refresh=True):
                # seen after a commit in the app's own clipboard editor: the suggestions stay on the
                # bar through a new page and a backspace; a letter typed and taken back clears them
                self.type("q", after=0.4)
                self.press("bksp", after=0.6)
                s = self.screen()
        if not s.find(desc="隐藏工具栏", where="ime"):
            self.tap_text("展开工具栏", where="ime", scroll=False)

    def hide_keyboard(self):
        n = self.screen().find(desc="隐藏键盘", where="ime")
        if n:
            self.tap(n.c)
            self._kb_at = None   # gone: the next one up slides in again

    # --- settings rows
    def row(self, title, s=None):
        s = s or self.screen()
        n = s.find(text=title, rid="title") or s.find(text=title)
        return n.row() if n else None

    def goto(self, *titles):
        """from where we are, tap each title in turn (scrolling to it), each time until the screen
        changed (the page opened) rather than a fixed second"""
        for t in titles:
            self.tap_text(t, after=0.2 if not SLOW else 1.0, owe=None)
            before = self._tapped_on
            now = self.wait_changed(before)
            if now == before:
                # a tap right after the activity (re)starts can be lost (seen on the release build):
                # still the same page, so tap once more
                self.tap_text(t, after=0.2 if not SLOW else 1.0, owe=None)
                now = self.wait_changed(before)
            self.wait_settled(now)
            self._scrolled, self._at_top = False, False

    def wait_changed(self, before, timeout=3):
        """the screen's texts once they are not before, or as they are at timeout"""
        end = time.time() + timeout
        now = self.screen().texts()
        while time.time() < end and now == before:
            time.sleep(0.15)
            now = self.screen().texts()
        return now

    def wait_settled(self, now, timeout=2):
        """changed is not arrived: the pages slide in, and a frame mid-way has both or neither.
        Settled once two reads agree"""
        end = time.time() + timeout
        while time.time() < end:
            time.sleep(0.15)
            again = self.screen().texts()
            if again == now:
                return
            now = again

    def switch(self, title, on):
        """set a switch/checkbox preference; returns whether it was changed"""
        for _ in range(8):
            s = self.screen()
            r = self.row(title, s)
            # the pages run under the navigation bar (edge to edge): a row whose middle is behind it
            # is not tapped there (ST-05: 跟随系统夜间模式 at the bottom once 大小与样式 grew)
            nav = [n.b[1] for n in s.nodes if n.pkg == "com.android.systemui" and n.b[1] > 1200 and n.b[3] > n.b[1]]
            if r and nav and r.c[1] >= min(nav) - 10 and self.scroll(s):
                continue
            if r:
                w = [n for n in r.walk() if n.cls in ("Switch", "SwitchCompat", "CheckBox", "SwitchMaterial")]
                if not w:
                    raise Blocked(f"「{title}」 has no switch")
                if w[0].checked != on:
                    was, cls = w[0].checked, w[0].cls
                    self.tap(r.c, 0.8)
                    state, dialog = self._switch_wait(title, cls, was)
                    if state == was and not dialog:
                        # a tap right after the activity (re)starts can be lost (goto's note; ST-05 on
                        # the release build, as the first thing after a reset): again only when nothing
                        # happened for 2 s, same page, same state, no dialog (F3721)
                        self.tap(r.c, 0.8)
                        state, dialog = self._switch_wait(title, cls, was)
                    if state == on:
                        # read once more after a pause: a first tap that came late, after the second,
                        # turns it back, and that must fail here rather than pass as on
                        time.sleep(0.8)
                        state, _ = self._switch_read(title, cls)
                    if state != on:
                        raise Fail(f"「{title}」 did not turn {'on' if on else 'off'}")
                    return True
                return False
            if not self.scroll(s):
                break
        raise Blocked(f"no setting 「{title}」")

    def _switch_read(self, title, cls):
        """(the row's switch checked, None when the row or its switch is not on screen; a dialog shown)"""
        s = self.screen()
        r = self.row(title, s)
        w = [n for n in r.walk() if n.cls == cls] if r else []
        return (w[0].checked if w else None), any(n.rid in ("alertTitle", "button1") for n in s.nodes)

    def _switch_wait(self, title, cls, was, timeout=2):
        """_switch_read, polled until the switch is not was (or gone) or a dialog shows, or timeout"""
        end = time.time() + timeout
        while True:
            state, dialog = self._switch_read(title, cls)
            if state != was or dialog or time.time() >= end:
                return state, dialog
            time.sleep(0.15)

    def choose(self, title, option):
        """a list preference: the module's choose (exact option first, then a prefix)"""
        choose(self, title, option)

    def summary(self, title, s=None):
        r = self.row(title, s)
        if not r:
            return None
        t = [n.text for n in r.walk() if n.rid == "summary"]
        return t[0] if t else None

    def pref_rows(self):
        """the whole preference page top to bottom: [(title, summary, is_category)]"""
        self.scroll_top()
        rows, seen = [], set()
        for _ in range(12):
            s = self.screen()
            for n in s.app():
                if n.rid != "title" or not n.text:
                    continue
                r = n.row()
                if r.parent is None:   # not in a clickable row: a category title
                    key = ("cat", n.text)
                    item = (n.text, "", True)
                else:
                    summ = [k.text for k in r.walk() if k.rid == "summary" and k.text]
                    key = ("row", n.text)
                    item = (n.text, summ[0] if summ else "", False)
                if key not in seen:
                    seen.add(key)
                    rows.append(item)
            if not self.scroll(s):
                break
        return rows

    def png(self):
        from PIL import Image
        self.settle()
        return Image.open(io.BytesIO(self.adb("exec-out", "screencap", "-p", binary=True))).convert("RGB")

    def lum(self, box, im=None):
        """mean luminance 0..255 of a box of the screen"""
        im = (im or self.png()).crop(box).convert("L").resize((40, 40))
        px = list(im.getdata())
        return sum(px) / len(px)

    # --- the system
    def top_activity(self):
        out = self.sh("dumpsys activity activities | grep -m1 topResumedActivity")
        m = re.search(r"\{[^ ]+ [^ ]+ ([^ ]+)", out)
        return m.group(1) if m else out.strip()

    # --- screenshots
    def shot(self, region="kb", note="", png=None):
        """a screenshot of a region, kept for the group's contact sheet: kb (candidate bar and keys),
        field (the field down to the keyboard's bottom), app (the app window), top (upper half),
        full; or a (x0, y0, x1, y1) box"""
        from PIL import Image
        if png is None:
            self.settle()
            png = self.adb("exec-out", "screencap", "-p", binary=True)
        im = Image.open(io.BytesIO(png)).convert("RGB")
        W, H = im.size
        if isinstance(region, tuple):
            box = region
        elif region == "kb" or region == "field":
            s = self.screen()
            top = [n for n in s.ime() if n.rid == "candidate_view" or n.desc == "展开工具栏"]
            kb = s.find(rid="input_window", where="ime")
            y0 = min((n.b[1] for n in s.ime() if n.cls == "HorizontalScrollView"), default=None)
            if y0 is None:
                y0 = min((n.b[1] for n in top), default=kb.b[1] if kb else H // 2)
            y1 = kb.b[3] if kb else H
            if region == "field":
                f = self.field(s)
                y0 = max(0, (f.b[1] if f else y0) - 10)
            box = (0, y0, W, y1)
        elif region == "top":
            box = (0, 0, W, H // 2)
        elif region == "app":
            box = (0, 60, W, H - 130)
        else:
            box = (0, 0, W, H)
        im = im.crop(box)
        scale = 360 / im.width
        im = im.resize((360, max(1, int(im.height * scale))))
        if self.run:
            self.run.add_shot(self.case, im, note)
        return im


# ---------------------------------------------------------------- baselines

def ime_ready(d):
    """after a clear the engine prepares its data on the first keyboard: wait for candidates"""
    d.type("ni", after=0.3)
    c = d.cands(timeout=60)
    if not c:
        raise Blocked("the engine gave no candidates in 60 s")
    d.bksp(3)


def ensure_ime(d):
    """ours as the current keyboard. After `pm clear` the system may fall back to its own keyboard
    a moment after `ime set` (seen: LatinIME), so set it until it holds"""
    stable = 0
    for _ in range(20):
        cur = d.sh("settings get secure default_input_method").strip()
        if cur == IME:
            stable += 1
            if stable >= 2:
                return
        else:
            stable = 0
            d.sh(f"ime enable {IME}; ime set {IME}")
        time.sleep(0.7)
    raise Blocked(f"输入法设不成幽默：{cur}")


# baselines "B0-<x>": B0, then this input method added in settings and switched to with the globe key
# (the space key's description names it); B0-英文 is B0 and the globe key once. The third: 双拼's 键盘
# set to this before the page is opened again (B0-14键 and B0-九键 are 拼音's keyboards, their own branch in reset)
IM_BASES = {"B0-五笔": ("五笔", "五笔", None),
            "B0-二笔": ("二笔", "二笔", None), "B0-仓颉": ("仓颉", "仓颉", None),
            "B0-双拼": ("双拼", "双拼", None), "B0-手写": ("手写", "手写", None),
            "B0-自然码": ("自然码", "自然码", None), "B0-五笔拼音": ("五笔拼音", "五笔拼音", None),
            "B0-英文": (None, "英文", None),
            "B0-18键": ("双拼", "双拼", "18 键"), "B0-九键双拼": ("双拼", "双拼", "九键")}
# baselines that are B0 with 拼音's 键盘 set to this (0.5: 九键 is pinyin's keyboard, no 九键拼音 to add;
# 0.5.1: the two 注音 keyboards are pinyin's too); the space key still names 拼音
PINYIN_KEYBOARDS = {"B0-14键": "14 键", "B0-九键": "九键", "B0-注音九键": "注音九键", "B0-注音全键盘": "注音全键盘"}


def add_im(d, name):
    """settings 输入法 → 添加 → name"""
    d.hide_keyboard()
    d.open_settings()
    d.goto("输入法")
    d.tap_text("添加", scroll=False, after=1.2)
    # scrolled to: landscape (B0-横屏) shows only the top of the list
    d.tap_text(name, after=1.2)
    s = d.screen()
    if not any(n.text == name for n in s.app()):
        raise Blocked(f"添加「{name}」后输入法页是 {s.texts()[:8]}")


def reset(d, base, page):
    """the app as freshly installed, then the baseline"""
    d.sh(f"pm clear {PKG}; settings put system accelerometer_rotation 0; "
         "settings put system user_rotation 0; wm size reset; cmd uimode night no")
    d.rot = 0
    ensure_ime(d)
    d._keys = None
    if not base.startswith("B0"):
        raise Blocked(f"unknown baseline {base}")
    plug(d, base)
    # the voice build: the microphone allowed (pm clear took it back), on every baseline (B0-语音 is
    # B0 there; VO-01 revokes it itself). The text build has no such permission: refused, no harm
    d.sh(f"pm grant {PKG} android.permission.RECORD_AUDIO 2>/dev/null")
    d.open_page("text")   # the engine's first load is waited for on the letters
    ime_ready(d)
    if base != "B0-设置" and page != "text":
        d.open_page(page)
    if base == "B0-设置":
        d.hide_keyboard()
        d.open_settings()
    elif base in IM_BASES:
        name, label, keyboard = IM_BASES[base]
        if name:
            add_im(d, name)
            if name == "双拼":
                shuangpin_scheme(d, keyboard)
            d.open_page(page)
        d.switch_im(label)
    elif base in PINYIN_KEYBOARDS:
        pinyin_keyboard(d, PINYIN_KEYBOARDS[base])
        d.open_page(page)
    elif base == "B0-横屏":
        d.rotate(1)
        d.open_page(page)
    elif base not in ("B0", "B0-实体", "B0-语音"):
        raise Blocked(f"unknown baseline {base}")


def plug(d, base):
    """B0-实体: the emulator's USB keyboard plugged in; any other baseline: unplugged"""
    want = base == "B0-实体"
    # gesture nav while it is plugged: with no touch keyboard the input method's window takes touches
    # only over the navigation bar (onComputeInsets), and under three-button nav's bar that region is
    # covered, so accessibility (Dumper, uiautomator) leaves the whole window out: the floating
    # candidates and the 显示屏幕键盘 button read as nothing. The other baselines keep avd.sh's three-button.
    # Set every time (enable-exclusive is idempotent), before the keyboard, so a keyboard plugged by
    # hand or left from an earlier run, or kb() raising, still leaves the nav the baseline wants
    nav = "gestural" if want else "threebutton"
    overlay = f"com.android.internal.systemui.navbar.{nav}"
    changed = f"[x] {overlay}" not in d.sh(f"cmd overlay list android | grep -F '{overlay}'")
    if changed:
        d.sh(f"cmd overlay enable-exclusive --category {overlay}")
    if d.kb_plugged() != want:
        d.kb("up" if want else "down")
        changed = True
    if changed:
        time.sleep(3)


PREFILLED = ("long",)   # the test page's fields that come with text


def soft_reset(d, base, page):
    """between two cases on a clean state: nothing composing, an empty field, the start page"""
    plug(d, base)
    if base == "B0-设置":
        d.open_settings()
        return
    s = d.screen()
    want_rot = 1 if base == "B0-横屏" else 0
    if getattr(d, "rot", 0) != want_rot:   # a case turned the screen
        d.rotate(want_rot)
        s = d.screen()
    abc = s.find(text="ABC", where="ime")   # a case left a number/symbol layout up
    if abc and not s.find(rid="button_lang", where="ime"):
        d.tap(abc.c, 0.6)
        d._keys = None
    fold = s.find(desc="隐藏工具栏", where="ime")   # a case left the toolbar open: the next starts closed
    if fold:
        d.tap(fold.c, 0.4)
    if page in PREFILLED:   # its text is part of the case: a fresh page
        d.open_page(page)
    else:
        if d.page != page or not d.field(s) or not s.find(rid="keyboard_view", where="ime"):
            d.open_page(page)
        d.clear()
    d.switch_im(IM_BASES[base][1] if base in IM_BASES else "拼音")


# ---------------------------------------------------------------- helpers for the case scripts

# Where the tree tells too little (a panel's rows have no ids),
# a few scripts go by place: the portrait layout of 1080x2340 at 440 dpi, the default keyboard height
# (measured on redroid, the same on the emulator with three-button nav). Named once here; a new device size or keyboard height changes them.
BAR_BOTTOM = 1560                     # the toolbar / candidate bar's lower edge: the keys or a panel below
PANEL_TOP, PANEL_BOTTOM = 1550, 2090  # a panel's content (symbols, emoji, clipboard rows)


def check(ok, msg):
    if not ok:
        raise Fail(msg)


def has(cands, word):
    """word is a candidate (a candidate's comment, after a space, left out)"""
    return any(c == word or c.split(" ", 1)[0] == word for c in cands)


def first(cands):
    return cands[0].split(" ", 1)[0] if cands else ""


def home(d, *path):
    """the settings home, then each title in turn"""
    d.open_settings()
    if path:
        d.goto(*path)


def choose(d, title, option):
    """a list preference: its row, then the option whose text starts with option (确定 if any)"""
    d.tap_text(title, after=0.2 if not SLOW else 1.0, owe=None)

    def dialog(s):
        return s.find(rid="select_dialog_listview") or s.find(rid="alertTitle")

    def opt(s):
        # the options in the dialog only: the row behind it shows the current value as its summary
        box = dialog(s)
        pool = [n for n in box.walk()] if box and box.rid == "select_dialog_listview" else s.app()
        return next((n for n in pool if n.text == option), None) or \
            next((n for n in pool if n.text.startswith(option) and n.text != title and n.rid != "summary"), None)
    try:   # the dialog up, then the option in it
        d.wait(dialog, 3, step=0.15)
        n = d.wait(opt, 2, step=0.15)
    except Blocked:
        raise Blocked(f"「{title}」里没有「{option}」：{d.screen().texts()[:12]}")
    d.tap(n.c, 0.3 if not SLOW else 1.0)
    # a 确定 only in a dialog still up: one fading out lets the tap through to the rows behind it
    s = d.screen()
    ok = s.find(text="确定") if dialog(s) else None
    if ok:
        d.tap(ok.c, 0.3 if not SLOW else 1.0)
    try:   # the dialog gone
        d.wait(lambda s: not dialog(s), 3, step=0.15)
    except Blocked:
        pass


def path_steps(pts, per_hop=6):
    """a finger down on the first point and over the others (touch steps), not lifted"""
    steps = [("down", *pts[0])]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        steps += [("move", x0 + (x1 - x0) * i // per_hop, y0 + (y1 - y0) * i // per_hop)
                  for i in range(1, per_hop + 1)]
    return steps


def sym_char(d, ch):
    """a character from the 符 panel's 英文/常用/数学 categories"""
    d.press("符", after=1.0)
    for name in ("英文", "常用", "数学"):
        n = d.visible(name, d.screen(), where="ime")
        if n:
            d.tap(n.c, 0.6)
        for _ in range(4):
            s = d.screen()
            m = next((m for m in s.ime() if m.text == ch and m.c[0] > 200 and PANEL_TOP < m.c[1] < PANEL_BOTTOM), None)
            if m:
                d.tap(m.c, 0.9)
                d._keys = None
                return
            d.swipe((640, 2000), (640, 1700), 400)
    raise Blocked(f"符号面板里找不到「{ch}」")


def glide_on(d, im=None, page="text", add=None):
    """通用输入 → 滑行输入 on (after adding the input method add), the test page, then im"""
    if add:
        add_im(d, add)
    home(d, "通用输入")
    d.switch("滑行输入", True)
    d.open_page(page)
    if im:
        d.switch_im(im)


def gear(d, im_name):
    """settings 输入法: the gear on an input method's row"""
    home(d, "输入法")
    s = d.screen()
    n = s.find(text=im_name)
    g = [k for k in n.parent.kids if k.cls == "ImageButton"] if n and n.parent else []
    if not g:
        raise Blocked(f"输入法页没有「{im_name}」的齿轮")
    d.tap(g[0].c, 1.2)


def pinyin_keyboard(d, option):
    """输入法 → 拼音's 键盘 set to option (全键盘, 18 键, 14 键, 九键, 注音九键 or 注音全键盘); stays on the page"""
    gear(d, "拼音")
    choose(d, "键盘", option)


def shuangpin_keyboard(d, option):
    """输入法 → 双拼's 键盘 set to option (全键盘, 18 键, 14 键 or 九键); stays on the page"""
    gear(d, "双拼")
    choose(d, "键盘", option)


def shuangpin_scheme(d, keyboard=None):
    """输入法 → 双拼's 方案 set to 自然码, as the 双拼 baselines have it (a new install's is 小鹤), and its
    键盘 to keyboard if given; stays on the page"""
    gear(d, "双拼")
    choose(d, "双拼方案", "自然码")
    if keyboard:
        choose(d, "键盘", keyboard)


# the grouped keyboards' codes (14 keys, 18 keys, 九键双拼) and a tap's place are all private use:
# none of them may reach a field
def has_keyboard_code(t):
    return any(unicodedata.category(c) == "Co" for c in t or "")


def grouped_keys(d, groups, *, after=0.6, at=None, drift=None):
    """tap the 14 or 18 keys by their letters ("bn ui gh"), a key a word; "'" is the separator key.
    at: where across each key to tap, a value a word (0 its left edge, 1 its right; None its centre),
    as the 14 keys weigh a letter by the half tapped; drift: the index of the one key tapped with a
    MOVE of a couple of pixels before the lift (Device.tap_drift), as a real finger's tap"""
    k = d.keys(refresh=True)
    s = d.screen() if at is not None else None
    if at is not None and len(at) != len(groups.split()):
        raise ValueError(f"at {at} for {groups}")
    pts = []
    for i, g in enumerate(groups.split()):
        name = "sep" if g == "'" else g
        if name == "sep":
            n = d.screen().find(desc="分词", where="ime")
            if not n:
                raise Blocked("键盘上没有分词键")
            pts.append(n.c)
            continue
        if name not in k:
            raise Blocked(f"键盘上没有 {g.upper()}：{sorted(k)[:20]}")
        if at is None or at[i] is None:
            pts.append(k[name])
            continue
        n = d.key_node(name, s)
        if not n:
            raise Blocked(f"键盘上没有 {g.upper()} 的边界")
        pts.append((int(n.b[0] + (n.b[2] - n.b[0]) * at[i]), k[name][1]))
    if drift is None:
        d.taps(pts, gap=0.3, after=after)
        return
    # the drifting tap a process of its own: one input swipe, never three input motionevent calls,
    # which take long enough between DOWN and UP to be a long press (review N1)
    if pts[:drift]:
        d.taps(pts[:drift], gap=0.3, after=0.3)
    d.tap_drift(pts[drift], after=0.3)
    if pts[drift + 1:]:
        d.taps(pts[drift + 1:], gap=0.3, after=after)
    else:
        time.sleep(after)


HOST_PKG = "io.github.sraw.youmo.debug"
HOST = f"{HOST_PKG}/org.fcitx.fcitx5.android.input.TypingHostActivity"


def open_host(d, **extras):
    """the debug build's TypingHostActivity, a native EditText with what a web field cannot have:
    next=True (an IME_ACTION_NEXT field and a second one under it), noLearning=True
    (IME_FLAG_NO_PERSONALIZED_LEARNING), sensitiveClip="…" (that text on the clipboard marked
    sensitive). Not exported: started as root. A release run (UAT_BUILD=release) has no debug package:
    it is installed for this case as a host app only, its own input method disabled so the system
    has one 幽默输入法, and removed when the case ends (SET-01's wizard must see one)"""
    if BUILD == "release":
        # removed when the case ends whether this case put it there or a run before left it
        # once a case: clear() opens it again, and a second pm uninstall only logs a failure
        if not any(getattr(u, "unhost", False) for u in d.after_case):
            u = lambda: unhost(d)   # noqa: E731
            u.unhost = True
            d.after_case.append(u)
        if not d.sh(f"pm path {HOST_PKG}").strip():
            dp = os.path.join(REPO, "app/build/outputs/apk/text/debug")
            apks = sorted((os.path.join(dp, f) for f in (os.listdir(dp) if os.path.isdir(dp) else [])
                           if ABI in f and f.endswith(".apk")), key=os.path.getmtime)
            if not apks:
                raise Blocked(f"正式包跑时要 debug 包当测试宿主：BUILD_ABI={ABI} ./gradlew :app:assembleTextDebug")
            d.adb("install", "-r", apks[-1], check=True, timeout=600)
        d.sh(f"ime disable {HOST_PKG}/org.fcitx.fcitx5.android.input.FcitxInputMethodService")
        ensure_ime(d)
    args = " ".join(f"--ez {k} true" if v is True else f"--es {k} {shlex.quote(v)}" for k, v in extras.items())
    # a new task each time: the extras of this start, not an instance left from another case
    d.root(f"am start -W -f 0x10008000 -n {HOST} {args}")
    d.wait(lambda s: s.find(rid="keyboard_view", where="ime") and d.field(s), 15, what="the test host's field")
    d.page = None
    # started afresh by clear() as a page is reloaded; the clip is not put on the clipboard again
    d.host = {k: v for k, v in extras.items() if k != "sensitiveClip"}
    d._keys = None


def in_picker(d, timeout=8):
    """until the system's file picker (DocumentsUI) is in front and shows its content, rather than a
    fixed 2.5 s after the tap that opens it; the caller still checks top_activity and says what failed"""
    def shown(s):
        return "documentsui" in d.top_activity() and any(n.pkg.endswith("documentsui") and n.text for n in s.nodes)
    try:
        d.wait(shown, timeout, step=0.2, what="the file picker")
    except Blocked:
        pass


def unhost(d):
    """the debug package a release run installed as a host: gone, or said so (SET-01 would see two)"""
    out = d.sh(f"pm uninstall {HOST_PKG}").strip()
    if "Success" not in out:
        print(f"! {d.case.id if d.case else ''}: pm uninstall {HOST_PKG}: {out[:80]}", flush=True)


def open_url(d, page, field=0):
    """the test page with this hash (or "" for all fields), the field-th field tapped"""
    d.reload_n += 1
    url = f"http://127.0.0.1:8000/fields.html?r={d.reload_n}" + (f"#{page}" if page else "")
    d.sh(f"am start -a android.intent.action.VIEW -d '{url}' -n {BROWSER}")
    def ready(s):
        f = [n for n in s.app() if n.cls == "EditText" and n.pkg == "org.chromium.webview_shell"
             and n.rid != "url_field"]
        return f if len(f) > field else None
    f = d.wait(ready, 20, what="the test page")
    d.tap(f[field].c, 1.0)
    d.page = None
    d.host = None
    d._keys = None


# ---------------------------------------------------------------- a run

def read_json(path, default=None):
    if not os.path.exists(path):
        return default
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def write_atomic(path, text):
    """a crash half-way leaves the old file, not half a new one"""
    tmp = f"{path}.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)


class Run:
    """one run per date and version: a rerun of some cases replaces only their results and shots"""

    def __init__(self, name, ids):
        self.name = name
        self.dir = os.path.join(OUT, name)
        os.makedirs(self.dir, exist_ok=True)
        self.lock = threading.Lock()
        self.ids = ids
        rj, sj = os.path.join(self.dir, "results.json"), os.path.join(self.dir, "shots.json")
        self.results = {k: tuple(v) for k, v in read_json(rj, {}).items()}
        old = read_json(sj, [])
        # a rerun replaces the result but keeps the attempts before it: a first run's failure is
        # what tells a flaky case from a broken one
        self.prev = {cid: attempts_of(self.results[cid]) for cid in ids if cid in self.results}
        # the rerun's old results, written by save() till each is run again: a rerun stopped half-way
        # keeps the result and attempts of every case it did not reach
        self.held = {cid: self.results.pop(cid) for cid in self.prev}
        self.spans = []     # [port, case id, start, end] of every case ended, for "beside"
        self.active = {}    # port: (case id, start) of the case running there now
        self.shots = [x for x in old if x[0] not in ids]   # [case id, group, file, note]
        for f in os.listdir(self.dir):
            if f.endswith(".png") and f.rsplit("-", 1)[0] in ids:
                os.remove(os.path.join(self.dir, f))

    def add_shot(self, c, im, note):
        with self.lock:
            n = sum(1 for x in self.shots if x[0] == c.id) + 1
            f = f"{c.id}-{n}.png"
            im.save(os.path.join(self.dir, f))
            self.shots.append([c.id, c.group, f, note])

    def save(self):
        with self.lock:
            res = json.dumps({k: list(v) for k, v in {**self.held, **self.results}.items()}, ensure_ascii=False)
            shots = json.dumps(self.shots, ensure_ascii=False)
            # inside the lock: both device threads write the same .tmp files
            write_atomic(os.path.join(self.dir, "results.json"), res)
            write_atomic(os.path.join(self.dir, "shots.json"), shots)

    def started(self, port, cid):
        with self.lock:
            self.active[port] = (cid, time.time())

    def record(self, c, state, seen, dev, secs):
        with self.lock:
            end = time.time()
            start = end - secs
            self.active.pop(dev, None)
            # what the other device ran while this one did: two emulators share the host's CPU
            beside = [x[1] for x in self.spans if x[0] != dev and x[2] < end and x[3] > start] + \
                     [cid for p, (cid, s) in self.active.items() if p != dev and s < end]
            self.spans.append([dev, c.id, start, end])
            att = self.prev.pop(c.id, []) + [{"at": datetime.datetime.now().isoformat(timespec="seconds"),
                                              "dev": dev, "state": state, "seen": seen,
                                              "secs": round(secs, 1), "beside": list(dict.fromkeys(beside))}]
            self.results[c.id] = (state, seen, dev, secs, att)
            tag = {"通过": ".", "失败": "F", "阻塞": "B", "跳过": "s", "待看图": "v", "在别处跑": "o"}[state]
            print(f"{tag} {c.id} {state} [{dev} {secs:.0f}s]" + (f" {seen[:80]}" if state in ("失败", "阻塞") else ""),
                  flush=True)
        self.save()   # as each case ends: a runner stopped half-way keeps what it ran


def stepped(d, e):
    """the message, after the step it happened in (Device.step) when the case named one"""
    return f"「{d._step}」这一步：{e}" if getattr(d, "_step", None) else str(e)


def run_case(d, c, run):
    d.case = c
    d._step = None
    d.after_case = []   # undone when the case ends, however it ends (open_host's package)
    t0 = time.time()
    run.started(d.port, c.id)
    try:
        unmet = c.unmet(d)
        if unmet:
            raise unmet[0](unmet[1])
        seen = c.fn(d) or ""
        state = "待看图" if c.visual else "通过"
    except Fail as e:
        state, seen = "失败", stepped(d, e)
    except Blocked as e:
        state, seen = "阻塞", stepped(d, e)
    except Skip as e:
        state, seen = "跳过", str(e)
    except Elsewhere as e:
        state, seen = "在别处跑", str(e)
    except Exception as e:  # a script error: blocked, with where
        tb = traceback.extract_tb(e.__traceback__)[-1]
        state, seen = "阻塞", stepped(d, f"脚本出错 {type(e).__name__}: {e} ({os.path.basename(tb.filename)}:{tb.lineno})")
    for undo in reversed(d.after_case):
        try:
            undo()
        except Exception:   # a cleanup that failed leaves the next reset to it
            pass
    run.record(c, state, seen, d.port, time.time() - t0)
    return state


class Scheduler:
    """groups to devices: each device takes the next group; a device with no group left takes the
    remaining state-changing cases from the tail of a group in progress (each needs a reset anyway).
    The heavy cases come after all of it, one at a time, each once no other device runs anything"""

    def __init__(self, cases, run, ports, flavor="text"):
        groups = {}
        for c in cases:
            if not c.heavy or not c.heavy_here():
                groups.setdefault(c.group, []).append(c)
        for g in groups.values():
            g.sort(key=lambda c: (c.order(), c.id))
        # biggest groups first, so the stealing at the end has small pieces
        self.pending = sorted(groups.values(), key=lambda g: -sum(3 if c.mutates else 1 for c in g))
        self.heavy = [[c] for c in sorted((c for c in cases if c.heavy and c.heavy_here()), key=lambda c: c.id)]
        self.active = []
        self.busy = set()   # the ports running a group now
        self.lock = threading.Condition()
        self.run, self.ports, self.flavor = run, ports, flavor

    def next_group(self, port=None):
        with self.lock:
            self.busy.discard(port)
            self.lock.notify_all()
            g, stolen = self._next()
            while g is None and self.heavy:
                if not self.busy:
                    g, stolen = self.heavy.pop(0), False
                    break
                self.lock.wait()
                g, stolen = self._next()
            if g is not None:
                self.busy.add(port)
            return g, stolen

    def _next(self):
        if self.pending:
            g = self.pending.pop(0)
            self.active.append(g)
            return g, False
        for g in sorted(self.active, key=len, reverse=True):
            tail = [c for c in g[1:] if c.mutates is True or c.clean]
            if len(tail) >= 2:
                cut = len(tail) // 2
                stolen = tail[-cut:]
                for c in stolen:
                    g.remove(c)
                stolen = list(stolen)
                self.active.append(stolen)
                return stolen, True
        return None, False

    def take(self, g):
        with self.lock:
            return g.pop(0) if g else None

    def device_loop(self, port):
        d = Device(port, self.run, self.flavor)
        try:
            if not d.start_dumper():
                d.stop_dumper()   # falls back to `uiautomator dump`, ~2 s a read
            while True:
                g, stolen = self.next_group(port)
                if g is None:
                    return
                dirty = None   # None: not at the baseline; "learn"; True
                while True:
                    c = self.take(g)
                    if c is None:
                        break
                    if c.unmet(d):   # skipped or blocked as it is: no reset for it
                        run_case(d, c, self.run)
                        continue
                    try:
                        if dirty is None or dirty is True or (dirty == "learn" and c.clean):
                            reset(d, c.base, c.page)
                            dirty = False
                        else:
                            soft_reset(d, c.base, c.page)
                    except Exception as e:   # any: the case is blocked, the device goes on
                        tb = traceback.extract_tb(e.__traceback__)[-1]
                        where = "" if isinstance(e, (Blocked, Fail)) else \
                            f"{type(e).__name__} ({os.path.basename(tb.filename)}:{tb.lineno}) "
                        self.run.record(c, "阻塞", f"基线没到：{where}{e}", d.port, 0)
                        dirty = None
                        continue
                    run_case(d, c, self.run)
                    if c.mutates is True or dirty is True:
                        dirty = True
                    elif c.mutates == "learn" and not dirty:
                        dirty = "learn"
                    # a case that failed half-way may have left anything behind
                    if self.run.results[c.id][0] in ("失败", "阻塞") and c.mutates:
                        dirty = True
        except Exception as e:  # keep the other device going
            print(f"device {port} stopped: {e}", flush=True)
        finally:
            self.done(port)   # a device stopped holds back no heavy case
            d.stop_dumper()

    def done(self, port):
        """port runs nothing now and will take no more"""
        with self.lock:
            self.busy.discard(port)
            self.lock.notify_all()


# ---------------------------------------------------------------- sheets and the results file

def build_sheets(run, cases):
    from PIL import Image, ImageDraw, ImageFont
    # neither font on this host: PIL's own (Latin only), the sheets still made and the results written
    font = Fonts(ImageFont.truetype(FONT, 15) if FONT else ImageFont.load_default(),
                 ImageFont.truetype(FONT_LATIN, 14) if FONT_LATIN else None)
    from PIL import Image as _I
    groups = {}
    for cid, group, f, note in run.shots:
        if os.path.exists(os.path.join(run.dir, f)):
            groups.setdefault(group, []).append((cid, _I.open(os.path.join(run.dir, f)), note))
    for f in os.listdir(run.dir):
        if f.startswith("sheet-"):
            os.remove(os.path.join(run.dir, f))
    paths = []
    for group, shots in sorted(groups.items()):
        shots = sorted(shots, key=lambda x: x[0])
        tiles = []
        for cid, im, note in shots:
            c = CASES.get(cid)
            if c is None:
                continue
            cap = f"{cid} {note}".strip() + "\n预期：" + (c.expect or "")
            lines = wrap(cap, font, im.width - 8)[:7]
            hh = 6 + 19 * len(lines)
            t = Image.new("RGB", (im.width, hh + im.height), "white")
            dr = ImageDraw.Draw(t)
            for i, ln in enumerate(lines):
                font.draw(dr, (4, 3 + 19 * i), ln)
            t.paste(im, (0, hh))
            tiles.append(t)
        if not tiles:
            continue
        cols = min(5, len(tiles))
        rows = [tiles[i:i + cols] for i in range(0, len(tiles), cols)]
        W = cols * 370 + 10
        H = sum(max(t.height for t in r) + 10 for r in rows) + 10
        sheet = Image.new("RGB", (W, H), (200, 200, 200))
        y = 10
        for r in rows:
            x = 10
            for t in r:
                sheet.paste(t, (x, y))
                x += 370
            y += max(t.height for t in r) + 10
        p = os.path.join(run.dir, f"sheet-{group.replace('·', '-')}.png")
        sheet.save(p)
        paths.append(p)
    return paths


class Fonts:
    """CJK from the fallback font, Latin and digits from a Latin one (the fallback has none)"""

    def __init__(self, cjk, latin):
        self.cjk, self.latin = cjk, latin or cjk

    def pick(self, ch):
        return self.latin if ord(ch) < 0x2000 else self.cjk

    def getlength(self, text):
        return sum(self.pick(ch).getlength(ch) for ch in text)

    def draw(self, dr, xy, text):
        x, y = xy
        for ch in text:
            f = self.pick(ch)
            dr.text((x, y + (1 if f is self.latin else 0)), ch, fill="black", font=f)
            x += f.getlength(ch)


def wrap(text, font, width):
    out = []
    for para in text.split("\n"):
        line = ""
        for ch in para:
            if font.getlength(line + ch) > width:
                out.append(line)
                line = ch
            else:
                line += ch
        out.append(line)
    return out


def counts_text(results):
    """the counts of what ran here; the cases that run elsewhere after them, apart"""
    counts, away = {}, 0
    for st, *_ in results.values():
        if st == "在别处跑":
            away += 1
        else:
            counts[st] = counts.get(st, 0) + 1
    return "，".join(f"{k} {v}" for k, v in sorted(counts.items())) + \
        (f"（另有在别处跑 {away} 条，不计入）" if away else "")


def attempts_of(v):
    """a result's attempts; a result from before they were kept counts as its one attempt"""
    if len(v) > 4:
        return list(v[4])
    defaults = ["", "", "-", 0]
    st, seen, dev, secs = list(v)[:4] + defaults[len(v):]
    return [{"at": "", "dev": dev, "state": st, "seen": seen, "secs": secs, "beside": []}]


def result_cell(v):
    """the state, and the attempts before the last when there were any: 通过（第 2 次；第 1 次 失败：…）"""
    att = attempts_of(v)
    if len(att) < 2:
        return v[0]
    before = "；".join(f"第 {i} 次 {a['state']}" + (f"：{a['seen'][:60]}" if a["state"] in ("失败", "阻塞") else "")
                      for i, a in enumerate(att[:-1], 1))
    return f"{v[0]}（第 {len(att)} 次；{before}）".replace("|", "/").replace("\n", " ")


def by_id(i):
    return (i.split("-")[0], int(re.sub(r"\D", "", i) or 0), i)


def table(results):
    """the results file's table, from results.json's {id: [state, seen, …]}; the cases that run
    elsewhere in a list of their own after it"""
    lines = ["| 编号 | 结果 | 看到的 |", "|---|---|---|"]
    away = []
    for cid in sorted(results, key=by_id):
        st, seen, *_ = results[cid]
        if st == "在别处跑":
            away.append(f"- {cid}：{seen}")
            continue
        lines.append(f"| {cid} | {result_cell(results[cid])} | {seen.replace('|', '/').replace(chr(10), ' ')} |")
    if away:
        lines += ["", f"## 在别处跑（{len(away)} 条，不在这里跑，不算跳过）", ""] + away
    return lines


def write_results(run, cases, version, ports, wall, sheets, flavor="text"):
    p = os.path.join(UAT, "runs", f"{run.name}.md")
    dev = (f"- 设备：Android 模拟器（AVD {AVD}，android-36 google_apis {ABI}），1080×2340，"
           f"440 dpi，简体中文；{len(ports)} 台（{', '.join('emulator-' + p for p in ports)}）；"
           f"实体键盘是 uat/tools/emu-keyboard.sh 的 uinput USB 键盘")
    lines = [f"# {run.name}", "", dev,
             f"- 包：{'语音版' if flavor == 'voice' else '文字版'} {ABI} {BUILD}（{PKG}）{version}",
             # the ids of a short rerun say which rows are new; a whole run is just its count
             f"- 跑法：`uat/tools/uat.py run`（最近一次跑 {sum(1 for i in run.ids if run.results.get(i, ('',))[0] != '在别处跑')} 条"
             f"{'：' + ', '.join(run.ids) if len(run.ids) <= 12 else ''}，用时 {wall:.0f} s）；"
             f"共 {sum(1 for v in run.results.values() if v[0] != '在别处跑')} 条：" + counts_text(run.results),
             f"- 图：`dev/uat-out/{run.name}/`（{len(sheets)} 张拼图）；待看图的看过拼图后用 `uat.py mark` 改",
             ""] + table(run.results)
    run.save()
    write_atomic(p, "\n".join(lines) + "\n")
    return p


def mark(run_name, cid, state, note):
    """results.json is the record: the case's state there, then the results file drawn again from it"""
    rj = os.path.join(OUT, run_name, "results.json")
    p = os.path.join(UAT, "runs", f"{run_name}.md")
    res = read_json(rj)
    if res is None or cid not in res:
        sys.exit(f"{cid} not in {rj}")
    seen = res[cid][1]
    if note:
        seen = (seen + "；" if seen else "") + "看图：" + note
    res[cid][0], res[cid][1] = state, seen
    if len(res[cid]) > 4 and res[cid][4]:   # the last attempt is this result: flakes reads it
        res[cid][4][-1]["state"], res[cid][4][-1]["seen"] = state, seen
    write_atomic(rj, json.dumps(res, ensure_ascii=False))
    with open(p, encoding="utf-8") as f:
        head = f.read().split("\n| 编号 |")[0].split("\n")
    here = sum(1 for v in res.values() if v[0] != "在别处跑")
    head = [re.sub(r"共 \d+ 条：.*", lambda m: f"共 {here} 条：" + counts_text(res), ln) if ln.startswith("- 跑法：") else ln
            for ln in head]
    write_atomic(p, "\n".join(head + table(res)) + "\n")
    print(f"{cid} {state}")


def flakes(names):
    """cases that failed or blocked and then passed, with what the other device ran beside each
    failure: the 2026-10-23 guess is that heavy cases there starve the timing-bound ones here"""
    names = names or sorted(n for n in os.listdir(OUT) if os.path.exists(os.path.join(OUT, n, "results.json")))
    tally, devs, n = {}, {}, 0
    for name in names:
        res = read_json(os.path.join(OUT, name, "results.json"), {})
        secs = {cid: v[3] for cid, v in res.items() if len(v) > 3}
        for cid in sorted(res, key=by_id):
            att = attempts_of(res[cid])
            bad = [a for a in att if a["state"] in ("失败", "阻塞")]
            if not bad or att[-1]["state"] not in ("通过", "待看图"):
                continue
            n += 1
            print(f"{name} {cid}: {att[-1]['state']} on attempt {len(att)}")
            for a in bad:
                devs[a["dev"]] = devs.get(a["dev"], 0) + 1
                for b in a["beside"]:
                    tally[b] = tally.get(b, 0) + 1
                beside = ", ".join(f"{b} {secs.get(b, 0):.0f}s" for b in a["beside"]) or "-"
                print(f"  {a['state']} [{a['dev']} {a['secs']:.0f}s {a['at']}] {a['seen'][:100]}\n    beside: {beside}")
    print(f"{n} flaky cases; failures by device: " + (", ".join(f"{k} {v}" for k, v in sorted(devs.items())) or "-"))
    if tally:
        print("beside the failures most often: " +
              ", ".join(f"{k} ×{v}" for k, v in sorted(tally.items(), key=lambda kv: -kv[1])[:10]))


# ---------------------------------------------------------------- devices and the page server

SHIM_LOCK = threading.Lock()


def emulator_env(gpu):
    """The emulator's environment: for -gpu host on a Mac, the offline-renderer shim (offline-gl.m) loaded
    into it, built once into ~/.cache/youmo (again when the source is newer)."""
    env = dict(os.environ)
    if gpu != "host" or sys.platform != "darwin":
        return env
    src = os.path.join(os.path.dirname(os.path.abspath(__file__)), "offline-gl.m")
    lib = os.path.expanduser("~/.cache/youmo/liboffline-gl.dylib")
    with SHIM_LOCK:   # devices start on threads: one builds it, the others wait
        if not os.path.exists(lib) or os.path.getmtime(lib) < os.path.getmtime(src):
            os.makedirs(os.path.dirname(lib), exist_ok=True)
            tmp = lib + f".{os.getpid()}"   # another uat.py building it too: each its own, the last one kept
            subprocess.run(["clang", "-dynamiclib", "-arch", "arm64", "-o", tmp, src,
                            "-framework", "AppKit", "-framework", "OpenGL"], check=True)
            subprocess.run(["codesign", "-s", "-", "-f", tmp], check=True, capture_output=True)
            os.replace(tmp, lib)
    env["DYLD_INSERT_LIBRARIES"] = lib
    return env


def emulator_bin():
    sdk = (os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
           or os.path.expanduser("~/Library/Android/sdk"))
    p = os.path.join(sdk, "emulator", "emulator")
    if not os.path.exists(p):
        sys.exit(f"no emulator at {p}: set ANDROID_HOME to the SDK")
    return p


def wait_for(ok, timeout):
    """ok() polled each second until it holds (True) or [timeout] s went by (False)"""
    end = time.time() + timeout
    while not ok():
        if time.time() > end:
            return False
        time.sleep(1)
    return True


def tail(path, n=3):
    """a log's last [n] lines, on one line"""
    try:
        with open(path, errors="replace") as f:
            return " / ".join(l.strip() for l in f.read().splitlines()[-n:])
    except OSError:
        return path


def record_age(out, pkg=None):
    """Device.recording's parse: out is the device's date, then dumpsys audio's recording monitor and
    events; an active riid whose lines name a package (pack:) counts only if one is pkg (the app's)"""
    pkg = pkg or PKG
    if not out:
        return None
    for riid in (m.group(1) for l in out if (m := re.match(r"\s*riid (\d+); active\? true", l))):
        mine = [l for l in out if re.search(rf"\briid:{riid}\b", l)]
        packs = [l for l in mine if "pack:" in l]
        if packs and not any(f"pack:{pkg} " in l + " " for l in packs):
            continue
        now = datetime.datetime.strptime("2000-" + out[0][:21], "%Y-%m-%d %H:%M:%S.%f")
        for l in mine:
            if re.match(r"\d\d-\d\d \d", l):
                then = datetime.datetime.strptime("2000-" + l[:18], "%Y-%m-%d %H:%M:%S:%f")
                return max(0.0, (now - then).total_seconds())
        return 0.0   # active, its start not logged yet: just opened
    return None


def grpc_port(port):
    """the emulator's gRPC port, from its console port (8554 for 5554: one each, two can run at once)"""
    return 8554 + int(port) - 5554


def need_mic():
    """grpcio, which Device.say plays clips through: a case that speaks calls it first, before its waits
    (host_calm, the hold) cost anything"""
    try:
        import grpc  # noqa: F401
    except ImportError:
        raise Blocked("没有 grpcio：Device.say 经模拟器的 gRPC 把语音片段放进麦克风（pip install grpcio）")


def _pb_varint(n):
    out = b""
    while True:
        b, n = n & 0x7F, n >> 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def _pb_field(num, v):
    """one protobuf field: int as a varint, bytes length-delimited (enough for AudioPacket)"""
    if isinstance(v, int):
        return _pb_varint(num << 3) + _pb_varint(v)
    return _pb_varint(num << 3 | 2) + _pb_varint(len(v)) + v


MIC_SETTLE_S = 0.5   # Device.say: a recording this old takes a stream (0.6 s held through VO-28; none crashed it)


class Mic:
    """a WAV (16-bit PCM) streamed to EmulatorController.injectAudio (emulator_controller.proto in the
    SDK's emulator/lib) in real time, on a thread; Popen's poll/wait/kill so a case holds it like the old
    pw-play. The messages are encoded by hand (no generated stubs, so only grpcio is needed)."""
    CHUNK_S = 0.02

    def __init__(self, port, wav):
        need_mic()
        import grpc
        import threading
        import wave
        with wave.open(wav) as w:
            if w.getsampwidth() != 2:
                raise Blocked(f"{wav}：不是 16 位 PCM")
            self.rate, ch, self.pcm = w.getframerate(), w.getnchannels(), w.readframes(w.getnframes())
        self.fmt = _pb_field(1, self.rate) + _pb_field(2, 0 if ch == 1 else 1) + _pb_field(3, 1) + _pb_field(4, 1)
        self.step = int(self.rate * self.CHUNK_S) * 2 * ch
        self.chan = grpc.insecure_channel(f"127.0.0.1:{port}")
        self.call = self.chan.stream_unary("/android.emulation.control.EmulatorController/injectAudio",
                                           request_serializer=lambda b: b, response_deserializer=lambda b: b)
        self.stop, self.error = threading.Event(), None
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _packets(self):
        t0 = time.time()
        for i, off in enumerate(range(0, len(self.pcm), self.step)):
            if self.stop.is_set():
                return
            lag = t0 + i * self.CHUNK_S - time.time()
            if lag > 0:
                time.sleep(lag)
            yield (_pb_field(1, self.fmt) + _pb_field(2, int(time.time() * 1e6))
                   + _pb_field(3, self.pcm[off:off + self.step]))

    def _run(self):
        try:
            self.call(self._packets(), timeout=120)
        except Exception as e:   # the emulator not started with -grpc, or gone
            self.error = e
        finally:
            self.chan.close()

    def poll(self):
        if self.thread.is_alive():
            return None
        if self.error:
            raise Blocked(f"模拟器 gRPC injectAudio 失败：{self.error}")
        return 0

    def wait(self, timeout=None):
        self.thread.join(timeout)
        if self.thread.is_alive():
            self.kill()   # else its thread streams the rest of the clip into the next case
            raise subprocess.TimeoutExpired("injectAudio", timeout)
        return self.poll()

    def kill(self):
        self.stop.set()
        self.thread.join(5)


def need_root(d):
    """adb root, then exit unless the shell is root: open_host, the keyboard and the cases' root
    calls run through it and would fail quietly as shell (an AVD whose adbd cannot be root)"""
    if d.sh("whoami").strip() == "root":
        return
    subprocess.run(["adb", "-s", d.serial, "root"], capture_output=True)
    subprocess.run(["adb", "-s", d.serial, "wait-for-device"], capture_output=True)
    if d.sh("whoami").strip() != "root":
        sys.exit(f"{d.serial}: adb root did not make the shell root (an AVD of a google_apis image, "
                 "not google_play, does)")
    for _ in range(30):   # adbd restarted: the package manager answers again before installing
        if "package:" in d.sh("pm path android"):
            break
        time.sleep(1)


def up(port="5556"):
    """emulator-<port> of the AVD from its snapshot, read-only (-read-only: two of one AVD at once,
    nothing saved back), so a device this starts is clean; one already up is left as it is"""
    port = emu_port(port)
    d = Device(port)
    if d.sh("getprop sys.boot_completed").strip() == "1":
        ours(d)
        print(f"{d.serial} already up")
    else:
        # one still booting is waited for if it is this AVD; another project's, or one not answering,
        # is left: starting ours on its port would fail and the wait below would take that one
        name = avd_name(d)
        if name not in ("", AVD):
            not_ours(d, name)
        emu = None
        log = os.path.join(OUT, "emu", f"emulator-{port}.log")
        if not name:
            os.makedirs(os.path.join(OUT, "emu"), exist_ok=True)
            # -gpu host: the Mac's GPU (its OpenGL, through emulator_env's shim with no display attached;
            # EMU_GPU=swiftshader_indirect for software, which the snapshot was not saved with: a cold boot).
            # -grpc: its controller, whose injectAudio is the microphone Device.say plays clips into
            # (audio stays on: -no-audio would drop the microphone)
            gpu = os.environ.get("EMU_GPU", "host")
            with open(log, "w") as f:   # the emulator holds its own copy of the file
                emu = subprocess.Popen([emulator_bin(), "-avd", AVD, "-port", port, "-snapshot", SNAPSHOT,
                                        "-no-snapshot-save", "-read-only", "-no-window", "-no-boot-anim",
                                        "-grpc", str(grpc_port(port)), "-audio", "none", "-gpu", gpu],
                                       stdout=f, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                       env=emulator_env(gpu), start_new_session=True)
        for _ in range(180):
            time.sleep(1)
            if d.sh("getprop sys.boot_completed").strip() == "1":
                break
            if emu is not None and emu.poll() is not None:   # no AVD or snapshot, a bad EMU_GPU, the port taken
                sys.exit(f"the emulator exited ({emu.returncode}) before booting: {tail(log)}")
        else:
            if emu is not None:   # ours, hung: not left running (3-4.5 GB each)
                emu.kill()
            sys.exit(f"{d.serial} did not boot in 3 min ({log})")
        ours(d)   # the port taken by another emulator between the look and the start: that one booted
        # the log polled: from the snapshot, boot_completed can come before the emulator's output is written
        if emu is not None and gpu == "host" and not wait_for(
                lambda: "OpenGL ES Translator (Apple" in tail(log, 400), 15):
            # booted from the snapshot without the GPU: no surfaceflinger, every screencap would time out
            emu.kill()
            sys.exit(f"{d.serial}: the emulator found no GPU ({log}); the shim not loaded (an emulator update "
                     "without allow-dyld-environment-variables?): EMU_GPU=swiftshader_indirect for software")
    need_root(d)
    if d.sh("getprop persist.sys.locale").strip() != "zh-CN":   # a snapshot made without it
        d.sh("setprop persist.sys.locale zh-CN; setprop sys.boot_completed 0; stop; start")
        time.sleep(5)
        for _ in range(120):
            if d.sh("getprop sys.boot_completed").strip() == "1":
                break
            time.sleep(1)
        time.sleep(3)
    if "package:" not in d.sh("pm path org.chromium.webview_shell"):
        # the shell's minSdk is 34: an older AVD (UAT_AVD=youmo29, Android 10) has none, its page cases blocked
        sdk = d.sh("getprop ro.build.version.sdk").strip()
        if not sdk.isdigit() or int(sdk) >= 34:   # an answer not a number: the check kept
            sys.exit(f"{d.serial}: no org.chromium.webview_shell (the test page's browser): the snapshot is "
                     "not uat/tools/avd.sh's")
        print(f"{d.serial}: below API 34, no test page browser: the cases on fields.html are blocked here")
    print(f"{d.serial} up")



def install(d, flavor="text"):
    # UAT_APK_REPO: another checkout's build under this one's cases (a red baseline: VO-23 on R15)
    apk_repo = os.environ.get("UAT_APK_REPO", REPO)
    apks = sorted((os.path.join(dp, f) for dp in [os.path.join(apk_repo, f"app/build/outputs/apk/{flavor}/{BUILD}")]
                   if os.path.isdir(dp) for f in os.listdir(dp) if ABI in f and f.endswith(".apk") and not f.endswith("-unsigned.apk")),
                  key=os.path.getmtime)
    if not apks:
        msg = f"no {flavor} {BUILD} {ABI} APK: BUILD_ABI={ABI} ./gradlew :app:assemble{flavor.capitalize()}{BUILD.capitalize()}"
        if BUILD == "release":
            msg += " (signed: the release key's environment set; an -unsigned.apk does not install)"
        if d.case:   # inside a case (SET-01): that case is blocked, the run goes on
            raise Blocked(msg)
        sys.exit(msg)
    # the same version name is rebuilt between commits: the installed file is compared, not the name
    import hashlib
    h = hashlib.sha1()
    with open(apks[-1], "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    # one 幽默输入法 on the device: with the debug and the release build both there, the system's
    # picker lists two and the setup wizard (SET-01) can take the other one
    other = "io.github.sraw.youmo" if BUILD == "debug" else "io.github.sraw.youmo.debug"
    if d.sh(f"pm path {other}").strip():
        d.sh(f"pm uninstall {other}")
    path = d.sh(f"pm path {PKG}").strip().split(":", 1)[-1]
    on_device = d.sh(f"sha1sum {path}").split()[:1] if path else []
    if on_device != [h.hexdigest()]:
        d.adb("install", "-r", apks[-1], check=True, timeout=600)
    d.sh(f"ime enable {IME}; ime set {IME}")




def down(port="5556"):
    """emulator-<port> killed, if it is this AVD's (another project's emulator on the port is left)"""
    d = Device(emu_port(port))
    ours(d)
    subprocess.run([os.path.join(UAT, "tools", "emu-keyboard.sh"), "down"], capture_output=True,
                   env={**os.environ, "ANDROID_SERIAL": d.serial})
    d.adb("emu", "kill")
    for _ in range(30):
        if d.serial not in subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout:
            break
        time.sleep(1)
    print(f"{d.serial} down")



def version(d):
    m = re.search(r"versionName=(\S+)", d.sh(f"dumpsys package {PKG} | grep -m1 versionName"))
    return m.group(1) if m else "?"


def start_server():
    """the test page on 127.0.0.1 only; returns the process (stopped by its pid)"""
    s = __import__("socket").socket()
    busy = s.connect_ex(("127.0.0.1", HTTP_PORT)) == 0
    s.close()
    if busy:   # this run's port taken: its fixtures may be another checkout's
        sys.exit(f"127.0.0.1:{HTTP_PORT} is in use: stop that server or set UAT_HTTP_PORT")
    p = subprocess.Popen([sys.executable, "-m", "http.server", str(HTTP_PORT), "--bind", "127.0.0.1",
                          "-d", os.path.join(UAT, "fixtures")],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(0.8)
    return p


def cmd_run(a):
    # every script loaded (the sheets and the results file show all cases of the run), these run
    load_scripts()
    areas = a.areas.split(",") if a.areas else None
    cases = [c for c in CASES.values() if (not a.ids or c.id in a.ids.split(","))
             and (not areas or c.area in areas)]
    if not cases:
        sys.exit("no cases")
    if a.devices == "auto":   # as many as memory allows, started here and brought down after
        ports = auto_ports()
        try:   # a Ctrl-C while they boot too: each in its own session, the signal does not reach them
            return run_on(a, cases, up_many(ports))
        finally:
            down_all(ports)
    return run_on(a, cases, [emu_port(p) for p in a.devices.split(",")])


def up_many(ports):
    """emulators on [ports] booted together; if any did not boot, all are brought down and the run exits"""
    fails = {}
    def one(p):
        try:
            up(p)
        except SystemExit as e:
            fails[p] = e.code
        except Exception as e:   # the shim's build, adb timing out: not taken for a device that is up
            fails[p] = f"{type(e).__name__}: {e}"
    threads = [threading.Thread(target=one, args=(p,), daemon=True) for p in ports]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    if fails:
        down_all(ports)   # those booted, and those that failed after booting (root, the page's browser)
        sys.exit("; ".join(f"{p}: {m}" for p, m in fails.items()))
    return ports


def down_all(ports):
    """down() each of [ports]; one that will not go down does not keep the others up"""
    for p in ports:
        try:
            down(p)
        except (SystemExit, Exception) as e:
            print(f"emulator-{p}: not brought down ({e})")


def run_on(a, cases, ports):
    global HTTP_PORT
    HTTP_PORT = HTTP_PORT or http_port(ports[0])
    for p in ports:
        d = Device(p)
        if d.sh("getprop sys.boot_completed").strip() != "1":
            sys.exit(f"{d.serial} is not up (uat.py up --port {p})")
        ours(d)
        need_root(d)   # one started by hand
        install(d, a.flavor)
        d.adb("reverse", "tcp:8000", f"tcp:{HTTP_PORT}", check=True)
    ver = version(Device(ports[0]))
    name = f"{datetime.date.today().isoformat()}-emulator-{ver}"
    run = Run(name, [c.id for c in cases])
    # a case since removed from the scripts leaves the record, or its last row comes back each run
    for cid in [k for k in run.results if k not in CASES]:
        del run.results[cid]
    server = start_server()
    t0 = time.time()
    try:
        sch = Scheduler(cases, run, ports, a.flavor)
        threads = [threading.Thread(target=sch.device_loop, args=(p,)) for p in ports]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
    finally:
        if server:
            server.terminate()
    wall = time.time() - t0
    for c in cases:
        if c.id not in run.results:
            run.record(c, "阻塞", "没跑到（设备停了）", "-", 0)
    sheets = build_sheets(run, cases)
    p = write_results(run, cases, ver, ports, wall, sheets, a.flavor)
    counts = {}
    for cid in run.ids:
        st = run.results[cid][0]
        counts[st] = counts.get(st, 0) + 1
    nshots = sum(1 for x in run.shots if x[0] in run.ids)
    away = counts.pop("在别处跑", 0)
    print(f"{len(run.ids) - away} cases in {wall:.0f} s: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items()))
          + (f"; {away} run elsewhere (在别处跑)" if away else ""))
    print(f"results {os.path.relpath(p, REPO)}; {len(sheets)} sheets, {nshots} shots in {os.path.relpath(run.dir, REPO)}/")


# ---------------------------------------------------------------- the cases asserted on the JVM

UAT_RE = re.compile(r'@Uat\(([^)]*)\)\s*(?:@\w+(?:\([^)]*\))?\s*)*fun\s+(?:`([^`\n]+)`|(\w+))\s*\(')


CLASS_RE = re.compile(r"^([ \t]*)(?:(?:internal|private|open|abstract|data|inner)\s+)*class\s+(\w+)", re.M)


def enclosing_class(classes, pos, indent):
    """JUnit's name of the class a method at [pos] indented [indent] is in, from [classes] [(pos, indent,
    name)] in order: a nested class is Outer$Inner, as the XML names it (formatted code: a class's members
    sit deeper than it, and a class ends where one at its depth or shallower begins)"""
    stack = []
    for at, ind, name in classes:
        if at > pos:
            break
        while stack and stack[-1][0] >= ind:
            stack.pop()
        stack.append((ind, name))
    return "$".join(name for ind, name in stack if ind < indent)


def uat_tests(roots):
    """{case id: [(test class, method)]} from @Uat("…") on test methods under [roots]"""
    found = {}
    for root in roots:
        for dirpath, _, files in os.walk(root):
            for name in files:
                if not name.endswith(".kt"):
                    continue
                text = open(os.path.join(dirpath, name), encoding="utf-8").read()
                pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
                classes = [(m.start(), len(m.group(1).expandtabs()), m.group(2)) for m in CLASS_RE.finditer(text)]
                if not classes:
                    continue
                # UAT_RE falling short drops a case without a word: a method name it cannot read
                matched = len(UAT_RE.findall(text))
                if matched != text.count("@Uat("):
                    print(f"warning: {name}: {text.count('@Uat(')} @Uat, {matched} read", file=sys.stderr)
                for m in UAT_RE.finditer(text):
                    line = text.rfind("\n", 0, m.start()) + 1
                    indent = len(text[line:m.start()].expandtabs())
                    cls = enclosing_class(classes, m.start(), indent)
                    if not cls:
                        continue
                    fqcn = (pkg.group(1) + "." if pkg else "") + cls
                    for cid in re.findall(r'"([^"]+)"', m.group(1)):
                        found.setdefault(cid, []).append((fqcn, m.group(2) or m.group(3)))
    return found


def named_tests(needs):
    """the "Class.method" names a needs=jvm text lists ("Class" alone names no method), separated by 、"""
    return [n.strip() for n in needs.split(":", 1)[1].split("、") if "." in n]


def needs_problems(cases, found):
    """for each needs=jvm case in [cases] {id: needs}: the tests its needs= names that do not carry its
    @Uat in [found] (uat_tests'), so their results would never reach the case"""
    have = {}
    for cid, tests in found.items():
        for cls, method in tests:
            have.setdefault(f"{cls.rsplit('.', 1)[-1]}.{method}", set()).add(cid)
    out = []
    for cid, needs in sorted(cases.items()):
        if place(needs) != "jvm":
            continue
        for n in named_tests(needs):
            if n not in have:
                out.append(f"{cid}: needs= names {n}, which has no @Uat")
            elif cid not in have[n]:
                out.append(f"{cid}: needs= names {n}, whose @Uat is {'、'.join(sorted(have[n]))}'s")
    return out


def junit_results(dirs):
    """{(class, method): (state, seconds, message)} from the JUnit XML under [dirs]"""
    import xml.etree.ElementTree as ET
    out = {}
    for d in dirs:
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            if not (name.startswith("TEST-") and name.endswith(".xml")):
                continue
            for tc in ET.parse(os.path.join(d, name)).getroot().iter("testcase"):
                bad = tc.find("failure")
                if bad is None:
                    bad = tc.find("error")
                state = "跳过" if tc.find("skipped") is not None else ("失败" if bad is not None else "通过")
                msg = ((bad.get("message") or "").splitlines() or [""])[0][:200] if bad is not None else ""
                key = (tc.get("classname"), tc.get("name"))
                # the same test in both flavors' results: the worse counts (a text-only failure must
                # not be hidden by the voice run read after it)
                if key not in out or WORSE.index(state) < WORSE.index(out[key][0]):
                    out[key] = (state, float(tc.get("time") or 0), msg)
    return out


WORSE = ("失败", "阻塞", "跳过", "通过")


def jvm_record(found, results, expected=()):
    """{case id: (state, seen)}: each case's tests' results; a case with a test that did not run is 阻塞,
    and so is each of [expected] (the scripts' needs=jvm cases) with no @Uat test at all: a test renamed
    or overwritten must not drop its case from the record without a word"""
    rec = {cid: ("阻塞", "没有 @Uat 测试") for cid in expected if cid not in found}
    for cid, tests in sorted(found.items()):
        states, seen = [], []
        for cls, method in tests:
            r = results.get((cls, method))
            short = f"{cls.rsplit('.', 1)[-1]}.{method}"
            if r is None:
                states.append("阻塞")
                seen.append(f"{short} 没有结果")
            else:
                states.append(r[0])
                seen.append(f"{short} {r[1]:.1f} s" + (f"：{r[2]}" if r[2] else ""))
        state = next((s for s in WORSE if s in states), "通过")
        rec[cid] = (state, "；".join(seen))
    return dict(sorted(rec.items()))


def cmd_jvm(a):
    """the cases the app's and ime-core's JVM tests assert, recorded as a run of their own"""
    roots = [os.path.join(REPO, p) for p in ("app/src/test", "app/src/testText", "app/src/testVoice", "lib/ime-core/src/test",
                                                "lib/ime-eval/src/uatTest")]
    dirs = [os.path.join(REPO, p) for p in a.results.split(",")]
    load_scripts()
    expected = [c.id for c in CASES.values() if place(c.needs) == "jvm"]
    found = uat_tests(roots)
    rec = jvm_record(found, junit_results(dirs), expected)
    problems = needs_problems({c.id: c.needs for c in CASES.values()}, found)
    for p in problems:
        print("problem:", p)
    ver = subprocess.run(["git", "-C", REPO, "describe", "--tags"], capture_output=True, text=True).stdout.strip() or "unknown"
    name = f"{datetime.date.today().isoformat()}-jvm-{ver}"
    counts = {}
    for st, _ in rec.values():
        counts[st] = counts.get(st, 0) + 1
    lines = [f"# {name}", "", f"- 跑法：`uat/tools/uat.py jvm`（JUnit 结果：{', '.join(os.path.relpath(d, REPO) for d in dirs)}）；"
             f"共 {len(rec)} 条：" + "，".join(f"{k} {v}" for k, v in counts.items()), "",
             "| 编号 | 结果 | 测试 |", "|---|---|---|"]
    lines += [f"| {cid} | {st} | {seen.replace('|', '/').replace(chr(10), ' ')} |" for cid, (st, seen) in rec.items()]
    path = os.path.join(REPO, "uat", "runs", name + ".md")
    write_atomic(path, "\n".join(lines) + "\n")
    print(f"{len(rec)} cases: " + ", ".join(f"{v} {k}" for k, v in counts.items()) + f"; {os.path.relpath(path, REPO)}")
    return 0 if all(st == "通过" for st, _ in rec.values()) and not problems else 1


def place(needs):
    """where a case runs, from its needs=: device (the emulator runs), or one of ELSEWHERE's keys"""
    if not needs:
        return "device"
    kinds = needs.split(":", 1)[0].split(",")
    for k in ELSEWHERE:
        if k in kinds:
            return k
    return "device"   # skip:/block: stay in the emulator runs, as 跳过/阻塞


def tally(cases, priority, jvm_line):
    """(counts, problems) for the README: cases {id: needs}, priority {id: "P0"…}, jvm_line {ids with a
    **JVM** line in their .md}. smoke = still on the device but asserted on the JVM too. A needs=jvm case
    without the .md line, a case missing from the .md, or one in the .md with no script (registered
    as NO_SCRIPT; it used to be blocked on every run and never reported) is a problem: the two would tell different stories"""
    n = {k: 0 for k in ("device", *ELSEWHERE, "smoke")}
    prios, problems = {}, []
    for cid, needs in sorted(cases.items()):
        where = place(needs)
        n[where] += 1
        if where == "device" and cid in jvm_line:
            n["smoke"] += 1
        if where == "jvm" and cid not in jvm_line:
            problems.append(f"{cid}: needs=jvm but no **JVM** line in its .md")
        if needs == NO_SCRIPT:
            problems.append(f"{cid}: in the .md but no script (register it, needs= where it runs)")
        if cid not in priority:
            problems.append(f"{cid}: has a script but no case in the .md")
        else:
            prios[priority[cid]] = prios.get(priority[cid], 0) + 1
    n["total"] = len(cases)
    n["prios"] = dict(sorted(prios.items()))
    return n, problems


def counts_line(n):
    """the README's sentence of counts"""
    elsewhere = n["total"] - n["device"]
    return (f"现在共 {n['total']} 条（" + "、".join(f"{k} {v}" for k, v in n["prios"].items()) + "）："
            f"模拟器上跑 {n['device']} 条（其中 {n['smoke']} 条在 JVM 上也断言，设备上留作冒烟或照跑），"
            f"在别处跑 {elsewhere} 条（" + "、".join(f"{v} {n[k]}" for k, v in ELSEWHERE.items()) + "）。")


def cmd_counts(a):
    """the README's counts from the scripts' needs= and the .md files' **JVM** lines; 1 when the README says otherwise"""
    load_scripts()
    priority, jvm_line = {}, set()
    for f in sorted(os.listdir(UAT)):
        if not re.match(r"\d\d-.*\.md$", f):
            continue
        cur = None
        for line in open(os.path.join(UAT, f), encoding="utf-8"):
            m = re.match(r"### (\S+) \[(P\d)\]", line)
            if m:
                cur = m.group(1)
                priority[cur] = m.group(2)
            elif cur and line.startswith("- **JVM**"):
                jvm_line.add(cur)
    n, problems = tally({c.id: c.needs for c in CASES.values()}, priority, jvm_line)
    line = counts_line(n)
    print(line)
    for p in problems:
        print("problem:", p)
    readme = open(os.path.join(UAT, "README.md"), encoding="utf-8").read()
    if line not in readme:
        print("README.md says otherwise: put the line above in place of its 现在共 … line")
        return 1
    return 1 if problems else 0


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--areas")
    r.add_argument("--ids")
    r.add_argument("--devices", default=",".join(PORTS))
    r.add_argument("--flavor", default="text", choices=["text", "voice"])
    l = sub.add_parser("list")
    l.add_argument("--areas")
    for n in ("up", "down"):
        sub.add_parser(n).add_argument("--port", default=PORTS[1])
    sub.choices["up"].add_argument("--count", help="N or auto: that many on free ports (auto: as many as memory allows)")
    sub.add_parser("emu-up")
    sub.add_parser("emu-down")
    m = sub.add_parser("mark")
    m.add_argument("run")
    m.add_argument("id")
    m.add_argument("state", choices=["通过", "失败", "阻塞", "跳过"])
    m.add_argument("note", nargs="?", default="")
    f = sub.add_parser("flakes")
    f.add_argument("runs", nargs="*")
    j = sub.add_parser("jvm")
    j.add_argument("--results", default="app/build/test-results/testTextDebugUnitTest,app/build/test-results/testVoiceDebugUnitTest,"
                                         "lib/ime-core/build/test-results/test,"
                                         "lib/ime-eval/build/test-results/uatTest")
    sub.add_parser("counts")
    a = ap.parse_args()
    if a.cmd == "run":
        cmd_run(a)
    elif a.cmd == "list":
        load_scripts(a.areas.split(",") if a.areas else None)
        for c in sorted(CASES.values(), key=lambda c: (c.group, c.order(), c.id)):
            print(c.group, c.id, {0: "read", 1: "learn-clean", 2: "learn", 3: "mutate"}[c.order()],
                  "visual" if c.visual else "", c.needs or "")
    elif a.cmd == "up" and a.count:
        print("up: " + ",".join(up_many(auto_ports(a.count))))
    elif a.cmd == "up":
        up(a.port)
    elif a.cmd == "down":
        down(a.port)
    elif a.cmd == "emu-up":
        up(PORTS[0])
    elif a.cmd == "emu-down":
        down(PORTS[0])
    elif a.cmd == "mark":
        mark(a.run, a.id, a.state, a.note)
    elif a.cmd == "flakes":
        flakes(a.runs)
    elif a.cmd == "jvm":
        sys.exit(cmd_jvm(a))
    elif a.cmd == "counts":
        sys.exit(cmd_counts(a))


sys.modules.setdefault("uat", sys.modules[__name__])

if __name__ == "__main__":
    main()
