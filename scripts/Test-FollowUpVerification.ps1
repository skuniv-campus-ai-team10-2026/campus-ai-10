param([ValidateSet('LIVE','OFFLINE')][string]$Mode='OFFLINE')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$frozen='bad04470eaf7ac57bbc791446dbed59ed69ff6179fbccf881cc8ee48d037d0a9'
$fixture=Join-Path $projectRoot 'docs/evaluation/followup-v1.json'
if((Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash.ToLowerInvariant() -ne $frozen){throw 'Frozen expectations changed; refusing execution.'}
$allowed=@('AI_BASE_URL','AI_API_KEY','AI_MODEL','EMBEDDING_MODEL','AI_TIMEOUT_SECONDS','AI_MAX_COMPLETION_TOKENS','AI_REASONING_EFFORT')
$otherRuns=@('SKOPE_RUN_FRESH','SKOPE_RUN_HOLDOUT','SKOPE_RUN_QUALITY','SKOPE_RUN_QUALITY_REPLAY')
$names=$allowed+$otherRuns+@('GRADLE_USER_HOME','JAVA_TOOL_OPTIONS','SKOPE_RUN_FOLLOWUP','FOLLOWUP_MODE','FOLLOWUP_FIXTURE_SHA256')
$previous=@{};foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name)}
try {
    foreach($line in [IO.File]::ReadAllLines((Join-Path $projectRoot '.runtime/ai-verification.env'))){
        $value=$line.Trim();if(!$value -or $value.StartsWith('#')){continue}
        if($value -notmatch '^([A-Z_]+)=(.*)$' -or $Matches[1] -notin $allowed){throw 'Invalid AI configuration format.'}
        [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim())
    }
    if($env:AI_BASE_URL -and $env:AI_BASE_URL.TrimEnd('/') -ne 'https://api.openai.com/v1'){throw 'Only the official provider is permitted.'}
    if($env:AI_MODEL -ne 'gpt-6-luna' -or $env:EMBEDDING_MODEL -ne 'text-embedding-3-small'){throw 'Model changed; recheck pricing and frozen evaluation before any paid call.'}
    if($Mode -eq 'LIVE' -and !$env:AI_API_KEY){throw 'Configured API key is required for LIVE.'}
    if($Mode -eq 'OFFLINE'){[Environment]::SetEnvironmentVariable('AI_API_KEY',$null)}
    foreach($name in $otherRuns){[Environment]::SetEnvironmentVariable($name,$null)}
    $env:GRADLE_USER_HOME=Join-Path $projectRoot '.gradle'
    $env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
    $env:SKOPE_RUN_FOLLOWUP='1';$env:FOLLOWUP_MODE=$Mode;$env:FOLLOWUP_FIXTURE_SHA256=$frozen
    Write-Output "Follow-up verification: $Mode. Frozen 16 analysis steps / 3 unique embeddings. Conservative preflight USD 0.15404672; cumulative budget USD 1, call caps 20 + 4, automatic paid retries 0. Isolated H2 only; existing app servers and MySQL untouched."
    Push-Location (Join-Path $projectRoot 'backend')
    try {
        & .\gradlew.bat --offline --no-daemon test --tests edu.skope.ai.FollowUpVerificationTest --rerun-tasks
        if($LASTEXITCODE -ne 0){throw 'Harness failed. Inspect saved results. Do not automatically retry provider calls.'}
    } finally {Pop-Location}
    Write-Output 'Results: docs/evaluation/followup-v1-results.json. JUnit success means measurement completed, not that every AI quality case passed.'
} finally {foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$previous[$name])}}
