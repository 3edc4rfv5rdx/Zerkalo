#!/usr/bin/env bash
#
# Stamp the changelog with the version this build went out as and create the tag
# locally. Pushing is a separate step: 21-PushTag.sh
#
# The Unreleased heading stays where it is and the new section goes right under
# it, so what has landed since is written above and the next build's version
# rule reads an empty Unreleased.
#
set -e
cd "$(dirname "$0")" || exit 1
. ./98-common

require_clean_tree

echo "=== Reading build info ==="
version_load
# The version ends in the build number, so a tag has nothing to add to it. The
# build date goes into the changelog heading below, for the reader alone.
TAG="v${version}"

echo "Version: $version"
echo "Build:   $build"
echo "Tag:     $TAG"

if git tag --list "$TAG" | grep -q "^${TAG}$"; then
    echo "Tag $TAG already exists. Nothing to do."
    exit 0
fi

[ -f "$CHANGELOG_FILE" ] || die "$CHANGELOG_FILE not found."

# The tag alone or the tag followed by the date: a section stamped before the
# date was written here must still count as this release's.
if grep -qE "^## ${TAG}( |$)" "$CHANGELOG_FILE"; then
    echo "Changelog already has a section for $TAG. Skipping update."
else
    echo "=== Inserting $TAG right after Unreleased ==="
    updated=$(mktemp "/tmp/${PROJECT}-changelog.XXXXXX.md")

    awk -v heading="## $TAG${build_date:+ ($build_date)}" '
        /^## Unreleased$/ && !done {
            print $0
            print heading
            done = 1
            next
        }
        { print }
    ' "$CHANGELOG_FILE" > "$updated"

    if ! grep -qE "^## ${TAG}( |$)" "$updated"; then
        rm -f "$updated"
        die "Failed to insert $TAG into $CHANGELOG_FILE — is there a '## Unreleased' line?"
    fi

    # Written back into the existing file rather than moved over it: mktemp makes
    # its file readable by the owner alone, and a move would quietly hand the
    # changelog those permissions.
    cat "$updated" > "$CHANGELOG_FILE"
    rm -f "$updated"

    echo "=== Committing changelog update ==="
    git add "$CHANGELOG_FILE"
    git commit -m "Add release notes for $TAG"
fi

echo "=== Creating tag $TAG ==="
git tag -a "$TAG" -m "Build $build"

echo "=== Done: tag $TAG created ==="

pause
