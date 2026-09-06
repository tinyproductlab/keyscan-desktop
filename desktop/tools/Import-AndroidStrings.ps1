[CmdletBinding()]
param(
    [string]$AndroidProject = $env:KEYSCAN_ANDROID_PROJECT,
    [string]$DesktopProject = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($DesktopProject)) {
    $DesktopProject = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
}

$sourceRoot = Join-Path $AndroidProject 'app\src\main\res'
$destination = Join-Path $DesktopProject 'desktop-ui\src\main\resources\android-strings'
$locales = [ordered]@{
    'zh-CN' = 'values'
    'en' = 'values-en'
    'zh-TW' = 'values-zh-rTW'
    'ja' = 'values-ja'
    'ko' = 'values-ko'
    'de' = 'values-de'
    'es' = 'values-es'
    'fr' = 'values-fr'
    'it' = 'values-it'
    'nl' = 'values-nl'
    'pt-BR' = 'values-pt-rBR'
    'ru' = 'values-ru'
}

New-Item -ItemType Directory -Path $destination -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
foreach ($entry in $locales.GetEnumerator()) {
    $directory = Join-Path $sourceRoot $entry.Value
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { throw "Android resource directory is missing: $directory" }
    $catalog = [ordered]@{}
    foreach ($file in (Get-ChildItem -LiteralPath $directory -Filter '*.xml' -File | Sort-Object Name)) {
        try { [xml]$document = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 } catch { continue }
        foreach ($node in @($document.SelectNodes('/resources/string'))) {
            if ($null -eq $node -or [string]::IsNullOrWhiteSpace($node.name)) { continue }
            $catalog[[string]$node.name] = [string]$node.InnerText
        }
    }
    # Desktop-only strings (LAN share, QR, browser extension) have no Android counterpart.
    # They live in these catalogs too, so carry them over instead of dropping them on re-import.
    $target = Join-Path $destination ($entry.Key + '.json')
    $carried = 0
    if (Test-Path -LiteralPath $target -PathType Leaf) {
        $previous = Get-Content -LiteralPath $target -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($property in $previous.PSObject.Properties) {
            if ($property.Name.StartsWith('desktop_') -and -not $catalog.Contains($property.Name)) {
                $catalog[$property.Name] = [string]$property.Value
                $carried++
            }
        }
    }
    $json = $catalog | ConvertTo-Json -Depth 3
    [IO.File]::WriteAllText($target, $json, $utf8)
    Write-Host ("{0}: {1} strings ({2} desktop-only carried over)" -f $entry.Key, $catalog.Count, $carried)
}
