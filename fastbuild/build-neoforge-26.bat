@echo off
cd /d "%~dp0.."
echo Building: NeoForge 26
call gradlew.bat :neoforge:neoforge-26:build -x test
echo.
echo Output: neoforge\neoforge-26\build\libs\
pause