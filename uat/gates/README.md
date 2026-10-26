# Release gates 2, 3, 5

`dev/design/fcitx-replacement.md` §8: run on the release tree before the draft release; also
runnable on any debug build (this tree, or one built at the tag `native-last` for the native host) to find problems early.
Each brings up its own fresh redroid (`--container`, `--port`, `--cpus`; default `youmo-gates`,
5566, 0-5) and removes it after (`--keep` leaves it). Build the APKs before: no build while it is up.

| Gate | Command | What |
|---|---|---|
| 2 | `python3 uat/gates/gates.py upgrade <new.apk> [--old <dir>]` | each `uat/fixtures/upgrade/v0.3.x` upgraded to the new build, then 13 checks (14 with a `de.tar`) |
| 3 | `python3 uat/gates/gates.py backups <new.apk> --old <dir>` | the new build's 备份全部 restored into v0.3.0/0.3.1/0.3.2; each fixture's `backup.zip` restored into the new build |
| 5 | `python3 uat/gates/gates.py perf <label>=<apk>…` | cold start (keyboard, host up, first candidate), per-key latency, PSS/Native/Java heap, side by side |

`<dir>`: the released text APKs, `gh release download v0.3.x -p '*text-arm64*'` for each.

The checks (`phase.py check`), the same after every upgrade and restore: the fixtures' settings
(括号成对 关, 键盘高度 高, 性能档位 中档 in the file and on the settings page), the input method list
and order, 繁体 on (`guojia` gives 國家), the punctuation map (`,` gives 、), the custom phrase
(`dz`), the learned word (`jingmiaoyu` gives 鲸喵屿 first), each read from the files as fcitx reads
them and typed on the test page. A fixture with a `de.tar` (device-protected storage, made by
`make.py` since R15; the three v0.3.x fixtures predate it, so remake them to cover this) also has it
restored, and the check adds that the old native host's synced data there (`descriptor.json`,
`usr/`) is gone after the new build's first start (gate 2 only: in gate 3 an installed 0.3.x build keeps its own synced data there); without one, that migration is not exercised.

A release APK (`io.github.sraw.youmo`) is installed over the old release (`install -r`), as users
get it. A `.debug` APK cannot be (other id and key): gate 2 installs it and puts the fixture's files
into its directories before its first start, and gate 3 retargets each backup to the other id
(`metadata.json` and the preferences file's name, which the import checks).

Gate 5 on redroid x86_64: a release APK is arm64 only and runs translated there, so compare like
with like (two x86_64 debug builds of the same tree, or two arm64 release builds); every latency
includes adb's and the dumper's round trip, the same for each build. A column with no reading
(every run timed out, meminfo unread) shows n/a and fails the gate.

Results: `uat/runs/<date>-gate-<name>-<apk>.md`, every item in `uat/runs/gates/<stamp>-<name>/`.
`python3 -m unittest test_gates` (here) tests the device-free parts.
