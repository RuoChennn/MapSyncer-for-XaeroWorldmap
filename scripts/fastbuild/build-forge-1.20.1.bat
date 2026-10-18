@echo off
setlocal
cd /d "%~dp0..\.."

set SETTINGS_FILE=settings.gradle
set SETTINGS_BAK=settings.bak.gradle
set SETTINGS_FORGE=scripts\fastbuild\settings-forge.gradle
set PROPS_FILE=gradle.properties
set PROPS_BAK=gradle.properties.bak
set GRADLE_89=gradle-8.9\bin\gradle.bat

echo ============================================
echo   Building: Forge 1.20.1 (JDK 17)
echo ============================================

:: Override JDK in gradle.properties (Gradle 8.9 max is JDK 23)
if exist "%PROPS_BAK%" del "%PROPS_BAK%"
copy "%PROPS_FILE%" "%PROPS_BAK%" >nul
call "%~dp0set-gradle-jdk.bat" 17
if errorlevel 1 (
    echo [ERROR] JDK 17 not found
    if exist "%PROPS_FILE%" del "%PROPS_FILE%"
    if exist "%PROPS_BAK%" ren "%PROPS_BAK%" "%PROPS_FILE%"
    exit /b 1
)

:: Switch settings
ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
copy "%SETTINGS_FORGE%" "%SETTINGS_FILE%" >nul

set "JAVA_HOME=%GRADLE_JDK_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call %GRADLE_89% --stop >nul 2>&1
call %GRADLE_89% :mc-1.20.1:forge:build -x test --no-daemon
set RESULT=%errorlevel%

:: Restore
del "%SETTINGS_FILE%" 2>nul
ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"
del "%PROPS_FILE%" 2>nul
ren "%PROPS_BAK%" "%PROPS_FILE%"

if %RESULT% neq 0 (
    echo Build failed!
    exit /b 1
)

echo Collecting JARs...
if not exist output mkdir output
call "%~dp0copy-release-jars.bat" mc-1.20.1\forge\build\libs
echo Output:
dir /b output\*.jar 2>nul | findstr /v /i /c:"-slim"
exit /b 0
