param(
    [ValidateRange(1024,65535)][int]$ServerPort = 8080,
    [switch]$EnableMediaUploads
)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$jar = Join-Path $repository 'blog-server/blog-app/target/blog-app.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Build and test blog-app first.' }
if (Get-NetTCPConnection -LocalPort $ServerPort -State Listen -ErrorAction SilentlyContinue) { throw 'The requested local port is in use; no process was stopped.' }
$names = @('DB_URL','DB_HOST','DB_USERNAME','DB_PASSWORD','REDIS_DATABASE','JWT_SECRET','SA_TOKEN_JWT_SECRET_KEY',
    'PUBLICATION_API_TOKEN','PUBLICATION_BASE_URL','PUBLICATION_TOKEN_FILE','MUSIC_STATE_DIRECTORY','MUSIC_PLAYLIST_ID',
    'IMGBED_BASE_URL','IMGBED_API_TOKEN','IMGBED_UPLOAD_CHANNEL','IMGBED_CHANNEL_NAME','IMGBED_ROOT_FOLDER')
$previous = @{}
foreach ($name in $names) { $previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
try {
    $endpoint=(docker context inspect --format '{{.Endpoints.docker.Host}}' | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $endpoint -cnotmatch '^npipe:////\./pipe/(dockerDesktopLinuxEngine|docker_engine)$' -or ($env:DOCKER_HOST -and $env:DOCKER_HOST -ne $endpoint)) { throw 'Local Docker identity not confirmed.' }
    $inspection=docker inspect unruffled_bartik | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $inspection[0].State.Running) { throw 'Local PostgreSQL is unavailable.' }
    foreach ($file in @((Join-Path $repository 'local.env'),(Join-Path $repository '.env.imgbed.local'))) {
        if(-not (Test-Path -LiteralPath $file -PathType Leaf)) { continue }
        foreach($line in Get-Content -LiteralPath $file) {
            $pair=$line.Trim().Split('=',2)
            if($pair.Length -eq 2 -and $pair[0] -in $names) { [Environment]::SetEnvironmentVariable($pair[0],$pair[1],'Process') }
        }
    }
    $settings=@{}
    foreach($entry in $inspection[0].Config.Env) { $pair=$entry.Split('=',2); if($pair.Length -eq 2) { $settings[$pair[0]]=$pair[1] } }
    $env:DB_URL='jdbc:postgresql://127.0.0.1:5432/blog_system'
    $env:DB_USERNAME=$settings['POSTGRES_USER']; $env:DB_PASSWORD=$settings['POSTGRES_PASSWORD']
    if(-not $env:DB_USERNAME -or -not $env:DB_PASSWORD) { throw 'Local database credentials unavailable.' }
    $arguments=@('-Dfile.encoding=UTF-8','-Djava.net.useSystemProxies=true','-jar',$jar,'--server.address=127.0.0.1',"--server.port=$ServerPort",
        '--spring.profiles.active=dev',"--spring.datasource.url=$env:DB_URL",'--spring.data.redis.host=127.0.0.1',
        '--spring.data.redis.port=6379','--ai.enabled=false','--ai.knowledge.initialize-on-startup=false',
        '--media.catalog.storage=postgres',('--media.catalog.uploads.enabled='+$EnableMediaUploads.IsPresent.ToString().ToLowerInvariant()))
    Push-Location (Join-Path $repository 'blog-server')
    try { & java @arguments; $serverExit=$LASTEXITCODE } finally { Pop-Location }
} finally {
    foreach($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
    $inspection=$null; $settings=$null; $pair=$null; $line=$null
}
exit $serverExit
