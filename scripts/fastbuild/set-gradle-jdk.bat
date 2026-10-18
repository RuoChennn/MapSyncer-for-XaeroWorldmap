@echo off
chcp 65001 >nul
rem 切换构建用 JDK，并写入 gradle.properties。
rem 用法: call set-gradle-jdk.bat 17^|21^|25
rem 成功后导出 GRADLE_JDK_HOME / JAVA_HOME，并把 JDK\bin 放到 PATH 最前。
setlocal EnableExtensions

set "JDK_MAJOR=%~1"
for %%I in ("%~dp0..\..") do set "PROJECT_ROOT=%%~sI"

if /i "%JDK_MAJOR%"=="17" set "JDK_PATH=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
if /i "%JDK_MAJOR%"=="21" set "JDK_PATH=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
if /i "%JDK_MAJOR%"=="25" set "JDK_PATH=C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"

if not defined JDK_PATH (
    echo [ERROR] Unsupported JDK major version: %JDK_MAJOR%
    endlocal
    exit /b 2
)

if not exist "%JDK_PATH%\bin\java.exe" (
    echo [ERROR] JDK not found: %JDK_PATH%
    endlocal
    exit /b 3
)

set "PROPS_FILE=gradle.properties"
pushd "%~dp0..\.." >nul
if not exist "%PROPS_FILE%" goto :skip_props
set "TMP_PROPS=%PROPS_FILE%.tmp"
findstr /v /b /i /c:"org.gradle.java.home=" /c:"# org.gradle.java.home=" /c:"#org.gradle.java.home=" "%PROPS_FILE%" > "%TMP_PROPS%"
>> "%TMP_PROPS%" echo org.gradle.java.home=%JDK_PATH:\=/%
move /y "%TMP_PROPS%" "%PROPS_FILE%" >nul
:skip_props
popd >nul

endlocal & set "GRADLE_JDK_HOME=%JDK_PATH%" & set "JAVA_HOME=%JDK_PATH%" & set "PATH=%JDK_PATH%\bin;%PATH%"
exit /b 0
