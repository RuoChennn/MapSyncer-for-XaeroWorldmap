@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0..\.."
rem Reuse target scripts; stop before collecting stale artifacts after a failure.
set "BUILD_FLAGS="
if /i "%MAPSYNCER_BUILD_OFFLINE%"=="1" set "BUILD_FLAGS=-Offline"
for %%T in (
    fabric-1.20.1 forge-1.20.1
    fabric-1.21.1 forge-1.21.1 neoforge-1.21.1
    fabric-1.21.11 forge-1.21.11 neoforge-1.21.11
    fabric-26.1 neoforge-26.1
    fabric-26.2 neoforge-26.2
    fabric-26.3 neoforge-26.3
) do (
    call "%~dp0build-target.bat" %%T !BUILD_FLAGS!
    if errorlevel 1 (
        echo [ERROR] Build failed: %%T
        exit /b 1
    )
)
echo All 14 targets built successfully.
exit /b 0
