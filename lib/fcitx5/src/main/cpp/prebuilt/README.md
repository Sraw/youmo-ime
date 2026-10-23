# Prebuilt native dependencies

The libraries the native build links, built for the four Android ABIs by
[fcitx5-android/prebuilder](https://github.com/fcitx5-android/prebuilder) (`eb156443`, NDK 28.0.13004108,
CMake 3.31.6, platform 23), copied from [fcitx5-android/prebuilt](https://github.com/fcitx5-android/prebuilt)
`86ce2c95`. Only these are used; the rest of that repository (boost, librime, libime, …) never was.

| Directory | Library | Version | License | Used by |
|---|---|---|---|---|
| `opencc` | [OpenCC](https://github.com/BYVoid/OpenCC) (+ `data/`, its conversion tables) | 1.2.0 | Apache-2.0 | chttrans (简繁) |
| `marisa` | [marisa-trie](https://github.com/s-yata/marisa-trie) | 0.3.1 | BSD-2-Clause or LGPL-2.1+ | OpenCC |
| `libuv` | [libuv](https://github.com/libuv/libuv) | 1.52.0 | MIT | fcitx5's event loop, `native-lib` |
| `libintl-lite` | [libintl-lite](https://github.com/j-jorge/libintl-lite) | edbb306 | BSL-1.0 | fcitx5's gettext |

Each directory's `LICENSE` is the upstream one. To update, copy the directories from a newer prebuilt
commit and check the app's `.so` files and assets still build.
