# Build a tested Windows app with a bundled runtime. No global installs required.
[CmdletBinding()]
param([switch]$Installer)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsDirectory = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force -Path $toolsDirectory | Out-Null

function Get-VerifiedArchive([string]$Uri, [string]$Path, [string]$Sha256) {
    if ((Test-Path -LiteralPath $Path) -and
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -eq $Sha256) { return }
    Write-Host "Downloading $(Split-Path -Leaf $Path)..."
    $temporary = "$Path.download"
    Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $temporary
    if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $Sha256) {
        throw "Checksum verification failed for $Uri"
    }
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

if ($env:OS -ne 'Windows_NT') { throw 'Run this script on Windows.' }

$javaDirectory = Get-ChildItem -LiteralPath $toolsDirectory -Directory -Filter 'jdk-17*' |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/jpackage.exe') } |
    Sort-Object Name -Descending | Select-Object -First 1
if (!$javaDirectory) {
    Write-Host 'Preparing Java 17...'
    $release = Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows'
    $package = $release[0].binary.package
    $archive = Join-Path $toolsDirectory 'jdk17.zip'
    Get-VerifiedArchive $package.link $archive $package.checksum
    Expand-Archive -LiteralPath $archive -DestinationPath $toolsDirectory -Force
    $javaDirectory = Get-ChildItem -LiteralPath $toolsDirectory -Directory -Filter 'jdk-17*' |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/jpackage.exe') } |
        Sort-Object Name -Descending | Select-Object -First 1
}
if (!$javaDirectory) { throw 'Java 17 could not be prepared.' }

$gradle = Join-Path $toolsDirectory 'gradle-8.5/bin/gradle.bat'
if (!(Test-Path -LiteralPath $gradle)) {
    $archive = Join-Path $toolsDirectory 'gradle85.zip'
    Get-VerifiedArchive 'https://services.gradle.org/distributions/gradle-8.5-bin.zip' $archive `
        '9d926787066a081739e8200858338b4a69e837c3a821a33aca9db09dd4a41026'
    Expand-Archive -LiteralPath $archive -DestinationPath $toolsDirectory -Force
}

$priorJava = $env:JAVA_HOME
$priorGradle = $env:GRADLE_USER_HOME
$priorPath = $env:PATH
Push-Location -LiteralPath $projectRoot
try {
    $env:JAVA_HOME = $javaDirectory.FullName
    $env:GRADLE_USER_HOME = Join-Path $toolsDirectory 'gradle-home'
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    $tasks = @('-PdesktopOnly=true', ':desktop:packageWindowsPortable', '--no-daemon')
    if ($Installer) { $tasks += ':desktop:packageMsi' }
    & $gradle @tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    if ($Installer) {
        $msi = Get-ChildItem 'desktop/build/compose/binaries/main/msi/*.msi' |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (!$msi) { throw 'The Windows installer was not created.' }
        Copy-Item -LiteralPath $msi.FullName -Destination 'dist/TorrentToDDL-windows-x64.msi'
    }
    Get-ChildItem 'dist/TorrentToDDL-windows-x64.*' | ForEach-Object {
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $($_.Name)"
    } | Set-Content -LiteralPath 'dist/SHA256SUMS' -Encoding ASCII
    Write-Host "Ready: $projectRoot\Start-Windows.cmd"
    Write-Host "Portable download: $projectRoot\dist\TorrentToDDL-windows-x64.zip"
} finally {
    $env:JAVA_HOME = $priorJava
    $env:GRADLE_USER_HOME = $priorGradle
    $env:PATH = $priorPath
    Pop-Location
}
