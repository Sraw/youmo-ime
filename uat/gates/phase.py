#!/usr/bin/env python3
"""One device step of a release gate, for one package: gates.py (upgrade, backups, perf) runs
each step as its own process, with UAT_BUILD naming the package (release: the
released id, debug: .debug), since uat.py fixes the package at import.

  UAT_BUILD=... phase.py <port> <step> [args...]

Steps:
  install <apk> [-r]           adb install (-r: over what is there, an in-place upgrade)
  restore-files <version>      the fixture's data.tar, external.tar and (if it has one) de.tar into
                               this package's dirs, as root, owned by the app (its preferences file
                               renamed to this package's)
  check <out.json>             the fixture's settings, map, phrase, learned word and 繁体: in the files
                               and as typed on the test page; out.json gets every item's result
  rewrite                      changes made in the keyboard and settings and changed back, so this
                               build writes chttrans.conf and androidengine.conf itself
  backup <out.zip>             备份全部, the zip pulled
  import <zip>                 the zip pushed to Download, 从备份恢复
  perf <out.json> [runs]       cold start to first candidate, per-key latency, memory (PSS etc.)
"""
import json
import os
import re
import statistics
import subprocess
import sys
import tarfile
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
UAT_DIR = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(UAT_DIR, "tools"))
sys.path.insert(0, os.path.join(UAT_DIR, "scripts"))
import uat  # noqa: E402
from uat import PKG, Device, Blocked, ensure_ime, home, ime_ready, script  # noqa: E402

FIXTURES = os.path.join(UAT_DIR, "fixtures", "upgrade")
EXTERNAL = f"/data/media/0/Android/data/{PKG}/files"
DATA = f"/data/data/{PKG}"
DE = f"/data/user_de/0/{PKG}"   # device-protected: the old builds' synced native data and direct-boot prefs
PREFS = f"{DATA}/shared_prefs/{PKG}_preferences.xml"

# what every fixture set (uat/fixtures/upgrade/*/README.md); each value as the app may show it
# simplified or, 繁体 being on, traditional
PHRASE = ("北京市海淀区", "北京市海淀區")   # OpenCC s2tw keeps the place name 海淀
LEARNED = ("鲸喵屿", "鯨喵嶼")


def device(port):
    d = Device(port)
    subprocess.run(["adb", "connect", d.serial], capture_output=True)
    d.start_dumper()
    return d


def with_page(d, fn):
    uat.HTTP_PORT = uat.http_port(d.port)
    server = uat.start_server()
    d.adb("reverse", "tcp:8000", f"tcp:{uat.HTTP_PORT}", check=True)
    try:
        return fn()
    finally:
        server.terminate()


def uid(d):
    out = d.root(f"stat -c %u {DATA}").strip()
    if not out.isdigit():
        raise Blocked(f"{PKG} not installed")
    return out


def restore_files(d, version):
    """as `adb install` then the fixture's files put back, the way the device had them; the app not
    started in between, so this build's first start finds the old build's files"""
    d.sh(f"am force-stop {PKG}")
    u = uid(d)
    with tempfile.TemporaryDirectory() as tmp:
        for name in names(version):
            src = os.path.join(FIXTURES, version, f"{name}.tar")
            fixed = os.path.join(tmp, f"{name}.tar")
            with tarfile.open(src) as t, tarfile.open(fixed, "w") as out:
                for m in t.getmembers():
                    f = t.extractfile(m) if m.isfile() else None
                    # the default preferences are named after the package (a .debug build reads its own)
                    m.name = re.sub(r"shared_prefs/io\.github\.sraw\.youmo(\.debug)?_preferences\.xml$",
                                    f"shared_prefs/{PKG}_preferences.xml", m.name)
                    out.addfile(m, f)
            d.adb("push", fixed, f"/data/local/tmp/{name}.tar", check=True, timeout=300)
    # the external dir as Android makes it for an app (uid, group ext_data_rw), else the app cannot write it
    de = (f"mkdir -p {DE} && cd {DE} && tar -xf /data/local/tmp/de.tar && chown -R {u}:{u} {DE} && "
          f"restorecon -R {DE} && ") if "de" in names(version) else ""
    # the chain's own marker: d.root returns stdout only, and a failed step must block here, not
    # fail gate 2's check as if the new build were at fault
    out = d.root(f"mkdir -p {EXTERNAL} && cd {EXTERNAL} && tar -xf /data/local/tmp/external.tar && "
                 f"chown -R {u}:1078 /data/media/0/Android/data/{PKG} && chmod -R ug+rwX {EXTERNAL} && "
                 f"cd {DATA} && tar -xf /data/local/tmp/data.tar && chown -R {u}:{u} {DATA} && "
                 f"restorecon -R {DATA} /data/media/0/Android/data/{PKG} && {de}echo restore-ok; "
                 "rm -f /data/local/tmp/data.tar /data/local/tmp/external.tar /data/local/tmp/de.tar")
    if "restore-ok" not in out:
        raise Blocked(f"restore-files {version} failed: {out.strip()[-200:]}")
    return f"uid {u}" + (", user_de too" if de else "")


def names(version):
    """the fixture's tars: de.tar only in fixtures made since make.py saved user_de"""
    return [n for n in ("data", "external", "de")
            if n != "de" or os.path.exists(os.path.join(FIXTURES, version, "de.tar"))]


def cat(d, path):
    return d.root(f"cat {path} 2>/dev/null")


def ini_values(text):
    """fcitx's reading (iniparser.cpp:63-69): # lines skipped, [section], key=value untrimmed"""
    out, section = {}, ""
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1]
            continue
        k, sep, v = line.partition("=")
        if sep:
            out[(section + "/" if section else "") + k] = v
    return out


def file_checks(d, version=None):
    """version: gate 2's fixture, restored before the new build's first start; None for gate 3's
    restores, where user_de is whatever the installed build left (a 0.3.x one syncs its data there)"""
    res = []

    def item(name, ok, got):
        res.append({"item": name, "ok": bool(ok), "got": got})

    prefs = cat(d, PREFS)
    m = re.search(r'name="auto_pairs" value="(\w+)"', prefs)
    item("括号成对 关 (prefs auto_pairs)", m and m.group(1) == "false", m and m.group(1))
    m = re.search(r'name="keyboard_height_percent" value="(\d+)"', prefs)
    item("键盘高度 高 (prefs keyboard_height_percent 34)", m and m.group(1) == "34", m and m.group(1))
    engine = ini_values(cat(d, f"{EXTERNAL}/config/conf/androidengine.conf"))
    item("性能档位 中档 (androidengine.conf)", engine.get("ModelLevel") == "Medium", engine.get("ModelLevel"))
    profile = ini_values(cat(d, f"{EXTERNAL}/config/profile"))
    ims = [profile[k] for k in sorted(profile) if re.fullmatch(r"Groups/0/Items/\d+/Name", k)]
    item("输入法 拼音、英文、双拼 (profile)", ims == ["engine-pinyin", "keyboard-us", "engine-shuangpin"], ims)
    trad = ini_values(cat(d, f"{EXTERNAL}/config/conf/chttrans.conf"))
    item("繁体 (chttrans.conf EnabledIM)", "engine-pinyin" in [v for k, v in trad.items() if k.startswith("EnabledIM/")],
         trad)
    punc = cat(d, f"{EXTERNAL}/data/punctuation/punc.mb.zh_CN")
    item("中文标点 , → 、 (punc.mb.zh_CN)", ", 、" in punc.splitlines(), [l for l in punc.splitlines() if l.startswith(",")])
    phrase = cat(d, f"{EXTERNAL}/data/pinyin/customphrase")
    item("自定义短语 dz (customphrase)", "dz,1=北京市海淀区" in phrase.splitlines(), phrase.strip())
    if version and "de" in names(version):
        # only a fixture with a de.tar put the old data there: else the item would pass unexercised;
        # the old native host's synced data (FcitxApplication.deleteNativeData) gone after the first start
        left = d.root(f"ls {DE}/descriptor.json {DE}/usr 2>/dev/null").split()
        item("旧原生数据已删 (user_de descriptor.json, usr)", not left, left)
    elif version:
        # ok None: listed as not checked in the report, so a fixture without de.tar can't read as all-pass
        res.append({"item": "旧原生数据已删 (user_de descriptor.json, usr)", "ok": None, "got": f"未检查：{version} 无 de.tar"})
    return res


def typed_checks(d):
    res = []

    def item(name, ok, got):
        res.append({"item": name, "ok": bool(ok), "got": got})

    # the input method the restored files left current, read before to_pinyin switches
    label = to_pinyin(d)
    item("当前输入法 拼音", "拼音" in label, label)
    d.type("guojia")
    c = d.cands()
    d.space()
    time.sleep(0.8)
    t = d.field_text()
    item("繁体: guojia 空格 → 國家", t == "國家", {"cands": c[:3], "field": t})
    d.open_page("text")
    d.type("dz")
    c = d.cands()
    item("自定义短语: dz 第一个是北京市海淀区", c[:1] and c[0] in PHRASE, c[:3])
    d.open_page("text")
    d.type("jingmiaoyu")
    c = d.cands()
    item("学到的词: jingmiaoyu 第一个是鲸喵屿", c[:1] and c[0] in LEARNED, c[:3])
    d.open_page("text")
    d.type(",")
    time.sleep(0.8)
    t = d.field_text()
    item("中文标点: , → 、", t == "、", t)
    # the row's dialog, its checked option: the page shows no summary for it
    try:
        home(d, "通用输入")
        d.tap_text("性能档位", after=0.8)
        checked = [n.text for n in d.screen().app() if n.checked and n.text]
        d.back()
    except Blocked as e:
        checked = [f"blocked: {e}"[:200]]
    item("设置页: 性能档位 中档", checked[:1] and checked[0].startswith("中档"), checked)
    return res


def to_pinyin(d):
    """the test page, the keyboard on 拼音: once the host is up (the space key says （不可用） till
    then, and a globe key pressed meanwhile lands on another input method); the label it found"""
    d.open_page("text")
    end = time.time() + 30
    while "不可用" in d.im_label() and time.time() < end:
        time.sleep(0.5)
    label = d.im_label()
    if "拼音" not in label:
        d.switch_im("拼音")
    return label


def first_start(d):
    ensure_ime(d)
    d.open_settings()


def check(d, out, version=None):
    def run():
        first_start(d)
        typed = typed_checks(d)
        # the settings page closed before the files are read (the host writes each change at once)
        d.sh("input keyevent KEYCODE_HOME")
        time.sleep(2)
        return typed + file_checks(d, version)
    res = with_page(d, run)
    with open(out, "w") as f:
        json.dump(res, f, ensure_ascii=False, indent=1)
    bad = [r["item"] for r in res if r["ok"] is False]
    unchecked = [r["item"] for r in res if r["ok"] is None]
    return (f"{len(res) - len(bad) - len(unchecked)}/{len(res)} ok" + (f"; failed: {bad}" if bad else "")
            + (f"; not checked: {unchecked}" if unchecked else ""))


def rewrite(d):
    s05 = script("05-toolbar")

    def run():
        first_start(d)
        to_pinyin(d)
        # the toggle shows the current state: 繁体 on in the fixture, off, then on again
        a = s05.status_toggle(d, "繁体")
        b = s05.status_toggle(d, "简体")
        for tier in ("高档", "中档"):
            home(d, "通用输入")
            uat.choose(d, "性能档位", tier)
        d.sh("input keyevent KEYCODE_HOME")
        time.sleep(2)
        return f"繁体 {a} → {b}; 档位 高档 → 中档"
    return with_page(d, run)


def backup(d, out):
    s15 = script("15-privacy-backup")
    first_start(d)
    name, new, msgs = s15.backup(d)
    if not new:   # seen once: the picker's 保存 tapped before it took the name; once more
        time.sleep(2)
        name, new, msgs = s15.backup(d)
    if not new:
        raise Blocked(f"no backup file ({msgs})")
    f, last = f"{s15.DOWNLOAD}/{new[0]}", None
    for _ in range(60):   # written after the picker closes: pulled once its size holds
        size = d.sh(f"stat -c %s '{f}'").strip()
        if size == last and size != "0":
            break
        last = size
        time.sleep(1)
    d.adb("pull", f, out, check=True)
    d.sh(f"rm '{f}'")
    return new[0]


def import_(d, zip_path):
    s15 = script("15-privacy-backup")
    name = f"gate-{int(time.time())}.zip"
    d.adb("push", zip_path, f"{s15.DOWNLOAD}/{name}", check=True)
    # the media store learns of a pushed file only when scanned: else the picker does not list it
    d.sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://{s15.DOWNLOAD}/{name}")
    first_start(d)
    warn, restarted = s15.restore(d, name)
    # restore() only reports a missing restart; a gate that restored without one must fail (F4089)
    # uat.check, not check: this file's own check (the gate step) shadows the import (F4160)
    uat.check(restarted, "恢复后应用没有重启")
    return f"restored {name}, restarted {restarted}"


# --- gate 5

def meminfo(d):
    pid = d.sh(f"pidof {PKG}").strip()
    if pid:
        d.root(f"kill -10 {pid}")   # SIGUSR1: ART collects first (13-words.py java_heap)
        time.sleep(1.5)
    out = d.sh(f"dumpsys meminfo {PKG}")
    got = {}
    for key, pat in (("pss_total", r"TOTAL PSS:\s+(\d+)"), ("native_heap", r"Native Heap:\s+(\d+)"),
                     ("java_heap", r"Java Heap:\s+(\d+)")):
        m = re.search(pat, out)
        got[key] = int(m.group(1)) if m else None
    return got


def bar(d):
    """what a key changes: the composing row and the candidates"""
    s = d.screen()
    return d.preedit(s), tuple(d.candidates(s))


def perf(d, out, runs=5):
    words = ["nihao", "women", "zhongguo", "jintian", "tianqi", "henhao", "shijian", "pengyou"]

    def run():
        ensure_ime(d)
        to_pinyin(d)
        ime_ready(d)
        f = d.field()
        fx, fy = f.c
        # cold: the app's process stopped, the focused field tapped; then the keyboard drawn, the host
        # up (the space key names the input method, not （不可用）), and the bar's first candidate for ni
        cold = []
        for _ in range(runs):
            # timed from the stop: the system binds the keyboard again at once, and a release build
            # is up before a pause after the stop would end
            d.sh("logcat -c")
            t0 = time.time()
            d.sh(f"am force-stop {PKG}")
            ensure_ime(d)   # a stopped keyboard may have been swapped for the system's
            d.sh(f"input tap {fx} {fy}")
            t_kb = t_ready = t_cand = None
            while time.time() - t0 < 60:
                label = d.im_label()
                if not label and time.time() - t0 > 5:   # the tap went before the window was back
                    d.sh(f"input tap {fx} {fy}")
                if label and t_kb is None:
                    t_kb = time.time() - t0
                if label and "不可用" not in label:
                    t_ready = time.time() - t0
                    break
            keys = d.keys(refresh=True)
            for ch in "ni":
                d.sh(f"input tap {keys[ch][0]} {keys[ch][1]}")
            while time.time() - t0 < 60:
                if d.candidates():
                    t_cand = time.time() - t0
                    break
            run_ = {k: round(v * 1000) if v else None for k, v in
                    (("keyboard", t_kb), ("ready", t_ready), ("candidate", t_cand))}
            m = re.search(r"Kotlin host ready in (\d+) ms", d.sh("logcat -d"))   # KotlinFcitx.startup's own
            run_["host_log"] = int(m.group(1)) if m else None
            cold.append(run_)
            d.bksp(3)
        # warm: each key timed from the tap to the composing row or the bar changing
        keys = d.keys(refresh=True)
        lat = []
        for w in words * 2:
            before = bar(d)
            for ch in w:
                x, y = keys[ch]
                t0 = time.time()
                d.sh(f"input tap {x} {y}")
                while time.time() - t0 < 5:
                    now = bar(d)
                    if now != before:
                        break
                lat.append(round((time.time() - t0) * 1000))
                before = now
            d.space()
            time.sleep(0.3)
        time.sleep(5)
        mem = [meminfo(d) for _ in range(6)]
        return {"cold_start_ms": cold, "key_ms": lat, "mem_kb": mem}
    res = with_page(d, run)
    with open(out, "w") as f:
        json.dump(res, f, indent=1)
    cand = [c["candidate"] for c in res["cold_start_ms"] if c["candidate"]]
    pss = [m["pss_total"] for m in res["mem_kb"][1:] if m["pss_total"]]
    return (f"cold median {statistics.median(cand) if cand else None} ms, key median "
            f"{statistics.median(res['key_ms'])} ms, PSS median {statistics.median(pss) if pss else None} KB")


def main():
    port, step, *args = sys.argv[1:]
    if step == "install":
        d = Device(port)
        subprocess.run(["adb", "connect", d.serial], capture_output=True)
        r = d.adb("install", *(["-r"] if "-r" in args[1:] else []), args[0], timeout=900)
        if "Success" not in r:
            raise Blocked(f"install: {r.strip()[-200:]}")
        print(f"installed {os.path.basename(args[0])} as {PKG}")
        return
    d = device(port)
    try:
        r = {"restore-files": lambda: restore_files(d, args[0]), "check": lambda: check(d, *args[:2]),
             "rewrite": lambda: rewrite(d), "backup": lambda: backup(d, args[0]),
             "import": lambda: import_(d, args[0]),
             "perf": lambda: perf(d, args[0], int(args[1]) if len(args) > 1 else 5)}[step]()
        print(f"{step}: {r}")
    finally:
        d.stop_dumper()


if __name__ == "__main__":
    try:
        main()
    except Blocked as e:
        print(f"BLOCKED: {e}", file=sys.stderr)
        sys.exit(3)
