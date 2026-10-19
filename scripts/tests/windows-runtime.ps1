# Executes only when explicitly invoked on a machine that permits this unsigned preview.
param([Parameter(Mandatory=$true)][string]$Executable,[Parameter(Mandatory=$true)][string]$PackageDirectory,[Parameter(Mandatory=$true)][string]$Scratch)
$ErrorActionPreference='Stop'
$exePath=(Resolve-Path $Executable).Path
$testRoot=(Resolve-Path $Scratch).Path
$packageRoot=(Resolve-Path $PackageDirectory).Path
if(-not $exePath.StartsWith($packageRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Executable outside test package'}
$appRoot=Join-Path $testRoot 'test-app'
$dataRoot=Join-Path $testRoot 'test-data'
function Invoke-TestExecutable([string[]]$Arguments,[string]$Json=''){
 $start=[Diagnostics.ProcessStartInfo]::new();$start.FileName=$exePath
 foreach($value in $Arguments){if($value.Contains('"')){throw 'Unexpected quote in test argument'}}
 $start.Arguments=($Arguments | ForEach-Object {'"'+$_+'"'}) -join ' '
 $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.WindowStyle=[Diagnostics.ProcessWindowStyle]::Hidden
 $start.RedirectStandardInput=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
 $start.StandardInputEncoding=[Text.UTF8Encoding]::new($false);$start.StandardOutputEncoding=[Text.UTF8Encoding]::new($false)
 $start.EnvironmentVariables['PATH']=Join-Path $env:SystemRoot 'System32'
 $process=[Diagnostics.Process]::Start($start)
 try{$process.StandardInput.Write($Json);$process.StandardInput.Close();$output=$process.StandardOutput.ReadToEnd();$errorText=$process.StandardError.ReadToEnd();if(-not $process.WaitForExit(20000)){throw 'Test process timed out'};if($process.ExitCode -ne 0){throw ('Test executable failed: '+$errorText+' '+$output)};return $output}finally{if(-not $process.HasExited){$process.Kill()};$process.Dispose()}
}
$manifest=Get-Content -Raw -LiteralPath (Join-Path $packageRoot 'manifest.json') | ConvertFrom-Json
if((Invoke-TestExecutable @('version')).Trim() -ne $manifest.version){throw 'Version differs from package'}
Invoke-TestExecutable @('install','--source',$packageRoot,'--root',$appRoot,'--data',$dataRoot,'--yes') | Out-Null
Invoke-TestExecutable @('install','--source',$packageRoot,'--root',$appRoot,'--data',$dataRoot,'--yes') | Out-Null
$running=$null
try {
 $running=Start-Process -FilePath $exePath -ArgumentList @('serve','--data',('"'+$dataRoot+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $testRoot 'stdout.txt') -RedirectStandardError (Join-Path $testRoot 'stderr.txt')
 $ready=$false
 for($i=0;$i -lt 15;$i++){try{$health=Invoke-TestExecutable @('doctor','--data',$dataRoot) | ConvertFrom-Json;$ready=$health.ok;if($ready){break}}catch{if($running.HasExited){throw}};Start-Sleep -Milliseconds 200}
 if(-not $ready){throw 'Bridge did not report ready'}
 $command=[ordered]@{schema_version=1;method='note.upsert';params=[ordered]@{schema_version=1;operation_id=[Guid]::NewGuid().ToString();entity_type='note';entity_id='11111111-1111-4111-8111-111111111111';base_revision=0;action='upsert';payload=@{note_id='11111111-1111-4111-8111-111111111111';text='Synthetic clean-profile memo'}}}
 $saved=Invoke-TestExecutable @('request','--json','--data',$dataRoot) ($command|ConvertTo-Json -Depth 6) | ConvertFrom-Json
 if(-not $saved.ok -or $saved.result.status -eq 'armed'){throw 'Incorrect save result'}
 Invoke-TestExecutable @('request','--json','--data',$dataRoot) '{"schema_version":1,"method":"app.stop","params":{}}' | Out-Null
 if(-not $running.WaitForExit(5000)){throw 'Bridge did not stop'}
} finally {if($running){if(-not $running.HasExited){$running.Kill()};$running.Dispose()}}
Invoke-TestExecutable @('uninstall','--root',$appRoot,'--data',$dataRoot,'--yes') | Out-Null
if(-not(Test-Path -LiteralPath (Join-Path $dataRoot 'bridge.db')) -or (Test-Path -LiteralPath (Join-Path $appRoot 'current.json'))){throw 'Uninstall did not preserve data/remove current app'}
Write-Output 'Isolated minimal-PATH install/start/CLI/stop/uninstall smoke passed; this is not a clean Windows VM.'
