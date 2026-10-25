$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'media-offline-repository.ps1')
$sourceRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../../'))
$repository = New-MediaOfflineRepository -SourceRepository $sourceRepository
$wrapper = Join-Path $repository 'blog-server/tools/media/media-dry-run.ps1'
$global:MediaDryRunObservedConsoleEncoding = 0
$global:MediaDryRunObservedPipelineEncoding = 0
$global:MediaDryRunObservedMavenOptions = ''
$global:MediaDryRunUnicodeKey = 'blog/music/' + [char]0x4E2D + [char]0x6587 + '.x-m4a'
function global:mvn.cmd { $global:LASTEXITCODE = 0 }
function global:java {
    $global:MediaDryRunObservedConsoleEncoding = [Console]::OutputEncoding.CodePage
    $global:MediaDryRunObservedPipelineEncoding = $OutputEncoding.CodePage
    $global:MediaDryRunObservedMavenOptions = [Environment]::GetEnvironmentVariable('MAVEN_OPTS', 'Process')
    $global:LASTEXITCODE = 0
    return ([ordered]@{
        mode = 'READ_ONLY_DRY_RUN'; resultCode = 0
        objects = @(@{ objectKey = $global:MediaDryRunUnicodeKey })
    } | ConvertTo-Json -Depth 5)
}
$previousConsole = [Console]::OutputEncoding
$previousPipeline = $OutputEncoding
$previousOptions = [Environment]::GetEnvironmentVariable('MAVEN_OPTS', 'Process')
try {
    [Console]::OutputEncoding = [Text.Encoding]::ASCII
    $OutputEncoding = [Text.Encoding]::ASCII
    [Environment]::SetEnvironmentVariable('MAVEN_OPTS', '-Xmx256m', 'Process')
    # Explicit absent env file prevents reading the repository's real token.
    $output = @(& $wrapper -ImgBedEnvironmentFile (Join-Path $repository 'not-existing-test-environment.env') -AllowMissingSurpriseSnapshot) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0) { throw 'Controlled dry-run did not produce a successful report' }
    if ($global:MediaDryRunObservedConsoleEncoding -ne 65001 -or $global:MediaDryRunObservedPipelineEncoding -ne 65001) {
        throw 'Native dry-run boundary must use UTF8, not caller ASCII encoding'
    }
    if ($global:MediaDryRunObservedMavenOptions -notmatch '-Dfile.encoding=UTF-8') { throw 'Java encoding was not explicitly UTF8' }
    if ([Console]::OutputEncoding.CodePage -ne 20127 -or $OutputEncoding.CodePage -ne 20127 -or
        [Environment]::GetEnvironmentVariable('MAVEN_OPTS', 'Process') -ne '-Xmx256m') { throw 'Caller encoding/options were not restored' }
    $report = $output | ConvertFrom-Json
    if ($report.objects[0].objectKey -ne $global:MediaDryRunUnicodeKey -or $output.Contains([char]0xFFFD)) {
        throw 'Unicode object key was not echoed without corruption'
    }
    Write-Output 'Media dry-run wrapper offline UTF8 test: 1 passed; no real Maven, provider, or database calls.'
} finally {
    [Console]::OutputEncoding = $previousConsole
    $OutputEncoding = $previousPipeline
    [Environment]::SetEnvironmentVariable('MAVEN_OPTS', $previousOptions, 'Process')
    Remove-MediaOfflineRepository -Repository $repository
}
