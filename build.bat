@echo off
rem Compiles the translator into build\classes, with nothing but a JDK. The Windows
rem twin of build.sh -- see there for the whole story; the ANTLR version, URLs and
rem SHA-256 values below must stay equal to the ones in env.sh.
rem
rem Everything it compiles is source code kept in this repository:
rem   Kotlin2Java.java   the translator
rem   generated\         the parser ANTLR wrote from Kotlin.g4
rem   antlr-runtime\     the source of the ANTLR runtime that parser uses
rem Delete generated\ or antlr-runtime\ and this script puts them back: it downloads
rem what can only come from the internet, checks its SHA-256, and generates the rest.
rem It never deletes a file to do it: a download that fails or does not match stops
rem the build and leaves everything as it was.
rem After editing Kotlin.g4, delete generated\ so that it is made again.
setlocal enabledelayedexpansion

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"

set "ANTLR_VERSION=4.13.2"
set "ANTLR_TOOL_NAME=antlr-%ANTLR_VERSION%-complete.jar"
set "ANTLR_TOOL_URL=https://www.antlr.org/download/%ANTLR_TOOL_NAME%"
set "ANTLR_TOOL_SHA256=eae2dfa119a64327444672aff63e9ec35a20180dc5b8090b7a6ab85125df4d76"
set "ANTLR_RUNTIME_SRC_NAME=antlr4-runtime-%ANTLR_VERSION%-sources.jar"
set "ANTLR_RUNTIME_SRC_URL=https://repo1.maven.org/maven2/org/antlr/antlr4-runtime/%ANTLR_VERSION%/%ANTLR_RUNTIME_SRC_NAME%"
set "ANTLR_RUNTIME_SRC_SHA256=4100b1df2f38152d8502e89195b94654d9aa22f5b6c47aca7b6a7f3194b5fbb0"
set "ANTLR_LICENSE_URL=https://raw.githubusercontent.com/antlr/antlr4/%ANTLR_VERSION%/LICENSE.txt"
set "ANTLR_LICENSE_SHA256=3db1fb3ee79a4b4f9918fc4d0f6133bf18a3cf787f126cd22f8aa9b862281c0c"

set "RUNTIME=%ROOT%\antlr-runtime"
set "GENERATED=%ROOT%\generated"
set "PARSER_FILES=KotlinLexer.java KotlinParser.java KotlinVisitor.java KotlinBaseVisitor.java"
set "DOWNLOADS=%ROOT%\build\downloads"
if not exist "%DOWNLOADS%" mkdir "%DOWNLOADS%"

rem --- locate the JDK ---
set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK=%JAVA_HOME%\bin"
if not defined JDK (
    for /f "delims=" %%J in ('where javac 2^>nul') do (
        if not defined JDK for %%D in ("%%~dpJ.") do set "JDK=%%~fD"
    )
)
if not defined JDK (
    for /d %%D in ("%ProgramFiles%\Java\jdk*") do if exist "%%D\bin\javac.exe" set "JDK=%%D\bin"
)
if not defined JDK (
    echo No JDK found. Install one or set JAVA_HOME.>&2
    exit /b 1
)
rem The javac on the PATH may be a launcher stub, like Oracle's
rem "Common Files\Oracle\Java\javapath", which has java and javac but not jar.
rem The JDK itself is where java.home says.
if not exist "%JDK%\jar.exe" (
    "%JDK%\java.exe" -XshowSettings:properties -version 2> "%DOWNLOADS%\java-props.txt"
    set "JHOME="
    for /f "tokens=1,* delims==" %%A in ('findstr /c:"java.home" "%DOWNLOADS%\java-props.txt"') do set "JHOME=%%B"
    if defined JHOME for /f "tokens=*" %%X in ("!JHOME!") do set "JHOME=%%X"
    if defined JHOME if exist "!JHOME!\bin\jar.exe" set "JDK=!JHOME!\bin"
    del "%DOWNLOADS%\java-props.txt" 2>nul
)
if not exist "%JDK%\jar.exe" (
    echo The JDK in %JDK% has no jar.exe. Install a full JDK or set JAVA_HOME.>&2
    exit /b 1
)
echo == jdk: %JDK%

rem --- 1. the ANTLR runtime, as source ---
rem (gotos rather than blocks: a path holding a parenthesis, as in
rem "Program Files (x86)", would close a block early)
echo == antlr-runtime\
if exist "%RUNTIME%\org\antlr\v4\runtime\" goto :runtime_present
if exist "%RUNTIME%\org" goto :runtime_partial
echo    missing: fetching the official source of ANTLR %ANTLR_VERSION%
set "JARFILE=%DOWNLOADS%\%ANTLR_RUNTIME_SRC_NAME%"
call :fetch "%ANTLR_RUNTIME_SRC_URL%" %ANTLR_RUNTIME_SRC_SHA256% "%JARFILE%" || goto :fail
set "LICENSE=%DOWNLOADS%\LICENSE.txt"
call :fetch "%ANTLR_LICENSE_URL%" %ANTLR_LICENSE_SHA256% "%LICENSE%" || goto :fail
rem only the Java sources (org\...), not the build metadata the jar also carries
if exist "%DOWNLOADS%\runtime.next" rmdir /s /q "%DOWNLOADS%\runtime.next"
mkdir "%DOWNLOADS%\runtime.next"
pushd "%DOWNLOADS%\runtime.next"
"%JDK%\jar.exe" xf "%JARFILE%" org
set "RC=%errorlevel%"
popd
if not "%RC%"=="0" goto :fail
if not exist "%RUNTIME%" mkdir "%RUNTIME%"
call :move_retry "%DOWNLOADS%\runtime.next\org" "%RUNTIME%\org" || goto :fail
if not exist "%RUNTIME%\LICENSE.txt" copy "%LICENSE%" "%RUNTIME%\LICENSE.txt" >nul
rmdir /s /q "%DOWNLOADS%\runtime.next"
echo    ok: antlr-runtime\ restored
goto :runtime_done
:runtime_partial
echo    incomplete: antlr-runtime\org is there but not the runtime in it.>&2
echo    Nothing was changed; delete the whole antlr-runtime\ to fetch it again.>&2
goto :fail
:runtime_present
echo    present, using the copy in the repository
echo    sha256: not checked, there is no official value for the unpacked copy
echo      (delete antlr-runtime\ to download it again and check it)
:runtime_done

rem --- 2. the parser generated from Kotlin.g4 ---
echo == generated\ (the parser ANTLR writes from Kotlin.g4)
set "REGEN="
set "PRESENT="
for %%F in (%PARSER_FILES%) do (
    if exist "%GENERATED%\%%F" (
        set "PRESENT=!PRESENT! %%F"
    ) else (
        if not defined REGEN set "REGEN=%%F is missing"
    )
)
if not defined REGEN goto :generated_present
if defined PRESENT goto :generated_partial
echo    regenerating (%REGEN%), this needs the ANTLR %ANTLR_VERSION% tool
set "TOOL="
if defined ANTLR_JAR if exist "%ANTLR_JAR%" set "TOOL=%ANTLR_JAR%"
if not defined TOOL if exist "%ROOT%\%ANTLR_TOOL_NAME%" set "TOOL=%ROOT%\%ANTLR_TOOL_NAME%"
if not defined TOOL goto :tool_download
rem a copy this machine already has: checked, never replaced or deleted
echo    found %TOOL%
call :check_sha256 "%TOOL%" %ANTLR_TOOL_SHA256%
if not errorlevel 1 goto :tool_ok
echo      it was left untouched. Replace it with the official>&2
echo      %ANTLR_TOOL_NAME%, or set ANTLR_JAR to one.>&2
goto :fail
:tool_download
echo    not on this machine
set "TOOL=%ROOT%\%ANTLR_TOOL_NAME%"
call :fetch "%ANTLR_TOOL_URL%" %ANTLR_TOOL_SHA256% "%TOOL%" || goto :fail
:tool_ok
rem Run from the repository with a relative path: ANTLR writes the path it was given
rem into each file's first line, and an absolute one would put this machine's
rem directories in the repository.
if exist "%ROOT%\build\gen.next" rmdir /s /q "%ROOT%\build\gen.next"
pushd "%ROOT%"
"%JDK%\java.exe" -jar "%TOOL%" -visitor -no-listener -o build\gen.next Kotlin.g4
set "RC=%errorlevel%"
popd
if not "%RC%"=="0" goto :fail
if not exist "%GENERATED%" mkdir "%GENERATED%"
for %%F in (%PARSER_FILES%) do (
    call :move_retry "%ROOT%\build\gen.next\%%F" "%GENERATED%\%%F" || goto :fail
)
rmdir /s /q "%ROOT%\build\gen.next"
echo    ok: %PARSER_FILES%
goto :generated_done
:generated_partial
echo    incomplete: %REGEN%, but these are there:%PRESENT%>&2
echo    Nothing was changed; delete the whole generated\ to make it again.>&2
goto :fail
:generated_present
echo    present, using the copy in the repository
echo    sha256: not checked, ANTLR publishes no value for files made from Kotlin.g4
echo      (delete generated\ to make it again, as after editing Kotlin.g4)
:generated_done

rem --- 3. compile ---
set "CLASSES=%ROOT%\build\classes"
rem Compile into a staging directory and swap it in at the end, as build.sh does:
rem wiping the classes first would leave no translator at all if javac then failed,
rem and would break a test run that happens to be using them.
set "STAGE=%ROOT%\build\classes.next"
if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%STAGE%"

echo == javac: Kotlin2Java.java + generated\ + antlr-runtime\ -^> build\classes
rem The list goes in a file: the runtime alone is ~170 files. javac reads a
rem backslash inside quotes as an escape, so the paths are written with slashes.
set "SOURCES=%ROOT%\build\sources.txt"
(
    for /f "delims=" %%F in ('dir /s /b "%RUNTIME%\org\*.java"') do (
        set "P=%%F"
        echo "!P:\=/!"
    )
    for %%F in (%PARSER_FILES%) do (
        set "P=%GENERATED%\%%F"
        echo "!P:\=/!"
    )
    set "P=%ROOT%\Kotlin2Java.java"
    echo "!P:\=/!"
) > "%SOURCES%"
rem -XDsuppressNotes: the runtime uses a deprecated API, and the note says nothing
rem about this project
rem --release 17: the classes run on any Java from 17 up, whichever JDK built them;
rem build/ is shared by build.sh and build.bat on the same checkout.
"%JDK%\javac.exe" -nowarn -XDsuppressNotes --release 17 -encoding UTF-8 -d "%STAGE%" "@%SOURCES%"
if errorlevel 1 goto :fail

rem The swap is two renames, and on a Windows drive either can fail because another
rem process (OneDrive, an indexer, an antivirus) is still looking at the freshly
rem compiled files. It is retried; if it keeps failing the previous classes are
rem put back where they were.
set "OLD=%ROOT%\build\classes.old"
for /l %%N in (1,1,5) do (
    call :swap && goto :swapped
    ping -n 2 127.0.0.1 >nul
)
echo cannot replace build\classes: something is holding it open (OneDrive or an>&2
echo indexer). The previous classes are intact; close whatever is using build\>&2
echo and run build.bat again.>&2
goto :fail
:swapped

echo ok. To translate:  kotlin2java.bat input.kt Output.java
endlocal
exit /b 0

:fail
echo build failed>&2
exit /b 1

rem --- helpers ---

rem :swap  -> build\classes.next becomes build\classes
:swap
if exist "%OLD%" rmdir /s /q "%OLD%"
if exist "%CLASSES%" (
    move "%CLASSES%" "%OLD%" >nul 2>&1 || exit /b 1
)
move "%STAGE%" "%CLASSES%" >nul 2>&1 || (
    if exist "%OLD%" move "%OLD%" "%CLASSES%" >nul 2>&1
    exit /b 1
)
if exist "%OLD%" rmdir /s /q "%OLD%"
exit /b 0

rem :sha256_of FILE  -> sets SHA (lowercase hex, no spaces)
:sha256_of
set "SHA="
for /f "skip=1 delims=" %%H in ('certutil -hashfile "%~1" SHA256') do (
    if not defined SHA set "SHA=%%H"
)
if not defined SHA (
    echo cannot hash %~1 with certutil>&2
    exit /b 1
)
set "SHA=%SHA: =%"
exit /b 0

rem :check_sha256 FILE EXPECTED  -> prints the comparison, fails on a mismatch
:check_sha256
call :sha256_of "%~1" || exit /b 1
echo    sha256 of %~1
echo      expected: %~2
echo      got:      %SHA%
if /i "%SHA%"=="%~2" (
    echo      -^> MATCH
    exit /b 0
)
echo      -^> MISMATCH: this is not the official file>&2
exit /b 1

rem :fetch URL SHA256 DEST
rem Makes DEST the official file, and never deletes anything to do it:
rem - DEST already there: it is used if its SHA-256 matches, else the build stops
rem   and the file is left as it is.
rem - otherwise it is downloaded to DEST.part, and only moved to DEST if its
rem   SHA-256 matches. A failed or wrong download never ends up where it is used.
:fetch
if not exist "%~3" goto :fetch_download
echo    found %~3
call :check_sha256 "%~3" %~2
if not errorlevel 1 exit /b 0
echo      it was left untouched. Replace it with the official file from>&2
echo      %~1>&2
echo      or move it away so that it is downloaded again.>&2
exit /b 1
:fetch_download
echo    downloading %~1
call :download "%~1" "%~3.part"
if not errorlevel 1 goto :fetch_check
echo    sha256 of %~3.part>&2
echo      expected: %~2>&2
echo      got:      ^(nothing: the download failed^)>&2
echo      -^> NOT CHECKED>&2
echo      -^> FAILED: could not download it (no connection, or the server is>&2
echo         down). Nothing was changed: try again later, or download it>&2
echo         yourself and put it at %~3>&2
exit /b 1
:fetch_check
call :check_sha256 "%~3.part" %~2
if not errorlevel 1 goto :fetch_move
echo      nothing was changed; the download was kept, unused, at %~3.part>&2
exit /b 1
:fetch_move
call :move_retry "%~3.part" "%~3"
exit /b %errorlevel%

rem :download URL FILE, retrying when the connection fails or the server errs
:download
where curl.exe >nul 2>&1
if errorlevel 1 goto :download_ps
curl.exe -fsSL --connect-timeout 20 --retry 3 --retry-delay 2 --retry-connrefused -o "%~2" "%~1"
exit /b %errorlevel%
:download_ps
powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; for ($i = 1; $i -le 4; $i++) { try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 20 -Uri '%~1' -OutFile '%~2'; exit 0 } catch { Write-Host ('     ' + $_.Exception.Message); if ($i -lt 4) { Start-Sleep 2 } } }; exit 1"
exit /b %errorlevel%

rem :move_retry SRC DEST
rem A move on a Windows drive can fail for a moment because OneDrive or an
rem antivirus is still looking at files that were just written: it is retried.
:move_retry
for /l %%N in (1,1,10) do (
    move "%~1" "%~2" >nul 2>&1 && exit /b 0
    ping -n 2 127.0.0.1 >nul
)
echo    cannot move %~1 to %~2: something keeps it open (OneDrive, an antivirus^).>&2
echo    Nothing was deleted; close whatever is using it and run build.bat again.>&2
exit /b 1
