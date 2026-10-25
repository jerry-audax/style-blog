function New-MediaOfflineRepository {
    param([Parameter(Mandatory)][string]$SourceRepository)
    $fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('media-wrapper-中文-' + [Guid]::NewGuid().ToString('N'))
    $null = New-Item -ItemType Directory -Path $fixtureRoot
    try {
        foreach ($relative in @(
            'blog-server/pom.xml',
            'blog-server/tools/start-local-backend.ps1',
            'blog-server/tools/architecture/backend-runtime.ps1',
            'blog-server/tools/media/media-dry-run.ps1',
            'blog-server/tools/media/media-import.ps1',
            'migration/005_add_media_metadata_tables.sql'
        )) {
            $destination = Join-Path $fixtureRoot $relative
            $null = New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force
            Copy-Item -LiteralPath (Join-Path $SourceRepository $relative) -Destination $destination
        }
        # Native build/Java calls are replaced by the consuming tests. Never borrow a real build artifact.
        $artifact = Join-Path $fixtureRoot 'blog-server/blog-app/target/blog-app.jar'
        $null = New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($artifact)) -Force
        [IO.File]::WriteAllText($artifact, 'offline fixture, not an executable JAR')
        return $fixtureRoot
    } catch {
        Remove-MediaOfflineRepository -Repository $fixtureRoot
        throw
    }
}

function Remove-MediaOfflineRepository {
    param([Parameter(Mandatory)][string]$Repository)
    $resolved = [IO.Path]::GetFullPath($Repository)
    $temporaryRoot = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath([IO.Path]::GetTempPath()))
    if ([IO.Path]::GetDirectoryName($resolved) -ne $temporaryRoot -or
        [IO.Path]::GetFileName($resolved) -cnotmatch '^media-wrapper-中文-[0-9a-f]{32}$') {
        throw 'Refused to clean a path outside the owned offline fixture.'
    }
    $entries = @((Get-Item -LiteralPath $resolved)) + @(Get-ChildItem -LiteralPath $resolved -Recurse -Force)
    if (@($entries | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count -gt 0) {
        throw 'Refused to clean an offline fixture containing a reparse point.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
