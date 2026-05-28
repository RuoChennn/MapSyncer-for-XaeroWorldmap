@echo off
cd /d "%~dp0..\.."
echo Building: Fabric 26.1
call gradlew.bat :platforms:fabric:26.1:build -x test collectJars
echo.
echo Output: output\
pause
