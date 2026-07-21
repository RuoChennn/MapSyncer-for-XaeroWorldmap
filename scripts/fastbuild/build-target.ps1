# MapSyncer 分版本构建（G1–G4 锚点 + 胶水层）
#
# 用法:
#   .\build-target.ps1 fabric-1.21.1 [-Clean] [-NoTest]
#   .\build-target.ps1 forge-1.20.1 -NoTest
#   .\build-target.ps1 all -NoTest
#   .\build-target.ps1 packager
#
# 结构: libs/mc-*（锚点源码）+ mc-{版本}/{fabric|forge|neoforge}（胶水）
# 隔离 settings: Forge → settings-forge.gradle；Fabric 1.21.11 → settings-12111.gradle；26.x → settings-26.gradle

param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Target,

    [switch]$Clean,
    [switch]$NoTest
)

$ErrorActionPreference = "Stop"

$ScriptDir = $PSScriptRoot
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $ScriptDir)
$SettingsDefault = Join-Path $ProjectRoot "settings.gradle"
$SettingsBak = Join-Path $ProjectRoot "settings.bak.gradle"
$SettingsForge = Join-Path $ScriptDir "settings-forge.gradle"
$Settings12111 = Join-Path $ScriptDir "settings-12111.gradle"
$Settings26 = Join-Path $ScriptDir "settings-26.gradle"
$GradleWrapper = Join-Path $ProjectRoot "gradlew.bat"
$Gradle89 = Join-Path $ProjectRoot "gradle-8.9\bin\gradle.bat"
$CopyJars = Join-Path $ScriptDir "copy-release-jars.ps1"
$PropsFile = Join-Path $ProjectRoot "gradle.properties"
$PropsBak = Join-Path $ProjectRoot "gradle.properties.bak"

$script:SettingsSwitched = $false
$script:PropsOverridden = $false

function Get-JdkPath([int]$Major) {
    switch ($Major) {
        17 { return "C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot" }
        21 { return "C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" }
        25 { return "C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot" }
        default { throw "Unsupported JDK major version: $Major" }
    }
}

function Switch-Settings([string]$Profile) {
    if ($Profile -eq "default") {
        if (Test-Path $SettingsBak) {
            Write-Host "Settings -> default (restored)" -ForegroundColor Yellow
            Copy-Item $SettingsBak $SettingsDefault -Force
            Remove-Item $SettingsBak -Force
            $script:SettingsSwitched = $false
        }
        return
    }

    $source = switch ($Profile) {
        "forge" { $SettingsForge }
        "12111" { $Settings12111 }
        "26" { $Settings26 }
        default { throw "Unknown settings profile: $Profile" }
    }

    $markerPattern = switch ($Profile) {
        "forge" { "^\s*include\s+'mc-1\.20\.1:forge'" }
        "12111" { "^\s*include\s+'mc-1\.21\.11:fabric'" }
        "26" { "^\s*include\s+'mc-26\.1:fabric'" }
    }

    if (Select-String -Path $SettingsDefault -Pattern $markerPattern -Quiet) {
        Write-Host "Settings -> already $Profile, skip" -ForegroundColor DarkGray
        return
    }

    Write-Host "Settings -> $Profile (isolated)" -ForegroundColor Yellow
    if (-not (Test-Path $SettingsBak)) {
        Copy-Item $SettingsDefault $SettingsBak -Force
    }
    Copy-Item $source $SettingsDefault -Force
    $script:SettingsSwitched = $true
}

function Restore-Settings {
    if ($script:SettingsSwitched -and (Test-Path $SettingsBak)) {
        Write-Host "Settings -> restored" -ForegroundColor Yellow
        Copy-Item $SettingsBak $SettingsDefault -Force
        Remove-Item $SettingsBak -Force
        $script:SettingsSwitched = $false
    }
}

function Set-PropsJdk([int]$Major) {
    $jdkPath = Get-JdkPath $Major
    if (-not (Test-Path $PropsBak)) {
        Copy-Item $PropsFile $PropsBak -Force
    }
    $content = Get-Content $PropsFile -Raw
    $updated = $content -replace 'org\.gradle\.java\.home=.*', "org.gradle.java.home=$jdkPath"
    Set-Content $PropsFile $updated -NoNewline
    $script:PropsOverridden = $true
    $env:JAVA_HOME = $jdkPath -replace '/', '\'
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

function Restore-Props {
    if ($script:PropsOverridden -and (Test-Path $PropsBak)) {
        Copy-Item $PropsBak $PropsFile -Force
        Remove-Item $PropsBak -Force
        $script:PropsOverridden = $false
    }
}

function Get-TargetSpec([string]$target) {
    switch ($target) {
        "all" {
            return @{
                Kind = "delegate"
                Script = Join-Path $ScriptDir "build-all.bat"
            }
        }
        "packager" {
            return @{
                Kind = "packager"
                Tasks = @(":libs:core:mapPackagerDist")
                CopyFrom = @("libs/core/build/dist/mapsyncer-packager-*.zip")
            }
        }
        "core" {
            return @{
                Kind = "gradle"
                Tasks = @(":libs:core:build")
                Settings = "default"
                Gradle = "wrapper"
            }
        }
        "platform-api" {
            return @{
                Kind = "gradle"
                Tasks = @(":libs:platform-api:build")
                Settings = "default"
                Gradle = "wrapper"
            }
        }
        default {
            $dash = $target.IndexOf("-")
            if ($dash -le 0) { throw "Unknown target: $target" }

            $platform = $target.Substring(0, $dash)
            $mcVersion = $target.Substring($dash + 1)
            if ($platform -notin @("fabric", "forge", "neoforge")) {
                throw "Unknown platform in target: $target"
            }

            $settings = "default"
            $gradle = "wrapper"
            $jdk = $null

            if ($platform -eq "forge") {
                $settings = "forge"
                $gradle = "8.9"
                $jdk = if ($mcVersion -like "1.20*") { 17 } else { 21 }
            } elseif ($platform -eq "fabric" -and $mcVersion -eq "1.21.11") {
                $settings = "12111"
            } elseif ($mcVersion -like "26*") {
                $settings = "26"
                $jdk = 25
            }

            return @{
                Kind = "gradle"
                Tasks = @(":mc-${mcVersion}:${platform}:build")
                CleanTasks = @(":mc-${mcVersion}:${platform}:clean")
                Settings = $settings
                Gradle = $gradle
                Jdk = $jdk
                CopyLibs = @("mc-$mcVersion\$platform\build\libs")
            }
        }
    }
}

function Invoke-Gradle([string[]]$tasks, [string]$gradleKind) {
    $exe = if ($gradleKind -eq "8.9") { $Gradle89 } else { $GradleWrapper }
    if ($gradleKind -eq "8.9" -and -not (Test-Path $exe)) {
        throw "Forge build requires gradle-8.9 at $exe (ForgeGradle incompatible with Gradle 9.x)"
    }

    $argsList = @("--no-daemon") + $tasks
    if ($NoTest) { $argsList += @("-x", "test") }

    Push-Location $ProjectRoot
    try {
        & $exe @argsList
        if ($LASTEXITCODE -ne 0) {
            throw "Build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Invoke-Target([string]$target) {
    $spec = Get-TargetSpec $target

    if ($spec.Kind -eq "delegate") {
        & $spec.Script
        if ($LASTEXITCODE -ne 0) { throw "build-all.bat failed" }
        return
    }

    if ($spec.Kind -eq "packager") {
        Invoke-Gradle $spec.Tasks "wrapper"
        $dest = Join-Path $ProjectRoot "output"
        if (-not (Test-Path $dest)) { New-Item -ItemType Directory -Path $dest | Out-Null }
        Copy-Item (Join-Path $ProjectRoot $spec.CopyFrom[0]) $dest -Force
        return
    }

    try {
        Switch-Settings $spec.Settings
        if ($null -ne $spec.Jdk) { Set-PropsJdk $spec.Jdk }

        $tasks = @()
        if ($Clean) { $tasks += $spec.CleanTasks }
        $tasks += $spec.Tasks

        Write-Host "Building: $target (settings=$($spec.Settings), gradle=$($spec.Gradle))" -ForegroundColor Green
        Invoke-Gradle $tasks $spec.Gradle

        if ($spec.CopyLibs) {
            & $CopyJars @($spec.CopyLibs)
        }
    } finally {
        Restore-Props
        Restore-Settings
    }
}

Write-Host "MapSyncer Build Script (G1-G4)" -ForegroundColor Magenta
Invoke-Target $Target
Write-Host "Done! -> output\" -ForegroundColor Green
