#!/usr/bin/env bash
# Builds a release and publishes it on GitHub, where the app's update button finds it.
# Needs the GitHub CLI (gh) signed in, and the same signing key as the installed app
# (~/.config/.android/debug.keystore on this computer). Without that key, phones can't update.
# Usage: tools/release.sh ["what changed"]
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="${UPDATE_REPO:-Normal99/grandma-phone}"
NOTES="${1:-Update}"
CODE=$(( ($(date +%s) - 1767225600) / 60 ))

systemd-run --user --scope -q -p MemoryMax=8G -p MemorySwapMax=0 \
    ./gradlew --no-daemon -q testDebugUnitTest assembleRelease -PversionCode="$CODE" -PupdateRepo="$REPO"

APK="build/faces-1.$CODE.apk"
mkdir -p build
cp app/build/outputs/apk/release/app-release.apk "$APK"
gh release create "v$CODE" "$APK" --repo "$REPO" --title "1.$CODE" --notes "$NOTES"
echo "Published 1.$CODE. Phones get it from Settings > App updates > Update now."
