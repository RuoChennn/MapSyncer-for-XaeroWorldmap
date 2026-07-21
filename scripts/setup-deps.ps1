# MapSyncer 开发环境依赖一键部署
#
# 安装/检测 JDK 17 + 21 + 25、下载 Gradle 8.9（Forge 构建）、
# 引导 Gradle Wrapper，并预拉取默认构建所需的 Maven / MC 工件。
#
# 用法:
#   .\scripts\setup-deps.ps1              # 完整部署
#   .\scripts\setup-deps.ps1 -Quick       # 仅 JDK + Gradle，不预拉 Maven
#   .\scripts\setup-deps.ps1 -SkipJdk     # 跳过 JDK 安装（仅检测）
#   .\scripts\setup-deps.ps1 -SkipMaven   # 跳过 Maven 预拉取
#
# 也可双击 scripts\setup-deps.bat

param(
    [switch]$Quick,
    [switch]$SkipJdk,
    [switch]$SkipGradle89,
    [switch]$SkipMaven,
    [switch]$SkipPropsUpdate
)

$ErrorActionPreference = "Stop"

$ScriptDir = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $ScriptDir
$GradleWrapper = Join-Path $ProjectRoot "gradlew.bat"
$Gradle89Dir = Join-Path $ProjectRoot "gradle-8.9"
$Gradle89Bin = Join-Path $Gradle89Dir "bin\gradle.bat"
$PropsFile = Join-Path $ProjectRoot "gradle.properties"
$SettingsDefault = Join-Path $ProjectRoot "settings.gradle"
$SettingsBak = Join-Path $ProjectRoot "settings.bak.gradle"
$SettingsForge = Join-Path $ScriptDir "fastbuild\settings-forge.gradle"
$Settings12111 = Join-Path $ScriptDir "fastbuild\settings-12111.gradle"
$Settings26 = Join-Path $ScriptDir "fastbuild\settings-26.gradle"
$Gradle89Url = "https://services.gradle.org/distributions/gradle-8.9-bin.zip"

$RequiredJdks = @(17, 21, 25)

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "    [OK] $Message" -ForegroundColor Green
}

function Write-Warn([string]$Message) {
    Write-Host "    [WARN] $Message" -ForegroundColor Yellow
}

function Write-Fail([string]$Message) {
    Write-Host "    [FAIL] $Message" -ForegroundColor Red
}

function Find-JdkInstallations {
    $roots = @(
        "${env:ProgramFiles}\Eclipse Adoptium",
        "${env:ProgramFiles}\Java",
        "${env:ProgramFiles(x86)}\Eclipse Adoptium"
    )

    $found = @{}
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        Get-ChildItem $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            if ($_.Name -match '^jdk-(\d+)') {
                $major = [int]$Matches[1]
                $javaExe = Join-Path $_.FullName "bin\java.exe"
                if (Test-Path $javaExe) {
                    if (-not $found.ContainsKey($major) -or $_.Name -gt $found[$major].Name) {
                        $found[$major] = $_
                    }
                }
            }
        }
    }
    return $found
}

function Install-JdkViaWinget([int]$Major) {
    $package = switch ($Major) {
        17 { "EclipseAdoptium.Temurin.17.JDK" }
        21 { "EclipseAdoptium.Temurin.21.JDK" }
        25 { "EclipseAdoptium.Temurin.25.JDK" }
        default { throw "Unsupported JDK major version: $Major" }
    }

    Write-Host "    Installing Temurin JDK $Major via winget ($package)..." -ForegroundColor DarkGray
    winget install --id $package `
        --accept-package-agreements `
        --accept-source-agreements `
        --disable-interactivity `
        --silent
}

function Ensure-Jdks {
    Write-Step "JDK 17 / 21 / 25 (Eclipse Temurium)"

    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        Write-Warn "winget not found; will only detect existing JDK installations"
    }

    foreach ($major in $RequiredJdks) {
        $installations = Find-JdkInstallations
        if ($installations.ContainsKey($major)) {
            Write-Ok "JDK $major -> $($installations[$major].FullName)"
            continue
        }

        if ($SkipJdk) {
            Write-Warn "JDK $major missing (SkipJdk set)"
            continue
        }

        if (Get-Command winget -ErrorAction SilentlyContinue) {
            try {
                Install-JdkViaWinget $major
            } catch {
                Write-Warn "winget install JDK $major failed: $_"
            }
        } else {
            Write-Warn "JDK $major missing; install Temurin $major manually"
        }
    }

    $final = Find-JdkInstallations
    foreach ($major in $RequiredJdks) {
        if ($final.ContainsKey($major)) {
            Write-Ok "JDK $major ready"
        } else {
            Write-Fail "JDK $major still missing"
        }
    }
    return $final
}

function Update-GradleProperties([hashtable]$Jdks) {
    if ($SkipPropsUpdate) { return }
    if (-not (Test-Path $PropsFile)) {
        Write-Warn "gradle.properties not found, skip path update"
        return
    }

    Write-Step "Update gradle.properties JDK paths"

    $jdk25 = if ($Jdks.ContainsKey(25)) { ($Jdks[25].FullName -replace '\\', '/') } else { $null }
    $paths = @()
    foreach ($major in @(17, 21, 25)) {
        if ($Jdks.ContainsKey($major)) {
            $paths += ($Jdks[$major].FullName -replace '\\', '/')
        }
    }

    if (-not $jdk25 -and $paths.Count -eq 0) {
        Write-Warn "No JDK paths detected, keeping existing gradle.properties"
        return
    }

    $content = Get-Content $PropsFile -Raw
    if ($jdk25) {
        $content = $content -replace 'org\.gradle\.java\.home=.*', "org.gradle.java.home=$jdk25"
    }
    if ($paths.Count -gt 0) {
        $joined = ($paths -join ',')
        if ($content -match 'org\.gradle\.java\.installations\.paths=') {
            $content = $content -replace 'org\.gradle\.java\.installations\.paths=.*', "org.gradle.java.installations.paths=$joined"
        } else {
            $content = $content.TrimEnd() + "`norg.gradle.java.installations.paths=$joined`n"
        }
    }
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($PropsFile, $content, $utf8NoBom)
    Write-Ok "gradle.properties updated"
}

function Ensure-Gradle89 {
    if ($SkipGradle89) {
        Write-Warn "SkipGradle89 set"
        return
    }

    Write-Step "Gradle 8.9 (Forge 构建专用，ForgeGradle 不支持 Gradle 9.x)"

    if (Test-Path $Gradle89Bin) {
        Write-Ok "Already present at $Gradle89Dir"
        return
    }

    $zipPath = Join-Path $env:TEMP "gradle-8.9-bin.zip"
    Write-Host "    Downloading $Gradle89Url ..." -ForegroundColor DarkGray
    Invoke-WebRequest -Uri $Gradle89Url -OutFile $zipPath -UseBasicParsing

    if (Test-Path $Gradle89Dir) {
        Remove-Item $Gradle89Dir -Recurse -Force
    }
    Expand-Archive -Path $zipPath -DestinationPath $ProjectRoot -Force
    Remove-Item $zipPath -Force

    $extracted = Join-Path $ProjectRoot "gradle-8.9"
    if (-not (Test-Path $Gradle89Bin)) {
        throw "Gradle 8.9 extraction failed: $Gradle89Bin not found"
    }
    Write-Ok "Extracted to $extracted"
}

function Invoke-GradleWrapper([string[]]$Tasks) {
    if (-not (Test-Path $GradleWrapper)) {
        throw "gradlew.bat not found at $GradleWrapper"
    }
    Push-Location $ProjectRoot
    try {
        & $GradleWrapper @Tasks
        if ($LASTEXITCODE -ne 0) {
            throw "gradlew failed (exit $LASTEXITCODE): $($Tasks -join ' ')"
        }
    } finally {
        Pop-Location
    }
}

function Invoke-Gradle89([string[]]$Tasks) {
    if (-not (Test-Path $Gradle89Bin)) {
        throw "Gradle 8.9 required but missing at $Gradle89Bin"
    }
    Push-Location $ProjectRoot
    try {
        & $Gradle89Bin @("--no-daemon") + $Tasks
        if ($LASTEXITCODE -ne 0) {
            throw "gradle-8.9 failed (exit $LASTEXITCODE): $($Tasks -join ' ')"
        }
    } finally {
        Pop-Location
    }
}

function Switch-Settings([string]$SourcePath) {
    if (-not (Test-Path $SettingsBak)) {
        Copy-Item $SettingsDefault $SettingsBak -Force
    }
    Copy-Item $SourcePath $SettingsDefault -Force
}

function Restore-Settings {
    if (Test-Path $SettingsBak) {
        Copy-Item $SettingsBak $SettingsDefault -Force
        Remove-Item $SettingsBak -Force
    }
}

function Prefetch-MavenDependencies([hashtable]$Jdks) {
    Write-Step "Prefetch Maven / MC artifacts (default settings.gradle)"

    $jdk25 = if ($Jdks.ContainsKey(25)) { $Jdks[25].FullName } else { $null }
    if ($jdk25) {
        $env:JAVA_HOME = $jdk25
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    }

    Invoke-GradleWrapper @(
        "--no-daemon", "-x", "test",
        ":libs:lz4-relocated:shadowJar",
        ":libs:core:compileJava",
        ":libs:platform-api:compileJava",
        ":mc-1.20.1:fabric:compileJava",
        ":mc-1.21.1:fabric:compileJava",
        ":mc-1.21.1:neoforge:compileJava",
        ":mc-1.21.11:neoforge:compileJava",
        ":mc-26.1:neoforge:compileJava",
        ":mc-26.2:neoforge:compileJava"
    )
    Write-Ok "Default platform dependencies prefetched"

    if (-not (Test-Path $Gradle89Bin)) {
        Write-Warn "Gradle 8.9 missing; skip Forge dependency prefetch"
        return
    }

    Write-Step "Prefetch Forge dependencies (settings-forge.gradle + Gradle 8.9)"
    Switch-Settings $SettingsForge
    try {
        if ($Jdks.ContainsKey(17)) {
            $env:JAVA_HOME = $Jdks[17].FullName
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
            Invoke-Gradle89 @(
                "-x", "test",
                ":mc-1.20.1:forge:compileJava"
            )
            Write-Ok "Forge 1.20.1 dependencies prefetched"
        } else {
            Write-Warn "JDK 17 missing; skip Forge 1.20.1 prefetch"
        }

        if ($Jdks.ContainsKey(21)) {
            $env:JAVA_HOME = $Jdks[21].FullName
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
            Invoke-Gradle89 @(
                "-x", "test",
                ":mc-1.21.1:forge:compileJava",
                ":mc-1.21.11:forge:compileJava"
            )
            Write-Ok "Forge 1.21.x dependencies prefetched"
        } else {
            Write-Warn "JDK 21 missing; skip Forge 1.21.x prefetch"
        }
    } finally {
        Restore-Settings
    }

    Write-Step "Prefetch isolated Fabric dependencies"
    Switch-Settings $Settings12111
    try {
        if ($jdk25) {
            $env:JAVA_HOME = $jdk25
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        }
        Invoke-GradleWrapper @("--no-daemon", "-x", "test", ":mc-1.21.11:fabric:compileJava")
        Write-Ok "Fabric 1.21.11 dependencies prefetched"
    } finally {
        Restore-Settings
    }

    Switch-Settings $Settings26
    try {
        if ($jdk25) {
            $env:JAVA_HOME = $jdk25
            $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        }
        Invoke-GradleWrapper @(
            "--no-daemon", "-x", "test",
            ":mc-26.1:fabric:compileJava",
            ":mc-26.2:fabric:compileJava"
        )
        Write-Ok "Fabric 26.x dependencies prefetched"
    } finally {
        Restore-Settings
    }
}

Write-Host "============================================" -ForegroundColor White
Write-Host " MapSyncer - Setup Development Dependencies" -ForegroundColor White
Write-Host "============================================" -ForegroundColor White
Write-Host "Project: $ProjectRoot"

$jdks = Ensure-Jdks
Update-GradleProperties $jdks

Write-Step "Gradle Wrapper (9.4 via gradlew)"
Invoke-GradleWrapper @("--version")
Write-Ok "Gradle Wrapper ready"

Ensure-Gradle89

if ($Quick) {
    Write-Warn "Quick mode: skipped Maven / MC artifact prefetch"
} elseif (-not $SkipMaven) {
    Prefetch-MavenDependencies $jdks
} else {
    Write-Warn "SkipMaven set"
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Green
Write-Host " Setup complete" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Green
Write-Host ""
Write-Host "Next steps:"
Write-Host "  .\gradlew.bat buildAll -x test          # build default platforms"
Write-Host "  .\scripts\fastbuild\build-all.bat       # build all platforms"
Write-Host "  .\scripts\fastbuild\build-target.ps1 fabric-1.21.1 -NoTest"
Write-Host ""
