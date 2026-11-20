# Release gates 2, 3

`dev/design/fcitx-replacement.md` §8: run on the release tree before the draft release; also
runnable on any debug build to find problems early; run before every release (DECISIONS.md B14); gate 2 is paused, see below.
Each brings up uat.py's emulator (the AVD `youmo` from its read-only snapshot: a clean device every
time; `uat/tools/avd.sh` makes it) on `--port` (default 5560, clear of a UAT run's 5554/5556, but `uat.py --devices auto` can reach it) and
kills it after (`--keep` leaves it up to look at; the next run kills a gate's emulator found on the port and starts
it again from the snapshot; one of this AVD a gate did not start, a UAT run's, stops the gate instead, and so does a
kept one a `uat.py run --devices <port>` has taken since: the run drops the gate's mark).
The new build is arm64 (the emulator's ABI); the text pack (`./gradlew :app:packTextPack`) is imported
before the new build's checks, after the fixture's files are in place (the import starts the app, whose
first start moves them); a released build in gate 3 before 0.6.0 has no pack to import (no 资源包 page,
`gates.released_before_pack_page`), one from 0.6.0 on gets it as the new build does. Build the APK and pack before.

| Gate | Command | What |
|---|---|---|
| 2 | `python3 uat/gates/gates.py upgrade <new.apk> [--old <dir>]` | paused: not run at a release (below) |
| 3 | `python3 uat/gates/gates.py backups <new.apk> --old <dir>` | the new build's 备份全部 restored into v0.3.0/0.3.1/0.3.2; each fixture's `backup.zip` restored into the new build |

Gate 2 upgrades each `uat/fixtures/upgrade` fixture (v0.3.0–v0.4.0) in place, a path this build no longer
supports (0.4 and before upgrade through 0.6.2 first), so it is paused till there are 0.5.x fixtures
(`make.py` still runs on redroid only). Gate 3 still puts the v0.3.2 fixture's files in as its starting point.

`<dir>`: the released text APKs, `gh release download v0.3.x -p '*text-arm64*'` for each (0.3.x and
0.4.0 shipped a text and a voice APK; 0.6.0 on ships one). Gate 5 (start, latency, memory) is gone: `uat/tools/perf.py`.

The checks (`phase.py check`), the same after every upgrade and restore: the fixtures' settings
(括号成对 关, 键盘高度 高, 性能档位 中档 in the file and on the settings page), the input method list
and order, 繁体 on (`guojia` gives 國家), the punctuation (the fixtures' edited map gone since 0.6.1, ST-145: `,` gives the stock ，), the custom phrase
(`dz`), the learned word (`jingmiaoyu` gives 鲸喵屿 first), each read from the files as fcitx reads
them and typed on the test page (from `host-config/` and `host-data/` in the app's own storage; for a
v0.3.x build installed in gate 3, from `config/` and `data/` on the external storage, where it keeps
them). A fixture's `de.tar` (device-protected storage: the direct-boot prefs; made by `make.py` since R15, the
three v0.3.x fixtures have none) is put back with the rest; 0.3.x's native host data there (`descriptor.json`,
`usr/`) is left in place, no longer deleted or checked.

A release APK (`io.github.sraw.youmo`) is installed over the old release (`install -r`), as users
get it. A `.debug` APK cannot be (other id and key): gate 2 installs it and puts the fixture's files
into its directories before its first start, and gate 3 retargets each backup to the other id
(`metadata.json` and the preferences file's name, which the import checks).

Results: `uat/runs/<date>-gate-<name>-<apk>.md`, every item in `uat/runs/gates/<stamp>-<name>/`.
`python3 -m unittest test_gates` (here) tests the device-free parts.
