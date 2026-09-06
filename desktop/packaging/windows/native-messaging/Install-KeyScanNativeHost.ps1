[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)] [string] $HostExecutable,
    [Parameter(Mandatory)] [ValidatePattern('^[a-p]{32}$')] [string] $ChromeExtensionId,
    [Parameter(Mandatory)] [ValidatePattern('^[a-p]{32}$')] [string] $EdgeExtensionId,
    [Parameter(Mandatory)] [ValidatePattern('^[a-p]{32}$')] [string] $BraveExtensionId,
    [Parameter()] [ValidatePattern('^[A-Za-z0-9._@{}-]+$')] [string] $FirefoxExtensionId = 'keyscan@keyscan.app',
    [Parameter()] [string] $ConfigurationDirectory = '',
    [Parameter()] [switch] $SkipRegistry
)

$ErrorActionPreference = 'Stop'
$hostName = 'com.keyscan.desktop'
$hostPath = (Resolve-Path -LiteralPath $HostExecutable).Path
if ([IO.Path]::GetExtension($hostPath) -ne '.exe') { throw 'HostExecutable must be the packaged KeyScanNativeHost.exe.' }

$keyScanDirectory = if ([string]::IsNullOrWhiteSpace($ConfigurationDirectory)) {
    Join-Path $env:APPDATA 'KeyScan'
} else {
    [IO.Path]::GetFullPath($ConfigurationDirectory)
}
$manifestDirectory = Join-Path $keyScanDirectory 'native-messaging'
New-Item -ItemType Directory -Path $manifestDirectory -Force | Out-Null

$chromiumOrigins = @(
    @($ChromeExtensionId, $EdgeExtensionId, $BraveExtensionId) |
        Select-Object -Unique |
        ForEach-Object { "chrome-extension://$_/" }
)
$chromiumManifest = [ordered]@{
    name = $hostName
    description = 'KeyScan password manager native messaging host'
    path = $hostPath
    type = 'stdio'
    allowed_origins = $chromiumOrigins
}
$firefoxManifest = [ordered]@{
    name = $hostName
    description = 'KeyScan password manager native messaging host'
    path = $hostPath
    type = 'stdio'
    allowed_extensions = @($FirefoxExtensionId)
}

$chromiumManifestPath = Join-Path $manifestDirectory "$hostName.chromium.json"
$firefoxManifestPath = Join-Path $manifestDirectory "$hostName.firefox.json"
$utf8NoBom = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText($chromiumManifestPath, ($chromiumManifest | ConvertTo-Json -Depth 4), $utf8NoBom)
[IO.File]::WriteAllText($firefoxManifestPath, ($firefoxManifest | ConvertTo-Json -Depth 4), $utf8NoBom)

# The host performs a second allow-list check. Chrome-family callers pass their origin; Firefox
# passes the manifest path followed by the add-on ID, so both forms are listed explicitly.
$nativeHostAllowlist = @($chromiumOrigins) + @($FirefoxExtensionId)
[IO.File]::WriteAllLines((Join-Path $keyScanDirectory 'native-host-allowlist.txt'), [string[]]$nativeHostAllowlist, $utf8NoBom)

$registryRoots = @(
    'HKCU:\Software\Google\Chrome\NativeMessagingHosts',
    'HKCU:\Software\Microsoft\Edge\NativeMessagingHosts',
    'HKCU:\Software\BraveSoftware\Brave-Browser\NativeMessagingHosts'
)
if (-not $SkipRegistry) { foreach ($root in $registryRoots) {
    $key = Join-Path $root $hostName
    if ($PSCmdlet.ShouldProcess($key, 'Register KeyScan Chromium native messaging host')) {
        New-Item -Path $key -Force | Out-Null
        Set-Item -Path $key -Value $chromiumManifestPath
    }
} }
$firefoxKey = "HKCU:\Software\Mozilla\NativeMessagingHosts\$hostName"
if (-not $SkipRegistry -and $PSCmdlet.ShouldProcess($firefoxKey, 'Register KeyScan Firefox native messaging host')) {
    New-Item -Path $firefoxKey -Force | Out-Null
    Set-Item -Path $firefoxKey -Value $firefoxManifestPath
}

if ($SkipRegistry) { Write-Host 'KeyScan native host manifests generated; registry changes skipped.' }
else { Write-Host 'KeyScan native host registered for Chrome, Edge, Brave and Firefox.' }
