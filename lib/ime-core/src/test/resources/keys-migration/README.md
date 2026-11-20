# keys-migration samples (060-keys-unify §6.4)

One directory per version whose long presses `KeyListsMigration` reads: each setting the version
stored (`popup_overrides`, `t9_symbols`) as its own file, the text exactly as
the settings hold it; `key_lists`, the migrated text expected (`@1` first, `fn/…` names: the migration's own output, which
`KeyListsMigration.upgrade` then makes `@2`, 060-row-unlock §5); `lost`,
what cannot be kept, a line each. Written by hand in each version's format: the upgrade fixtures'
make.py needs the old APK on an emulator, and only these settings' text matters here.

- `v0.5.2`, `v0.6.0-beta1`: `popup_overrides` by the character, `t9_symbols`.

`KeyListsMigrationTest.theSamplesMigrate` checks every directory. To remake the expected files
after a deliberate change: `KEYS_MIGRATION_RECORD=1` in the environment of `:lib:ime-core:test`,
then read the diff.
