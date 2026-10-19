param([string]$GoExecutable='go',[string]$Version='0.1.0-dev',[string]$OutputDirectory=(Join-Path $PSScriptRoot '../dist'))
$ErrorActionPreference='Stop'
if($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9]+([.-][A-Za-z0-9]+)*)?$'){throw 'Invalid version'}
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$goCommand=(Get-Command $GoExecutable -ErrorAction Stop).Source
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$outputRoot=(Resolve-Path $OutputDirectory).Path
$stage=Join-Path $outputRoot ('.package-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
$originalLocation=Get-Location
$originalGoOS=$env:GOOS; $originalGoArch=$env:GOARCH; $originalCgo=$env:CGO_ENABLED
try {
 $env:GOOS='windows';$env:GOARCH='amd64';$env:CGO_ENABLED='0'
 Set-Location (Join-Path $projectRoot 'bridge')
 $exe=Join-Path $stage 'memo-alarm-bridge.exe'
 & $goCommand build -trimpath -ldflags "-s -w -X main.version=$Version" -o $exe ./cmd/memo-alarm-bridge
 if($LASTEXITCODE -ne 0){throw 'Windows build failed'}
 $hash=(Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant()
 $manifest=[ordered]@{schema_version=1;app_id='io.github.inkee1.memoalarmbridge';version=$Version;platform='windows-amd64';executable='memo-alarm-bridge.exe';sha256=$hash;database_schema=2;signature='unsigned'}
 [IO.File]::WriteAllText((Join-Path $stage 'manifest.json'),($manifest|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
 Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging/README.txt') -Destination (Join-Path $stage 'README.txt')
 Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSE') -Destination $stage
 if(Test-Path -LiteralPath (Join-Path $projectRoot 'THIRD_PARTY_NOTICES.md')){Copy-Item -LiteralPath (Join-Path $projectRoot 'THIRD_PARTY_NOTICES.md') -Destination $stage}
 if(Test-Path -LiteralPath (Join-Path $projectRoot 'LICENSES')){Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSES') -Destination $stage -Recurse}
 $zip=Join-Path $outputRoot "memoalarm-bridge-$Version-windows-x64.zip"
 Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip -Force
 $zipHash=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
 [IO.File]::WriteAllText((Join-Path $outputRoot 'WINDOWS-SHA256SUMS.txt'),($zipHash+'  '+[IO.Path]::GetFileName($zip)+"`n"),[Text.UTF8Encoding]::new($false))
 Write-Output "Built unsigned Windows ZIP: $zip"
 Write-Output "SHA-256: $zipHash"
} finally {
 Set-Location $originalLocation
 $env:GOOS=$originalGoOS;$env:GOARCH=$originalGoArch;$env:CGO_ENABLED=$originalCgo
 $resolved=[IO.Path]::GetFullPath($stage)
 if(-not $resolved.StartsWith($outputRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or -not ([IO.Path]::GetFileName($resolved)).StartsWith('.package-')){throw 'Refusing cleanup outside package staging'}
 Remove-Item -LiteralPath $resolved -Recurse -Force
}
