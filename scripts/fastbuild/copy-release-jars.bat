@echo off
setlocal enabledelayedexpansion

if "%~1"=="" (
    echo Usage: copy-release-jars.bat ^<source-dir^> [more-dirs...]
    exit /b 1
)

if not exist "output" mkdir "output"

:loop
if "%~1"=="" goto done
if not exist "%~1" (
    echo WARNING: Source not found: %~1
    goto next
)
for %%f in ("%~1\*.jar") do (
    set "fname=%%~nxf"
    echo !fname! | findstr /i /c:"-slim.jar" /c:"-sources.jar" /c:"-javadoc.jar" >nul
    if errorlevel 1 copy /y "%%f" "output\" >nul
)
:next
shift
goto loop

:done
exit /b 0
