$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../scan-public.ps1') -Library
function Assert-Rejected([string]$Path,[string]$Text){if(@(Find-PublicContentIssues $Path ([Text.Encoding]::UTF8.GetBytes($Text))).Count -eq 0){throw ('Unsafe fixture accepted: '+$Path)}}
function Assert-Allowed([string]$Path,[string]$Text){$issues=@(Find-PublicContentIssues $Path ([Text.Encoding]::UTF8.GetBytes($Text)));if($issues.Count -ne 0){throw ('Safe fixture rejected: '+$Path+' '+($issues -join ', '))}}
Assert-Rejected 'docs/key.txt' ('-----BEGIN '+'PRIVATE KEY-----' + "`n"+'synthetic')
Assert-Rejected 'docs/token.txt' ('Authorization: '+'Bearer '+('x'*44))
Assert-Rejected 'docs/token.txt' ('gh'+'p_'+('x'*36))
Assert-Rejected 'docs/local.txt' ('C:'+ '\Users\' + 'SyntheticOwner\private')
Assert-Rejected 'docs/local.txt' ('/home/'+'syntheticowner/private')
foreach($path in '.env','android/local.properties','data/bridge.db','private.jks','sample.m4a','output.log','.superpowers/progress.md','dist/program.exe','connection.json'){Assert-Rejected $path 'synthetic'}
Assert-Allowed 'examples/note.json' '{"text":"Synthetic example","note_id":"11111111-1111-4111-8111-111111111111"}'
Assert-Allowed 'bridge/example.go' 'header.Set("Authorization", "Bearer "+deviceID+"."+token)'
Assert-Allowed 'docs/installation.md' 'Use %APPDATA% for your private data. An API key is not required.'
Write-Output 'Public-content scanner fixtures passed: key/token/path/data rejection and safe examples.'
