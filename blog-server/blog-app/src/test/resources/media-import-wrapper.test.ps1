$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'media-offline-repository.ps1')
$sourceRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../../'))
$repository = New-MediaOfflineRepository -SourceRepository $sourceRepository
$wrapper = Join-Path $repository 'blog-server/tools/media/media-import.ps1'
$global:MediaWrapperTestScenario = ''
$global:MediaWrapperTestNativeCalls = @()
$global:MediaWrapperTestObservedEnvironment = @{}
$global:MediaWrapperTestObservedConsoleEncoding = 0
$global:MediaWrapperTestObservedPipelineEncoding = 0
$global:MediaWrapperTestBackupDirectories = @()
$inputDirectory = Join-Path ([IO.Path]::GetTempPath()) ('arch-import-fixture-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $inputDirectory
'{}' | Set-Content -LiteralPath (Join-Path $inputDirectory 'report.json') -Encoding utf8
'{"tracks":[]}' | Set-Content -LiteralPath (Join-Path $inputDirectory 'music.json') -Encoding utf8

# These boundary fakes make all test scenarios offline; no Docker/Maven process starts.
function global:docker {
    $global:MediaWrapperTestNativeCalls += ,@($args)
    $global:LASTEXITCODE = 0
    if ($args[0] -eq 'context') {
        if ($args[-1] -eq 'remote-test') { return 'ssh://synthetic-remote.test' }
        return 'npipe:////./pipe/docker_engine'
    }
    if ($args[0] -eq 'inspect') {
        return '[{"Config":{"Env":["POSTGRES_USER=postgres","POSTGRES_PASSWORD=synthetic-test-credential"]},"State":{"Running":true},"NetworkSettings":{"Ports":{"5432/tcp":[{"HostIp":"127.0.0.1","HostPort":"5432"}]}}}]'
    }
    if ($args[0] -eq 'exec' -and $args[2] -eq 'pg_dump') {
        $identity = [IO.Path]::GetFileName($args[-1]).Replace('blog_media_before_', '').Replace('.dump', '')
        $global:MediaWrapperTestBackupDirectories += (Join-Path $repository ('.artifacts/backups/' + $identity))
        if ($global:MediaWrapperTestScenario -eq 'backup_failure') { $global:LASTEXITCODE = 1; return 'synthetic-test-credential must never be output' }
        return
    }
    if ($args[0] -eq 'exec' -and $args[2] -eq 'pg_restore') { return '; simulated custom-format archive list' }
    if ($args[0] -eq 'cp') {
        [IO.File]::WriteAllBytes($args[2], [Text.Encoding]::ASCII.GetBytes('PGDMPsimulated-custom-archive'))
        return
    }
    throw 'Unexpected Docker boundary request'
}

function global:mvn.cmd {
    $global:MediaWrapperTestNativeCalls += ,(@('mvn') + @($args))
    $global:LASTEXITCODE = 0
}
function global:java {
    $global:MediaWrapperTestNativeCalls += ,(@('java') + @($args))
    foreach ($name in @('MEDIA_IMPORT_DB_URL', 'MEDIA_IMPORT_DB_USERNAME', 'MEDIA_IMPORT_DB_PASSWORD', 'DOCKER_HOST', 'DOCKER_CONTEXT')) {
        $global:MediaWrapperTestObservedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    $global:MediaWrapperTestObservedConsoleEncoding = [Console]::OutputEncoding.CodePage
    $global:MediaWrapperTestObservedPipelineEncoding = $OutputEncoding.CodePage
    $global:MediaWrapperTestObservedEnvironment['MAVEN_OPTS'] = [Environment]::GetEnvironmentVariable('MAVEN_OPTS', 'Process')
    $global:LASTEXITCODE = 0
    if ($global:MediaWrapperTestScenario -eq 'missing_receipt') {
        $global:LASTEXITCODE = 1
        return 'synthetic-test-credential native failure details'
    }
    if ($global:MediaWrapperTestScenario -in @('guard_failure', 'origin_failure', 'unknown_commit', 'conflicting_exit', 'extra_diagnostics')) {
        $global:LASTEXITCODE = 1
        $stage = if ($global:MediaWrapperTestScenario -eq 'origin_failure') { 'INPUT' } else { 'REFERENCE_GUARD' }
        $envelope = 'Media metadata import outcome=ROLLBACK_ATTEMPTED; verify database before retry. stage=' + $stage + ' errorType=MediaReferenceWriteException'
        if ($global:MediaWrapperTestScenario -eq 'unknown_commit') {
            $global:LASTEXITCODE = 2
            return 'Media metadata import outcome=COMMIT_UNKNOWN; verify database before retry. stage=COMMIT errorType=TransactionSystemException'
        }
        if ($global:MediaWrapperTestScenario -eq 'conflicting_exit') { $global:LASTEXITCODE = 2 }
        if ($global:MediaWrapperTestScenario -eq 'extra_diagnostics') { return ($envelope + "`nsynthetic-test-credential arbitrary diagnostics") }
        return $envelope
    }
    $database = if ($global:MediaWrapperTestScenario -eq 'wrong_receipt') { 'family_memory' } else { 'blog_system' }
    return @'
synthetic-test-credential raw diagnostic
{
  "mode": "ADDITIVE_METADATA_IMPORT",
  "database": "DATABASE_PLACEHOLDER",
  "schema": "public",
  "tablesInitialized": 1,
  "assetsCreated": 52,
  "assetsPreserved": 0,
  "tracksCreated": 36,
  "tracksPreserved": 0,
  "videosCreated": 0,
  "videosPreserved": 0,
  "videosSkipped": 0,
  "missingBindings": 0,
  "videoSnapshotMissing": true,
  "runtimeRepositorySwitched": false
}
'@.Replace('DATABASE_PLACEHOLDER', $database)
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Invoke-WrapperCase([string]$Scenario, [switch]$Apply) {
    $global:MediaWrapperTestScenario = $Scenario
    $global:MediaWrapperTestNativeCalls = @()
    $global:MediaWrapperTestObservedEnvironment = @{}
    $global:MediaWrapperTestObservedConsoleEncoding = 0
    $global:MediaWrapperTestObservedPipelineEncoding = 0
    $before = @{}
    $names = @('MEDIA_IMPORT_DB_URL', 'MEDIA_IMPORT_DB_USERNAME', 'MEDIA_IMPORT_DB_PASSWORD')
    foreach ($name in $names) {
        $before[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, 'caller-sentinel', 'Process')
    }
    $errors = [IO.StringWriter]::new()
    $previousError = [Console]::Error
    $previousConsoleEncoding = [Console]::OutputEncoding
    $previousPipelineEncoding = $OutputEncoding
    $previousMavenOptions = [Environment]::GetEnvironmentVariable('MAVEN_OPTS', 'Process')
    $previousDockerHost = [Environment]::GetEnvironmentVariable('DOCKER_HOST', 'Process')
    $previousDockerContext = [Environment]::GetEnvironmentVariable('DOCKER_CONTEXT', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('DOCKER_HOST', $null, 'Process')
        [Environment]::SetEnvironmentVariable('DOCKER_CONTEXT', $null, 'Process')
        if ($Scenario -eq 'remote_context') {
            [Environment]::SetEnvironmentVariable('DOCKER_HOST', 'npipe:////./pipe/docker_engine', 'Process')
            [Environment]::SetEnvironmentVariable('DOCKER_CONTEXT', 'remote-test', 'Process')
        }
        $expectedDockerHost = [Environment]::GetEnvironmentVariable('DOCKER_HOST', 'Process')
        $expectedDockerContext = [Environment]::GetEnvironmentVariable('DOCKER_CONTEXT', 'Process')
        [Console]::OutputEncoding = [Text.Encoding]::ASCII
        $OutputEncoding = [Text.Encoding]::ASCII
        [Environment]::SetEnvironmentVariable('MAVEN_OPTS', '-Xmx256m', 'Process')
        [Console]::SetError($errors)
        $parameters = @{ Database = 'blog_system'; Report=(Join-Path $inputDirectory 'report.json');
            MusicCatalog=(Join-Path $inputDirectory 'music.json'); SurpriseCatalog=(Join-Path $inputDirectory 'absent-surprise.json'); Playlist='' }
        if ($Apply) { $parameters.Apply = $true }
        $output = @(& $wrapper @parameters) -join [Environment]::NewLine
        $code = $LASTEXITCODE
        Assert-True ([Environment]::GetEnvironmentVariable('DOCKER_HOST', 'Process') -eq $expectedDockerHost) 'Caller Docker host was not restored'
        Assert-True ([Environment]::GetEnvironmentVariable('DOCKER_CONTEXT', 'Process') -eq $expectedDockerContext) 'Caller Docker context was not restored'
        foreach ($name in $names) {
            Assert-True ([Environment]::GetEnvironmentVariable($name, 'Process') -eq 'caller-sentinel') 'Caller environment was not restored'
        }
        Assert-True ([Console]::OutputEncoding.CodePage -eq 20127 -and $OutputEncoding.CodePage -eq 20127) 'Caller encoding was not restored'
        Assert-True ([Environment]::GetEnvironmentVariable('MAVEN_OPTS', 'Process') -eq '-Xmx256m') 'Caller Maven options were not restored'
        Assert-True (-not (($output + $errors.ToString()).Contains('synthetic-test-credential'))) 'Raw native diagnostics leaked'
        return @{ Output = $output; Errors = $errors.ToString(); Code = $code; Calls = @($global:MediaWrapperTestNativeCalls) }
    } finally {
        [Console]::SetError($previousError)
        [Console]::OutputEncoding = $previousConsoleEncoding
        $OutputEncoding = $previousPipelineEncoding
        [Environment]::SetEnvironmentVariable('MAVEN_OPTS', $previousMavenOptions, 'Process')
        [Environment]::SetEnvironmentVariable('DOCKER_HOST', $previousDockerHost, 'Process')
        [Environment]::SetEnvironmentVariable('DOCKER_CONTEXT', $previousDockerContext, 'Process')
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process') }
        $errors.Dispose()
    }
}

try {
$case = Invoke-WrapperCase 'no_apply'
Assert-True ($case.Code -eq 1 -and $case.Output -eq '' -and $case.Calls.Count -eq 0) 'Missing -Apply must refuse all external work'

$case = Invoke-WrapperCase 'backup_failure' -Apply
Assert-True ($case.Code -eq 1 -and $case.Output -eq '') ('Backup failure must not return an import receipt; code=' + $case.Code + ' outputLength=' + $case.Output.Length)
Assert-True (@($case.Calls | Where-Object { $_[0] -eq 'mvn' }).Count -eq 0) 'Backup failure must prevent database import'

$case = Invoke-WrapperCase 'success' -Apply
Assert-True ($case.Code -eq 0 -and $case.Errors -eq '') 'Successful import must produce only a JSON receipt'
$receipt = $case.Output | ConvertFrom-Json
Assert-True ($receipt.mode -eq 'ADDITIVE_METADATA_IMPORT' -and $receipt.assetsCreated -eq 52 -and $receipt.tracksCreated -eq 36) 'Receipt did not preserve validated import totals'
Assert-True ($receipt.backup.sha256 -match '^[0-9A-Fa-f]{64}$' -and (Test-Path -LiteralPath $receipt.backup.path -PathType Leaf)) 'Receipt must identify a verified recoverable backup'
Assert-True ($global:MediaWrapperTestObservedEnvironment.MEDIA_IMPORT_DB_URL -eq 'jdbc:postgresql://127.0.0.1:5432/blog_system') 'Import target must be the explicitly selected local database'
Assert-True ($global:MediaWrapperTestObservedEnvironment.DOCKER_HOST -eq 'npipe:////./pipe/docker_engine' -and -not $global:MediaWrapperTestObservedEnvironment.DOCKER_CONTEXT) 'Backup/import boundary must pin the validated local Docker endpoint'
Assert-True ($global:MediaWrapperTestObservedConsoleEncoding -eq 65001 -and $global:MediaWrapperTestObservedPipelineEncoding -eq 65001) 'Native import boundary must use UTF8, not caller ASCII encoding'
Assert-True ($global:MediaWrapperTestObservedEnvironment.MAVEN_OPTS -match '-Dfile.encoding=UTF-8') 'Maven must request explicit UTF8 in the Java process'
Assert-True ($receipt.backup.path.Contains($repository) -and -not $receipt.backup.path.Contains([char]0xFFFD)) 'Unicode backup receipt must preserve the original repository path'
$java = @($case.Calls | Where-Object { $_[0] -eq 'java' })[0]
Assert-True ($java -contains '-Dloader.main=com.blogsystem.app.cli.MediaMetadataImportMain' -and
    $java -contains 'org.springframework.boot.loader.launch.PropertiesLauncher' -and $java -contains '--apply') 'Packaged CLI must retain its selected main and approved apply flag'
$reportIndex = [array]::IndexOf($java, '--report')
$backupIndex = [array]::IndexOf($java, '--backup')
Assert-True ($reportIndex -ge 0 -and $java[$reportIndex + 1] -eq (Join-Path $inputDirectory 'report.json').Replace('\','/') -and
    $backupIndex -ge 0 -and $java[$backupIndex + 1] -eq $receipt.backup.path.Replace('\','/')) 'CLI paths must remain individual Unicode-safe argument values'

$case = Invoke-WrapperCase 'missing_receipt' -Apply
Assert-True ($case.Code -eq 2 -and $case.Output -eq '' -and $case.Errors -match 'unconfirmed') 'Missing receipt must report unconfirmed outcome, never promise no writes'

$case = Invoke-WrapperCase 'wrong_receipt' -Apply
Assert-True ($case.Code -eq 2 -and $case.Output -eq '' -and $case.Errors -match 'unconfirmed') 'A receipt for another database must not be accepted'

foreach ($scenario in @('guard_failure', 'origin_failure')) {
    $case = Invoke-WrapperCase $scenario -Apply
    $expectedStage = if ($scenario -eq 'origin_failure') { 'INPUT' } else { 'REFERENCE_GUARD' }
    Assert-True ($case.Code -eq 1 -and $case.Output -eq '' -and $case.Errors -match 'ROLLBACK_ATTEMPTED' -and $case.Errors.Contains('stage=' + $expectedStage)) 'A strict pre-commit failure envelope must retain exit 1 and its safe stage'
}
foreach ($scenario in @('unknown_commit', 'conflicting_exit', 'extra_diagnostics')) {
    $case = Invoke-WrapperCase $scenario -Apply
    Assert-True ($case.Code -eq 2 -and $case.Output -eq '' -and $case.Errors -match 'unconfirmed') 'Unknown, inconsistent or mixed native results must never claim rollback'
}

$case = Invoke-WrapperCase 'remote_context' -Apply
Assert-True ($case.Code -eq 1 -and $case.Output -eq '') 'Remote DOCKER_CONTEXT must override local DOCKER_HOST and be rejected'
Assert-True (@($case.Calls | Where-Object { $_[0] -in @('inspect','exec','cp','mvn','java') }).Count -eq 0) 'Remote context must refuse before container inspection, backup, or import'

Write-Output 'Media import wrapper offline behavior tests: 6 passed; failure-envelope checks: 5 passed; no real Docker, Maven, or database calls.'
} finally {
    # Only this fake's exact generated directories are removed; production backups are never targets.
    $backupRoot = [IO.Path]::GetFullPath((Join-Path $repository '.artifacts/backups'))
    foreach ($directory in $global:MediaWrapperTestBackupDirectories) {
        $resolved = [IO.Path]::GetFullPath($directory)
        if ([IO.Path]::GetDirectoryName($resolved) -ne $backupRoot -or [IO.Path]::GetFileName($resolved) -notmatch '^\d{8}T\d{6}Z_[0-9a-f]{32}$') {
            throw 'Refused to clean a test path outside the verified backup directory'
        }
        if (Test-Path -LiteralPath $resolved -PathType Container) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    }
    $resolvedInput = [IO.Path]::GetFullPath($inputDirectory)
    if ([IO.Path]::GetDirectoryName($resolvedInput) -ne [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath([IO.Path]::GetTempPath())) -or
        [IO.Path]::GetFileName($resolvedInput) -notmatch '^arch-import-fixture-[0-9a-f]{32}$') { throw 'Unsafe fixture cleanup target' }
    Remove-Item -LiteralPath $resolvedInput -Recurse -Force
    Remove-MediaOfflineRepository -Repository $repository
}
