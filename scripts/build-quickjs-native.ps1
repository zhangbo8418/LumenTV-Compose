# Build quickjs-java-wrapper.dll for Windows x64 with Win7 SP1 target.
#
# Usage:
#   pwsh scripts/build-quickjs-native.ps1 [-Platform windows-x64]
#
# Requires: git, cmake, MinGW-w64 (gcc), JAVA_HOME with include/win32/jni_md.h
#
# Win7 constraints:
#   -D_WIN32_WINNT=0x0601 -DWINVER=0x0601
#   -static-libgcc -static-libstdc++
#   dependency gate rejects VCRuntime / Win8+ API sets

param(
    [string]$Platform = "windows-x64"
)

$ErrorActionPreference = "Stop"
$QuickJsTag = if ($env:QUICKJS_TAG) { $env:QUICKJS_TAG } else { "3.2.3" }

if ($Platform -ne "windows-x64") {
    throw "This script only supports windows-x64 (got: $Platform). Use build-quickjs-native.sh for other OSes."
}

$RepoRoot = if ($env:REPO_ROOT) {
    $env:REPO_ROOT
} else {
    (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME is not set"
}
$JniH = Join-Path $env:JAVA_HOME "include/jni.h"
$JniMd = Join-Path $env:JAVA_HOME "include/win32/jni_md.h"
if (-not (Test-Path $JniH)) { throw "jni.h not found: $JniH" }
if (-not (Test-Path $JniMd)) { throw "jni_md.h not found: $JniMd" }

function Require-Cmd($name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $name"
    }
}
Require-Cmd git
Require-Cmd cmake
Require-Cmd gcc

$OutDir = Join-Path $RepoRoot "composeApp/src/desktopMain/appResources/$Platform/lib"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$Work = Join-Path ([System.IO.Path]::GetTempPath()) ("quickjs-native-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $Work | Out-Null
try {
    $SrcRoot = Join-Path $Work "src"
    Write-Host "==> Cloning HarlonWang/quickjs-wrapper@$QuickJsTag"
    git clone --depth 1 --branch $QuickJsTag --recursive `
        https://github.com/HarlonWang/quickjs-wrapper.git $SrcRoot
    if ($LASTEXITCODE -ne 0) { throw "git clone failed" }

    # Upstream CMakeLists only includes darwin/linux JNI dirs — patch in win32.
    $CmakeLists = Join-Path $SrcRoot "wrapper-java/src/main/CMakeLists.txt"
    $cmakeText = Get-Content -Raw $CmakeLists
    if ($cmakeText -notmatch "include/win32") {
        $cmakeText = $cmakeText + "`ninclude_directories(`$ENV{JAVA_HOME}/include/win32)`n"
        Set-Content -Path $CmakeLists -Value $cmakeText -NoNewline
    }

    $Build = Join-Path $Work "build"
    New-Item -ItemType Directory -Force -Path $Build | Out-Null

    $Win7Defs = "-D_WIN32_WINNT=0x0601 -DWINVER=0x0601"
    $JniInc = "-I`"$($env:JAVA_HOME)/include`" -I`"$($env:JAVA_HOME)/include/win32`""
    $StaticRt = "-static-libgcc -static-libstdc++"
    $CFlags = "$Win7Defs $JniInc $StaticRt"
    $CxxFlags = "$Win7Defs $JniInc $StaticRt"
    $LdFlags = "-static-libgcc -static-libstdc++ -Wl,-Bstatic -lwinpthread -Wl,-Bdynamic -Wl,--subsystem,windows:6.01"

    $Generator = "MinGW Makefiles"
    if (Get-Command ninja -ErrorAction SilentlyContinue) {
        $Generator = "Ninja"
    }

    Write-Host "==> cmake configure (Win7 MinGW, $Generator)"
    $cmakeArgs = @(
        "-G", $Generator,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_C_COMPILER=gcc",
        "-DCMAKE_CXX_COMPILER=g++",
        "-DCMAKE_C_FLAGS=$CFlags",
        "-DCMAKE_CXX_FLAGS=$CxxFlags",
        "-DCMAKE_SHARED_LINKER_FLAGS=$LdFlags",
        "-S", (Join-Path $SrcRoot "wrapper-java/src/main"),
        "-B", $Build
    )
    & cmake @cmakeArgs
    if ($LASTEXITCODE -ne 0) { throw "cmake configure failed" }

    Write-Host "==> cmake build"
    & cmake --build $Build --target quickjs-java-wrapper -j 4
    if ($LASTEXITCODE -ne 0) { throw "cmake build failed" }

    # 运行时/workflow 期望不带 lib 前缀；MinGW+Ninja 常产出 libquickjs-java-wrapper.dll
    $LibName = "quickjs-java-wrapper.dll"
    $Built = $null
    foreach ($cand in @(
            (Join-Path $Build $LibName),
            (Join-Path $Build "lib/$LibName"),
            (Join-Path $Build "Release/$LibName"),
            (Join-Path $Build "lib$LibName"),
            (Join-Path $Build "lib/lib$LibName"),
            (Join-Path $Build "Release/lib$LibName")
        )) {
        if (Test-Path $cand) { $Built = $cand; break }
    }
    if (-not $Built) {
        $Built = Get-ChildItem -Recurse $Build -Filter "*quickjs-java-wrapper.dll" -ErrorAction SilentlyContinue |
            Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $Built) {
        Get-ChildItem -Recurse $Build -Filter "*.dll" | ForEach-Object { Write-Host $_.FullName }
        throw "Built DLL not found: $LibName (also tried lib$LibName)"
    }
    Write-Host "==> Found built DLL: $Built"

    # --- Win7 dependency gate ---
    Write-Host "==> Scanning DLL dependents (Win7 gate)"
    $depsText = ""
    if (Get-Command dumpbin -ErrorAction SilentlyContinue) {
        $depsText = & dumpbin /DEPENDENTS $Built 2>&1 | Out-String
    } elseif (Get-Command objdump -ErrorAction SilentlyContinue) {
        $depsText = & objdump -p $Built 2>&1 | Out-String
    } else {
        Write-Warning "Neither dumpbin nor objdump found; skipping detailed scan (still built with Win7 flags)."
        $depsText = ""
    }

    if ($depsText) {
        Write-Host $depsText
        $forbidden = @(
            "VCRUNTIME",
            "MSVCP",
            "MSVCR",
            "CONCRT",
            "api-ms-win-core-path-",      # Win10+ path APIs often pulled by new UCRT
            "api-ms-win-core-libraryloader-l1-2-", # newer loader API set
            "api-ms-win-crt-private",
            "KERNELBASE.dll"  # soft signal — MinGW rarely links this directly; keep as warn only below
        )
        # Hard-fail patterns (case-insensitive)
        $hardFail = @(
            "VCRUNTIME140",
            "VCRUNTIME140_1",
            "MSVCP140",
            "MSVCR1",
            "CONCRT140",
            "api-ms-win-core-path-l1-1-0"
        )
        foreach ($pat in $hardFail) {
            if ($depsText -match [regex]::Escape($pat)) {
                throw "Win7 gate failed: DLL depends on '$pat' (not acceptable for Win7 SP1 static build)."
            }
        }
        # Soft: warn on unexpected api-ms-win-* beyond common CRT stubs
        $apiMatches = [regex]::Matches($depsText, "api-ms-win-[A-Za-z0-9\-]+\.dll", "IgnoreCase")
        foreach ($m in $apiMatches) {
            $name = $m.Value.ToLowerInvariant()
            # Allow nothing by default from api-ms-win for MinGW static — flag all
            Write-Warning "Win7 gate: unexpected API set dependency: $($m.Value)"
            if ($name -match "core-path|core-realtime|core-winrt|appmodel") {
                throw "Win7 gate failed: disallowed API set $($m.Value)"
            }
        }
        Write-Host "==> Win7 dependency gate passed"
    }

    $Dest = Join-Path $OutDir $LibName
    Copy-Item -Force $Built $Dest
    Write-Host "==> Installed $Dest"
    Get-Item $Dest | Format-List FullName, Length, LastWriteTime
}
finally {
    if (Test-Path $Work) {
        Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
    }
}
