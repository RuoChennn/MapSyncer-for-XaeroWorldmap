@echo off
setlocal
cd /d "%~dp0..\.."

set SETTINGS_BAK=settings.bak.gradle
set SETTINGS_FILE=settings.gradle

echo ============================================
echo   MapSyncer Build: Fabric 26.2
echo   (Loom 1.17 + Gradle 9.5.1, isolated)
echo ============================================

if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
copy "scripts\fastbuild\settings-262.gradle" "%SETTINGS_FILE%" >nul

call gradlew.bat :mc-26.2:fabric:clean :mc-26.2:fabric:build
set BUILD_RESULT=%errorlevel%

del "%SETTINGS_FILE%"
ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"

if %BUILD_RESULT% neq 0 exit /b %BUILD_RESULT%

if not exist output mkdir output
copy /y mc-26.2\fabric\build\libs\*.jar output\ >nul 2>&1
echo Build successful. Output: output\
