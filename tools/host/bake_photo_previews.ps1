param([string]$OutputDirectory = '')
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $project 'build\bundled-photo-previews' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $OutputDirectory, (Join-Path $OutputDirectory 'objects') | Out-Null
$cpp = Join-Path $project 'engine\src\main\cpp'
$shared = Join-Path $project 'engine\src\main\shared\shaders'
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$installation = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $installation) { throw 'Visual Studio C++ build tools are required' }
$vcvars = Join-Path $installation 'VC\Auxiliary\Build\vcvars64.bat'
$sources = @((Join-Path $PSScriptRoot 'bake_photo_previews.cpp')) + (@('lt_tables.cpp', 'lt_fast.cpp', 'overscan.cpp', 'filters.cpp', 'cam16.cpp') | ForEach-Object { Join-Path $cpp $_ })
$quoted = ($sources | ForEach-Object { '"' + $_ + '"' }) -join ' '
$exe = Join-Path $OutputDirectory 'bake-photo-previews.exe'
$command = '"' + $vcvars + '" >nul && cl /nologo /std:c++20 /utf-8 /MD /O2 /EHsc /W1 /D_USE_MATH_DEFINES /I "' + $cpp + '" /I "' + $shared + '" ' + $quoted + ' /Fo"' + $OutputDirectory + '\objects\\" /Fe"' + $exe + '"'
& cmd.exe /d /c $command | Set-Content (Join-Path $OutputDirectory 'build.txt')
if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $OutputDirectory 'build.txt'); throw 'Default photo cube compilation failed' }
$staging = Join-Path $OutputDirectory 'assets'
New-Item -ItemType Directory -Force -Path $staging | Out-Null
$engine = Join-Path $project 'engine\src\main\assets\engine\engine.bin'
& $exe $engine $staging 2>&1 | Tee-Object (Join-Path $OutputDirectory 'results.txt')
if ($LASTEXITCODE -ne 0) { throw 'Default photo cube bake failed' }
$entries = @(Get-Content (Join-Path $staging 'entries.tsv') | ForEach-Object {
    $fields = $_ -split "`t"
    [ordered]@{ film=$fields[0]; paper=$fields[1]; scan=($fields[2] -eq 'true'); white=$fields[3]; file=$fields[4]; sha256=(Get-FileHash -LiteralPath (Join-Path $staging $fields[4]) -Algorithm SHA256).Hash.ToLowerInvariant() }
})
$engineHash = (Get-FileHash -LiteralPath $engine -Algorithm SHA256).Hash.ToLowerInvariant()
$engineSource = Get-Content (Join-Path $project 'engine\src\main\java\com\spektra\latent\engine\FilmEngine.kt') -Raw
$version = [regex]::Match($engineSource, 'var renderCacheKey: String = "([^"]+)"').Groups[1].Value
if (-not $version) { throw 'Could not read engine render-cache version' }
$manifest = [ordered]@{
    schema=1; engineCacheKey="$version-$($engineHash.Substring(0,12))"; engineAssetSha256=$engineHash
    size=33; type='float32-le'; shaper='photo-signed-log-v1'; license='CC-BY-SA-4.0'
    sources=@('https://github.com/andreavolpato/spektrafilm', 'https://github.com/JamesQiu2005/SpektraLab', 'https://github.com/Knonoa/Spektramat')
    generator='tools/host/bake_photo_previews.ps1'; entries=$entries
}
$destination = Join-Path $project 'engine\src\main\assets\photo-previews'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
foreach ($entry in $entries) { Copy-Item -LiteralPath (Join-Path $staging $entry.file) -Destination $destination -Force }
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destination 'manifest.json') -Encoding utf8
Write-Output "Bundled $($entries.Count) default photo LUTs ($engineHash)"
