param([ValidateSet('LIVE','OFFLINE')][string]$Mode='OFFLINE',[string]$EnvironmentFile='')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if($Mode -eq 'OFFLINE'){
    # A new deterministic postprocessor changes the full source hash. Replay the saved LIVE
    # outputs explicitly instead of treating a cache miss as a new model-quality measurement.
    & (Join-Path $PSScriptRoot 'Test-AiQualityReplay.ps1')
    return
}
if(!$EnvironmentFile){$EnvironmentFile=Join-Path $projectRoot '.runtime/ai-verification.env'}
$allowed=@('AI_BASE_URL','AI_API_KEY','AI_MODEL','EMBEDDING_MODEL','AI_TIMEOUT_SECONDS','AI_MAX_COMPLETION_TOKENS','AI_REASONING_EFFORT')
$changed=$allowed+@('GRADLE_USER_HOME','SKOPE_RUN_QUALITY','SKOPE_QUALITY_MODE','SKOPE_RUN_HOLDOUT')
$previous=@{};foreach($name in $changed){$previous[$name]=[Environment]::GetEnvironmentVariable($name)}
try {
    if($Mode -eq 'LIVE' -and (Test-Path -LiteralPath $EnvironmentFile)){
        foreach($line in [IO.File]::ReadAllLines($EnvironmentFile)){
            $trimmed=$line.Trim();if(!$trimmed -or $trimmed.StartsWith('#')){continue}
            if($trimmed -notmatch '^([A-Z_]+)=(.*)$' -or $Matches[1] -notin $allowed){throw 'Invalid key or format in AI verification environment file.'}
            [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim())
        }
    }
    if($Mode -eq 'LIVE'){
        if(!$env:AI_API_KEY -or !$env:AI_MODEL -or !$env:EMBEDDING_MODEL){throw 'LIVE evaluation requires provider key and both model settings.'}
        if($env:AI_BASE_URL -and $env:AI_BASE_URL.TrimEnd('/') -ne 'https://api.openai.com/v1'){throw 'Only the configured OpenAI provider is permitted.'}
    }else{[Environment]::SetEnvironmentVariable('AI_API_KEY',$null)}
    $env:GRADLE_USER_HOME=Join-Path $projectRoot '.gradle'
    $env:SKOPE_RUN_QUALITY='1';$env:SKOPE_QUALITY_MODE=$Mode
    [Environment]::SetEnvironmentVariable('SKOPE_RUN_HOLDOUT',$null)
    Push-Location (Join-Path $projectRoot 'backend')
    try{& .\gradlew.bat test --tests edu.skope.ai.AiQualityTest --rerun-tasks; if($LASTEXITCODE -ne 0){throw 'Quality measurement runner failed'}}finally{Pop-Location}
    Write-Output "Quality comparison saved under docs/evaluation/quality-v2-$($Mode.ToLower())-results.json. Original holdout results are unchanged."
}finally{foreach($name in $changed){[Environment]::SetEnvironmentVariable($name,$previous[$name])}}
