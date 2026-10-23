param([ValidateSet('PREPARE','LIVE')][string]$Mode='PREPARE')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$frozen='271ff76a6f5715ad07b33e3e38feecedb407b1bc6e3f6ffca7635c0349cdfe35'
if((Get-FileHash (Join-Path $projectRoot 'docs/evaluation/language-variation-v3.json') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $frozen){throw 'Frozen expectations changed.'}
$allowed=@('AI_BASE_URL','AI_API_KEY','AI_MODEL','EMBEDDING_MODEL','AI_TIMEOUT_SECONDS','AI_MAX_COMPLETION_TOKENS','AI_REASONING_EFFORT')
$names=$allowed+@('GRADLE_USER_HOME','JAVA_TOOL_OPTIONS','SKOPE_RUN_LANGUAGE','LANGUAGE_MODE')
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
        $preflight=Get-Content (Join-Path $projectRoot 'docs/evaluation/language-variation-v3-preflight.json') -Raw | ConvertFrom-Json
        if($preflight.fixtureSha256 -ne $frozen -or $preflight.evidenceIntegrity -ne 'PASS' -or @($preflight.rows | Where-Object requestBytes -gt 0).Count -ne 6){throw 'Run PREPARE successfully before LIVE.'}
    } else {[Environment]::SetEnvironmentVariable('AI_API_KEY',$null)}
    $env:GRADLE_USER_HOME=Join-Path $projectRoot '.gradle'
    $env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
    $env:SKOPE_RUN_LANGUAGE='1';$env:LANGUAGE_MODE=$Mode
    Write-Output "Language variation ${Mode}: maximum 6 new LLM calls, no embeddings, no retries. Reservation USD 0.049788, cap USD 0.05; PREPARE makes zero calls. No application DB writes."
    Push-Location (Join-Path $projectRoot 'backend')
    try {& .\gradlew.bat --offline --no-daemon test --tests edu.skope.ai.LanguageVariationVerificationTest --rerun-tasks;if($LASTEXITCODE -ne 0){throw 'Harness failed; do not automatically repeat paid execution.'}}finally{Pop-Location}
    Write-Output 'JUnit success means output collection completed, not semantic PASS. Review outputs against the frozen fixture.'
} finally {foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$previous[$name])}}
