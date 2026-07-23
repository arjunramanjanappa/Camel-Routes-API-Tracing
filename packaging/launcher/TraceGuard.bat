@echo off
setlocal enabledelayedexpansion
title TraceGuard Launcher
REM ============================================================================
REM  TraceGuard standalone launcher (Windows).
REM  Double-click this (or its desktop shortcut). It starts the bundled server
REM  in its own "TraceGuard" window and opens your browser. No install, no admin,
REM  no PATH changes, and no PowerShell (works where group policy blocks scripts).
REM  Close the "TraceGuard" window to stop the app.
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

REM --- curl ships with Windows 10/11; used to poll readiness (no PowerShell) ---
set "HAVE_CURL="
where curl >nul 2>&1 && set "HAVE_CURL=1"

REM --- If a server already answers on the port, just open the browser and exit ---
if defined HAVE_CURL (
  curl -s -o nul -m 2 "%URL%" && (
    echo [TraceGuard] Already running - opening %URL%
    start "" "%URL%"
    exit /b 0
  )
)

REM --- Start the server in its OWN window. Closing that window stops TraceGuard. ---
start "TraceGuard" "!JAVAC!" -jar "!JAR!"

REM --- Wait for it to come up, then open the default browser (pure cmd) ---
echo [TraceGuard] Starting... your browser will open automatically when it is ready.
if defined HAVE_CURL (
  for /l %%i in (1,1,90) do (
    curl -s -o nul -m 2 "%URL%" && (
      start "" "%URL%"
      goto :opened
    )
    timeout /t 1 /nobreak >nul
  )
  REM Fell through without a response - open anyway so the user isn't stuck.
  start "" "%URL%"
) else (
  REM No curl: wait a few seconds for boot, then open.
  timeout /t 6 /nobreak >nul
  start "" "%URL%"
)

:opened
echo [TraceGuard] Opened %URL%
echo [TraceGuard] To stop TraceGuard, close the "TraceGuard" window.
timeout /t 4 /nobreak >nul
exit /b 0
