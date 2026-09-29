#!/usr/bin/env bash
# Generates the parser from Kotlin.g4 and compiles the translator into build/classes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$ROOT/env.sh"

echo "== tools: $JAVA_BIN/javac, $(basename "$ANTLR_JAR") (antlr $ANTLR_VERSION)"

GEN="$ROOT/build/gen"
CLASSES="$ROOT/build/classes"
# Compile into a staging directory and swap it in at the end: wiping the classes in
# place breaks any test run that happens to be using them.
STAGE="$ROOT/build/classes.next"
rm -rf "$GEN" "$STAGE"
mkdir -p "$GEN" "$STAGE"

echo "== antlr: Kotlin.g4 -> build/gen"
"$JAVA_BIN/java" -jar "$ANTLR_JAR" -visitor -no-listener -o "$GEN" "$ROOT/Kotlin.g4"

echo "== javac: translator -> build/classes"
# only the translator, never a stray .java that a test happened to leave here
"$JAVA_BIN/javac" -nowarn -encoding UTF-8 -cp "$ANTLR_JAR" -d "$STAGE" \
	"$GEN"/*.java "$ROOT/Kotlin2Java.java"

# The swap is two renames, and on a Windows drive either can fail because another
# process (OneDrive, an indexer, an antivirus) is holding the directory open. If the
# second one fails the previous classes go back where they were: a failed build must
# not leave the repository with no translator at all.
OLD="$ROOT/build/classes.old"
swap() {
    rm -rf "$OLD"
    if [ -d "$CLASSES" ] && ! mv "$CLASSES" "$OLD"; then
        return 1
    fi
    if ! mv "$STAGE" "$CLASSES"; then
        [ -d "$OLD" ] && mv "$OLD" "$CLASSES"
        return 1
    fi
    rm -rf "$OLD"
}

if ! swap; then
    sleep 1                          # whatever held it is usually done by now
    if ! swap; then
        echo "cannot replace $CLASSES: something is holding it open (OneDrive or an" >&2
        echo "indexer, if this is a Windows drive). The previous classes are intact;" >&2
        echo "close whatever is using build/ and run ./build.sh again." >&2
        exit 1
    fi
fi

echo "ok"
