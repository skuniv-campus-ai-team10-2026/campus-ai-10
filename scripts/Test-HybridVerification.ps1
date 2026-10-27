param([ValidateSet('LIVE','OFFLINE')][string]$Mode='OFFLINE',[ValidateSet('hybrid-v1','hybrid-stress-v1','intake-review-v1')][string]$Suite='hybrid-v1',[switch]$ReplayPolicy,[string]$AiEnvPath)
$ErrorActionPreference='Stop'
if($ReplayPolicy -and $Mode -ne 'OFFLINE'){throw 'Policy replay forbids LIVE mode.'}
$projectRoot=Split-Path $PSScriptRoot -Parent
$frozen='f24afd17e6cf2b784ae6cb6bc2cff7d42fb5138220df9b1f6120f7b2d7718c60'
$cap=9;$budget=0.10
if($Suite -eq 'hybrid-stress-v1'){$frozen='393ec0142f150a78e5f2a28acd98425283b8720cf95527701cd8d4fe24184112';$cap=40;$budget=0.45}
if($Suite -eq 'intake-review-v1'){$frozen='cc48e05996e66a71613038e3e0d28de450778fb8f6a96b9eeb9b631625887d9d';$cap=3;$budget=0.05}
if((Get-FileHash (Join-Path $projectRoot "docs/evaluation/$Suite.json") -Algorithm SHA256).Hash.ToLowerInvariant() -ne $frozen){throw 'Frozen expectations changed.'}
$allowed=@('AI_BASE_URL','AI_API_KEY','AI_MODEL','EMBEDDING_MODEL','AI_TIMEOUT_SECONDS','AI_MAX_COMPLETION_TOKENS','AI_REASONING_EFFORT')
$names=$allowed+@('GRADLE_USER_HOME','JAVA_TOOL_OPTIONS','SKOPE_RUN_HYBRID','HYBRID_MODE','HYBRID_FIXTURE_SHA256','HYBRID_SUITE','HYBRID_REPLAY')
$previous=@{};foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name)}
try {
    if(!$AiEnvPath){$AiEnvPath=Join-Path $projectRoot '.runtime/ai-verification.env'}
    foreach($line in [IO.File]::ReadAllLines($AiEnvPath)){
        $value=$line.Trim();if(!$value -or $value.StartsWith('#')){continue}
        if($value -notmatch '^([A-Z_]+)=(.*)$' -or $Matches[1] -notin $allowed){throw 'Invalid AI configuration format.'}
        [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim())
    }
    if($env:AI_BASE_URL -and $env:AI_BASE_URL.TrimEnd('/') -ne 'https://api.openai.com/v1'){throw 'Only official OpenAI allowed.'}
    if($env:AI_MODEL -ne 'gpt-6-luna'){throw 'Model changed; verify price first.'}
    if($Mode -eq 'LIVE' -and !$env:AI_API_KEY){throw 'Missing configured key.'}
    if($Mode -eq 'OFFLINE'){[Environment]::SetEnvironmentVariable('AI_API_KEY',$null)}
    if(!$env:GRADLE_USER_HOME){$env:GRADLE_USER_HOME=Join-Path $projectRoot '.gradle'}
    $env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
    $env:HYBRID_REPLAY=if($ReplayPolicy){'1'}else{'0'}
    $env:HYBRID_SUITE=$Suite;$env:SKOPE_RUN_HYBRID='1';$env:HYBRID_MODE=$Mode;$env:HYBRID_FIXTURE_SHA256=$frozen
    if($ReplayPolicy){$cap=0;$budget=0}
    Write-Output "Hybrid ${Mode}: $cap LLM calls maximum, zero embeddings, zero retries; conservative reservation USD $($cap * 0.010048) (budget USD $budget). No application DB writes."
    Push-Location (Join-Path $projectRoot 'backend')
    try {& .\gradlew.bat --offline --no-daemon test --tests edu.skope.ai.HybridVerificationTest --rerun-tasks;if($LASTEXITCODE -ne 0){throw 'Harness failed; do not retry paid calls.'}}finally{Pop-Location}
    $suffix=if($ReplayPolicy){'policy-v2-results'}else{'results'}
    Write-Output "Read per-case results in docs/evaluation/$Suite-$suffix.json; test completion is not an AI quality PASS."
} finally {foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$previous[$name])}}
