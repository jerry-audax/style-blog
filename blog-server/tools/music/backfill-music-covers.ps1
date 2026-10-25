param(
    [switch]$ApplyLocal,
    [ValidateRange(1,2000)][int]$Limit = 2000,
    [string]$LocalEnvironmentFile = (Join-Path $PSScriptRoot '../../../local.env'),
    [string]$ImgBedEnvironmentFile = (Join-Path $PSScriptRoot '../../../.env.imgbed.local'),
    [string]$OwnerPhone = '19140838906'
)
$ErrorActionPreference = 'Stop'
if (-not $ApplyLocal) { throw 'Explicit -ApplyLocal is required; this command uploads covers to the shared provider.' }
if ($OwnerPhone -notmatch '^1[0-9]{10}$') { throw 'Invalid owner identity' }
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$jar = Join-Path $repository 'blog-server/blog-app/target/blog-app.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Build and test blog-app before running this command.' }
$names = @('MEDIA_IMPORT_DB_URL','MEDIA_IMPORT_DB_USERNAME','MEDIA_IMPORT_DB_PASSWORD','MUSIC_COVER_OWNER_ID','MUSIC_COVER_BATCH_LIMIT',
    'IMGBED_BASE_URL','IMGBED_API_TOKEN','IMGBED_UPLOAD_CHANNEL','IMGBED_CHANNEL_NAME','IMGBED_ROOT_FOLDER',
    'DOCKER_HOST','DOCKER_CONTEXT')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    $endpoint = (docker context inspect --format '{{.Endpoints.docker.Host}}' | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $endpoint -cnotmatch '^npipe:////\./pipe/(dockerDesktopLinuxEngine|docker_engine)$') { throw 'Only the approved local Docker endpoint is allowed.' }
    if ($env:DOCKER_HOST -and $env:DOCKER_HOST -ne $endpoint) { throw 'Unexpected Docker host override.' }
    $env:DOCKER_CONTEXT = $null; $env:DOCKER_HOST = $endpoint
    $inspection = docker inspect unruffled_bartik | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $inspection[0].State.Running) { throw 'Local database unavailable.' }
    $settings = @{}
    foreach ($entry in $inspection[0].Config.Env) { $pair=$entry.Split('=',2); if($pair.Length -eq 2) { $settings[$pair[0]]=$pair[1] } }
    $env:MEDIA_IMPORT_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/blog_system'
    $env:MEDIA_IMPORT_DB_USERNAME = $settings['POSTGRES_USER']
    $env:MEDIA_IMPORT_DB_PASSWORD = $settings['POSTGRES_PASSWORD']
    if (-not $env:MEDIA_IMPORT_DB_USERNAME -or -not $env:MEDIA_IMPORT_DB_PASSWORD) { throw 'Local database credentials unavailable.' }
    foreach ($environmentFile in @($LocalEnvironmentFile,$ImgBedEnvironmentFile)) {
        if (-not (Test-Path -LiteralPath $environmentFile -PathType Leaf)) { continue }
        foreach ($line in Get-Content -LiteralPath $environmentFile) {
            $pair=$line.Trim().Split('=',2)
            if($pair.Length -eq 2 -and $pair[0] -in @('IMGBED_BASE_URL','IMGBED_API_TOKEN','IMGBED_UPLOAD_CHANNEL','IMGBED_CHANNEL_NAME','IMGBED_ROOT_FOLDER')) {
                [Environment]::SetEnvironmentVariable($pair[0],$pair[1],'Process')
            }
        }
    }
    if (-not $env:IMGBED_API_TOKEN) { throw 'Provider credential unavailable.' }
    $ownerQuery = "SELECT id FROM sys_user WHERE phone='$OwnerPhone' AND status=1 AND deleted=0;"
    $owner = ($ownerQuery | docker exec -i unruffled_bartik sh -c 'exec psql -U "$POSTGRES_USER" -d blog_system -X -At -v ON_ERROR_STOP=1' | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $owner -notmatch '^[1-9][0-9]{0,18}$') { throw 'Exactly one active local owner is required.' }
    $env:MUSIC_COVER_OWNER_ID = $owner
    $env:MUSIC_COVER_BATCH_LIMIT = [string]$Limit
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '_' + [Guid]::NewGuid().ToString('N')
    $directory = Join-Path $repository ('.artifacts/backups/music-covers-' + $stamp)
    $null = New-Item -ItemType Directory -Path $directory
    $containerBackup = '/tmp/music-covers-' + $stamp + '.dump'
    docker exec unruffled_bartik sh -c ('exec pg_dump -U "$POSTGRES_USER" -d blog_system -Fc -f ' + $containerBackup)
    if ($LASTEXITCODE -ne 0) { throw 'Backup failed; no cover batch started.' }
    docker exec unruffled_bartik pg_restore --list $containerBackup > $null
    if ($LASTEXITCODE -ne 0) { throw 'Backup verification failed; no cover batch started.' }
    $backup = Join-Path $directory 'blog_system.dump'
    docker cp ('unruffled_bartik:' + $containerBackup) $backup
    if ($LASTEXITCODE -ne 0 -or (Get-Item -LiteralPath $backup).Length -lt 1000) { throw 'Local backup copy failed.' }
    Write-Output ('BACKUP=' + $backup)
    $captured = @(& java '-Dfile.encoding=UTF-8' '-Djava.net.useSystemProxies=true' '-Dloader.main=com.blogsystem.app.cli.MusicCoverBackfillMain' '-cp' $jar 'org.springframework.boot.loader.launch.PropertiesLauncher' '--apply-local' 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Cover batch outcome not confirmed; inspect database operations before retrying.' }
    $receipts = @($captured | Where-Object { $_ -is [string] -and $_.StartsWith('MUSIC_COVER_RECEIPT=') })
    if ($receipts.Count -ne 1) { throw 'Cover batch receipt not confirmed; do not blindly repeat.' }
    $receipts[0]
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    $inspection=$null; $settings=$null; $captured=$null; $pair=$null; $line=$null
}
