# scel2org5 golden files

What the native `libscel2org5.so` of 幽默输入法 0.3.2 (the release's arm64 text APK, see `VERSION`)
makes of each `.scel` here, called the way the app calls it (`PinyinDictManager.kt:370`:
`scel2org5 -o <dest> <src>`, no `-a`). When scel import moves to Kotlin, its output is compared
with these files; the native tool is not needed again.

| Input | What it is | `.rc` | `.txt` lines |
|---|---|---|---|
| `extra.scel` | the two-word UAT dictionary (`uat/fixtures/extra.scel`) | 0 | 2 |
| `sogou-4.scel` | Sogou cell dictionary 4, 网络流行新词 (DCS header) | 0 | 7105 |
| `sogou-75228.scel` | Sogou cell dictionary 75228, 开发大神专用词库 (DCS) | 0 | 430 |
| `sogou-15117.scel` | Sogou cell dictionary 15117, 计算机词汇大全 (the newer ECS header) | 0 | 10300 |
| `truncated.scel` | the first 3000 bytes of `sogou-75228.scel` | 134 | none |
| `not-scel.scel` | one line of text | 134 | none |

Per input: `<name>.txt` the file written with `-o` (`词\tpin'yin\t0` per line, `scel2org5.cpp:447`),
`<name>.out` stdout and stderr (the DESC/SOURCE/LONGDESC/EXAMPLE header; for a bad file the
error, log time and addresses stripped), `<name>.rc` the exit status. The two bad files abort
(134) after the error: FORTIFY reports a destroyed mutex at exit; the app sees a non-zero status
either way (`PinyinDictManager.runConverter`).

The Sogou files were downloaded on 2026-10-23 from
`https://pinyin.sogou.com/d/dict/download_cell.php?id=<id>&name=x`.

Regenerate on a throwaway redroid with the APK installed: `uat/fixtures/scel/make.sh <container> <serial>`
(root; it overlays the app's libraries on `/system/lib64/arm64`, the only place the arm64
translation's linker looks, see the script).
