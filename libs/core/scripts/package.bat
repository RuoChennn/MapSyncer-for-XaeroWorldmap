@echo off
setlocal enabledelayedexpansion

:: ==========================================
:: 自定义配置（修改下面两行即可）
:: ==========================================
set "SERVER_ADDRESS="
set "OUTPUT_NAME="
:: SERVER_ADDRESS  服务器地址，如 play.example.com:25565（留空则使用占位名 Server）
:: OUTPUT_NAME     输出 zip 文件名，如 my_server_map.zip（留空则按日期自动生成）
:: ==========================================

cd /d "%~dp0"

:: Find Java
set "JAVA=java"
where java >nul 2>&1
if errorlevel 1 (
    for %%d in ("Oracle-jdk-21" "jdk-21" "jdk" "java") do (
        if exist "%%~d\bin\java.exe" (
            set "JAVA=%%~d\bin\java.exe"
            goto :java_found
        )
    )
    echo [MapPackager] Java not found
    pause
    exit /b 1
)
:java_found

:: Locate JAR
set "JAR="
for %%f in ("mapsyncer-packager-*.jar") do set "JAR=%%~f"
if "%JAR%"=="" (
    echo [MapPackager] mapsyncer-packager-*.jar not found
    pause
    exit /b 1
)

:: Cache dir: arg1 overrides default
set "CACHE_DIR=server_map_cache"
if not "%~1"=="" set "CACHE_DIR=%~1"

:: Server address: arg2 overrides header config
if not "%~2"=="" set "SERVER_ADDRESS=%~2"

if not exist "!CACHE_DIR!" (
    echo [MapPackager] Cache dir not found: !CACHE_DIR!
    pause
    exit /b 1
)

:: Auto-detect world dir (relative to script)
set "WORLD_DIR="
if exist "world\xaeromap.txt" (
    set "WORLD_DIR=world"
) else if exist "world1\xaeromap.txt" (
    set "WORLD_DIR=world1"
)

:: Output file name
if not "!OUTPUT_NAME!"=="" (
    set "OUTPUT=!OUTPUT_NAME!"
) else (
    set "DT="
    for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value 2^>nul') do (
        if not "%%I"=="" if not defined DT set "DT=%%I"
    )
    if defined DT (
        set "DATE_PART=!DT:~0,4!-!DT:~4,2!-!DT:~6,2!"
        set "TIME_PART=!DT:~8,2!!DT:~10,2!!DT:~12,2!"
    ) else (
        for /f "tokens=1-3 delims=/-. " %%a in ("%DATE%") do (
            if "%%a" geq "1000" (set "Y=%%a" & set "M=%%b" & set "D=%%c"
            ) else if "%%c" geq "1000" (set "Y=%%c" & set "M=%%a" & set "D=%%b")
        )
        for /f "tokens=1-3 delims=:., " %%a in ("%TIME%") do (
            set "H=%%a" & set "N=%%b" & set "S=%%c"
        )
        if "!M:~1,1!"=="" set "M=0!M!"
        if "!D:~1,1!"=="" set "D=0!D!"
        if "!H:~1,1!"=="" set "H=0!H!"
        if "!N:~1,1!"=="" set "N=0!N!"
        if "!S:~1,1!"=="" set "S=0!S!"
        set "DATE_PART=!Y!-!M!-!D!"
        set "TIME_PART=!H!!N!!S!"
    )
    if not defined DATE_PART set "DATE_PART=unknown-date"
    if not defined TIME_PART set "TIME_PART=000000"
    set "OUTPUT=server_map_cache_!DATE_PART!.zip"
    if exist "!OUTPUT!" set "OUTPUT=server_map_cache_!DATE_PART!_!TIME_PART!.zip"
)

echo.
echo ========================================
echo   MapPackager - Xaero Map Packager
echo ========================================
echo   Cache: !CACHE_DIR!
echo   Output: !OUTPUT!
if not "!WORLD_DIR!"=="" echo   World: !WORLD_DIR!
if not "!SERVER_ADDRESS!"=="" (
    echo   Server: !SERVER_ADDRESS!
) else (
    echo   Server: ^(placeholder Server^)
)
echo ========================================
echo.

set "EXTRA_ARGS="
if not "!SERVER_ADDRESS!"=="" set "EXTRA_ARGS=-a !SERVER_ADDRESS!"

if "!WORLD_DIR!"=="" (
    "!JAVA!" -jar "!JAR!" -c "!CACHE_DIR!" -o "!OUTPUT!" !EXTRA_ARGS!
) else (
    "!JAVA!" -jar "!JAR!" -c "!CACHE_DIR!" -o "!OUTPUT!" -d "!WORLD_DIR!" !EXTRA_ARGS!
)

if !ERRORLEVEL! neq 0 (
    echo.
    echo [MapPackager] FAILED ^(exit !ERRORLEVEL!^)
    pause
    exit /b 1
)

for %%f in ("!OUTPUT!") do echo [MapPackager] Done: %%~ff ^(%%~zf bytes^)
pause
