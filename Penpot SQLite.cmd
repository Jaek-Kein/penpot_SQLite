@echo off
@rem SPDX-License-Identifier: MPL-2.0
setlocal
if not exist "%~dp0Penpot SQLite.exe" (
    powershell.exe -NoLogo -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0desktop\Register-StartMenu.ps1"
    if not exist "%~dp0Penpot SQLite.exe" (
        pause
        exit /b 1
    )
)
start "" "%~dp0Penpot SQLite.exe"
exit /b %errorlevel%
