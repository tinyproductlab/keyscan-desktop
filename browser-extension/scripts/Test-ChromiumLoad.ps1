[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('chrome', 'edge', 'brave')][string]$Target,
    [Parameter(Mandatory)][string]$BrowserExecutable
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$extensionPath = (Resolve-Path (Join-Path $projectRoot "dist\$Target")).Path
if (-not (Test-Path -LiteralPath $BrowserExecutable -PathType Leaf)) { throw "Browser executable not found: $BrowserExecutable" }
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
Get-ChildItem -LiteralPath $tempBase -Directory -Filter "keyscan-$Target-smoke-*" -ErrorAction SilentlyContinue | ForEach-Object {
    $stale = [IO.Path]::GetFullPath($_.FullName)
    if ($stale.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase) -and $_.Name -like "keyscan-$Target-smoke-*") {
        Remove-Item -LiteralPath $stale -Recurse -Force -ErrorAction SilentlyContinue
    }
}
$profile = Join-Path $tempBase ("keyscan-$Target-smoke-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $profile | Out-Null

try {
    $arguments = @(
        '--no-first-run', '--no-default-browser-check', '--window-position=-32000,-32000', '--window-size=1,1',
        "--user-data-dir=$profile", "--disable-extensions-except=$extensionPath",
        "--load-extension=$extensionPath", 'about:blank'
    )
    $process = Start-Process -FilePath $BrowserExecutable -ArgumentList $arguments -WindowStyle Hidden -PassThru
    $preferences = Join-Path $profile 'Default\Secure Preferences'
    $deadline = [DateTime]::UtcNow.AddSeconds(20); $ids = @(); $settings = $null
    do {
        if (Test-Path -LiteralPath $preferences) {
            try {
                $json = Get-Content -LiteralPath $preferences -Raw -Encoding UTF8 | ConvertFrom-Json
                $settings = $json.extensions.settings
                $ids = @($settings.PSObject.Properties | Where-Object {
                    $candidate = [string]$_.Value.path
                    $candidate -and [IO.Path]::GetFullPath($candidate).Equals($extensionPath, [StringComparison]::OrdinalIgnoreCase)
                } | Select-Object -ExpandProperty Name)
            } catch { $ids = @() }
        }
        if ($ids.Count -ne 1) { Start-Sleep -Milliseconds 250 }
    } while ($ids.Count -ne 1 -and [DateTime]::UtcNow -lt $deadline)
    if (-not (Test-Path -LiteralPath $preferences)) { throw "$Target did not create an isolated Preferences file." }
    if ($ids.Count -ne 1) {
        $loaded = @($settings.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value.path)" }) -join '; '
        throw "$Target did not load exactly one KeyScan extension from $extensionPath. Registered: $loaded"
    }
    [pscustomobject]@{ Target = $Target; ExtensionId = $ids[0]; BrowserVersion = (Get-Item -LiteralPath $BrowserExecutable).VersionInfo.ProductVersion }
}
finally {
    Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like "*$profile*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    $resolved = [IO.Path]::GetFullPath($profile)
    if (-not $resolved.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $resolved -Leaf) -notlike "keyscan-$Target-smoke-*") {
        throw "Unsafe temporary cleanup target: $resolved"
    }
    $cleanupDeadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        try { if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }; break }
        catch [IO.IOException] { if ([DateTime]::UtcNow -ge $cleanupDeadline) { throw }; Start-Sleep -Milliseconds 250 }
    } while ($true)
}
