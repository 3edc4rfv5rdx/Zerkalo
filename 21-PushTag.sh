#!/usr/bin/env bash
#
# Push the branch and the newest tag. Uploading the APKs to the release is a
# separate step: 22-RelUpload.sh
#
set -e
cd "$(dirname "$0")" || exit 1
. ./98-common

# ===== dry-run switch =====
#DRY="--dry-run"
DRY=""
# ==========================

require_clean_tree

echo "=== Detecting latest tag ==="
LAST_TAG=$(git tag --list 'v*' | sort -V | tail -n 1)
[ -n "$LAST_TAG" ] || die "No tags found. Run ./20-MakeTag.sh first."
echo "Latest tag: $LAST_TAG"

# The branch releases go out on is named in 99-project.conf, not read off HEAD:
# standing on a side branch when you meant to release is exactly the mistake
# this catches.
HEAD_BRANCH=$(git rev-parse --abbrev-ref HEAD)
[ "$HEAD_BRANCH" = "$GIT_BRANCH" ] ||
    die "On $HEAD_BRANCH, but releases go out on $GIT_BRANCH (99-project.conf)."

git fetch "$GIT_REMOTE" "$GIT_BRANCH" --quiet 2>/dev/null || true
LOCAL=$(git rev-parse HEAD)
REMOTE_HEAD=$(git rev-parse "$GIT_REMOTE/$GIT_BRANCH" 2>/dev/null || echo "")

if [[ "$LOCAL" == "$REMOTE_HEAD" ]]; then
    echo "Branch $GIT_BRANCH is up to date with $GIT_REMOTE."
else
    echo "=== Pushing branch $GIT_BRANCH ($DRY) ==="
    # Unquoted: $DRY is either --dry-run or nothing, and quoting nothing would
    # hand git an empty argument.
    # shellcheck disable=SC2086
    git push $DRY "$GIT_REMOTE" "$GIT_BRANCH"
fi

# An exact match: a substring test would find v0.2.20260826-4 in -45 and skip a
# tag that was never pushed.
if git ls-remote --tags "$GIT_REMOTE" "refs/tags/$LAST_TAG" | grep -q "refs/tags/${LAST_TAG}$"; then
    echo "Tag $LAST_TAG already exists on $GIT_REMOTE."
else
    echo "=== Pushing tag $LAST_TAG ($DRY) ==="
    # shellcheck disable=SC2086
    git push $DRY "$GIT_REMOTE" "$LAST_TAG"
fi

echo "=== Done ==="

pause
