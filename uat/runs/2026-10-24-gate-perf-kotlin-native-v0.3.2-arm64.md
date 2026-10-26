# Gate 5: start, key latency, memory

Items: `runs/gates/20261024-042600-perf/`. redroid x86_64, cpus 0-5, 2 GB. Cold start: from `am force-stop` (the field focused, tapped again), until the keyboard shows, the space key names the input method, and `ni` tapped then has a candidate (medians of 5). Key: `input tap` to the composing row or the bar changing, 8 words twice, through adb and the UI dumper (its read cost is in every number, the same for both). Memory: dumpsys meminfo after a GC, 6 samples 1.5 s apart, the first dropped (it reads high).

| build | version | cold start ms: keyboard / host up / candidate | key ms p50 / p90 | PSS MB | Native heap MB | Java heap MB |
|---|---|---|---|---|---|---|
| kotlin | 0.3.2-172-g525433d7b | 2792 / 5388 / 6313 (Kotlin host's own: 585) | 387.5 / 465 | 191.7 | 25.6 | 42.7 |
| native | 0.3.2-172-g525433d7b | 2391 / 4555 / 5574 | 403.5 / 473 | 187.4 | 26.1 | 33.2 |
| v0.3.2-arm64 | 0.3.2 | 1641 / 1641 / 2072 | 331.0 / 511 | 184.1 | 26.5 | 26.0 |
