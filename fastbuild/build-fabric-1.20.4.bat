@echo off
cd /d "%~dp0.."
echo Building: Fabric 1.20.4
call gradlew.bat :fabric:fabric-1.20.4:build -x test collectJars
echo.
echo Output: build\lib\
pause