#!/usr/bin/env bash
#
# Install the release build on the phone.
#
# The device is the one that is connected; pass a serial to choose:
#   ./12-PhoneRELEASE.sh <serial>
#
cd "$(dirname "$0")" || exit 1
. ./98-common

# The phone is arm64-v8a — take that split, fall back to universal, then to
# whatever release APK is there.
apk=$(pick_apk "$RELEASE_APK_DIR" '*arm64-v8a*.apk' '*universal*.apk' '*.apk') || {
    echo "No release APK found in $RELEASE_APK_DIR"
    exit 1
}

# Returns 3 when no device is connected: 00-MakeAll.sh reads that as "nothing to
# install on" and carries on, while anything else non-zero ends its run.
TEL=$(pick_device "${1:-}") || exit $?

install_apk "$TEL" "$apk"
status=$?

pause 3
exit $status
