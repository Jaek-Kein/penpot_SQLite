# SPDX-License-Identifier: MPL-2.0
$ErrorActionPreference = 'Stop'
$desktop = Split-Path -Parent $PSScriptRoot
$register = Join-Path $desktop 'Register-StartMenu.ps1'
$folder = Join-Path ([IO.Path]::GetTempPath()) ('Penpot Start ' + [guid]::NewGuid())
$link = Join-Path $folder 'Penpot SQLite.lnk'
$fixture = Join-Path $folder 'test app'
$fixtureDesktop = Join-Path $fixture 'desktop'
$fixtureExe = Join-Path $fixture 'Penpot SQLite.exe'
$fixtureBatch = Join-Path $fixture 'Penpot SQLite.cmd'
$fixtureScript = Join-Path $fixtureDesktop 'Start.ps1'
$result = Join-Path $fixtureDesktop 'result.txt'
try {
    & $register -ProgramsDirectory $folder
    & $register -ProgramsDirectory $folder
    if (@(Get-ChildItem -LiteralPath $folder -File).Count -ne 1) { throw 'Expected one Start menu entry.' }

    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($link)
    $launcher = Join-Path (Split-Path -Parent $desktop) 'Penpot SQLite.exe'
    if ($shortcut.TargetPath -ne $launcher -or !(Test-Path -LiteralPath $shortcut.TargetPath)) {
        throw 'The shortcut must open the Penpot GUI executable.'
    }
    if ($shortcut.Arguments) { throw 'The GUI shortcut does not need command-line arguments.' }
    if ($shortcut.WorkingDirectory -ne (Split-Path -Parent $desktop)) { throw 'Incorrect working directory.' }
    $icon = $shortcut.IconLocation -replace ',\s*0$', ''
    if ($icon -ne $launcher) { throw 'The shortcut should use the embedded icon.' }
    $bytes = [IO.File]::ReadAllBytes($launcher)
    $peHeader = [BitConverter]::ToInt32($bytes, 0x3c)
    if ([BitConverter]::ToUInt16($bytes, $peHeader + 24 + 68) -ne 2) { throw 'The executable must use the Windows GUI subsystem.' }
    # Run the real GUI launcher against a test host under a path with spaces.
    [void][IO.Directory]::CreateDirectory($fixtureDesktop)
    Copy-Item -LiteralPath $launcher -Destination $fixtureExe
    Copy-Item -LiteralPath (Join-Path (Split-Path -Parent $desktop) 'Penpot SQLite.cmd') -Destination $fixtureBatch
    @'
param([switch]$FromDesktop)
$ErrorActionPreference = 'Stop'
try {
    Add-Type -Name Probe -Namespace PenpotTest -MemberDefinition '[System.Runtime.InteropServices.DllImport("kernel32.dll")] public static extern System.IntPtr GetConsoleWindow();'
    $observed = "$FromDesktop $([PenpotTest.Probe]::GetConsoleWindow().ToInt64())"
} catch { $observed = $_.Exception.Message }
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'result.tmp'), $observed)
[IO.File]::Move((Join-Path $PSScriptRoot 'result.tmp'), (Join-Path $PSScriptRoot 'result.txt'))
'@ | Set-Content -LiteralPath $fixtureScript -Encoding Ascii
    & $fixtureBatch
    if ($LASTEXITCODE -ne 0) { throw 'The batch entry point did not dispatch the GUI launcher.' }
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while (!(Test-Path -LiteralPath $result)) {
        if ([DateTime]::UtcNow -ge $deadline) { throw 'The GUI launcher failed to start the test host.' }
        Start-Sleep -Milliseconds 100
    }
    if ([IO.File]::ReadAllText($result) -ne 'True 0') { throw 'The desktop host did not start without an attached console.' }
    Write-Host 'Start menu shortcut and console-free launch tests passed.'
} finally {
    foreach ($file in @($result, (Join-Path $fixtureDesktop 'result.tmp'), $fixtureScript, $fixtureExe, $fixtureBatch, $link)) {
        if ([IO.File]::Exists($file)) { [IO.File]::Delete($file) }
    }
    foreach ($dir in @($fixtureDesktop,$fixture,$folder)) {
        if ([IO.Directory]::Exists($dir)) { [IO.Directory]::Delete($dir) }
    }
}
