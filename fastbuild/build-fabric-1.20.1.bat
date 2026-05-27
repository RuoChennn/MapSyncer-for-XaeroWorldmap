@echo off
cd /d "%~dp0.."
echo Building: Fabric 1.20.1
call gradlew.bat :fabric:fabric-1.20.1:build -x test
echo.
echo Output: fabric\fabric-1.20.1\build\libs\
pause