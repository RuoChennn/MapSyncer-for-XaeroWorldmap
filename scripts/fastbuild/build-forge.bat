@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0..\.."

set SETTINGS_BAK=settings.bak.gradle
set SETTINGS_FILE=settings.gradle
set SETTINGS_FORGE=scripts\fastbuild\settings-forge.gradle
set GRADLE_89=gradle-8.9\bin\gradle.bat

echo ============================================
echo   MapSyncer Build: Forge (1.20.1 + 1.21.1)
echo   (ForgeGradle 6.x + Gradle 8.9, JDK 17/21)
echo ============================================

:: Switch to forge settings
echo [1/4] Switching to forge-only settings...
if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
copy "%SETTINGS_FORGE%" "%SETTINGS_FILE%" >nul

:: Build mc-1.20.1:forge (needs JDK 17)
echo [2/4] Building mc-1.20.1:forge (JDK 17)...
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
call %GRADLE_89% :mc-1.20.1:forge:build -x test --no-daemon
set FORGE1201=%errorlevel%

:: Build mc-1.21.1:forge (needs JDK 21)
echo [3/4] Building mc-1.21.1:forge (JDK 21)...
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
call %GRADLE_89% --stop >nul 2>&1
call %GRADLE_89% :mc-1.21.1:forge:build -x test --no-daemon
set FORGE1211=%errorlevel%

:: Restore settings
echo [4/4] Restoring settings.gradle...
if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"

if %FORGE1201% neq 0 echo   mc-1.20.1:forge FAILED
if %FORGE1211% neq 0 echo   mc-1.21.1:forge FAILED

if %FORGE1201% neq 0 goto :fail
if %FORGE1211% neq 0 goto :fail

:: Collect JARs
echo.
echo Collecting JARs to output...
if not exist output mkdir output
copy /y mc-1.20.1\forge\build\libs\*.jar output\ >nul 2>&1
copy /y mc-1.21.1\forge\build\libs\*.jar output\ >nul 2>&1

echo.
echo ============================================
echo   Forge BUILD SUCCESSFUL
echo ============================================
dir /b output\*-forge-*.jar 2>nul
pause
exit /b 0

:fail
echo.
echo ============================================
echo   Forge BUILD FAILED
echo ============================================
pause
exit /b 1
