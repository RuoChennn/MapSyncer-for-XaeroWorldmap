@echo off
setlocal
cd /d "%~dp0..\.."

set SETTINGS_BAK=settings.bak.gradle
set SETTINGS_FILE=settings.gradle
set SETTINGS_26=scripts\fastbuild\settings-26.gradle
set SWITCHED=0

echo ============================================
echo   MapSyncer Build: ALL 26.1
echo   (Fabric + NeoForge, Loom 1.16 + Gradle 9.4.0)
echo ============================================

findstr /r /b /c:"include 'mc-26.1:fabric'" "%SETTINGS_FILE%" >nul 2>&1
if %errorlevel% equ 0 (
    echo [1/5] Settings: already using 26.x
) else (
    echo [1/5] Settings: switching to 26.x...
    if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
    ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
    copy "%SETTINGS_26%" "%SETTINGS_FILE%" >nul
    set SWITCHED=1
)

echo [2/5] Building mc-26.1:fabric...
call gradlew.bat :mc-26.1:fabric:clean :mc-26.1:fabric:build -x test
set FABRIC_RESULT=%errorlevel%

echo [3/5] Building mc-26.1:neoforge...
call gradlew.bat :mc-26.1:neoforge:clean :mc-26.1:neoforge:build -x test
set NEO_RESULT=%errorlevel%

if %SWITCHED% equ 1 (
    echo [4/5] Restoring settings.gradle...
    if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
    ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"
) else (
    echo [4/5] Settings unchanged
)

echo [5/5] Collecting results...
if %FABRIC_RESULT% neq 0 echo         Fabric 26.1: FAILED
if %NEO_RESULT% neq 0 echo         NeoForge 26.1: FAILED
if %FABRIC_RESULT% neq 0 goto :fail
if %NEO_RESULT% neq 0 goto :fail

echo.
echo Collecting JARs to output...
if not exist output mkdir output
call "%~dp0copy-release-jars.bat" mc-26.1\fabric\build\libs mc-26.1\neoforge\build\libs

echo.
echo ============================================
echo   BUILD SUCCESSFUL
echo ============================================
echo Output: output\
dir /b output\*.jar 2>nul | findstr /v /i "-slim"
exit /b 0

:fail
echo.
echo ============================================
echo   BUILD FAILED
echo ============================================
exit /b 1
