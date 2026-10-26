# Upgrade fixtures

One directory per released version (v0.3.0, v0.3.1, v0.3.2): the app's files after a set of
non-default settings, a punctuation map, a custom phrase, a learned word and 繁体, plus that build's
backup zip. Release gates 2 and 3 of `dev/design/fcitx-replacement.md` §8 upgrade from these and
restore these backups. Each README says what was set and how long each step took.

Regenerate one (removes and remakes the container; the APK from `gh release download v<x> -p '*text-arm64*'`):

    UAT_BUILD=release python3 uat/fixtures/upgrade/make.py v0.3.2 <apk> <container> <port>
