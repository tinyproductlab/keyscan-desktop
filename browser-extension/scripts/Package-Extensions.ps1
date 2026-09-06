[CmdletBinding()]
param([string]$Version = '0.1.64')

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

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
    [IO.Compression.ZipFile]::CreateFromDirectory($source, $destination, [IO.Compression.CompressionLevel]::Optimal, $false)

    $archive = [IO.Compression.ZipFile]::OpenRead($destination)
    try {
        $names = @($archive.Entries | ForEach-Object FullName)
        foreach ($name in $required) { if ($name -notin $names) { throw "$destination is missing root entry $name" } }
        if ($names | Where-Object { $_ -match '(^|/)(node_modules|src|scripts)/' }) { throw "$destination contains development files" }
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
