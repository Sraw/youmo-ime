# keys-migration samples (060-keys-unify §6.4)

One directory per version whose long presses `KeyListsMigration` reads: each setting the version
stored (`popup_overrides`, `t9_symbols`, beta2's `key_popups`) as its own file, the text exactly as
the settings hold it; `key_lists`, the migrated text expected (`@1` first, `fn/…` names); `lost`,
what cannot be kept, a line each. Written by hand in each version's format: the upgrade fixtures'
make.py needs the old APK on an emulator, and only these settings' text matters here.

- `v0.5.2`, `v0.6.0-beta1`: `popup_overrides` by the character, `t9_symbols`.
- `v0.6.0-beta2`: `key_popups` as beta2's editor stored it, the symbols alone (`t9/2 # @`).
- `dev-after-beta2`: `key_popups` with the whole list, first and row in front of the symbols
  (`t9/2 2 A B C a b c # @`). Only development builds after beta2 stored these; no release did.

`KeyListsMigrationTest.theSamplesMigrate` checks every directory. To remake the expected files
after a deliberate change: `KEYS_MIGRATION_RECORD=1` in the environment of `:lib:ime-core:test`,
then read the diff.
