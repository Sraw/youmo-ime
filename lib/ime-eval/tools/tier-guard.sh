#!/bin/bash
# The 性能档位 guard: five sets typed at each tier, held row for row to lib/ime-eval/data/tier-baseline.tsv.
#
#   EVAL=<ime-eval launcher> DATA=<pinyin.data> MODELS=<dir> tier-guard.sh [--rebaseline] [--device] [<work dir>]
#
# MODELS holds sentence-model.safetensors and sentence-model-large.safetensors (as
# app/build/generated/engine-models/engine has them). Sets: full pinyin (pinyin-chat.tsv), the nine keys
# (pinyin-chat.tsv typed by `t9`, read t9-strict with 中英混输), 小鹤 (pinyin-chat.tsv typed by `shuangpin
# xiaohe`), the main set (pinyin.tsv) and full pinyin past the large model's window (pinyin-long.tsv), where
# the start it reads from is chosen; every one with neighbouring keys, as the app reads by default.
# Each set × tier gives its first-candidate and first-five rows; high also the keys its gate sent to the
# large model. Exits 0 when every number equals the baseline's, 1 when one differs, 2 when a run fails,
# and 3 when the data or a model is not the baseline's (a new baseline is due: no verdict). Without a work dir it
# works in a temporary one, removed at the end.
#
# --device also types every set at each tier on the connected device (run-on-device.sh, its INSTALL for the
# first run; the debug APK's data and models must be these) and holds its input and first five candidates,
# row for row, to the JVM's: <work dir>/<set>-<tier>.shown.tsv and .device.tsv, with no timing. Where the device
# row has more of the built-in date, time and weekday phrases the app puts after a first candidate (rq, sj, xq:
# 2026年11月22日, 15:06, 周日, …; ime-eval has none), both rows are taken less them, the JVM's first cut by as
# many as the app added; other rows are held whole. Exits 4 where one differs, before the verdict.
#
# --rebaseline writes the baseline anew, only where the gate does what it was measured to: escalating
# 25–55% of full pinyin's and 小鹤's keys and 0.5–10% of the nine keys'; high's first ahead of medium's on
# full pinyin and 小鹤, and nowhere more than 0.2 points behind; medium nowhere behind low. Else exit 1.
set -o pipefail
REBASE=0 DEVICE=0
while [ "${1#--}" != "$1" ]; do
  case $1 in
    --rebaseline) REBASE=1 ;;
    --device) DEVICE=1 ;;
    *) echo "$1: --rebaseline or --device" >&2; exit 2 ;;
  esac
  shift
done
: "${EVAL:?EVAL: the ime-eval launcher}" "${DATA:?DATA: the pinyin data}" "${MODELS:?MODELS: the sentence models dir}"
HERE=$(cd "$(dirname "$0")/.." && pwd)
SETS=$HERE/data
BASELINE=$SETS/tier-baseline.tsv
if [ -n "$1" ]; then
  WORK=$1
else
  [ "$DEVICE" = 0 ] || { echo "--device: give a work dir, to keep the rows that differ" >&2; exit 2; }
  WORK=$(mktemp -d "${TMPDIR:-/tmp}/tier-guard.XXXXXX") || exit 2
  trap 'rm -rf "$WORK"' EXIT
fi
mkdir -p "$WORK" && WORK=$(cd "$WORK" && pwd) || exit 2
THREADS=${THREADS:-$(sysctl -n hw.ncpu 2>/dev/null || nproc)}

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
hashes() {
  printf 'sha256\tpinyin.data\t%s\n' "$(sha "$DATA")"
  for m in sentence-model.safetensors sentence-model-large.safetensors; do printf 'sha256\t%s\t%s\n' "$m" "$(sha "$MODELS/$m")"; done
}
if [ "$REBASE" = 0 ]; then
  [ -f "$BASELINE" ] || { echo "no baseline: $BASELINE (--rebaseline makes one)" >&2; exit 3; }
  if [ "$(hashes)" != "$(grep '^sha256' "$BASELINE")" ]; then
    echo "the data or a model changed since the baseline: a new baseline is due (--rebaseline)" >&2
    exit 3
  fi
fi

"$EVAL" t9 "$SETS/pinyin-chat.tsv" "$WORK/chat-t9.tsv" > /dev/null || exit 2
"$EVAL" shuangpin xiaohe "$SETS/pinyin-chat.tsv" "$WORK/chat-xiaohe.tsv" > /dev/null || exit 2

# name, set, reading, and the input method and run-on-device.sh's variables that type it on the device so
RUNS=(
  "pinyin	$SETS/pinyin-chat.tsv	--neighbours on	engine-pinyin"
  "t9	$WORK/chat-t9.tsv	--scheme t9-strict --latin on --neighbours on	engine-pinyin NINE=1"
  "xiaohe	$WORK/chat-xiaohe.tsv	--scheme xiaohe --neighbours on	engine-shuangpin"
  "main	$SETS/pinyin.tsv	--neighbours on	engine-pinyin"
  "long	$SETS/pinyin-long.tsv	--neighbours on	engine-pinyin"
)
# rows, first-candidate rows and first-five rows of <set> <result>: as Metrics scores them, a result line a sample in order
count() {
  awk -F'\t' 'NR == FNR { if ($0 !~ /^#/ && $0 ~ /[^[:space:]]/) { n++; input[n] = $1; want[n] = $2 } next }
    /[^[:space:]]/ { r++; if ($1 != input[r]) { print "result " r " is not of " input[r] > "/dev/stderr"; exit 2 }
      if ($3 == want[r]) top1++; for (i = 3; i <= 7 && i <= NF; i++) if ($i == want[r]) { top5++; break } }
    END { if (r != n) { print r " results for " n " samples" > "/dev/stderr"; exit 2 } print n "\t" top1 + 0 "\t" top5 + 0 }' "$1" "$2"
}
NOW=$WORK/now.tsv
hashes > "$NOW"
printf 'set\ttier\trows\ttop1\ttop5\tescalated\tkeys\n' >> "$NOW"
for run in "${RUNS[@]}"; do
  IFS=$'\t' read -r name set reading _ <<< "$run"
  for tier in low medium high; do
    # shellcheck disable=SC2086
    out=$("$EVAL" pinyin "$DATA" "$set" "$WORK/$name-$tier.tsv" $reading --tier "$tier" --models "$MODELS" --threads "$THREADS") || { echo "$name $tier: the run failed" >&2; exit 2; }
    counted=$(count "$set" "$WORK/$name-$tier.tsv") || exit 2
    escalated=- keys=-
    if [ "$tier" = high ]; then
      read -r escalated keys <<< "$(sed -n 's/^escalated \([0-9]*\) of \([0-9]*\) keys$/\1 \2/p' <<< "$out")"
      [ -n "$keys" ] || { echo "$name high: no escalation count" >&2; exit 2; }
    fi
    printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$tier" "$counted" "$escalated" "$keys" >> "$NOW"
  done
done
cat "$NOW"

# input and the first five candidates: the device runner lists five
shown() { cut -f1,3-7 "$1"; }
# <jvm shown> <device shown> <jvm out> <device out>: a row whose device side has more of the built-in date, time and
# weekday phrases (CustomPhrases.BUILT_IN, of the clock, which ime-eval does not add) is taken less them on both sides,
# the JVM's first cut by as many as the app added; other rows, a word of that shape both list among them, are held whole
unclocked() {
  awk -F'\t' -v jf="$1" -v jvm="$3" -v dev="$4" '
    function clock(c, d, n) {
      d = "(〇|零|一|二|三|四|五|六|七|八|九|十)+"; n = "(日|一|二|三|四|五|六)"
      return c ~ /^[0-9]+:[0-9][0-9]$/ || c ~ /^[0-9]+时[0-9][0-9]分$/ || c ~ /^[0-9]+年[0-9]+月[0-9]+日$/ ||
        c ~ /^[0-9]+-[0-9][0-9]-[0-9][0-9]$/ || c ~ ("^" d "年" d "月" d "日$") || c ~ ("^(星期|周)" n "$")
    }
    # the first <first> candidates of <line> less the clock ones into out[1..], the input into out[0]; their count
    function kept(line, out, first,   f, n, i, k) {
      n = split(line, f, "\t"); out[0] = f[1]; k = 0; clocked = 0
      for (i = 2; i <= n && i <= first + 1; i++) if (clock(f[i])) clocked++; else out[++k] = f[i]
      return k
    }
    function row(out, n,   s, i) { s = out[0]; for (i = 1; i <= n; i++) s = s "\t" out[i]; return s }
    BEGIN { while ((getline l < jf) > 0) j[++rows] = l; printf "" > jvm; printf "" > dev }
    NR > rows { print > dev; next }
    {
      b = kept($0, D, 5); added = clocked; kept(j[NR], J, 5); added -= clocked
      if (added <= 0) { print j[NR] > jvm; print > dev; next }
      # the device lists five: what the app added pushed as many of the JVM candidates off its end
      print row(J, kept(j[NR], J, 5 - added)) > jvm; print row(D, b) > dev
    }
    END { for (r = NR + 1; r <= rows; r++) print j[r] > jvm }' "$2"
}
if [ "$DEVICE" = 1 ]; then
  install=${INSTALL:-1} differs=0
  for run in "${RUNS[@]}"; do
    IFS=$'\t' read -r name set _ device <<< "$run"
    read -r ime vars <<< "$device"
    for tier in low medium high; do
      # shellcheck disable=SC2086
      got=$(env INSTALL="$install" LEVEL="$tier" $vars "$HERE/run-on-device.sh" "$set" "$ime" "$WORK/device" | tail -n 1) || { echo "$name $tier: the device run failed" >&2; exit 2; }
      install=0
      shown "$WORK/$name-$tier.tsv" > "$WORK/$name-$tier.jvm.tsv"
      shown "$got" > "$WORK/$name-$tier.got.tsv"
      unclocked "$WORK/$name-$tier.jvm.tsv" "$WORK/$name-$tier.got.tsv" "$WORK/$name-$tier.shown.tsv" "$WORK/$name-$tier.device.tsv" || exit 2
      if ! diff "$WORK/$name-$tier.shown.tsv" "$WORK/$name-$tier.device.tsv" > "$WORK/$name-$tier.diff"; then
        echo "$name $tier: the device shows $(grep -c '^>' "$WORK/$name-$tier.diff") rows otherwise than the JVM ($WORK/$name-$tier.diff)" >&2
        differs=1
      fi
    done
  done
  [ "$differs" = 0 ] || exit 4
  echo "the device as the JVM"
fi

if [ "$REBASE" = 0 ]; then
  if diff <(grep -v '^sha256' "$BASELINE" | grep -v '^#') <(grep -v '^sha256' "$NOW") >&2; then echo "as the baseline"; exit 0; fi
  echo "not as the baseline: if meant, --rebaseline and say why in the commit" >&2
  exit 1
fi

# the hard conditions, in rows: a gate that never or always escalates, or whose large model does nothing, fails one
awk -F'\t' '$1 != "sha256" && $1 != "set" { k = $1; top1[k, $2] = $4; rows[k] = $3; if ($2 == "high") { esc[k] = $6; keys[k] = $7 } }
  END {
    bad = 0
    for (k in rows) {
      rate = esc[k] / keys[k]
      lo = (k == "t9") ? 0.005 : 0.25; hi = (k == "t9") ? 0.10 : 0.55
      if ((k == "pinyin" || k == "xiaohe" || k == "t9") && (rate < lo || rate > hi)) { print k ": escalated " rate " of keys, not in " lo "–" hi; bad = 1 }
      if ((k == "pinyin" || k == "xiaohe") && top1[k, "high"] <= top1[k, "medium"]) { print k ": high not ahead of medium"; bad = 1 }
      if (top1[k, "high"] < top1[k, "medium"] - 0.002 * rows[k]) { print k ": high more than 0.2 points behind medium"; bad = 1 }
      if (top1[k, "medium"] < top1[k, "low"]) { print k ": medium behind low"; bad = 1 }
    }
    exit bad
  }' "$NOW" >&2 || { echo "no baseline written" >&2; exit 1; }
{ echo "# tier-guard.sh --rebaseline: rows of each set at each tier, the keys the high gate escalated, on this data"; cat "$NOW"; } > "$BASELINE"
echo "baseline written: $BASELINE"
