@echo off
setlocal
cd /d "%~dp0..\.."

echo ============================================
echo   Building: Fabric 1.21.1
echo ============================================

call gradlew.bat :mc-1.21.1:fabric:clean :mc-1.21.1:fabric:build -x test
if %errorlevel% neq 0 (
    echo Build failed!
    exit /b 1
)

echo.
echo Collecting JARs to output...
if not exist output mkdir output
call "%~dp0copy-release-jars.bat" mc-1.21.1\fabric\build\libs

echo.
echo Output: output\
dir /b output\*-fabric-1.21*.jar 2>nul
exit /b 0
