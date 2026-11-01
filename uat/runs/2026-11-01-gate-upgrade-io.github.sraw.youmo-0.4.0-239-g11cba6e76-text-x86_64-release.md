# Gate 2: in-place upgrade

New build: `io.github.sraw.youmo-0.4.0-239-g11cba6e76-text-x86_64-release.apk` (io.github.sraw.youmo 0.4.0-239-g11cba6e76)
Items: `runs/gates/20261101-130742-upgrade/`

Upgrade: in place over the released APK (`install -r`).

| scenario | result | detail |
|---|---|---|
| v0.3.0 → new | PASS | NOT CHECKED 旧原生数据已删 (user_de descriptor.json, usr): 未检查：v0.3.0 无 de.tar |
| v0.3.1 → new | PASS | NOT CHECKED 旧原生数据已删 (user_de descriptor.json, usr): 未检查：v0.3.1 无 de.tar |
| v0.3.2 → new | PASS | NOT CHECKED 旧原生数据已删 (user_de descriptor.json, usr): 未检查：v0.3.2 无 de.tar |
| v0.4.0 → new | PASS | check: 15/15 ok |

Gate: **PASS**
