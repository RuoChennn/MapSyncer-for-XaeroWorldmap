@echo off
cd /d "%~dp0..\.."
echo Building: NeoForge 1.21.1
call gradlew.bat :platforms:neoforge:1.21.1:build -x test collectJars
echo.
echo Output: build\lib\
pause
