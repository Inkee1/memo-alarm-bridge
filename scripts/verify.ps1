param([string]$GoExecutable='go',[string]$NodeExecutable='node',[string]$AndroidSdk=$env:ANDROID_HOME,[switch]$StaticOnly)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$goCommand=(Get-Command $GoExecutable -ErrorAction Stop).Source
$nodeCommand=(Get-Command $NodeExecutable -ErrorAction Stop).Source
function Invoke-Checked([string]$Program,[string[]]$Arguments){
 & $Program @Arguments
 if($LASTEXITCODE -ne 0){throw ('Verification failed: '+$Program+' '+($Arguments -join ' '))}
}
Push-Location $projectRoot
try {
 if($StaticOnly){Write-Output 'PARTIAL MODE: Go test execution and Windows install/start/CLI/uninstall smoke are omitted.'}else{
  Invoke-Checked $goCommand @('-C','bridge','test','./...','-timeout','60s')
 }
 Invoke-Checked $goCommand @('-C','bridge','vet','./...')
 Invoke-Checked $goCommand @('-C','bridge','build','./...')
 Invoke-Checked $nodeCommand @('--test','bridge/internal/web/assets/app.test.mjs')
 Invoke-Checked $nodeCommand @('--check','bridge/internal/web/assets/app.js')
 Invoke-Checked $nodeCommand @('--check','bridge/internal/web/assets/audio.mjs')
 Invoke-Checked $nodeCommand @('--check','bridge/internal/web/assets/setup.js')
 Push-Location android
 try {Invoke-Checked '.\gradlew.bat' @(':core:test',':app:testDebugUnitTest',':app:compileDebugAndroidTestKotlin',':app:lintDebug',':app:assembleDebug',':app:assembleRelease',':app:runtimeDependencyInventory','--console=plain')}finally{Pop-Location}
 & (Join-Path $PSScriptRoot 'tests/public-content.tests.ps1')
 & (Join-Path $PSScriptRoot 'scan-public.ps1') -AllHistory
 & (Join-Path $PSScriptRoot 'package-windows.ps1') -GoExecutable $goCommand
 & (Join-Path $PSScriptRoot 'package-android.ps1') -AndroidSdk $AndroidSdk -SkipBuild
 & (Join-Path $PSScriptRoot 'tests/packaging.tests.ps1') -SkipRuntime:$StaticOnly
 if($StaticOnly){Write-Output 'PARTIAL verification passed. Windows runtime/Go execution, remote Linux CI and physical-phone/browser tests are NOT established.'}else{Write-Output 'Local automated verification passed. Physical-phone/browser tests and remote CI remain separate checks.'}
} finally {Pop-Location}
