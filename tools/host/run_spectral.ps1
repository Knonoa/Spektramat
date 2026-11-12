param([string]$Python='python')
$ErrorActionPreference='Stop'
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$output=Join-Path $project 'build\spectral-input-contract'
New-Item -ItemType Directory -Force $output | Out-Null
$vswhere="${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$installation=& $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if(-not $installation){throw 'Visual Studio C++ build tools required'}
$vcvars=Join-Path $installation 'VC\Auxiliary\Build\vcvars64.bat'
$command='"'+$vcvars+'" >nul && cl /nologo /std:c++20 /utf-8 /MD /O2 /EHsc /W1 /I "'+(Join-Path $project 'engine\src\main\cpp')+'" "'+(Join-Path $PSScriptRoot 'spectral_input_contract.cpp')+'" /Fo"'+$output+'\test.obj" /Fe"'+$output+'\test.exe"'
& cmd.exe /d /c $command
if($LASTEXITCODE -ne 0){throw 'Spectral contract compilation failed'}
& (Join-Path $output 'test.exe') (Join-Path $project 'engine\src\main\assets\engine\engine.bin') $output
if($LASTEXITCODE -ne 0){throw 'Spectral contract failed'}
$savedPath=$env:PYTHONPATH
try {
    $env:PYTHONPATH=Join-Path (Split-Path -Parent $project) 'upstream\spektrafilm\src'
    & $Python (Join-Path $PSScriptRoot 'spectral_input_oracle.py') $output
    if($LASTEXITCODE -ne 0){throw 'Spectral setup differs from reference'}
}finally{$env:PYTHONPATH=$savedPath}
