@echo off
setlocal
cd /d "%~dp0..\.."

set SETTINGS_BAK=settings.bak.gradle
set SETTINGS_FILE=settings.gradle
set SETTINGS_26=scripts\fastbuild\settings-26.gradle

echo ============================================
echo   Building: Fabric 26.1 (isolated Loom)
echo ============================================

if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
copy "%SETTINGS_26%" "%SETTINGS_FILE%" >nul

call gradlew.bat :mc-26.1:fabric:clean :mc-26.1:fabric:build -x test
set RESULT=%errorlevel%

if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"

if %RESULT% neq 0 (
    echo Build failed!
    exit /b 1
)

echo.
echo Collecting JARs to output...
if not exist output mkdir output
call "%~dp0copy-release-jars.bat" mc-26.1\fabric\build\libs

echo.
echo Output: output\
dir /b output\*-fabric-26*.jar 2>nul
exit /b 0
