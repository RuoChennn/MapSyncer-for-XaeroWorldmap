@echo off
setlocal
cd /d "%~dp0..\.."

set SETTINGS_BAK=settings.bak.gradle
set SETTINGS_FILE=settings.gradle
set SETTINGS_12111=scripts\fastbuild\settings-12111.gradle

echo ============================================
echo   Building: Fabric 1.21.11 (isolated Loom)
echo ============================================

if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
copy "%SETTINGS_12111%" "%SETTINGS_FILE%" >nul

call gradlew.bat :mc-1.21.11:fabric:clean :mc-1.21.11:fabric:build -x test
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
call "%~dp0copy-release-jars.bat" mc-1.21.11\fabric\build\libs

echo.
echo Output: output\
dir /b output\*-fabric-1.21.11*.jar 2>nul
exit /b 0
