@echo off
cd /d "%~dp0.."
echo Building: Fabric 26.1
call gradlew.bat :fabric:fabric-26.1:build -x test collectJars
echo.
echo Output: build\lib\
pause