"""uat/19-motion-notices.md. The .md is the source of truth; each function follows its case's
前提/操作/预期."""
import io
import os
import re
import statistics
import sys
import tempfile
import time
import zipfile

from uat import PKG, Blocked, case, check


case("MN-13", base="B0-设置", needs="manual:要 TalkBack 读屏，在模拟器（google_apis 镜像带 TalkBack）或真机上手动做")(lambda d: None)


# ---- 宠物（MN-28、MN-31；MN-29、30、32 只在 JVM 上） ----
THEME_XML = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "web", "theme-maker", "test", "testdata",
                         "from-maker.theme")
# each state a colour of its own: the pet's corner of a screenshot says which one is drawn
PET_COLORS = {"idle": (150, 0, 150), "typing": (230, 0, 0), "delete": (0, 0, 230), "enter": (0, 190, 0),
              "space": (230, 200, 0), "sleep": (40, 40, 40), "wake": (0, 200, 200)}
# slow enough for a screenshot to catch a state before it is over
PET_STATES = {"idle": (4, 4), "typing": (6, 2), "delete": (6, 2), "enter": (6, 2), "space": (6, 2), "sleep": (2, 4), "wake": (2, 2)}


def pet_sheet(state, frames, px=96):
    """[frames] frames of [px] in a row, all the state's colour, each with the state's name and its number on it"""
    from PIL import Image, ImageDraw
    im = Image.new("RGB", (px * frames, px), PET_COLORS[state])
    dr = ImageDraw.Draw(im)
    for i in range(frames):
        dr.text((i * px + 8, 8), f"{state} {i}", fill=(255, 255, 255))
    out = io.BytesIO()
    im.save(out, "PNG")
    return out.getvalue()


def pet_theme(d, name, pet='<pet>', states=PET_STATES):
    """a .theme of the maker's test theme renamed [name], with [pet] and [states] ({state: (frames, fps)}; pet None: no
    pet), pushed into 下载"""
    with zipfile.ZipFile(THEME_XML) as z:
        xml = z.read("theme.xml").decode()
    xml = re.sub(r'(<theme [^>]*name=")[^"]*"', rf'\g<1>{name}"', xml, count=1)
    files = {}
    if pet is not None:
        rows = "".join(f'\n    <state name="{s}" sheet="pet-{s}.png" frames="{f}" fps="{fps}"/>' for s, (f, fps) in states.items())
        xml = xml.replace("</theme>", f"  {pet}{rows}\n  </pet>\n</theme>")
        files = {f"pet-{s}.png": pet_sheet(s, f) for s, (f, _) in states.items()}
    p = os.path.join(tempfile.gettempdir(), f"{name}-{d.port}.theme")
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("theme.xml", xml)
        for n, b in files.items():
            z.writestr(n, b)
    sys.modules["uat_14_settings"].push_download(d, p, name)


def use_theme(d, name):
    """[name] (pushed) imported and picked, the keyboard up on the test page"""
    s14 = sys.modules["uat_14_settings"]
    s14.import_theme(d, name)
    s14.appearance_tab(d, "主题")
    item = s14.custom_item(d)
    if not item:
        raise Blocked(f"导入 {name} 后主题列表里没有它")
    d.tap(item.c, 1.5)
    d.open_page("text")


def delete_themes(d):
    s14 = sys.modules["uat_14_settings"]
    s14.appearance_tab(d, "主题")
    while s14.custom_item(d):
        s14.delete_custom(d)
        s14.appearance_tab(d, "主题")


def pet_state(d, im=None):
    """which state's colour fills most of the keys' top-right corner (where a keyboard pet sits by default), or None"""
    x0, y0, x1, y1 = sys.modules["uat_14_settings"].kb_box(d)
    side = int(64 * d.dpx())
    px = list((im or d.png()).crop((x1 - side, y0, x1, y0 + side + int(60 * d.dpx()))).resize((40, 60)).getdata())
    near = {s: sum(1 for p in px if sum(abs(a - b) for a, b in zip(p, c)) < 60) for s, c in PET_COLORS.items()}
    best = max(near, key=near.get)
    return best if near[best] >= 60 else None


@case("MN-28", base="B0-设置", mutates=True, visual=True)
def mn28(d):
    """smoke on a device: the JVM's PetUatTest has the rest"""
    delete_themes(d)
    pet_theme(d, "uat-pet")
    use_theme(d, "uat-pet")
    check(pet_state(d) == "idle", f"选用后键盘角上不是 idle 的宠物：{pet_state(d)}")
    d.shot("kb", "宠物 idle")
    for press, want in ((lambda: d.type("q"), "typing"), (d.bksp, "delete"), (d.enter, "enter"), (d.space, "space")):
        d.step(want)
        press()
        got = pet_state(d)
        check(got == want, f"按了 {want} 的键，宠物是 {got}")
        d.shot("kb", f"宠物 {want}")
        time.sleep(3.5)
        check(pet_state(d) == "idle", f"{want} 播完没回 idle")
    d.step("sleep")
    time.sleep(31)
    check(pet_state(d) == "sleep", f"30 s 不按键没睡着：{pet_state(d)}")
    d.type("a")
    check(pet_state(d) in ("wake", "typing"), f"睡着时按键没醒：{pet_state(d)}")
    d.step("收起再弹出")
    time.sleep(4)
    # a comma commits at once: the bar is back to its toolbar and its hide button (letters would leave candidates there)
    d.type(",")
    check(pet_state(d) == "typing", f"按了逗号，宠物是 {pet_state(d)}")
    d.hide_keyboard()
    # a keyboard back before its window went away was never hidden
    time.sleep(0.8)
    if d.screen().find(rid="keyboard_view", where="ime"):
        d.sh("input keyevent 4")
        time.sleep(0.8)
    check(not d.screen().find(rid="keyboard_view", where="ime"), "键盘没收起来")
    d.open_page("text")
    check(pet_state(d) == "idle", f"收起再弹出后不是 idle：{pet_state(d)}")
    delete_themes(d)


def total_pss(d):
    """the app's TOTAL PSS in KB after a GC (SIGUSR1 makes ART collect), the median of three readings"""
    out = []
    for _ in range(3):
        pid = d.sh(f"pidof {PKG}").strip()
        if pid:
            d.root(f"kill -10 {pid}")
        time.sleep(1.5)
        m = re.search(r"TOTAL(?: PSS)?:\s+(\d+)", d.sh(f"dumpsys meminfo {PKG}"))
        if m:
            out.append(int(m.group(1)))
    if not out:
        raise Blocked("dumpsys meminfo 读不出 TOTAL")
    return statistics.median(out)


@case("MN-31", base="B0-设置", mutates=True, heavy=True)
def mn31(d):
    """the same theme without and with the pet: the memory it adds within its budget + 1 MB, and 1000 keys add none"""
    heap = d.sh("getprop dalvik.vm.heapgrowthlimit").strip()
    mb = int(re.sub(r"\D", "", heap) or 0)
    budget = 3 if mb <= 192 else 6   # LowMemory.MAX_HEAP_MB, PetFrames' two budgets
    print(f"{d.serial}: MN-31 memoryClass {heap}：宠物预算 {budget} MB", flush=True)
    delete_themes(d)
    pet_theme(d, "uat-nopet", pet=None)
    pet_theme(d, "uat-pet")

    def taps():
        # ⌫ on an empty field: the pet plays each time, the engine and the field take nothing in
        x, y = d.keys(refresh=True)["bksp"]
        d.adb("shell", f"for i in $(seq 1000); do input tap {x} {y}; done", timeout=1800)

    use_theme(d, "uat-nopet")
    d.type("nihao")
    d.clear()
    taps()   # warms the app up as the later rounds find it
    without = total_pss(d)
    taps()
    without_after = total_pss(d)
    delete_themes(d)
    use_theme(d, "uat-pet")
    d.type("nihao")
    d.clear()
    with_pet = total_pss(d)
    print(f"{d.serial}: MN-31 TOTAL PSS 没有宠物 {without} KB（连按 1000 下后 {without_after}），有宠物 {with_pet} KB", flush=True)
    check(with_pet - without <= (budget + 1) * 1024, f"宠物多占 {with_pet - without} KB，超过 {budget} MB + 1 MB")
    d.step("连按 1000 下")
    taps()
    after = total_pss(d)
    grew = (after - with_pet) - (without_after - without)
    print(f"{d.serial}: MN-31 有宠物连按 1000 下后 {after} KB，比没有宠物时多长 {grew} KB", flush=True)
    check(grew <= 1024, f"有宠物连按 1000 下比没有宠物时多长 {grew} KB")
    delete_themes(d)

