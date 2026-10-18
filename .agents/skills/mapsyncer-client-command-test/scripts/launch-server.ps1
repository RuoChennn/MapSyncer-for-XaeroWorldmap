param(
    [Parameter(Mandatory = $true)]
    [string]$ServerDir,
    [string]$Xmx = '4G',
    [int]$JavaMajor = 0
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ServerDir)) {
    throw "Server directory not found: $ServerDir"
}

$listener = Get-NetTCPConnection -LocalPort 25565 -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    throw "Port 25565 is already in use; stop the previous test server first."
}

$eula = Join-Path $ServerDir 'eula.txt'
if (Test-Path -LiteralPath $eula) {
    $eulaText = Get-Content -LiteralPath $eula -Raw
    if ($eulaText -notmatch '(?im)^\s*eula\s*=\s*true\s*$') {
        throw "eula.txt does not contain eula=true in $ServerDir"
    }
}

if ($JavaMajor -le 0) {
    $name = Split-Path -Leaf $ServerDir
    if ($name -match '1\.20') { $JavaMajor = 17 }
    elseif ($name -match '26\.') { $JavaMajor = 25 }
    else { $JavaMajor = 21 }
}

$jdkRoot = 'C:\Program Files\Eclipse Adoptium'
$jdk = Get-ChildItem -LiteralPath $jdkRoot -Directory |
    Where-Object { $_.Name -like "jdk-$JavaMajor.*" } |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $jdk) {
    throw "JDK $JavaMajor not found under $jdkRoot"
}
$java = Join-Path $jdk.FullName 'bin\java.exe'

$gameTemp = Join-Path $ServerDir 'tmp'
New-Item -ItemType Directory -Force $gameTemp | Out-Null
$env:TEMP = $gameTemp
$env:TMP = $gameTemp
$env:TMPDIR = $gameTemp
$env:JAVA_HOME = $jdk.FullName
$env:PATH = (Join-Path $jdk.FullName 'bin') + ';' + $env:PATH

$outDir = Join-Path $ServerDir 'logs'
New-Item -ItemType Directory -Force $outDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outLog = Join-Path $outDir "codex-console-$stamp.log"
$errLog = Join-Path $outDir "codex-console-$stamp.err.log"

$fabricJar = Get-ChildItem -LiteralPath $ServerDir -Filter 'fabric-server*.jar' |
    Select-Object -First 1
$vanillaJar = Join-Path $ServerDir 'server.jar'
$runBat = Join-Path $ServerDir 'run.bat'

if ($fabricJar) {
    $process = Start-Process -FilePath $java `
        -ArgumentList "-Xmx$Xmx -jar `"$($fabricJar.FullName)`" nogui" `
        -WorkingDirectory $ServerDir `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -PassThru
    $kind = 'fabric'
} elseif (Test-Path -LiteralPath $vanillaJar) {
    $process = Start-Process -FilePath $java `
        -ArgumentList "-Xmx$Xmx -jar `"$vanillaJar`" nogui" `
        -WorkingDirectory $ServerDir `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -PassThru
    $kind = 'vanilla'
} elseif (Test-Path -LiteralPath $runBat) {
    $process = Start-Process -FilePath 'cmd.exe' `
            -ArgumentList '/c', 'run.bat', '--nogui' `
        -WorkingDirectory $ServerDir `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -PassThru
    $kind = 'run.bat'
} else {
    throw "No fabric-server*.jar, server.jar, or run.bat found in $ServerDir"
}

Write-Output "pid=$($process.Id)"
Write-Output "kind=$kind"
Write-Output "java=$java"
Write-Output "out=$outLog"
Write-Output "err=$errLog"
