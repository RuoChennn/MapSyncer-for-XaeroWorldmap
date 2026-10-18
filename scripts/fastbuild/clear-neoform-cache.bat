@echo off
chcp 65001 >nul
rem NeoForm 缓存清理（解决 ZipException: STORED entry missing size）
setlocal EnableExtensions

for %%I in ("%~dp0..\..") do set "PROJECT_ROOT=%%~sI"
set "GRADLE_CACHE=%USERPROFILE%\.gradle\caches"

echo 清理 NeoForm 缓存...

for %%D in (
    "%GRADLE_CACHE%\modules-2\files-2.1\net.neoforged.neoform"
    "%GRADLE_CACHE%\neoform"
    "%GRADLE_CACHE%\neoforged"
    "%GRADLE_CACHE%\transforms-4"
    "%GRADLE_CACHE%\8.9"
    "%PROJECT_ROOT%\mc-1.21.11\neoforge\build"
    "%PROJECT_ROOT%\mc-1.21.1\neoforge\build"
) do (
    if exist "%%~D" (
        rd /s /q "%%~D"
        echo 已清理: %%~D
    )
)

echo.
echo 清理完成!
echo 现在可以重新构建 NeoForge 1.21.11:
echo   gradlew.bat :mc-1.21.11:neoforge:build -x test --no-daemon --refresh-dependencies
exit /b 0
