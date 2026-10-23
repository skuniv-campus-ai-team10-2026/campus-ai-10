param([ValidateSet('PREPARE','LIVE')][string]$Mode='PREPARE')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$frozen='6ba6fa23b49a918ebdd51c783fe58bdcaae9cfc2f815f51963c4eb425e132b36'
if((Get-FileHash (Join-Path $projectRoot 'docs/evaluation/summary-grounding-v1.json') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $frozen){throw 'Frozen expectations changed.'}
$allowed=@('AI_BASE_URL','AI_API_KEY','AI_MODEL','EMBEDDING_MODEL','AI_TIMEOUT_SECONDS','AI_MAX_COMPLETION_TOKENS','AI_REASONING_EFFORT')
$names=$allowed+@('GRADLE_USER_HOME','JAVA_TOOL_OPTIONS','SKOPE_RUN_GROUNDING','GROUNDING_MODE')
$previous=@{};foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name)}
try {
    if($Mode -eq 'LIVE') {
        foreach($line in [IO.File]::ReadAllLines((Join-Path $projectRoot '.runtime/ai-verification.env'))){
            $value=$line.Trim();if(!$value -or $value.StartsWith('#')){continue}
            if($value -notmatch '^([A-Z_]+)=(.*)$' -or $Matches[1] -notin $allowed){throw 'Invalid AI configuration format.'}
            [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim())
        }
        if($env:AI_BASE_URL -and $env:AI_BASE_URL.TrimEnd('/') -ne 'https://api.openai.com/v1'){throw 'Only official OpenAI allowed.'}
        if($env:AI_MODEL -ne 'gpt-6-luna' -or !$env:AI_API_KEY){throw 'Configured model or key is not ready.'}
        $preflight=Get-Content (Join-Path $projectRoot 'docs/evaluation/summary-grounding-v1-preflight.json') -Raw | ConvertFrom-Json
        if($preflight.fixtureSha256 -ne $frozen -or $preflight.evidenceIntegrity -ne 'PASS' -or @($preflight.rows | Where-Object requestBytes -gt 0).Count -ne 12){throw 'Run PREPARE successfully before LIVE.'}
    } else {[Environment]::SetEnvironmentVariable('AI_API_KEY',$null)}
    $env:GRADLE_USER_HOME=Join-Path $projectRoot '.gradle'
    $env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
    $env:SKOPE_RUN_GROUNDING='1';$env:GROUNDING_MODE=$Mode
    Write-Output "Summary grounding ${Mode}: maximum 12 new LLM calls, no embeddings, no retries. Reservation USD 0.084576, cap USD 0.10; PREPARE makes zero calls. No application DB writes."
    Push-Location (Join-Path $projectRoot 'backend')
    try {& .\gradlew.bat --offline --no-daemon test --tests edu.skope.ai.SummaryGroundingTest --rerun-tasks;if($LASTEXITCODE -ne 0){throw 'Harness failed; do not automatically repeat paid execution.'}}finally{Pop-Location}
    Write-Output 'JUnit success means output collection completed, not semantic PASS. Review outputs against the frozen fixture.'
} finally {foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$previous[$name])}}
