$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'media-offline-repository.ps1')
$sourceRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../../'))
$fixtureRepository = New-MediaOfflineRepository -SourceRepository $sourceRepository
$launcher = Join-Path $fixtureRepository 'blog-server/tools/start-local-backend.ps1'
$fixture = Join-Path $fixtureRepository 'launcher.fixture.env'
$absent = Join-Path $fixtureRepository 'launcher-no-environment.env'
$names = @('DB_URL','DB_HOST','DB_USERNAME','DB_PASSWORD','IMGBED_API_TOKEN','DOCKER_HOST','DOCKER_CONTEXT','MEDIA_CATALOG_UPLOADS_ENABLED','AI_ENABLED')
$original = @{}
foreach ($name in $names) { $original[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$script:failures = 0
$script:cases = 0
$global:MediaLauncherTestState = @{}
function global:docker {
    $global:LASTEXITCODE = 0
    $global:MediaLauncherTestState.dockerCalls += ,@($args)
    if ($args[0] -eq 'context' -and $args[1] -eq 'show') { return 'local-fixture' }
    if ($args[0] -eq 'context' -and $args[1] -eq 'inspect') {
        $endpoint = if ($args[-1] -eq 'remote-fixture') { 'ssh://remote.invalid' } else { 'npipe:////./pipe/dockerDesktopLinuxEngine' }
        if ($args -contains '--format') { return $endpoint }
        if ($global:MediaLauncherTestState.invalidContextJson) { return 'non-ASCII context metadata cannot be decoded as JSON' }
        return ConvertTo-Json -Depth 5 -InputObject @(@{Endpoints=@{docker=@{Host=$endpoint}}}) -Compress
    }
    if ($args[0] -eq 'inspect') {
        return ConvertTo-Json -Depth 5 -InputObject @(@{State=@{Running=$true};Config=@{Env=@('POSTGRES_USER=fixture-user','POSTGRES_PASSWORD=fixture-only-password')}}) -Compress
    }
    throw 'Unexpected Docker command in offline test.'
}
function global:mvn.cmd {
    $global:MediaLauncherTestState.mavenCalls += ,@($args)
    $global:MediaLauncherTestState.mavenHost = $env:DOCKER_HOST
    $global:MediaLauncherTestState.mavenContext = $env:DOCKER_CONTEXT
    $global:LASTEXITCODE = 0
}
function global:java {
    $global:MediaLauncherTestState.javaCalls += ,@($args)
    $global:LASTEXITCODE = 0
}
function Assert-Test($condition, $description) {
    if (-not $condition) { throw $description }
}
function Run-Case($name, [scriptblock]$body) {
    $script:cases++
    foreach ($item in $names) { [Environment]::SetEnvironmentVariable($item, $null, 'Process') }
    $env:DOCKER_CONTEXT = 'local-fixture'
    $global:MediaLauncherTestState.dockerCalls = @(); $global:MediaLauncherTestState.mavenCalls = @()
    $global:MediaLauncherTestState.javaCalls = @()
    $global:MediaLauncherTestState.invalidContextJson = $false
    try { & $body; Write-Output "PASS $name" }
    catch { $script:failures++; Write-Output "FAIL $name : $($_.Exception.Message)" }
}
function Invoke-Fixture([hashtable]$extra = @{}) {
    $parameters = @{LocalEnvironmentFile=$absent; ImgBedEnvironmentFile=$absent}
    foreach ($key in $extra.Keys) { $parameters[$key] = $extra[$key] }
    $global:MediaLauncherTestState.launchOutput = @(& $launcher @parameters)
}
function Assert-Rejected([hashtable]$extra = @{}) {
    $rejected = $false
    try { Invoke-Fixture $extra } catch { $rejected = $true }
    Assert-Test $rejected 'Expected launcher rejection.'
    Assert-Test ($global:MediaLauncherTestState.mavenCalls.Count -eq 0) 'Rejected target invoked Maven.'
}
try {
    [IO.File]::WriteAllLines($fixture, @(
        'DB_URL=jdbc:postgresql://remote.invalid:5432/blog_system',
        'DB_PASSWORD=fixture-only-password',
        'IMGBED_API_TOKEN=fixture-only-token'
    ))
    Run-Case 'verification cannot inherit real upload or AI enablement' {
        $env:MEDIA_CATALOG_UPLOADS_ENABLED='true';$env:AI_ENABLED='true'
        Invoke-Fixture @{MediaCatalogStorage='postgres'}
        $command=$global:MediaLauncherTestState.javaCalls[0] -join ' '
        Assert-Test ($command -match '--media.catalog.uploads.enabled=false') 'Verification did not explicitly disable real uploads.'
        Assert-Test ($command -match '--ai.enabled=false') 'Verification did not explicitly disable AI.'
        Assert-Test ($env:MEDIA_CATALOG_UPLOADS_ENABLED -eq 'true') 'Verification changed caller upload environment.'
    }
    Run-Case 'default remains legacy-json and packaged JAR AI-disabled' {
        Invoke-Fixture
        $command = $global:MediaLauncherTestState.javaCalls[0] -join ' '
        Assert-Test (($global:MediaLauncherTestState.mavenCalls[0] -join ' ') -match 'package' -and $command -match '-jar .*blog-app.jar') 'Wrong package/startup boundary.'
        Assert-Test ($command -match '--media.catalog.storage=legacy-json') 'Missing explicit legacy source.'
        Assert-Test ($command -match '--ai.enabled=false' -and $command -match '--ai.knowledge.initialize-on-startup=false') 'AI not disabled.'
    }
    Run-Case 'postgres source, local daemon pin, no secret arguments/output and restoration' {
        $env:DOCKER_HOST = 'tcp://127.0.0.1:2375'
        $env:DB_PASSWORD = 'fixture-only-password'; $env:IMGBED_API_TOKEN = 'fixture-only-token'
        Invoke-Fixture @{MediaCatalogStorage='postgres'}
        Assert-Test (($global:MediaLauncherTestState.javaCalls[0] -join ' ') -match '--media.catalog.storage=postgres') 'Missing postgres source.'
        Assert-Test (-not $global:MediaLauncherTestState.mavenContext -and $global:MediaLauncherTestState.mavenHost -eq 'npipe:////./pipe/dockerDesktopLinuxEngine') 'Docker context not resolved and pinned.'
        Assert-Test ($env:DOCKER_CONTEXT -eq 'local-fixture' -and $env:DOCKER_HOST -eq 'tcp://127.0.0.1:2375') 'Docker environment not restored.'
        Assert-Test ($env:DB_PASSWORD -eq 'fixture-only-password') 'Database environment not restored.'
        Assert-Test ((($global:MediaLauncherTestState.mavenCalls | ConvertTo-Json -Compress) + ($global:MediaLauncherTestState.javaCalls | ConvertTo-Json -Compress) + ($global:MediaLauncherTestState.launchOutput -join ' ')) -notmatch 'fixture-only-password|fixture-only-token') 'Secret exposed.'
    }
    Run-Case 'invalid storage rejects before Docker' { Assert-Rejected @{MediaCatalogStorage='other'}; Assert-Test ($global:MediaLauncherTestState.dockerCalls.Count -eq 0) 'Invalid mode reached Docker.' }
    Run-Case 'context endpoint resolution avoids unrelated Unicode JSON metadata' {
        $global:MediaLauncherTestState.invalidContextJson=$true
        Invoke-Fixture @{MediaCatalogStorage='postgres'}
        Assert-Test ($global:MediaLauncherTestState.mavenCalls.Count -eq 1) 'Local endpoint resolution consumed full context JSON.'
    }
    Run-Case 'postgres effective datasource ignores inherited remote DB_HOST' {
        $env:DB_HOST='remote.invalid'
        Invoke-Fixture @{MediaCatalogStorage='postgres'}
        Assert-Test (($global:MediaLauncherTestState.javaCalls[0] -join ' ') -match '--spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/blog_system') 'Actual Spring datasource not pinned to verified DB_URL.'
    }
    Run-Case 'other container rejected' { Assert-Rejected @{MediaCatalogStorage='postgres';PostgresContainer='other'} }
    Run-Case 'family database rejected' { Assert-Rejected @{MediaCatalogStorage='postgres';Database='family_memory'} }
    Run-Case 'remote database host rejected' { Assert-Rejected @{MediaCatalogStorage='postgres';PostgresHost='remote.invalid'} }
    Run-Case 'nonapproved port rejected' { Assert-Rejected @{MediaCatalogStorage='postgres';PostgresPort=5433} }
    Run-Case 'effective env DB URL rejected and all environment restored' {
        Assert-Rejected @{MediaCatalogStorage='postgres';LocalEnvironmentFile=$fixture}
        Assert-Test (-not $env:DB_URL -and -not $env:DB_PASSWORD -and -not $env:IMGBED_API_TOKEN) 'Imported environment leaked on failure.'
        Assert-Test ($env:DOCKER_CONTEXT -eq 'local-fixture' -and -not $env:DOCKER_HOST) 'Docker environment leaked on failure.'
    }
    Run-Case 'URL query rejected' { $env:DB_URL='jdbc:postgresql://127.0.0.1:5432/blog_system?currentSchema=other'; Assert-Rejected @{MediaCatalogStorage='postgres'} }
    Run-Case 'remote context cannot be bypassed by local host' { $env:DOCKER_CONTEXT='remote-fixture';$env:DOCKER_HOST='tcp://127.0.0.1:2375';Assert-Rejected @{MediaCatalogStorage='postgres'} }
    Run-Case 'remote HOST rejected without context' { $env:DOCKER_CONTEXT=$null;$env:DOCKER_HOST='tcp://remote.invalid:2375';Assert-Rejected @{MediaCatalogStorage='postgres'} }
    Run-Case 'legacy target arguments are not narrowed' { Invoke-Fixture @{Database='other';PostgresContainer='other';PostgresPort=5433};Assert-Test ($global:MediaLauncherTestState.mavenCalls.Count -eq 1) 'Legacy parameters narrowed.' }
} finally {
    foreach ($name in $original.Keys) { [Environment]::SetEnvironmentVariable($name, $original[$name], 'Process') }
    Remove-Item Function:\docker,Function:\mvn.cmd,Function:\java -ErrorAction SilentlyContinue
    Remove-MediaOfflineRepository -Repository $fixtureRepository
}
Write-Output "$($script:cases) tests, $($script:failures) failures"
if ($script:failures) { exit 1 }
exit 0
