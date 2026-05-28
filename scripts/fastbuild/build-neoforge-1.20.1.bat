@echo off
cd /d "%~dp0..\.."
echo Building: NeoForge 1.20.1
call gradlew.bat :platforms:neoforge:1.20.1:build -x test collectJars
echo.
echo Output: output\
pause
