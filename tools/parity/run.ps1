# Runs exact reference tap parity. Requires MSVC and Python with the spektrafilm runtime dependencies.
param(
    [string]$SpektrafilmSource = '',
    [string]$SpektraLabEngine = '',
    [string]$Python = 'python',
    [string]$OutputDirectory = '',
    [string[]]$Cases = @()
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$workspace = Split-Path -Parent $project
if (-not $SpektrafilmSource) { $SpektrafilmSource = Join-Path $workspace 'upstream\spektrafilm\src' }
if (-not $SpektraLabEngine) { $SpektraLabEngine = Join-Path $workspace 'upstream\SpektraLab\engine' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $project 'build\reference-parity' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (-not (Test-Path -LiteralPath (Join-Path $SpektrafilmSource 'spektrafilm\runtime'))) { throw 'SpektrafilmSource must contain spektrafilm/runtime' }
if (-not (Test-Path -LiteralPath (Join-Path $SpektraLabEngine 'resources\profiles'))) { throw 'SpektraLabEngine must contain resources/profiles' }
New-Item -ItemType Directory -Force -Path $OutputDirectory, (Join-Path $OutputDirectory 'objects') | Out-Null

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$installation = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $installation) { throw 'Visual Studio C++ build tools are required' }
$vcvars = Join-Path $installation 'VC\Auxiliary\Build\vcvars64.bat'
$cpp = Join-Path $project 'engine\src\main\cpp'
$sources = @((Join-Path $PSScriptRoot 'host_parity.cpp')) + (@('spektra_engine.cpp', 'overscan.cpp', 'filters.cpp', 'cam16.cpp') | ForEach-Object { Join-Path $cpp $_ })
$quoted = ($sources | ForEach-Object { '"' + $_ + '"' }) -join ' '
$exe = Join-Path $OutputDirectory 'host_parity.exe'
$command = '"' + $vcvars + '" >nul && cl /nologo /std:c++20 /utf-8 /MD /O2 /EHsc /W1 /D_USE_MATH_DEFINES /I "' + $cpp + '" ' + $quoted + ' /Fo"' + $OutputDirectory + '\objects\\" /Fe"' + $exe + '"'
& cmd.exe /d /c $command | Set-Content (Join-Path $OutputDirectory 'build.txt')
if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $OutputDirectory 'build.txt'); throw 'Parity host compilation failed' }

$savedPath = $env:PYTHONPATH
$savedLab = $env:SPEKTRALAB_ENGINE
$savedCache = $env:NUMBA_CACHE_DIR
$savedBytecode = $env:PYTHONDONTWRITEBYTECODE
try {
    $env:PYTHONPATH = $SpektrafilmSource
    $env:SPEKTRALAB_ENGINE = $SpektraLabEngine
    $env:NUMBA_CACHE_DIR = Join-Path $OutputDirectory 'numba'
    $env:PYTHONDONTWRITEBYTECODE = '1'
    $caseArguments = @()
    foreach ($case in $Cases) {
        foreach ($name in $case.Split(',', [StringSplitOptions]::RemoveEmptyEntries)) {
            $caseArguments += @('--case', $name.Trim())
        }
    }
    & $Python (Join-Path $PSScriptRoot 'run.py') --output $OutputDirectory --host $exe @caseArguments
    if ($LASTEXITCODE -ne 0) { throw 'Reference parity failed; inspect results.json and per-case run.log' }
} finally {
    $env:PYTHONPATH = $savedPath
    $env:SPEKTRALAB_ENGINE = $savedLab
    $env:NUMBA_CACHE_DIR = $savedCache
    $env:PYTHONDONTWRITEBYTECODE = $savedBytecode
}
