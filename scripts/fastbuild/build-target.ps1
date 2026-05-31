# MapSyncer 分版本构建脚本（支持多 settings 文件自动切换）
#
# 用法:
#   .\build-target.ps1 fabric-26.1 -Clean -NoTest
#   .\build-target.ps1 neoforge-26.1 -Clean -NoTest
#   .\build-target.ps1 all -NoTest
#
# Settings 文件说明:
#   settings.gradle          — 默认: 1.20.1 + 1.21.1 系列
#   scripts/fastbuild/settings-26.gradle — 26.1 专用 (隔离 Loom 1.16)

param(
    [Parameter(Mandatory=$true, Position=0)]
    [string]$Target,

    [switch]$Clean,
    [switch]$NoTest
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# Settings 文件路径
$SettingsDefault = Join-Path $ProjectRoot "settings.gradle"
$SettingsBak = Join-Path $ProjectRoot "settings.bak.gradle"
$Settings26 = Join-Path $ProjectRoot "scripts\fastbuild\settings-26.gradle"

# Gradle 版本映射
$GradleVersions = @{
    "neoforge-1.20.1" = "8.5"
    "neoforge-26.1"   = "9.4.0"
    "forge-1.20.1"    = "9.4.0"
    "forge-1.21.1"    = "9.4.0"
    "fabric-1.20.1"   = "9.4.0"
    "fabric-1.21.1"   = "9.4.0"
    "fabric-26.1"     = "9.4.0"
    "core"            = "9.4.0"
    "platform-api"    = "9.4.0"
    "all"             = "9.4.0"
}

# 需要隔离 settings 的目标（避免 Loom 版本冲突）
$IsolatedSettingsTargets = @("fabric-26.1", "neoforge-26.1")

function Get-GradleVersion($target) {
    if ($GradleVersions.ContainsKey($target)) {
        return $GradleVersions[$target]
    }
    return "9.4.0"
}

function Set-GradleWrapper($version) {
    $wrapperProps = Join-Path $ProjectRoot "gradle\wrapper\gradle-wrapper.properties"
    $newUrl = "distributionUrl=https\://services.gradle.org/distributions/gradle-${version}-bin.zip"
    $content = Get-Content $wrapperProps -Raw
    $updatedContent = $content -replace "distributionUrl=.*", $newUrl
    Set-Content $wrapperProps $updatedContent -NoNewline
    Write-Host "Gradle wrapper -> $version" -ForegroundColor Cyan
}

function Switch-SettingsFile($target) {
    if ($IsolatedSettingsTargets -contains $target) {
        # 检查当前是否已经是 26 版本
        $currentContent = Get-Content $SettingsDefault -Raw
        if ($currentContent -notmatch "include 'mc-26.1:fabric'") {
            Write-Host "Settings -> 26.x (isolated)" -ForegroundColor Yellow
            # 备份当前
            Copy-Item $SettingsDefault $SettingsBak -Force
            # 切换到 26 版本
            Copy-Item $Settings26 $SettingsDefault -Force
            $script:_settingsSwitched = $true
        } else {
            Write-Host "Settings -> already 26.x, skip" -ForegroundColor DarkGray
            $script:_settingsSwitched = $false
        }
    } else {
        # 非 26 目标：检查是否需要恢复
        $currentContent = Get-Content $SettingsDefault -Raw
        if ($currentContent -match "include 'mc-26.1:fabric'" -or (Test-Path $SettingsBak)) {
            if (Test-Path $SettingsBak) {
                Write-Host "Settings -> default (restored)" -ForegroundColor Yellow
                Copy-Item $SettingsBak $SettingsDefault -Force
                Remove-Item $SettingsBak -Force
            }
        }
        $script:_settingsSwitched = $false
    }
}

function Restore-SettingsFile {
    if ($script:_settingsSwitched -and (Test-Path $SettingsBak)) {
        Write-Host "Settings -> restored" -ForegroundColor Yellow
        Copy-Item $SettingsBak $SettingsDefault -Force
        Remove-Item $SettingsBak -Force
    }
}

function Build-Module($target) {
    $gradleVersion = Get-GradleVersion $target
    Set-GradleWrapper $gradleVersion
    Switch-SettingsFile $target

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

    $argsList = @($buildCmd, "--no-daemon")
    if ($NoTest) { $argsList += @("-x", "test") }
    if ($Clean) { $argsList = @("clean") + $argsList }

    Write-Host "Building: $target (Gradle $gradleVersion)" -ForegroundColor Green

    Push-Location $ProjectRoot
    try {
        & ".\gradlew.bat" $argsList
        if ($LASTEXITCODE -ne 0) {
            throw "Build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Restore-SettingsFile
        Pop-Location
    }
}

Write-Host "MapSyncer Build Script" -ForegroundColor Magenta
Build-Module $Target
Write-Host "Done!" -ForegroundColor Green
