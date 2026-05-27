@echo off
cd /d "%~dp0.."
echo ========================================
echo   MapSyncer - Build All Versions
echo ========================================
echo.

call gradlew.bat build -x test --parallel

echo.
echo ========================================
echo   Build Complete!
echo ========================================
pause