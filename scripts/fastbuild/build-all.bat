@echo off
cd /d "%~dp0..\.."
echo ========================================
echo   MapSyncer - Build All Versions
echo ========================================
echo.

call gradlew.bat build -x test --parallel
if %errorlevel% neq 0 (
    echo Build failed!
    pause
    exit /b 1
)

echo.
echo Collecting JARs to output...
if not exist output mkdir output
for /r platforms %%f in (*.jar) do copy /y "%%f" output\ >nul 2>nul
copy /y libs\core\build\libs\*.jar output\ >nul
copy /y libs\platform-api\build\libs\*.jar output\ >nul
echo.
echo ========================================
echo   Build Complete! Output: output\
echo ========================================
dir /b output\*.jar
pause
