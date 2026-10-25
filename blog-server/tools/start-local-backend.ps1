param(
    [string]$PostgresContainer = 'unruffled_bartik',
    [string]$Database = 'blog_system',
    [string]$PostgresHost = '127.0.0.1',
    [int]$PostgresPort = 5432,
    [string]$RedisHost = '127.0.0.1',
    [int]$RedisPort = 6379,
    [ValidateRange(1024,65535)][int]$ServerPort = 8080,
    [string]$LocalEnvironmentFile = (Join-Path $PSScriptRoot '../../local.env'),
    [string]$ImgBedEnvironmentFile = (Join-Path $PSScriptRoot '../../.env.imgbed.local'),
    [ValidateSet('legacy-json', 'postgres')][string]$MediaCatalogStorage = 'legacy-json',
    [string]$MusicAudioProbeFile = '',
    [switch]$MusicAudioProbeReconcile
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'architecture/backend-runtime.ps1')

function Import-LocalImgBedEnvironment {
    if (Test-Path -LiteralPath $ImgBedEnvironmentFile) {
        foreach ($line in Get-Content -LiteralPath $ImgBedEnvironmentFile) {
            $parts = $line.Split('=', 2)
            if ($parts.Length -eq 2 -and $parts[0] -in @('IMGBED_BASE_URL', 'IMGBED_API_TOKEN', 'IMGBED_UPLOAD_CHANNEL', 'IMGBED_CHANNEL_NAME', 'IMGBED_ROOT_FOLDER')) {
                [Environment]::SetEnvironmentVariable($parts[0], $parts[1], 'Process')
            }
        }
    }
}

function Import-LocalEnvironment {
    $allowed = @(
        'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'DB_HOST', 'DB_PORT',
        'JWT_SECRET', 'SA_TOKEN_JWT_SECRET_KEY', 'PUBLICATION_API_TOKEN',
        'PUBLICATION_BASE_URL', 'PUBLICATION_TOKEN_FILE',
        'IMGBED_BASE_URL', 'IMGBED_API_TOKEN', 'IMGBED_UPLOAD_CHANNEL',
        'IMGBED_CHANNEL_NAME', 'IMGBED_ROOT_FOLDER', 'MUSIC_PLAYLIST_ID',
        'DASHSCOPE_API_KEY'
    )
    if (-not (Test-Path -LiteralPath $LocalEnvironmentFile)) { return }
    foreach ($line in Get-Content -LiteralPath $LocalEnvironmentFile) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $parts = $trimmed.Split('=', 2)
        if ($parts.Length -ne 2 -or $parts[0].Trim() -notin $allowed) { continue }
        $value = $parts[1].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $value, 'Process')
    }
}

# Explicit single-file provider POC. Do not inspect PostgreSQL or start/restart Java in this mode.
if ($MusicAudioProbeFile) {
    $previousAudioVariables = @{}
    foreach ($name in @('IMGBED_BASE_URL', 'IMGBED_API_TOKEN', 'IMGBED_UPLOAD_CHANNEL', 'IMGBED_CHANNEL_NAME', 'IMGBED_ROOT_FOLDER')) {
        $previousAudioVariables[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    try {
        Import-LocalImgBedEnvironment
        $audioProbeMode = if ($MusicAudioProbeReconcile) { '--reconcile' } else { '--allow-upload' }
        & node (Join-Path $PSScriptRoot 'music/telegram-audio-probe.mjs') --file $MusicAudioProbeFile $audioProbeMode
        $localAudioProbeExit = $LASTEXITCODE
    } finally {
        foreach ($name in $previousAudioVariables.Keys) {
            [Environment]::SetEnvironmentVariable($name, $previousAudioVariables[$name], 'Process')
        }
    }
    exit $localAudioProbeExit
}

$previousDockerVariables = @{}
$previousCatalogConsoleEncoding = [Console]::OutputEncoding
$previousCatalogPipelineEncoding = $OutputEncoding
foreach ($name in @('DOCKER_HOST', 'DOCKER_CONTEXT')) {
    $previousDockerVariables[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
$MediaCatalogStorage = $MediaCatalogStorage.ToLowerInvariant()
if ($MediaCatalogStorage -eq 'postgres') {
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    $OutputEncoding = [Text.UTF8Encoding]::new($false)
    if ($PostgresContainer -cne 'unruffled_bartik' -or $Database -cne 'blog_system' -or
        $PostgresHost -cnotin @('127.0.0.1', 'localhost') -or $PostgresPort -ne 5432) {
        throw 'Postgres catalogue verification is restricted to the approved local blog_system container.'
    }
    # Docker CONTEXT overrides HOST. Resolve that effective target, then pin it for this process.
    $catalogDockerContext = $env:DOCKER_CONTEXT
    if (-not $catalogDockerContext -and -not $env:DOCKER_HOST) {
        $catalogDockerContext = (docker context show | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or -not $catalogDockerContext) { throw 'Cannot resolve the local Docker context.' }
    }
    $catalogDockerEndpoint = $env:DOCKER_HOST
    if ($catalogDockerContext) {
        $catalogDockerEndpoint = (docker context inspect --format '{{.Endpoints.docker.Host}}' $catalogDockerContext | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the local Docker context.' }
    }
    if ($catalogDockerEndpoint -cnotin @('npipe:////./pipe/dockerDesktopLinuxEngine', 'npipe:////./pipe/docker_engine') -and
        $catalogDockerEndpoint -cnotmatch '^tcp://(127\.0\.0\.1|localhost):[0-9]+$') {
        throw 'Postgres catalogue verification requires a confirmed local Docker endpoint.'
    }
    $env:DOCKER_CONTEXT = $null
    $env:DOCKER_HOST = $catalogDockerEndpoint
}
$localPgContainer = (docker inspect $PostgresContainer | ConvertFrom-Json)[0]
if ($LASTEXITCODE -ne 0 -or -not $localPgContainer.State.Running) {
    throw 'The selected PostgreSQL container must already be running.'
}
$localPgSettings = @{}
foreach ($setting in $localPgContainer.Config.Env) {
    $parts = $setting.Split('=', 2)
    $localPgSettings[$parts[0]] = $parts[1]
}
if (-not $localPgSettings['POSTGRES_PASSWORD']) {
    throw 'Container has no POSTGRES_PASSWORD; supply backend connection variables manually.'
}
$previousDbVariables = @{}
foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'DB_HOST', 'DB_PORT', 'JWT_SECRET', 'SA_TOKEN_JWT_SECRET_KEY', 'PUBLICATION_API_TOKEN', 'PUBLICATION_BASE_URL', 'PUBLICATION_TOKEN_FILE', 'IMGBED_BASE_URL', 'IMGBED_API_TOKEN', 'IMGBED_UPLOAD_CHANNEL', 'IMGBED_CHANNEL_NAME', 'IMGBED_ROOT_FOLDER', 'MUSIC_PLAYLIST_ID', 'DASHSCOPE_API_KEY')) {
    $previousDbVariables[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    Import-LocalEnvironment
    Import-LocalImgBedEnvironment
    # Read credentials in-process only. Never print or persist the password.
    if (-not $env:DB_URL) { $env:DB_URL = "jdbc:postgresql://${PostgresHost}:${PostgresPort}/${Database}" }
    if ($MediaCatalogStorage -eq 'postgres' -and $env:DB_URL -cnotmatch '^jdbc:postgresql://(127\.0\.0\.1|localhost):5432/blog_system$') {
        throw 'Effective DB_URL must be the approved local blog_system database without URL options.'
    }
    # application-dev historically uses DB_HOST, not DB_URL. Pin the URL we actually verified.
    $localCatalogDatasourceArgument = if ($MediaCatalogStorage -eq 'postgres') { " --spring.datasource.url=$env:DB_URL" } else { '' }
    if (-not $env:DB_USERNAME) { $env:DB_USERNAME = if ($localPgSettings['POSTGRES_USER']) { $localPgSettings['POSTGRES_USER'] } else { 'postgres' } }
    if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = $localPgSettings['POSTGRES_PASSWORD'] }
    Push-Location (Join-Path $PSScriptRoot '..')
    try {
        # The verification launcher intentionally overrides AI_ENABLED, even if the shell had enabled it.
        $localBackendJar = Invoke-BackendPackage -Repository ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')))
        $localBackendArguments = @('-Dfile.encoding=UTF-8','-jar',$localBackendJar,
            '--spring.profiles.active=dev','--server.address=127.0.0.1',"--server.port=$ServerPort",
            "--spring.data.redis.host=$RedisHost","--spring.data.redis.port=$RedisPort",'--ai.enabled=false',
            '--ai.knowledge.initialize-on-startup=false','--media.catalog.uploads.enabled=false',"--media.catalog.storage=$MediaCatalogStorage")
        if ($localCatalogDatasourceArgument) { $localBackendArguments += $localCatalogDatasourceArgument.Trim() }
        & java @localBackendArguments
        $localBackendExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $previousDbVariables.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousDbVariables[$name], 'Process')
    }
}
} finally {
    [Console]::OutputEncoding = $previousCatalogConsoleEncoding
    $OutputEncoding = $previousCatalogPipelineEncoding
    foreach ($name in $previousDockerVariables.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousDockerVariables[$name], 'Process')
    }
}
exit $localBackendExit
