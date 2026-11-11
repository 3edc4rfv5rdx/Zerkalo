#!/usr/bin/env bash
#
# Pushes the current branch to GitHub.
#
set -e
cd "$(dirname "$0")" || exit 1

git push -u origin HEAD
