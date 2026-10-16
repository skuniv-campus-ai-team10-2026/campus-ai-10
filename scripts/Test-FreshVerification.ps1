param([ValidateSet('LIVE','OFFLINE')][string]$Mode='OFFLINE')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$fixture=Join-Path $projectRoot 'docs/evaluation/fresh-v1.json'
$frozen='3c2b8cd4b6294e5f52d81c6937214af8e755008ef0153a03ded6179bde3c76f2'
if((Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash.ToLowerInvariant() -ne $frozen){throw 'Frozen evaluation labels changed; refusing execution.'}
$runtime=Join-Path $projectRoot '.runtime'
$config=Get-Content -Raw (Join-Path $runtime 'local-config.json') | ConvertFrom-Json
$secrets=Get-Content -Raw (Join-Path $runtime 'local-secrets.json') | ConvertFrom-Json
$mysql='C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$adminCnf=Join-Path $runtime 'mysql-admin.cnf'
$identity=& $mysql "--defaults-extra-file=$adminCnf" --batch --skip-column-names --execute='SELECT @@datadir'
if($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($identity.Trim()).TrimEnd('\') -ne [IO.Path]::GetFullPath((Join-Path $runtime 'mysql')).TrimEnd('\')){throw 'Expected existing workspace MySQL. No other server may be used.'}
$serverPort=& $mysql "--defaults-extra-file=$adminCnf" --batch --skip-column-names --execute='SELECT @@port'
if($LASTEXITCODE -ne 0 -or [int]$serverPort.Trim() -ne [int]$config.mysqlPort){throw 'MySQL port mismatch.'}
# Dedicated schema only; never drop/reset databases or existing reports.
@"
CREATE DATABASE IF NOT EXISTS skope_fresh_verification_20261014 CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL ON skope_fresh_verification_20261014.* TO 'skope'@'localhost';
"@ | & $mysql "--defaults-extra-file=$adminCnf"
if($LASTEXITCODE -ne 0){throw 'Verification schema preparation failed.'}
$allowed=@('AI_BASE_URL','AI_API_KEY','AI_MODEL','EMBEDDING_MODEL','AI_TIMEOUT_SECONDS','AI_MAX_COMPLETION_TOKENS','AI_REASONING_EFFORT')
$names=$allowed+@('GRADLE_USER_HOME','JAVA_TOOL_OPTIONS','SKOPE_RUN_FRESH','FRESH_MODE','FRESH_DB_URL','FRESH_DB_USERNAME','FRESH_DB_PASSWORD','FRESH_PASSWORD','FRESH_FIXTURE_SHA256','SKOPE_RUN_HOLDOUT','SKOPE_RUN_QUALITY','SKOPE_RUN_QUALITY_REPLAY')
$previous=@{};foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name)}
try{
    foreach($line in [IO.File]::ReadAllLines((Join-Path $runtime 'ai-verification.env'))){
        $value=$line.Trim();if(!$value -or $value.StartsWith('#')){continue}
        if($value -notmatch '^([A-Z_]+)=(.*)$' -or $Matches[1] -notin $allowed){throw 'Invalid AI configuration format.'}
        [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim())
    }
    if($env:AI_BASE_URL -and $env:AI_BASE_URL.TrimEnd('/') -ne 'https://api.openai.com/v1'){throw 'Only the official configured provider is permitted.'}
    if(!$env:AI_MODEL -or !$env:EMBEDDING_MODEL){throw 'Both model names are required.'}
    if($Mode -eq 'LIVE' -and !$env:AI_API_KEY){throw 'LIVE mode requires the configured API key.'}
    if($Mode -eq 'OFFLINE'){[Environment]::SetEnvironmentVariable('AI_API_KEY',$null)}
    $env:GRADLE_USER_HOME=Join-Path $projectRoot '.gradle'
    $env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
    $env:SKOPE_RUN_FRESH='1';$env:FRESH_MODE=$Mode;$env:FRESH_FIXTURE_SHA256=$frozen
    $env:FRESH_DB_URL="jdbc:mysql://127.0.0.1:$($config.mysqlPort)/skope_fresh_verification_20261014?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Seoul"
    $env:FRESH_DB_USERNAME='skope';$env:FRESH_DB_PASSWORD=$secrets.dbPassword;$env:FRESH_PASSWORD=$secrets.demoPassword
    foreach($name in @('SKOPE_RUN_HOLDOUT','SKOPE_RUN_QUALITY','SKOPE_RUN_QUALITY_REPLAY')){[Environment]::SetEnvironmentVariable($name,$null)}
    Write-Output "Fresh verification: $Mode; frozen 15 sentences/10 pairs; cumulative cap 35 (analysis 15, embedding 20). Failed attempts are never retried. Existing app servers are untouched."
    Push-Location (Join-Path $projectRoot 'backend')
    try{
        & .\gradlew.bat --offline --no-daemon test --tests edu.skope.ai.FreshVerificationTest --rerun-tasks
        if($LASTEXITCODE -ne 0){throw 'Verification harness failed. Inspect saved results; do not automatically retry provider calls.'}
    }finally{Pop-Location}
    Write-Output 'Results: docs/evaluation/fresh-v1-results.json. Quality FAIL findings are distinct from successful harness execution.'
}finally{foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$previous[$name])}}
