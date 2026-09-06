[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $repoRoot 'desktop-ui\src\main\kotlin\com\keyscan\desktop'
$catalogRoot = Join-Path $repoRoot 'desktop-ui\src\main\resources\android-strings'
$utf8 = [System.Text.UTF8Encoding]::new($false, $true)

# Files that legitimately hold Chinese: each is a per-language translation table.
$translationTables = @(
    'Localization.kt', 'DesktopMessages.kt', 'VaultMessages.kt', 'PasswordMessages.kt',
    'TotpMessages.kt', 'DesktopDocuments.kt', 'DesktopHelpDocument.kt'
)

$sourceFiles = Get-ChildItem -LiteralPath $sourceRoot -Filter '*.kt' -File |
    Where-Object { -not $_.Name.StartsWith('._') }
$sourceText = ($sourceFiles | ForEach-Object {
    $path = $_.FullName
    $bytes = [System.IO.File]::ReadAllBytes($path)
    try { $utf8.GetString($bytes) } catch { throw "Invalid UTF-8 source: $path" }
}) -join "`n"

if ($sourceText.Contains([char]0xFFFD)) {
    throw 'A desktop Kotlin source contains the Unicode replacement character U+FFFD.'
}

# User-visible failures must use stable catalog text. Raw exception messages can
# expose English/JVM details, local paths, or remote server responses.
foreach ($file in $sourceFiles) {
    $text = [System.IO.File]::ReadAllText($file.FullName, $utf8)
    if ($text -match '\.message\s*\?:\s*t\(' -or $text -match 't\("[a-zA-Z0-9_]+",\s*[A-Za-z.]*\.message') {
        throw "$($file.Name) exposes a raw exception message through a localized UI string."
    }
}

# Any Chinese left in a UI source is text the other eleven languages will never see
# translated. This is the check that catches a new hardcoded label at review time.
$han = [regex]'[\u4e00-\u9fff]'
$literal = [regex]'"((?:[^"\\\r\n]|\\.)*)"'
$hardcoded = [System.Collections.Generic.List[string]]::new()
foreach ($file in $sourceFiles) {
    if ($translationTables -contains $file.Name) { continue }
    $text = [System.IO.File]::ReadAllText($file.FullName, $utf8)
    foreach ($m in $literal.Matches($text)) {
        $value = $m.Groups[1].Value
        if ($han.IsMatch($value)) {
            $line = ($text.Substring(0, $m.Index) -split "`n").Count
            $hardcoded.Add("$($file.Name):${line}: $value")
        }
    }
}
if ($hardcoded.Count -gt 0) {
    throw "Hardcoded Chinese in desktop UI source (use t(`"key`") instead):`n$($hardcoded -join "`n")"
}

# Catalog lookups reach the UI two ways: the local t("key") helper, and a direct
# AndroidStringCatalog.text(language, "key") call from non-composable code.
$requiredKeys = @(
    [regex]::Matches($sourceText, '\bt\("([a-zA-Z0-9_]+)"') +
    [regex]::Matches($sourceText, 'AndroidStringCatalog\.text\([^,)]+,\s*"([a-zA-Z0-9_]+)"')
) | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique

if ($requiredKeys.Count -eq 0) { throw 'No desktop Android string keys were discovered.' }

$catalogs = Get-ChildItem -LiteralPath $catalogRoot -Filter '*.json' -File | Sort-Object Name
if ($catalogs.Count -ne 12) { throw "Expected 12 desktop language catalogs, found $($catalogs.Count)." }

$failures = [System.Collections.Generic.List[string]]::new()
$englishByKey = $null
foreach ($catalog in $catalogs) {
    $json = Get-Content -LiteralPath $catalog.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    $propertyNames = @($json.PSObject.Properties.Name)
    foreach ($key in $requiredKeys) {
        if ($key -notin $propertyNames -or [string]::IsNullOrWhiteSpace([string]$json.$key)) {
            $failures.Add("$($catalog.BaseName):$key")
        }
    }
    if ($catalog.BaseName -eq 'en') { $englishByKey = $json }
}

if ($failures.Count -gt 0) {
    throw "Missing or blank direct translations:`n$($failures -join "`n")"
}

# A translation that drops a %s silently renders a message with the value missing.
$placeholder = [regex]'%(?:\d+\$)?[sd]'
$mismatch = [System.Collections.Generic.List[string]]::new()
foreach ($catalog in $catalogs) {
    if ($catalog.BaseName -eq 'en') { continue }
    $json = Get-Content -LiteralPath $catalog.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($key in $requiredKeys) {
        $expected = $placeholder.Matches([string]$englishByKey.$key).Count
        $actual = $placeholder.Matches([string]$json.$key).Count
        if ($expected -ne $actual) {
            $mismatch.Add("$($catalog.BaseName):${key} expected $expected placeholders, found $actual")
        }
    }
}
if ($mismatch.Count -gt 0) {
    throw "Placeholder mismatch between English and a translation:`n$($mismatch -join "`n")"
}

Write-Host "Desktop localization audit passed."
Write-Host "  Kotlin sources: $($sourceFiles.Count)"
Write-Host "  Direct catalog keys: $($requiredKeys.Count)"
Write-Host "  Language catalogs: $($catalogs.Count)"
