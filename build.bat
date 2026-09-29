@echo off
rem Generates the parser from Kotlin.g4 and compiles the translator into build\classes.
rem Needs a JDK on the PATH (or JAVA_HOME set) and the ANTLR complete jar, either
rem next to this script or pointed at by ANTLR_JAR.
setlocal enabledelayedexpansion

set ROOT=%~dp0
set ROOT=%ROOT:~0,-1%

rem --- locate javac ---
set JAVAC=
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set JAVAC=%JAVA_HOME%\bin
if not defined JAVAC (
    for /f "delims=" %%J in ('where javac 2^>nul') do (
        if not defined JAVAC for %%D in ("%%~dpJ.") do set JAVAC=%%~fD
    )
)
if not defined JAVAC (
    for /d %%D in ("%ProgramFiles%\Java\jdk*") do if exist "%%D\bin\javac.exe" set JAVAC=%%D\bin
)
if not defined JAVAC (
    echo No JDK found. Install one or set JAVA_HOME.>&2
    exit /b 1
)

rem --- locate the ANTLR jar ---
if defined ANTLR_JAR if exist "%ANTLR_JAR%" goto :gotantlr
set ANTLR_JAR=
for %%J in ("%ROOT%\antlr*-complete.jar") do set ANTLR_JAR=%%~fJ
if not defined ANTLR_JAR (
    echo ANTLR jar not found. Download it next to this script:>&2
    echo   curl -LO https://www.antlr.org/download/antlr-4.13.2-complete.jar>&2
    echo or set ANTLR_JAR to its path.>&2
    exit /b 1
)
:gotantlr

set GEN=%ROOT%\build\gen
set CLASSES=%ROOT%\build\classes
rem Compile into a staging directory and swap it in at the end, as build.sh does:
rem wiping the classes first would leave no translator at all if javac then failed,
rem and would break a test run that happens to be using them.
set STAGE=%ROOT%\build\classes.next
if exist "%GEN%" rmdir /s /q "%GEN%"
if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%GEN%" 2>nul
mkdir "%STAGE%" 2>nul

echo == antlr: Kotlin.g4 -^> build\gen
"%JAVAC%\java.exe" -jar "%ANTLR_JAR%" -visitor -no-listener -o "%GEN%" "%ROOT%\Kotlin.g4"
if errorlevel 1 exit /b 1

echo == javac: translator -^> build\classes
"%JAVAC%\javac.exe" -nowarn -encoding UTF-8 -cp "%ANTLR_JAR%" -d "%STAGE%" "%GEN%\*.java" "%ROOT%\Kotlin2Java.java"
if errorlevel 1 exit /b 1

if exist "%CLASSES%" rmdir /s /q "%CLASSES%"
move /y "%STAGE%" "%CLASSES%" >nul

echo ok
endlocal
