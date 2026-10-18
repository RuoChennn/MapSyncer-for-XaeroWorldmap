param(
    [Parameter(Mandatory = $true)]
    [string]$VersionDir,
    [string]$VersionId = '',
    [string]$Username = 'CodexTester',
    [string]$QuickPlaySingleplayer = '',
    [string]$QuickPlayMultiplayer = '',
    [int]$Width = 1280,
    [int]$Height = 720,
    [string]$Xmx = '4G'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $VersionDir)) {
    throw "Version directory not found: $VersionDir"
}
if (-not $VersionId) {
    $jsonFile = Get-ChildItem -LiteralPath $VersionDir -Filter '*.json' |
        Where-Object { $_.Name -notmatch 'natives' } |
        Select-Object -First 1
    if (-not $jsonFile) {
        throw "No version json found in $VersionDir"
    }
    $VersionId = [System.IO.Path]::GetFileNameWithoutExtension($jsonFile.Name)
}

$jsonPath = Join-Path $VersionDir "$VersionId.json"
if (-not (Test-Path -LiteralPath $jsonPath)) {
    throw "Version json not found: $jsonPath"
}
$json = Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json
$libRoot = 'E:\Game\pcl\.minecraft\libraries'
$assetsRoot = 'E:\Game\pcl\.minecraft\assets'
$natives = Join-Path $VersionDir "$VersionId-natives"
New-Item -ItemType Directory -Force $natives | Out-Null

$featureValues = @{
    'is_demo_user' = $false
    'has_custom_resolution' = $false
    'has_quick_plays_support' = $false
    'is_quick_play_singleplayer' = $false
    'is_quick_play_multiplayer' = $false
    'is_quick_play_realms' = $false
}

function Test-Rules([object]$rules) {
    if (-not $rules) { return $true }
    $allowed = $false
    foreach ($rule in $rules) {
        $match = $true
        if ($rule.os) {
            if ($rule.os.name -and $rule.os.name -ne 'windows') { $match = $false }
            if ($rule.os.arch -and $rule.os.arch -ne 'x86') { $match = $false }
        }
        if ($rule.features) {
            foreach ($name in $rule.features.PSObject.Properties.Name) {
                $wanted = [bool]$rule.features.$name
                if ($featureValues[$name] -ne $wanted) { $match = $false }
            }
        }
        if ($match) {
            if ($rule.action -eq 'allow') { $allowed = $true }
            if ($rule.action -eq 'disallow') { $allowed = $false }
        }
    }
    return $allowed
}

function Expand-Placeholders([string]$value) {
    # 默认测试账号 CodexTester 的离线 UUID，与专用服务端 usercache.json 保持一致。
    # 离线服务端不会使用客户端传入的占位 UUID，而会按用户名生成该值；
    # 改用 -Username 时需同步更新这里和 ops.json。
    $map = @{
        '${auth_player_name}' = $Username
        '${version_name}' = $VersionId
        '${game_directory}' = $VersionDir
        '${assets_root}' = $assetsRoot
        '${assets_index_name}' = [string]$json.assetIndex.id
        '${auth_uuid}' = 'dc94793c8409363d954aa2295f4554d1'
        '${auth_access_token}' = '0'
        '${clientid}' = '0'
        '${auth_xuid}' = '0'
        '${user_type}' = 'legacy'
        '${version_type}' = 'release'
        '${resolution_width}' = [string]$Width
        '${resolution_height}' = [string]$Height
        '${natives_directory}' = $natives
        '${classpath}' = $script:classpath
        '${launcher_name}' = 'codex'
        '${launcher_version}' = 'test'
        '${library_directory}' = $libRoot
        '${classpath_separator}' = ';'
        '${quickPlayPath}' = ''
        '${quickPlaySingleplayer}' = $QuickPlaySingleplayer
        '${quickPlayMultiplayer}' = $QuickPlayMultiplayer
        '${quickPlayRealms}' = ''
    }
    $result = $value
    foreach ($key in $map.Keys) {
        $result = $result.Replace($key, [string]$map[$key])
    }
    return $result
}

$classpathEntries = New-Object System.Collections.Generic.List[string]
$classpathSeen = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($lib in $json.libraries) {
    if (-not (Test-Rules $lib.rules)) { continue }
    if ($lib.downloads.artifact.path) {
        $path = Join-Path $libRoot $lib.downloads.artifact.path
    } else {
        $parts = $lib.name -split ':'
        $group = $parts[0].Replace('.', '\')
        $artifact = $parts[1]
        $version = $parts[2]
        $path = Join-Path $libRoot "$group\$artifact\$version\$artifact-$version.jar"
    }
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing library: $($lib.name) -> $path"
    }
    if (-not $classpathSeen.Add($path)) { continue }
    $classpathEntries.Add($path)
}
$classpathEntries.Add((Join-Path $VersionDir "$VersionId.jar"))
$script:classpath = $classpathEntries -join ';'

$jvmArgs = New-Object System.Collections.Generic.List[string]
$jvmArgs.Add("-Xmx$Xmx")
foreach ($arg in $json.arguments.jvm) {
    if ($arg -is [string]) {
        $jvmArgs.Add((Expand-Placeholders $arg))
    } elseif ($arg.value) {
        if (Test-Rules $arg.rules) {
            foreach ($value in @($arg.value)) {
                $jvmArgs.Add((Expand-Placeholders ([string]$value)))
            }
        }
    }
}

$gameArgs = New-Object System.Collections.Generic.List[string]
foreach ($arg in $json.arguments.game) {
    if ($arg -is [string]) {
        $gameArgs.Add((Expand-Placeholders $arg))
    } elseif ($arg.value) {
        if (Test-Rules $arg.rules) {
            foreach ($value in @($arg.value)) {
                $gameArgs.Add((Expand-Placeholders ([string]$value)))
            }
        }
    }
}

$gameArgs.Add('--width')
$gameArgs.Add([string]$Width)
$gameArgs.Add('--height')
$gameArgs.Add([string]$Height)
if ($QuickPlaySingleplayer) {
    $gameArgs.Add('--quickPlaySingleplayer')
    $gameArgs.Add($QuickPlaySingleplayer)
}
if ($QuickPlayMultiplayer) {
    $gameArgs.Add('--quickPlayMultiplayer')
    $gameArgs.Add($QuickPlayMultiplayer)
}

$major = 21
if ($json.javaVersion -and $json.javaVersion.majorVersion) {
    $major = [int]$json.javaVersion.majorVersion
}
$jdkRoot = 'C:\Program Files\Eclipse Adoptium'
$jdk = Get-ChildItem -LiteralPath $jdkRoot -Directory |
    Where-Object { $_.Name -like "jdk-$major.*" } |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $jdk) {
    throw "JDK $major not found under $jdkRoot"
}
$java = Join-Path $jdk.FullName 'bin\java.exe'

$gameTemp = Join-Path $VersionDir 'tmp'
New-Item -ItemType Directory -Force $gameTemp | Out-Null
$env:TEMP = $gameTemp
$env:TMP = $gameTemp
$env:TMPDIR = $gameTemp
$env:JAVA_HOME = $jdk.FullName

$outDir = Join-Path $VersionDir 'logs\codex-client'
New-Item -ItemType Directory -Force $outDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outLog = Join-Path $outDir "client-$stamp.out.log"
$errLog = Join-Path $outDir "client-$stamp.err.log"

function Quote-Arg([string]$value) {
    if ($value -match '[\s"]') {
        return '"' + $value.Replace('"', '\"') + '"'
    }
    return $value
}

$allArgs = @()
$allArgs += $jvmArgs
$allArgs += $json.mainClass
$allArgs += $gameArgs
$argLine = ($allArgs | ForEach-Object { Quote-Arg ([string]$_) }) -join ' '

$process = Start-Process -FilePath $java -ArgumentList $argLine `
    -WorkingDirectory $VersionDir `
    -RedirectStandardOutput $outLog `
    -RedirectStandardError $errLog `
    -PassThru

Write-Output "pid=$($process.Id)"
Write-Output "java=$java"
Write-Output "main=$($json.mainClass)"
Write-Output "out=$outLog"
Write-Output "err=$errLog"
