#!/usr/bin/env bash
# One command for a parity round on a redroid (fcitx-replacement §7.2): this checkout's debug build
# (the Kotlin host, the only one since the native fcitx sources were deleted), the sequences run on
# it, the Kotlin host on the JVM, compared with each other and with the native host's side.
#
#   uat/parity/record.sh <sequences.jsonl> <out dir> (--port <P> | --containers <n>) [--sample <n>]
#                        [--seed <s>] [--ids a,b] [--epsilon <e>] [--no-build] [--no-jvm] [--resume]
#                        [--native-apk <apk> | --native-out <native.jsonl>]
# --resume: each output goes on from the sequences it has (a run killed, an OOM).
# The native side: --native-apk runs a debug APK built at the tag native-last (the last tree with the
# native host) beside this checkout's, from the same snapshot; --native-out compares with a recorded
# native run (dev/parity-runs/r3/final/native.jsonl) instead. Neither: Kotlin against the JVM only.
#
# The redroid: UAT_CONTAINERS=<P>:<name> for one of your own (uat.py up makes it if missing).
# --containers <n> runs n at once instead (youmo-parity-<i> on 5581.., the host's cores split
# between them): each its own snapshot, (native then) kotlin on its shard of the sequences chosen, so
# both builds of a sequence start from one state; the shards merged after.
# <out dir> gets kotlin.apk, kotlin.jsonl, jvm.jsonl, compare-*.txt (native.apk, native.jsonl with
# --native-apk).
# A sequence file of another engine (parity-check, Provenance) is still run and compared, the JVM
# comparison labelled "engine changed since this recording: differences may be intended".
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
seqs=$(realpath "$1"); out=$(realpath -m "$2"); shift 2
port="" resume="" containers=1 sample="" seed=0 ids="" epsilon=1e-4 build=1 jvm=1 native_apk="" native_out=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --port) port=$2; shift 2 ;;
        --containers) containers=$2; shift 2 ;;
        --sample) sample=$2; shift 2 ;;
        --seed) seed=$2; shift 2 ;;
        --ids) ids=$2; shift 2 ;;
        --epsilon) epsilon=$2; shift 2 ;;
        --no-build) build=0; shift ;;
        --no-jvm) jvm=0; shift ;;
        --resume) resume=1; shift ;;
        --native-apk) native_apk=$(realpath "$2"); shift 2 ;;
        --native-out) native_out=$(realpath "$2"); shift 2 ;;
        *) echo "unknown option $1" >&2; exit 2 ;;
    esac
done
[[ -n "$port" || $containers -gt 1 ]] || { echo "--port or --containers is required" >&2; exit 2; }
[[ -z "$native_apk" || -z "$native_out" ]] || { echo "--native-apk or --native-out, not both" >&2; exit 2; }
mkdir -p "$out"
hosts=(kotlin)
if [[ -n "$native_apk" ]]; then
    hosts=(native kotlin)
    [[ "$native_apk" == "$out/native.apk" ]] || cp "$native_apk" "$out/native.apk"
    native_out="$out/native.jsonl"
fi

# Gradle: this checkout's wrapper. On a box that runs several streams at once, PARITY_GRADLE names a
# wrapper script run instead (given YOUMO_REPO), PARITY_LOCK_DIR a directory whose locks serialise
# the builds, PARITY_MEMWAIT a command that waits until there is room for a Gradle, a replay or a
# redroid; all three optional.
memwait() { [[ -n "${PARITY_MEMWAIT:-}" ]] && $PARITY_MEMWAIT || true; }
locked() {
    local lock=$1; shift
    if [[ -n "${PARITY_LOCK_DIR:-}" ]]; then flock "$PARITY_LOCK_DIR/$lock" "$@"; else "$@"; fi
}
run_gradle() {
    if [[ -n "${PARITY_GRADLE:-}" ]]; then env YOUMO_REPO="$repo" bash "$PARITY_GRADLE" -q "$@"
    else "$repo/gradlew" -p "$repo" -q "$@"; fi
}
gradle() { memwait; locked youmo-slim-gradle.lock run_gradle "$@"; }
eval_cli() { memwait; locked youmo-core-gradle.lock run_gradle :lib:ime-eval:run --args="$*"; }

eval_cli parity-check "$seqs"

if [[ $build == 1 ]]; then
    gradle :app:assembleTextDebug 2>&1 | tail -5
    apk=$(ls -t "$repo"/app/build/outputs/apk/text/debug/*x86_64*.apk | grep -v unsigned | head -1)
    cp "$apk" "$out/kotlin.apk"
fi

sel=()
[[ -n "$sample" ]] && sel+=(--sample "$sample" --seed "$seed")
[[ -n "$ids" ]] && sel+=(--ids "$ids")

# one container's part: up, its snapshot, each build on shard [k/n] (none for one), down. A subshell,
# so its EXIT trap takes the redroid down however the part ends (a failed run under set -e too).
device() (
    local p=$1 shard=$2 tag=$3
    local part=()
    [[ -n "$shard" ]] && part=(--shard "$shard")
    memwait
    python3 "$repo/uat/tools/uat.py" up --port "$p"
    trap 'python3 "$repo/uat/tools/uat.py" down --port "$p"' EXIT
    python3 "$here/parity.py" prepare --port "$p" --apk "$out/${hosts[0]}.apk"
    for host in "${hosts[@]}"; do
        python3 "$here/parity.py" run --port "$p" --apk "$out/$host.apk" --host $host "${sel[@]}" "${part[@]}" ${resume:+--resume} "$seqs" "$out/$host$tag.jsonl"
    done
)

if [[ $containers -gt 1 ]]; then
    cores=$(nproc); per=$(( cores / containers )); list=""; pids=()
    for ((i = 0; i < containers; i++)); do list+="$((5581 + i)):youmo-parity-$((i + 1)),"; done
    export UAT_CONTAINERS=${list%,}
    for ((i = 0; i < containers; i++)); do
        UAT_CPUSET="$((i * per))-$((i * per + per - 1))" device $((5581 + i)) "$i/$containers" "-$i" > "$out/device-$i.log" 2>&1 &
        pids+=($!)
    done
    # a bare wait returns 0 whatever the shards did: a failed or cut-short shard would be merged and
    # show only as MISSING sequences
    failed=0
    for ((i = 0; i < containers; i++)); do
        wait "${pids[$i]}" || { echo "shard $i failed: $out/device-$i.log" >&2; failed=1; }
    done
    [[ $failed == 0 ]] || exit 1
    for host in "${hosts[@]}"; do
        python3 "$here/parity.py" merge "$out/$host.jsonl" $(for ((i = 0; i < containers; i++)); do echo "$out/$host-$i.jsonl"; done)
    done
else
    device "$port" "" ""
fi

# compare into its file, then show the head: `| tee | head` would cut the file at 40 lines (tee dies
# of SIGPIPE) and take the status from that. The worst code wins; parity-compare's own 3 (engine
# changed since the recording) arrives as Gradle's 1, since :lib:ime-eval:run does not pass exit codes on.
status=0
compare() {
    local file=$1; shift
    local code=0
    eval_cli parity-compare "$@" > "$file" || code=$?
    head -40 "$file"
    (( code > status )) && status=$code
    return 0
}

# device against device: every step and every call the same (the native side run here or recorded)
if [[ -n "$native_out" ]]; then
    compare "$out/compare-device.txt" "$seqs" "$native_out" "$out/kotlin.jsonl"
fi

if [[ $jvm == 1 ]]; then
    # a device sample: the JVM replays those sequences, not the whole file (10k is hours)
    gradle :lib:ime-eval:parityReplay --args="$seqs $out/jvm.jsonl all $out/kotlin.jsonl"
    model="$repo/app/build/generated/engine-models/engine/sentence-model-large.safetensors"
    for host in kotlin ${native_out:+native}; do
        dev="$out/$host.jsonl"; [[ $host == native ]] && dev=$native_out
        compare "$out/compare-jvm-$host.txt" "$seqs" "$dev" "$out/jvm.jsonl" --jvm --model "$model" --epsilon "$epsilon"
    done
fi
exit $status
