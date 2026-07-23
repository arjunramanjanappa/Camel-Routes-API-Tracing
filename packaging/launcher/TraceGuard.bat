@echo off
setlocal enabledelayedexpansion
title TraceGuard
REM ============================================================================
REM  TraceGuard standalone launcher (Windows).
REM  Double-click this (or its desktop shortcut). It starts the bundled server
REM  and opens your browser. No install, no admin, no PATH changes.
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
  echo Make sure this launcher sits next to the "app" and "jre" folders.
  pause
  exit /b 1
)

REM --- If a server is already answering on the port, just open the browser ---
powershell -NoProfile -Command "try{[void](Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 '%URL%');exit 0}catch{exit 1}" >nul 2>&1
if !errorlevel! equ 0 (
  echo [TraceGuard] Already running - opening %URL%
  start "" "%URL%"
  timeout /t 2 >nul
  exit /b 0
)

REM --- Background: wait for the server to come up, then open the default browser ---
start "" /b powershell -NoProfile -WindowStyle Hidden -Command "$u='%URL%';for($i=0;$i -lt 90;$i++){try{[void](Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 $u);Start-Process $u;break}catch{Start-Sleep -Milliseconds 800}}"

echo [TraceGuard] Starting... your browser will open when it is ready.
echo [TraceGuard] Keep this window open while you use TraceGuard. Close it to stop.
echo.
"!JAVAC!" -jar "!JAR!"
