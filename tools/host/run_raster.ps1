# Native raster precision contract. Windows host; MSVC and Android CMake required.
param([string]$Fixtures = "")
$ErrorActionPreference='Stop'
$project=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if(-not $Fixtures){$Fixtures=Join-Path $project 'build\raster-fixtures'}
$cmake=Join-Path $env:LOCALAPPDATA 'Android\Sdk\cmake\3.22.1\bin\cmake.exe'
$build=Join-Path $project 'build\raster-host-contract'
if(-not (Test-Path $cmake)){throw 'Android CMake 3.22.1 is required'}
& $cmake -S (Join-Path $PSScriptRoot 'raster') -B $build -G 'Visual Studio 17 2022' -A x64
if($LASTEXITCODE){throw 'raster host configuration failed'}
& $cmake --build $build --target raster-test raw-precision-test --config Release --parallel 4
if($LASTEXITCODE){throw 'raster host build failed'}
& (Join-Path $build 'Release\raster-test.exe') $Fixtures
if($LASTEXITCODE){throw 'raster precision contract failed'}
& (Join-Path $build 'Release\raw-precision-test.exe') $Fixtures
if($LASTEXITCODE){throw 'RAW precision contract failed'}
