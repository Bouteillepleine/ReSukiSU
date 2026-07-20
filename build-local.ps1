<#
.SYNOPSIS
    Local replacement for the GitHub "Build Manager" CI pipeline.

.DESCRIPTION
    Does what CI does, on this machine:
      1. Gradle-builds the manager APK
      2. Cross-compiles ksud from source with cargo-ndk
      3. Stages ksud where repack_apk_multi.py expects it
      4. Runs the project's own repack script (inject ksud + zipalign + sign)
      5. Optionally installs to the connected device

    Needed because a fork can't produce artifacts on a push: the workflow's
    signing/repack/upload steps are gated to main|dev|ci|sync-upstream, and
    "Prepare signing inputs" fails on the empty PROD_KEYSTORE secret anyway.

    Signing comes from repack-config.json (git-excluded, holds keystore creds).
    That script signs v2-only, which is required: the kernel's and ksud's
    apk_sign parser rejects any APK carrying a v3 signature block.

.EXAMPLE
    .\build-local.ps1
    .\build-local.ps1 -Install
    .\build-local.ps1 -BuildType release -SkipKsud
#>
[CmdletBinding()]
param(
    # Gradle variant to build. CI builds both; debug is the usual local choice.
    [ValidateSet('debug', 'release')]
    [string]$BuildType = 'debug',

    # Reuse the existing ksud binary instead of recompiling (much faster).
    [switch]$SkipKsud,

    # adb install the result when finished.
    [switch]$Install
)

$ErrorActionPreference = 'Stop'
$repo = $PSScriptRoot

function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

# --- Toolchain locations -----------------------------------------------------
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
if (-not (Test-Path $sdk)) { throw "Android SDK not found at $sdk" }

$ndk = Get-ChildItem (Join-Path $sdk 'ndk') -Directory |
       Sort-Object Name -Descending | Select-Object -First 1
if (-not $ndk) { throw "No NDK found under $sdk\ndk" }

$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (-not (Test-Path $jbr)) { throw "Android Studio JBR not found at $jbr" }

# repack_apk.py finds zipalign/apksigner via ANDROID_SDK_ROOT or ANDROID_HOME.
# Note ANDROID_SDK_HOME (C:\Android) is a *different* variable and won't work.
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_HOME     = $sdk
$env:ANDROID_NDK_HOME = $ndk.FullName
$env:JAVA_HOME        = $jbr
# bindgen (ksud build dependency) needs libclang explicitly on Windows.
$env:LIBCLANG_PATH    = Join-Path $ndk.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin'

Write-Host "SDK : $sdk"
Write-Host "NDK : $($ndk.Name)"

# --- 1. Manager APK ----------------------------------------------------------
Step "Gradle assemble$BuildType"
Push-Location (Join-Path $repo 'manager')
try {
    $target = $BuildType.Substring(0,1).ToUpper() + $BuildType.Substring(1)
    & .\gradlew.bat ":app:assemble$target" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally { Pop-Location }

# --- 2. ksud -----------------------------------------------------------------
# Stable Rust is fine: CI's extra -Z build-std flags only shrink the binary.
$staged = Join-Path $repo 'target\aarch64-linux-android\release\ksud'
if ($SkipKsud -and (Test-Path $staged)) {
    Step "Reusing existing ksud ($((Get-Item $staged).Length) bytes)"
} else {
    Step "cargo ndk build ksud (aarch64)"
    Push-Location (Join-Path $repo 'userspace\ksud')
    try {
        & cargo ndk b -t aarch64-linux-android -r
        if ($LASTEXITCODE -ne 0) { throw "ksud build failed" }
    } finally { Pop-Location }

    $built = Join-Path $repo 'userspace\ksud\target\aarch64-linux-android\release\ksud'
    if (-not (Test-Path $built)) { throw "ksud missing after build: $built" }

    # repack_apk.py looks under <repo>/target/<triple>/<ksud_build_type>/ksud
    New-Item -ItemType Directory -Force (Split-Path $staged) | Out-Null
    Copy-Item $built $staged -Force
    Write-Host "Staged ksud -> $staged ($((Get-Item $staged).Length) bytes)"
}

# --- 3. Repack (the same script CI invokes) ----------------------------------
Step "repack_apk_multi.py"
Push-Location $repo
try {
    if (-not (Test-Path (Join-Path $repo 'repack-config.json'))) {
        throw "repack-config.json missing - it carries the signing config"
    }
    & python repack_apk_multi.py repack -b $BuildType
    if ($LASTEXITCODE -ne 0) { throw "repack failed" }
} finally { Pop-Location }

$apk = Get-ChildItem (Join-Path $repo 'dist\*.apk') |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw "No APK produced in dist/" }

Step "Result"
Write-Host $apk.FullName
Write-Host "$([math]::Round($apk.Length / 1MB, 1)) MB"

# Guard the failure mode that silently breaks module listing on-device.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
try {
    $lib = $zip.Entries | Where-Object { $_.FullName -eq 'lib/arm64-v8a/libksud.so' }
    if (-not $lib) { throw "libksud.so missing from APK - manager would show 0 modules" }
    Write-Host "libksud.so present ($($lib.Length) bytes)"
} finally { $zip.Dispose() }

# --- 4. Install --------------------------------------------------------------
if ($Install) {
    Step "adb install"
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    # -d allows a lower versionCode (version is derived from git commit count,
    # so it moves backwards when switching branches).
    & $adb install -r -d $apk.FullName
    if ($LASTEXITCODE -ne 0) { throw "install failed" }
    & $adb shell am force-stop com.resukisu.resukisu
    Write-Host "Installed. If the manager shows 'Non installe', re-register the"
    Write-Host "signature: su -c '/data/adb/ksud kernel dynamic-manager set-apk <apk>'"
}

Write-Host "`nDone." -ForegroundColor Green
