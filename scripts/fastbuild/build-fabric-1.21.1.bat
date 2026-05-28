@echo off
cd /d "%~dp0..\.."
echo Building: Fabric 1.21.1
call gradlew.bat :platforms:fabric:1.21.1:build -x test collectJars
echo.
echo Output: build\lib\
pause
