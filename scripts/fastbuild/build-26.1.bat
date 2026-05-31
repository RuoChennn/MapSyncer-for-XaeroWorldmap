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

:: Step 1: Switch settings
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

:: Step 2: Build Fabric 26.1
echo [2/5] Building mc-26.1:fabric...
call gradlew.bat :mc-26.1:fabric:clean :mc-26.1:fabric:build -x test
set FABRIC_RESULT=%errorlevel%

:: Step 3: Build NeoForge 26.1
echo [3/5] Building mc-26.1:neoforge...
call gradlew.bat :mc-26.1:neoforge:clean :mc-26.1:neoforge:build -x test
set NEO_RESULT=%errorlevel%

:: Step 4: Restore settings
if %SWITCHED% equ 1 (
    echo [4/5] Restoring settings.gradle...
    if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
    ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"
) else (
    echo [4/5] Settings unchanged
)

:: Step 5: Results
echo [5/5] Collecting results...
if %FABRIC_RESULT% neq 0 (
    echo         Fabric 26.1: FAILED
)
if %NEO_RESULT% neq 0 (
    echo         NeoForge 26.1: FAILED
)

if %FABRIC_RESULT% neq 0 goto :fail
if %NEO_RESULT% neq 0 goto :fail

:: Collect JARs
echo.
echo Collecting JARs to output...
if not exist output mkdir output
copy /y mc-26.1\fabric\build\libs\*.jar output\ >nul 2>&1
copy /y mc-26.1\neoforge\build\libs\*.jar output\ >nul 2>&1
copy /y libs\core\build\libs\*.jar output\ >nul 2>&1
copy /y libs\platform-api\build\libs\*.jar output\ >nul 2>&1

echo.
echo ============================================
echo   BUILD SUCCESSFUL
echo ============================================
echo Output: output\
dir /b output\*.jar
pause
exit /b 0

:fail
echo.
echo ============================================
echo   BUILD FAILED
echo ============================================
pause
exit /b 1
