@echo off
cd /d "%~dp0.."
echo Building: NeoForge 1.21.11
call gradlew.bat :neoforge:neoforge-1.21.11:build -x test
echo.
echo Output: neoforge\neoforge-1.21.11\build\libs\
pause