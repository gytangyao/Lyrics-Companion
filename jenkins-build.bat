@echo off
setlocal enabledelayedexpansion

:: ===================== version (injected by Jenkins) =====================
:: app/build.gradle reads APP_VERSION_CODE and APP_VERSION_NAME from the environment,
:: so BUILD_NUMBER alone gives a monotonically increasing version for upgrade installs.
set "versionPrefix=1.0"
if not defined BUILD_NUMBER set "BUILD_NUMBER=0"
set "appVersion=%versionPrefix%.%BUILD_NUMBER%"
set "appVersionCode=%BUILD_NUMBER%"

:: ===================== paths =====================
if not defined WORKSPACE set "WORKSPACE=%~dp0"
set "projectDir=%WORKSPACE%"
rem %~dp0 keeps a trailing backslash; Jenkins WORKSPACE does not. Drop it so the
rem paths below stay readable and the same on both.
if "%projectDir:~-1%" equ "\" set "projectDir=%projectDir:~0,-1%"
set "apkOutDir=%projectDir%\app\build\outputs\apk\release"
set "releasesDir=%projectDir%\Releases"
if not defined ARCHIVE_DIR set "ARCHIVE_DIR=D:\builds\WemewLrcSync"

:: ===================== Android SDK =====================
:: Gradle answers "SDK location not found" when ANDROID_HOME points at a directory this node does
:: not have, which reads like a broken SDK install. Take ANDROID_HOME when it really holds
:: build-tools, otherwise look in the places the SDK usually lands, and stop before Gradle when
:: none of them does.
set "sdkPicked="
rem A trailing backslash inside the quotes ("D:\sdk\") reads as an escaped quote, which swallows the
rem label argument and dies with "cannot find batch label". Strip it before every call.
if defined ANDROID_HOME if "!ANDROID_HOME:~-1!" equ "\" set "ANDROID_HOME=!ANDROID_HOME:~0,-1!"
if defined ANDROID_HOME call :use_sdk "%ANDROID_HOME%"
if not defined sdkPicked for %%d in ("D:\Soft\android-sdk" "%LOCALAPPDATA%\Android\Sdk" "C:\Android\Sdk" "C:\Android\android-sdk" "C:\Program Files (x86)\Android\android-sdk") do call :use_sdk "%%~d"
if defined sdkPicked set "ANDROID_HOME=!sdkPicked!"
if not defined sdkPicked (
    echo [ERROR] no Android SDK with build-tools found on this node.
    echo         Checked ANDROID_HOME and the usual install directories.
    echo         Install the SDK, or set ANDROID_HOME to it in the Jenkins job.
    goto ERROR_EOF
)

:: ===================== signing keys (Jenkins credentials binding) =====================
:: Bind the keystore file to APP_SIGNING_STORE_FILE and the other three to secret text.
:: The repository keeps no key material.
set "missing="
if not defined APP_SIGNING_STORE_FILE     set "missing=!missing! APP_SIGNING_STORE_FILE"
if not defined APP_SIGNING_STORE_PASSWORD set "missing=!missing! APP_SIGNING_STORE_PASSWORD"
if not defined APP_SIGNING_KEY_ALIAS      set "missing=!missing! APP_SIGNING_KEY_ALIAS"
if not defined APP_SIGNING_KEY_PASSWORD   set "missing=!missing! APP_SIGNING_KEY_PASSWORD"
if defined missing (
    echo [ERROR] signing environment incomplete, missing:!missing!
    echo         Bind the four credentials in the Jenkins job.
    goto ERROR_EOF
)

if not exist "!APP_SIGNING_STORE_FILE!" (
    echo [ERROR] keystore not found on this machine: !APP_SIGNING_STORE_FILE!
    echo         The File credential only stores a path. Copy the keystore to this
    echo         build node, or replace the credential with an uploaded file.
    goto ERROR_EOF
)

set "APP_VERSION_NAME=%appVersion%"
set "APP_VERSION_CODE=%appVersionCode%"

:: ===================== JDK preflight =====================
:: Gradle 8.9 / AGP 8.7.3 cannot run on a JDK newer than 23. A node whose PATH java is 25 dies
:: inside the first build script with "Unsupported class file major version 69", which reads like
:: a Gradle bug rather than a missing JDK 17/21, and one forgotten environment variable is enough
:: to trigger it. So prefer RELEASE_JDK_HOME, then any installed JDK this Gradle can run on.
set "jdkPicked="
set "jdkPickedMajor=0"
if defined RELEASE_JDK_HOME if "!RELEASE_JDK_HOME:~-1!" equ "\" set "RELEASE_JDK_HOME=!RELEASE_JDK_HOME:~0,-1!"
if defined RELEASE_JDK_HOME call :use_jdk "%RELEASE_JDK_HOME%"
if not defined jdkPicked for /d %%d in ("C:\Program Files\Eclipse Adoptium\jdk-*") do call :use_jdk "%%d"
if not defined jdkPicked for /d %%d in ("C:\Program Files\Java\jdk-*") do call :use_jdk "%%d"
if not defined jdkPicked call :use_jdk "C:\Program Files\Android\Android Studio\jbr"
if defined jdkPicked set "JAVA_HOME=!jdkPicked!"

set "javaExe=java"
if defined JAVA_HOME if exist "!JAVA_HOME!\bin\java.exe" set "javaExe=!JAVA_HOME!\bin\java.exe"
set "javaVersion="
for /f "tokens=3" %%a in ('"!javaExe!" -version 2^>^&1 ^| findstr /i version') do if not defined javaVersion set "javaVersion=%%a"
if not defined javaVersion (
    echo [ERROR] no usable JDK found on this node.
    echo         Install JDK 17 or 21, or set RELEASE_JDK_HOME to its folder.
    goto ERROR_EOF
)
set javaVersion=!javaVersion:"=!
set "javaMajor="
for /f "tokens=1,2 delims=." %%x in ("!javaVersion!") do (
    if "%%x" equ "1" (set "javaMajor=%%y") else (set "javaMajor=%%x")
)
if not defined javaMajor set "javaMajor=0"
if !javaMajor! LSS 17 set "jdkProblem=too old, need 17 or 21"
if !javaMajor! GTR 23 set "jdkProblem=too new, this Gradle is only proven up to 23"
if not "!jdkProblem!" equ "" (
    echo [ERROR] java !javaVersion! from !javaExe! is !jdkProblem!
    echo         Install JDK 17 or 21 on this node and set RELEASE_JDK_HOME to its folder,
    echo         e.g. RELEASE_JDK_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot
    goto ERROR_EOF
)

echo ================================================================
echo  Build    : %appVersion%  (versionCode %appVersionCode%)
echo  JDK      : !javaVersion!  (!javaExe!)
echo  SDK      : %ANDROID_HOME%
echo  Workspace: %projectDir%
echo  Archive  : %ARCHIVE_DIR%
echo ================================================================

:: ===================== clean previous outputs =====================
if exist "%apkOutDir%"   rmdir /s /q "%apkOutDir%"
if exist "%releasesDir%" rmdir /s /q "%releasesDir%"

:: ===================== build (unit tests first, abort on failure) =====================
cd /d "%projectDir%"
if errorlevel 1 (
    echo [ERROR] cannot enter "%projectDir%"
    goto ERROR_EOF
)
call gradlew.bat --no-daemon clean testReleaseUnitTest assembleRelease
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] gradle build failed, exit code %ERRORLEVEL%
    goto ERROR_EOF
)

set "apkFile=%apkOutDir%\app-release.apk"
if not exist "%apkFile%" (
    echo [ERROR] APK not produced: %apkFile%
    if exist "%apkOutDir%\app-release-unsigned.apk" echo         Gradle did not see the signing credentials.
    goto ERROR_EOF
)

:: ===================== verify signature (AGP signed with the keystore above) =====================
set "buildToolsDir="
for /f "delims=" %%d in ('dir /b /ad /o:n "%ANDROID_HOME%\build-tools" 2^>nul') do set "buildToolsDir=%%d"
if not defined buildToolsDir (
    echo [ERROR] build-tools not found under "%ANDROID_HOME%\build-tools"
    goto ERROR_EOF
)
rem apksigner prints one warning per META-INF entry, so keep the log quiet on success
rem and only show it when the signature really is wrong.
if not defined TEMP set "TEMP=%projectDir%"
set "signLog=%TEMP%\jenkins-apksigner.log"
call "%ANDROID_HOME%\build-tools\%buildToolsDir%\apksigner.bat" verify "%apkFile%" >"%signLog%" 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] apksigner verify failed:
    type "%signLog%"
    goto ERROR_EOF
)
del "%signLog%" 2>nul
echo  signature OK

:: ===================== rename + checksum =====================
if not exist "%releasesDir%" mkdir "%releasesDir%"
set "releaseApkName=WemewLrcSync_%appVersion%.apk"
set "releaseApk=%releasesDir%\!releaseApkName!"
copy /y "%apkFile%" "%releaseApk%" >nul
if errorlevel 1 (
    echo [ERROR] cannot copy APK to "%releaseApk%"
    goto ERROR_EOF
)
set "apkSha256="
for /f "skip=1 delims=" %%h in ('certutil -hashfile "%releaseApk%" SHA256') do if not defined apkSha256 set "apkSha256=%%h"
if defined apkSha256 set "apkSha256=!apkSha256: =!"
if not defined apkSha256 (
    echo [ERROR] SHA256 calculation failed
    goto ERROR_EOF
)
> "%releaseApk%.sha256" echo !apkSha256!  !releaseApkName!

:: ===================== archive to the version directory =====================
set "targetArchiveDir=%ARCHIVE_DIR%\%appVersion%"
if not exist "%targetArchiveDir%" mkdir "%targetArchiveDir%"
copy /y "%releaseApk%" "%targetArchiveDir%\" >nul
if errorlevel 1 (
    echo [ERROR] cannot archive to "%targetArchiveDir%" -- check the ARCHIVE_DIR variable
    goto ERROR_EOF
)
copy /y "%releaseApk%.sha256" "%targetArchiveDir%\" >nul

echo ================================================================
echo  [OK] %releaseApk%
echo  [OK] SHA256 !apkSha256!
echo  [OK] %targetArchiveDir%
echo ================================================================
endlocal
exit /b 0

:ERROR_EOF
echo [FAILED] build stopped, see the Gradle output above.
endlocal
exit /b 1

:: Accept %~1 as a JDK home only when its java reports a Gradle-runnable version, and keep the
:: newest accepted one so a node with both 17 and 21 builds on 21. The raw existence test comes
:: first: "for %%f in (path\.)" on a missing directory prints "path not found" into the build log,
:: which reads like a broken scan even though the routine then correctly skips it.
:use_jdk
if not exist "%~1\bin\java.exe" exit /b 0
for %%f in ("%~1\.") do set "jdkRoot=%%~ff"
set "probeVersion="
for /f "tokens=3" %%a in ('"!jdkRoot!\bin\java.exe" -version 2^>^&1 ^| findstr /i version') do if not defined probeVersion set "probeVersion=%%a"
if not defined probeVersion exit /b 0
set probeVersion=!probeVersion:"=!
set "probeMajor="
for /f "tokens=1,2 delims=." %%x in ("!probeVersion!") do (
    if "%%x" equ "1" (set "probeMajor=%%y") else (set "probeMajor=%%x")
)
if not defined probeMajor exit /b 0
if !probeMajor! LSS 17 exit /b 0
if !probeMajor! GTR 23 exit /b 0
if !probeMajor! GTR !jdkPickedMajor! (
    set "jdkPicked=!jdkRoot!"
    set "jdkPickedMajor=!probeMajor!"
)
exit /b 0

:: %~1 counts as an SDK only when it holds build-tools, which is what apksigner is taken from.
:use_sdk
if not exist "%~1\build-tools" exit /b 0
for %%f in ("%~1\.") do set "sdkRoot=%%~ff"
set "sdkPicked=!sdkRoot!"
exit /b 0
