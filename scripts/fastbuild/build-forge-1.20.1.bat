@echo off
cd /d "%~dp0..\.."
echo Building: Forge 1.20.1
call gradlew.bat :mc-1.20.1:forge:clean :mc-1.20.1:forge:build -x test
if %errorlevel% neq 0 (
    echo Build failed!
    pause
    exit /b 1
)
echo.
echo Collecting JARs to output...
if not exist output mkdir output
copy /y mc-1.20.1\forge\build\libs\*.jar output\ >nul
copy /y libs\core\build\libs\*.jar output\ >nul
copy /y libs\platform-api\build\libs\*.jar output\ >nul
echo.
echo Output: output\
dir /b output\*.jar
pause
