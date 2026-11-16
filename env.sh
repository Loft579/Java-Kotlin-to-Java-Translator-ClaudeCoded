#!/usr/bin/env bash
# Shared by build.sh, tests.sh, run_tests.sh and k2j.sh: finds the JDK and
# exports JAVA_BIN, and defines what build.sh needs to fetch the ANTLR parts it is
# missing. Sourcing it downloads nothing; build.sh decides when that is needed.
#
# The JDK is taken from $JAVA_HOME, then the PATH, then ~/.local/jvm, /usr/lib/jvm
# and /opt/java:   JAVA_HOME=/path/to/jdk ./build.sh

# --- the ANTLR parts this repository is built from --------------------------------
# Both come straight from the ANTLR project. Their SHA-256 values were checked when
# they were pinned: antlr.org, Maven Central (its .sha1) and an existing copy agreed.
ANTLR_VERSION="4.13.2"
# The tool: only needed to regenerate generated/ from Kotlin.g4. Compiled code, but
# it never runs when translating; what it writes is plain Java in generated/.
ANTLR_TOOL_NAME="antlr-$ANTLR_VERSION-complete.jar"
ANTLR_TOOL_URL="https://www.antlr.org/download/$ANTLR_TOOL_NAME"
ANTLR_TOOL_SHA256="eae2dfa119a64327444672aff63e9ec35a20180dc5b8090b7a6ab85125df4d76"
# The runtime, as SOURCE code: it is compiled here with the rest of the translator.
ANTLR_RUNTIME_SRC_NAME="antlr4-runtime-$ANTLR_VERSION-sources.jar"
ANTLR_RUNTIME_SRC_URL="https://repo1.maven.org/maven2/org/antlr/antlr4-runtime/$ANTLR_VERSION/$ANTLR_RUNTIME_SRC_NAME"
ANTLR_RUNTIME_SRC_SHA256="4100b1df2f38152d8502e89195b94654d9aa22f5b6c47aca7b6a7f3194b5fbb0"
# Its BSD license, which says it must travel with the copy kept in antlr-runtime/.
ANTLR_LICENSE_URL="https://raw.githubusercontent.com/antlr/antlr4/$ANTLR_VERSION/LICENSE.txt"
ANTLR_LICENSE_SHA256="3db1fb3ee79a4b4f9918fc4d0f6133bf18a3cf787f126cd22f8aa9b862281c0c"

find_java_bin() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        echo "$JAVA_HOME/bin"; return 0
    fi
    if command -v javac >/dev/null 2>&1; then
        dirname "$(command -v javac)"; return 0
    fi
    local d
    for d in "$HOME"/.local/jvm/*/bin /usr/lib/jvm/*/bin /opt/java/*/bin; do
        [ -x "$d/javac" ] && { echo "$d"; return 0; }
    done
    return 1
}

# The ANTLR tool, if this machine already has it: $ANTLR_JAR, then this directory,
# then the usual places. Only the exact version is accepted, because the code it
# generates has to match the runtime in antlr-runtime/.
find_antlr_tool() {
    if [ -n "${ANTLR_JAR:-}" ] && [ -f "$ANTLR_JAR" ]; then
        echo "$ANTLR_JAR"; return 0
    fi
    local j
    for j in "$ROOT/$ANTLR_TOOL_NAME" "$HOME/.local/lib/$ANTLR_TOOL_NAME" \
             "/usr/local/lib/$ANTLR_TOOL_NAME" "/usr/share/java/$ANTLR_TOOL_NAME" \
             "/opt/$ANTLR_TOOL_NAME"; do
        [ -f "$j" ] && { echo "$j"; return 0; }
    done
    return 1
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1          # macOS
    fi
}

# Prints the comparison and fails on a mismatch:  check_sha256 FILE EXPECTED
check_sha256() {
    local got
    got="$(sha256_of "$1")"
    echo "   sha256 of $1"
    echo "     expected: $2"
    echo "     got:      $got"
    if [ "$got" = "$2" ]; then
        echo "     -> MATCH"
        return 0
    fi
    echo "     -> MISMATCH: this is not the official file" >&2
    return 1
}

# download URL FILE, retrying when the connection fails or the server errs
download() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --connect-timeout 20 --retry 3 --retry-delay 2 --retry-connrefused \
            -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --tries=3 --timeout=20 -O "$2" "$1"
    else
        echo "     neither curl nor wget is installed" >&2
        return 1
    fi
}

# fetch URL SHA256 DEST
# Makes DEST the official file, and never deletes anything to do it:
# - DEST already there: it is used if its SHA-256 matches, else the build stops
#   and the file is left as it is.
# - otherwise it is downloaded to DEST.part, and only moved to DEST if its
#   SHA-256 matches. A failed or wrong download never ends up where it is used.
fetch() {
    local url="$1" want="$2" dest="$3"
    if [ -f "$dest" ]; then
        echo "   found $dest"
        check_sha256 "$dest" "$want" && return 0
        echo "     it was left untouched. Replace it with the official file from" >&2
        echo "     $url" >&2
        echo "     or move it away so that it is downloaded again." >&2
        return 1
    fi
    local part="$dest.part"
    echo "   downloading $url"
    if ! download "$url" "$part"; then
        echo "   sha256 of $part" >&2
        echo "     expected: $want" >&2
        echo "     got:      (nothing: the download failed)" >&2
        echo "     -> NOT CHECKED" >&2
        echo "     -> FAILED: could not download it (no connection, or the server is" >&2
        echo "        down). Nothing was changed: try again later, or download it" >&2
        echo "        yourself and put it at $dest" >&2
        return 1
    fi
    if ! check_sha256 "$part" "$want"; then
        echo "     nothing was changed; the download was kept, unused, at $part" >&2
        return 1
    fi
    mv "$part" "$dest"
}

JAVA_BIN="$(find_java_bin)" || {
    echo "No JDK found. Install one (apt install openjdk-17-jdk) or set JAVA_HOME." >&2
    exit 1
}
# The generated Java uses List.of, so a JDK older than 9 cannot compile the output.
JAVA_MAJOR="$("$JAVA_BIN/javac" -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')"
if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -lt 11 ]; then
    echo "warning: javac $JAVA_MAJOR found; 11 or newer is expected" >&2
fi
export JAVA_BIN
