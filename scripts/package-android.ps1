param([string]$AndroidSdk=$env:ANDROID_HOME,[string]$BuildTools='36.0.0',[string]$OutputDirectory=(Join-Path $PSScriptRoot '../dist'),[switch]$SkipBuild)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if(-not $AndroidSdk){throw 'Set ANDROID_HOME or pass -AndroidSdk'}
$aapt=Join-Path $AndroidSdk "build-tools/$BuildTools/aapt2.exe"
$signer=Join-Path $AndroidSdk "build-tools/$BuildTools/apksigner.bat"
if(-not(Test-Path -LiteralPath $aapt) -or -not(Test-Path -LiteralPath $signer)){throw 'Android SDK build tools missing'}
if(-not $SkipBuild){Push-Location (Join-Path $projectRoot 'android');try{& .\gradlew.bat :app:assembleDebug :app:assembleRelease --console=plain;if($LASTEXITCODE -ne 0){throw 'APK build failed'}}finally{Pop-Location}}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$outputRoot=(Resolve-Path $OutputDirectory).Path
$records=@();$checksums=@()
foreach($kind in 'debug','release'){
 $source=Join-Path $projectRoot ('android/app/build/outputs/apk/'+$kind+'/'+$(if($kind -eq 'debug'){'app-debug.apk'}else{'app-release-unsigned.apk'}))
 $badging=(& $aapt dump badging $source) -join "`n";if($LASTEXITCODE -ne 0){throw 'Cannot inspect APK metadata'}
 $match=[Regex]::Match($badging,"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'")
 if(-not $match.Success -or $match.Groups[1].Value -ne 'io.github.inkee1.memoalarmbridge'){throw 'Wrong APK application identity'}
 $version=$match.Groups[3].Value
 if($version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9]+([.-][A-Za-z0-9]+)*)?$'){throw 'Unsafe APK version'}
 $min=[int][Regex]::Match($badging,"(?m)^(?:minSdkVersion|sdkVersion):'([0-9]+)'").Groups[1].Value
 $target=[int][Regex]::Match($badging,"targetSdkVersion:'([0-9]+)'").Groups[1].Value
 if($min -ne 31 -or $target -ne 36){throw 'Unexpected APK platform bounds'}
 if($badging -match 'android.permission.(USE_EXACT_ALARM|ACCESS_NOTIFICATION_POLICY|USE_FULL_SCREEN_INTENT|READ_CONTACTS|READ_SMS|CALL_PHONE)'){throw 'Unexpected sensitive permission'}
 $savedPreference=$ErrorActionPreference;$ErrorActionPreference='Continue'
 try{$signatureOutput=(& $signer verify --print-certs $source 2>&1 | ForEach-Object {"$_"}) -join "`n";$signatureExit=$LASTEXITCODE}finally{$ErrorActionPreference=$savedPreference}
 if($kind -eq 'debug' -and $signatureExit -ne 0){throw 'Development APK signature failed verification'}
 if($kind -eq 'release' -and ($signatureExit -eq 0 -or $signatureOutput -notmatch 'DOES NOT VERIFY')){throw 'Expected an unsigned release APK'}
 $label=if($kind -eq 'debug'){'development-only'}else{'unsigned'}
 $suffix=if($kind -eq 'debug'){'debug'}else{'unsigned'}
 $file="memoalarm-bridge-$version-android-$suffix.apk";Copy-Item -LiteralPath $source -Destination (Join-Path $outputRoot $file) -Force
 $hash=(Get-FileHash -LiteralPath (Join-Path $outputRoot $file) -Algorithm SHA256).Hash.ToLowerInvariant();$checksums+=($hash+'  '+$file)
 $certificate=[Regex]::Match($signatureOutput,'certificate SHA-256 digest: ([0-9a-fA-F]+)').Groups[1].Value.ToLowerInvariant()
 $records += [ordered]@{file=$file;sha256=$hash;application_id=$match.Groups[1].Value;version=$version;version_code=[int]$match.Groups[2].Value;min_sdk=$min;target_sdk=$target;signing=$label;certificate_sha256=$certificate}
}
$info=[ordered]@{schema_version=1;notice='Development key is project-local and disposable; unsigned release requires deliberate production signing. Neither artifact is device-tested by packaging.';apks=$records}
[IO.File]::WriteAllText((Join-Path $outputRoot 'APK-INFO.json'),($info|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $outputRoot 'ANDROID-SHA256SUMS.txt'),(($checksums -join "`n")+"`n"),[Text.UTF8Encoding]::new($false))
Write-Output ('APK metadata/signature inspection and packaging passed: '+($records.file -join ', '))
# The accepted unsigned-apksigner result is not this script's exit status.
exit 0
