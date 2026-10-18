@echo off
chcp 65001 >nul
rem MapSyncer 开发环境依赖一键部署（纯 cmd）
rem
rem 用法:
rem   scripts\setup-deps.bat              完整部署
rem   scripts\setup-deps.bat -Quick       仅 JDK + Gradle，不预拉 Maven
rem   scripts\setup-deps.bat -SkipJdk     跳过 JDK 安装（仅检测）
rem   scripts\setup-deps.bat -SkipGradle89
rem   scripts\setup-deps.bat -SkipMaven
rem   scripts\setup-deps.bat -SkipPropsUpdate
rem   scripts\setup-deps.bat -NoPause
setlocal EnableExtensions EnableDelayedExpansion

for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~sI"
cd /d "%PROJECT_ROOT%"

set "GRADLE_WRAPPER=%PROJECT_ROOT%\gradlew.bat"
set "GRADLE89_DIR=%PROJECT_ROOT%\gradle-8.9"
set "GRADLE89_BIN=%GRADLE89_DIR%\bin\gradle.bat"
set "PROPS_FILE=gradle.properties"
set "SETTINGS_FILE=%PROJECT_ROOT%\settings.gradle"
set "SETTINGS_BAK=%PROJECT_ROOT%\settings.bak.gradle"
set "SETTINGS_FORGE=%PROJECT_ROOT%\scripts\fastbuild\settings-forge.gradle"
set "SETTINGS_12111=%PROJECT_ROOT%\scripts\fastbuild\settings-12111.gradle"
set "SETTINGS_26=%PROJECT_ROOT%\scripts\fastbuild\settings-26.gradle"
set "CACHE_DIR=%PROJECT_ROOT%\cache\tmp\setup-deps"
set "GRADLE89_ZIP=%CACHE_DIR%\gradle-8.9-bin.zip"
set "GRADLE89_URL=https://services.gradle.org/distributions/gradle-8.9-bin.zip"

set "QUICK=0"
set "SKIPJDK=0"
set "SKIPGRADLE89=0"
set "SKIPMAVEN=0"
set "SKIPPROPS=0"
set "NOPAUSE=0"

:parse_args
if "%~1"=="" goto :after_args
if /i "%~1"=="-Quick" set "QUICK=1"
if /i "%~1"=="-SkipJdk" set "SKIPJDK=1"
if /i "%~1"=="-SkipGradle89" set "SKIPGRADLE89=1"
if /i "%~1"=="-SkipMaven" set "SKIPMAVEN=1"
if /i "%~1"=="-SkipPropsUpdate" set "SKIPPROPS=1"
if /i "%~1"=="-NoPause" set "NOPAUSE=1"
shift
goto :parse_args

:after_args
echo ============================================
echo  MapSyncer - Setup Development Dependencies
echo ============================================
echo Project: %PROJECT_ROOT%

call :detect_jdks
call :ensure_jdks

if "%SKIPPROPS%"=="0" call :update_props

echo.
echo ==^> Gradle Wrapper (9.4 via gradlew)
call :apply_jdk25
call "%GRADLE_WRAPPER%" --version
if errorlevel 1 (
    echo     [FAIL] gradlew.bat --version failed
    goto :fail
)
echo     [OK] Gradle Wrapper ready

if "%SKIPGRADLE89%"=="1" (
    echo     [WARN] SkipGradle89 set
) else (
    call :ensure_gradle89
    if errorlevel 1 goto :fail
)

if "%QUICK%"=="1" (
    echo     [WARN] Quick mode: skipped Maven / MC artifact prefetch
    goto :done
)
if "%SKIPMAVEN%"=="1" (
    echo     [WARN] SkipMaven set
    goto :done
)

call :prefetch_default
call :prefetch_forge
call :prefetch_12111
call :prefetch_26

:done
echo.
echo ============================================
echo  Setup complete
echo ============================================
echo.
echo Next steps:
echo   cmd /c scripts\fastbuild\build-all.bat
echo   cmd /c scripts\fastbuild\build-target.bat fabric-1.21.1
echo.
if "%NOPAUSE%"=="0" pause
exit /b 0

:fail
echo.
echo [ERROR] setup-deps failed
if "%NOPAUSE%"=="0" pause
exit /b 1

rem ------------------------------------------------------------
rem JDK 检测：扫描常见 Temurin 安装目录，记录 17/21/25 的完整路径
rem ------------------------------------------------------------
:detect_jdks
set "JDK17="
set "JDK21="
set "JDK25="
for %%R in (
    "%ProgramFiles%\Eclipse Adoptium"
    "%ProgramFiles%\Java"
    "%ProgramFiles(x86)%\Eclipse Adoptium"
) do (
    if exist "%%~R" (
        for /d %%D in ("%%~R\jdk-*") do (
            if exist "%%~D\bin\java.exe" (
                set "JDK_NAME=%%~nxD"
                set "JDK_MAJOR="
                for /f "tokens=2 delims=-" %%A in ("!JDK_NAME!") do (
                    for /f "tokens=1 delims=." %%B in ("%%A") do set "JDK_MAJOR=%%B"
                )
                if "!JDK_MAJOR!"=="17" if not defined JDK17 set "JDK17=%%~fD"
                if "!JDK_MAJOR!"=="21" if not defined JDK21 set "JDK21=%%~fD"
                if "!JDK_MAJOR!"=="25" if not defined JDK25 set "JDK25=%%~fD"
            )
        )
    )
)
exit /b 0

:ensure_jdks
echo.
echo ==^> JDK 17 / 21 / 25 (Eclipse Temurin)
where winget >nul 2>&1
if errorlevel 1 echo     [WARN] winget not found; only detect existing JDKs

for %%M in (17 21 25) do call :ensure_one_jdk %%M

call :detect_jdks
for %%M in (17 21 25) do call :report_one_jdk %%M
exit /b 0

:ensure_one_jdk
set "M=%~1"
set "JDKVAL="
if "%M%"=="17" set "JDKVAL=%JDK17%"
if "%M%"=="21" set "JDKVAL=%JDK21%"
if "%M%"=="25" set "JDKVAL=%JDK25%"
if defined JDKVAL (
    echo     [OK] JDK %M% -^> !JDKVAL!
    exit /b 0
)
if "%SKIPJDK%"=="1" (
    echo     [WARN] JDK %M% missing ^(SkipJdk set^)
    exit /b 0
)
where winget >nul 2>&1
if errorlevel 1 (
    echo     [WARN] JDK %M% missing; install Temurin %M% manually
    exit /b 0
)
echo     Installing Temurin JDK %M% via winget...
winget install --id EclipseAdoptium.Temurin.%M%.JDK --accept-package-agreements --accept-source-agreements --disable-interactivity --silent
if errorlevel 1 echo     [WARN] winget install JDK %M% failed
call :detect_jdks
exit /b 0

:report_one_jdk
set "M=%~1"
set "JDKVAL="
if "%M%"=="17" set "JDKVAL=%JDK17%"
if "%M%"=="21" set "JDKVAL=%JDK21%"
if "%M%"=="25" set "JDKVAL=%JDK25%"
if defined JDKVAL (
    echo     [OK] JDK %M% ready
) else (
    echo     [FAIL] JDK %M% still missing
)
exit /b 0

rem ------------------------------------------------------------
rem 写入 gradle.properties：主 JDK + toolchain 路径
rem ------------------------------------------------------------
:update_props
if not exist "%PROPS_FILE%" (
    echo     [WARN] gradle.properties not found, skip path update
    exit /b 0
)
call :detect_jdks
if not defined JDK17 if not defined JDK21 if not defined JDK25 (
    echo     [WARN] No JDK paths detected, keeping gradle.properties
    exit /b 0
)

echo.
echo ==^> Update gradle.properties JDK paths
set "PATHS="
if defined JDK17 set "PATHS=!JDK17:\=/!"
if defined JDK21 (
    if defined PATHS (
        set "PATHS=!PATHS!,!JDK21:\=/!"
    ) else (
        set "PATHS=!JDK21:\=/!"
    )
)
if defined JDK25 (
    if defined PATHS (
        set "PATHS=!PATHS!,!JDK25:\=/!"
    ) else (
        set "PATHS=!JDK25:\=/!"
    )
)

set "TMP_PROPS=%PROPS_FILE%.tmp"
findstr /v /b /i /c:"org.gradle.java.home=" /c:"# org.gradle.java.home=" /c:"#org.gradle.java.home=" /c:"org.gradle.java.installations.paths=" /c:"# org.gradle.java.installations.paths=" /c:"#org.gradle.java.installations.paths=" "%PROPS_FILE%" > "%TMP_PROPS%"
if defined JDK25 >>"%TMP_PROPS%" echo org.gradle.java.home=!JDK25:\=/!
if defined PATHS >>"%TMP_PROPS%" echo org.gradle.java.installations.paths=!PATHS!
move /y "%TMP_PROPS%" "%PROPS_FILE%" >nul
echo     [OK] gradle.properties updated
exit /b 0

:apply_jdk25
if defined JDK25 (
    set "JAVA_HOME=!JDK25!"
    set "PATH=!JDK25!\bin;!PATH!"
)
exit /b 0

rem ------------------------------------------------------------
rem Gradle 8.9（Forge 构建专用）
rem ------------------------------------------------------------
:ensure_gradle89
echo.
echo ==^> Gradle 8.9 (Forge 构建专用)
if exist "%GRADLE89_BIN%" (
    echo     [OK] Already present at %GRADLE89_DIR%
    exit /b 0
)
where curl.exe >nul 2>&1
if errorlevel 1 (
    echo     [FAIL] curl.exe not found
    exit /b 1
)
where tar.exe >nul 2>&1
if errorlevel 1 (
    echo     [FAIL] tar.exe not found ^(Windows 10 1803+ required^)
    exit /b 1
)
if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"
echo     Downloading %GRADLE89_URL% ...
curl.exe -L --fail --retry 3 --retry-delay 2 -o "%GRADLE89_ZIP%" "%GRADLE89_URL%"
if errorlevel 1 (
    if exist "%GRADLE89_ZIP%" del "%GRADLE89_ZIP%"
    echo     [FAIL] download failed
    exit /b 1
)
if exist "%GRADLE89_DIR%" rd /s /q "%GRADLE89_DIR%"
tar.exe -xf "%GRADLE89_ZIP%" -C "%PROJECT_ROOT%"
if errorlevel 1 (
    if exist "%GRADLE89_ZIP%" del "%GRADLE89_ZIP%"
    echo     [FAIL] extraction failed
    exit /b 1
)
del "%GRADLE89_ZIP%" >nul 2>&1
if not exist "%GRADLE89_BIN%" (
    echo     [FAIL] Gradle 8.9 extraction failed: %GRADLE89_BIN%
    exit /b 1
)
echo     [OK] Extracted to %GRADLE89_DIR%
exit /b 0

rem ------------------------------------------------------------
rem 预拉取依赖
rem ------------------------------------------------------------
:prefetch_default
echo.
echo ==^> Prefetch Maven / MC artifacts (default settings.gradle)
call :apply_jdk25
call "%GRADLE_WRAPPER%" --no-daemon -x test ^
    :libs:lz4-relocated:shadowJar ^
    :libs:core:compileJava ^
    :libs:platform-api:compileJava ^
    :mc-1.20.1:fabric:compileJava ^
    :mc-1.21.1:fabric:compileJava ^
    :mc-1.21.1:neoforge:compileJava ^
    :mc-1.21.11:neoforge:compileJava ^
    :mc-26.1:neoforge:compileJava ^
    :mc-26.2:neoforge:compileJava ^
    :mc-26.3:neoforge:compileJava
if errorlevel 1 (
    echo     [WARN] default platform prefetch had errors
) else (
    echo     [OK] Default platform dependencies prefetched
)
exit /b 0

:prefetch_forge
echo.
echo ==^> Prefetch Forge dependencies (settings-forge.gradle + Gradle 8.9)
if not exist "%GRADLE89_BIN%" (
    echo     [WARN] Gradle 8.9 missing; skip Forge dependency prefetch
    exit /b 0
)
call :switch_settings "%SETTINGS_FORGE%"
if defined JDK17 (
    set "JAVA_HOME=!JDK17!"
    set "PATH=!JDK17!\bin;!PATH!"
    call "%GRADLE89_BIN%" --no-daemon -x test :mc-1.20.1:forge:compileJava
    if errorlevel 1 (
        echo     [WARN] Forge 1.20.1 prefetch had errors
    ) else (
        echo     [OK] Forge 1.20.1 dependencies prefetched
    )
) else (
    echo     [WARN] JDK 17 missing; skip Forge 1.20.1 prefetch
)
if defined JDK21 (
    set "JAVA_HOME=!JDK21!"
    set "PATH=!JDK21!\bin;!PATH!"
    call "%GRADLE89_BIN%" --no-daemon -x test :mc-1.21.1:forge:compileJava :mc-1.21.11:forge:compileJava
    if errorlevel 1 (
        echo     [WARN] Forge 1.21.x prefetch had errors
    ) else (
        echo     [OK] Forge 1.21.x dependencies prefetched
    )
) else (
    echo     [WARN] JDK 21 missing; skip Forge 1.21.x prefetch
)
call :restore_settings
exit /b 0

:prefetch_12111
echo.
echo ==^> Prefetch isolated Fabric dependencies
call :switch_settings "%SETTINGS_12111%"
call :apply_jdk25
call "%GRADLE_WRAPPER%" --no-daemon -x test :mc-1.21.11:fabric:compileJava
if errorlevel 1 (
    echo     [WARN] Fabric 1.21.11 prefetch had errors
) else (
    echo     [OK] Fabric 1.21.11 dependencies prefetched
)
call :restore_settings
exit /b 0

:prefetch_26
call :switch_settings "%SETTINGS_26%"
call :apply_jdk25
call "%GRADLE_WRAPPER%" --no-daemon -x test ^
    :mc-26.1:fabric:compileJava ^
    :mc-26.2:fabric:compileJava ^
    :mc-26.3:fabric:compileJava
if errorlevel 1 (
    echo     [WARN] Fabric 26.x prefetch had errors
) else (
    echo     [OK] Fabric 26.x dependencies prefetched
)
call :restore_settings
exit /b 0

:switch_settings
if exist "%SETTINGS_BAK%" del "%SETTINGS_BAK%"
copy /y "%SETTINGS_FILE%" "%SETTINGS_BAK%" >nul
copy /y "%~1" "%SETTINGS_FILE%" >nul
exit /b 0

:restore_settings
if exist "%SETTINGS_BAK%" (
    if exist "%SETTINGS_FILE%" del "%SETTINGS_FILE%"
    move /y "%SETTINGS_BAK%" "%SETTINGS_FILE%" >nul
)
exit /b 0
