@echo off
setlocal
cd /d "%~dp0..\.."
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
python scripts\testing\run-regressions.py
exit /b %errorlevel%
