# Gate 5: start, key latency, memory

Items: `runs/gates/20261024-055647-perf/`. redroid x86_64, cpus 0-5, 2 GB. Cold start: from `am force-stop` (the field focused, tapped again), until the keyboard shows, the space key names the input method, and `ni` tapped then has a candidate (medians of 5). Key: `input tap` to the composing row or the bar changing, 8 words twice, through adb and the UI dumper (its read cost is in every number, the same for both). Memory: dumpsys meminfo after a GC, 6 samples 1.5 s apart, the first dropped (it reads high). Builds measured A B A B on one device, the second round reported.

| build | version | cold start ms: keyboard / host up / candidate | key ms p50 / p90 | PSS MB | Native heap MB | Java heap MB |
|---|---|---|---|---|---|---|
| kotlin | 0.3.2-178-ga181be581 | 1814 / 3341 / 4222 (Kotlin host's own: 46) | 348.0 / 409 | 180.2 | 25.5 | 33.6 |
| native | 0.3.2-175-g015b0e89c | 1807 / 3346 / 4267 | 347.0 / 405 | 188.5 | 25.4 | 33.2 |
