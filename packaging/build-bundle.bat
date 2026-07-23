@echo off
setlocal enabledelayedexpansion
REM ============================================================================
REM  build-bundle.bat - assemble a self-contained TraceGuard bundle for Windows
REM  using ONLY cmd + the Java tools + tar. No PowerShell .ps1 execution, so it
REM  works on locked-down machines where group policy blocks script files.
REM  Run from a shell where npm + mvn work (e.g. IntelliJ's Terminal).
REM
REM  Usage:  packaging\build-bundle.bat            full build
REM          packaging\build-bundle.bat skipbuild  reuse the existing target\*.jar
REM          packaging\build-bundle.bat full       jlink ALL modules (bigger, safest)
REM  (skipbuild and full can be combined.)
REM ============================================================================

set "HERE=%~dp0"
pushd "%HERE%.." >nul & set "ROOT=%CD%" & popd >nul

set "SKIPBUILD=0"
set "FULL=0"
for %%a in (%*) do (
  if /i "%%a"=="skipbuild" set "SKIPBUILD=1"
  if /i "%%a"=="full" set "FULL=1"
)
echo ==^> Repo root: %ROOT%

REM --- Find a JDK with jlink (JAVA_HOME, else derive from 'java' on PATH) -------
set "JLINK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jlink.exe" set "JLINK=%JAVA_HOME%\bin\jlink.exe"
if not defined JLINK (
  for /f "tokens=1* delims==" %%a in ('java -XshowSettings:properties 2^>^&1 ^| findstr /c:"java.home"') do set "JHOME=%%b"
  for /f "tokens=* delims= " %%a in ("!JHOME!") do set "JHOME=%%a"
  if exist "!JHOME!\bin\jlink.exe" set "JLINK=!JHOME!\bin\jlink.exe"
)
if not defined JLINK (
  echo ERROR: Could not find a JDK with jlink. Set JAVA_HOME to a JDK 21 ^(not a JRE^).
  exit /b 1
)
REM JDK home = jlink path minus \bin\jlink.exe (needed for --module-path in -full mode)
set "JDKDIR=!JLINK:\bin\jlink.exe=!"
echo ==^> jlink: !JLINK!

REM --- Build the fat jar (frontend + backend) ----------------------------------
if "%SKIPBUILD%"=="0" (
  set "MVN="
  for /f "delims=" %%m in ('where mvn 2^>nul') do if not defined MVN set "MVN=%%m"
  if not defined MVN (
    for /f "delims=" %%m in ('dir /b /s "C:\Program Files\JetBrains\*\plugins\maven\lib\maven3\bin\mvn.cmd" 2^>nul') do if not defined MVN set "MVN=%%m"
  )
  if not defined MVN (
    echo ERROR: Maven not found. Run from IntelliJ's Terminal, add mvn to PATH, or use "skipbuild".
    exit /b 1
  )
  echo ==^> mvn: !MVN!
  echo ==^> Building ^(mvn package^)... this rebuilds the frontend, so npm must be on PATH.
  call "!MVN!" -f "%ROOT%\pom.xml" -DskipTests clean package
  if errorlevel 1 ( echo ERROR: Maven build failed. & exit /b 1 )
)

REM --- Locate the jar (newest in target\, excluding thin/sources/javadoc) -------
set "JAR="
for /f "delims=" %%j in ('dir /b /a-d /o-d "%ROOT%\target\*.jar" 2^>nul') do (
  echo %%j| findstr /i "original sources javadoc" >nul
  if errorlevel 1 if not defined JAR set "JAR=%ROOT%\target\%%j"
)
if not defined JAR ( echo ERROR: No jar in target\. Run without "skipbuild" first. & exit /b 1 )
echo ==^> Jar: !JAR!

REM --- Fresh dist dir ----------------------------------------------------------
set "DIST=%ROOT%\dist\TraceGuard-windows"
if exist "%DIST%" rmdir /s /q "%DIST%"
mkdir "%DIST%\app"

REM --- jlink a trimmed runtime -------------------------------------------------
if "%FULL%"=="1" (
  set "MODARGS=--module-path "!JDKDIR!\jmods" --add-modules ALL-MODULE-PATH"
) else (
  set "MODARGS=--add-modules java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.jdwp.agent"
)
echo ==^> jlink -^> %DIST%\jre
REM compress=zip-0 (uncompressed): larger on disk but classes load as fast as a full JDK.
"!JLINK!" !MODARGS! --strip-debug --no-header-files --no-man-pages --compress=zip-0 --output "%DIST%\jre"
if errorlevel 1 ( echo ERROR: jlink failed. & exit /b 1 )
echo ==^> generating CDS archive ^(faster class loading^)
"%DIST%\jre\bin\java.exe" -Xshare:dump >nul 2>&1

REM --- Assemble ----------------------------------------------------------------
copy /y "!JAR!" "%DIST%\app\traceguard.jar" >nul
copy /y "%HERE%launcher\TraceGuard.bat" "%DIST%\" >nul

REM --- Icon (copy the pre-built icon that ships in packaging\) ------------------
if exist "%HERE%traceguard.ico" (
  copy /y "%HERE%traceguard.ico" "%DIST%\" >nul
  echo ==^> Icon: traceguard.ico
) else (
  echo NOTE: packaging\traceguard.ico not found ^(non-fatal^).
)

REM --- Zip with tar (built into Windows 10 1803+) ------------------------------
where tar >nul 2>&1
if not errorlevel 1 (
  if exist "%ROOT%\dist\TraceGuard-windows.zip" del /q "%ROOT%\dist\TraceGuard-windows.zip"
  tar -a -c -f "%ROOT%\dist\TraceGuard-windows.zip" -C "%ROOT%\dist" TraceGuard-windows
  echo ==^> Zip: %ROOT%\dist\TraceGuard-windows.zip
) else (
  echo NOTE: 'tar' not found. Zip the folder manually: right-click "%DIST%" ^> Send to ^> Compressed folder.
)

echo.
echo ==^> Done. Bundle: %DIST%
echo     Share the zip. On the target: unzip anywhere in your user folder, then double-click TraceGuard.bat
exit /b 0
