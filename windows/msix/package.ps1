param(
    [string]$BuildDirectory = (Join-Path $PSScriptRoot '..\dist')
)

$ErrorActionPreference = 'Stop'
$BuildDirectory = (Resolve-Path -LiteralPath $BuildDirectory).Path
$windowsDirectory = Split-Path $PSScriptRoot
$repo = Split-Path $windowsDirectory
$sdkBin = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin" -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName 'x64' } |
    Where-Object { Test-Path (Join-Path $_ 'makeappx.exe') } |
    Select-Object -First 1
if (-not $sdkBin) { throw 'Install the Windows SDK to get MakeAppx.exe.' }

[xml]$manifest = Get-Content -Raw (Join-Path $PSScriptRoot 'AppxManifest.xml')
$version = $manifest.Package.Identity.Version
$package = Join-Path $BuildDirectory "A-Console-$version-Windows-x64.msix"
$stage = Join-Path ([System.IO.Path]::GetTempPath()) ("a-console-msix-" + [guid]::NewGuid())
try {
    New-Item -ItemType Directory -Path $stage | Out-Null
    Copy-Item -Path (Join-Path $BuildDirectory 'A Console\*') -Destination $stage -Recurse
    Copy-Item -LiteralPath (Join-Path $BuildDirectory 'bridge') -Destination $stage -Recurse
    New-Item -ItemType Directory -Path "$stage\tools", "$stage\tracks", "$stage\samples" | Out-Null
    Copy-Item -LiteralPath (Join-Path $BuildDirectory 'uv') -Destination "$stage\tools" -Recurse
    Copy-Item -LiteralPath "$repo\macos\Sources\UnRealConsole\Resources\default-track.kmz" -Destination "$stage\tracks"
    Copy-Item -LiteralPath "$repo\macos\Tests\Fixtures\sample.gpx" -Destination "$stage\samples"
    Copy-Item -LiteralPath "$repo\LICENSE", "$PSScriptRoot\AppxManifest.xml", "$PSScriptRoot\Assets" -Destination $stage -Recurse

    foreach ($file in 'A Console.exe', 'bridge\UnRealDeviceBridge.exe', 'tools\uv\uv.exe') {
        if (-not (Test-Path -LiteralPath (Join-Path $stage $file) -PathType Leaf)) {
            throw "Missing package payload: $file"
        }
    }
    & (Join-Path $sdkBin 'makeappx.exe') pack /d $stage /p $package /o
    if ($LASTEXITCODE -ne 0) { throw "MakeAppx failed with exit code $LASTEXITCODE" }
    $hash = (Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $(Split-Path $package -Leaf)" | Set-Content -Encoding ascii "$package.sha256"
} finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
