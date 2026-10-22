#!/bin/bash
# The release report (dev/TRAINING-PLAN.md 13.4): the engine's data judged layer by layer, and,
# against an earlier report's results, sample by sample.
#
#   EVAL=<ime-eval launcher> DATA=<pinyin.data> MODELS=<dir> [PACK=<pack.words>] [SMALL=<small.tsv>]
#     [BASE=<an earlier report's dir>] release-report.sh <out dir>
#
# MODELS holds sentence-model.safetensors and sentence-model-large.safetensors (the app's, as
# app/build/generated/engine-models/engine has them). Writes each set's results, the options it
# was read with (<name>.options) and report.txt to <out dir>. The gate: with BASE, no gate set may
# lose significantly more samples than it wins (McNemar, p < 0.05 shared among the gate sets,
# Bonferroni: six sets each at 0.05 would fail a release that changed nothing one time in seven);
# and learning must bring back what was typed (95% first) and not cost the rest. It exits 1 when
# the gate fails, 2 when a run fails or its output cannot be read (a gate that cannot read its
# numbers does not pass), and 2, before any set is run and naming every such set, when BASE has no
# results for a gate set read with the options this run reads it with: a BASE made before the
# options were recorded (full pinyin was then read without neighbouring keys), or before a gate
# set was added, is made again with this script. A set reported only that BASE has no such
# results for (added since, or SMALL not given then) is reported without the comparison.
#
# Full pinyin is read with neighbouring keys, as the app reads it by default (EngineSettings.typos);
# the nine keys as the app types them (t9-strict), the set typed in digits by `t9` first.
#
# Gate sets, by layer:
#   base layer, must not get worse   web (recent pages, held out), chat (held-out half), dialog, pinyin
#   new-word layer, types its words  new
#   new-word layer, stays out of the way  collide (a common word reading as a new word)
#   user layer                       learn over chat: typed a third time, and what was not typed
# Reported only: context, small (when given), slips (mixed-up and slipped syllables), mixed
# (Chinese with an English word: not yet typed at all), and chat (held-out half) and pinyin on the
# nine keys (t9-chat, t9-pinyin).
set -o pipefail
OUT=${1:?usage: release-report.sh <out dir>}
: "${EVAL:?EVAL: the ime-eval launcher}" "${DATA:?DATA: the pinyin data}" "${MODELS:?MODELS: the sentence models dir}"
SETS=$(cd "$(dirname "$0")/data" && pwd)
mkdir -p "$OUT" || exit 1
REPORT=$OUT/report.txt
: > "$REPORT"

# name:set:gate weight:half:scheme (a weight of 0 is reported only; a scheme is a nine-key one).
# The set - is SMALL, as given from where this was run: not in this list, as its path may hold a
# space or a colon; the others are in data/
SPECS="web:pinyin-web.tsv:0.25:: chat:pinyin-chat.tsv:0.20:held-out: dialog:pinyin-dialog.tsv:0.15::
new:pinyin-new.tsv:0.20:: collide:pinyin-collide.tsv:0.15:: pinyin:pinyin.tsv:0.05::
context:pinyin-context.tsv:0:: slips:pinyin-slips.tsv:0:: mixed:pinyin-mixed.tsv:0::
t9-chat:pinyin-chat.tsv:0:held-out:t9-strict t9-pinyin:pinyin.tsv:0::t9-strict"
[ -n "$SMALL" ] && SPECS="$SPECS small:-:0::"

failed=0
line=""
gates=$(for spec in $SPECS; do echo "$spec"; done | awk -F: '$3 != 0' | wc -l)
fail() { echo "$1" | tee -a "$REPORT"; exit 2; }
number() { [[ $1 =~ ^[0-9]+(\.[0-9]+)?([eE][-+]?[0-9]+)?$ ]] || fail "$2: no number in its output (\"$1\")"; }
# the first word in the column named $1, in the row whose first cell is $2, of the table on stdin
# whose header names it: by name, so that a column put before it moves it, not what is read.
# Cells are two spaces apart at least, and hold no two in a row (MainTest checks it)
cell() {
  awk -F'  +' -v name="$1" -v row="$2" '$1 == "group" { c = 0; for (i = 2; i <= NF; i++) if ($i == name) c = i; next }
    c && $1 == row { split($c, words, " "); print words[1]; exit }'
}
# how a set is read ($reading), from its spec's $half and $scheme, and what its results hang on
# besides the release ($options): BASE's are compared only if read the same way
read_as() {
  if [ -n "$scheme" ]; then reading=(--scheme "$scheme"); else reading=(--neighbours on); fi
  options="${half:+--half $half }${reading[*]}"
}
# why BASE's results for $name, read with $options, cannot be compared to; nothing if they can
unbased_why() {
  if [ ! -f "$BASE/$name.tsv" ]; then
    echo "no results in BASE to compare to"
  elif [ "$(cat "$BASE/$name.options" 2> /dev/null)" != "$options" ]; then
    echo "BASE read it with other options than $options"
  fi
}
# a gate that cannot compare does not pass: every gate set looked at before any set is run, and all
# that cannot named at once; a set reported only goes without
if [ -n "$BASE" ]; then
  uncompared=()
  for spec in $SPECS; do
    IFS=: read -r name set weight half scheme <<< "$spec"
    [ "$weight" = 0 ] && continue
    read_as
    why=$(unbased_why)
    [ -n "$why" ] && uncompared+=("$name: a gate set, $why")
  done
  [ ${#uncompared[@]} = 0 ] || fail "$(printf '%s\n' "${uncompared[@]}")"
fi
for spec in $SPECS; do
  IFS=: read -r name set weight half scheme <<< "$spec"
  if [ "$set" = - ]; then set=$SMALL; else set=$SETS/$set; fi
  : > "$OUT/$name.err"
  if [ -n "$scheme" ]; then
    "$EVAL" t9 "$set" "$OUT/$name.set.tsv" > /dev/null 2>> "$OUT/$name.err" || fail "$name: the set could not be typed on the nine keys"
    set=$OUT/$name.set.tsv
  fi
  read_as
  base="" unbased=""
  if [ -n "$BASE" ]; then
    unbased=$(unbased_why)
    [ -n "$unbased" ] || base=$BASE/$name.tsv
  fi
  "$EVAL" pinyin "$DATA" "$set" "$OUT/$name.tsv" ${half:+--half $half} "${reading[@]}" \
    --rerank "$MODELS/sentence-model.safetensors" --refine "$MODELS/sentence-model-large.safetensors" \
    ${PACK:+--pack "$PACK"} > /dev/null 2>> "$OUT/$name.err" || fail "$name: the run failed"
  printf '%s\n' "$options" > "$OUT/$name.options"
  scores=$("$EVAL" score "$set" "$OUT/$name.tsv" ${base:+"$base"} ${half:+--half $half} 2>> "$OUT/$name.err") ||
    fail "$name: the scoring failed (see $name.err)"
  top1=$(cell top1 all <<< "$scores")
  number "${top1%\%}" "$name top1"
  line="$line $name=$top1:$weight"
  if [ -n "$base" ]; then
    won=$(cell won all <<< "$scores")
    lost=$(cell lost all <<< "$scores")
    p=$(cell p all <<< "$scores")
    number "$won" "$name won"; number "$lost" "$name lost"; number "$p" "$name p"
    verdict=""
    if [ "$weight" != 0 ] && [ "$lost" -gt "$won" ] && awk "BEGIN { exit !($p < 0.05 / $gates) }"; then
      verdict=" FAIL"
      failed=1
    fi
    echo "$name: top1 $top1, against BASE won $won lost $lost p $p$verdict" >> "$REPORT"
  elif [ -n "$BASE" ]; then
    echo "$name: top1 $top1, $unbased" >> "$REPORT"
  fi
done
echo "$line" | awk '{ s = 0; w = 0; for (i = 1; i <= NF; i++) { split($i, a, /[=%:]/); s += a[2] * a[4]; w += a[4] }
  printf "%s weighted=%.1f%%\n", $0, s / w }' | sed -E 's/:0(\.[0-9]*)?//g' >> "$REPORT"

# the user layer: chat typed once and twice (a correction made twice is first, U-02), and the held-out half before and after
"$EVAL" learn "$DATA" "$SETS/pinyin-chat.tsv" --neighbours on \
  --rerank "$MODELS/sentence-model.safetensors" --refine "$MODELS/sentence-model-large.safetensors" \
  ${PACK:+--pack "$PACK"} > "$OUT/learn.txt" 2> "$OUT/learn.err" || fail "learn: the run failed"
twice=$(cell top1 "tune, typed a third time" < "$OUT/learn.txt")
nothing=$(cell top1 "held-out, learning nothing" < "$OUT/learn.txt")
learned=$(cell top1 "held-out, tune learned" < "$OUT/learn.txt")
number "${twice%\%}" "learn typed a third time"; number "${nothing%\%}" "learn nothing"; number "${learned%\%}" "learn learned"
verdict=""
# a point on the held-out 1064 is about ten samples: a loss past that is learning's cost
if awk "BEGIN { exit !(${twice%\%} < 95 || ${learned%\%} < ${nothing%\%} - 1) }"; then
  verdict=" FAIL"
  failed=1
fi
echo "learn: typed a third time $twice first; held-out $nothing learning nothing, $learned after the rest was learned$verdict" >> "$REPORT"
cat "$REPORT"
[ $failed = 0 ] || exit 1
