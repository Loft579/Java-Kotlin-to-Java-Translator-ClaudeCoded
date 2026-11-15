#!/usr/bin/env bash
# Runs the translator that build.sh compiled; takes the same arguments as Kotlin2Java:
#   ./kotlin2java.sh input.kt Output.java
#   ./kotlin2java.sh -e 'fun main() { println("hi") }'
# Files are read and written relative to the directory it is run from.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$ROOT/env.sh"

if [ ! -f "$ROOT/build/classes/Kotlin2Java.class" ]; then
    echo "the translator is not compiled yet; run $ROOT/build.sh first" >&2
    exit 1
fi
exec "$JAVA_BIN/java" -cp "$ROOT/build/classes" Kotlin2Java "$@"
