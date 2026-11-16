@echo off
rem Runs the translator that build.bat compiled; takes the same arguments as Kotlin2Java:
rem   k2j.bat input.kt Output.java
rem Files are read and written relative to the directory it is run from.
setlocal
set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not exist "%ROOT%\build\classes\Kotlin2Java.class" (
    echo the translator is not compiled yet; run %ROOT%\build.bat first>&2
    exit /b 1
)
"%JAVA%" -cp "%ROOT%\build\classes" Kotlin2Java %*
