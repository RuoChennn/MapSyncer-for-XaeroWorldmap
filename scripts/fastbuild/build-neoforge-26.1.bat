@echo off
setlocal
cd /d "%~dp0..\.."

echo ============================================
echo   Building: NeoForge 26.1
echo ============================================

call gradlew.bat :mc-26.1:neoforge:clean :mc-26.1:neoforge:build -x test
if %errorlevel% neq 0 (
    echo Build failed!
    exit /b 1
)

echo.
echo Collecting JARs to output...
if not exist output mkdir output
call "%~dp0copy-release-jars.bat" mc-26.1\neoforge\build\libs

echo.
echo Output: output\
dir /b output\*-neoforge-26*.jar 2>nul | findstr /v /i "-slim"
exit /b 0
