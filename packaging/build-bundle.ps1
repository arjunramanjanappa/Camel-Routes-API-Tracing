<#
  build-bundle.ps1 — assemble a self-contained TraceGuard bundle for Windows.

  Produces  dist\TraceGuard-windows\  (unzip-and-run: no install, no admin, no PATH) containing:
      TraceGuard.bat        the launcher (double-click)
      app\traceguard.jar    the Spring Boot fat jar
      jre\                   a trimmed Java runtime (jlink) — so the target needs no Java
      traceguard.ico        the shortcut icon
  ...and zips it to  dist\TraceGuard-windows.zip.

  Run this on a BUILD machine that has a full JDK 21 (for jlink) and, unless -SkipBuild, npm on PATH
  (the Maven build compiles the React frontend). The target machine needs none of that.

  Examples:
      powershell -ExecutionPolicy Bypass -File packaging\build-bundle.ps1
      powershell -ExecutionPolicy Bypass -File packaging\build-bundle.ps1 -SkipBuild   # reuse existing jar
      powershell -ExecutionPolicy Bypass -File packaging\build-bundle.ps1 -Full        # jlink ALL modules (bigger, safest)
#>
[CmdletBinding()]
param(
  [string]$Mvn,                 # path to mvn/mvn.cmd; auto-detected if omitted
  [string]$JdkHome = $env:JAVA_HOME,
  [switch]$SkipBuild,           # reuse the existing target\*.jar instead of running mvn package
  [switch]$Full                 # jlink with ALL-MODULE-PATH (largest, guaranteed to include everything)
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot            # repo root (packaging\ is one level down)
$dist = Join-Path $root 'dist\TraceGuard-windows'
Write-Host "==> Repo root: $root"

# --- Resolve the JDK (needs jlink) --------------------------------------------
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin\jlink.exe'))) {
  $jhome = (& java -XshowSettings:properties 2>&1 | Select-String 'java.home' | ForEach-Object { ($_ -split '=')[1].Trim() } | Select-Object -First 1)
  if ($jhome -and (Test-Path (Join-Path $jhome 'bin\jlink.exe'))) { $JdkHome = $jhome }
}
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin\jlink.exe'))) {
  throw "Could not find a JDK with jlink. Pass -JdkHome 'C:\Program Files\Java\jdk-21...' (a JDK, not a JRE)."
}
$jlink = Join-Path $JdkHome 'bin\jlink.exe'
Write-Host "==> JDK: $JdkHome"

# --- Build the fat jar (frontend + backend) -----------------------------------
if (-not $SkipBuild) {
  if (-not $Mvn) {
    $onPath = (Get-Command mvn -ErrorAction SilentlyContinue)
    if ($onPath) { $Mvn = $onPath.Source }
    else {
      $bundled = Get-ChildItem 'C:\Program Files\JetBrains\*\plugins\maven\lib\maven3\bin\mvn.cmd' -ErrorAction SilentlyContinue | Select-Object -First 1
      if ($bundled) { $Mvn = $bundled.FullName }
    }
  }
  if (-not $Mvn) { throw "Maven not found. Install it, or pass -Mvn 'path\to\mvn.cmd', or use -SkipBuild." }

  # Make the saved npm token available to the frontend build (private registry). The project's .npmrc
  # can reference it as ${NPM_TOKEN}; we don't print it.
  $settings = Join-Path $env:USERPROFILE '.traceguard\settings.json'
  if (Test-Path $settings) {
    try {
      $npm = (Get-Content $settings -Raw | ConvertFrom-Json).npmToken
      if ($npm) { $env:NPM_TOKEN = $npm; Write-Host "==> npm token loaded from $settings (hidden)" }
    } catch { Write-Warning "Could not read npm token from $settings : $_" }
  }
  Write-Host "==> mvn package  ($Mvn)"
  & $Mvn -f (Join-Path $root 'pom.xml') -DskipTests clean package
  if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit $LASTEXITCODE)." }
}

$jar = Get-ChildItem (Join-Path $root 'target\*.jar') -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notmatch '\.original$' -and $_.Name -notmatch 'sources|javadoc' } |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw "No jar found in target\. Run without -SkipBuild first." }
Write-Host "==> Jar: $($jar.Name)"

# --- Fresh dist dir -----------------------------------------------------------
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $dist 'app') | Out-Null

# --- jlink a trimmed runtime --------------------------------------------------
$jreOut = Join-Path $dist 'jre'
if ($Full) {
  $mods = 'ALL-MODULE-PATH'
  $modArgs = @('--module-path', (Join-Path $JdkHome 'jmods'), '--add-modules', $mods)
} else {
  # Known-good module set for a Spring Boot + Tomcat + JGit(TLS) + Jackson app.
  $mods = @(
    'java.base','java.desktop','java.instrument','java.logging','java.management','java.naming',
    'java.net.http','java.prefs','java.rmi','java.scripting','java.security.jgss','java.security.sasl',
    'java.sql','java.transaction.xa','java.xml','jdk.crypto.ec','jdk.crypto.cryptoki','jdk.unsupported',
    'jdk.zipfs','jdk.jdwp.agent'
  ) -join ','
  $modArgs = @('--add-modules', $mods)
}
Write-Host "==> jlink -> $jreOut"
& $jlink @modArgs --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output $jreOut
if ($LASTEXITCODE -ne 0) { throw "jlink failed (exit $LASTEXITCODE)." }

# --- Assemble -----------------------------------------------------------------
Copy-Item $jar.FullName (Join-Path $dist 'app\traceguard.jar') -Force
Copy-Item (Join-Path $PSScriptRoot 'launcher\TraceGuard.bat') $dist -Force

# Icon: copy the pre-built icon that ships in packaging\.
$ico = Join-Path $PSScriptRoot 'traceguard.ico'
if (Test-Path $ico) { Copy-Item $ico $dist -Force; Write-Host "==> Icon: traceguard.ico" }
else { Write-Warning "packaging\traceguard.ico not found; skipping icon (non-fatal)." }

# --- Zip ----------------------------------------------------------------------
$zip = Join-Path $root 'dist\TraceGuard-windows.zip'
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $dist '*') -DestinationPath $zip -Force

$sizeMb = [math]::Round(((Get-ChildItem $dist -Recurse | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host ""
Write-Host "==> Done. Bundle: $dist  (~$sizeMb MB)"
Write-Host "==> Zip:    $zip"
Write-Host "    Ship the zip. On the target: unzip anywhere in your user folder, then double-click TraceGuard.bat"
Write-Host "    Optional desktop icon:  powershell -ExecutionPolicy Bypass -File packaging\Create-Shortcut.ps1 -BundleDir '$dist'"
