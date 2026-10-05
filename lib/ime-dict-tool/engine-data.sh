#!/bin/bash
# The engine's data as a release has it (dev/TRAINING-PLAN.md 12.3): the language model mixed with
# the chat-like pages of FineWeb-2 and the pages of a CommonCrawl crawl, and the new words the crawl
# has that the dictionaries lack, as a word pack. A release-time job, run on a rented machine or
# at home, never in an app build: it downloads some 100 GB and wants 16 GB of memory.
#
#   TOOL=<ime-dict-tool launcher> [EVAL=<ime-eval launcher> SETS=<dir>] [LEXICON=<add.tsv>] [WORDS=<pack.words>] engine-data.sh <work dir>
#
# The launchers are `installDist`'s (`./gradlew :lib:ime-dict-tool:installDist :lib:ime-eval:installDist`);
# SETS holds the evaluation sets: lib/ime-eval/data/pinyin-new.tsv and the held-out ones of the
# training data (pinyin, pinyin-context, pinyin-chat, pinyin-dialog, small).
# LEXICON is the curated list, lexicon/add.tsv, by an absolute path; WORDS the last words-* release's
# pack of it, by an absolute path: its words join the model's vocabulary, so the pages are split into
# them and they get a context, not a unigram alone (13.1). Out, in <work dir>/out:
#   lm.tar.zst      the model (lm.arpa, as the app build unpacks it)
#   new.words       the curated list's words as a pack (with LEXICON)
#   pack.words      every candidate that passes, read as the dictionary reads its words: for curating
#   candidates.tsv.zst, examples.tsv.zst, probe.tsv.zst  every candidate's numbers, three sentences of
#                   each packed one, and what the engine types for its reading now (with EVAL): the
#                   rest of a curator's batches (lexicon/tools/batches.py)
#   report.txt      the evaluation (with EVAL), of the model alone (with WORDS, as the app has it) and with each pack
#   weights.txt     with WEIGHTS="0.4 0.8" (and EVAL): the model alone mixed at each of those weights,
#                   evaluated as report.txt's first line, to choose the next release's weight by
#   manifest.txt    what went in, the SHA-256 of what came out
# Each step leaves done/<step> and is skipped when run again, so a run stopped halfway goes on where
# it was; done/ is beside out/, not in it, as most steps' work is too, so out/ copied onto another
# machine does not pass for the work there. Needs java 21, curl, zstd, sha256sum.
#
# What goes in is what was measured (11.7d, 12.10): CC-MAIN-2026-39's and CC-MAIN-2026-34's WET files
# 10 to 1509, mixed at 0.6 beside FineWeb-2's two shards (0.4 and 0.8 did no better); candidates seen
# 15 times, pmi 1 and entropy 1 or more (a run of two entropy alone: NewWords.GATHER_PMI). A newer
# crawl: CRAWL=CC-MAIN-... (and FROM, FILES); several, CRAWL="CC-MAIN-... CC-MAIN-...", the same files of each.
set -o pipefail
WORK=${1:?usage: engine-data.sh <work dir>}
: "${TOOL:?TOOL: the ime-dict-tool launcher}"
CRAWL=${CRAWL:-CC-MAIN-2026-39 CC-MAIN-2026-34}
FROM=${FROM:-10}
FILES=${FILES:-1500}
WEIGHT=${WEIGHT:-0.6}
# mix held 7 GB and words 6 GB over these inputs, as measured, words before it gathered every run of two
# and each run's rare occurrences (NewWords): give it more (the job gives 40 GB)
export JAVA_OPTS=${JAVA_OPTS:--Xmx12g}
[ -z "$WORDS" ] || [ -f "$WORDS" ] || { echo "WORDS: $WORDS not found"; exit 2; }
mkdir -p "$WORK"/out "$WORK"/done "$WORK"/src "$WORK"/cc || exit 1
cd "$WORK" || exit 1
rm -f out/FAILED
# a work directory is for one crawl and weight: its finished steps and shards are of those
params="$CRAWL $FROM $FILES $WEIGHT${WORDS:+ $(sha256sum "$WORDS" | cut -c1-16)}"
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

# libime's sources as build-logic's EngineDataPlugin pins them; FineWeb-2's shards
LIBIME=https://download.fcitx-im.org/data/
FINEWEB=https://huggingface.co/datasets/HuggingFaceFW/fineweb-2/resolve/af9c13333eb981300149d5ca60a8e9d659b276b9/data/cmn_Hani/train/
MODELS=https://github.com/Sraw/youmo-ime/releases/download/sentence-models-20261001/
SOURCES="
$LIBIME lm_sc.arpa-20260629.tar.zst 06808333b9173e5374cf2cb5afc12d08f5625bf9abb536489cac376fc05f2e7f
$LIBIME dict-20260703.tar.zst c686cab6df8964c48d596f57d205bac31fc72870b06a83017e44503df8c09697
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
DICTS="src/dict_sc.txt src/dict_extb.txt"

# a shard is named by its crawl, so several crawls' lie side by side
crawl() {
  local c
  for c in $CRAWL; do "$TOOL" cc -o cc/raw --crawl "$c" --from "$FROM" --files "$FILES" || return 1; done
}
# a bit wider a doubling of the lines, so two crawls' collide no more than the one measured's (PageCleaner)
clean() {
  local n bits=27
  n=$(echo $CRAWL | wc -w)
  while [ "$n" -gt 1 ]; do bits=$((bits + 1)); n=$(((n + 1) / 2)); done
  "$TOOL" clean -o cc/clean --sketch-bits $bits cc/raw/*.parquet
}
mix() { "$TOOL" mix -o lm.arpa --weight "$WEIGHT" --lm src/lm_sc.arpa ${WORDS:+"$WORDS"} src/000_0000?.parquet cc/clean/*.parquet; }
# with WORDS as the app build has it: a curated word is no candidate of the base layer, and the probe
# sees what a user would
# shellcheck disable=SC2086
data() {
  "$TOOL" pinyin -o pinyin.data --lm lm.arpa $DICTS ${WORDS:+"$WORDS"} > data.log || return 1
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
# DATA and TAG for a model other than pinyin.data, REPORT for another file than report.txt
evaluate() {
  local pack=$1 tag line="" spec name set weight half score
  tag=${TAG:-$(basename "$pack" .words)}
  for spec in new:$SETS/pinyin-new.tsv:0.25: pinyin:$SETS/pinyin.tsv:0.10:held-out \
      context:$SETS/pinyin-context.tsv:0.05:held-out chat:$SETS/pinyin-chat.tsv:0.35:held-out \
      dialog:$SETS/pinyin-dialog.tsv:0.20: small:$SETS/small.tsv:0.05:; do
    IFS=: read -r name set weight half <<< "$spec"
    "$EVAL" pinyin "${DATA:-pinyin.data}" "$set" evals/$name-$tag.tsv ${half:+--half $half} \
      --rerank src/sentence-model.safetensors --refine src/sentence-model-large.safetensors \
      $([ "$pack" != - ] && echo --pack "$pack") > /dev/null 2> evals/$name-$tag.err || return 1
    score=$("$EVAL" score "$set" evals/$name-$tag.tsv ${half:+--half $half} | awk '$1=="all"{print $4}') && [ -n "$score" ] ||
      { echo "$name: no score"; return 1; }
    line="$line $name=$score:$weight"
  done
  echo "$tag$line" | awk '{ s = 0; for (i = 2; i <= NF; i++) { split($i, a, /[=%:]/); s += a[2] * a[4] } printf "%s weighted=%.1f%%\n", $0, s }' \
    | sed 's/:0\.[0-9]*//g' >> "${REPORT:-out/report.txt}"
}
report() {
  mkdir -p evals && rm -f out/report.txt
  fetch ${MODELS}sentence-model.safetensors src/sentence-model.safetensors 342ae775e1ee64b6c42af782f55f736c5bf58b904f3b880ee77a576e2268e7fb &&
    fetch ${MODELS}sentence-model-large.safetensors src/sentence-model-large.safetensors 6f7fcb724738e2fbe4c26db70fd53aa5b5729cb4af7ce7f1e7bfea73003e669a &&
    evaluate - && evaluate out/pack.words && { [ ! -f out/new.words ] || evaluate out/new.words; } && cat out/report.txt
}
# the model mixed at another weight, alone: its data built and evaluated, then let go (a few GB each)
weight() {
  local w=$1
  mkdir -p evals && "$TOOL" mix -o lm-$w.arpa --weight "$w" --lm src/lm_sc.arpa ${WORDS:+"$WORDS"} src/000_0000?.parquet cc/clean/*.parquet &&
    "$TOOL" pinyin -o pinyin-$w.data --lm lm-$w.arpa $DICTS ${WORDS:+"$WORDS"} > data-$w.log &&
    DATA=pinyin-$w.data TAG=weight-$w REPORT=out/weights.txt evaluate - && rm -f lm-$w.arpa pinyin-$w.data
}
manifest() {
  {
    echo "made $(now)${COMMIT:+ by ime-dict-tool at $COMMIT}"
    echo "crawl $CRAWL WET files $FROM to $((FROM + FILES - 1)), mixed at $WEIGHT"
    echo "$SOURCES" | awk 'NF { print "source", $2, $3 }'
    # what ran, whatever COMMIT says: a stale installDist shows here
    sha256sum "$(dirname "$TOOL")"/../lib/ime-*.jar | awk '{ n = split($2, p, "/"); print "tool", p[n], $1 }' &&
      { [ -z "$LEXICON" ] || sha256sum "$LEXICON" | awk '{ print "lexicon add.tsv", $1 }'; } &&
      { [ -z "$WORDS" ] || sha256sum "$WORDS" | awk '{ print "words", $1 }'; }
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
# shellcheck disable=SC2086
if [ -n "$EVAL" ]; then for w in $WEIGHTS; do step weight-$w weight $w || exit 1; done; fi
step manifest manifest || exit 1
echo "$(now) end"
[ ! -f out/FAILED ]
