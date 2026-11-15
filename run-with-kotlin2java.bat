@echo off
rem Compiles and runs a Java program of yours that calls Kotlin2Java.translate(...),
rem with the translator on the classpath, so there is no -cp to write:
rem   run-with-kotlin2java.bat path\to\MyProgram.java [arguments for it]
rem The program must be in the default package (no 'package' line), as Kotlin2Java
rem is. Its .class files are written next to it. The translator is built first if
rem build.bat has not been run yet.
setlocal
set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
if "%~1"=="" goto :usage
if not exist "%~1" goto :usage
set "SRC=%~f1"
set "DIR=%~dp1"
set "DIR=%DIR:~0,-1%"
set "NAME=%~n1"

set "JAVA=java"
set "JAVAC=javac"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
)

if exist "%ROOT%\build\classes\Kotlin2Java.class" goto :built
echo == the translator is not compiled yet: running build.bat>&2
call "%ROOT%\build.bat" >&2 || exit /b 1
:built

rem everything after the .java file is passed on to the program
set "ARGS="
:collect
shift
if "%~1"=="" goto :run
set ARGS=%ARGS% %1
goto :collect
:run
"%JAVAC%" -encoding UTF-8 -cp "%ROOT%\build\classes" -d "%DIR%" "%SRC%" || exit /b 1
"%JAVA%" -cp "%ROOT%\build\classes;%DIR%" %NAME% %ARGS%
exit /b %errorlevel%

:usage
echo usage: %~nx0 MyProgram.java [arguments for it]>&2
exit /b 1
