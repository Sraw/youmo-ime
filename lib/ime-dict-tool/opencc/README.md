# OpenCC 1.2.0's s2tw dictionaries

`STPhrases.txt`, `STCharacters.txt` and `TWVariants.txt` are `data/dictionary/` of OpenCC's tag
`ver.1.2.0` (https://github.com/BYVoid/OpenCC, tarball SHA-256
`f4f86eb25e239450d075081e08594801aa063c298d21d9f6c6aa85cd55241962`, the one
`lib/ime-eval/tools/opencc/build.sh` builds the reference from), unchanged; Apache-2.0, `LICENSE`.
They are the three dictionaries `s2tw.json` reads, the conversion chttrans runs. The data is frozen
at 1.2.0: the golden outputs in `lib/ime-eval/src/test/resources/opencc/` hold the Kotlin converter
to that release, and a new release is a change of its own, measured again.

`ime-dict-tool opencc` compiles them into `engine/opencc-s2tw.data` (`OpenCCData` in ime-core) as
part of every app build.

| File | SHA-256 |
|---|---|
| `STPhrases.txt` | `1ce1869151530b6ea388ace1ae4efb3d51d1faa6e01c957f8d60313083a3969f` |
| `STCharacters.txt` | `9cedfb8bf13a220087103d9a96d9f56050c341c24a809cbce5c85c9045456557` (the same as `app/src/main/assets/learn/STCharacters.txt`) |
| `TWVariants.txt` | `89473e96e3f61e9bd3f2e303b9d88ac9caa61effb1faadcef94ff5e65b8ed54b` |
