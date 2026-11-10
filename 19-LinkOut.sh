#!/usr/bin/env bash
#
# Put the APKs of the newest release build into OUT/ as links under their own
# names, and sweep everything else out of that folder:
#
#   OUT/<project>-<version>-arm64-v8a.apk
#   OUT/<project>-<version>-universal.apk
#
# One place to copy a build from, instead of a path deep inside app/build/.
# Which ABIs are linked is LINK_ABIS in 99-project.conf. The x86_64 split is left
# out of it: that one only ever goes to the emulator, which is installed to from
# 11-EmulRELEASE.sh and never carried anywhere by hand.
#
# The links are hard ones: the entry here is the file itself, so copying it
# elsewhere copies a build and not a dangling path, and a gradle clean leaves it
# whole. The version ends in the build number, so the listing says which build
# it is. Nothing is built here: 00-MakeAll.sh runs this after a build, and on
# its own it picks up a build that already exists.
#
cd "$(dirname "$0")" || exit 1
. ./98-common

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    show_help 19
    exit 0
fi

MISSING=""
# An array, not a string of names, so a name with a space in it cannot turn the
# membership test below into a match on halves of two different names.
LINKED=()

link_latest() { # link_latest <glob>
    local newest name
    newest=$(pick_apk "$RELEASE_APK_DIR" "$1") || {
        echo ">>> nothing matching $1 to link into OUT"
        MISSING="yes"
        return 0
    }
    name=$(basename "$newest")
    mkdir -p "$OUT_DIR"
    ln -f "$newest" "$OUT_DIR/$name"
    LINKED+=("$name")
    echo "$OUT_DIR/$name"
}

# Nothing to link means nothing to keep, and the sweep below would empty OUT/ of
# a good build. A conf that names no ABI is a mistake, not an instruction.
[ -n "${LINK_ABIS// /}" ] || die "LINK_ABIS is empty in 99-project.conf"

for abi in $LINK_ABIS; do
    link_latest "*${abi}*.apk"
done

# Everything else goes: the previous build's names, an ABI no longer built, a
# copy left behind. Only files and links — a directory somebody made here is not
# ours to remove.
#
# And only when every artifact was linked. A run that could not find one of them
# would otherwise sweep anyway and delete the previous good build, leaving OUT/
# with half a release.
if [ -z "$MISSING" ] && [ -d "$OUT_DIR" ]; then
    for entry in "$OUT_DIR"/*; do
        [ -d "$entry" ] && continue
        [ -e "$entry" ] || [ -L "$entry" ] || continue
        name=$(basename "$entry")
        keep=""
        for linked in ${LINKED+"${LINKED[@]}"}; do
            [ "$name" = "$linked" ] && { keep=yes; break; }
        done
        [ -n "$keep" ] && continue
        rm -f "$entry"
    done
fi

if [ -n "$MISSING" ]; then
    echo ">>> incomplete set: $OUT_DIR left as it was"
    exit 1
fi
exit 0
