@echo off
cd /d "%~dp0.."
echo Building: NeoForge 26.1
call gradlew.bat :neoforge:neoforge-26.1:build -x test collectJars
echo.
echo Output: build\lib\
pause