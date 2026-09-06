[CmdletBinding()]
param(
    [string]$DesktopRoot = '',
    [string]$AndroidRoot = $env:KEYSCAN_ANDROID_PROJECT
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($DesktopRoot)) {
    $DesktopRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
}
$desktopRootPath = (Resolve-Path -LiteralPath $DesktopRoot).Path
$androidRootPath = (Resolve-Path -LiteralPath $AndroidRoot).Path
$gradle = Join-Path $desktopRootPath 'gradlew.bat'
$backupCipher = Join-Path $androidRootPath 'app\src\main\java\com\secureqr\scanner\backup\BackupStreamCipher.java'
$generator = Join-Path $androidRootPath 'tools\BackupGoldenVectorGenerator.java'
$verifier = Join-Path $androidRootPath 'tools\AndroidBackupV6VectorVerifier.java'

foreach ($path in @($gradle, $backupCipher, $generator, $verifier)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Required compatibility source was not found: $path" }
}

Push-Location $desktopRootPath
try {
    & $gradle --no-daemon --no-configuration-cache --max-workers=1 :shared-core:test :shared-core:generateDesktopV6CompatibilityVector
    if ($LASTEXITCODE -ne 0) { throw "Desktop compatibility build failed ($LASTEXITCODE)." }
} finally {
    Pop-Location
}

$vector = Join-Path $desktopRootPath 'shared-core\build\compatibility\desktop-v6-backup.ksb'
if (-not (Test-Path -LiteralPath $vector)) { throw "Desktop V6 vector was not generated: $vector" }

$javac = Get-Command javac -ErrorAction Stop
$java = Get-Command java -ErrorAction Stop
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("keyscan-android-backup-verify-" + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $temporaryRoot 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null

& $javac.Source -encoding UTF-8 -d $classes $backupCipher $generator $verifier
if ($LASTEXITCODE -ne 0) { throw "Android production verifier compilation failed ($LASTEXITCODE)." }
& $java.Source -cp $classes 'com.secureqr.scanner.backup.AndroidBackupV6VectorVerifier' $vector
if ($LASTEXITCODE -ne 0) { throw "Android production V6 decrypt verification failed ($LASTEXITCODE)." }

Get-FileHash -LiteralPath $vector -Algorithm SHA256
