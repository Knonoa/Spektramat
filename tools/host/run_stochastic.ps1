param([string]$Python='python')
$ErrorActionPreference='Stop'
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$output=Join-Path $project 'build\stochastic-parity'
New-Item -ItemType Directory -Force $output | Out-Null
$vswhere="${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$installation=& $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if(-not $installation){throw 'Visual Studio C++ build tools required'}
$vcvars=Join-Path $installation 'VC\Auxiliary\Build\vcvars64.bat'
$cpp=Join-Path $project 'engine\src\main\cpp'
$sources=@((Join-Path $PSScriptRoot 'stochastic_contract.cpp'))+(@('overscan.cpp','filters.cpp','cam16.cpp')|ForEach-Object{Join-Path $cpp $_})
$quoted=($sources|ForEach-Object{'"'+$_+'"'})-join ' '
$command='"'+$vcvars+'" >nul && cl /nologo /std:c++20 /utf-8 /MD /O2 /EHsc /W1 /D_USE_MATH_DEFINES /I "'+$cpp+'" '+$quoted+' /Fo"'+$output+'\\" /Fe"'+$output+'\test.exe"'
& cmd.exe /d /c $command
if($LASTEXITCODE -ne 0){throw 'Stochastic contract compilation failed'}
& (Join-Path $output 'test.exe') (Join-Path $project 'engine\src\main\assets\engine\engine.bin') $output
if($LASTEXITCODE -ne 0){throw 'Stochastic contract failed'}
$savedPath=$env:PYTHONPATH
$savedCache=$env:NUMBA_CACHE_DIR
try {
    $env:PYTHONPATH=Join-Path (Split-Path -Parent $project) 'upstream\spektrafilm\src'
    $env:NUMBA_CACHE_DIR=Join-Path $output 'numba'
    & $Python (Join-Path $PSScriptRoot 'stochastic_oracle.py') $output
    if($LASTEXITCODE -ne 0){throw 'Stochastic model differs from reference'}
}finally{$env:PYTHONPATH=$savedPath;$env:NUMBA_CACHE_DIR=$savedCache}
