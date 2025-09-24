@echo off
setlocal ENABLEDELAYEDEXPANSION
set VERSION=8.7
set BASE_URL=https://services.gradle.org/distributions
set ZIP=gradle-%VERSION%-bin.zip
set TOOLS=%CD%\.tools
set ZIP_PATH=%TOOLS%\%ZIP%
set DEST_DIR=%TOOLS%\gradle-%VERSION%

if not exist "%DEST_DIR%" (
  echo [gw] Gradle %VERSION% not found. Downloading...
  if not exist "%TOOLS%" mkdir "%TOOLS%"
  powershell -Command "Invoke-WebRequest '%BASE_URL%/%ZIP%' -OutFile '%ZIP_PATH%'"
  echo [gw] Unpacking...
  powershell -Command "Expand-Archive -Force '%ZIP_PATH%' '%TOOLS%'"
  if not exist "%DEST_DIR%" (
    rem Some shells unpack to tools\gradle-%VERSION% already
    rem no-op
  )
)

"%DEST_DIR%\bin\gradle.bat" %*
