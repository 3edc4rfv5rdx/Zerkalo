#!/usr/bin/env bash
#
# Build the signed release APKs and raise the build number.
#
# The major.minor line moves by itself when the changelog has an N entry waiting
# since the last tag: N is a feature, everything else is a fix, a tweak or
# plumbing, and that decision was already made when the entry was written. There
# is nothing to pass and nothing to remember. version_bump in 98-common holds
# the rule.
#
# Installing is a separate step: 11-EmulRELEASE.sh, 12-PhoneRELEASE.sh
#
set -e
cd "$(dirname "$0")" || exit 1
. ./98-common

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    show_help 12
    exit 0
fi

version_bump

./gradlew assembleRelease

echo
echo "Release APKs: $RELEASE_APK_DIR/"
ls -1 "$RELEASE_APK_DIR"/*.apk 2>/dev/null

echo
fold_build_number

pause
