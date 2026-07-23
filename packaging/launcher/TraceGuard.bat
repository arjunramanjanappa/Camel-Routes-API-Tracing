@echo off
setlocal enabledelayedexpansion
title TraceGuard
REM ============================================================================
REM  TraceGuard standalone launcher (Windows).
REM  Double-click this (or its desktop shortcut). It starts the bundled server and
REM  the app opens your browser itself when ready. No install, no admin, no PATH,
REM  and no PowerShell (works where group policy blocks scripts).
REM  Close this window to stop TraceGuard.
REM ============================================================================

set "HERE=%~dp0"
set "PORT=8080"
set "URL=http://localhost:%PORT%/"

REM --- Locate a Java runtime: the bundled JRE first, then JAVA_HOME, then PATH ---
set "JAVAC=%HERE%jre\bin\java.exe"
if not exist "%JAVAC%" (
  if defined JAVA_HOME set "JAVAC=%JAVA_HOME%\bin\java.exe"
)
if not exist "%JAVAC%" set "JAVAC=java"

REM --- Locate the application jar (app\traceguard.jar, or the first jar in app\) ---
set "JAR=%HERE%app\traceguard.jar"
if not exist "%JAR%" (
  for %%f in ("%HERE%app\*.jar") do set "JAR=%%f"
)
if not exist "!JAR!" (
  echo [TraceGuard] Could not find the application jar under "%HERE%app\".
  echo Keep TraceGuard.bat next to the "app" and "jre" folders ^(run it from inside the unzipped folder^).
  pause
  exit /b 1
)

REM --- If a server already answers on the port, just open the browser and exit ---
REM     (curl ships with Windows 10/11; no PowerShell.)
where curl >nul 2>&1 && curl -s -o nul -m 2 "%URL%" && (
  echo [TraceGuard] Already running - opening %URL%
  start "" "%URL%"
  exit /b 0
)

REM --- Start the server; the app opens the browser itself when ready ------------
echo [TraceGuard] Starting... your browser will open automatically when it is ready.
echo [TraceGuard] Keep this window open while you use TraceGuard. Close it to stop.
echo.
"!JAVAC!" -Dtracer.open-browser=true -jar "!JAR!"
