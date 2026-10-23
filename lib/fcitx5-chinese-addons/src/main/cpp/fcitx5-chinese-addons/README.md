# fcitx5-chinese-addons (vendored subset)

From [fcitx5-chinese-addons](https://github.com/fcitx/fcitx5-chinese-addons) 5.1.13 (`0d3fd040`), LGPL-2.1-or-later.
Kept: the modules we build (`modules/chttrans`, `fullwidth`, `punctuation`, and what they need), `tools/`,
`config.h.in`, the licences, and `po/` cut to `zh_CN` and `zh_TW`. The pinyin engine and the rest are not here;
our own engine replaces them.

`po/` keeps every msgid of those two languages, including ones the vendored code never uses:
`app/src/main/cpp/androidengine/androidengine.cpp` looks up pinyin menu strings ("Forget word",
"Pin to top as custom phrase", "Delete from custom phrase", "(Half)") in this domain. Don't trim it to the
vendored sources.

To update: copy the same files from the new upstream tag, then recheck what `../CMakeLists.txt` copied from
upstream's top-level CMake (`PROJECT_VERSION`, `REQUIRED_FCITX_VERSION`, the module setup) and the
LGPL headers.
