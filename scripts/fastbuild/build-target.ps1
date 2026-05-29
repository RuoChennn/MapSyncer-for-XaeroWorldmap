# MapSyncer 分版本构建脚本

param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$Target,

    [switch]$Clean,
    [switch]$NoTest
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# Gradle 版本映射
$GradleVersions = @{
    "neoforge-1.20.4" = "8.5"
    "neoforge-1.21.1" = "8.9"
    "neoforge-1.21.11" = "8.9"
    "forge-1.20.1" = "8.9"
    "forge-1.20.4" = "8.9"
    "forge-1.21.1" = "8.9"
    "forge-1.21.11" = "8.9"
    "fabric-1.20.1" = "8.9"
    "fabric-1.20.4" = "8.9"
    "fabric-1.21.1" = "8.9"
    "core" = "8.9"
    "platform-api" = "8.9"
    "all" = "8.9"
}

function Get-GradleVersion($target) {
    if ($GradleVersions.ContainsKey($target)) {
        return $GradleVersions[$target]
    }
    return "8.9"
}

function Set-GradleWrapper($version) {
    $wrapperProps = "${ProjectRoot}\gradle\wrapper\gradle-wrapper.properties"
    $content = Get-Content $wrapperProps
    $newUrl = "distributionUrl=https\://services.gradle.org/distributions/gradle-${version}-bin.zip"
    $updatedContent = $content -replace "distributionUrl=.*", $newUrl
    Set-Content $wrapperProps $updatedContent
    Write-Host "Gradle wrapper switched to $version" -ForegroundColor Cyan
}

function Build-Module($target) {
    $gradleVersion = Get-GradleVersion $target
    Set-GradleWrapper $gradleVersion

    # 构建命令
    if ($target -eq "all") {
        $buildCmd = "build"
    } elseif ($target -eq "core") {
        $buildCmd = ":libs:core:build"
    } elseif ($target -eq "platform-api") {
        $buildCmd = ":libs:platform-api:build"
    } else {
        $dashIndex = $target.IndexOf("-")
        if ($dashIndex -gt 0) {
            $platform = $target.Substring(0, $dashIndex)
            $version = $target.Substring($dashIndex + 1)
            $buildCmd = ":mc-${version}:${platform}:build"
        } else {
            $buildCmd = "${target}:build"
        }
    }

    $argsList = @($buildCmd, "collectJars", "--no-daemon")
    if ($NoTest) { $argsList += @("-x", "test") }
    if ($Clean) { $argsList = @("clean") + $argsList }

    Write-Host "Building: $target (Gradle $gradleVersion)" -ForegroundColor Green

    Push-Location $ProjectRoot
    try {
        & ".\gradlew.bat" $argsList
    } finally {
        Pop-Location
    }
}

Write-Host "MapSyncer Build Script" -ForegroundColor Magenta
Build-Module $Target
Write-Host "Done!" -ForegroundColor Green

# 显示统一输出目录的 jar 文件
$libDir = "${ProjectRoot}\build\lib"
if (Test-Path $libDir) {
    Get-ChildItem -Path "$libDir\*.jar" | ForEach-Object {
        Write-Host $_.FullName -ForegroundColor White
    }
}
