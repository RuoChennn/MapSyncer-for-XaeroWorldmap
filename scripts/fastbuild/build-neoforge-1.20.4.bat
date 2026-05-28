@echo off
cd /d "%~dp0..\.."
echo Building: NeoForge 1.20.4
call gradlew.bat :platforms:neoforge:1.20.4:build -x test collectJars
echo.
echo Output: build\lib\
pause
