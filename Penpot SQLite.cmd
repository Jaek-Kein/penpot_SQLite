@echo off
@rem SPDX-License-Identifier: MPL-2.0
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0desktop\Start.ps1"
set "launchResult=%ERRORLEVEL%"
if not "%launchResult%"=="0" pause
exit /b %launchResult%
