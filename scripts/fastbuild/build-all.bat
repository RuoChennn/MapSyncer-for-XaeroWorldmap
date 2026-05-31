@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0..\.."

set SETTINGS_BAK=settings.bak.gradle
set SETTINGS_FILE=settings.gradle
set SETTINGS_26=scripts\fastbuild\settings-26.gradle
set OUTPUT_DIR=outputs

echo ============================================
echo   MapSyncer - Build ALL Platforms
echo ============================================
echo.

:: Clean and recreate outputs
if exist "%OUTPUT_DIR%" rd /s /q "%OUTPUT_DIR%"
for %%d in (1.20.1-fabric 1.21.1-fabric 1.21.1-neoforge 26.1-neoforge 26.1-fabric) do (
    mkdir "%OUTPUT_DIR%\%%d" 2>nul
)

:: ============================================================
:: Phase 1: Build default platforms (1.20.1, 1.21.1, 26.1-neoforge)
:: ============================================================
echo [Phase 1/2] Building default platforms...
call gradlew.bat ^
    :mc-1.20.1:fabric:clean :mc-1.20.1:fabric:build ^
    :mc-1.21.1:fabric:clean :mc-1.21.1:fabric:build ^
    :mc-1.21.1:neoforge:clean :mc-1.21.1:neoforge:build ^
    :mc-26.1:neoforge:clean :mc-26.1:neoforge:build ^
    -x test --parallel
if %errorlevel% neq 0 (
    echo Phase 1 failed!
    goto :phase2
)
copy /y mc-1.20.1\fabric\build\libs\*.jar "%OUTPUT_DIR%\1.20.1-fabric\" >nul
copy /y mc-1.21.1\fabric\build\libs\*.jar "%OUTPUT_DIR%\1.21.1-fabric\" >nul
copy /y mc-1.21.1\neoforge\build\libs\*.jar "%OUTPUT_DIR%\1.21.1-neoforge\" >nul
copy /y mc-26.1\neoforge\build\libs\*.jar "%OUTPUT_DIR%\26.1-neoforge\" >nul
echo   Phase 1: OK

:: ============================================================
:: Phase 2: Build mc-26.1:fabric (needs isolated Loom 1.16 settings)
:: ============================================================
:phase2
echo [Phase 2/2] Building mc-26.1:fabric...
findstr /r /b /c:"include 'mc-26.1:fabric'" "%SETTINGS_FILE%" >nul 2>&1
if %errorlevel% equ 0 (
    echo   Already using 26.x settings
) else (
    if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
    ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
    copy "%SETTINGS_26%" "%SETTINGS_FILE%" >nul
)
call gradlew.bat :mc-26.1:fabric:clean :mc-26.1:fabric:build -x test
set FABRIC_RESULT=%errorlevel%
if exist "%SETTINGS_BAK%" (
    if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
    ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"
)
if %FABRIC_RESULT% neq 0 (
    echo Phase 2 failed!
    goto :summary
)
copy /y mc-26.1\fabric\build\libs\*.jar "%OUTPUT_DIR%\26.1-fabric\" >nul
echo   Phase 2: OK

:: ============================================================
:: Summary
:: ============================================================
:summary
echo.
echo ============================================
echo   Build Complete - outputs\
echo ============================================
for /r "%OUTPUT_DIR%" %%f in (*.jar) do echo   %%~nxf
echo ============================================
pause
