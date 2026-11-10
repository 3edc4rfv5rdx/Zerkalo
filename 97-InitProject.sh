#!/usr/bin/env bash
#
# Turn this template into the build set of a real project, named after the
# folder it is sitting in:
#
#   cp -r /home/e/PRJ/XXX/. /home/e/PRJ/MyThing/
#   cd /home/e/PRJ/MyThing && bash 97-InitProject.sh
#
# MyThing -> PROJECT="mything", which is what the release APKs are called. Pass a
# name to override the folder:  bash 97-InitProject.sh otherName
#
# It writes the name into 99-project.conf, into the Gradle snippet, and into the
# icon master's path, then says what is left to do by hand. Run once, by hand:
# no execute bit, and a second run over a project that already has a name stops
# unless you pass --force.
#
set -e
cd "$(dirname "$0")" || exit 1

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    sed -n '2,16p' "$0"
    exit 0
fi

FORCE=""
[ "${1:-}" = "--force" ] && { FORCE=yes; shift; }

CONF="99-project.conf"
WIRING="ADD/gradle-version-wiring.kts"
TEMPLATE_NAME="xxx"

[ -f "$CONF" ] || { echo "No $CONF here — is this a copy of the template?" >&2; exit 1; }

# The name: what was passed, or the folder this sits in. Lowercased and stripped
# of everything a file name should not carry, because it goes into APK names and
# into the tag the release goes out as.
RAW_NAME="${1:-$(basename "$PWD")}"
PROJECT_NAME=$(printf '%s' "$RAW_NAME" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9._-')

if [ -z "$PROJECT_NAME" ]; then
    echo "Nothing usable as a name in \"$RAW_NAME\". Pass one: bash $0 <name>" >&2
    exit 1
fi

if [ "$PROJECT_NAME" = "$TEMPLATE_NAME" ]; then
    echo "This would name the project \"$TEMPLATE_NAME\", which is the template's own name." >&2
    echo "Copy the folder into the project's directory first, or pass a name." >&2
    exit 1
fi

# What the conf says now. Already named means already initialised.
CURRENT=$(sed -n 's/^PROJECT="\(.*\)"$/\1/p' "$CONF")
if [ "$CURRENT" = "$PROJECT_NAME" ]; then
    echo "$CONF already names this project \"$CURRENT\". Nothing to do."
    exit 0
fi
if [ "$CURRENT" != "$TEMPLATE_NAME" ] && [ -z "$FORCE" ]; then
    echo "$CONF names this project \"$CURRENT\", not \"$PROJECT_NAME\"."
    echo "Nothing done. Pass --force to rename it."
    exit 1
fi

echo "=== Naming this project \"$PROJECT_NAME\" ==="

# The conf: the name, and the icon master that is named after it.
sed -i \
    -e "s|^PROJECT=\".*\"$|PROJECT=\"$PROJECT_NAME\"|" \
    -e "s|^ICON_MASTER=\"ADD/images/.*\"$|ICON_MASTER=\"ADD/images/${PROJECT_NAME}.png\"|" \
    "$CONF"
echo "$CONF: PROJECT=\"$PROJECT_NAME\", ICON_MASTER=\"ADD/images/${PROJECT_NAME}.png\""

# The Gradle snippet: the prefix the APKs are renamed to. It is written twice
# there — once for the release task, once for the debug one — and both have to
# match PROJECT, or 22-RelUpload.sh will not find the files it is meant to
# upload.
if [ -f "$WIRING" ]; then
    sed -i "s|^    projectName.set(\".*\")$|    projectName.set(\"$PROJECT_NAME\")|" "$WIRING"
    echo "$WIRING: projectName.set(\"$PROJECT_NAME\")"
fi

# Things the scripts produce that no repository wants. Appended only when they
# are not already there, and only to a .gitignore that exists — creating one in a
# project that deliberately has none is not this script's business.
if [ -f .gitignore ]; then
    ADDED=""
    for pattern in 'OUT/' '*.apkx' 'key.properties'; do
        grep -qxF "$pattern" .gitignore || { printf '%s\n' "$pattern" >> .gitignore; ADDED="$ADDED $pattern"; }
    done
    [ -z "$ADDED" ] || echo ".gitignore:$ADDED"
fi

echo
echo "=== Left to do by hand ==="
echo "1. Paste the three pieces of $WIRING into $(sed -n 's/^APP_MODULE="\(.*\)"$/\1/p' "$CONF")/build.gradle.kts."
echo "2. Set ic_launcher_background in res/values/colors.xml to the blue in $CONF."
echo "3. bash 77-MakeMyKey.sh — unless ~/.my-safe/key.properties is already there."
echo "4. Check the rest of $CONF: the emulator serial, the ABIs, the branch."
echo
echo "Then:  ./00-MakeAll.sh"
