[CmdletBinding()]
param(
    [string]$ProjectRoot = '',
    [string]$Version = '0.1.7'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

& (Join-Path $ProjectRoot 'tools\Audit-DesktopLocalization.ps1')

function Assert-File {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required distribution file is missing: $Path"
    }
    $item = Get-Item -LiteralPath $Path
    if ($item.Length -le 0) {
        throw "Required distribution file is empty: $Path"
    }
    return $item
}

function Test-NativeHostFrame {
    param([Parameter(Mandatory)][string]$Executable)
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = [Diagnostics.ProcessStartInfo]::new()
    $process.StartInfo.FileName = $Executable
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardInput = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    try {
        if (-not $process.Start()) { throw 'Native host process did not start.' }
        $payload = [Text.Encoding]::UTF8.GetBytes('{"id":"distribution-smoke","type":"status"}')
        $header = [BitConverter]::GetBytes([int]$payload.Length)
        $process.StandardInput.BaseStream.Write($header, 0, $header.Length)
        $process.StandardInput.BaseStream.Write($payload, 0, $payload.Length)
        $process.StandardInput.BaseStream.Flush()

        $responseHeader = [byte[]]::new(4)
        $headerRead = $process.StandardOutput.BaseStream.ReadAsync($responseHeader, 0, 4)
        if (-not $headerRead.Wait(5000) -or $headerRead.Result -ne 4) {
            throw 'Bundled native host did not return a complete frame header within five seconds.'
        }
        $responseLength = [BitConverter]::ToInt32($responseHeader, 0)
        if ($responseLength -le 0 -or $responseLength -gt 65536) { throw "Invalid native host response length: $responseLength" }
        $responseBytes = [byte[]]::new($responseLength)
        $offset = 0
        while ($offset -lt $responseLength) {
            $read = $process.StandardOutput.BaseStream.ReadAsync($responseBytes, $offset, $responseLength - $offset)
            if (-not $read.Wait(5000) -or $read.Result -le 0) { throw 'Bundled native host returned a truncated frame.' }
            $offset += $read.Result
        }
        $response = [Text.Encoding]::UTF8.GetString($responseBytes) | ConvertFrom-Json
        if ($response.id -ne 'distribution-smoke' -or $response.ok -ne $false -or $response.error -ne 'DENIED') {
            throw 'Bundled native host returned an unexpected unauthenticated smoke-test response.'
        }
    }
    finally {
        if ($null -ne $process.StandardInput) { $process.StandardInput.Close() }
        if (-not $process.HasExited -and -not $process.WaitForExit(3000)) { $process.Kill() }
        $process.Dispose()
    }
}

function Test-NativeHostManifestGeneration {
    param(
        [Parameter(Mandatory)][string]$InstallScript,
        [Parameter(Mandatory)][string]$HostExecutable
    )
    $testRoot = Join-Path ([IO.Path]::GetTempPath()) ("keyscan-native-manifest-" + [Guid]::NewGuid().ToString('N'))
    try {
        & $InstallScript -HostExecutable $HostExecutable `
            -ChromeExtensionId ('a' * 32) -EdgeExtensionId ('b' * 32) -BraveExtensionId ('c' * 32) `
            -FirefoxExtensionId 'keyscan@keyscan.app' -ConfigurationDirectory $testRoot -SkipRegistry
        $chromium = Get-Content -LiteralPath (Join-Path $testRoot 'native-messaging\com.keyscan.desktop.chromium.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        $firefox = Get-Content -LiteralPath (Join-Path $testRoot 'native-messaging\com.keyscan.desktop.firefox.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        $allowList = @(Get-Content -LiteralPath (Join-Path $testRoot 'native-host-allowlist.txt') -Encoding UTF8)
        $expectedOrigins = @(
            ('chrome-extension://' + ('a' * 32) + '/')
            ('chrome-extension://' + ('b' * 32) + '/')
            ('chrome-extension://' + ('c' * 32) + '/')
        )
        if (@($chromium.allowed_origins).Count -ne 3 -or @($firefox.allowed_extensions).Count -ne 1) { throw 'Generated native-host manifests have an invalid allow-list size.' }
        foreach ($origin in $expectedOrigins) { if ($origin -notin @($chromium.allowed_origins) -or $origin -notin $allowList) { throw "Generated native-host manifest is missing $origin" } }
        if ('keyscan@keyscan.app' -notin @($firefox.allowed_extensions) -or 'keyscan@keyscan.app' -notin $allowList) { throw 'Generated Firefox allow-list is invalid.' }
        if ($chromium.path -ne $HostExecutable -or $firefox.path -ne $HostExecutable) { throw 'Generated native-host manifest points to the wrong executable.' }
    }
    finally {
        if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
    }
}

$composeRoot = Join-Path $ProjectRoot 'desktop-ui\build\compose\binaries\main'
$appRoot = Join-Path $composeRoot 'app\KeyScan'
$appDir = Join-Path $appRoot 'app'
$resourcesDir = Join-Path $appDir 'resources'
$releaseDir = Join-Path $ProjectRoot 'build\release\windows'

$required = @(
    (Join-Path $appRoot 'KeyScan.exe'),
    (Join-Path $appRoot 'runtime\bin\jli.dll'),
    # Compose copies the self-contained native host as one archive. Keeping it
    # compressed avoids putting its JARs on the desktop application's classpath.
    (Join-Path $resourcesDir 'KeyScanNativeHost.zip'),
    (Join-Path $resourcesDir 'Install-KeyScanNativeHost.ps1'),
    (Join-Path $resourcesDir 'Uninstall-KeyScanNativeHost.ps1'),
    (Join-Path $composeRoot "exe\KeyScan-$Version.exe"),
    (Join-Path $composeRoot "msi\KeyScan-$Version.msi")
)

$requiredItems = foreach ($path in $required) { Assert-File -Path $path }
$nativeHostExtraction = Join-Path ([IO.Path]::GetTempPath()) ("keyscan-native-host-package-" + [Guid]::NewGuid().ToString('N'))
try {
    Expand-Archive -LiteralPath (Join-Path $resourcesDir 'KeyScanNativeHost.zip') -DestinationPath $nativeHostExtraction -Force
    $bundledNativeHost = Assert-File -Path (Join-Path $nativeHostExtraction 'KeyScanNativeHost.exe')
    Assert-File -Path (Join-Path $nativeHostExtraction 'runtime\bin\jli.dll') | Out-Null
    Test-NativeHostFrame -Executable $bundledNativeHost.FullName
    Test-NativeHostManifestGeneration -InstallScript (Join-Path $resourcesDir 'Install-KeyScanNativeHost.ps1') -HostExecutable $bundledNativeHost.FullName
    $nativeHostJar = Get-ChildItem -LiteralPath (Join-Path $nativeHostExtraction 'app') -Filter 'native-host-*.jar' -File | Select-Object -First 1
    if ($null -eq $nativeHostJar) { throw 'native-host JAR is missing from the bundled native-host archive.' }
}
finally {
    # A just-stopped jpackage process can briefly retain jvm.dll on Windows.
    # Cleanup must never turn a successful package verification into a failure.
    if (Test-Path -LiteralPath $nativeHostExtraction) { Remove-Item -LiteralPath $nativeHostExtraction -Recurse -Force -ErrorAction SilentlyContinue }
}

$desktopJar = Get-ChildItem -LiteralPath $appDir -Filter 'desktop-ui-*.jar' -File | Select-Object -First 1
if ($null -eq $desktopJar) { throw "desktop-ui JAR is missing from $appDir" }

$jarTool = Join-Path $env:JAVA_HOME 'bin\jar.exe'
$jimageTool = Join-Path $env:JAVA_HOME 'bin\jimage.exe'
if (-not (Test-Path -LiteralPath $jarTool -PathType Leaf)) {
    throw 'JAVA_HOME must point to JDK 17 so the bundled Windows Hello resource can be inspected.'
}
if (-not (Test-Path -LiteralPath $jimageTool -PathType Leaf)) {
    throw 'JAVA_HOME must point to a JDK containing jimage.exe so the packaged runtime modules can be inspected.'
}
$jarEntries = & $jarTool tf $desktopJar.FullName
if ($LASTEXITCODE -ne 0 -or $jarEntries -notcontains 'native/KeyScanWindowsHello.exe') {
    throw 'The desktop application JAR does not contain native/KeyScanWindowsHello.exe.'
}

$runtimeImage = Join-Path $appRoot 'runtime\lib\modules'
$runtimeEntries = & $jimageTool list $runtimeImage
if ($LASTEXITCODE -ne 0 -or -not ($runtimeEntries | Select-String -SimpleMatch 'java/net/http/HttpClient.class' -Quiet)) {
    throw 'The packaged desktop runtime does not contain java.net.http; WebDAV would fail after installation.'
}

New-Item -ItemType Directory -Path $releaseDir -Force | Out-Null
$artifacts = @(
    (Join-Path $composeRoot "exe\KeyScan-$Version.exe"),
    (Join-Path $composeRoot "msi\KeyScan-$Version.msi")
)
$checksumPath = Join-Path $releaseDir 'SHA256SUMS.txt'
$checksumLines = foreach ($artifact in $artifacts) {
    $hash = Get-FileHash -LiteralPath $artifact -Algorithm SHA256
    '{0}  {1}' -f $hash.Hash.ToLowerInvariant(), (Split-Path -Leaf $artifact)
}
[IO.File]::WriteAllLines($checksumPath, $checksumLines, [Text.UTF8Encoding]::new($false))

Write-Host 'Windows distribution verification passed.'
Write-Host '  Bundled native-host archive framing smoke test: passed (unauthenticated request denied)'
Write-Host '  Chrome/Edge/Brave/Firefox native-host manifest allow-list generation: passed'
Write-Host '  Packaged java.net.http runtime module: present'
foreach ($item in $requiredItems) {
    Write-Host ('  {0} ({1:N0} bytes)' -f $item.FullName, $item.Length)
}
Write-Host ('  {0} ({1:N0} bytes)' -f $desktopJar.FullName, $desktopJar.Length)
Write-Host ('  {0} ({1:N0} bytes)' -f $nativeHostJar.FullName, $nativeHostJar.Length)
Write-Host "  SHA-256 manifest: $checksumPath"
