#!/usr/bin/env bash
# Translates every input_files/*.kt and compiles the resulting .java with javac.
# Reports a table: PARSE (ANTLR syntax errors) / TRANS (translator crash) / JAVAC.
# MAIN_CLASS=OtherTranslator ./tests.sh runs the suite against another entry point.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$ROOT/env.sh"

MAIN_CLASS="${MAIN_CLASS:-Kotlin2Java}"

OUT="$ROOT/build/out"
LOG="$ROOT/build/logs"
rm -rf "$OUT" "$LOG"
mkdir -p "$OUT" "$LOG"

# The suite pins the translator it tests: a build.sh running meanwhile replaces
# build/classes, and a file translated after that point would be tested against a
# different version than the ones before it -- or find no classes at all mid-swap.
if [ ! -d "$ROOT/build/classes" ]; then
    echo "build/classes not found; run ./build.sh first" >&2
    exit 1
fi
cp -r "$ROOT/build/classes" "$OUT/translator"
CP="$ANTLR_JAR:$OUT/translator"

pass=0; fail=0
printf '%-14s %-7s %-7s %-7s %s\n' FILE PARSE TRANS JAVAC NOTE
printf '%.0s-' {1..72}; echo

for kt in "$ROOT"/input_files/*.kt; do
    name="$(basename "$kt" .kt)"
    # The class name comes from the output file name, so it has to be invoked from
    # the target directory with a bare name (no directories, no extra dots).
    ( cd "$OUT" && "$JAVA_BIN/java" -cp "$CP" "$MAIN_CLASS" "$kt" "$name.java" ) \
        >"$LOG/$name.trans.log" 2>&1
    trans_rc=$?

    if grep -qE '^line [0-9]+:[0-9]+ ' "$LOG/$name.trans.log"; then parse=ERR; else parse=ok; fi
    if [ $trans_rc -ne 0 ]; then trans=ERR; else trans=ok; fi

    if [ -f "$OUT/$name.java" ]; then
        "$JAVA_BIN/javac" -nowarn -encoding UTF-8 -d "$OUT/classes" "$OUT/$name.java" \
            >"$LOG/$name.javac.log" 2>&1
        if [ $? -eq 0 ]; then jc=ok; else jc=ERR; fi
    else
        jc=NOFILE
    fi

    note=""
    [ "$jc" = ERR ] && note="$(grep -m1 'error:' "$LOG/$name.javac.log" | sed "s|$OUT/||" | cut -c1-44)"
    [ "$trans" = ERR ] && note="$(grep -m1 -E 'Exception|error' "$LOG/$name.trans.log" | cut -c1-44)"

    if [ "$parse" = ok ] && [ "$trans" = ok ] && [ "$jc" = ok ]; then
        pass=$((pass+1))
    else
        fail=$((fail+1))
    fi
    printf '%-14s %-7s %-7s %-7s %s\n' "$name" "$parse" "$trans" "$jc" "$note"
done

printf '%.0s-' {1..72}; echo
echo "green: $pass   failing: $fail   (logs in build/logs/)"

# Multi-file fixtures: a main .kt plus the dependencies it imports, translated with
# --deps and compiled together, which the per-file loop above cannot cover.
if [ -d "$ROOT/input_files/multi" ]; then
    echo
    echo "multi-file:"
    for kt in "$ROOT"/input_files/multi/app*.kt; do
        name="$(basename "$kt" .kt)"
        cls="$(printf '%s' "${name^}")"
        dir="$OUT/multi_$name"
        mkdir -p "$dir"
        ( cd "$dir" && "$JAVA_BIN/java" -cp "$CP" "$MAIN_CLASS" --deps "$kt" "$cls.java" ) \
            >"$LOG/multi_$name.trans.log" 2>&1
        rc=$?
        if [ $rc -ne 0 ]; then
            printf '  %-20s TRANS ERR  %s\n' "$name" "$(grep -m1 error "$LOG/multi_$name.trans.log")"
            fail=$((fail+1))
            continue
        fi
        if "$JAVA_BIN/javac" -nowarn -encoding UTF-8 -d "$dir/classes" "$dir"/*.java \
                >"$LOG/multi_$name.javac.log" 2>&1; then
            out="$("$JAVA_BIN/java" -cp "$dir/classes" "$cls" 2>&1 | tr '\n' ' ')"
            printf '  %-20s ok    %s files -> %s\n' "$name" "$(ls "$dir"/*.java | wc -l)" "$out"
            pass=$((pass+1))
        else
            printf '  %-20s JAVAC ERR  %s\n' "$name" "$(grep -m1 error: "$LOG/multi_$name.javac.log")"
            fail=$((fail+1))
        fi
    done
fi

# A compiled library given with --classpath, standing in for android.jar: it is
# compiled first, on its own, so the translator sees nothing but its .class files.
if [ -d "$ROOT/input_files/classpath" ]; then
    echo
    echo "classpath:"
    lib="$OUT/classpath_lib"
    mkdir -p "$lib"
    if ! "$JAVA_BIN/javac" -nowarn -encoding UTF-8 -d "$lib" \
            $(find "$ROOT/input_files/classpath/lib" -name '*.java') \
            >"$LOG/classpath_lib.javac.log" 2>&1; then
        printf '  %-20s LIB ERR  %s\n' lib "$(grep -m1 error: "$LOG/classpath_lib.javac.log")"
        fail=$((fail+1))
    else
        for kt in "$ROOT"/input_files/classpath/app*.kt; do
            name="$(basename "$kt" .kt)"
            cls="$(printf '%s' "${name^}")"
            dir="$OUT/classpath_$name"
            mkdir -p "$dir"
            ( cd "$dir" && "$JAVA_BIN/java" -cp "$CP" "$MAIN_CLASS" --classpath "$lib" "$kt" "$cls.java" ) \
                >"$LOG/classpath_$name.trans.log" 2>&1
            if [ $? -ne 0 ]; then
                printf '  %-20s TRANS ERR  %s\n' "$name" "$(grep -m1 error "$LOG/classpath_$name.trans.log")"
                fail=$((fail+1))
                continue
            fi
            if "$JAVA_BIN/javac" -nowarn -encoding UTF-8 -cp "$lib" -d "$dir/classes" "$dir/$cls.java" \
                    >"$LOG/classpath_$name.javac.log" 2>&1; then
                out="$("$JAVA_BIN/java" -cp "$dir/classes:$lib" "$cls" 2>&1 | tr '\n' ' ')"
                printf '  %-20s ok    -> %s\n' "$name" "$out"
                pass=$((pass+1))
            else
                printf '  %-20s JAVAC ERR  %s\n' "$name" "$(grep -m1 error: "$LOG/classpath_$name.javac.log")"
                fail=$((fail+1))
            fi
        done
    fi
fi

# Negative fixtures: these must be REJECTED. A translator that quietly drops what
# it cannot handle looks like it passes, so the guarantee is tested explicitly.
if [ -d "$ROOT/input_files/invalid" ]; then
    echo
    echo "rejected-as-expected:"
    nok=0; nbad=0
    for kt in "$ROOT"/input_files/invalid/*.kt; do
        name="$(basename "$kt" .kt)"
        ( cd "$OUT" && "$JAVA_BIN/java" -cp "$CP" "$MAIN_CLASS" "$kt" "bad_$name.java" ) \
            >"$LOG/$name.trans.log" 2>&1
        rc=$?
        if [ $rc -ne 0 ] && [ ! -f "$OUT/bad_$name.java" ]; then
            nok=$((nok+1))
            printf '  %-28s ok    %s\n' "$name" "$(head -1 "$LOG/$name.trans.log" | cut -c1-40)"
        else
            nbad=$((nbad+1))
            printf '  %-28s FAIL  (accepted, exit %s)\n' "$name" "$rc"
        fi
    done
    echo "rejected: $nok   wrongly accepted: $nbad"
fi
