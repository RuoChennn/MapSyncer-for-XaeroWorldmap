@echo off
cd /d "%~dp0.."
echo Building: NeoForge 26.1
call gradlew.bat :neoforge:neoforge-26.1:build -x test
echo.
echo Output: neoforge\neoforge-26.1\build\libs\
pause