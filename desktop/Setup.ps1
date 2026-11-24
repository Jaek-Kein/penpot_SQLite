# SPDX-License-Identifier: MPL-2.0
# Fetch only Penpot's release artifacts. No container engine is needed.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$runtimeDir = Join-Path $PSScriptRoot '.runtime\2.19.5'
$cacheDir = Join-Path $runtimeDir 'downloads'
[void][IO.Directory]::CreateDirectory($cacheDir)

function Get-VerifiedDownload($Name, $Uri, $Sha256, $Headers) {
    $path = Join-Path $cacheDir $Name
    if ((Test-Path -LiteralPath $path) -and
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -eq $Sha256) {
        return $path
    }
    $temp = "$path.$([guid]::NewGuid()).tmp"
    try {
        Write-Host "Downloading $Name"
        Invoke-WebRequest -UseBasicParsing -Uri $Uri -Headers $Headers -OutFile $temp
        if ((Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash -ne $Sha256) {
            throw "SHA-256 mismatch for $Name"
        }
        Move-Item -LiteralPath $temp -Destination $path -Force
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp }
    }
    return $path
}

function Expand-PenpotLayer($Component, $Sha256, $Entry) {
    $repo = "penpotapp/$Component"
    $auth = Invoke-RestMethod -Uri "https://auth.docker.io/token?service=registry.docker.io&scope=repository:${repo}:pull"
    $headers = @{ Authorization = "Bearer $($auth.token)" }
    $archive = Get-VerifiedDownload "$Component.tar.gz" "https://registry-1.docker.io/v2/$repo/blobs/sha256:$Sha256" $Sha256 $headers
    & tar.exe -xf $archive -C $runtimeDir $Entry
    if ($LASTEXITCODE -ne 0) { throw "Cannot extract $Component" }
}

$jreName = 'zulu27.30.201-ca-jre27.0.1.1-win_x64'
$lockPath = Join-Path $runtimeDir 'install.lock'
try {
    $lock = [IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None')
} catch [IO.IOException] {
    throw 'Setup is already running. Wait for the first launcher to finish.'
}
try {
    $ready = Join-Path $runtimeDir 'ready'
    $installed = (Test-Path -LiteralPath $ready) -and
                 (Test-Path -LiteralPath (Join-Path $runtimeDir 'opt\penpot\backend\penpot.jar')) -and
                 (Test-Path -LiteralPath (Join-Path $runtimeDir 'var\www\app\index.html')) -and
                 (Test-Path -LiteralPath (Join-Path $runtimeDir "$jreName\bin\java.exe"))
    if (!$installed) {
        if (Test-Path -LiteralPath $ready) { Remove-Item -LiteralPath $ready }
        Expand-PenpotLayer 'frontend' 'd22218124f601c78f1f8af89b6f20b3dff6e849a169f7a567425a6bb7f4143d0' 'var/www/app'
        Expand-PenpotLayer 'backend' '2102d8cd2c8b506d23685ad2e5bd99cf1f8c4d785ed948c6fb7789ce2a561111' 'opt/penpot/backend/penpot.jar'
        $jreZip = Get-VerifiedDownload "$jreName.zip" "https://cdn.azul.com/zulu/bin/$jreName.zip" 'bff5ae8486daf949cff688b7e20407e4866886168ca6ff0af22e74da677903b0' @{}
        & tar.exe -xf $jreZip -C $runtimeDir
        if ($LASTEXITCODE -ne 0) { throw 'Cannot extract the Windows Java runtime' }
        Set-Content -LiteralPath $ready -Value '2.19.5' -Encoding Ascii
        Write-Host "Runtime ready in $runtimeDir"
    }
} finally {
    $lock.Dispose()
}
try {
    & (Join-Path $PSScriptRoot 'Register-StartMenu.ps1')
} catch {
    Write-Warning "Could not add Penpot SQLite to Start. You can still open Penpot SQLite.cmd. $_"
}
