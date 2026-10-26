#!/usr/bin/env python3
"""Writes the golden files of androidengine.conf: each case's input read by fcitx's own code
(build.sh's driver) as the addon reads it, pages saved as the addon saves them, and what fcitx
writes and shows. EngineOptionsGoldenTest holds the Kotlin side to them.

usage: gen.py <driver> <out dir>   (out: lib/ime-core/src/test/resources/host/ini)
"""
import os
import shutil
import subprocess
import sys
import tarfile

# the app's input methods and what the driver calls their page (androidengine.cpp:347-357)
PAGES = {
    "engine-pinyin": ("-", "pinyin"), "engine-t9": ("-", "pinyin"), "engine-shuangpin": ("-", "shuangpin"),
    "keyboard-us": ("-", "english"), "engine-handwriting": ("-", "handwriting"),
    "engine-wubi": ("Wubi", "Wubi"), "engine-cangjie": ("Cangjie", "Cangjie"), "engine-ziranma": ("Ziranma", "Ziranma"),
    "engine-erbi": ("Erbi", "Erbi"), "engine-wubipinyin": ("WubiPinyin", "WubiPinyin"),
}

# name: (file as found, [(input method, what its page saves)])
CASES = {
    "new-install": ("", []),
    "defaults-written": ("ShuangpinProfile=Ziranma\nPageSize=7\nPrediction=True\n\n[Fuzzy]\nNG_GN=True\nL_N=False\n\n"
                         "[Cangjie]\nAutoSelect=True\nHint=False\nOrderPolicy=No\n", []),
    "user-changed": ("ShuangpinProfile=\"GB Standard\"\nPageSize=5\nEmoji=False\nModelLevel=Medium\nEnglishSuggest=False\n"
                     "[Fuzzy]\nNG_GN=False\nL_N=True\nZ_ZH=True\n", []),
    "tables": ("[Wubi]\nHint=False\nOrderPolicy=Freq\n[Cangjie]\nAutoSelect=False\n[Ziranma]\n[Erbi]\nOrderPolicy=Fast\n"
               "[WubiPinyin]\nHint=True\n", []),
    "bad-values": ("PageSize=11\nPrediction=true\nModelLevel=Ultra\nShuangpinProfile=Foo\nEmoji=\n"
                   "[Fuzzy]\nL_N=yes\n[Cangjie]\nHint=xyz\n[Wubi]\nOrderPolicy=freq\n", []),
    "number-prefix": ("PageSize=5abc\n", []),
    "number-leading-zero": ("PageSize=08\n", []),
    "number-plus": ("PageSize=+5\n", []),
    "number-negative": ("PageSize=-3\n", []),
    "spaces": ("PageSize = 5\nEmoji= False\n  Chaizi=False  \n[ Fuzzy ]\nL_N=True\n[Fuzzy]\nV_U =True\nC_CH= True\n\tF_H=True\t\n", []),
    "quotes": ("ShuangpinProfile=\"GB Standard\" \nPrediction=\"False\"\nModelLevel=\"Low\"x\"\nLatinWords=\"False\n"
               "Chaizi=\"\\\"False\\\"\"\n", []),
    "unknown-keys": ("Unknown=1\nPageSize=6\n[Wubi]\nAutoPhraseLength=4\nSaveAutoPhraseAfter=-1\nHint=False\n[Other]\nA=1\n"
                     "[Fuzzy/X]\nY=1\n", []),
    "libime-migrated": ("ShuangpinProfile=MS\nPageSize=7\nPrediction=False\n\n[Fuzzy]\nV_U=True\nNG_GN=True\n\n"
                        "[Wubi]\nAutoSelect=True\nHint=True\nOrderPolicy=Fast\n", []),
    "order-kept": ("# ShuangpinProfile=Ziranma\n\n# [Wubi]\n# AutoSelect=True\n# Hint=True\n# OrderPolicy=No\n\n"
                   "[Wubi]\nOrderPolicy=Freq\n", []),
    "group-path-in-key": ("Cangjie/AutoSelect=False\nFuzzy/L_N=True\n", []),
    "crlf": ("PageSize=4\r\n[Fuzzy]\r\nL_N=True\r\n", []),
    "comment-header": ("[Fuzzy]\nL_N=True\n# [Wubi]\nHint=False\n", []),
    "pages": ("", [
        ("engine-pinyin", "PageSize=5\nPrediction=False\n[Fuzzy]\nL_N=True\n"),
        ("engine-wubi", "Hint=False\nPageSize=4\nOrderPolicy=Freq\n"),
        ("keyboard-us", "EnglishSuggest=False\n"),
        ("engine-shuangpin", "ShuangpinProfile=Xiaohe\n"),
        ("engine-cangjie", "AutoSelect=False\n"),
        ("engine-cangjie", "Hint=bad\n"),
        ("engine-pinyin", "Prediction=nope\n"),
        ("engine-handwriting", "ModelLevel=Low\n"),
        ("engine-t9", "[Fuzzy]\nZ_ZH=True\n"),
        ("engine-erbi", "Chaizi=False\nWubi/AutoSelect=False\n"),
        ("not-an-im", "PageSize=9\n"),
    ]),
}


# the real files of the upgrade fixtures (uat/fixtures/upgrade), each release's as its device wrote it
FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../../../uat/fixtures/upgrade")
for version in ("v0.3.0", "v0.3.1", "v0.3.2"):
    with tarfile.open(os.path.join(FIXTURES, version, "external.tar")) as tar:
        conf = tar.extractfile("./config/conf/androidengine.conf").read().decode()
    CASES["device-" + version] = (conf, [])
    CASES["device-" + version + "-pages"] = (conf, [("engine-wubi", "Hint=False\n"), ("engine-pinyin", "[Fuzzy]\nL_N=True\n")])


def run(driver, text, *args):
    return subprocess.run([driver, *args], input=text.encode(), capture_output=True, check=True).stdout.decode()


def page_args(steps):
    out = []
    for im, text in steps:
        if im in PAGES:
            out += ["page", PAGES[im][0], text]
    return out


def main():
    driver, out = sys.argv[1], sys.argv[2]
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    with open(os.path.join(out, "cases.txt"), "w") as f:
        f.write("".join(name + "\n" for name in CASES))
    for name, (text, steps) in CASES.items():
        d = os.path.join(out, name)
        os.makedirs(d)
        with open(os.path.join(d, "in.ini"), "w", newline="") as f:
            f.write(text)
        if steps:
            with open(os.path.join(d, "steps.tsv"), "w") as f:
                for im, page in steps:
                    f.write(im + "\t" + page.replace("\\", "\\\\").replace("\n", "\\n") + "\n")
        args = page_args(steps)
        written = run(driver, text, *args, "save")
        saved = run(driver, text, *args, "nodesc", "save")
        # fcitx reads back what it wrote, and what Kotlin writes (no descriptions), as the same settings
        assert run(driver, written, "nodesc", "save") == saved, name
        assert run(driver, saved, "nodesc", "save") == saved, name
        pages = "".join(
            f"### {im}\n" + run(driver, text, *args, "nodesc", "show", kind) for im, (_, kind) in PAGES.items()
        )
        for file, content in (("fcitx.ini", written), ("saved.ini", saved), ("pages.ini", pages)):
            with open(os.path.join(d, file), "w") as f:
                f.write(content)


if __name__ == "__main__":
    main()
