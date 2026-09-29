#!/usr/bin/env bash
# FINDS the tools, it does not install anything: it looks for an existing JDK and
# for the ANTLR jar, and exports JAVA_BIN and ANTLR_JAR for the other scripts.
# Sourced by build.sh, tests.sh and run_tests.sh so the search lives in one place.
# Both can be overridden from the environment:
#   JAVA_HOME=/path/to/jdk ANTLR_JAR=/path/to/antlr-complete.jar ./build.sh
#
# Search order for the JDK: $JAVA_HOME, then the PATH (a system install, which is
# the normal case), then ~/.local/jvm and /usr/lib/jvm.

ANTLR_URL="https://www.antlr.org/download/antlr-4.13.2-complete.jar"

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

# ANTLR_JAR wins, then a jar vendored in this directory (a deliberate pin). Beyond
# that the NEWEST version found wins rather than the first directory looked at, so a
# stale copy in one place cannot silently shadow a newer one somewhere else.
find_antlr_jar() {
    if [ -n "${ANTLR_JAR:-}" ] && [ -f "$ANTLR_JAR" ]; then
        echo "$ANTLR_JAR"; return 0
    fi
    local j
    for j in "$ROOT"/antlr*-complete.jar; do
        [ -f "$j" ] && { echo "$j"; return 0; }
    done
    local best=""
    for j in "$HOME"/.local/lib/antlr*-complete.jar /usr/local/lib/antlr*-complete.jar \
             /usr/share/java/antlr*-complete.jar /opt/antlr*-complete.jar; do
        [ -f "$j" ] || continue
        if [ -z "$best" ] || [ "$(printf '%s\n%s\n' "$(jar_version "$best")" \
                "$(jar_version "$j")" | sort -V | tail -1)" = "$(jar_version "$j")" ]; then
            best="$j"
        fi
    done
    [ -n "$best" ] && { echo "$best"; return 0; }
    return 1
}

# 4.13.2 out of /path/antlr-4.13.2-complete.jar; 0 when the name says nothing.
jar_version() {
    local v
    v="$(basename "$1" | sed -n 's/^antlr-\{0,1\}\([0-9][0-9.]*\)-complete\.jar$/\1/p')"
    echo "${v:-0}"
}

JAVA_BIN="$(find_java_bin)" || {
    echo "No JDK found. Install one (apt install openjdk-17-jdk) or set JAVA_HOME." >&2
    exit 1
}
ANTLR_JAR="$(find_antlr_jar)" || {
    echo "ANTLR jar not found. Download it next to this script:" >&2
    echo "  curl -fsSLO $ANTLR_URL" >&2
    echo "or set ANTLR_JAR to its path." >&2
    exit 1
}
# The generated Java uses List.of, so a JDK older than 9 cannot compile the output.
JAVA_MAJOR="$("$JAVA_BIN/javac" -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')"
if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -lt 11 ]; then
    echo "warning: javac $JAVA_MAJOR found; 11 or newer is expected" >&2
fi

ANTLR_VERSION="$(jar_version "$ANTLR_JAR")"
export JAVA_BIN ANTLR_JAR ANTLR_VERSION
