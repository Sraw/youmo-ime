# host-assets

fcitx's built-in data that the Kotlin host reads from the APK's assets, under `pkgdata/` in
fcitx's PkgData layout (`AssetPkgDataDir`, `KotlinFcitx.BUILT_IN`). Kept in the repo because the
native trees it came from are deleted; `lib/ime-core`'s tests read these same files, and
`lib/ime-core/tools/host-golden/gen.py` checks them against upstream.

| File | From | SHA-256 | Licence |
|---|---|---|---|
| `data/quickphrase.d/emoji-eac.mb` | fcitx/fcitx5 442edbc9 `src/modules/quickphrase/quickphrase.d/emoji-eac.mb` | `0bc969a977af31c60596a0a95fd0787fb7bbf5171a81c66d656b6bf3badaf243` | LGPL-2.1-or-later |
| `data/quickphrase.d/emoji.mb` | fcitx/fcitx5 442edbc9 `src/modules/quickphrase/quickphrase.d/emoji.mb` | `e129972533ea660a1db43aea64435de7a32a9119af202fd6780984e9725023a5` | LGPL-2.1-or-later |
| `data/quickphrase.d/latex.mb` | fcitx/fcitx5 442edbc9 `src/modules/quickphrase/quickphrase.d/latex.mb` | `2106eae747d501c31fad4a44bac7afd5ad4e70ca3356a4c8366259d720bd024c` | LGPL-2.1-or-later |
| `unicode/charselectdata` | fcitx/fcitx5 442edbc9 `src/modules/unicode/charselectdata` | `82a47500ca9c2918197acbc51ff74e8159bbd91d779cdf7ec483561f86eff841` | LGPL-2.1-or-later |
| `punctuation/punc.mb.zh_CN` | fcitx/fcitx5-chinese-addons 0d3fd040 `modules/punctuation/punc.mb.zh_CN` | `6c120e2e0db8a97c0334901c41b8667475779c4a26b95316492d8b3a42a4d91f` | LGPL-2.1-or-later |

Only `punc.mb.zh_CN` is carried: the host reads the zh_CN table alone (`KotlinFcitx.ZH_CN`). The
licences are listed in `app/licenses/libraries/fcitx5.json` and `fcitx5-chinese-addons.json`.
To fetch the upstream trees: `lib/ime-core/tools/upstream.sh fcitx5|chinese-addons`.
