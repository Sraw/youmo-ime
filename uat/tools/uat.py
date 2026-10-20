#!/usr/bin/env python3
"""UAT runner for redroid: groups of cases per baseline, machine checks, contact sheets, two devices.

  uat.py up | down                 the second redroid (youmo-redroid2, 127.0.0.1:5557)
  uat.py emu-up | emu-down         the emulator (emulator-5554, AVD dev/avd/youmo_kb) and its microphone
  uat.py run [--areas 14,06] [--ids ST-01,PY-02] [--devices 5556,5557] [--flavor text|voice]
  uat.py mark <run> <id> 通过|失败 [note]
  uat.py list [--areas …]          the registered cases, their group and kind

Cases live in uat/scripts/<file stem>.py (one per uat/*.md); the .md stays the source of truth:
a script follows its case's 前提/操作/预期, and the caption under a screenshot is the case's 预期.
Python 3 stdlib + PIL. Only `adb shell input tap/swipe/motionevent/keyevent/text`, uiautomator,
am/pm/ime/settings drive the device: never a uinput device (redroid shares the host's kernel).
The emulator (--devices 5554, for 17 实体键盘 and 12 语音) is a VM with its own kernel: there, and only
there, a uinput keyboard (uat/tools/emu-keyboard.sh) and root over `adb root`; its microphone is the
host's PulseAudio source uat-mic.monitor, which `Device.say` plays the clips of voice-clips.py into.
Output is kept short on purpose (counts and paths): an agent reading it re-reads its context.
"""
import argparse
import datetime
import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import threading
import time
import traceback
import warnings
import xml.etree.ElementTree as ET

warnings.filterwarnings("ignore", category=DeprecationWarning)

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
UAT = os.path.join(REPO, "uat")
OUT = os.path.join(REPO, "dev", "uat-out")
# UAT_BUILD=release runs the cases on the x86_64 release APK (a release's gate: R8's code, its own package)
BUILD = os.environ.get("UAT_BUILD", "debug")
PKG = "io.github.sraw.youmo" + (".debug" if BUILD == "debug" else "")
IME = f"{PKG}/org.fcitx.fcitx5.android.input.FcitxInputMethodService"
MAIN = f"{PKG}/org.fcitx.fcitx5.android.ui.main.MainActivity"
BROWSER = "org.chromium.webview_shell/.WebViewBrowserActivity"
IMAGE = "redroid/redroid:14.0.0_64only-latest"
# serial port -> container; only these two are ever started, stopped or rooted by this tool
CONTAINERS = {"5556": "youmo-redroid", "5557": "youmo-redroid2"}
EMU_PORT = "5554"   # the emulator: emulator-5554, no container
AVD_HOME, AVD = os.path.join(REPO, "dev", "avd"), "youmo_kb"
MIC = "uat-mic"   # a PulseAudio (PipeWire) null sink; the emulator records its monitor
SPEAKER = "uat-out"   # another, the emulator's output: not into uat-mic, where it came back as speech
CLIPS = os.path.join(OUT, "voice-clips")
HTTP_PORT = 8765  # host side of `adb reverse tcp:8000`
FONT = "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf"   # CJK; it draws no Latin
FONT_LATIN = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


class Fail(Exception):
    pass


class Blocked(Exception):
    pass


class Skip(Exception):
    pass


# ---------------------------------------------------------------- the case registry

CASES = {}


class Case:
    def __init__(self, fn, id, base, page, mutates, clean, visual, needs):
        self.fn, self.id, self.base, self.page = fn, id, base, page
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
        if "emu" in kinds and not d.emu:
            return Skip, why
        if "voice" in kinds and d.flavor != "voice":
            return Skip, "文字版没有语音输入（--flavor voice 装语音版再跑）"
        return None

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


def case(id, base="B0", page=None, mutates=False, clean=False, visual=False, needs=None):
    """mutates: False (no commit, nothing changed), "learn" (commits text the engine may learn
    from), True (changes settings, words, the system). clean: the case needs no learning before it.
    visual: part of the 预期 is only seen on a screenshot -> 待看图 unless a machine check failed.
    needs: "skip:<why>" / "block:<why>" when redroid cannot run it at all; "emu:<why>" for the emulator
    only, "voice:<why>" for the voice build only, "emu,voice:<why>" both: skipped elsewhere, no reset."""
    if page is None:
        page = "设置" if base == "B0-设置" else "text"

    def deco(fn):
        CASES[id] = Case(fn, id, base, page, mutates, clean, visual, needs)
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
        for c in list(CASES.values()):
            if c.id in md:
                c.title, _, c.expect = md[c.id]


# ---------------------------------------------------------------- one device

class Node:
    __slots__ = ("text", "desc", "rid", "cls", "pkg", "b", "checked", "focused", "selected",
                 "clickable", "parent", "kids", "enabled", "scrollable", "wtype")

    def __init__(self, e, parent):
        # an empty field reports its hint as its text
        self.text = "" if e.get("hint") == "true" else (e.get("text") or "")
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
            return n.wtype == "2"  # AccessibilityWindowInfo.TYPE_INPUT_METHOD
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
        self.emu = self.port == EMU_PORT
        self.serial = f"emulator-{self.port}" if self.emu else f"127.0.0.1:{self.port}"
        self.container = None if self.emu else CONTAINERS[self.port]
        self.run = run
        self.case = None
        self._keys = None
        # unique page URLs across runs: WebView caches a URL it loaded before (heuristic freshness)
        self.reload_n = int(time.time() * 1000) * 10 + int(port) % 10
        self.page = None

    # --- adb
    def adb(self, *args, binary=False, check=False, timeout=60):
        r = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True,
                           text=not binary, timeout=timeout)
        if check and r.returncode:
            raise Blocked(f"adb {' '.join(args)}: {(r.stderr or '')[:200]}")
        return r.stdout

    def sh(self, cmd):
        return self.adb("shell", cmd)

    def root(self, cmd):
        if self.emu:   # adbd runs as root there (emu-up)
            return self.sh(cmd)
        return subprocess.run(["docker", "exec", self.container, "sh", "-c", cmd],
                              capture_output=True, text=True).stdout

    # --- the emulator's hardware keyboard and microphone
    def kb(self, *args):
        """uat/tools/emu-keyboard.sh up|down|type …|key …|combo …, emulator only (the script
        refuses anything else too)"""
        if not self.emu:
            raise Blocked("实体键盘只在模拟器上（redroid 里的 uinput 键盘会到宿主机）")
        r = subprocess.run([os.path.join(UAT, "tools", "emu-keyboard.sh"), *args], capture_output=True,
                           text=True, env={**os.environ, "ANDROID_SERIAL": self.serial}, timeout=60)
        if r.returncode:
            raise Blocked(f"emu-keyboard.sh {' '.join(args)}: {(r.stderr or r.stdout).strip()[:200]}")
        self._keys = None
        return r.stdout

    def kb_plugged(self):
        return "Test USB Keyboard" in self.sh("dumpsys input | grep -m1 'Test USB Keyboard'")

    def say(self, clip, wait=True):
        """play dev/uat-out/voice-clips/<clip>.wav into the emulator's microphone; wait=False: in
        the background (returns the player)"""
        if not self.emu:
            raise Blocked("没有麦克风输入：只在模拟器上")
        f = os.path.join(CLIPS, f"{clip}.wav")
        if not os.path.exists(f):
            raise Blocked(f"没有 {f}：先跑 uat/tools/voice-clips.py")
        p = subprocess.Popen(["pw-play", "--target", MIC, f], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if wait:
            p.wait(30)
        return p

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

    def screen(self):
        if getattr(self, "fwd", None):
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

    def preedit(self, s=None):
        s = s or self.screen()
        for n in s.ime():
            if n.cls == "HorizontalScrollView":
                t = n.texts()
                return t[0] if t else ""
        return ""

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

    def wait_text(self, text, timeout=15, where="app", contains=False):
        return self.wait(lambda s: s.find(contains=text, where=where) if contains
                         else s.find(text=text, where=where) or s.find(desc=text, where=where),
                         timeout, what=f"「{text}」")

    # --- touching
    def tap(self, xy, after=0.3):
        self.sh(f"input tap {xy[0]} {xy[1]}")
        time.sleep(after)

    def taps(self, points, gap=0.05, after=0.4):
        cmd = f"; sleep {gap}; ".join(f"input tap {x} {y}" for x, y in points)
        if cmd:
            self.sh(cmd)
        time.sleep(after)

    def swipe(self, a, b, ms=300, after=0.4):
        self.sh(f"input swipe {a[0]} {a[1]} {b[0]} {b[1]} {ms}")
        time.sleep(after)

    def hold(self, xy, ms):
        self.swipe(xy, xy, ms)

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

    def tap_text(self, text, where="app", contains=False, scroll=True, after=0.8):
        s = self.screen()
        n = self.visible(text, s, where, contains)
        if not n and scroll:
            self.scroll_top()
            for _ in range(10):
                s = self.screen()
                n = self.visible(text, s, where, contains)
                if n or not self.scroll(s):
                    break
        if n:
            self.tap(n.c, after)
            return n
        raise Blocked(f"no 「{text}」 on screen")

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
        return self.screen().texts() != before

    def scroll_top(self):
        for _ in range(10):
            if not self.scroll(up=True):
                return

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
        kb = self.wait(lambda s: s.find(rid="keyboard_view", where="ime"), what="the keyboard")
        names = {"button_space": "space", "button_backspace": "bksp", "button_caps": "shift",
                 "button_return": "enter", "button_lang": "globe", "button_comma": ",",
                 "button_period": "."}
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
            for x in t[1:]:   # the nine keys: "ABC" over "2"
                if len(x) == 1 and x.isdigit():
                    k.setdefault(x, n.c)
        self._keys = k
        return k

    def key_node(self, label, s=None):
        s = s or self.screen()
        kb = s.find(rid="keyboard_view", where="ime")
        for n in kb.walk() if kb else []:
            if n.clickable:
                t = [m.text for m in n.walk() if m.text]
                if t and (t[0].lower() == label or label.isdigit() and label in t[1:]):
                    return n
        return None

    def type(self, keys, after=0.5):
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
        self.taps(pts, after=after)

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

    def press(self, name, n=1, after=0.5):
        k = self.keys(refresh=True)
        if name not in k:
            k = self.keys(refresh=True)
        if name not in k:
            raise Blocked(f"no key {name}")
        self.taps([k[name]] * n, after=after)

    def space(self):
        self.press("space")

    def bksp(self, n=1):
        self.press("bksp", n)

    def enter(self):
        self.press("enter")

    def cands(self, timeout=8, want=None):
        """the candidate bar, waiting for it to show (and, with want, to contain want)"""
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
        s = self.screen()
        row = next((n for n in s.ime() if n.cls == "HorizontalScrollView"), None)
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
            if "|" in p:   # the cursor inside the composing row: backspace stops at its start
                break
            n = len(p) + len(t)
            self.press("bksp", min(n + 1, 40), after=0.3)
        if self.page:
            self.open_page(self.page)
            if not self.preedit() and not self.field_text():
                return
        raise Blocked("could not empty the field")

    # --- pages
    def open_page(self, page="text"):
        """the test page showing one field, tapped: the keyboard is up"""
        self.reload_n += 1
        url = f"http://127.0.0.1:8000/fields.html?r={self.reload_n}#{page}"
        self.sh(f"am start -a android.intent.action.VIEW -d '{url}' -n {BROWSER}")
        def ready(s):
            for n in s.app():
                if n.cls == "EditText" and n.pkg == "org.chromium.webview_shell" and n.rid != "url_field":
                    return n
        try:
            f = self.wait(ready, 20, what="the test page")
        except Blocked:   # seen once after the system file picker: the browser did not come back
            self.sh(f"am force-stop org.chromium.webview_shell; am start -a android.intent.action.VIEW "
                    f"-d '{url}' -n {BROWSER}")
            f = self.wait(ready, 20, what="the test page")
        # near the field's bottom-right corner: in a field with text the cursor lands at its end
        at = (f.b[2] - 40, f.b[3] - 25) if f.b[3] - f.b[1] > 150 else f.c
        for i in range(3):
            self.tap(at, 0.8)
            if self.emu and self.kb_plugged():
                # a hardware keyboard typed on: a tap in a web field tells the input method nothing,
                # the touch keyboard comes back by the button by the cursor (HK-03)
                s = self.screen()
                b = s.find(desc="显示屏幕键盘", where="ime")
                if b and not s.find(rid="keyboard_view", where="ime"):
                    self.tap(b.c, 0.8)
            try:
                self.wait(lambda s: s.find(rid="keyboard_view", where="ime"), 8 + 8 * i, what="the keyboard")
                break
            except Blocked:
                if i == 2:
                    raise
                ensure_ime(self)
        self.page = page
        self._keys = None

    def at_home(self, s):
        """the settings home: the app's name as the title and the search button"""
        return (s.find(desc="搜索设置") or s.find(desc="Search settings")) and \
            any(n.text.startswith(("幽默输入法", "Youmo")) and n.parent and n.parent.rid == "toolbar"
                for n in s.app())

    def open_settings(self):
        """the settings home: back out of the app's pages rather than relaunching it (a relaunch
        with CLEAR_TASK over itself left a finishing activity that took the next tap away)"""
        for _ in range(8):
            if "MainActivity" not in self.top_activity():
                self.sh(f"am start -W -n {MAIN}")
                time.sleep(0.5)
            s = self.screen()
            if self.at_home(s):
                return
            self.back()
        raise Blocked("could not reach the settings home")

    def back(self, after=0.8):
        self.sh("input keyevent KEYCODE_BACK")
        time.sleep(after)

    def im_label(self, s=None):
        s = s or self.screen()
        sp = s.find(rid="button_space", where="ime") or next(
            (n for n in s.ime() if n.desc.startswith("空格")), None)
        return sp.desc if sp else ""

    def switch_im(self, label):
        """the globe key until the space key names this input method"""
        for _ in range(8):
            if label in self.im_label():
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
        """0 portrait, 1 landscape. redroid's display does not rotate (user_rotation is ignored), so
        landscape is the display resized to 2340x1080: the apps get a landscape configuration
        (w851dp, "land") and go through the same configuration change as on a turn"""
        self.sh("wm size 2340x1080" if r else "wm size reset")
        self.rot = r
        time.sleep(2.0)
        self._keys = None

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

    # --- settings rows
    def row(self, title, s=None):
        s = s or self.screen()
        n = s.find(text=title, rid="title") or s.find(text=title)
        return n.row() if n else None

    def goto(self, *titles):
        """from where we are, tap each title in turn (scrolling to it)"""
        for t in titles:
            self.tap_text(t, after=1.0)

    def switch(self, title, on):
        """set a switch/checkbox preference; returns whether it was changed"""
        for _ in range(8):
            s = self.screen()
            r = self.row(title, s)
            if r:
                w = [n for n in r.walk() if n.cls in ("Switch", "SwitchCompat", "CheckBox", "SwitchMaterial")]
                if not w:
                    raise Blocked(f"「{title}」 has no switch")
                if w[0].checked != on:
                    self.tap(r.c, 0.8)
                    r2 = self.row(title)
                    w2 = [n for n in r2.walk() if n.cls == w[0].cls] if r2 else []
                    if not w2 or w2[0].checked != on:
                        raise Fail(f"「{title}」 did not turn {'on' if on else 'off'}")
                    return True
                return False
            if not self.scroll(s):
                break
        raise Blocked(f"no setting 「{title}」")

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
        png = png or self.adb("exec-out", "screencap", "-p", binary=True)
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
# (the space key's description names it); B0-英文 is B0 and the globe key once
IM_BASES = {"B0-九键": ("九键拼音", "九键"), "B0-五笔": ("五笔", "五笔"), "B0-二笔": ("二笔", "二笔"),
            "B0-仓颉": ("仓颉", "仓颉"), "B0-双拼": ("双拼", "双拼"), "B0-手写": ("手写", "手写"),
            "B0-自然码": ("自然码", "自然码"), "B0-五笔拼音": ("五笔拼音", "五笔拼音"), "B0-英文": (None, "英文")}


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
        name, label = IM_BASES[base]
        if name:
            add_im(d, name)
            d.open_page(page)
        d.switch_im(label)
    elif base == "B0-横屏":
        d.rotate(1)
        d.open_page(page)
    elif base not in ("B0", "B0-实体", "B0-语音"):
        raise Blocked(f"unknown baseline {base}")


def plug(d, base):
    """B0-实体: the emulator's USB keyboard plugged in; any other baseline there: unplugged"""
    if d.emu:
        want = base == "B0-实体"
        if d.kb_plugged() != want:
            d.kb("up" if want else "down")
            time.sleep(1)


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
    if page in PREFILLED:   # its text is part of the case: a fresh page
        d.open_page(page)
    else:
        if d.page != page or not d.field(s) or not s.find(rid="keyboard_view", where="ime"):
            d.open_page(page)
        d.clear()
    d.switch_im(IM_BASES[base][1] if base in IM_BASES else "拼音")


# ---------------------------------------------------------------- helpers for the case scripts

# Where the tree tells too little (the emoji tabs are icons with no text; a panel's rows have no ids),
# a few scripts go by place: the portrait layout of 1080x2340 at 440 dpi, the default keyboard height
# (redroid and the emulator alike). Named once here; a new device size or keyboard height changes them.
BAR_BOTTOM = 1560                     # the toolbar / candidate bar's lower edge: the keys or a panel below
PANEL_TOP, PANEL_BOTTOM = 1550, 2090  # a panel's content (symbols, emoji, clipboard rows)
GRID_TOP, GRID_BOTTOM = 1600, 2040    # the emoji grid, without its tabs and bottom row


def emoji_tab_xy(i):
    """the emoji panel's i-th category tab (0-based): icons only"""
    return 54 + 108 * i, 1491


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
    d.tap_text(title, after=1.0)
    s = d.screen()
    n = next((n for n in s.app() if n.text == option), None) or \
        next((n for n in s.app() if n.text.startswith(option) and n.text != title), None)
    if not n:
        raise Blocked(f"「{title}」里没有「{option}」：{s.texts()[:12]}")
    d.tap(n.c, 1.0)
    ok = d.screen().find(text="确定")
    if ok:
        d.tap(ok.c, 1.0)


def path_steps(pts, per_hop=6):
    """a finger down on the first point and over the others (touch steps), not lifted"""
    steps = [("down", *pts[0])]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        steps += [("move", x0 + (x1 - x0) * i // per_hop, y0 + (y1 - y0) * i // per_hop)
                  for i in range(1, per_hop + 1)]
    return steps


def glide_shot(d, pts, note):
    """glide over pts, a screenshot of the keyboard before lifting (the trace), then lift"""
    d.touch(*path_steps(pts))
    d.shot("kb", note)
    d.touch(("up", *pts[-1]))
    time.sleep(0.8)


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


def menu_tap(d, text):
    """an item of the popup menu showing (the app's first, then any window's)"""
    s = d.screen()
    n = next((n for n in s.nodes if n.text == text and n.pkg == PKG), None) or \
        next((n for n in s.nodes if n.text == text), None)
    if not n:
        raise Blocked(f"菜单里没有「{text}」：{s.texts(where='all')[:12]}")
    d.tap(n.c, 0.8)


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


def key_texts(d, label, s=None):
    n = d.key_node(label, s)
    return [m.text for m in n.walk() if m.text] if n else []


def popup(d, key):
    """long-press a key and keep the finger down: (the popup's texts above the key, the key's centre).
    Finish with popup_slide or d.touch(("up", x, y))"""
    k = d.key_node(key)
    if not k:
        raise Blocked(f"键盘上没有 {key} 键")
    x, y = k.c
    d.touch(("down", x, y), ("sleep", 0.9))
    s = d.screen()

    def on_key(n):   # a key's own label (its clickable key node is a parent)
        a = n.parent
        for _ in range(2):
            if a is None:
                return False
            if a.clickable:
                return True
            a = a.parent
        return False
    top = max(150, k.b[1] - 700)   # not the status bar; landscape's keyboard starts higher up
    return [n for n in s.ime() if n.text and n.b[3] <= k.b[1] + 10 and n.b[1] > top and not on_key(n)], (x, y)


def popup_slide(d, xy, tgt):
    x, y = xy
    tx, ty = tgt.c
    d.touch(("move", (x + tx) // 2, (y + ty) // 2), ("sleep", 0.05), ("move", tx, ty), ("sleep", 0.15),
            ("up", tx, ty))
    time.sleep(0.5)


def popup_pick(d, key, char):
    """long-press a key, slide to char in its popup, let go; returns the popup's texts"""
    items, xy = popup(d, key)
    tgt = next((n for n in items if n.text == char), None)
    if not tgt:
        d.touch(("up", *xy))
        raise Blocked(f"长按 {key} 的弹出里没有「{char}」：{[n.text for n in items]}")
    popup_slide(d, xy, tgt)
    return [n.text for n in items]


def flick(d, key, dy=130, ms=120):
    """a short swipe on a key (dy > 0 down)"""
    x, y = d.keys(refresh=True)[key]
    d.swipe((x, y), (x, y + dy), ms)


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
        for cid in ids:
            self.results.pop(cid, None)
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
            res = json.dumps({k: list(v) for k, v in self.results.items()}, ensure_ascii=False)
            shots = json.dumps(self.shots, ensure_ascii=False)
            # inside the lock: both device threads write the same .tmp files
            write_atomic(os.path.join(self.dir, "results.json"), res)
            write_atomic(os.path.join(self.dir, "shots.json"), shots)

    def record(self, c, state, seen, dev, secs):
        with self.lock:
            self.results[c.id] = (state, seen, dev, secs)
            tag = {"通过": ".", "失败": "F", "阻塞": "B", "跳过": "s", "待看图": "v"}[state]
            print(f"{tag} {c.id} {state} [{dev} {secs:.0f}s]" + (f" {seen[:80]}" if state in ("失败", "阻塞") else ""),
                  flush=True)
        self.save()   # as each case ends: a runner stopped half-way keeps what it ran


def run_case(d, c, run):
    d.case = c
    t0 = time.time()
    try:
        unmet = c.unmet(d)
        if unmet:
            raise unmet[0](unmet[1])
        seen = c.fn(d) or ""
        state = "待看图" if c.visual else "通过"
    except Fail as e:
        state, seen = "失败", str(e)
    except Blocked as e:
        state, seen = "阻塞", str(e)
    except Skip as e:
        state, seen = "跳过", str(e)
    except Exception as e:  # a script error: blocked, with where
        tb = traceback.extract_tb(e.__traceback__)[-1]
        state, seen = "阻塞", f"脚本出错 {type(e).__name__}: {e} ({os.path.basename(tb.filename)}:{tb.lineno})"
    run.record(c, state, seen, d.port, time.time() - t0)
    return state


class Scheduler:
    """groups to devices: each device takes the next group; a device with no group left takes the
    remaining state-changing cases from the tail of a group in progress (each needs a reset anyway)"""

    def __init__(self, cases, run, ports, flavor="text"):
        groups = {}
        for c in cases:
            groups.setdefault(c.group, []).append(c)
        for g in groups.values():
            g.sort(key=lambda c: (c.order(), c.id))
        # biggest groups first, so the stealing at the end has small pieces
        self.pending = sorted(groups.values(), key=lambda g: -sum(3 if c.mutates else 1 for c in g))
        self.active = []
        self.lock = threading.Lock()
        self.run, self.ports, self.flavor = run, ports, flavor

    def next_group(self):
        with self.lock:
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
            d.adb("connect", d.serial)
            if not d.start_dumper():
                d.stop_dumper()   # falls back to `uiautomator dump`, ~2 s a read
            while True:
                g, stolen = self.next_group()
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
            d.stop_dumper()


# ---------------------------------------------------------------- sheets and the results file

def build_sheets(run, cases):
    from PIL import Image, ImageDraw, ImageFont
    font = Fonts(ImageFont.truetype(FONT, 15), ImageFont.truetype(FONT_LATIN, 14)
                 if os.path.exists(FONT_LATIN) else None)
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
    counts = {}
    for st, *_ in results.values():
        counts[st] = counts.get(st, 0) + 1
    return "，".join(f"{k} {v}" for k, v in sorted(counts.items()))


def table(results):
    """the results file's table, from results.json's {id: [state, seen, …]}"""
    lines = ["| 编号 | 结果 | 看到的 |", "|---|---|---|"]
    for cid in sorted(results, key=lambda i: (i.split("-")[0], int(re.sub(r"\D", "", i) or 0), i)):
        st, seen, *_ = results[cid]
        lines.append(f"| {cid} | {st} | {seen.replace('|', '/').replace(chr(10), ' ')} |")
    return lines


def write_results(run, cases, version, ports, wall, sheets, flavor="text"):
    p = os.path.join(UAT, "runs", f"{run.name}.md")
    if ports == [EMU_PORT]:
        dev = (f"- 设备：Android 模拟器（AVD dev/avd/{AVD}，android-36 google_apis x86_64），1080×2340，440 dpi，"
               f"简体中文；emulator-{EMU_PORT}；实体键盘是 uat/tools/emu-keyboard.sh 的 uinput USB 键盘，"
               f"麦克风是宿主 PulseAudio 的 {MIC}.monitor（uat/tools/voice-clips.py 合成的语音放进去）")
    else:
        dev = f"- 设备：redroid 14 x86_64，1080×2340，440 dpi，简体中文；{len(ports)} 台（{', '.join('127.0.0.1:' + p for p in ports)}）"
    lines = [f"# {run.name}", "", dev,
             f"- 包：{'语音版' if flavor == 'voice' else '文字版'} x86_64 {BUILD}（{PKG}）{version}",
             # the ids of a short rerun say which rows are new; a whole run is just its count
             f"- 跑法：`uat/tools/uat.py run`（最近一次跑 {len(run.ids)} 条"
             f"{'：' + ', '.join(run.ids) if len(run.ids) <= 12 else ''}，用时 {wall:.0f} s）；"
             f"共 {len(run.results)} 条：" + counts_text(run.results),
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
    write_atomic(rj, json.dumps(res, ensure_ascii=False))
    with open(p, encoding="utf-8") as f:
        head = f.read().split("\n| 编号 |")[0].split("\n")
    head = [re.sub(r"(共 \d+ 条：).*", lambda m: m.group(1) + counts_text(res), ln) if ln.startswith("- 跑法：") else ln
            for ln in head]
    write_atomic(p, "\n".join(head + table(res)) + "\n")
    print(f"{cid} {state}")


# ---------------------------------------------------------------- devices and the page server

def up(port="5557"):
    name = CONTAINERS[port]
    serial = f"127.0.0.1:{port}"
    if subprocess.run(["docker", "inspect", name], capture_output=True).returncode == 0:
        # stopped (docker stop, or the emulator's turn): started again, a running one left as it is
        subprocess.run(["docker", "start", name], check=True, capture_output=True)
        print(f"{name} already exists")
    else:
        subprocess.run(["docker", "run", "-d", "--name", name, "--privileged", "--memory", "2048m",
                        "-p", f"127.0.0.1:{port}:5555", IMAGE,
                        "androidboot.redroid_width=1080", "androidboot.redroid_height=2340",
                        "androidboot.redroid_dpi=440", "androidboot.redroid_gpu_mode=host"],
                       check=True, capture_output=True)
    d = Device(port)

    def booted():
        subprocess.run(["adb", "connect", serial], capture_output=True)
        return d.sh("getprop sys.boot_completed").strip() == "1" and "package:" in d.sh("pm path android")
    for _ in range(90):
        if booted():
            break
        time.sleep(1)
    if d.sh("getprop persist.sys.locale").strip() != "zh-CN":
        # the framework restarts for the language: boot_completed drops, adb may go offline
        d.root("setprop persist.sys.locale zh-CN; setprop sys.boot_completed 0; stop; start")
        time.sleep(3)
        for _ in range(120):
            if booted():
                break
            time.sleep(1)
    time.sleep(3)
    install(d)
    print(f"{name} up on {serial}")


def install(d, flavor="text"):
    apks = sorted((os.path.join(dp, f) for dp in [os.path.join(REPO, f"app/build/outputs/apk/{flavor}/{BUILD}")]
                   if os.path.isdir(dp) for f in os.listdir(dp) if "x86_64" in f and f.endswith(".apk")),
                  key=os.path.getmtime)
    if not apks:
        msg = f"no {flavor} {BUILD} x86_64 APK: BUILD_ABI=x86_64 ./gradlew :app:assemble{flavor.capitalize()}{BUILD.capitalize()}"
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


def down(port="5557"):
    if port not in ("5557",):
        sys.exit("down only removes the second device")
    subprocess.run(["adb", "disconnect", f"127.0.0.1:{port}"], capture_output=True)
    subprocess.run(["docker", "rm", "-f", CONTAINERS[port]], capture_output=True)
    print(f"{CONTAINERS[port]} removed")


def emu_up():
    """the emulator with a PulseAudio microphone; its own Android settings kept in the AVD"""
    running = subprocess.run(["docker", "ps", "--format", "{{.Names}}"], capture_output=True, text=True).stdout.split()
    ours = [c for c in CONTAINERS.values() if c in running]
    if ours:   # qemu ~3-6 GB beside a redroid on 15 GB: earlyoom (specs/testing.md)
        sys.exit(f"stop the redroids first (docker stop {' '.join(ours)})")
    d = Device(EMU_PORT)
    os.makedirs(os.path.join(OUT, "emu"), exist_ok=True)
    if subprocess.run(["systemctl", "--user", "is-active", "-q", "pipewire-pulse"]).returncode:
        subprocess.run(["systemctl", "--user", "start", "pipewire", "pipewire-pulse", "wireplumber"], check=True)
        open(os.path.join(OUT, "emu", "pipewire-started"), "w").close()
        time.sleep(2)
    # the emulator's own stream format (44.1 kHz stereo); the clips are resampled as they play
    have = subprocess.run(["pw-cli", "ls", "Node"], capture_output=True, text=True).stdout
    for sink in (MIC, SPEAKER):
        if f'"{sink}"' not in have:
            subprocess.run(["pw-cli", "create-node", "adapter",
                            f"{{ factory.name=support.null-audio-sink node.name={sink} node.description={sink} "
                            "media.class=Audio/Sink audio.position=[FL FR] audio.rate=44100 object.linger=true }"],
                           check=True, capture_output=True)
    if d.sh("getprop sys.boot_completed").strip() != "1":
        sdk = os.environ.get("ANDROID_HOME", os.path.expanduser("~/android_sdk"))
        log = open(os.path.join(OUT, "emu", "emulator.log"), "w")
        # -allow-host-audio: without it the microphone reads zeroes; PULSE_SOURCE picks the source.
        # A window on Xvfb, not -no-window: -no-window runs qemu-system-x86_64-headless, built
        # without PulseAudio ("Could not init `pa' audio driver", the guest's microphone silent)
        subprocess.Popen(["xvfb-run", "-a", "-s", "-screen 0 1280x1024x24",
                          os.path.join(sdk, "emulator", "emulator"), "-avd", AVD, "-no-boot-anim",
                          "-no-snapshot", "-gpu", "swiftshader_indirect", "-port", EMU_PORT, "-allow-host-audio"],
                         env={**os.environ, "ANDROID_AVD_HOME": AVD_HOME, "PULSE_SOURCE": f"{MIC}.monitor",
                              "PULSE_SINK": SPEAKER},
                         stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
        for _ in range(240):
            time.sleep(2)
            if d.sh("getprop sys.boot_completed").strip() == "1":
                break
        else:
            sys.exit("the emulator did not boot in 8 min (dev/uat-out/emu/emulator.log)")
    subprocess.run(["adb", "-s", d.serial, "root"], capture_output=True)
    subprocess.run(["adb", "-s", d.serial, "wait-for-device"], capture_output=True)
    time.sleep(2)
    if d.sh("getprop persist.sys.locale").strip() != "zh-CN":
        d.sh("setprop persist.sys.locale zh-CN; setprop sys.boot_completed 0; stop; start")
        time.sleep(5)
        for _ in range(120):
            if d.sh("getprop sys.boot_completed").strip() == "1":
                break
            time.sleep(1)
        time.sleep(3)
    if "package:" not in d.sh("pm path org.chromium.webview_shell"):
        # the test page's browser, as on redroid: AOSP's WebView shell, taken from the redroid image
        apk = os.path.join(OUT, "emu", "webview_shell.apk")
        if not os.path.exists(apk):
            subprocess.run(["docker", "create", "--name", "uat-apk-src", IMAGE], capture_output=True, check=True)
            try:
                subprocess.run(["docker", "cp", "uat-apk-src:/system/product/app/Browser2/Browser2.apk", apk],
                               capture_output=True, check=True)
            finally:
                subprocess.run(["docker", "rm", "uat-apk-src"], capture_output=True)
        d.adb("install", "-r", apk, check=True)
    print(f"emulator up on {d.serial}")


def emu_down():
    d = Device(EMU_PORT)
    if d.sh("getprop sys.boot_completed").strip() == "1":
        subprocess.run([os.path.join(UAT, "tools", "emu-keyboard.sh"), "down"], capture_output=True,
                       env={**os.environ, "ANDROID_SERIAL": d.serial})
        subprocess.run(["adb", "-s", d.serial, "emu", "kill"], capture_output=True)
        for _ in range(30):
            if d.serial not in subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout:
                break
            time.sleep(1)
    for sink in (MIC, SPEAKER):
        subprocess.run(["pw-cli", "destroy", sink], capture_output=True)
    mark_f = os.path.join(OUT, "emu", "pipewire-started")
    if os.path.exists(mark_f):   # started by emu-up: as it was
        subprocess.run(["systemctl", "--user", "stop", "pipewire-pulse.socket", "pipewire.socket",
                        "wireplumber", "pipewire-pulse", "pipewire"], capture_output=True)
        os.remove(mark_f)
    print("emulator down")


def version(d):
    m = re.search(r"versionName=(\S+)", d.sh(f"dumpsys package {PKG} | grep -m1 versionName"))
    return m.group(1) if m else "?"


def start_server():
    """the test page on 127.0.0.1 only; returns the process (stopped by its pid)"""
    s = __import__("socket").socket()
    busy = s.connect_ex(("127.0.0.1", HTTP_PORT)) == 0
    s.close()
    if busy:
        return None
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
    ports = a.devices.split(",")
    for p in ports:
        if p not in CONTAINERS and p != EMU_PORT:
            sys.exit(f"unknown device {p}")
        d = Device(p)
        if not d.emu:
            subprocess.run(["adb", "connect", d.serial], capture_output=True)
        if d.sh("getprop sys.boot_completed").strip() != "1":
            sys.exit(f"{d.serial} is not up" + {"5557": " (uat.py up)", EMU_PORT: " (uat.py emu-up)"}.get(p, ""))
        install(d, a.flavor)
        d.adb("reverse", "tcp:8000", f"tcp:{HTTP_PORT}", check=True)
    if len({p == EMU_PORT for p in ports}) > 1:
        sys.exit("the emulator runs alone, not beside a redroid")
    ver = version(Device(ports[0]))
    kind = "emulator" if ports == [EMU_PORT] else "redroid"
    name = f"{datetime.date.today().isoformat()}-{kind}-{ver}"
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
    print(f"{len(run.ids)} cases in {wall:.0f} s: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))
    print(f"results {os.path.relpath(p, REPO)}; {len(sheets)} sheets, {nshots} shots in {os.path.relpath(run.dir, REPO)}/")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--areas")
    r.add_argument("--ids")
    r.add_argument("--devices", default="5556,5557")
    r.add_argument("--flavor", default="text", choices=["text", "voice"])
    l = sub.add_parser("list")
    l.add_argument("--areas")
    sub.add_parser("up")
    sub.add_parser("down")
    sub.add_parser("emu-up")
    sub.add_parser("emu-down")
    m = sub.add_parser("mark")
    m.add_argument("run")
    m.add_argument("id")
    m.add_argument("state", choices=["通过", "失败", "阻塞", "跳过"])
    m.add_argument("note", nargs="?", default="")
    a = ap.parse_args()
    if a.cmd == "run":
        cmd_run(a)
    elif a.cmd == "list":
        load_scripts(a.areas.split(",") if a.areas else None)
        for c in sorted(CASES.values(), key=lambda c: (c.group, c.order(), c.id)):
            print(c.group, c.id, {0: "read", 1: "learn-clean", 2: "learn", 3: "mutate"}[c.order()],
                  "visual" if c.visual else "", c.needs or "")
    elif a.cmd == "up":
        up()
    elif a.cmd == "down":
        down()
    elif a.cmd == "emu-up":
        emu_up()
    elif a.cmd == "emu-down":
        emu_down()
    elif a.cmd == "mark":
        mark(a.run, a.id, a.state, a.note)


sys.modules.setdefault("uat", sys.modules[__name__])

if __name__ == "__main__":
    main()
