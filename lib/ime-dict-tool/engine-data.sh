#!/bin/bash
# The engine's data as a release has it (dev/TRAINING-PLAN.md 12.3): the language model mixed with
# the chat-like pages of FineWeb-2 and the pages of a CommonCrawl crawl, and the new words the crawl
# has that the dictionaries lack, as a word pack. A release-time job, run on a rented machine or
# at home, never in an app build: it downloads some 100 GB and wants 16 GB of memory.
#
#   TOOL=<ime-dict-tool launcher> [EVAL=<ime-eval launcher> SETS=<dir>] [LEXICON=<add.tsv>] engine-data.sh <work dir>
#
# The launchers are `installDist`'s (`./gradlew :lib:ime-dict-tool:installDist :lib:ime-eval:installDist`);
# SETS holds the evaluation sets: lib/ime-eval/data/pinyin-new.tsv and the held-out ones of the
# training data (pinyin, pinyin-context, pinyin-chat, pinyin-dialog, small).
# LEXICON is the curated list, lexicon/add.tsv, by an absolute path. Out, in <work dir>/out:
#   lm.tar.zst      the model (lm.arpa, as the app build unpacks it)
#   new.words       the curated list's words as a pack (with LEXICON)
#   pack.words      every candidate that passes, read as the dictionary reads its words: for curating
#   candidates.tsv.zst, examples.tsv.zst, probe.tsv.zst  every candidate's numbers, three sentences of
#                   each packed one, and what the engine types for its reading now (with EVAL): the
#                   rest of a curator's batches (lexicon/tools/batches.py)
#   report.txt      the evaluation (with EVAL), of the model alone and with each pack
#   manifest.txt    what went in, the SHA-256 of what came out
# Each step leaves done/<step> and is skipped when run again, so a run stopped halfway goes on where
# it was; done/ is beside out/, not in it, as most steps' work is too, so out/ copied onto another
# machine does not pass for the work there. Needs java 21, curl, zstd, sha256sum.
#
# What goes in is what was measured (11.7d): CC-MAIN-2026-39's WET files 10 to 1509, mixed at 0.6
# beside FineWeb-2's two shards; candidates seen 15 times, pmi 1 and entropy 1 or more. A newer
# crawl: CRAWL=CC-MAIN-... (and FROM, FILES).
set -o pipefail
WORK=${1:?usage: engine-data.sh <work dir>}
: "${TOOL:?TOOL: the ime-dict-tool launcher}"
CRAWL=${CRAWL:-CC-MAIN-2026-39}
FROM=${FROM:-10}
FILES=${FILES:-1500}
WEIGHT=${WEIGHT:-0.6}
# mix held 7 GB and words 6 GB over these inputs, as measured; room for a bigger crawl
export JAVA_OPTS=${JAVA_OPTS:--Xmx12g}
mkdir -p "$WORK"/out "$WORK"/done "$WORK"/src "$WORK"/cc || exit 1
cd "$WORK" || exit 1
rm -f out/FAILED
# a work directory is for one crawl and weight: its finished steps and shards are of those
params="$CRAWL $FROM $FILES $WEIGHT"
[ -f params ] || echo "$params" > params
[ "$(cat params)" = "$params" ] || { echo "$WORK was made with $(cat params), not $params: use another"; exit 2; }
now() { date '+%F %T'; }
step() {
  local name=$1; shift
  [ -f done/$name ] && return
  echo "$(now) == $name"
  if "$@"; then touch done/$name; else echo "$(now) FAILED $name"; echo $name >> out/FAILED; return 1; fi
}
# fetch <url> <file> <sha256>: a file there already is checked, not fetched again
fetch() {
  [ -f "$2" ] && echo "$3  $2" | sha256sum -c --quiet - && return
  curl -fsSL --retry 5 -o "$2.part" "$1" && echo "$3  $2.part" | sha256sum -c --quiet - && mv "$2.part" "$2"
}

# libime's sources and 万象's dictionaries as build-logic's EngineDataPlugin pins them; FineWeb-2's shards
LIBIME=https://download.fcitx-im.org/data/
WANXIANG=https://raw.githubusercontent.com/amzxyz/rime_wanxiang/55fbad487c637d64a0371b74d177302ac1cdd16e/dicts/
FINEWEB=https://huggingface.co/datasets/HuggingFaceFW/fineweb-2/resolve/af9c13333eb981300149d5ca60a8e9d659b276b9/data/cmn_Hani/train/
MODELS=https://github.com/Sraw/youmo-ime/releases/download/sentence-models-20261001/
SOURCES="
$LIBIME lm_sc.arpa-20260629.tar.zst 06808333b9173e5374cf2cb5afc12d08f5625bf9abb536489cac376fc05f2e7f
$LIBIME dict-20260703.tar.zst c686cab6df8964c48d596f57d205bac31fc72870b06a83017e44503df8c09697
$WANXIANG zi.dict.yaml 4be1b3689bb6a5e9316583b3aa2e8c1cb83a33a7efe3775568e957212943b35a
$WANXIANG jichu.dict.yaml 99c09968033e4a8e4e73f9e1cca7240ddb48a2af75cf2e745659a0b534e4c502
$WANXIANG lianxiang.dict.yaml 46ad9ba434e5f1c5e2a38adefa3a8d542d26aa1c9eb588407bea82a9240e1c3f
$WANXIANG duoyin.dict.yaml 36d3110e14cc58910bcb586cef0c0cb193d4f571332baf517932c39d9498f9ac
$WANXIANG diming.dict.yaml 627b351e6fa660a40cef86dd923a4bafdeb0bc052c3f2b5eb36600b0d839e0d2
$WANXIANG renming.dict.yaml 4c171aa4f5608934f5c504d8435c0e85dc06b938a1337a1fb041819dca1ca631
$FINEWEB 000_00000.parquet 3e43fefabc3ee500f9874655ece1776f96b81568cf33e0a6376835425ce42598
$FINEWEB 000_00001.parquet 1829410bee959d64fee8c34efd3e741f22c368cfe62aaa7a971a56afabe1d92f
"
sources() {
  echo "$SOURCES" | while read -r base name sha; do
    [ -n "$name" ] || continue
    fetch "$base$name" "src/$name" "$sha" || { echo "$name: not fetched or not as pinned"; return 1; }
  done || return 1
  tar -I zstd -xf src/lm_sc.arpa-20260629.tar.zst -C src && tar -I zstd -xf src/dict-20260703.tar.zst -C src
}
DICTS="src/dict_sc.txt src/dict_extb.txt src/zi.dict.yaml src/jichu.dict.yaml src/lianxiang.dict.yaml src/duoyin.dict.yaml src/diming.dict.yaml src/renming.dict.yaml"

crawl() { "$TOOL" cc -o cc/raw --crawl "$CRAWL" --from "$FROM" --files "$FILES"; }
clean() { "$TOOL" clean -o cc/clean cc/raw/*.parquet; }
mix() { "$TOOL" mix -o lm.arpa --weight "$WEIGHT" --lm src/lm_sc.arpa src/000_0000?.parquet cc/clean/*.parquet; }
# shellcheck disable=SC2086
data() {
  "$TOOL" pinyin -o pinyin.data --lm lm.arpa $DICTS > data.log || return 1
  grep -v '^wrote\|^trie' data.log
  return 0
}
words() { "$TOOL" words -o candidates.tsv --data pinyin.data --min-count 15 cc/clean/*.parquet; }
pack() {
  "$TOOL" pack -o out/pack.words --data pinyin.data --layer new --min-count 15 --min-pmi 1 --min-entropy 1 candidates.tsv
}
curated() { "$TOOL" pack -o out/new.words --data pinyin.data --layer new --min-count 15 --lexicon "$LEXICON" candidates.tsv; }
examples() { "$TOOL" examples -o examples.tsv --only out/pack.words cc/clean/*.parquet; }
probe() {
  awk -F'\t' 'NR > 2 { p = $2; gsub("\x27", "", p); print p "\t" $1 "\tpack" }' out/pack.words > probe-set.tsv &&
    "$EVAL" pinyin pinyin.data probe-set.tsv probe.tsv > /dev/null
}
compress() {
  tar -I 'zstd -19 -T0' -cf out/lm.tar.zst lm.arpa || return 1
  for f in candidates examples probe; do
    [ ! -f $f.tsv ] || zstd -q -19 -T0 -f $f.tsv -o out/$f.tsv.zst || return 1
  done
}

# evaluate <pack|->: the top choice right, a column a set, then the score weighted as 12.7 weighs them;
# ime-eval's threads are as many as the JVM sees, the container's quota and not the host's cores
evaluate() {
  local pack=$1 tag line="" spec name set weight half score
  tag=$(basename "$pack" .words)
  for spec in new:$SETS/pinyin-new.tsv:0.25: pinyin:$SETS/pinyin.tsv:0.10:held-out \
      context:$SETS/pinyin-context.tsv:0.05:held-out chat:$SETS/pinyin-chat.tsv:0.35:held-out \
      dialog:$SETS/pinyin-dialog.tsv:0.20: small:$SETS/small.tsv:0.05:; do
    IFS=: read -r name set weight half <<< "$spec"
    "$EVAL" pinyin pinyin.data "$set" evals/$name-$tag.tsv ${half:+--half $half} \
      --rerank src/sentence-model.safetensors --refine src/sentence-model-large.safetensors \
      $([ "$pack" != - ] && echo --pack "$pack") > /dev/null 2> evals/$name-$tag.err || return 1
    score=$("$EVAL" score "$set" evals/$name-$tag.tsv ${half:+--half $half} | awk '$1=="all"{print $4}') && [ -n "$score" ] ||
      { echo "$name: no score"; return 1; }
    line="$line $name=$score:$weight"
  done
  echo "$tag$line" | awk '{ s = 0; for (i = 2; i <= NF; i++) { split($i, a, /[=%:]/); s += a[2] * a[4] } printf "%s weighted=%.1f%%\n", $0, s }' \
    | sed 's/:0\.[0-9]*//g' >> out/report.txt
}
report() {
  mkdir -p evals && rm -f out/report.txt
  fetch ${MODELS}sentence-model.safetensors src/sentence-model.safetensors 342ae775e1ee64b6c42af782f55f736c5bf58b904f3b880ee77a576e2268e7fb &&
    fetch ${MODELS}sentence-model-large.safetensors src/sentence-model-large.safetensors 6f7fcb724738e2fbe4c26db70fd53aa5b5729cb4af7ce7f1e7bfea73003e669a &&
    evaluate - && evaluate out/pack.words && { [ ! -f out/new.words ] || evaluate out/new.words; } && cat out/report.txt
}
manifest() {
  {
    echo "made $(now)${COMMIT:+ by ime-dict-tool at $COMMIT}"
    echo "crawl $CRAWL WET files $FROM to $((FROM + FILES - 1)), mixed at $WEIGHT"
    echo "$SOURCES" | awk 'NF { print "source", $2, $3 }'
    # what ran, whatever COMMIT says: a stale installDist shows here
    sha256sum "$(dirname "$TOOL")"/../lib/ime-*.jar | awk '{ n = split($2, p, "/"); print "tool", p[n], $1 }' &&
      { [ -z "$LEXICON" ] || sha256sum "$LEXICON" | awk '{ print "lexicon add.tsv", $1 }'; }
  } > out/manifest.txt || return 1
  # shellcheck disable=SC2046
  (cd out && sha256sum lm.tar.zst $(ls new.words pack.words ./*.tsv.zst 2> /dev/null)) >> out/manifest.txt
}

echo "$(now) start: $(nproc) cores, $(free -g | awk '/^Mem/{print $2}') GB, $(java -version 2>&1 | head -1)"
step sources sources || exit 1
step crawl crawl || exit 1
step clean clean || exit 1
step mix mix || exit 1
step data data || exit 1
step words words || exit 1
step pack pack || exit 1
# the list changes between runs where nothing else does, and packing it takes a minute: always again
if [ -n "$LEXICON" ]; then rm -f done/curated done/report done/manifest; step curated curated || exit 1; fi
step examples examples || exit 1
if [ -n "$EVAL" ]; then step probe probe || exit 1; fi
step compress compress || exit 1
if [ -n "$EVAL" ]; then step report report || exit 1; fi
step manifest manifest || exit 1
echo "$(now) end"
[ ! -f out/FAILED ]
