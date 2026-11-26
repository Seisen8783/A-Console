$ErrorActionPreference = 'Stop'

$version = '0.13.10'
$archiveName = 'uv-x86_64-pc-windows-msvc.zip'
$expectedHash = '297b3881fe1adfb922becc2c1613202abb41dc83ea5ff43d472aa087ca1f8683'
$staging = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString())
$destination = Join-Path $PSScriptRoot 'dist\uv'
New-Item -ItemType Directory -Path $staging, $destination -Force | Out-Null
try {
    $archive = Join-Path $staging $archiveName
    Invoke-WebRequest -UseBasicParsing "https://github.com/astral-sh/uv/releases/download/$version/$archiveName" -OutFile $archive
    if ((Get-FileHash $archive -Algorithm SHA256).Hash -ne $expectedHash) {
        throw "Unexpected SHA-256 for $archiveName"
    }
    Expand-Archive -Path $archive -DestinationPath $staging
    $uv = Get-ChildItem -LiteralPath $staging -Filter uv.exe -Recurse -File
    Copy-Item -LiteralPath $uv.FullName -Destination $destination
    foreach ($license in 'LICENSE-APACHE', 'LICENSE-MIT') {
        Invoke-WebRequest -UseBasicParsing "https://raw.githubusercontent.com/astral-sh/uv/$version/$license" -OutFile (Join-Path $destination $license)
    }
} finally {
    Remove-Item -LiteralPath $staging -Recurse -Force
}
