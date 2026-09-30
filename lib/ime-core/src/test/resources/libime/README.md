Test fixtures for `engine/libime`.

- `db.main.dict`: libime's 电报码 table as fcitx5-android's prebuilt ships it (zstd, version 2).
- `extra.dict`, `user.dict`, `user.history`: what the app kept under libime on an emulator: a
  dictionary imported from text, the (empty) user dictionary and the pinyin history.
- `*.zst`: made by the zstd 1.5.5 tool. `text.*` is bytes 10000–150000 of libime's
  `dict_sc.txt` at `-19`, `--fast=5` and `-19 --zstd=wlog=10`; `random.zst` 1000 random bytes at
  `-19`; `zeros.zst` 100000 zeros at `-3`; `unchecked.zst` the first 1000 bytes of
  `dict_sc.txt` at `-3 --no-check`; `two-frames.zst` that frame at `-3` followed by
  `random.zst`. The tests check what they unpack to by its SHA-256.
