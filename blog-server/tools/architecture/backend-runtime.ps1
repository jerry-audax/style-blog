function Invoke-BackendPackage {
    param([Parameter(Mandatory)][string]$Repository)
    $architectureBuildRoot = [IO.Path]::GetFullPath($Repository)
    $architectureBackendRoot = Join-Path $architectureBuildRoot 'blog-server'
    $architectureJar = Join-Path $architectureBackendRoot 'blog-app/target/blog-app.jar'
    if (-not (Test-Path -LiteralPath (Join-Path $architectureBackendRoot 'pom.xml') -PathType Leaf)) {
        throw 'Backend aggregate POM is unavailable.'
    }
    Push-Location $architectureBackendRoot
    try {
        & mvn.cmd -B -ntp package '-DskipTests' | Out-Host
        $architectureBuildExit = $LASTEXITCODE
        if ($architectureBuildExit -ne 0) { throw 'Backend packaging failed; the previous artifact will not be started.' }
        if (-not (Test-Path -LiteralPath $architectureJar -PathType Leaf)) { throw 'Backend packaging produced no application artifact.' }
        return $architectureJar
    } finally { Pop-Location }
}
