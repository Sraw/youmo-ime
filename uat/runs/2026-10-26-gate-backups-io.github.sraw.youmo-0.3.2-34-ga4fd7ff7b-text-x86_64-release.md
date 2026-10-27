# Gate 3: backups both ways

New build: `io.github.sraw.youmo-0.3.2-34-ga4fd7ff7b-text-x86_64-release.apk` (io.github.sraw.youmo 0.3.2-34-ga4fd7ff7b)
Items: `runs/gates/20261026-170914-backups/`

New → old: the new build upgraded from the v0.3.2 fixture, 繁体 and 性能档位 changed and changed back from its keyboard and settings (so it wrote those files), 备份全部; that zip restored into each released build on a clean install. Old → new: each fixture's backup.zip restored into a clean new build.

| scenario | result | detail |
|---|---|---|
| new build itself (after its backup) | PASS | check: 13/13 ok |
| new → v0.3.0 | FAIL | 学到的词: jingmiaoyu 第一个是鲸喵屿: ['精妙與', '精妙於', '經廟宇'] |
| new → v0.3.1 | PASS | check: 13/13 ok |
| new → v0.3.2 | PASS | check: 13/13 ok |
| v0.3.0 backup → new | PASS | check: 13/13 ok |
| v0.3.1 backup → new | PASS | check: 13/13 ok |
| v0.3.2 backup → new | PASS | check: 13/13 ok |

Gate: **FAIL**
