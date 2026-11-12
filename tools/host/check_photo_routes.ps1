# Route guard: a photo result must enter the exact engine, never a table renderer.
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$source = Join-Path $project 'app\src\main\java'
$bad = Get-ChildItem -LiteralPath $source -Filter '*.kt' -Recurse |
    Select-String -Pattern 'FilmEngine\.renderFast\s*\('
if ($bad) {
    $bad | ForEach-Object { Write-Error "$($_.Path):$($_.LineNumber): photo source calls renderFast" }
    exit 1
}

$photos = @(
    'com\spektra\latent\data\Printer.kt',
    'com\spektra\latent\data\Library.kt',
    'com\spektra\latent\data\PrintDesk.kt',
    'com\spektra\latent\tether\Monitor.kt',
    'com\spektra\latent\ui\darkroom\DarkroomState.kt'
)
foreach ($relative in $photos) {
    $file = Join-Path $source $relative
    $text = Get-Content -LiteralPath $file -Raw
    if ($text -notmatch 'FilmEngine\.develop\s*\(') { throw "$relative has no full-engine photo route" }
    if ($text -match 'FilmTables\s*\(|PreviewLut\.bake\s*\(' -or
        ($relative -notlike '*Monitor.kt' -and $text -match 'VideoLut\.bake\s*\(')) {
        throw "$relative constructs a table renderer for photographs"
    }
}

$compare = Get-Content -LiteralPath (Join-Path $source 'com\spektra\latent\gl\FilmPreview.kt') -Raw
if ($compare -match 'FilmTables\s*\(|FilmGpu\s*\(') {
    throw 'The original-photo comparison display must not construct film LUTs'
}

$camera = Get-Content -LiteralPath (Join-Path $source 'com\spektra\latent\camera\CameraRig.kt') -Raw
if ($camera -match 'FilmEffect\([^\r\n]*CameraEffect\.(PREVIEW|IMAGE_CAPTURE)') {
    throw 'The LUT CameraEffect must target video only; viewfinder uses a plain SurfaceProvider for RAW compatibility'
}
if ($camera -match 'startPhotoPreview|snapshotFrame|photoPreview|VideoLut\.bake') {
    throw 'CPU snapshot or video LUT fallback must not render the live photo viewfinder'
}
if ($camera -notmatch 'setSurfaceProvider\(GlCore\.executor, film\.direct\)') {
    throw 'Camera preview must feed its texture directly to the GPU SurfaceProvider'
}
$film = Get-Content -LiteralPath (Join-Path $source 'com\spektra\latent\gl\CameraFilm.kt') -Raw
if ($film -notmatch 'PreviewLut\.bake' -or $film -notmatch 'targets == CameraEffect\.VIDEO_CAPTURE') {
    throw 'The isolated preview cube and recording-only effect guard are required'
}
Write-Output 'PHOTO_ROUTE_FAILURES 0'
