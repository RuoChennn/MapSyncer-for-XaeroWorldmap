@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0..\.."

set SETTINGS_BAK=settings.bak.gradle
set SETTINGS_FILE=settings.gradle
set SETTINGS_FORGE=scripts\fastbuild\settings-forge.gradle
set GRADLE_89=gradle-8.9\bin\gradle.bat
set PROPS_BAK=gradle.properties.bak
set PROPS_FILE=gradle.properties
set OUTPUT_DIR=output
set COPY_JARS=%~dp0copy-release-jars.bat

echo ============================================
echo   MapSyncer Build: Forge (1.20.1 + 1.21.1 + 1.21.11)
echo   (ForgeGradle 6.x + Gradle 8.9, JDK 17/21)
echo ============================================

:: Step 0: Override org.gradle.java.home to JDK 21 (Gradle 8.9 incompatible with JDK 25)
echo [0/5] Overriding gradle.properties JDK to 21 (Gradle 8.9 max is 23)...
if exist "%PROPS_BAK%" del "%PROPS_BAK%"
copy "%PROPS_FILE%" "%PROPS_BAK%" >nul
powershell -NoProfile -Command ^
  "$c = Get-Content '%PROPS_FILE%' -Raw; $c = $c -replace 'org\.gradle\.java\.home=.*', 'org.gradle.java.home=C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'; Set-Content '%PROPS_FILE%' -Value $c -NoNewline"
if %errorlevel% neq 0 (
    echo   WARNING: Failed to override gradle.properties, trying fallback...
    :: Fallback: use sed-like findstr to rebuild
    findstr /v /r "^org.gradle.java.home=" "%PROPS_BAK%" > "%PROPS_FILE%"
    echo org.gradle.java.home=C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot >> "%PROPS_FILE%"
)

:: Switch to forge settings
echo [1/5] Switching to forge-only settings...
if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
copy "%SETTINGS_FORGE%" "%SETTINGS_FILE%" >nul

:: Build mc-1.20.1:forge (needs JDK 17)
echo [2/5] Building mc-1.20.1:forge (JDK 17)...
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
call %GRADLE_89% --stop >nul 2>&1
call %GRADLE_89% :mc-1.20.1:forge:build -x test --no-daemon
set FORGE1201=%errorlevel%
if %FORGE1201% neq 0 echo   mc-1.20.1:forge FAILED (error %FORGE1201%)

:: Build mc-1.21.1:forge + mc-1.21.11:forge (needs JDK 21)
echo [3/5] Building mc-1.21.1:forge + mc-1.21.11:forge (JDK 21)...
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
call %GRADLE_89% --stop >nul 2>&1
call %GRADLE_89% :mc-1.21.1:forge:build :mc-1.21.11:forge:build -x test --no-daemon
set FORGE121X=%errorlevel%
if %FORGE121X% neq 0 echo   mc-1.21.x:forge FAILED (error %FORGE121X%)

:: Restore settings
echo [4/5] Restoring settings.gradle...
if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"

:: Restore gradle.properties
echo [5/5] Restoring gradle.properties...
if exist "%PROPS_FILE%" del "%PROPS_FILE%"
ren "%PROPS_BAK%" "%PROPS_FILE%"

:: Check results
if %FORGE1201% neq 0 goto :fail
if %FORGE121X% neq 0 goto :fail

:: Collect JARs
echo.
echo Collecting JARs to output...
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
call "%COPY_JARS%" mc-1.20.1\forge\build\libs mc-1.21.1\forge\build\libs mc-1.21.11\forge\build\libs

echo.
echo ============================================
echo   Forge BUILD SUCCESSFUL
echo ============================================
dir /b "%OUTPUT_DIR%\*.jar" 2>nul | findstr /v /i "-slim"
echo.
echo ============================================
exit /b 0

:fail
:: Ensure restore happens even on failure
if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
if exist "%SETTINGS_BAK%" ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"
if exist "%PROPS_FILE%" del "%PROPS_FILE%"
if exist "%PROPS_BAK%" ren "%PROPS_BAK%" "%PROPS_FILE%"

echo.
echo ============================================
echo   Forge BUILD FAILED
echo ============================================
exit /b 1
