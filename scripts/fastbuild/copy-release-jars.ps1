# 将构建产物复制到 output/，排除 slim / sources / javadoc 等附属 JAR。
#
# 用法:
#   .\copy-release-jars.ps1 mc-1.21.1\fabric\build\libs
#   .\copy-release-jars.ps1 mc-1.21.1\forge\build\libs mc-1.21.1\neoforge\build\libs
#
# JAR 命名按锚点组后缀，例如 1.0.4-fabric-1.21（G2）、1.0.4-fabric-1.20（G1）

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$SourceDirs
)

$ErrorActionPreference = "Stop"
$DestDir = "output"
$Filter = "*.jar"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Dest = Join-Path $ProjectRoot $DestDir
$ExcludePattern = '-(slim|sources|javadoc)\.jar$'

if ($null -eq $SourceDirs -or $SourceDirs.Count -eq 0) {
    Write-Error "Usage: copy-release-jars.ps1 <source-dir> [more-dirs...]"
}

if (-not (Test-Path $Dest)) {
    New-Item -ItemType Directory -Path $Dest -Force | Out-Null
}

foreach ($src in $SourceDirs) {
    if ([string]::IsNullOrWhiteSpace($src)) {
        continue
    }
    $fullSrc = if ([System.IO.Path]::IsPathRooted($src)) { $src } else { Join-Path $ProjectRoot $src }
    if (-not (Test-Path -LiteralPath $fullSrc)) {
        Write-Warning "Source not found: $src"
        continue
    }
    Get-ChildItem -LiteralPath $fullSrc -Filter $Filter -File | Where-Object {
        $_.Name -notmatch $ExcludePattern
    } | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $Dest -Force
    }
}
