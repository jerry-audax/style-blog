param(
    [string]$PostgresContainer = 'unruffled_bartik',
    [string]$Database = 'blog_system',
    [string]$PostgresHost = '127.0.0.1',
    [int]$PostgresPort = 5432,
    [string]$RedisHost = '127.0.0.1',
    [int]$RedisPort = 6379,
    [string]$ImgBedEnvironmentFile = (Join-Path $PSScriptRoot '../.env.imgbed.local'),
    [string]$MusicAudioProbeFile = '',
    [switch]$MusicAudioProbeReconcile
)
$ErrorActionPreference = 'Stop'

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
foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'IMGBED_BASE_URL', 'IMGBED_API_TOKEN', 'IMGBED_UPLOAD_CHANNEL', 'IMGBED_CHANNEL_NAME', 'IMGBED_ROOT_FOLDER')) {
    $previousDbVariables[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    Import-LocalImgBedEnvironment
    # Read credentials in-process only. Never print or persist the password.
    $env:DB_URL = "jdbc:postgresql://${PostgresHost}:${PostgresPort}/${Database}"
    $env:DB_USERNAME = if ($localPgSettings['POSTGRES_USER']) { $localPgSettings['POSTGRES_USER'] } else { 'postgres' }
    $env:DB_PASSWORD = $localPgSettings['POSTGRES_PASSWORD']
    Push-Location (Join-Path $PSScriptRoot '../blog-server')
    try {
        # The verification launcher intentionally overrides AI_ENABLED, even if the shell had enabled it.
        & mvn -B -ntp exec:java '-Dexec.mainClass=com.blogsystem.BlogServerApplication' "-Dexec.args=--spring.profiles.active=dev --server.address=127.0.0.1 --spring.data.redis.host=$RedisHost --spring.data.redis.port=$RedisPort --ai.enabled=false --ai.knowledge.initialize-on-startup=false"
        $localBackendExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    foreach ($name in $previousDbVariables.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousDbVariables[$name], 'Process')
    }
}
exit $localBackendExit
