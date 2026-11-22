param([string]$OutputDirectory = '')
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $project 'build\creative-contract' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $OutputDirectory, (Join-Path $OutputDirectory 'objects') | Out-Null
$cpp = Join-Path $project 'engine\src\main\cpp'
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$installation = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $installation) { throw 'Visual Studio C++ build tools are required' }
$vcvars = Join-Path $installation 'VC\Auxiliary\Build\vcvars64.bat'
$sources = @((Join-Path $PSScriptRoot 'creative_contract.cpp'), (Join-Path $cpp 'creative_effects.cpp'), (Join-Path $cpp 'filters.cpp'))
$quoted = ($sources | ForEach-Object { '"' + $_ + '"' }) -join ' '
$exe = Join-Path $OutputDirectory 'creative-contract.exe'
$command = '"' + $vcvars + '" >nul && cl /nologo /std:c++20 /utf-8 /MD /O2 /EHsc /W1 /I "' + $cpp + '" ' + $quoted + ' /Fo"' + $OutputDirectory + '\objects\\" /Fe"' + $exe + '"'
& cmd.exe /d /c $command | Set-Content (Join-Path $OutputDirectory 'build.txt')
if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $OutputDirectory 'build.txt'); throw 'Creative contract compilation failed' }
& $exe 2>&1 | Tee-Object (Join-Path $OutputDirectory 'results.txt')
if ($LASTEXITCODE -ne 0) { throw 'Creative contract failed' }
