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
# ksud requires NIGHTLY on current revisions: src/main.rs opens with
# #![feature(decl_macro)], which stable rejects outright (E0554). Older
# revisions did build on stable, hence the previous note here - don't
# "simplify" this back.
# Set RUSTUP_TOOLCHAIN rather than `cargo +nightly`: cargo-ndk shells out to
# cargo/rustc itself, and those nested calls only inherit the toolchain through
# the env var.
$staged = Join-Path $repo 'target\aarch64-linux-android\release\ksud'
if ($SkipKsud -and (Test-Path $staged)) {
    Step "Reusing existing ksud ($((Get-Item $staged).Length) bytes)"
} else {
    Step "cargo ndk build ksud (aarch64, nightly)"
    Push-Location (Join-Path $repo 'userspace\ksud')
    $prevToolchain = $env:RUSTUP_TOOLCHAIN
    try {
        # Must be the -gnu host, spelled out: rustup's default host triple here
        # is x86_64-pc-windows-msvc, so a bare 'nightly' resolves to the MSVC
        # nightly and the host-side build scripts (proc-macro2, libc, ...) die
        # with "linker `link.exe` not found" - this box has no MSVC linker.
        $env:RUSTUP_TOOLCHAIN = 'nightly-x86_64-pc-windows-gnu'
        & cargo ndk b -t aarch64-linux-android -r
        if ($LASTEXITCODE -ne 0) { throw "ksud build failed" }
    } finally { $env:RUSTUP_TOOLCHAIN = $prevToolchain; Pop-Location }

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

# Guard two failure modes that silently ship a broken manager:
#   1. libksud.so missing        -> manager shows 0 modules.
#   2. CRLF in the embedded       -> ksud runs the module installer via
#      installer.sh                  `busybox sh -c`, and \r\n makes ash reject
#                                     `umask 022\r` ("illegal mode: 022") and
#                                     mangle loops -> EVERY module install fails
#                                     with "Failed to install module script".
# The include_str! installer is baked in at compile time, so a transiently-CRLF
# working tree (git autocrlf, a stray editor save) poisons the binary even when
# .gitattributes says `*.sh eol=lf`. Byte-check the shipped .so, not the source.
Add-Type -AssemblyName System.IO.Compression.FileSystem
# ISO-8859-1 (28591) maps each byte 1:1 to a char - safe for scanning binaries
# on Windows PowerShell 5.1, where [Text.Encoding]::Latin1 does not exist.
$latin1 = [System.Text.Encoding]::GetEncoding(28591)
$zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
try {
    $libs = $zip.Entries | Where-Object { $_.FullName -match '^lib/.+/libksud\.so$' }
    if (-not $libs) { throw "libksud.so missing from APK - manager would show 0 modules" }
    foreach ($lib in $libs) {
        $ms = New-Object System.IO.MemoryStream
        $st = $lib.Open(); $st.CopyTo($ms); $st.Close()
        $text = $latin1.GetString($ms.ToArray()); $ms.Dispose()
        if ($text.Contains("umask 022`r`n")) {
            throw ("CRLF embedded installer.sh in $($lib.FullName) - this ships a " +
                   "manager that fails EVERY module install ('umask illegal mode 022'). " +
                   "Fix the ksud checkout: git add --renormalize . ; confirm " +
                   "`git ls-files --eol` shows w/lf for src/**/*.sh, then rebuild ksud.")
        }
        if (-not $text.Contains("umask 022`n")) {
            throw "installer.sh 'umask 022' marker not found in $($lib.FullName) - build looks corrupt."
        }
        Write-Host "$($lib.FullName): present, installer.sh is LF ($($lib.Length) bytes)"
    }
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

    # The kernel only accepts manager certs it knows: the hardcoded upstream
    # ones, plus one "dynamic" signature. A self-signed build is not in that
    # list, so register it or the app loses manager status ("Non installe",
    # SuperUser count 0). Idempotent, and needed only when the signing key
    # changes or the in-kernel registration gets cleared - but re-asserting
    # each install costs nothing and removes a confusing failure mode.
    #
    # set-apk parses the installed base.apk directly; that only works because
    # we sign v2-only. ksud's parser rejects any v3 signature block.
    Step "Register manager signature (dynamic manager)"
    $pkgPath = (& $adb shell pm path com.resukisu.resukisu) -replace '^package:', '' -replace '\s', ''
    if (-not $pkgPath) {
        Write-Warning "Could not resolve installed APK path; skipping registration."
    } else {
        & $adb shell "su -c '/data/adb/ksud kernel dynamic-manager set-apk $pkgPath'"
        $reg = (& $adb shell "su -c '/data/adb/ksud kernel dynamic-manager get'") -join ' '
        if ($reg -match 'size:\s*\d+') {
            Write-Host "Registered -> $($reg.Trim())"
        } else {
            Write-Warning "Registration did not report a signature: $reg"
            Write-Warning "Manager may show 'Non installe' until this succeeds."
        }
    }
}

Write-Host "`nDone." -ForegroundColor Green
