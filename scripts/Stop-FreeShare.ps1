$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$statePath=Join-Path $root '.runtime/free-share-processes.json'
if(!(Test-Path -LiteralPath $statePath)){Write-Output 'No recorded sharing processes.';return}
$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
# Stop in reverse startup order: tunnel first. Protect against recycled process IDs.
for($i=$state.Count-1;$i -ge 0;$i--){
    $entry=$state[$i]
    $process=Get-Process -Id $entry.id -ErrorAction SilentlyContinue
    if(!$process){continue}
    if($process.StartTime.ToUniversalTime().ToString('o') -ne $entry.startedUtc -or $process.Path -ne $entry.path){throw 'Recorded process identity changed; no unrelated process will be stopped.'}
    $allowedTool=$process.Path.StartsWith((Join-Path $root '.runtime/free-share-tools'),[StringComparison]::OrdinalIgnoreCase)
    $javaCmd=if($process.ProcessName -eq 'java'){(Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)").CommandLine}else{''}
    if(!$allowedTool -and !$javaCmd.Contains((Join-Path $root 'backend/build/libs/skope-0.1.0.jar'))){throw 'Process does not belong to this sharing checkout.'}
    Stop-Process -InputObject $process
}
Write-Output 'Sharing processes stopped. MySQL and data preserved.'
