# Gate 2: in-place upgrade

New build: `io.github.sraw.youmo-0.3.2-34-ga4fd7ff7b-text-x86_64-release.apk` (io.github.sraw.youmo 0.3.2-34-ga4fd7ff7b)
Items: `runs/gates/20261026-170646-upgrade/`

Upgrade: in place over the released APK (`install -r`).

| scenario | result | detail |
|---|---|---|
| v0.3.0 → new | PASS | NOT CHECKED 旧原生数据已删 (user_de descriptor.json, usr): 未检查：v0.3.0 无 de.tar |
| v0.3.1 → new | PASS | NOT CHECKED 旧原生数据已删 (user_de descriptor.json, usr): 未检查：v0.3.1 无 de.tar |
| v0.3.2 → new | PASS | NOT CHECKED 旧原生数据已删 (user_de descriptor.json, usr): 未检查：v0.3.2 无 de.tar |

Gate: **PASS**
