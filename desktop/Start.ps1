# SPDX-License-Identifier: MPL-2.0
[CmdletBinding()]
param(
    [string]$WorkspaceDirectory,
    [ValidateRange(1024, 65535)][int]$Port = 17861,
    [switch]$FromDesktop
)

$ErrorActionPreference = 'Stop'
trap {
    if ($FromDesktop) {
        Add-Type -AssemblyName System.Windows.Forms
        [void][Windows.Forms.MessageBox]::Show($_.Exception.Message, 'Penpot SQLite', 'OK', 'Error')
        exit 1
    }
    break
}
if (!$WorkspaceDirectory) { $WorkspaceDirectory = Join-Path $PSScriptRoot 'data' }
$runtimeDir = Join-Path $PSScriptRoot '.runtime\2.19.5'
$java = Join-Path $runtimeDir 'zulu27.30.201-ca-jre27.0.1.1-win_x64\bin\java.exe'
$jar = Join-Path $runtimeDir 'opt\penpot\backend\penpot.jar'
$web = Join-Path $runtimeDir 'var\www\app'
if (!(Test-Path -LiteralPath (Join-Path $runtimeDir 'ready')) -or
    !(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $jar) -or
    !(Test-Path -LiteralPath (Join-Path $web 'index.html'))) {
    & (Join-Path $PSScriptRoot 'Setup.ps1')
}

$browser = @(
    "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
    "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe"
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe"
    "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (!$browser) { throw 'Install Microsoft Edge or Google Chrome to open the editor.' }

$workspace = [IO.Directory]::CreateDirectory($WorkspaceDirectory).FullName
$db = Join-Path $workspace 'workspace.sqlite'
$profile = Join-Path $workspace 'browser'
$logs = [IO.Directory]::CreateDirectory((Join-Path $workspace 'logs')).FullName
$tempDir = [IO.Directory]::CreateDirectory((Join-Path $workspace 'temp')).FullName
$classPath = (Join-Path $PSScriptRoot 'src') + ';' + $jar
$url = "http://127.0.0.1:$Port"
# Keep one writer/launcher per workspace, even if a second shortcut uses a different port.
$lock = $null
$service = $null
try {
    try {
        $lock = [IO.File]::Open((Join-Path $workspace 'session.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    } catch [IO.IOException] {
        throw 'This workspace is already open. Switch to its existing editor window.'
    }
    $endpoint = [Net.IPEndPoint]::new([Net.IPAddress]::Loopback, $Port)
    $probe = [Net.Sockets.TcpListener]::new($endpoint)
    try { $probe.Start() } finally { $probe.Stop() }

    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $java
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.WorkingDirectory = $workspace
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.Arguments = ('-Xms32m -Xmx448m -XX:+UseSerialGC -XX:ActiveProcessorCount=4 ' +
        '--enable-native-access=ALL-UNNAMED -Djava.awt.headless=true "-Djava.io.tmpdir={0}" ' +
        '-cp "{1}" clojure.main -m local.server "{2}" "{3}" {4} --owner-stdin') -f $tempDir,$classPath,$db,$web,$Port
    $service = [Diagnostics.Process]::Start($info)
    $outFile = [IO.File]::Create((Join-Path $logs 'server.log'))
    $errFile = [IO.File]::Create((Join-Path $logs 'server-errors.log'))
    $outCopy = $service.StandardOutput.BaseStream.CopyToAsync($outFile)
    $errCopy = $service.StandardError.BaseStream.CopyToAsync($errFile)
    $deadline = [DateTime]::UtcNow.AddMinutes(2)
    Write-Host 'Opening your local Penpot workspace...'
    do {
        if ($service.HasExited) { throw "The local editor stopped during startup. See $logs" }
        try { $health = (Invoke-WebRequest -UseBasicParsing -Uri "$url/local/health" -TimeoutSec 2).Content }
        catch { $health = '' }
        if ($health -eq 'penpot-sqlite/1') { break }
        if ([DateTime]::UtcNow -ge $deadline) { throw "Startup timed out. See $logs" }
        Start-Sleep -Milliseconds 350
    } while ($true)

    $app = New-Object Diagnostics.ProcessStartInfo
    $app.FileName = $browser
    $app.UseShellExecute = $false
    $app.Arguments = ('--user-data-dir="{0}" --no-first-run --disable-background-mode ' +
                      '--disable-extensions --disable-sync --app="{1}/local/open"') -f $profile,$url
    $window = [Diagnostics.Process]::Start($app)
    $window.WaitForExit()
} finally {
    if ($service) {
        $service.StandardInput.Close()
        if (!$service.WaitForExit(15000)) { $service.Kill(); $service.WaitForExit() }
        if ($outCopy) { [void]$outCopy.GetAwaiter().GetResult() }
        if ($errCopy) { [void]$errCopy.GetAwaiter().GetResult() }
        if ($outFile) { $outFile.Dispose() }
        if ($errFile) { $errFile.Dispose() }
        $service.Dispose()
    }
    if ($lock) { $lock.Dispose() }
}
