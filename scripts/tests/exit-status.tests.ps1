param([string]$Dist=(Join-Path $PSScriptRoot '../../dist'),[string]$AndroidSdk=$env:ANDROID_HOME)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$distRoot=(Resolve-Path $Dist).Path
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$testRoot=Join-Path $distRoot ('.exit-status-'+[Guid]::NewGuid().ToString('N'))
$fixture=Join-Path $testRoot 'project'
$scripts=Join-Path $fixture 'scripts'
$tests=Join-Path $scripts 'tests'
$debug=Join-Path $fixture 'android/app/build/outputs/apk/debug/app-debug.apk'
$release=Join-Path $fixture 'android/app/build/outputs/apk/release/app-release-unsigned.apk'
$packageScript=Join-Path $scripts 'package-android.ps1'
$smokeScript=Join-Path $tests 'packaging.tests.ps1'
$runtimeScript=Join-Path $tests 'windows-runtime.ps1'
$out=Join-Path $fixture 'out'
$failures=[Collections.Generic.List[string]]::new()
$info=Get-Content -LiteralPath (Join-Path $distRoot 'APK-INFO.json') -Raw | ConvertFrom-Json
$debugSource=Join-Path $distRoot (@($info.apks | Where-Object {$_.signing -eq 'development-only'})[0].file)
$releaseSource=Join-Path $distRoot (@($info.apks | Where-Object {$_.signing -eq 'unsigned'})[0].file)
function Invoke-Case([string]$Name,[scriptblock]$Body){
 try {& $Body;Write-Output ('PASS: '+$Name)}catch{$failures.Add($Name+': '+$_.Exception.Message);Write-Output ('FAIL: '+$Name+' - '+$_.Exception.Message)}
}
function Assert-Throws([scriptblock]$Body,[string]$Message){
 $caught=$null
 try {& $Body | Out-Null}catch{$caught=$_}
 if($null -eq $caught){throw ('Expected failure: '+$Message)}
 if($caught.Exception.Message -notlike ('*'+$Message+'*')){throw ('Expected '+$Message+'; received '+$caught.Exception.Message)}
}
function Invoke-AndroidPackage {& $packageScript -AndroidSdk $AndroidSdk -SkipBuild -OutputDirectory $out}
try {
 foreach($directory in @($tests,(Split-Path $debug),(Split-Path $release))){New-Item -ItemType Directory -Path $directory -Force | Out-Null}
 Copy-Item -LiteralPath (Join-Path $projectRoot 'scripts/package-android.ps1') -Destination $packageScript
 Copy-Item -LiteralPath (Join-Path $projectRoot 'scripts/tests/packaging.tests.ps1') -Destination $smokeScript
 Copy-Item -LiteralPath $debugSource -Destination $debug
 Copy-Item -LiteralPath $releaseSource -Destination $release
 Invoke-Case 'accepted unsigned APK produces a successful script exit status' {
  Invoke-AndroidPackage | Out-Null
  if($LASTEXITCODE -ne 0){throw ('Successful Android package left LASTEXITCODE='+$LASTEXITCODE)}
 }
 # Exercise the real packaging caller without starting the distribution executable.
 # The independent full Windows smoke still runs in the main verification pipeline.
 [IO.File]::WriteAllText($runtimeScript,'param($Executable,$PackageDirectory,$Scratch)'+"`n"+'Write-Output "Synthetic runtime completed"'+"`n",[Text.UTF8Encoding]::new($false))
 Invoke-Case 'successful PowerShell runtime is independent of prior native failure' {
  & $env:ComSpec /d /c 'exit 17'
  if($LASTEXITCODE -ne 17){throw 'Could not establish inherited native status'}
  & $smokeScript -Dist $distRoot | Out-Null
  if($LASTEXITCODE -ne 0){throw ('Successful package checks left LASTEXITCODE='+$LASTEXITCODE)}
 }
 [IO.File]::WriteAllText($runtimeScript,'param($Executable,$PackageDirectory,$Scratch)'+"`n"+'throw "Synthetic runtime failure"'+"`n",[Text.UTF8Encoding]::new($false))
 Invoke-Case 'actual runtime exception remains visible' {
  Assert-Throws {& $smokeScript -Dist $distRoot} 'Synthetic runtime failure'
 }
 Invoke-Case 'invalid development signature is still rejected' {
  Copy-Item -LiteralPath $releaseSource -Destination $debug -Force
  try {Assert-Throws {Invoke-AndroidPackage} 'Development APK signature failed verification'}finally{Copy-Item -LiteralPath $debugSource -Destination $debug -Force}
 }
 Invoke-Case 'APK metadata command failure remains visible' {
  [IO.File]::WriteAllText($debug,'not an APK',[Text.UTF8Encoding]::new($false))
  try {Assert-Throws {Invoke-AndroidPackage} 'Cannot inspect APK metadata'}finally{Copy-Item -LiteralPath $debugSource -Destination $debug -Force}
 }
 Invoke-Case 'unexpectedly signed release is still rejected' {
  Copy-Item -LiteralPath $debugSource -Destination $release -Force
  try {Assert-Throws {Invoke-AndroidPackage} 'Expected an unsigned release APK'}finally{Copy-Item -LiteralPath $releaseSource -Destination $release -Force}
 }
} finally {
 $resolved=[IO.Path]::GetFullPath($testRoot)
 if(-not $resolved.StartsWith($distRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or -not ([IO.Path]::GetFileName($resolved)).StartsWith('.exit-status-')){throw 'Refusing cleanup outside exit-status test directory'}
 if(Test-Path -LiteralPath $resolved){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
if($failures.Count){throw ('Exit-status regressions failed: '+($failures -join '; '))}
Write-Output 'Exit-status regressions passed: 6 cases; expected unsigned failure handled and real failures preserved.'
exit 0
