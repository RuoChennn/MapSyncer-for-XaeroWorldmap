@echo off
setlocal
cd /d "%~dp0..\.."

set SETTINGS_BAK=settings.bak.gradle
set SETTINGS_FILE=settings.gradle
set SETTINGS_26=scripts\fastbuild\settings-26.gradle

echo ============================================
echo   MapSyncer Build: Fabric 26.1
echo   (Loom 1.16 + Gradle 9.4.0, isolated)
echo ============================================

:: Switch settings if Fabric is commented out
findstr /r /b /c:"include 'mc-26.1:fabric'" "%SETTINGS_FILE%" >nul 2>&1
if %errorlevel% equ 0 goto :skip_switch

echo Switching to 26.x settings...
if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
ren "%SETTINGS_FILE%" "%SETTINGS_BAK%"
copy "%SETTINGS_26%" "%SETTINGS_FILE%" >nul

:skip_switch
echo Building mc-26.1:fabric...
call gradlew.bat :mc-26.1:fabric:clean :mc-26.1:fabric:build -x test
set BUILD_RESULT=%errorlevel%

:: Restore original settings if we switched
if exist "%SETTINGS_BAK%" (
    del "%SETTINGS_FILE%"
    ren "%SETTINGS_BAK%" "%SETTINGS_FILE%"
)

if %BUILD_RESULT% neq 0 (
    echo.
    echo ============================================
    echo   BUILD FAILED
    echo ============================================
    exit /b 1
)

echo.
echo Collecting JARs to output...
if not exist output mkdir output
copy /y mc-26.1\fabric\build\libs\*.jar output\ >nul 2>&1
copy /y libs\core\build\libs\*.jar output\ >nul 2>&1
copy /y libs\platform-api\build\libs\*.jar output\ >nul 2>&1

echo.
echo ============================================
echo   BUILD SUCCESSFUL
echo ============================================
echo Output: output\
dir /b output\*.jar
