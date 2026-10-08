[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SocketTempDirectory,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs = @('help')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
if (-not $JavaHome) {
    $cachedJdks = Join-Path $env:USERPROFILE '.gradle\jdks'
    $candidate = Get-ChildItem -LiteralPath $cachedJdks -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '21' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe')) } |
        Sort-Object Name | Select-Object -First 1
    if ($candidate) { $JavaHome = $candidate.FullName }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Provide -JavaHome or JAVA_HOME pointing to an installed JDK 21.'
}
if (-not $SocketTempDirectory) {
    $SocketTempDirectory = Join-Path $projectRoot '.gradle\java-sockets'
}
New-Item -ItemType Directory -Path $SocketTempDirectory -Force | Out-Null
$socketPath = (Resolve-Path -LiteralPath $SocketTempDirectory).Path.Replace('\', '/')

# JDK Windows selectors use AF_UNIX wakeup sockets. A project-owned directory
# avoids failures in the default Windows TEMP without changing system settings.
# JAVA_TOOL_OPTIONS also applies to Gradle workers and the single-use daemon.
$previousJavaHome = $env:JAVA_HOME
$previousToolOptions = $env:JAVA_TOOL_OPTIONS
$exitCode = 1
try {
    $env:JAVA_HOME = $JavaHome
    $socketOption = '"-Djdk.net.unixdomain.tmpdir=' + $socketPath + '"'
    $env:JAVA_TOOL_OPTIONS = ($previousToolOptions + ' ' + $socketOption).Trim()
    if ($GradleArgs -notcontains '--no-daemon') { $GradleArgs = @('--no-daemon') + $GradleArgs }
    Push-Location -LiteralPath $projectRoot
    try {
        & (Join-Path $projectRoot 'gradlew.bat') @GradleArgs
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:JAVA_TOOL_OPTIONS = $previousToolOptions
}
exit $exitCode
