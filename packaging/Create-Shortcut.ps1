<#
  Create-Shortcut.ps1 — put a "TraceGuard" icon on the Desktop (and optionally Start Menu)
  that launches the bundled TraceGuard.bat. No admin required (writes to your own profile).

  Run this on the TARGET machine, once, after unzipping the bundle:
      powershell -ExecutionPolicy Bypass -File Create-Shortcut.ps1
  or point it at the bundle explicitly:
      powershell -ExecutionPolicy Bypass -File Create-Shortcut.ps1 -BundleDir "C:\Users\me\Apps\TraceGuard-windows"
#>
[CmdletBinding()]
param(
  [string]$BundleDir = $PSScriptRoot,   # defaults to the folder this script sits in
  [switch]$StartMenu                    # also add a Start Menu entry
)
$ErrorActionPreference = 'Stop'

$bat = Join-Path $BundleDir 'TraceGuard.bat'
if (-not (Test-Path $bat)) { throw "TraceGuard.bat not found in '$BundleDir'. Pass -BundleDir <the unzipped folder>." }
$ico = Join-Path $BundleDir 'traceguard.ico'

function New-Shortcut($lnkPath) {
  $ws = New-Object -ComObject WScript.Shell
  $sc = $ws.CreateShortcut($lnkPath)
  $sc.TargetPath = $bat
  $sc.WorkingDirectory = $BundleDir
  $sc.Description = 'TraceGuard — trace the flow, guard the release'
  if (Test-Path $ico) { $sc.IconLocation = $ico }
  $sc.Save()
  Write-Host "==> Shortcut created: $lnkPath"
}

New-Shortcut (Join-Path ([Environment]::GetFolderPath('Desktop')) 'TraceGuard.lnk')
if ($StartMenu) {
  $sm = Join-Path ([Environment]::GetFolderPath('StartMenu')) 'Programs'
  New-Item -ItemType Directory -Force -Path $sm | Out-Null
  New-Shortcut (Join-Path $sm 'TraceGuard.lnk')
}
Write-Host "Double-click the TraceGuard icon on your Desktop to launch."
