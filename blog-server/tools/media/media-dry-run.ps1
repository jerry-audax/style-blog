param(
    [string]$MusicCatalog = (Join-Path $PSScriptRoot '../../../data/music/music-catalog.json'),
    [string]$SurpriseCatalog = (Join-Path $PSScriptRoot '../../../data/music/surprise-videos.json'),
    [string]$ImgBedEnvironmentFile = (Join-Path $PSScriptRoot '../../../.env.imgbed.local'),
    [switch]$AllowMissingSurpriseSnapshot
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../architecture/backend-runtime.ps1')
# Environment credentials stay in-process and never enter the command line/report.
$names = @('IMGBED_BASE_URL', 'IMGBED_API_TOKEN', 'IMGBED_UPLOAD_CHANNEL', 'IMGBED_CHANNEL_NAME', 'IMGBED_ROOT_FOLDER')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$previousConsoleEncoding = [Console]::OutputEncoding
$previousPipelineEncoding = $OutputEncoding
$previousMavenOptions = [Environment]::GetEnvironmentVariable('MAVEN_OPTS', 'Process')
$dryRunExit = 1
try {
    # Preserve Chinese provider keys through Java stdout and native-command decoding.
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    $OutputEncoding = [Text.UTF8Encoding]::new($false)
    [Environment]::SetEnvironmentVariable('MAVEN_OPTS', ($previousMavenOptions + ' -Dfile.encoding=UTF-8').Trim(), 'Process')
    if (Test-Path -LiteralPath $ImgBedEnvironmentFile -PathType Leaf) {
        foreach ($line in Get-Content -LiteralPath $ImgBedEnvironmentFile) {
            $trimmed = $line.Trim()
            if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
            $parts = $trimmed.Split('=', 2)
            if ($parts.Length -ne 2 -or $parts[0].Trim() -notin $names) { continue }
            $value = $parts[1].Trim()
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $value, 'Process')
        }
    }
    $musicPath = [IO.Path]::GetFullPath($MusicCatalog)
    $surprisePath = [IO.Path]::GetFullPath($SurpriseCatalog)
    if ($musicPath.Contains('"') -or $surprisePath.Contains('"')) { throw 'Invalid snapshot path' }
    $arguments = @('--dry-run','--music-catalog',$musicPath,'--surprise-catalog',$surprisePath)
    if ($AllowMissingSurpriseSnapshot) { $arguments += '--allow-missing-surprise-snapshot' }
    Push-Location (Join-Path $PSScriptRoot '../..')
    try {
        $appJar = Invoke-BackendPackage -Repository ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))) 6>$null
        # Capture raw JVM diagnostics; release only the validated report below.
        $raw = (& java '-Dfile.encoding=UTF-8' '-Dloader.main=com.blogsystem.app.cli.MediaInventoryDryRunMain' '-cp' $appJar 'org.springframework.boot.loader.launch.PropertiesLauncher' @arguments 2>&1 | Out-String)
        $mavenExit = $LASTEXITCODE
    } finally { Pop-Location }
    $match = [regex]::Match($raw, '(?sm)^\{\r?\n.*?^\}')
    if (-not $match.Success) { throw 'Dry-run did not produce a report' }
    $report = $match.Value | ConvertFrom-Json
    if ($report.mode -ne 'READ_ONLY_DRY_RUN' -or $report.resultCode -notin @(0, 2) -or
        ($report.resultCode -eq 0 -and $mavenExit -ne 0)) { throw 'Dry-run result is invalid' }
    Write-Output $match.Value
    $dryRunExit = [int]$report.resultCode
} catch {
    [Console]::Error.WriteLine('Media dry-run failed. Check snapshot paths/format and local ImgBed configuration; no database or provider files were modified.')
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    [Console]::OutputEncoding = $previousConsoleEncoding
    $OutputEncoding = $previousPipelineEncoding
    [Environment]::SetEnvironmentVariable('MAVEN_OPTS', $previousMavenOptions, 'Process')
}
exit $dryRunExit
