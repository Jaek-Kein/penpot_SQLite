# SPDX-License-Identifier: MPL-2.0
$ErrorActionPreference = 'Stop'
$desktop = Split-Path -Parent $PSScriptRoot
$register = Join-Path $desktop 'Register-StartMenu.ps1'
$folder = Join-Path ([IO.Path]::GetTempPath()) ('Penpot Start ' + [guid]::NewGuid())
$link = Join-Path $folder 'Penpot SQLite.lnk'
try {
    & $register -ProgramsDirectory $folder
    & $register -ProgramsDirectory $folder
    if (@(Get-ChildItem -LiteralPath $folder -File).Count -ne 1) { throw 'Expected one Start menu entry.' }

    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($link)
    $powershell = Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell\v1.0\powershell.exe'
    $startScript = Join-Path $desktop 'Start.ps1'
    if ($shortcut.TargetPath -ne $powershell -or !(Test-Path -LiteralPath $shortcut.TargetPath)) {
        throw 'The shortcut does not target Windows PowerShell.'
    }
    if (!$shortcut.Arguments.EndsWith(('-File "{0}"' -f $startScript))) { throw 'The launcher path must be quoted.' }
    if ($shortcut.WorkingDirectory -ne (Split-Path -Parent $desktop)) { throw 'Incorrect working directory.' }
    $icon = $shortcut.IconLocation -replace ',\s*0$', ''
    if ($icon -ne (Join-Path $desktop 'penpot.ico') -or !(Test-Path -LiteralPath $icon)) { throw 'Icon missing.' }
    Write-Host 'Start menu shortcut test passed.'
} finally {
    if (Test-Path -LiteralPath $link) { Remove-Item -LiteralPath $link }
    if (Test-Path -LiteralPath $folder) { Remove-Item -LiteralPath $folder }
}
