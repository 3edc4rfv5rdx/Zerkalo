#!/usr/bin/env bash
#
# Install the release build on the emulator.
#
cd "$(dirname "$0")" || exit 1
. ./98-common

# The emulator is x86_64 — take that split, fall back to universal, then to
# whatever release APK is there.
apk=$(pick_apk "$RELEASE_APK_DIR" '*x86_64*.apk' '*universal*.apk' '*.apk') || {
    echo "No release APK found in $RELEASE_APK_DIR"
    exit 1
}

# Nothing to work on is not a failure: 00-MakeAll.sh reads 3 as "no emulator
# running" and carries on, while anything else non-zero ends its run.
if ! emulator_running; then
    echo "Emulator $EMULATOR_SERIAL is not running"
    exit 3
fi

install_apk "$EMULATOR_SERIAL" "$apk"
# Kept across the pause: the script used to end on sleep and report its exit
# code, so a failed install still read as success.
status=$?

pause
exit $status
