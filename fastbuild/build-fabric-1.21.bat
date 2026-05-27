@echo off
cd /d "%~dp0.."
echo Building: Fabric 1.21
call gradlew.bat :fabric:fabric-1.21:build -x test
echo.
echo Output: fabric\fabric-1.21\build\libs\
pause