[CmdletBinding(SupportsShouldProcess)]
param()
$ErrorActionPreference = 'Stop'
$hostName = 'com.keyscan.desktop'
$keys = @(
    "HKCU:\Software\Google\Chrome\NativeMessagingHosts\$hostName",
    "HKCU:\Software\Microsoft\Edge\NativeMessagingHosts\$hostName",
    "HKCU:\Software\BraveSoftware\Brave-Browser\NativeMessagingHosts\$hostName",
    "HKCU:\Software\Mozilla\NativeMessagingHosts\$hostName"
)
foreach ($key in $keys) {
    if (Test-Path -LiteralPath $key) {
        if ($PSCmdlet.ShouldProcess($key, 'Remove KeyScan native messaging registration')) {
            Remove-Item -LiteralPath $key -Force
        }
    }
}
$manifestDirectory = Join-Path (Join-Path $env:APPDATA 'KeyScan') 'native-messaging'
if (Test-Path -LiteralPath $manifestDirectory) {
    if ($PSCmdlet.ShouldProcess($manifestDirectory, 'Remove generated KeyScan native host manifests')) {
        Remove-Item -LiteralPath $manifestDirectory -Recurse -Force
    }
}
