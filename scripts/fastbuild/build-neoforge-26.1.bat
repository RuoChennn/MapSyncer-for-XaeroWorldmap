@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0..\.."

set "SETTINGS_BAK=settings.bak.gradle"
set "SETTINGS_FILE=settings.gradle"
set "SETTINGS_26=scripts\fastbuild\settings-26.gradle"

echo ============================================
echo   Building: NeoForge 26.1
echo ============================================

:: 检查是否需要切换到 26.x settings
findstr /r /b /c:"include 'mc-26.1:fabric'" "%SETTINGS_FILE%" >nul 2>&1
if %errorlevel% neq 0 (
    echo [1/3] Already using 26.x settings, skipping switch...
) else (
    echo [1/3] Switching to 26.x-only settings...
    if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
    ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
    copy "%SETTINGS_26%" "%SETTINGS_FILE%" >nul
)

:: 构建
echo [2/3] Building mc-26.1:neoforge...
call gradlew.bat :mc-26.1:neoforge:clean :mc-26.1:neoforge:build -x test
set BUILD_RESULT=%errorlevel%

:: 恢复 settings.gradle
echo [3/3] Restoring settings.gradle...
if exist "%SETTINGS_BAK%" (
    if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
    ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"
)

if %BUILD_RESULT% neq 0 (
    echo.
    echo ============================================
    echo   BUILD FAILED!
    echo ============================================
    pause
    exit /b 1
)

:: 收集输出
echo.
echo Collecting JARs to output...
if not exist output mkdir output
copy /y mc-26.1\neoforge\build\libs\*.jar output\ >nul
copy /y libs\core\build\libs\*.jar output\ >nul
copy /y libs\platform-api\build\libs\*.jar output\ >nul

echo.
echo ============================================
echo   BUILD SUCCESSFUL
echo ============================================
echo Output: output\
dir /b output\*.jar
pause
