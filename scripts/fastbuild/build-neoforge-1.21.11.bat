@echo off
cd /d "%~dp0..\.."
echo ============================================
echo   Building: NeoForge 1.21.11
echo ============================================
call gradlew.bat :mc-1.21.11:neoforge:clean :mc-1.21.11:neoforge:build -x test
if %errorlevel% neq 0 (
    echo Build failed!
    pause
    exit /b 1
)
echo.
echo Collecting JARs to output...
if not exist output mkdir output
copy /y mc-1.21.11\neoforge\build\libs\*.jar output\ >nul
echo.
echo Output: output\
dir /b output\*-neoforge-1.21.11*.jar 2>nul
pause
