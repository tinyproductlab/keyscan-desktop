[CmdletBinding()]
param([string]$Version = '0.1.64')

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$releaseRoot = Join-Path $projectRoot "build\release\$Version"
New-Item -ItemType Directory -Path $releaseRoot -Force | Out-Null
$targets = @(
    @{ Name = 'chrome'; Extension = 'zip' },
    @{ Name = 'edge'; Extension = 'zip' },
    @{ Name = 'brave'; Extension = 'zip' },
    @{ Name = 'firefox'; Extension = 'xpi' },
    @{ Name = 'safari'; Extension = 'zip' }
)
$required = @('manifest.json', 'background.js', 'content.js', 'popup.js', 'popup.html', 'popup.css')
$artifacts = @()

foreach ($target in $targets) {
    $source = Join-Path $projectRoot "dist\$($target.Name)"
    if (-not (Test-Path -LiteralPath $source -PathType Container)) { throw "Missing built extension directory: $source" }
    $destination = Join-Path $releaseRoot "KeyScan-$($target.Name)-$Version.$($target.Extension)"
    if (Test-Path -LiteralPath $destination) { Remove-Item -LiteralPath $destination -Force }
    # CreateFromDirectory on .NET Framework writes the platform separator, so entries come out as
    # icons\icon-128.png. A browser reads that as one filename containing a backslash, so
    # _locales and the icons go missing and __MSG_appName__ never resolves. Write the entries by
    # hand with the forward slashes the ZIP format requires.
    $archive = [IO.Compression.ZipFile]::Open($destination, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in (Get-ChildItem -LiteralPath $source -Recurse -File | Sort-Object FullName)) {
            $relative = $file.FullName.Substring($source.Length).TrimStart('\', '/').Replace('\', '/')
            if ($relative -eq 'manifest.json') {
                # The pinned public key exists so an unpacked dev load gets a stable ID. A store
                # assigns its own key, and a manifest carrying a different one fails to install,
                # so the uploaded copy ships without it.
                $manifest = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
                $ordered = [ordered]@{}
                foreach ($property in $manifest.PSObject.Properties) {
                    if ($property.Name -ne 'key') { $ordered[$property.Name] = $property.Value }
                }
                $entry = $archive.CreateEntry($relative, [IO.Compression.CompressionLevel]::Optimal)
                $writer = New-Object IO.StreamWriter($entry.Open(), [Text.UTF8Encoding]::new($false))
                try { $writer.Write(($ordered | ConvertTo-Json -Depth 8)) } finally { $writer.Dispose() }
                continue
            }
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $file.FullName, $relative, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $archive.Dispose() }

    $archive = [IO.Compression.ZipFile]::OpenRead($destination)
    try {
        $names = @($archive.Entries | ForEach-Object FullName)
        foreach ($name in $required) { if ($name -notin $names) { throw "$destination is missing root entry $name" } }
        if ($names | Where-Object { $_ -match '(^|/)(node_modules|src|scripts)/' }) { throw "$destination contains development files" }
        $backslashed = @($names | Where-Object { $_ -like '*\*' })
        if ($backslashed.Count -gt 0) { throw "$destination uses backslash entry names: $($backslashed -join ', ')" }
        # The manifest points at these; a store upload is rejected when they are unreachable.
        foreach ($name in @('manifest.json', '_locales/en/messages.json', 'icons/icon-128.png')) {
            if ($name -notin $names) { throw "$destination is missing $name" }
        }
        $packaged = [IO.StreamReader]::new(($archive.Entries | Where-Object FullName -eq 'manifest.json').Open()).ReadToEnd() | ConvertFrom-Json
        if ($packaged.PSObject.Properties.Name -contains 'key') { throw "$destination still carries the development key" }
        if (-not $packaged.icons.'128') { throw "$destination manifest lost its 128px icon" }
    } finally { $archive.Dispose() }
    $artifacts += Get-Item -LiteralPath $destination
}

$checksumPath = Join-Path $releaseRoot 'SHA256SUMS.txt'
$lines = $artifacts | ForEach-Object {
    $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $($_.Name)"
}
[IO.File]::WriteAllLines($checksumPath, $lines, [Text.UTF8Encoding]::new($false))

Write-Host "Browser extension packages verified."
$artifacts | ForEach-Object { Write-Host "  $($_.FullName) ($($_.Length) bytes)" }
Write-Host "  SHA-256 manifest: $checksumPath"
