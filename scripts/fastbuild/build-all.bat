@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0..\.."

set SETTINGS_BAK=settings.bak.gradle
set SETTINGS_FILE=settings.gradle
set SETTINGS_26=scripts\fastbuild\settings-26.gradle
set SETTINGS_12111=scripts\fastbuild\settings-12111.gradle
set SETTINGS_FORGE=scripts\fastbuild\settings-forge.gradle
set GRADLE_89=gradle-8.9\bin\gradle.bat
set PROPS_BAK=gradle.properties.bak
set PROPS_FILE=gradle.properties
set OUTPUT_DIR=output

echo ============================================
echo   MapSyncer - Build ALL Platforms
echo ============================================
echo.

:: Clean output
if exist "%OUTPUT_DIR%" rd /s /q "%OUTPUT_DIR%"
mkdir "%OUTPUT_DIR%" 2>nul

:: ============================================================
:: Phase 1: Gradle 9.x platforms (Fabric + NeoForge)
:: ============================================================
echo [Phase 1/4] Building Gradle 9.x platforms...
call gradlew.bat ^
    :mc-1.20.1:fabric:clean :mc-1.20.1:fabric:build ^
    :mc-1.21.1:fabric:clean  :mc-1.21.1:fabric:build ^
    :mc-1.21.1:neoforge:clean  :mc-1.21.1:neoforge:build ^
    :mc-1.21.11:neoforge:clean :mc-1.21.11:neoforge:build ^
    :mc-26.1:neoforge:clean   :mc-26.1:neoforge:build ^
    -x test --parallel
if %errorlevel% neq 0 echo   Phase 1 had errors, continuing...

copy /y mc-1.20.1\fabric\build\libs\*.jar   "%OUTPUT_DIR%\" >nul 2>&1
copy /y mc-1.21.1\fabric\build\libs\*.jar    "%OUTPUT_DIR%\" >nul 2>&1
copy /y mc-1.21.1\neoforge\build\libs\*.jar  "%OUTPUT_DIR%\" >nul 2>&1
copy /y mc-1.21.11\neoforge\build\libs\*.jar "%OUTPUT_DIR%\" >nul 2>&1
copy /y mc-26.1\neoforge\build\libs\*.jar    "%OUTPUT_DIR%\" >nul 2>&1
echo   Phase 1: done

:: ============================================================
:: Phase 2: Forge platforms (Gradle 8.9 + JDK 17/21)
::   Switches settings-forge.gradle + overrides JDK in gradle.properties
:: ============================================================
echo.
echo [Phase 2/4] Building Forge platforms (Gradle 8.9)...

:: Override gradle.properties JDK (Gradle 8.9 incompatible with JDK 25)
if exist "%PROPS_BAK%" del "%PROPS_BAK%"
copy "%PROPS_FILE%" "%PROPS_BAK%" >nul
powershell -NoProfile -Command ^
  "$c = Get-Content '%PROPS_FILE%' -Raw; $c = $c -replace 'org\.gradle\.java\.home=.*', 'org.gradle.java.home=C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'; Set-Content '%PROPS_FILE%' -Value $c -NoNewline"

:: Switch to forge settings
if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
copy "%SETTINGS_FORGE%" "%SETTINGS_FILE%" >nul

:: 1.20.1 (JDK 17)
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
call %GRADLE_89% --stop >nul 2>&1
call %GRADLE_89% :mc-1.20.1:forge:clean :mc-1.20.1:forge:build -x test --no-daemon
if %errorlevel% neq 0 echo   1.20.1 Forge had errors, continuing...

copy /y mc-1.20.1\forge\build\libs\*.jar "%OUTPUT_DIR%\" >nul 2>&1

:: 1.21.1 + 1.21.11 (JDK 21)
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
call %GRADLE_89% --stop >nul 2>&1
call %GRADLE_89% :mc-1.21.1:forge:clean :mc-1.21.1:forge:build :mc-1.21.11:forge:clean :mc-1.21.11:forge:build -x test --no-daemon
if %errorlevel% neq 0 echo   1.21.x Forge had errors, continuing...

copy /y mc-1.21.1\forge\build\libs\*.jar  "%OUTPUT_DIR%\" >nul 2>&1
copy /y mc-1.21.11\forge\build\libs\*.jar "%OUTPUT_DIR%\" >nul 2>&1

:: Restore settings and gradle.properties
if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"
if exist "%PROPS_FILE%" del "%PROPS_FILE%"
ren "%PROPS_BAK%" "%PROPS_FILE%"
echo   Phase 2: done

:: ============================================================
:: Phase 3: Fabric 1.21.11 (isolated, Loom 1.15.4)
:: ============================================================
echo.
echo [Phase 3/4] Building mc-1.21.11:fabric (isolated, Loom 1.15.4)...
if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
copy "%SETTINGS_12111%" "%SETTINGS_FILE%" >nul
call gradlew.bat :mc-1.21.11:fabric:clean :mc-1.21.11:fabric:build -x test
set FABRIC12111_RESULT=%errorlevel%
if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"
if %FABRIC12111_RESULT% neq 0 echo   Fabric 1.21.11 FAILED
copy /y mc-1.21.11\fabric\build\libs\*.jar "%OUTPUT_DIR%\" >nul 2>&1
echo   Phase 3: done

:: ============================================================
:: Phase 4: Fabric 26.1 (isolated, Loom 1.16)
:: ============================================================
echo.
echo [Phase 4/4] Building mc-26.1:fabric (isolated, Loom 1.16)...
if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
copy "%SETTINGS_26%" "%SETTINGS_FILE%" >nul
call gradlew.bat :mc-26.1:fabric:clean :mc-26.1:fabric:build -x test
set FABRIC26_RESULT=%errorlevel%
if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"
if %FABRIC26_RESULT% neq 0 echo   Fabric 26.1 FAILED
copy /y mc-26.1\fabric\build\libs\*.jar "%OUTPUT_DIR%\" >nul 2>&1
echo   Phase 4: done

:: ============================================================
:: Summary
:: ============================================================
echo.
echo ============================================
echo   Build Complete - %OUTPUT_DIR%\
echo ============================================
for /r "%OUTPUT_DIR%" %%f in (*.jar) do echo   %%~nxf
echo ============================================
exit /b 0
