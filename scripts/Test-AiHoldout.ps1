param([ValidateSet('DEMO','LIVE')][string]$Mode='DEMO',[string]$EnvironmentFile='')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
if(!$EnvironmentFile){$EnvironmentFile=Join-Path $projectRoot '.runtime/ai-verification.env'}
$allowed=@('AI_BASE_URL','AI_API_KEY','AI_MODEL','EMBEDDING_MODEL','AI_TIMEOUT_SECONDS','AI_MAX_COMPLETION_TOKENS','AI_REASONING_EFFORT')
$changed=$allowed+@('GRADLE_USER_HOME','SKOPE_RUN_HOLDOUT','SKOPE_HOLDOUT_MODE')
$previous=@{};foreach($name in $changed){$previous[$name]=[Environment]::GetEnvironmentVariable($name)}
try {
    if($Mode -eq 'LIVE' -and (Test-Path -LiteralPath $EnvironmentFile)){
        foreach($line in [IO.File]::ReadAllLines($EnvironmentFile)){
            $trimmed=$line.Trim();if(!$trimmed -or $trimmed.StartsWith('#')){continue}
            if($trimmed -notmatch '^([A-Z_]+)=(.*)$' -or $Matches[1] -notin $allowed){throw 'Invalid key or format in AI verification environment file.'}
            [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim())
        }
    }
    # This verification runner is scoped to the recommended OpenAI provider.
    # No external request is made merely by loading the file.
    if($Mode -eq 'LIVE' -and $env:AI_BASE_URL -and $env:AI_BASE_URL.TrimEnd('/') -ne 'https://api.openai.com/v1'){
        throw 'This runner permits only https://api.openai.com/v1. Review provider choice before changing it.'
    }
    $configured=$Mode -eq 'DEMO' -or ($env:AI_API_KEY -and $env:AI_MODEL -and $env:EMBEDDING_MODEL)
    if(!$configured){Write-Output 'LIVE calls will be SKIPPED: API key or model configuration missing.'}
    $env:GRADLE_USER_HOME=Join-Path $projectRoot '.gradle'
    $env:SKOPE_RUN_HOLDOUT='1';$env:SKOPE_HOLDOUT_MODE=$Mode
    Push-Location (Join-Path $projectRoot 'backend')
    try{& .\gradlew.bat test --tests edu.skope.ai.AiHoldoutTest --rerun-tasks; if($LASTEXITCODE -ne 0){throw 'Holdout measurement runner failed'}}finally{Pop-Location}
    Write-Output "Measurement saved under docs/evaluation/holdout-v1-$($Mode.ToLower())-results.json. Read quality failures separately from Gradle success."
}finally{foreach($name in $changed){[Environment]::SetEnvironmentVariable($name,$previous[$name])}}
