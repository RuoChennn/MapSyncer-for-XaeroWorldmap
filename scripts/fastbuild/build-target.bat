@echo off
chcp 65001 >nul
rem MapSyncer 分版本构建入口（纯 cmd）
rem
rem 用法:
rem   build-target.bat fabric-1.21.1
rem   build-target.bat forge-1.20.1
rem   build-target.bat all
rem   build-target.bat packager
rem   build-target.bat core
rem
rem -Clean / -NoTest 为兼容参数；各目标脚本默认执行 clean 并跳过测试。
setlocal EnableExtensions EnableDelayedExpansion

for %%I in ("%~dp0..\..") do set "PROJECT_ROOT=%%~sI"
set "SCRIPT_DIR=%~dp0"
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"

rem Gradle 8.9 cannot accept the CJK project path as java.io.tmpdir.
rem Use the already-resolved 8.3 short path and make sure the directory exists.
set "BUILD_TEMP=%PROJECT_ROOT%\cache\tmp\client-command-matrix\build-temp"
if not exist "%BUILD_TEMP%" mkdir "%BUILD_TEMP%" >nul 2>&1
if not exist "%BUILD_TEMP%" (
    echo [ERROR] Unable to create build temp directory: %BUILD_TEMP%
    exit /b 1
)
set "TEMP=%BUILD_TEMP%"
set "TMP=%BUILD_TEMP%"
set "TMPDIR=%BUILD_TEMP%"

if "%~1"=="" goto :usage
set "TARGET=%~1"
shift

:parse_flags
if "%~1"=="" goto :dispatch
if /i "%~1"=="-Offline" (
    set "GRADLE_OFFLINE=--offline"
    shift
    goto :parse_flags
)
if /i "%~1"=="-Clean" (
    set "CLEAN=1"
    shift
    goto :parse_flags
)
if /i "%~1"=="-NoTest" (
    set "NOTEST=1"
    shift
    goto :parse_flags
)
echo [ERROR] Unknown option: %~1
goto :usage

:dispatch
if /i "%TARGET%"=="regression" (
    call "%SCRIPT_DIR%build-regression.bat"
    exit /b !errorlevel!
)
if /i "%TARGET%"=="all" (
    call "%SCRIPT_DIR%build-all.bat"
    set "RC=!errorlevel!"
    exit /b !RC!
)
if /i "%TARGET%"=="packager" (
    call "%SCRIPT_DIR%build-packager.bat"
    set "RC=!errorlevel!"
    exit /b !RC!
)

set "TEST_ARG="
if defined NOTEST set "TEST_ARG=-x test"

if /i "%TARGET%"=="core" (
    call "%PROJECT_ROOT%\gradlew.bat" :libs:core:build %TEST_ARG%
    set "RC=!errorlevel!"
    exit /b !RC!
)
if /i "%TARGET%"=="platform-api" (
    call "%PROJECT_ROOT%\gradlew.bat" :libs:platform-api:build %TEST_ARG%
    set "RC=!errorlevel!"
    exit /b !RC!
)

set "PLATFORM="
set "MC_VERSION="
for /f "tokens=1,* delims=-" %%A in ("%TARGET%") do (
    set "PLATFORM=%%A"
    set "MC_VERSION=%%B"
)

if /i not "%PLATFORM%"=="fabric" if /i not "%PLATFORM%"=="forge" if /i not "%PLATFORM%"=="neoforge" (
    echo [ERROR] Unknown target: %TARGET%
    goto :usage
)
if "%MC_VERSION%"=="" (
    echo [ERROR] Missing MC version in target: %TARGET%
    goto :usage
)

set "BUILD_SCRIPT=%SCRIPT_DIR%build-%PLATFORM%-%MC_VERSION%.bat"
if not exist "%BUILD_SCRIPT%" (
    echo [ERROR] No build script for target: %TARGET%
    echo         Expected: %BUILD_SCRIPT%
    echo         Add a .bat in this directory following the existing cmd style.
    exit /b 1
)

echo MapSyncer Build Script - %TARGET%
call "%BUILD_SCRIPT%"
set "RC=%errorlevel%"
if %RC% neq 0 exit /b %RC%
echo Done! -^> output\
exit /b 0

:usage
echo Usage: build-target.bat ^<target^> [-Clean] [-NoTest]
echo.
echo Targets:
echo   all
echo   packager
echo   core
echo   platform-api
echo   fabric-^<mc-version^>
echo   forge-^<mc-version^>
echo   neoforge-^<mc-version^>
exit /b 1
