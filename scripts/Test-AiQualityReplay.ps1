$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$changed=@('AI_API_KEY','GRADLE_USER_HOME','SKOPE_RUN_QUALITY_REPLAY','SKOPE_RUN_QUALITY','SKOPE_RUN_HOLDOUT')
$previous=@{};foreach($name in $changed){$previous[$name]=[Environment]::GetEnvironmentVariable($name)}
try {
    # No environment file is opened. This runner cannot make new provider calls.
    [Environment]::SetEnvironmentVariable('AI_API_KEY',$null)
    [Environment]::SetEnvironmentVariable('SKOPE_RUN_QUALITY',$null)
    [Environment]::SetEnvironmentVariable('SKOPE_RUN_HOLDOUT',$null)
    $env:GRADLE_USER_HOME=Join-Path $projectRoot '.gradle'
    $env:SKOPE_RUN_QUALITY_REPLAY='1'
    Push-Location (Join-Path $projectRoot 'backend')
    try{& .\gradlew.bat test --tests edu.skope.ai.AiQualityReplayTest --rerun-tasks; if($LASTEXITCODE -ne 0){throw 'Offline quality replay failed'}}finally{Pop-Location}
    Write-Output 'Offline results saved to docs/evaluation/quality-v2-replay-results.json. No new provider inference occurred.'
}finally{foreach($name in $changed){[Environment]::SetEnvironmentVariable($name,$previous[$name])}}
