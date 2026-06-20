@echo off
cd /d "%~dp0..\.."

echo ============================================
echo   Building: Fabric 1.21.11
echo ============================================
call gradlew.bat :mc-1.21.11:fabric:clean :mc-1.21.11:fabric:build -x test
if %errorlevel% neq 0 (
    echo Build failed!
    exit /b 1
)

echo.
echo Collecting JARs to output...
if not exist output mkdir output
copy /y mc-1.21.11\fabric\build\libs\*.jar output\ >nul

echo.
echo Output: output\
dir /b output\*-fabric-1.21.11*.jar 2>nul
