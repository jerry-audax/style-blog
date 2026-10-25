param(
    [ValidatePattern('^[A-Za-z0-9_.,]*$')][string]$Tests = '',
    [switch]$WithMediaPostgres,
    [switch]$Package,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$Label = 'tests'
)
$ErrorActionPreference = 'Stop'
& node (Join-Path $PSScriptRoot 'verify-migration-names.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Migration filenames must pass validation before backend tests.' }
$architectureRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$architectureEvidence = Join-Path $architectureRepository '.artifacts/backend-tests'
$null = New-Item -ItemType Directory -Path $architectureEvidence -Force
$architectureNames = @('MEDIA_PG_TEST_URL','MEDIA_PG_TEST_USERNAME','MEDIA_PG_TEST_PASSWORD','PG_TEST_URL',
    'PG_TEST_USERNAME','PG_TEST_PASSWORD','DOCKER_HOST','DOCKER_CONTEXT','IMGBED_SMOKE_TEST','MUSIC_SMOKE_TEST','MAVEN_OPTS')
$architecturePrevious = @{}
foreach ($name in $architectureNames) { $architecturePrevious[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$architectureExit = 1
try {
    $env:MAVEN_OPTS = (($env:MAVEN_OPTS + ' -Dfile.encoding=UTF-8').Trim())
    # Never inherit opt-ins for business-database tests or real providers.
    foreach ($name in @('PG_TEST_URL','PG_TEST_USERNAME','PG_TEST_PASSWORD','IMGBED_SMOKE_TEST','MUSIC_SMOKE_TEST')) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    if ($WithMediaPostgres) {
        $configured = @('MEDIA_PG_TEST_URL','MEDIA_PG_TEST_USERNAME','MEDIA_PG_TEST_PASSWORD') |
            Where-Object { -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, 'Process')) }
        if ($configured.Count -ne 0 -and $configured.Count -ne 3) { throw 'Media test environment must be complete.' }
        if ($configured.Count -eq 0) {
            $context = $env:DOCKER_CONTEXT
            if (-not $context -and -not $env:DOCKER_HOST) {
                $context = (docker context show | Out-String).Trim()
                if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve local Docker context.' }
            }
            $endpoint = $env:DOCKER_HOST
            if ($context) {
                $endpoint = (docker context inspect --format '{{.Endpoints.docker.Host}}' $context | Out-String).Trim()
                if ($LASTEXITCODE -ne 0) { throw 'Cannot verify local Docker endpoint.' }
            }
            if ($endpoint -cnotin @('npipe:////./pipe/dockerDesktopLinuxEngine','npipe:////./pipe/docker_engine') -and
                $endpoint -cnotmatch '^tcp://(127\.0\.0\.1|localhost):[0-9]+$') { throw 'Only the approved local Docker endpoint is allowed.' }
            $env:DOCKER_CONTEXT = $null
            $env:DOCKER_HOST = $endpoint
            $inspection = docker inspect unruffled_bartik | Out-String
            if ($LASTEXITCODE -ne 0) { throw 'The approved local PostgreSQL container is unavailable.' }
            $container = @($inspection | ConvertFrom-Json)[0]
            if (-not $container.State.Running) { throw 'Start the approved local PostgreSQL container first.' }
            $settings = @{}
            foreach ($entry in $container.Config.Env) {
                $parts = $entry.Split('=', 2)
                if ($parts.Length -eq 2) { $settings[$parts[0]] = $parts[1] }
            }
            $username = if ($settings['POSTGRES_USER']) { $settings['POSTGRES_USER'] } else { 'postgres' }
            if ($username -cnotmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or -not $settings['POSTGRES_PASSWORD']) { throw 'Cannot inject local test credentials.' }
            $catalog = (docker exec unruffled_bartik psql -U $username -d blog_media_test -Atc 'SELECT current_database()' | Out-String).Trim()
            if ($LASTEXITCODE -ne 0 -or $catalog -cne 'blog_media_test') { throw 'The isolated test catalog must already exist.' }
            $env:MEDIA_PG_TEST_URL = 'jdbc:postgresql://127.0.0.1:5432/blog_media_test'
            $env:MEDIA_PG_TEST_USERNAME = $username
            $env:MEDIA_PG_TEST_PASSWORD = $settings['POSTGRES_PASSWORD']
        }
        if ($env:MEDIA_PG_TEST_URL -cnotmatch '^jdbc:postgresql://(127\.0\.0\.1|localhost):5432/blog_media_test$') {
            throw 'Media tests must target the isolated local catalog without URL options.'
        }
    } else {
        foreach ($name in @('MEDIA_PG_TEST_URL','MEDIA_PG_TEST_USERNAME','MEDIA_PG_TEST_PASSWORD')) {
            [Environment]::SetEnvironmentVariable($name, $null, 'Process')
        }
    }
    $started = [DateTime]::UtcNow
    $arguments = @('-B','-ntp','-f',(Join-Path $architectureRepository 'blog-server/pom.xml'))
    if ($Tests) { $arguments += @("-Dtest=$Tests",'-Dsurefire.failIfNoSpecifiedTests=false') }
    # Package moves must not leave old bytecode or stale test reports on the classpath.
    $arguments += @('clean','test')
    if ($Package) { $arguments += 'package' }
    Push-Location (Join-Path $architectureRepository 'blog-server')
    try {
        $captured = (& mvn.cmd @arguments 2>&1 | Out-String)
        $architectureExit = $LASTEXITCODE
    } finally { Pop-Location }
    if ($env:MEDIA_PG_TEST_PASSWORD) { $captured = $captured.Replace($env:MEDIA_PG_TEST_PASSWORD, '[REDACTED]') }
    $log = Join-Path $architectureEvidence "$Label.log"
    Set-Content -LiteralPath $log -Value $captured -Encoding utf8
    Get-Content -LiteralPath $log -Tail 16
    if ($architectureExit -ne 0) { throw "Maven failed (exit $architectureExit); see the local evidence log." }
    $reports = @(Get-ChildItem -LiteralPath (Join-Path $architectureRepository 'blog-server') -Recurse -File -Filter 'TEST-*.xml' |
        Where-Object { $_.Directory.Name -eq 'surefire-reports' -and $_.LastWriteTimeUtc -ge $started.AddSeconds(-1) })
    $suites = @($reports | ForEach-Object { ([xml](Get-Content -LiteralPath $_.FullName -Raw)).testsuite })
    $counts = @{tests=0; failures=0; errors=0; skipped=0}
    foreach ($suite in $suites) { foreach ($key in @('tests','failures','errors','skipped')) { $counts[$key] += [int]$suite.$key } }
    if ($counts.tests -eq 0) { throw 'No tests were actually discovered in this run.' }
    if ($Tests) {
        foreach ($requested in $Tests.Split(',')) {
            if (-not ($suites | Where-Object { ($_.name -eq $requested -or $_.name.EndsWith(".$requested")) -and [int]$_.tests -gt [int]$_.skipped })) {
                throw "Requested test was not executed: $requested"
            }
        }
    }
    Write-Output ('Verified tests: {0}, failures: {1}, errors: {2}, skipped: {3}; log: {4}' -f $counts.tests,$counts.failures,$counts.errors,$counts.skipped,$log)
} finally {
    foreach ($name in $architecturePrevious.Keys) { [Environment]::SetEnvironmentVariable($name, $architecturePrevious[$name], 'Process') }
    $inspection = $null; $container = $null; $settings = $null; $captured = $null
}
exit $architectureExit
