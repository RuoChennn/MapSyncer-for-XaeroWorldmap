@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0..\.."

set SETTINGS_BAK=settings.bak.gradle
set SETTINGS_FILE=settings.gradle
set SETTINGS_26=scripts\fastbuild\settings-26.gradle
set GRADLE_89=gradle-8.9\bin\gradle.bat
set OUTPUT_DIR=output

echo ============================================
echo   MapSyncer - Build ALL Platforms
echo ============================================
echo.

:: Clean output
if exist "%OUTPUT_DIR%" rd /s /q "%OUTPUT_DIR%"
mkdir "%OUTPUT_DIR%" 2>nul

:: ============================================================
:: Phase 1: Default platforms (Gradle 9.x + JDK 21)
::   Fabric: 1.20.1, 1.21.1, 1.21.11
::   NeoForge: 1.21.1, 1.21.11, 26.1
:: ============================================================
echo [Phase 1/3] Building Gradle 9.x platforms...
call gradlew.bat ^
    :mc-1.20.1:fabric:clean :mc-1.20.1:fabric:build ^
    :mc-1.21.1:fabric:clean  :mc-1.21.1:fabric:build ^
    :mc-1.21.11:fabric:clean :mc-1.21.11:fabric:build ^
    :mc-1.21.1:neoforge:clean  :mc-1.21.1:neoforge:build ^
    :mc-1.21.11:neoforge:clean :mc-1.21.11:neoforge:build ^
    :mc-26.1:neoforge:clean   :mc-26.1:neoforge:build ^
    -x test --parallel
if %errorlevel% neq 0 (
    echo Phase 1 has errors, continuing...
)

:: Collect Phase 1 JARs
copy /y mc-1.20.1\fabric\build\libs\*.jar   "%OUTPUT_DIR%\" >nul 2>&1
copy /y mc-1.21.1\fabric\build\libs\*.jar    "%OUTPUT_DIR%\" >nul 2>&1
copy /y mc-1.21.11\fabric\build\libs\*.jar   "%OUTPUT_DIR%\" >nul 2>&1
copy /y mc-1.21.1\neoforge\build\libs\*.jar  "%OUTPUT_DIR%\" >nul 2>&1
copy /y mc-1.21.11\neoforge\build\libs\*.jar "%OUTPUT_DIR%\" >nul 2>&1
copy /y mc-26.1\neoforge\build\libs\*.jar    "%OUTPUT_DIR%\" >nul 2>&1
echo   Phase 1: done

:: ============================================================
:: Phase 2: Forge platforms (Gradle 8.9 + JDK 21/17)
:: ============================================================
echo.
echo [Phase 2/3] Building Forge platforms (Gradle 8.9)...

:: 1.21.1 + 1.21.11 (JDK 21)
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
call %GRADLE_89% --stop >nul 2>&1
call %GRADLE_89% :mc-1.21.1:forge:clean :mc-1.21.1:forge:build :mc-1.21.11:forge:clean :mc-1.21.11:forge:build -x test --no-daemon
if %errorlevel% neq 0 echo   1.21.x Forge had errors, continuing...

copy /y mc-1.21.1\forge\build\libs\*.jar  "%OUTPUT_DIR%\" >nul 2>&1
copy /y mc-1.21.11\forge\build\libs\*.jar "%OUTPUT_DIR%\" >nul 2>&1

:: 1.20.1 (JDK 17)
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
call %GRADLE_89% --stop >nul 2>&1
call %GRADLE_89% :mc-1.20.1:forge:clean :mc-1.20.1:forge:build -x test --no-daemon
if %errorlevel% neq 0 echo   1.20.1 Forge had errors, continuing...

copy /y mc-1.20.1\forge\build\libs\*.jar "%OUTPUT_DIR%\" >nul 2>&1
echo   Phase 2: done

:: ============================================================
:: Phase 3: Fabric 26.1 (isolated Loom 1.16 settings)
:: ============================================================
echo.
echo [Phase 3/3] Building mc-26.1:fabric (isolated Loom 1.16)...
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
if %FABRIC_RESULT% neq 0 echo   Fabric 26.1 FAILED
copy /y mc-26.1\fabric\build\libs\*.jar "%OUTPUT_DIR%\" >nul 2>&1
echo   Phase 3: done

:: ============================================================
:: Summary
:: ============================================================
echo.
echo ============================================
echo   Build Complete - %OUTPUT_DIR%\
echo ============================================
for /r "%OUTPUT_DIR%" %%f in (*.jar) do echo   %%~nxf
echo ============================================
pause
