[CmdletBinding()]
param([string] $OutputDirectory)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory = Join-Path $PSScriptRoot 'build' }
$compiler = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$runtime = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\System.Runtime.WindowsRuntime.dll'
$systemRuntime = 'C:\Windows\Microsoft.NET\assembly\GAC_MSIL\System.Runtime\v4.0_4.0.0.0__b03f5f7f11d50a3a\System.Runtime.dll'
$contracts = Join-Path $PSScriptRoot '.deps\microsoft.windows.sdk.contracts.10.0.26100.1\ref\netstandard2.0'
$contractVersion = '10.0.26100.1'
$contractHash = '933D0AEE3BF2EC5AD04D2E19D27609A95EECEC4D3FF0F50B222E2E3931819EF9'
if (-not (Test-Path -LiteralPath $contracts)) {
    $dependencyRoot = Join-Path $PSScriptRoot '.deps'
    New-Item -ItemType Directory -Path $dependencyRoot -Force | Out-Null
    $package = Join-Path $dependencyRoot "microsoft.windows.sdk.contracts.$contractVersion.nupkg"
    Invoke-WebRequest -UseBasicParsing -Uri "https://api.nuget.org/v3-flatcontainer/microsoft.windows.sdk.contracts/$contractVersion/microsoft.windows.sdk.contracts.$contractVersion.nupkg" -OutFile $package
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $package).Hash -ne $contractHash) { throw 'Windows SDK contract package hash mismatch.' }
    $zip = [IO.Path]::ChangeExtension($package, '.zip')
    Copy-Item -LiteralPath $package -Destination $zip -Force
    Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $dependencyRoot "microsoft.windows.sdk.contracts.$contractVersion") -Force
}
foreach ($required in @($compiler, $runtime, $systemRuntime, $contracts)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required Windows SDK component is missing: $required" }
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$output = Join-Path $OutputDirectory 'KeyScanWindowsHello.exe'
$references = @("/reference:$runtime", "/reference:$systemRuntime") +
    @(Get-ChildItem -LiteralPath $contracts -Filter '*.winmd' | ForEach-Object { "/reference:$($_.FullName)" })
& $compiler /nologo /target:exe /platform:anycpu /optimize+ /out:$output $references (Join-Path $PSScriptRoot 'KeyScanWindowsHello.cs')
if ($LASTEXITCODE -ne 0) { throw "C# compiler failed with exit code $LASTEXITCODE" }
Write-Output $output
