@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0..\.."

set GRADLE_89=gradle-8.9\bin\gradle.bat

echo ============================================
echo   Building: Forge 1.21.11  (Gradle 8.9 + JDK 21)
echo ============================================

set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
call %GRADLE_89% :mc-1.21.11:forge:clean :mc-1.21.11:forge:build -x test --no-daemon
if %errorlevel% neq 0 (
    echo Build failed!
    pause
    exit /b 1
)
echo.
echo Collecting JARs to output...
if not exist output mkdir output
copy /y mc-1.21.11\forge\build\libs\*.jar output\ >nul
echo.
echo Output: output\
dir /b output\*-forge-1.21.11*.jar 2>nul
pause
