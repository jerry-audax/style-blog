param(
    [switch]$Apply,
    [ValidateSet('blog_system', 'blog_media_test')][string]$Database = 'blog_system',
    [string]$Schema = 'public',
    [string]$Report = (Join-Path $PSScriptRoot '../../../.artifacts/media-dry-run-2026-10-13.json'),
    [string]$MusicCatalog = (Join-Path $PSScriptRoot '../../../data/music/music-catalog.json'),
    [string]$SurpriseCatalog = (Join-Path $PSScriptRoot '../../../data/music/surprise-videos.json'),
    [string]$Playlist = (Join-Path $PSScriptRoot '../../../data/music/public-playlist.json'),
    [string]$Ddl = (Join-Path $PSScriptRoot '../../../migration/005_add_media_metadata_tables.sql')
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../architecture/backend-runtime.ps1')
$PSNativeCommandUseErrorActionPreference = $false
$containerName = 'unruffled_bartik'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$names = @('MEDIA_IMPORT_DB_URL', 'MEDIA_IMPORT_DB_USERNAME', 'MEDIA_IMPORT_DB_PASSWORD')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$previousConsoleEncoding = [Console]::OutputEncoding
$previousPipelineEncoding = $OutputEncoding
$previousMavenOptions = [Environment]::GetEnvironmentVariable('MAVEN_OPTS', 'Process')
$previousDockerHost = [Environment]::GetEnvironmentVariable('DOCKER_HOST', 'Process')
$previousDockerContext = [Environment]::GetEnvironmentVariable('DOCKER_CONTEXT', 'Process')
$importStarted = $false
$recognizedRollback = $false
$stage = 'PREFLIGHT'
$importExit = 1
$backup = $null

function Invoke-CapturedNative([string]$Command, [string[]]$Arguments) {
    # Native diagnostics may contain connection details. Never release this buffer.
    $captured = @(& $Command @Arguments 2>&1)
    $code = $LASTEXITCODE
    return @{ Output = ($captured | Out-String); ExitCode = $code }
}

function Get-ImportPath([string]$Value, [bool]$Required) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -match '["\r\n\x00]') { throw 'Invalid input path' }
    $path = [IO.Path]::GetFullPath($Value)
    if ($Required -and -not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Input file unavailable' }
    if (Test-Path -LiteralPath $path -PathType Container) { throw 'Input path is not a regular file' }
    # Forward slashes avoid trailing-backslash ambiguities in Maven's quoted CLI arguments.
    return $path.Replace('\', '/')
}

try {
    if (-not $Apply) { throw 'Explicit -Apply is required' }
    # Windows PowerShell otherwise decodes native UTF8 paths through its legacy code page.
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    $OutputEncoding = [Text.UTF8Encoding]::new($false)
    [Environment]::SetEnvironmentVariable('MAVEN_OPTS', ($previousMavenOptions + ' -Dfile.encoding=UTF-8').Trim(), 'Process')
    if ($Schema -ne 'public' -and -not ($Database -eq 'blog_media_test' -and $Schema -match '^media_it_[0-9a-f]{32}$')) {
        throw 'Invalid target schema'
    }
    $reportPath = Get-ImportPath $Report $true
    $musicPath = Get-ImportPath $MusicCatalog $true
    $surprisePath = Get-ImportPath $SurpriseCatalog $false
    $ddlPath = Get-ImportPath $Ddl $true
    $playlistPath = if ([string]::IsNullOrWhiteSpace($Playlist)) { $null } else { Get-ImportPath $Playlist $false }

    $stage = 'LOCAL_DOCKER_GUARD'
    # Do not back up a same-named container on a remote Docker daemon.
    if ($previousDockerContext) {
        # Docker's context override takes precedence over DOCKER_HOST.
        $context = Invoke-CapturedNative 'docker' @('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}', $previousDockerContext)
        if ($context.ExitCode -ne 0) { throw 'Docker context unavailable' }
        $daemon = $context.Output.Trim()
    } elseif ($previousDockerHost) {
        $daemon = $previousDockerHost
    } else {
        $context = Invoke-CapturedNative 'docker' @('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}')
        if ($context.ExitCode -ne 0) { throw 'Docker context unavailable' }
        $daemon = $context.Output.Trim()
    }
    if ($daemon -notmatch '^(npipe:////\./pipe/[A-Za-z0-9_.-]+|unix:///[^\r\n]+)$') { throw 'Docker daemon is not local' }
    # Pin all subsequent backup commands to the endpoint that passed the guard.
    [Environment]::SetEnvironmentVariable('DOCKER_CONTEXT', $null, 'Process')
    [Environment]::SetEnvironmentVariable('DOCKER_HOST', $daemon, 'Process')
    $inspection = Invoke-CapturedNative 'docker' @('inspect', $containerName)
    if ($inspection.ExitCode -ne 0) { throw 'Local database container unavailable' }
    $containers = @($inspection.Output | ConvertFrom-Json)
    if ($containers.Count -ne 1 -or $containers[0].State.Running -ne $true) { throw 'Local database container is not running' }
    $bindings = @($containers[0].NetworkSettings.Ports.'5432/tcp')
    if (@($bindings | Where-Object { $_.HostPort -eq '5432' -and $_.HostIp -in @('127.0.0.1', '0.0.0.0', '::', '::1') }).Count -eq 0) {
        throw 'Expected local database port is not available'
    }
    $settings = @{}
    foreach ($entry in $containers[0].Config.Env) {
        $parts = $entry.Split('=', 2)
        if ($parts.Length -eq 2 -and $parts[0] -in @('POSTGRES_USER', 'POSTGRES_PASSWORD')) { $settings[$parts[0]] = $parts[1] }
    }
    $username = if ($settings['POSTGRES_USER']) { $settings['POSTGRES_USER'] } else { 'postgres' }
    if ($username -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$' -or [string]::IsNullOrEmpty($settings['POSTGRES_PASSWORD'])) {
        throw 'Local database environment unavailable'
    }
    [Environment]::SetEnvironmentVariable('MEDIA_IMPORT_DB_URL', ('jdbc:postgresql://127.0.0.1:5432/' + $Database), 'Process')
    [Environment]::SetEnvironmentVariable('MEDIA_IMPORT_DB_USERNAME', $username, 'Process')
    [Environment]::SetEnvironmentVariable('MEDIA_IMPORT_DB_PASSWORD', $settings['POSTGRES_PASSWORD'], 'Process')

    if ($Database -eq 'blog_system') {
        $stage = 'BACKUP'
        $identity = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '_' + [Guid]::NewGuid().ToString('N')
        $fileName = 'blog_media_before_' + $identity + '.dump'
        $containerBackup = '/tmp/' + $fileName
        $backupDirectory = Join-Path $repository ('.artifacts/backups/' + $identity)
        $null = New-Item -ItemType Directory -Path $backupDirectory
        $backupPath = Join-Path $backupDirectory $fileName
        $dump = Invoke-CapturedNative 'docker' @('exec', $containerName, 'pg_dump', '-U', $username, '-d', $Database, '-Fc', '-f', $containerBackup)
        if ($dump.ExitCode -ne 0) { throw 'Database backup failed' }
        $archive = Invoke-CapturedNative 'docker' @('exec', $containerName, 'pg_restore', '--list', $containerBackup)
        if ($archive.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($archive.Output)) { throw 'Database backup validation failed' }
        $copy = Invoke-CapturedNative 'docker' @('cp', ($containerName + ':' + $containerBackup), $backupPath)
        if ($copy.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $backupPath -PathType Leaf)) { throw 'Database backup copy failed' }
        $stream = [IO.File]::OpenRead($backupPath)
        try {
            $header = New-Object byte[] 5
            if ($stream.Length -le 5 -or $stream.Read($header, 0, 5) -ne 5 -or [Text.Encoding]::ASCII.GetString($header) -ne 'PGDMP') {
                throw 'Copied database backup is not a custom-format archive'
            }
        } finally { $stream.Dispose() }
        $backup = [ordered]@{ path = $backupPath; sha256 = (Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash }
        foreach ($snapshot in @(
            @{ Path = $reportPath; Name = 'inventory-report.json' },
            @{ Path = $musicPath; Name = 'music-catalog.json' },
            @{ Path = $surprisePath; Name = 'surprise-videos.json' },
            @{ Path = $playlistPath; Name = 'public-playlist.json' }
        )) {
            if ($snapshot.Path -and (Test-Path -LiteralPath $snapshot.Path -PathType Leaf)) {
                Copy-Item -LiteralPath $snapshot.Path -Destination (Join-Path $backupDirectory $snapshot.Name)
            }
        }
        # Backups remain recoverable in both locations; no database or provider files are deleted.
    }

    $arguments = @('--apply','--database',$Database,'--schema',$Schema,'--report',$reportPath,
        '--music-catalog',$musicPath,'--surprise-catalog',$surprisePath,'--ddl',$ddlPath)
    if ($playlistPath -and (Test-Path -LiteralPath $playlistPath -PathType Leaf)) { $arguments += @('--playlist',$playlistPath) }
    if ($backup) { $arguments += @('--backup',$backup.path.Replace('\', '/')) }
    $stage = 'IMPORT'
    Push-Location (Join-Path $repository 'blog-server')
    try {
        $appJar = Invoke-BackendPackage -Repository $repository 6>$null
        $importStarted = $true
        $result = Invoke-CapturedNative 'java' (@('-Dfile.encoding=UTF-8',
            '-Dloader.main=com.blogsystem.app.cli.MediaMetadataImportMain','-cp',$appJar,
            'org.springframework.boot.loader.launch.PropertiesLauncher') + $arguments)
    } finally { Pop-Location }
    # 仅识别导入器完整的脱敏失败信封；混合诊断、退出码矛盾或提交未知仍按未知处理。
    $failurePattern = '\AMedia metadata import outcome=ROLLBACK_ATTEMPTED; verify database before retry\. stage=(INPUT|MYBATIS_CONFIGURATION|DATABASE_GUARD|DDL|DDL_CHECK|DDL_APPLY|ASSETS|MUSIC|VIDEO|REFERENCE_GUARD) errorType=[A-Za-z0-9_$]{1,100}\z'
    $failure = [regex]::Match($result.Output.Trim(), $failurePattern)
    if ($result.ExitCode -eq 1 -and $failure.Success) {
        $recognizedRollback = $true
        $stage = $failure.Groups[1].Value
        throw 'Importer reported a pre-commit failure'
    }
    $stage = 'RECEIPT'
    $matches = [regex]::Matches($result.Output, '(?sm)^\{\r?\n.*?^\}')
    if ($matches.Count -ne 1 -or $result.ExitCode -ne 0) { throw 'Import outcome is not confirmed' }
    $receipt = $matches[0].Value | ConvertFrom-Json
    $counts = @('tablesInitialized', 'assetsCreated', 'assetsPreserved', 'tracksCreated', 'tracksPreserved',
        'videosCreated', 'videosPreserved', 'videosSkipped', 'missingBindings')
    $allowed = @('mode', 'database', 'schema', 'videoSnapshotMissing', 'runtimeRepositorySwitched') + $counts
    $properties = @($receipt.PSObject.Properties.Name)
    if ($properties.Count -ne $allowed.Count -or @($properties | Where-Object { $_ -notin $allowed }).Count -gt 0 -or
        $receipt.mode -ne 'ADDITIVE_METADATA_IMPORT' -or $receipt.database -ne $Database -or $receipt.schema -ne $Schema -or
        $receipt.videoSnapshotMissing -isnot [bool] -or $receipt.runtimeRepositorySwitched -isnot [bool] -or $receipt.runtimeRepositorySwitched) {
        throw 'Unexpected import receipt'
    }
    foreach ($name in $counts) {
        $value = $receipt.$name
        if (($value -isnot [int] -and $value -isnot [long]) -or $value -lt 0) { throw 'Invalid import totals' }
    }
    if ($receipt.tablesInitialized -notin @(0, 1)) { throw 'Invalid table initialization result' }
    if ($backup) { $receipt | Add-Member -NotePropertyName 'backup' -NotePropertyValue $backup }
    Write-Output ($receipt | ConvertTo-Json -Depth 5)
    $importExit = 0
} catch {
    if ($recognizedRollback) {
        $importExit = 1
        [Console]::Error.WriteLine('Media metadata import outcome=ROLLBACK_ATTEMPTED; verify database before retry. stage=' + $stage + '.')
    } elseif ($importStarted) {
        $importExit = 2
        [Console]::Error.WriteLine('Media metadata import outcome is unconfirmed. Verify database state and the retained backup before rerunning; stage=' + $stage + '.')
    } else {
        [Console]::Error.WriteLine('Media metadata import refused before invoking the importer. Require -Apply, valid local inputs and a verified backup; stage=' + $stage + '.')
    }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    [Console]::OutputEncoding = $previousConsoleEncoding
    $OutputEncoding = $previousPipelineEncoding
    [Environment]::SetEnvironmentVariable('MAVEN_OPTS', $previousMavenOptions, 'Process')
    [Environment]::SetEnvironmentVariable('DOCKER_HOST', $previousDockerHost, 'Process')
    [Environment]::SetEnvironmentVariable('DOCKER_CONTEXT', $previousDockerContext, 'Process')
}
exit $importExit
