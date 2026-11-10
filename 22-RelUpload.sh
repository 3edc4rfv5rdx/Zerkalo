#!/usr/bin/env bash
#
# Create the GitHub release for the newest tag and upload the APKs to it.
#
# The release notes are the changelog section of that tag, with the legend line
# from the top of the file in front of them, so the letters in the notes mean
# something to whoever reads them there.
#
set -e
cd "$(dirname "$0")" || exit 1
. ./98-common

NOTES_FILE=$(mktemp "/tmp/${PROJECT}-release-notes.XXXXXX.md")
trap 'rm -f "$NOTES_FILE" "${NOTES_FILE}.tmp"' EXIT

DRY_RUN=false
# ------------------------------------------------------------
# Dry-run mode: show what would happen without touching GitHub
# ------------------------------------------------------------
#DRY_RUN=true

# ------------------------------------------------------------
# Upload protection
# ------------------------------------------------------------
UPLOAD_TIMEOUT=180     # seconds per attempt
UPLOAD_RETRY=2         # number of attempts

echo "=== Detecting latest tag ==="
mapfile -t TAGS < <(git tag --list 'v*' | sort -V)
(( ${#TAGS[@]} > 0 )) || die "No tags found. Run ./20-MakeTag.sh first."

TAG="${TAGS[-1]}"
PREV_TAG=""
(( ${#TAGS[@]} > 1 )) && PREV_TAG="${TAGS[-2]}"

echo "Tag: $TAG"
echo "Prev: ${PREV_TAG:-(none)}"

# ------------------------------------------------------------
# Parse tag: v0.4.190  ->  VERSION=0.4.190
# Matched whole rather than trusted: a tag from either older spelling — the
# +build one or the <date>-<build> one — stops here instead of parsing into
# artifact names that were never built.
# ------------------------------------------------------------
CLEAN_TAG="${TAG#v}"
if [[ "$CLEAN_TAG" =~ ^([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
    VERSION="${BASH_REMATCH[1]}"
else
    die "Failed to parse tag: $TAG (expected v<major>.<minor>.<build>)"
fi

echo "Version: $VERSION"

APK_PREFIX="${PROJECT}-${VERSION}"

# ------------------------------------------------------------
# Release notes out of CHANGELOG.md
# Everything under the section of this tag, stopping at the previous tag's
# section — or, when there is no previous tag, at the next heading.
# ------------------------------------------------------------
echo "=== Building release notes from $CHANGELOG_FILE ==="

grep -qE "^## ${TAG}( |$)" "$CHANGELOG_FILE" || die "$CHANGELOG_FILE has no section for $TAG."

# The legend, so the letters in the notes mean something to whoever reads them
# on the release page. It is a "> " quote at the top of the file and goes out as
# one, which is what GitHub renders it as. A "#>" from the older spelling is
# taken too, with the hash dropped, so a changelog not yet converted still
# carries its legend into the notes.
LEGEND_LINE=$(grep -m 1 -E '^#?> *N=' "$CHANGELOG_FILE" | sed 's/^#//' || true)

awk -v cur="## ${TAG}" -v stop="${PREV_TAG:+## ${PREV_TAG}}" '
    $0 == cur || index($0, cur " ") == 1 { capture=1; next }
    capture && stop != "" && ($0 == stop || index($0, stop " ") == 1) { exit }
    capture && stop == "" && /^## / { exit }
    capture { print }
' "$CHANGELOG_FILE" > "$NOTES_FILE"

[ -s "$NOTES_FILE" ] || die "No notes found between $TAG and ${PREV_TAG:-end of file}."

if [[ -n "$LEGEND_LINE" ]]; then
    {
        echo "$LEGEND_LINE"
        echo
        cat "$NOTES_FILE"
    } > "${NOTES_FILE}.tmp"
    mv "${NOTES_FILE}.tmp" "$NOTES_FILE"
fi

echo "Generated notes:"
echo "--------------------------------------------------"
cat "$NOTES_FILE"
echo "--------------------------------------------------"

# ------------------------------------------------------------
# The APKs, one per ABI in LINK_ABIS
# ------------------------------------------------------------
echo "=== Checking APK files ==="

FILES=()
for abi in $LINK_ABIS; do
    src="$RELEASE_APK_DIR/${APK_PREFIX}-${abi}.apk"
    # The fallback is for a project whose Gradle rename spells the name a little
    # differently — never for a different build or a different ABI. It has to
    # carry this tag's version and build number, so a release can only ever be
    # made of the APKs that build produced; anything else fails below.
    if [[ ! -f "$src" ]]; then
        src=$(pick_apk "$RELEASE_APK_DIR" "*${VERSION}*${abi}.apk") || src=""
    fi
    if [[ -z "$src" || ! -f "$src" ]]; then
        echo "ERROR: no $abi APK for $TAG in $RELEASE_APK_DIR"
        echo "Available:"
        ls -1 "$RELEASE_APK_DIR"/*.apk 2>/dev/null || echo "(none)"
        exit 1
    fi
    echo "OK: $(basename "$src")"
    # The name it takes in the release, spelled out rather than taken from the
    # file: the fallback above may have found it under a different spelling, and
    # what lands on the release page is the one name every project here uses.
    FILES+=("$src#${PROJECT}-${VERSION}-${abi}.apk")
done

# ------------------------------------------------------------
# Create release if not exists
# ------------------------------------------------------------
echo "=== Checking if GitHub Release exists ==="

if $DRY_RUN; then
    echo "[DRY RUN] Would create release: $TAG"
    echo "[DRY RUN] Title: Release $TAG"
    echo "[DRY RUN] Notes file: $NOTES_FILE"
elif gh release view "$TAG" >/dev/null 2>&1; then
    echo "Release already exists."
else
    echo "Creating GitHub Release..."
    gh release create "$TAG" --title "Release $TAG" --notes-file "$NOTES_FILE"
fi

# ------------------------------------------------------------
# Upload helper with retry + timeout + cleanup
# ------------------------------------------------------------
upload_asset() { # upload_asset <tag> <src> <dst>
    local tag="$1" src="$2" dst="$3" i

    echo "--------------------------------------------------"
    echo "Uploading: $(basename "$src") -> $dst"

    if $DRY_RUN; then
        echo "[DRY RUN] Would upload: $(basename "$src") -> $dst"
        return 0
    fi

    for ((i = 1; i <= UPLOAD_RETRY; i++)); do
        echo "Attempt $i/$UPLOAD_RETRY..."

        # Remove a broken asset from a previous attempt (ignore errors).
        gh release delete-asset "$tag" "$dst" -y 2>/dev/null || true

        if timeout "$UPLOAD_TIMEOUT" gh release upload "$tag" "${src}#${dst}" --clobber; then
            echo "Upload OK: $dst"
            return 0
        fi

        echo "Upload failed or timed out, retrying in 5s..."
        sleep 5
    done

    echo "ERROR: Upload failed after $UPLOAD_RETRY attempts: $dst"
    return 1
}

echo "=== Uploading files to Release ==="
for pair in "${FILES[@]}"; do
    upload_asset "$TAG" "${pair%%#*}" "${pair##*#}"
done

echo "=== Release upload completed successfully ==="

# The temp notes file is removed by the EXIT trap.
pause
