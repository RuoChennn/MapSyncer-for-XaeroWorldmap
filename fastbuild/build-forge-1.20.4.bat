@echo off
cd /d "%~dp0.."
echo Building: Forge 1.20.4
call gradlew.bat :forge:forge-1.20.4:build -x test
echo.
echo Output: forge\forge-1.20.4\build\libs\
pause