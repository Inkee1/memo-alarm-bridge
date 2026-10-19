param([string]$Dist=(Join-Path $PSScriptRoot '../../dist'),[switch]$SkipRuntime)
$ErrorActionPreference='Stop'
$distRoot=(Resolve-Path $Dist).Path
$zip=Get-ChildItem -LiteralPath $distRoot -Filter '*windows-x64.zip' | Select-Object -First 1
if(-not $zip){throw 'Windows ZIP missing'}
$scratch=Join-Path $distRoot ('.verify-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
try {
 Expand-Archive -LiteralPath $zip.FullName -DestinationPath $scratch
 $manifest=Get-Content -LiteralPath (Join-Path $scratch 'manifest.json') -Raw | ConvertFrom-Json
 if($manifest.app_id -ne 'io.github.inkee1.memoalarmbridge' -or $manifest.signature -ne 'unsigned' -or $manifest.executable -ne 'memo-alarm-bridge.exe'){throw 'Incorrect Windows manifest'}
 $exe=Join-Path $scratch $manifest.executable
 if((Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.sha256){throw 'Windows executable hash mismatch'}
 $allowed=@('memo-alarm-bridge.exe','manifest.json','README.txt','LICENSE','THIRD_PARTY_NOTICES.md','LICENSES')
 foreach($file in Get-ChildItem -LiteralPath $scratch){if($file.Name -notin $allowed){throw ('Unexpected package entry: '+$file.Name)}}
 foreach($file in Get-ChildItem -LiteralPath $scratch -Recurse -File){if($file.Name -match '\.(db|key|pem|keystore|log)$'){throw 'Private file in package'}}
 $signature=Get-AuthenticodeSignature -LiteralPath $exe
 if($signature.Status -ne 'NotSigned'){throw ('Manifest must accurately label Windows signature: '+$signature.Status)}
 Write-Output 'Windows ZIP manifest, hash, unsigned label and content allowlist passed.'
 if($SkipRuntime){Write-Output 'SKIPPED: Windows execution, install/start/CLI/uninstall smoke and minimal-PATH runtime.'}else{
  & (Join-Path $PSScriptRoot 'windows-runtime.ps1') -Executable $exe -PackageDirectory $scratch -Scratch $scratch
  # This PowerShell script checks Process.ExitCode and throws on failure.
  # LASTEXITCODE here could belong to an unrelated earlier native command.
 }
 $info=Get-Content -LiteralPath (Join-Path $distRoot 'APK-INFO.json') -Raw | ConvertFrom-Json
 foreach($apk in $info.apks){$file=Join-Path $distRoot $apk.file;if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $apk.sha256){throw 'APK hash mismatch'};if($apk.application_id -ne 'io.github.inkee1.memoalarmbridge' -or $apk.min_sdk -ne 31 -or $apk.target_sdk -ne 36){throw 'Incorrect APK support metadata'}}
 if(@($info.apks | Where-Object {$_.signing -eq 'development-only'}).Count -ne 1 -or @($info.apks | Where-Object {$_.signing -eq 'unsigned'}).Count -ne 1){throw 'APK signing labels missing'}
 Write-Output 'APK package hashes, identity, API bounds and signing labels passed.'
} finally {
 $resolved=[IO.Path]::GetFullPath($scratch)
 if(-not $resolved.StartsWith($distRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or -not ([IO.Path]::GetFileName($resolved)).StartsWith('.verify-')){throw 'Refusing cleanup outside private verification directory'}
 Remove-Item -LiteralPath $resolved -Recurse -Force
}
exit 0
