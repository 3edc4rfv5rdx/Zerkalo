#!/usr/bin/env bash
#
# Bring the launcher icons up to date with the drawing they are cut from:
#
#   $ICON_MASTER  ->  app/src/main/res/**/$ICON_OUTPUTS
#
# $ICON_SCRIPT does the cutting; this only decides whether it has to run. The two
# sides are compared by modification time, and the generator runs only when
# something generated is older than the drawing — or missing. All three names are
# in 99-project.conf.
#
# The master usually lives under ADD/, which is git-ignored, so a fresh clone has
# no drawing at all. That is not a failure: the generated PNGs are committed, and
# without the master there is simply nothing to redo.
#
# Its own step, and deliberately not part of a build — 00-MakeAll.sh does not run
# it either. A build that regenerated icons would rewrite tracked files behind the
# build's back: the APK would carry icons no commit recorded, and 10-MakeRelease.sh
# would stop folding the version bump into the previous commit because the tree was
# dirty for a reason nobody asked for. Redraw the master and you run this yourself,
# then commit what it rewrote.
#
# What it rewrites is listed at the end; those files belong in a commit of their
# own. No execute bit, on purpose:  bash 02-MakeIcons.sh
#
set -e
cd "$(dirname "$0")" || exit 1
. ./98-common

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    show_help 25
    exit 0
fi

GENERATED_ROOT="$APP_MODULE/src/main/res"

if [ -z "${ICON_MASTER:-}" ]; then
    echo "99-project.conf names no ICON_MASTER; the icons here are drawn by hand."
    exit 0
fi

if [ ! -e "$ICON_MASTER" ]; then
    echo "No $ICON_MASTER here; the committed icons stay as they are."
    exit 3
fi

[ -f "$ICON_SCRIPT" ] || die "99-project.conf names $ICON_SCRIPT, which is not here"

# Whether anything generated is older than the drawing, or simply not there.
#
# By modification time, which is the only thing to compare without doing the work
# anyway. Git does not preserve mtimes, so a fresh clone can answer yes when the
# content is already right; that costs one regeneration which changes nothing, and
# the listing at the end says so.
needs_rebuild() {
    local find_args=() name generated file
    for name in $ICON_OUTPUTS; do
        [ ${#find_args[@]} -eq 0 ] || find_args+=(-o)
        find_args+=(-name "$name")
    done

    generated=$(find "$GENERATED_ROOT" \( "${find_args[@]}" \) -type f 2>/dev/null || true)
    if [ -z "$generated" ]; then
        echo "No generated icons under $GENERATED_ROOT"
        return 0
    fi

    while IFS= read -r file; do
        [ -n "$file" ] || continue
        if [ "$ICON_MASTER" -nt "$file" ]; then
            echo "Older than $ICON_MASTER: $file"
            return 0
        fi
    done <<STALE
$generated
STALE
    return 1
}

if ! needs_rebuild; then
    echo "The icons are newer than $ICON_MASTER; nothing to do."
    exit 0
fi

echo "=== Cutting the icons out of $ICON_MASTER ==="
# The plate colour goes to the generator in the environment rather than being
# written into it, so 99-project.conf stays the one place it is named.
export ICON_MASTER ICON_BG_COLOR
case "$ICON_SCRIPT" in
    *.py) python3 "$ICON_SCRIPT" ;;
    *)    bash "$ICON_SCRIPT" ;;
esac

# What this run rewrote, so it can be committed on purpose rather than swept into
# the next commit by accident. Only inside a git work tree: the script has to keep
# working in a copy that is not one.
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    CHANGED=$(git status --porcelain -- "$GENERATED_ROOT")
    echo
    if [ -z "$CHANGED" ]; then
        echo "Nothing changed: the icons already matched the drawing."
    else
        echo "Rewritten — commit these on their own:"
        printf '%s\n' "$CHANGED"
    fi
fi
