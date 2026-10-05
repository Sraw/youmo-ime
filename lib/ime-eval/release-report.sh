#!/bin/bash
# The release report (dev/TRAINING-PLAN.md 13.4): the engine's data judged layer by layer, and,
# against an earlier report's results, sample by sample.
#
#   EVAL=<ime-eval launcher> DATA=<pinyin.data> MODELS=<dir> [PACK=<pack.words>] [SMALL=<small.tsv>]
#     [BASE=<an earlier report's dir>] release-report.sh <out dir>
#
# MODELS holds sentence-model.safetensors and sentence-model-large.safetensors (the app's, as
# app/build/generated/engine-models/engine has them). Writes each set's results and report.txt
# to <out dir>. The gate: with BASE, no gate set may lose significantly more samples than it
# wins (McNemar, p < 0.05 shared among the gate sets, Bonferroni: six sets each at 0.05 would fail
# a release that changed nothing one time in seven); and learning must bring back what was typed
# (95% first) and not cost the rest. It exits 1 when the gate fails, 2 when a run fails or its
# output cannot be read (a gate that cannot read its numbers does not pass).
#
# Gate sets, by layer:
#   base layer, must not get worse   web (recent pages, held out), chat (held-out half), dialog, pinyin
#   new-word layer, types its words  new
#   new-word layer, stays out of the way  collide (a common word reading as a new word)
#   user layer                       learn over chat: typed again, and what was not typed
# Reported only: context, small (when given), slips (mixed-up and slipped syllables), mixed
# (Chinese with an English word: not yet typed at all).
set -o pipefail
OUT=${1:?usage: release-report.sh <out dir>}
: "${EVAL:?EVAL: the ime-eval launcher}" "${DATA:?DATA: the pinyin data}" "${MODELS:?MODELS: the sentence models dir}"
SETS=$(cd "$(dirname "$0")/data" && pwd)
mkdir -p "$OUT" || exit 1
REPORT=$OUT/report.txt
: > "$REPORT"

# name:set:gate weight:half (a weight of 0 is reported only)
SPECS="web:pinyin-web.tsv:0.25: chat:pinyin-chat.tsv:0.20:held-out dialog:pinyin-dialog.tsv:0.15:
new:pinyin-new.tsv:0.20: collide:pinyin-collide.tsv:0.15: pinyin:pinyin.tsv:0.05:
context:pinyin-context.tsv:0: slips:pinyin-slips.tsv:0: mixed:pinyin-mixed.tsv:0:"
# SMALL as given, from where this was run; the sets above, from data/
[ -n "$SMALL" ] && SPECS="$SPECS small:$(realpath "$SMALL"):0:"

failed=0
line=""
gates=$(for spec in $SPECS; do echo "$spec"; done | awk -F: '$3 != 0' | wc -l)
number() { [[ $1 =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "$2: no number in its output (\"$1\")" | tee -a "$REPORT"; exit 2; }; }
for spec in $SPECS; do
  IFS=: read -r name set weight half <<< "$spec"
  [ "${set#/}" = "$set" ] && set=$SETS/$set
  "$EVAL" pinyin "$DATA" "$set" "$OUT/$name.tsv" ${half:+--half $half} \
    --rerank "$MODELS/sentence-model.safetensors" --refine "$MODELS/sentence-model-large.safetensors" \
    ${PACK:+--pack "$PACK"} > /dev/null 2> "$OUT/$name.err" || { echo "$name: the run failed" | tee -a "$REPORT"; exit 2; }
  scores=$("$EVAL" score "$set" "$OUT/$name.tsv" ${BASE:+"$BASE/$name.tsv"} ${half:+--half $half}) || exit 2
  top1=$(echo "$scores" | awk '$1 == "all" { print $4; exit }')
  number "${top1%\%}" "$name top1"
  line="$line $name=$top1:$weight"
  if [ -n "$BASE" ]; then
    flips=$(echo "$scores" | awk '/^group +n +won/ { on = 1; next } on && $1 == "all" { print $3, $4, $5 }')
    read -r won lost p <<< "$flips"
    number "$won" "$name won"; number "$lost" "$name lost"; number "$p" "$name p"
    verdict=""
    if [ "$weight" != 0 ] && [ "$lost" -gt "$won" ] && awk "BEGIN { exit !($p < 0.05 / $gates) }"; then
      verdict=" FAIL"
      failed=1
    fi
    echo "$name: top1 $top1, against BASE won $won lost $lost p $p$verdict" >> "$REPORT"
  fi
done
echo "$line" | awk '{ s = 0; w = 0; for (i = 1; i <= NF; i++) { split($i, a, /[=%:]/); s += a[2] * a[4]; w += a[4] }
  printf "%s weighted=%.1f%%\n", $0, s / w }' | sed 's/:0\(\.[0-9]*\)\?//g' >> "$REPORT"

# the user layer: chat typed once and again, and the held-out half before and after
"$EVAL" learn "$DATA" "$SETS/pinyin-chat.tsv" > "$OUT/learn.txt" 2> "$OUT/learn.err" || { echo "learn: the run failed" | tee -a "$REPORT"; exit 2; }
again=$(awk -F'  +' '$1 ~ /^tune, typed again/ { print $4 }' "$OUT/learn.txt")
nothing=$(awk -F'  +' '$1 ~ /^held-out, learning nothing/ { print $4 }' "$OUT/learn.txt")
learned=$(awk -F'  +' '$1 ~ /^held-out, tune learned$/ { print $4 }' "$OUT/learn.txt")
number "${again%\%}" "learn typed again"; number "${nothing%\%}" "learn nothing"; number "${learned%\%}" "learn learned"
verdict=""
# a point on the held-out 1064 is about ten samples: a loss past that is learning's cost
if awk "BEGIN { exit !(${again%\%} < 95 || ${learned%\%} < ${nothing%\%} - 1) }"; then
  verdict=" FAIL"
  failed=1
fi
echo "learn: typed again $again first; held-out $nothing learning nothing, $learned after the rest was learned$verdict" >> "$REPORT"
cat "$REPORT"
[ $failed = 0 ] || exit 1
