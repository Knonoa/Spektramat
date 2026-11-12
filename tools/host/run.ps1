# Host checks of the engine layer, compiled with MSVC from the Android sources. No device needed.
#   pwsh -NoProfile -File tools/host/run.ps1
param([string]$OutputDirectory = "")
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'check_photo_routes.ps1')
$project = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $project 'build\host' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$cpp = Join-Path $project 'engine\src\main\cpp'
$shared = Join-Path $project 'engine\src\main\shared\shaders'
$bin = Join-Path $project 'engine\src\main\assets\engine\engine.bin'
$objects = Join-Path $OutputDirectory 'objects'
New-Item -ItemType Directory -Force -Path $OutputDirectory, $objects | Out-Null
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$installation = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $installation) { throw 'Visual Studio C++ build tools are required' }
$vcvars = Join-Path $installation 'VC\Auxiliary\Build\vcvars64.bat'

function Build-Test([string]$Name, [string[]]$Sources) {
    $quoted = ($Sources | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $exe = Join-Path $OutputDirectory ($Name + '.exe')
    $command = '"' + $vcvars + '" >nul && cl /nologo /std:c++20 /utf-8 /MD /O2 /EHsc /W1 /D_USE_MATH_DEFINES /I "' + $cpp +
        '" /I "' + $shared + '" ' + $quoted + ' /Fo"' + $objects + '\\" /Fd"' + $OutputDirectory + '\compiler.pdb" /Fe"' + $exe + '"'
    & cmd.exe /d /c $command | Set-Content (Join-Path $OutputDirectory ($Name + '-build.txt'))
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $OutputDirectory ($Name + '-build.txt')); throw "Compilation failed: $Name" }
    return $exe
}

# lt_tables.cpp includes spektra_engine.cpp, so the engine is not listed on its own.
$engine = @('lt_tables.cpp', 'lt_fast.cpp', 'overscan.cpp', 'filters.cpp', 'cam16.cpp') | ForEach-Object { Join-Path $cpp $_ }

$exe = Build-Test 'fast-vs-engine' (@((Join-Path $PSScriptRoot 'fast_vs_engine.cpp')) + $engine)
# the edge print's faces, and a place for pictures of the film's edge to look at (PPM)
$fonts = Join-Path $project 'engine\src\main\assets\fonts'
$proofs = Join-Path $OutputDirectory 'proofs'
New-Item -ItemType Directory -Force -Path $proofs | Out-Null
& $exe $bin $fonts $proofs 2>&1 | Tee-Object (Join-Path $OutputDirectory 'fast-vs-engine.txt')
$fastCode = $LASTEXITCODE

if ($fastCode -ne 0) { exit 1 }

$photo = Build-Test 'photo-contract' (@((Join-Path $PSScriptRoot 'photo_contract.cpp')) + $engine)
& $photo $bin 2>&1 | Tee-Object (Join-Path $OutputDirectory 'photo-contract.txt')
if ($LASTEXITCODE -ne 0) { exit 1 }
