#!/usr/bin/env bash
# Compiles and runs a Java program of yours that calls Kotlin2Java.translate(...),
# with the translator on the classpath, so there is no -cp to write:
#   ./run-with-kotlin2java.sh path/to/MyProgram.java [arguments for it]
# The program must be in the default package (no 'package' line), as Kotlin2Java
# is. Its .class files are written next to it. The translator is built first if
# build.sh has not been run yet.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$ROOT/env.sh"

if [ $# -lt 1 ] || [ ! -f "$1" ]; then
    echo "usage: $0 MyProgram.java [arguments for it]" >&2
    exit 1
fi
src="$1"; shift
dir="$(cd "$(dirname "$src")" && pwd)"
name="$(basename "$src" .java)"

if [ ! -f "$ROOT/build/classes/Kotlin2Java.class" ]; then
    echo "== the translator is not compiled yet: running build.sh" >&2
    "$ROOT/build.sh" >&2
fi

"$JAVA_BIN/javac" -encoding UTF-8 -cp "$ROOT/build/classes" -d "$dir" "$src"
exec "$JAVA_BIN/java" -cp "$ROOT/build/classes:$dir" "$name" "$@"
