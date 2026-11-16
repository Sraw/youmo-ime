# theme-shift060

FB12（dev/design/061-theme-separator-migration.md）的夹具：uat/14-settings.md 的 ST-143、15-privacy-backup.md 的 PR-63。
`qwerty.xml` 是 v0.6.0 内置的 26 键布局原文（`git show v0.6.0:lib/keyboard-scene/src/commonMain/layouts/qwerty.xml`），
只把 `name=` 改成「旧 26 键」：⇧ 写 `when="!table"`，没有分词键。脚本 `push_shift060_pack`（uat/scripts/14-settings.py）
把它和内置 Nord（改名）打成 `uat-shift060.theme`、`uat-shift060b.theme`。
