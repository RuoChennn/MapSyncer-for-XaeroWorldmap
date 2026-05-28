@echo off
cd /d "%~dp0..\.."
echo ========================================
echo   MapSyncer - Build All Versions
echo ========================================
echo.

call gradlew.bat build -x test --parallel collectJars

echo.
echo ========================================
echo   Build Complete! Output: build\lib\
echo ========================================
pause
