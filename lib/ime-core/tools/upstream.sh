#!/bin/sh
# Prints the directory of an upstream fcitx source tree at the commit the app's host was ported
# from, downloaded once from GitHub and checked by SHA-256, so the golden generators run without
# the vendored native trees (deleted before the release).
#
#   upstream.sh fcitx5|chinese-addons
#
# Cache: $YOUMO_UPSTREAM_CACHE, default ~/.cache/youmo-upstream.
set -eu
case ${1:?usage: upstream.sh fcitx5|chinese-addons} in
fcitx5)
    repo=fcitx/fcitx5 commit=442edbc9b1880c766ff9cc9a9ac67e864b200595
    sha=b984ef5b526603311e5e9a67786c662b355e8ce4dc78c0a2f7f603c8d25962d5 ;;
chinese-addons)
    repo=fcitx/fcitx5-chinese-addons commit=0d3fd0408d03abc63cdb700559f85a387b98735f
    sha=da22c432805d1581ec013448ac54469e47c55054928106d00add3e2c43052027 ;;
*) echo "unknown tree: $1" >&2; exit 2 ;;
esac
cache=${YOUMO_UPSTREAM_CACHE:-$HOME/.cache/youmo-upstream}
dir=$cache/$1-$commit
if [ ! -f "$dir/.ok" ]; then
    # downloaded and unpacked in a directory of this run's own, then moved into place whole: parallel
    # worktrees' builds share the cache, and one must never read a tree another is still writing
    mkdir -p "$cache"
    tmp=$(mktemp -d "$cache/.$1-XXXXXX")
    trap 'rm -rf "$tmp"' EXIT
    curl -sSfL -o "$tmp/tree.tar.gz" "https://codeload.github.com/$repo/tar.gz/$commit"
    got=$(sha256sum "$tmp/tree.tar.gz" | cut -d' ' -f1)
    if [ "$got" != "$sha" ]; then
        echo "$repo@$commit: SHA-256 $got, expected $sha" >&2
        exit 1
    fi
    mkdir "$tmp/tree"
    tar xzf "$tmp/tree.tar.gz" -C "$tmp/tree" --strip-components=1
    touch "$tmp/tree/.ok"
    # a run that got there first has won (mv onto its tree fails); a tree still without .ok after
    # that is an older script's unfinished one, moved aside into $tmp (not rm -rf in place: two runs doing
    # that could delete the tree the other just put there), then replaced
    # (the move aside may fail, under set -e too: a run beside this one moved it first, or finished it)
    mv -T "$tmp/tree" "$dir" 2>/dev/null || [ -f "$dir/.ok" ] ||
        { [ -f "$dir/.ok" ] || mv -T "$dir" "$tmp/stale" 2>/dev/null || :; mv -T "$tmp/tree" "$dir" 2>/dev/null || [ -f "$dir/.ok" ]; }
fi
echo "$dir"
