param(
    [string]$JavaHome,
    [string]$MavenRepository
)
$ErrorActionPreference = 'Stop'
$taskSavedJavaHome = $env:JAVA_HOME
$taskSavedPath = $env:PATH
Push-Location $PSScriptRoot
try {
    if ($JavaHome) {
        $taskJavaExecutable = Join-Path $JavaHome 'bin/java.exe'
        if (-not (Test-Path -LiteralPath $taskJavaExecutable)) { throw "Java executable not found: $taskJavaExecutable" }
        $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
        $env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
    }
    $taskJavaVersion = (& java -version 2>&1 | Out-String)
    if ($taskJavaVersion -notmatch 'version "(\d+)') { throw 'Cannot detect Java version. Pass -JavaHome with a Java 21+ JDK path.' }
    if ([int]$Matches[1] -lt 21) { throw 'Java 21+ is required. Pass -JavaHome with a Java 21+ JDK path.' }
    $taskMavenArgs = @('-B', 'verify')
    if ($MavenRepository) { $taskMavenArgs += "-Dmaven.repo.local=$MavenRepository" }
    & mvn @taskMavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE" }
    Write-Output 'Built: target/curl-http-mock-client.jar'
} finally {
    $env:JAVA_HOME = $taskSavedJavaHome
    $env:PATH = $taskSavedPath
    Pop-Location
}
