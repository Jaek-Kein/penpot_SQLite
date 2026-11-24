# SPDX-License-Identifier: MPL-2.0
[CmdletBinding()]
param(
    [string]$ProgramsDirectory = [Environment]::GetFolderPath('Programs')
)

$ErrorActionPreference = 'Stop'
$folder = [IO.Directory]::CreateDirectory($ProgramsDirectory).FullName
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut((Join-Path $folder 'Penpot SQLite.lnk'))
$shortcut.TargetPath = Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe'
$shortcut.Arguments = '-NoLogo -NoProfile -ExecutionPolicy RemoteSigned -File "{0}"' -f (Join-Path $PSScriptRoot 'Start.ps1')
$shortcut.WorkingDirectory = Split-Path -Parent $PSScriptRoot
$shortcut.Description = 'Open your local Penpot SQLite workspace'
$shortcut.IconLocation = (Join-Path $PSScriptRoot 'penpot.ico') + ',0'
$shortcut.WindowStyle = 7
$shortcut.Save()
