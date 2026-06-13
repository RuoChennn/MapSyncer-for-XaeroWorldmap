@echo off
setlocal
cd /d "%~dp0..\.."

echo ============================================
echo   Building: MapPackager Tool
echo ============================================
echo.

call gradlew.bat :libs:core:mapPackagerDist
set RESULT=%errorlevel%

if %RESULT% neq 0 (
    echo.
    echo Build FAILED!
    exit /b 1
)

echo.
echo Collecting output...
if not exist output mkdir output
copy /y libs\core\build\dist\mapsyncer-packager-*.zip output\ >nul

echo.
echo ============================================
echo   Build Complete - output\
echo ============================================
dir /b output\mapsyncer-packager-*.zip 2>nul
echo ============================================
exit /b 0
