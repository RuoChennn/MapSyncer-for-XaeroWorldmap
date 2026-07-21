@echo off
chcp 65001 >nul
setlocal

for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"
cd /d "%PROJECT_ROOT%"

echo ============================================
echo   MapSyncer - Setup Development Dependencies
echo ============================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-deps.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

if %EXIT_CODE% neq 0 (
    echo.
    echo [ERROR] setup-deps failed with exit code %EXIT_CODE%
    pause
    exit /b %EXIT_CODE%
)

echo.
pause
exit /b 0
