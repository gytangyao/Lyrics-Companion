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
if not defined ANDROID_HOME set "ANDROID_HOME=D:\Soft\android-sdk"

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

set "APP_VERSION_NAME=%appVersion%"
set "APP_VERSION_CODE=%appVersionCode%"

echo ================================================================
echo  Build    : %appVersion%  (versionCode %appVersionCode%)
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
