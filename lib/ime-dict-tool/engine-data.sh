#!/bin/bash
# The engine's data as a release has it: the language model mixed with
# the chat-like pages of FineWeb-2 and of a CommonCrawl crawl, and the new words the crawl
# has that the dictionaries lack, as a word pack. A release-time job, run on a rented machine or
# at home, never in an app build: it downloads some 100 GB and wants 16 GB of memory.
#
#   TOOL=<ime-dict-tool launcher> [EVAL=<ime-eval launcher> SETS=<dir>] [LEXICON=<add.tsv>] [WORDS=<pack.words>]
#     [REMOVE=<remove.tsv>] [READINGS=<readings.tsv>] [MISREADINGS=<misreadings.tsv>] [LATIN=<latin.words>]
#     engine-data.sh <work dir>
#
# The launchers are `installDist`'s (`./gradlew :lib:ime-dict-tool:installDist :lib:ime-eval:installDist`);
# SETS holds the evaluation sets: lib/ime-eval/data/pinyin-new.tsv and the held-out ones of the
# training data (pinyin, pinyin-context, pinyin-chat, pinyin-dialog, small).
# LEXICON is the curated list, lexicon/add.tsv; WORDS the last words-* release's pack of it: its
# words join the model's vocabulary, so the pages are split into them and they get a context, not a
# unigram alone. REMOVE, READINGS and MISREADINGS are lexicon/'s corrections of libime's
# dictionary and LATIN lexicon/latin.words, the Latin words typed inside Chinese, as the app build
# applies them (EngineDataPlugin): with all four the probe and the report measure the data a user
# gets. A path may be relative to where it is started. Out, in <work dir>/out:
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
# it was; data and every step after it are made again when the corrections or LATIN change, and clean
# and mix too when the tool does (crawl keeps the shards it fetched, and the manifest names the jars
# that fetched them).
# done/ is beside out/, not in it, as most steps' work is too, so out/ copied onto another
# machine does not pass for the work there. Needs java 21, curl, zstd, sha256sum.
#
# What goes in is what was measured: CC-MAIN-2026-39's and CC-MAIN-2026-34's WET files
# 10 to 1509, mixed at 0.6 beside FineWeb-2's two shards (0.4 and 0.8 did no better); candidates seen
# 15 times, pmi 1 and entropy 1 or more (a run of two entropy alone: NewWords.GATHER_PMI). A newer
# crawl: CRAWL=CC-MAIN-... (and FROM, FILES); several, CRAWL="CC-MAIN-... CC-MAIN-...", the same files of each.
# Since then WebText, CommonCrawl (MIN_HAN) and PageCleaner count Extension A as Han too (TextChars), as NewWords did.
set -o pipefail
WORK=${1:?usage: engine-data.sh <work dir>}
: "${TOOL:?TOOL: the ime-dict-tool launcher}"
[ -z "$EVAL" ] || : "${SETS:?SETS: the evaluation sets, with EVAL}"
CRAWL=${CRAWL:-CC-MAIN-2026-39 CC-MAIN-2026-34}
FROM=${FROM:-10}
FILES=${FILES:-1500}
WEIGHT=${WEIGHT:-0.6}
# mix held 7 GB and words 6 GB over these inputs, as measured, words before it gathered every run of two
# and each run's rare occurrences (NewWords): give it more (the job gives 40 GB)
export JAVA_OPTS=${JAVA_OPTS:--Xmx12g}
# the paths from where it was started, as it works in <work dir>; a launcher by its name alone is
# looked up on the PATH here, as the stamp and the manifest find its jars beside it
abs() { case $1 in "" | /*) echo "$1" ;; *) echo "$PWD/$1" ;; esac; }
for v in WORDS LEXICON REMOVE READINGS MISREADINGS LATIN SETS; do printf -v "$v" %s "$(abs "${!v}")"; done
for v in TOOL EVAL; do
  case ${!v} in
    */*) printf -v "$v" %s "$(abs "${!v}")" ;;
    ?*) found=$(command -v "${!v}") || { echo "$v: ${!v} not on the PATH"; exit 2; }; printf -v "$v" %s "$(abs "$found")" ;;
  esac
done
# the jars the launcher runs: its start script finds them through a link to it, so readlink too
TOOL_LIB=$(dirname "$(readlink -f "$TOOL")")/../lib
for v in WORDS LEXICON REMOVE READINGS MISREADINGS LATIN; do
  [ -z "${!v}" ] || [ -f "${!v}" ] || { echo "$v: ${!v} not found"; exit 2; }
done
mkdir -p "$WORK"/out "$WORK"/done "$WORK"/src "$WORK"/cc || exit 1
cd "$WORK" || exit 1
rm -f out/FAILED
# a work directory is for one crawl and weight: its finished steps and shards are of those
params="$CRAWL $FROM $FILES $WEIGHT${WORDS:+ $(sha256sum "$WORDS" | cut -c1-16)}"
[ -f params ] || echo "$params" > params
[ "$(cat params)" = "$params" ] || { echo "$WORK was made with $(cat params), not $params: use another"; exit 2; }
# what made the steps past crawl (params holds the crawl, the weight and WORDS): the tool's jars made
# clean, mix and every step after them, the corrections and LATIN data and every step after it; when
# one changes, those steps again, or the manifest would name inputs they were not made of
stamp() {
  local f
  for f in "remove:$REMOVE" "readings:$READINGS" "misreadings:$MISREADINGS" "latin:$LATIN"; do
    [ -z "${f#*:}" ] || echo "${f%%:*} $(sha256sum < "${f#*:}" | cut -c1-16)"
  done
}
tool_stamp=$(sha256sum "$TOOL_LIB"/ime-*.jar | cut -c1-16) || { echo "no ime-*.jar in $TOOL_LIB"; exit 2; }
data_stamp=$(stamp)
now() { date '+%F %T'; }
# with the steps, what they left that a run without LEXICON, EVAL or WEIGHTS would keep: weight
# appends to weights.txt, and the manifest hashes new.words and probe.tsv.zst
redo_data() {
  rm -f done/data done/words done/pack done/curated done/examples done/probe done/compress done/report done/weight-* done/manifest \
    out/new.words out/report.txt out/weights.txt probe.tsv out/probe.tsv.zst
}
if [ "$(cat tool.stamp 2> /dev/null)" != "$tool_stamp" ]; then
  # a work directory older than tool.stamp has none: nothing tells which tool made its clean and mix
  if [ -f tool.stamp ]; then why="the tool's jars are not the ones tool.stamp records"; else why="no tool.stamp"; fi
  [ ! -f done/clean ] || echo "$(now) $why: clean, mix and every step after them again"
  rm -f done/clean done/mix
  redo_data
  echo "$tool_stamp" > tool.stamp
fi
if [ "$(cat data.stamp 2> /dev/null)" != "$data_stamp" ]; then
  [ ! -f done/data ] || echo "$(now) the corrections or LATIN are not the ones data.stamp records: data and every step after it again"
  redo_data
  echo "$data_stamp" > data.stamp
fi
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
# an array, so a path with a space in it stays one argument
CORRECT=()
[ -z "$REMOVE" ] || CORRECT+=(--remove "$REMOVE")
[ -z "$READINGS" ] || CORRECT+=(--readings "$READINGS")
[ -z "$MISREADINGS" ] || CORRECT+=(--misreadings "$MISREADINGS")

# the tool's jars by name and SHA-256, as the manifest names what ran
tool_jars() { sha256sum "$TOOL_LIB"/ime-*.jar | awk '{ n = split($2, p, "/"); print "tool", p[n], $1 }'; }
# a shard is named by its crawl, so several crawls' lie side by side. Which pages it holds is the
# tool's (CommonCrawl's MIN_HAN, by TextChars), yet it is kept when the tool changes, as fetching it
# again is the 100 GB: tool.txt beside the shards keeps every jar crawl ran with, for the manifest
crawl() {
  local c
  mkdir -p cc/raw || return 1
  tool_jars >> cc/raw/tool.txt || return 1
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
# with WORDS, the corrections and LATIN as the app build has them: a curated word is no candidate of
# the base layer, and the probe sees what a user would
# shellcheck disable=SC2086
data() {
  "$TOOL" pinyin -o pinyin.data --lm lm.arpa $DICTS "${CORRECT[@]}" ${WORDS:+"$WORDS"} ${LATIN:+"$LATIN"} > data.log || return 1
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

# evaluate <pack|->: the top choice right, a column a set, then the score weighted by each set's weight below;
# ime-eval's threads are as many as the JVM sees, the container's quota and not the host's cores
# DATA and TAG for a model other than pinyin.data, REPORT for another file than report.txt.
# Full pinyin is read with neighbouring keys, as the app reads it by default and release-report.sh does
evaluate() {
  local pack=$1 tag line="" spec name set weight half score
  tag=${TAG:-$(basename "$pack" .words)}
  for spec in new:$SETS/pinyin-new.tsv:0.25: pinyin:$SETS/pinyin.tsv:0.10:held-out \
      context:$SETS/pinyin-context.tsv:0.05:held-out chat:$SETS/pinyin-chat.tsv:0.35:held-out \
      dialog:$SETS/pinyin-dialog.tsv:0.20: small:$SETS/small.tsv:0.05:; do
    IFS=: read -r name set weight half <<< "$spec"
    "$EVAL" pinyin "${DATA:-pinyin.data}" "$set" evals/$name-$tag.tsv ${half:+--half $half} --neighbours on \
      --tier high --models src \
      $([ "$pack" != - ] && echo --pack "$pack") > /dev/null 2> evals/$name-$tag.err || return 1
    score=$("$EVAL" score "$set" evals/$name-$tag.tsv ${half:+--half $half} | top1) && [ -n "$score" ] ||
      { echo "$name: no score"; return 1; }
    line="$line $name=$score:$weight"
  done
  echo "$tag$line" | awk '{ s = 0; for (i = 2; i <= NF; i++) { split($i, a, /[=%:]/); s += a[2] * a[4] } printf "%s weighted=%.1f%%\n", $0, s }' \
    | sed 's/:0\.[0-9]*//g' >> "${REPORT:-out/report.txt}"
}
# the rate in score's top1 column of its `all` row: by the column's name, so a column put before it
# moves it, not what is read. A copy of release-report.sh's cell, which MainTest runs over score's
# output, reading to the end rather than exit, as score writes into the pipe: nothing tests this
# copy, so change the two together
top1() {
  awk -F'  +' '$1 == "group" { c = 0; for (i = 2; i <= NF; i++) if ($i == "top1") c = i; next }
    c && $1 == "all" && !found { split($c, words, " "); print words[1]; found = 1 }'
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
    "$TOOL" pinyin -o pinyin-$w.data --lm lm-$w.arpa $DICTS "${CORRECT[@]}" ${WORDS:+"$WORDS"} ${LATIN:+"$LATIN"} > data-$w.log &&
    DATA=pinyin-$w.data TAG=weight-$w REPORT=out/weights.txt evaluate - && rm -f lm-$w.arpa pinyin-$w.data
}
manifest() {
  {
    echo "made $(now)${COMMIT:+ by ime-dict-tool at $COMMIT}"
    echo "crawl $CRAWL WET files $FROM to $((FROM + FILES - 1)), mixed at $WEIGHT"
    echo "$SOURCES" | awk 'NF { print "source", $2, $3 }'
    # what ran, whatever COMMIT says: a stale installDist shows here; and what crawl ran, as its
    # shards stay when the tool changes
    tool_jars &&
      { if [ -f cc/raw/tool.txt ]; then sort -u cc/raw/tool.txt | sed 's/^/crawl /'; else echo "crawl tool not recorded: its shards were fetched before engine-data.sh kept cc/raw/tool.txt"; fi; } &&
      { [ -z "$LEXICON" ] || sha256sum "$LEXICON" | awk '{ print "lexicon add.tsv", $1 }'; } &&
      { [ -z "$WORDS" ] || sha256sum "$WORDS" | awk '{ print "words", $1 }'; } &&
      { [ -z "$READINGS" ] || sha256sum "$READINGS" | awk '{ print "lexicon readings.tsv", $1 }'; } &&
      { [ -z "$REMOVE" ] || sha256sum "$REMOVE" | awk '{ print "lexicon remove.tsv", $1 }'; } &&
      { [ -z "$MISREADINGS" ] || sha256sum "$MISREADINGS" | awk '{ print "lexicon misreadings.tsv", $1 }'; } &&
      { [ -z "$LATIN" ] || sha256sum "$LATIN" | awk '{ print "lexicon latin.words", $1 }'; }
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
if [ -n "$EVAL" ]; then
  # EVAL new to the work directory: compress and the manifest again, for out/probe.tsv.zst
  [ -f done/probe ] || rm -f done/compress done/manifest
  step probe probe || exit 1
fi
step compress compress || exit 1
if [ -n "$EVAL" ]; then step report report || exit 1; fi
# shellcheck disable=SC2086
if [ -n "$EVAL" ]; then for w in $WEIGHTS; do step weight-$w weight $w || exit 1; done; fi
step manifest manifest || exit 1
echo "$(now) end"
[ ! -f out/FAILED ]
