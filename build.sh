#!/usr/bin/env bash
# Compiles the translator into build/classes, with nothing but a JDK.
#
# Everything it compiles is source code kept in this repository:
#   Kotlin2Java.java   the translator
#   generated/         the parser ANTLR wrote from Kotlin.g4
#   antlr-runtime/     the source of the ANTLR runtime that parser uses
# Delete generated/ or antlr-runtime/ (say, to get them fresh from the ANTLR project
# instead of trusting the copies here) and this script puts them back: it downloads
# what can only come from the internet, checks its SHA-256, and generates the rest.
# It never deletes a file to do it: a download that fails or does not match stops
# the build and leaves everything as it was.
# After editing Kotlin.g4, delete generated/ so that it is made again.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$ROOT/env.sh"

RUNTIME="$ROOT/antlr-runtime"
GENERATED="$ROOT/generated"
PARSER_FILES="KotlinLexer.java KotlinParser.java KotlinVisitor.java KotlinBaseVisitor.java"
DOWNLOADS="$ROOT/build/downloads"
mkdir -p "$DOWNLOADS"

echo "== jdk: $JAVA_BIN"

# --- 1. the ANTLR runtime, as source ----------------------------------------------
echo "== antlr-runtime/"
if [ -d "$RUNTIME/org/antlr/v4/runtime" ]; then
    echo "   present, using the copy in the repository"
    echo "   sha256: not checked, there is no official value for the unpacked copy"
    echo "     (delete antlr-runtime/ to download it again and check it)"
elif [ -e "$RUNTIME/org" ]; then
    echo "   incomplete: antlr-runtime/org is there but not the runtime in it." >&2
    echo "   Nothing was changed; delete the whole antlr-runtime/ to fetch it again." >&2
    exit 1
else
    echo "   missing: fetching the official source of ANTLR $ANTLR_VERSION"
    jar_file="$DOWNLOADS/$ANTLR_RUNTIME_SRC_NAME"
    fetch "$ANTLR_RUNTIME_SRC_URL" "$ANTLR_RUNTIME_SRC_SHA256" "$jar_file" || exit 1
    license="$DOWNLOADS/LICENSE.txt"
    fetch "$ANTLR_LICENSE_URL" "$ANTLR_LICENSE_SHA256" "$license" || exit 1
    # only the Java sources (org/...), not the build metadata the jar also carries
    rm -rf "$DOWNLOADS/runtime.next"
    mkdir -p "$DOWNLOADS/runtime.next"
    ( cd "$DOWNLOADS/runtime.next" && "$JAVA_BIN/jar" xf "$jar_file" org )
    mkdir -p "$RUNTIME"
    mv "$DOWNLOADS/runtime.next/org" "$RUNTIME/org"
    [ -e "$RUNTIME/LICENSE.txt" ] || cp "$license" "$RUNTIME/LICENSE.txt"
    rm -rf "$DOWNLOADS/runtime.next"
    echo "   ok: $(find "$RUNTIME/org" -name '*.java' | wc -l | tr -d ' ') files in antlr-runtime/"
fi

# --- 2. the parser generated from Kotlin.g4 ---------------------------------------
echo "== generated/ (the parser ANTLR writes from Kotlin.g4)"
regen=""
present=""
for f in $PARSER_FILES; do
    if [ -f "$GENERATED/$f" ]; then present="$present $f"; else regen="$f is missing"; fi
done
if [ -n "$regen" ] && [ -n "$present" ]; then
    echo "   incomplete: $regen, but these are there:$present" >&2
    echo "   Nothing was changed; delete the whole generated/ to make it again." >&2
    exit 1
fi

if [ -z "$regen" ]; then
    echo "   present, using the copy in the repository"
    echo "   sha256: not checked, ANTLR publishes no value for files made from Kotlin.g4"
    echo "     (delete generated/ to make it again, as after editing Kotlin.g4)"
else
    echo "   regenerating ($regen), this needs the ANTLR $ANTLR_VERSION tool"
    if tool="$(find_antlr_tool)"; then
        # a copy this machine already has: checked, never replaced or deleted
        echo "   found $tool"
        if ! check_sha256 "$tool" "$ANTLR_TOOL_SHA256"; then
            echo "     it was left untouched. Replace it with the official" >&2
            echo "     $ANTLR_TOOL_NAME, or set ANTLR_JAR to one." >&2
            exit 1
        fi
    else
        echo "   not on this machine"
        tool="$ROOT/$ANTLR_TOOL_NAME"
        fetch "$ANTLR_TOOL_URL" "$ANTLR_TOOL_SHA256" "$tool" || exit 1
    fi
    # Run from the repository with a relative path: ANTLR writes the path it was
    # given into each file's first line, and an absolute one would put this
    # machine's directories in the repository.
    rm -rf "$ROOT/build/gen.next"
    ( cd "$ROOT" && "$JAVA_BIN/java" -jar "$tool" -visitor -no-listener \
          -o build/gen.next Kotlin.g4 )
    mkdir -p "$GENERATED"
    for f in $PARSER_FILES; do
        mv "$ROOT/build/gen.next/$f" "$GENERATED/$f"
    done
    rm -rf "$ROOT/build/gen.next"
    echo "   ok: $PARSER_FILES"
fi

# --- 3. compile -------------------------------------------------------------------
CLASSES="$ROOT/build/classes"
# Compile into a staging directory and swap it in at the end: wiping the classes in
# place breaks any test run that happens to be using them.
STAGE="$ROOT/build/classes.next"
rm -rf "$STAGE"
mkdir -p "$STAGE"

echo "== javac: Kotlin2Java.java + generated/ + antlr-runtime/ -> build/classes"
# The list goes in a file: the runtime alone is ~170 files, too many for some shells.
SOURCES="$ROOT/build/sources.txt"
{
    find "$RUNTIME/org" -name '*.java'
    for f in $PARSER_FILES; do echo "$GENERATED/$f"; done
    echo "$ROOT/Kotlin2Java.java"
} | sed 's/.*/"&"/' > "$SOURCES"
# -XDsuppressNotes: the runtime uses a deprecated API, and the note says nothing
# about this project
# --release 17: the classes run on any Java from 17 up, whichever JDK built them;
# build/ is shared by build.sh and build.bat on the same checkout.
"$JAVA_BIN/javac" -nowarn -XDsuppressNotes --release 17 -encoding UTF-8 -d "$STAGE" "@$SOURCES"

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

echo "ok. To translate:  ./kotlin2java.sh input.kt Output.java"
