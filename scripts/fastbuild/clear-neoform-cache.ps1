# NeoForm 缓存清理脚本
# 用于解决 ZipException: STORED entry missing size 问题

Write-Host "清理 NeoForm 缓存..." -ForegroundColor Cyan

# 清理全局 Gradle 缓存中的 NeoForm 相关文件
$gradleCache = "$env:USERPROFILE\.gradle\caches"

# 1. 清理 NeoForm 模块缓存
$neoformModules = Join-Path $gradleCache "modules-2\files-2.1\net.neoforged.neoform"
if (Test-Path $neoformModules) {
    Remove-Item -Recurse -Force $neoformModules
    Write-Host "已清理: $neoformModules" -ForegroundColor Green
}

# 2. 清理 NeoForm 缓存目录
$neoformCache = Join-Path $gradleCache "neoform"
if (Test-Path $neoformCache) {
    Remove-Item -Recurse -Force $neoformCache
    Write-Host "已清理: $neoformCache" -ForegroundColor Green
}

# 3. 清理 NeoForge 缓存
$neoforgeCache = Join-Path $gradleCache "neoforged"
if (Test-Path $neoforgeCache) {
    Remove-Item -Recurse -Force $neoforgeCache
    Write-Host "已清理: $neoforgeCache" -ForegroundColor Green
}

# 4. 清理 transforms 缓存（可能包含 NeoForm 相关转换）
$transformsCache = Join-Path $gradleCache "transforms-4"
if (Test-Path $transformsCache) {
    Remove-Item -Recurse -Force $transformsCache
    Write-Host "已清理: $transformsCache" -ForegroundColor Green
}

# 5. 清理 8.9 版本的缓存（包含 NeoForm 运行时缓存）
$gradleVersionCache = Join-Path $gradleCache "8.9"
if (Test-Path $gradleVersionCache) {
    Remove-Item -Recurse -Force $gradleVersionCache
    Write-Host "已清理: $gradleVersionCache" -ForegroundColor Green
}

# 6. 清理项目构建目录
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$buildDirs = @(
    Join-Path $projectRoot "mc-1.21.11\neoforge\build"
    Join-Path $projectRoot "mc-1.21.1\neoforge\build"
)
foreach ($dir in $buildDirs) {
    if (Test-Path $dir) {
        Remove-Item -Recurse -Force $dir
        Write-Host "已清理: $dir" -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "清理完成!" -ForegroundColor Green
Write-Host "现在可以重新构建 NeoForge 1.21.11:" -ForegroundColor Yellow
Write-Host "  .\gradlew :mc-1.21.11:neoforge:build -x test --no-daemon --refresh-dependencies"