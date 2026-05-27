@echo off
cd /d "%~dp0.."
echo Building: Forge 1.21
call gradlew.bat :forge:forge-1.21:build -x test
echo.
echo Output: forge\forge-1.21\build\libs\
pause