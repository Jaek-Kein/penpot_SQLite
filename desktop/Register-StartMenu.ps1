# SPDX-License-Identifier: MPL-2.0
[CmdletBinding()]
param(
    [string]$ProgramsDirectory = [Environment]::GetFolderPath('Programs')
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$launcher = Join-Path $project 'Penpot SQLite.exe'
$source = Join-Path $PSScriptRoot 'Launcher.cs'
$icon = Join-Path $PSScriptRoot 'penpot.ico'
$inputs = Get-Item -LiteralPath $source,$icon,$PSCommandPath
$latestInput = ($inputs | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).LastWriteTimeUtc
if (!(Test-Path -LiteralPath $launcher) -or (Get-Item -LiteralPath $launcher).LastWriteTimeUtc -lt $latestInput) {
    $compiler = Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
    if (!(Test-Path -LiteralPath $compiler)) { throw 'The Windows .NET Framework C# compiler is required to build the launcher.' }
    $build = [IO.Directory]::CreateDirectory((Join-Path $PSScriptRoot ('.runtime\build-' + [guid]::NewGuid()))).FullName
    $binary = Join-Path $build 'Penpot SQLite.exe'
    try {
        & $compiler /nologo /target:winexe /optimize+ /reference:System.Windows.Forms.dll "/win32icon:$icon" "/out:$binary" $source
        if ($LASTEXITCODE -ne 0) { throw 'Could not build the Windows GUI launcher.' }
        if ([IO.File]::Exists($launcher)) {
            [IO.File]::Replace($binary, $launcher, [NullString]::Value)
        } else {
            try { [IO.File]::Move($binary, $launcher) }
            catch [IO.IOException] { if (![IO.File]::Exists($launcher)) { throw } }
        }
    } finally {
        if (Test-Path -LiteralPath $binary) { Remove-Item -LiteralPath $binary }
        Remove-Item -LiteralPath $build
    }
}

$folder = [IO.Directory]::CreateDirectory($ProgramsDirectory).FullName
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut((Join-Path $folder 'Penpot SQLite.lnk'))
$shortcut.TargetPath = $launcher
$shortcut.Arguments = ''
$shortcut.WorkingDirectory = $project
$shortcut.Description = 'Open your local Penpot SQLite workspace'
$shortcut.IconLocation = $launcher + ',0'
$shortcut.WindowStyle = 1
$shortcut.Save()
