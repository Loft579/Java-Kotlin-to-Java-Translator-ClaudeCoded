#!/usr/bin/env bash
# Runs every program produced by tests.sh and prints its output, so the suite can
# be checked for behaviour and not just for "it compiles".
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$ROOT/env.sh"

OUT="$ROOT/build/out"
[ -d "$OUT/classes" ] || { echo "run ./tests.sh first" >&2; exit 1; }

for java in "$OUT"/*.java; do
    name="$(basename "$java" .java)"
    grep -q 'static void main' "$java" || continue          # nothing to run
    echo "===== $name"
    "$JAVA_BIN/java" -Dfile.encoding=UTF-8 -cp "$OUT/classes" "$name" 2>&1 | sed 's/^/| /'
done
