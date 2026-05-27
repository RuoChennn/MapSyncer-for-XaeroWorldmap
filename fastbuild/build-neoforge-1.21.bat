@echo off
cd /d "%~dp0.."
echo Building: NeoForge 1.21
call gradlew.bat :neoforge:neoforge-1.21:build -x test
echo.
echo Output: neoforge\neoforge-1.21\build\libs\
pause