@echo off
cd /d "%~dp0.."
echo Building: Forge 1.20
call gradlew.bat :forge:forge-1.20:build -x test
echo.
echo Output: forge\forge-1.20\build\libs\
pause