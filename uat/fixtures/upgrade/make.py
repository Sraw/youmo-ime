#!/usr/bin/env python3
"""Makes uat/fixtures/upgrade/<version>/ from a released arm64 text APK on a throwaway redroid:
installs it on a fresh device, sets what README.md lists through the app's UI (the uat.py
helpers), then saves the app's files (internal, external and device-protected, as root) and a 备份全部 zip.
Each step is timed into timings.tsv.

  UAT_BUILD=release python3 uat/fixtures/upgrade/make.py <version> <apk> <container> <port>

The container is removed and made again first: every fixture starts from a clean device.
"""
import os
import subprocess
import sys
import tarfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "tools"))
sys.path.insert(0, os.path.join(HERE, "..", "..", "scripts"))
import uat  # noqa: E402
from uat import PKG, IME, Device, add_im, choose, home, script, ensure_ime, ime_ready  # noqa: E402

version, apk, container, port = sys.argv[1:5]
out = os.path.join(HERE, version)
os.makedirs(out, exist_ok=True)
times = []


def step(name, fn):
    t0 = time.time()
    r = fn()
    times.append((name, time.time() - t0))
    print(f"{name}: {times[-1][1]:.1f} s {r or ''}", flush=True)
    return r


def up():
    serial = f"127.0.0.1:{port}"
    subprocess.run(["adb", "disconnect", serial], capture_output=True)
    subprocess.run(["docker", "rm", "-f", container], capture_output=True)
    subprocess.run(["docker", "run", "-d", "--name", container, "--privileged", "--memory", "2048m",
                    "-p", f"{serial}:5555", uat.IMAGE, "androidboot.redroid_width=1080",
                    "androidboot.redroid_height=2340", "androidboot.redroid_dpi=440",
                    "androidboot.redroid_gpu_mode=host"], check=True, capture_output=True)

    def booted():
        subprocess.run(["adb", "connect", serial], capture_output=True)
        r = subprocess.run(["adb", "-s", serial, "shell", "getprop sys.boot_completed; pm path android"],
                           capture_output=True, text=True)
        return r.stdout.startswith("1") and "package:" in r.stdout
    for _ in range(120):
        if booted():
            break
        time.sleep(1)
    subprocess.run(["docker", "exec", container, "sh", "-c",
                    "setprop persist.sys.locale zh-CN; setprop sys.boot_completed 0; stop; start"])
    time.sleep(3)
    for _ in range(120):
        if booted():
            break
        time.sleep(1)
    time.sleep(3)


step("redroid up (fresh, zh-CN)", up)
d = Device(port)
d.start_dumper()   # as uat.py run does; without it every read is the slow uiautomator dump
step("install APK", lambda: d.adb("install", apk, check=True, timeout=600))
d.sh(f"ime enable {IME}; ime set {IME}")
ensure_ime(d)
uat.HTTP_PORT = uat.http_port(port)
server = uat.start_server()
d.adb("reverse", "tcp:8000", f"tcp:{uat.HTTP_PORT}", check=True)
try:
    def first_run():
        d.open_settings()
        d.open_page("text")
        ime_ready(d)
    step("first run: settings, keyboard, engine ready", first_run)

    s14, s05, s13, s15 = (script(n) for n in ("14-settings", "05-toolbar", "13-words", "15-privacy-backup"))
    step("输入法: 添加双拼", lambda: add_im(d, "双拼"))

    def tier():
        home(d, "通用输入")
        choose(d, "性能档位", "中档")
    step("通用输入: 性能档位 中档", tier)

    def brackets():
        home(d, "通用输入")
        d.switch("括号和引号成对输入，光标停在中间", False)
    step("通用输入: 括号成对 关", brackets)

    def height():
        s14.appearance_tab(d, "大小与样式")
        choose(d, "键盘高度", "高")
    step("外观: 键盘高度 高", height)

    def punct():
        # 14-settings.py ST-28: , -> 、
        home(d, "通用输入", "中文标点")
        s = d.screen()
        n = next(n for n in s.app() if n.text == ",")
        d.tap(n.row().c, 1.2)
        eds = [n for n in d.screen().app() if "Edit" in n.cls]
        d.tap(eds[1].c, 0.8)
        d.clear()
        m = d.keys()["m"]
        d.swipe(m, (m[0], m[1] + 130), 150)
        t = d.field_text()
        assert t == "、", t
        d.hide_keyboard()
        d.tap_text("确定", scroll=False, after=1.0)
    step("中文标点: , 改成 、", punct)

    step("自定义短语: dz 北京市海淀区", lambda: s13.dz(d))

    def learn():
        d.open_page("text")
        for _ in range(2):
            d.type("jingmiaoyu")
            for w in "鲸喵屿":
                d.cands(want=w)
                d.pick(w)
            time.sleep(0.5)
        d.clear()
        d.type("jingmiaoyu")
        c = d.cands()
        d.clear()
        return f"jingmiaoyu 候选 {c[:3]}"
    step("学到的词: 鲸喵屿 两次", learn)

    def trad():
        d.open_page("text")
        now = s05.status_toggle(d, "简体")
        d.type("guojia")
        d.cands()
        d.space()
        time.sleep(1)
        t = d.field_text()
        d.clear()
        return f"{now}: guojia -> {t}"
    step("状态面板: 繁体", trad)

    def backup():
        name, new, msgs = s15.backup(d)
        assert new, f"no backup file ({msgs})"
        f, last = f"{s15.DOWNLOAD}/{new[0]}", None
        for _ in range(60):   # written after the picker closes: pulled once its size holds
            size = d.sh(f"stat -c %s {f}").strip()
            if size == last and size != "0":
                break
            last = size
            time.sleep(1)
        d.adb("pull", f, os.path.join(out, "backup.zip"), check=True)
        return new[0]
    step("备份全部 + 取回 zip", backup)
    def save_files():
        # the settings page's onStop saves fcitx's state (MainActivity.kt:129), which writes
        # chttrans.conf and the profile; then the process is stopped so nothing is half written
        home(d)
        d.sh("input keyevent KEYCODE_HOME")
        time.sleep(2)
        d.sh(f"am force-stop {PKG}")
        time.sleep(1)
        # de: device-protected storage, where the native builds synced their data (descriptor.json,
        # usr/, learn/) and the direct-boot prefs; gate 2 checks the new build deletes that data
        for name, path in (("data", f"/data/data/{PKG}"), ("external", f"/sdcard/Android/data/{PKG}/files"),
                           ("de", f"/data/user_de/0/{PKG}")):
            dev = f"/data/local/tmp/{name}.tar"
            d.root(f"cd {path} && tar -cf {dev} --exclude=./cache --exclude=./code_cache . && chmod 644 {dev}")
            local = os.path.join(out, f"{name}.tar")
            d.adb("pull", dev, local, check=True, timeout=600)
        return " ".join(f"{n} {os.path.getsize(os.path.join(out, n + '.tar')) >> 10} KiB" for n in ("data", "external", "de"))
    step("导出应用文件 (root tar)", save_files)

finally:
    server.terminate()

with open(os.path.join(out, "timings.tsv"), "w") as f:
    f.write("step\tseconds\n")
    f.writelines(f"{n}\t{t:.1f}\n" for n, t in times)
print("total", round(sum(t for _, t in times)), "s")
