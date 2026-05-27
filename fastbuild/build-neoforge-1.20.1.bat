@echo off
cd /d "%~dp0.."
echo Building: NeoForge 1.20.1
call gradlew.bat :neoforge:neoforge-1.20.1:build -x test
echo.
echo Output: neoforge\neoforge-1.20.1\build\libs\
pause