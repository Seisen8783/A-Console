$ErrorActionPreference = 'Stop'
[xml]$manifest = Get-Content -Raw (Join-Path $PSScriptRoot 'AppxManifest.xml')
$identity = $manifest.Package.Identity
$packageName = $identity.Name
if (Get-AppxPackage -Name $packageName) { throw "Remove the existing test installation of $packageName first." }

$source = Get-Item -LiteralPath (Join-Path $PSScriptRoot "..\dist\A-Console-$($identity.Version)-Windows-x64.msix")
$signTool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" |
    Sort-Object { [version]$_.Directory.Parent.Name } -Descending |
    Select-Object -First 1
if (-not $signTool) { throw 'The Windows SDK SignTool was not found.' }

$testDir = Join-Path $env:TEMP ("a-console-msix-test-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testDir | Out-Null
try {
    $testPackage = Copy-Item -LiteralPath $source.FullName -Destination $testDir -PassThru
    $certificate = New-SelfSignedCertificate -Type CodeSigningCert -Subject $identity.Publisher -CertStoreLocation 'Cert:\CurrentUser\My' -NotAfter (Get-Date).AddDays(1)
    $certFile = Join-Path $testDir 'test.cer'
    Export-Certificate -Cert $certificate -FilePath $certFile | Out-Null
    Import-Certificate -FilePath $certFile -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null
    & $signTool.FullName sign /fd SHA256 /sha1 $certificate.Thumbprint $testPackage.FullName
    if ($LASTEXITCODE -ne 0) { throw 'Could not sign the test copy.' }

    Add-AppxPackage -Path $testPackage.FullName
    $installed = Get-AppxPackage -Name $packageName
    if (-not $installed -or $installed.Version -ne [version]$identity.Version) {
        throw 'Windows did not register the expected package version.'
    }

    $appId = "$($installed.PackageFamilyName)!$($manifest.Package.Applications.Application.Id)"
    Start-Process explorer.exe -ArgumentList "shell:AppsFolder\$appId"
    $deadline = (Get-Date).AddSeconds(45)
    do {
        $window = Get-Process -Name 'A Console' -ErrorAction SilentlyContinue |
            Where-Object { $_.MainWindowTitle -like 'A Console*' } |
            Select-Object -First 1
        if ($window) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    if (-not $window) { throw 'The installed MSIX did not show an A Console window.' }
    Write-Host "Launched packaged app: $($window.MainWindowTitle)"
    if (-not $window.CloseMainWindow() -or -not $window.WaitForExit(15000)) {
        throw 'The packaged application did not exit normally.'
    }

    Remove-AppxPackage -Package $installed.PackageFullName
    if (Get-AppxPackage -Name $packageName) { throw 'The package is still installed.' }
    Write-Host "Installed, launched and uninstalled $appId"
} finally {
    if ($window -and -not $window.HasExited) { Stop-Process -Id $window.Id -Force }
    Get-AppxPackage -Name $packageName | ForEach-Object { Remove-AppxPackage -Package $_.PackageFullName }
    if ($certificate) {
        Remove-Item "Cert:\CurrentUser\My\$($certificate.Thumbprint)" -ErrorAction SilentlyContinue
        Remove-Item "Cert:\LocalMachine\TrustedPeople\$($certificate.Thumbprint)" -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $testDir -Recurse -Force
}
