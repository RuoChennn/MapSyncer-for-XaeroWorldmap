@echo off
cd /d "%~dp0.."
echo Building: Forge 1.21.1
call gradlew.bat :forge:forge-1.21.1:build -x test collectJars
echo.
echo Output: build\lib\
pause