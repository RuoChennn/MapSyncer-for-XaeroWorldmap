@echo off
setlocal enabledelayedexpansion

:: Resolve project root using 8.3 short path to avoid CJK encoding issues
for %%I in ("%~dp0..\..") do set "PROJECT_ROOT=%%~sI"
cd /d "%PROJECT_ROOT%"

rem Gradle 8.9 cannot accept the CJK project path as java.io.tmpdir.
rem Use the already-resolved 8.3 short path and make sure the directory exists.
set "BUILD_TEMP=%PROJECT_ROOT%\cache\tmp\client-command-matrix\build-temp"
if not exist "%BUILD_TEMP%" mkdir "%BUILD_TEMP%" >nul 2>&1
if not exist "%BUILD_TEMP%" (
    echo [ERROR] Unable to create build temp directory: %BUILD_TEMP%
    exit /b 1
)
set "TEMP=%BUILD_TEMP%"
set "TMP=%BUILD_TEMP%"
set "TMPDIR=%BUILD_TEMP%"
if /i "%MAPSYNCER_BUILD_OFFLINE%"=="1" set "GRADLE_OFFLINE=--offline"

set SETTINGS_BAK=settings.bak.gradle
set SETTINGS_FILE=settings.gradle
set SETTINGS_26=scripts\fastbuild\settings-26.gradle
set SETTINGS_12111=scripts\fastbuild\settings-12111.gradle
set SETTINGS_FORGE=scripts\fastbuild\settings-forge.gradle
set GRADLE_89=%PROJECT_ROOT%\gradle-8.9\bin\gradle.bat
set PROPS_BAK=gradle.properties.bak
set PROPS_FILE=gradle.properties
set OUTPUT_DIR=output
set COPY_JARS=%~dp0copy-release-jars.bat

echo ============================================
echo   MapSyncer - Build ALL Platforms (G1-G4)
echo   libs/mc-* anchor + mc-*/glue loaders
echo ============================================
echo.

:: Clean output
if exist "%OUTPUT_DIR%" rd /s /q "%OUTPUT_DIR%"
mkdir "%OUTPUT_DIR%" 2>nul

:: ============================================================
:: Phase 1: Gradle 9.x platforms (Fabric + NeoForge)
:: ============================================================
echo [Phase 1/5] Building Gradle 9.x platforms...
call "%PROJECT_ROOT%\gradlew.bat" ^
    :mc-1.20.1:fabric:clean :mc-1.20.1:fabric:build ^
    :mc-1.21.1:fabric:clean  :mc-1.21.1:fabric:build ^
    :mc-1.21.1:neoforge:clean  :mc-1.21.1:neoforge:build ^
    :mc-1.21.11:neoforge:clean :mc-1.21.11:neoforge:build ^
    :mc-26.1:neoforge:clean   :mc-26.1:neoforge:build ^
    -x test --parallel %GRADLE_OFFLINE%
if %errorlevel% neq 0 echo   Phase 1 had errors, continuing...

call "%COPY_JARS%" mc-1.20.1\fabric\build\libs
call "%COPY_JARS%" mc-1.21.1\fabric\build\libs
call "%COPY_JARS%" mc-1.21.1\neoforge\build\libs
call "%COPY_JARS%" mc-1.21.11\neoforge\build\libs
call "%COPY_JARS%" mc-26.1\neoforge\build\libs
echo   Phase 1: done

:: ============================================================
:: Phase 2: Forge platforms (Gradle 8.9 + JDK 17/21)
::   Switches settings-forge.gradle + overrides JDK in gradle.properties
:: ============================================================
echo.
echo [Phase 2/5] Building Forge platforms (Gradle 8.9)...

:: Override gradle.properties JDK (Gradle 8.9 incompatible with JDK 25)
if exist "%PROPS_BAK%" del "%PROPS_BAK%"
copy "%PROPS_FILE%" "%PROPS_BAK%" >nul
call "%~dp0set-gradle-jdk.bat" 21
if errorlevel 1 (
    echo   JDK 21 not found; skipping Forge phase
    if exist "%PROPS_FILE%" del "%PROPS_FILE%"
    if exist "%PROPS_BAK%" ren "%PROPS_BAK%" "%PROPS_FILE%"
    goto :phase3
)

:: Switch to forge settings
if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
copy "%SETTINGS_FORGE%" "%SETTINGS_FILE%" >nul

:: 1.20.1 (JDK 17)
call "%~dp0set-gradle-jdk.bat" 17
set "JDK17_RC=%errorlevel%"
if %JDK17_RC% neq 0 echo   JDK 17 not found; skipping 1.20.1 Forge
if %JDK17_RC% neq 0 goto :forge_121x
set "JAVA_HOME=%GRADLE_JDK_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call %GRADLE_89% --stop >nul 2>&1
call %GRADLE_89% :mc-1.20.1:forge:clean :mc-1.20.1:forge:build -x test --no-daemon %GRADLE_OFFLINE%
if %errorlevel% neq 0 echo   1.20.1 Forge had errors, continuing...

call "%COPY_JARS%" mc-1.20.1\forge\build\libs

:: 1.21.1 + 1.21.11 (JDK 21)
:forge_121x
call "%~dp0set-gradle-jdk.bat" 21
set "JDK21_RC=%errorlevel%"
if %JDK21_RC% neq 0 echo   JDK 21 not found; skipping 1.21.x Forge
if %JDK21_RC% neq 0 goto :forge_done
set "JAVA_HOME=%GRADLE_JDK_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call %GRADLE_89% --stop >nul 2>&1
call %GRADLE_89% :mc-1.21.1:forge:clean :mc-1.21.1:forge:build :mc-1.21.11:forge:clean :mc-1.21.11:forge:build -x test --no-daemon %GRADLE_OFFLINE%
if %errorlevel% neq 0 echo   1.21.x Forge had errors, continuing...

call "%COPY_JARS%" mc-1.21.1\forge\build\libs
call "%COPY_JARS%" mc-1.21.11\forge\build\libs

:: Restore settings and gradle.properties
:forge_done
if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"
if exist "%PROPS_FILE%" del "%PROPS_FILE%"
ren "%PROPS_BAK%" "%PROPS_FILE%"
echo   Phase 2: done

:: ============================================================
:: Phase 3: Fabric 1.21.11 (isolated, Loom 1.15.4)
:: ============================================================
:phase3
echo.
echo [Phase 3/5] Building mc-1.21.11:fabric (isolated, Loom 1.15.4)...
if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
copy "%SETTINGS_12111%" "%SETTINGS_FILE%" >nul
call "%PROJECT_ROOT%\gradlew.bat" :mc-1.21.11:fabric:clean :mc-1.21.11:fabric:build -x test %GRADLE_OFFLINE%
set FABRIC12111_RESULT=%errorlevel%
if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"
if %FABRIC12111_RESULT% neq 0 echo   Fabric 1.21.11 FAILED
call "%COPY_JARS%" mc-1.21.11\fabric\build\libs
echo   Phase 3: done

:: ============================================================
:: Phase 4: MC 26.x (settings-26.gradle: 26.1 Fabric + 26.2/26.3 Fabric+NeoForge)
:: ============================================================
echo.
echo [Phase 4/5] Building MC 26.x (settings-26.gradle)...
if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
copy "%SETTINGS_26%" "%SETTINGS_FILE%" >nul
call "%PROJECT_ROOT%\gradlew.bat" ^
    :mc-26.1:fabric:clean :mc-26.1:fabric:build ^
    :mc-26.2:fabric:clean :mc-26.2:fabric:build ^
    :mc-26.2:neoforge:clean :mc-26.2:neoforge:build ^
    :mc-26.3:fabric:clean :mc-26.3:fabric:build ^
    :mc-26.3:neoforge:clean :mc-26.3:neoforge:build ^
    -x test %GRADLE_OFFLINE%
set FABRIC26_RESULT=%errorlevel%
if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"
if %FABRIC26_RESULT% neq 0 echo   MC 26.x builds had errors
call "%COPY_JARS%" mc-26.1\fabric\build\libs mc-26.2\fabric\build\libs mc-26.2\neoforge\build\libs mc-26.3\fabric\build\libs mc-26.3\neoforge\build\libs
echo   Phase 4: done

:: ============================================================
:: Summary
:: ============================================================
echo.
echo ============================================
echo   Build Complete - %OUTPUT_DIR%\
echo ============================================
for /r "%OUTPUT_DIR%" %%f in (*.jar) do (
    echo %%~nxf | findstr /i /c:"-slim.jar" >nul || echo   %%~nxf
)
echo ============================================
exit /b 0
