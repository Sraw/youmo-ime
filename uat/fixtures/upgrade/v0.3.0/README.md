# Upgrade fixture: 幽默输入法 0.3.0

Made on 2026-10-24 by `uat/fixtures/upgrade/make.py` on a fresh redroid (14, x86_64, arm64 translated,
zh-CN) from the release's `io.github.sraw.youmo-0.3.0-text-arm64-v8a-release.apk`, every change made
through the app's UI.

What was set, in order:
1. First run: settings opened, keyboard shown on the test page, the engine ready (candidates for `ni`).
2. 输入法 → 添加 **双拼** (profile: 拼音, 英文, 双拼).
3. 通用输入 → 性能档位 **中档** (`androidengine.conf`: `ModelLevel=Medium`, the only uncommented line: fcitx writes defaults as `# Key=Value`).
4. 通用输入 → 括号和引号成对输入 **关** (shared preferences).
5. 外观 → 大小与样式 → 键盘高度 **高** (shared preferences).
6. 通用输入 → 中文标点: `,` → **、** (`data/punctuation/punc.mb.zh_CN`).
7. 自定义短语: `dz`, position 1, **北京市海淀区** (`data/pinyin/customphrase`).
8. Learned: `jingmiaoyu` typed twice picking 鲸 喵 屿; afterwards 鲸喵屿 is the first candidate.
9. Status panel → **繁体** (`chttrans.conf`: `[EnabledIM] 0=engine-pinyin`); `guojia` then typed 國家.
10. 备份全部 → saved, pulled as `backup.zip`.
11. Settings opened and left (its onStop saves fcitx's state), app force-stopped, then as root:
    `data.tar` = `/data/data/io.github.sraw.youmo` (without cache, code_cache),
    `external.tar` = `/sdcard/Android/data/io.github.sraw.youmo/files` (fcitx's config/ and data/).

`config/profile` says `DefaultIM=keyboard-us`: that is what this build wrote, kept as is.

To use: install the same APK on a clean device, untar both as root into those paths (owner: the app's
uid), then upgrade with the new APK (`adb install -r`).

## Time per step (seconds)

| step | s |
|---|---|
| redroid up (fresh, zh-CN) | 37.8 |
| install APK | 10.5 |
| first run: settings, keyboard, engine ready | 5.9 |
| 输入法: 添加双拼 | 4.9 |
| 通用输入: 性能档位 中档 | 4.1 |
| 通用输入: 括号成对 关 | 2.6 |
| 外观: 键盘高度 高 | 4.4 |
| 中文标点: , 改成 、 | 9.6 |
| 自定义短语: dz 北京市海淀区 | 18.0 |
| 学到的词: 鲸喵屿 两次 | 65.7 |
| 状态面板: 繁体 | 11.1 |
| 备份全部 + 取回 zip | 11.2 |
| 导出应用文件 (root tar) | 4.3 |
| total | 190 |
