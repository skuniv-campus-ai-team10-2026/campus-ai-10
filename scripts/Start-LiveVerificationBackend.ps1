param([ValidateRange(1024,65535)][int]$Port=8080,[switch]$DemoRouting)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$runtime=Join-Path $projectRoot '.runtime'
$secrets=Get-Content (Join-Path $runtime 'local-secrets.json') -Raw | ConvertFrom-Json
$config=Get-Content (Join-Path $runtime 'local-config.json') -Raw | ConvertFrom-Json
$mysql='C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$adminCnf=Join-Path $runtime 'mysql-admin.cnf'
$identity=& $mysql "--defaults-extra-file=$adminCnf" --batch --skip-column-names --execute='SELECT @@datadir'
if($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($identity.Trim()).TrimEnd('\') -ne [IO.Path]::GetFullPath((Join-Path $runtime 'mysql')).TrimEnd('\')){throw 'Expected the isolated workspace MySQL server.'}
$serverPort=& $mysql "--defaults-extra-file=$adminCnf" --batch --skip-column-names --execute='SELECT @@port'
if($LASTEXITCODE -ne 0 -or [int]$serverPort.Trim() -ne [int]$config.mysqlPort){throw 'MySQL configuration port does not match the verified server.'}
$allowed=@('AI_BASE_URL','AI_API_KEY','AI_MODEL','EMBEDDING_MODEL','AI_TIMEOUT_SECONDS','AI_MAX_COMPLETION_TOKENS','AI_REASONING_EFFORT')
$names=$allowed+@('DB_URL','DB_USERNAME','DB_PASSWORD','DEMO_PASSWORD','SPRING_PROFILES_ACTIVE','PORT','COOKIE_SECURE','AI_MODE','EMBEDDING_MODE','JAVA_TOOL_OPTIONS','DEMO_ROUTING_ENABLED')
$previous=@{};foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name)}
try {
    foreach($line in [IO.File]::ReadAllLines((Join-Path $runtime 'ai-verification.env'))){
        $trimmed=$line.Trim();if(!$trimmed -or $trimmed.StartsWith('#')){continue}
        if($trimmed -notmatch '^([A-Z_]+)=(.*)$' -or $Matches[1] -notin $allowed){throw 'Invalid verification environment format.'}
        [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim())
    }
    if(!$env:AI_API_KEY -or !$env:AI_MODEL -or !$env:EMBEDDING_MODEL){throw 'AI key/model configuration required.'}
    if($env:AI_BASE_URL -and $env:AI_BASE_URL.TrimEnd('/') -ne 'https://api.openai.com/v1'){throw 'Only the configured official OpenAI endpoint is permitted.'}
    $listener=[Net.Sockets.TcpClient]::new()
    try{$listener.Connect('127.0.0.1',$Port);$occupied=$true}catch{$occupied=$false}finally{$listener.Dispose()}
    if($occupied){throw 'Requested backend port is already occupied; no existing process stopped.'}
    @"
CREATE DATABASE IF NOT EXISTS skope_ai_quality CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL ON skope_ai_quality.* TO 'skope'@'localhost';
"@ | & $mysql "--defaults-extra-file=$adminCnf"
    if($LASTEXITCODE -ne 0){throw 'Verification database preparation failed'}
    $env:DB_URL="jdbc:mysql://127.0.0.1:$($config.mysqlPort)/skope_ai_quality?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Seoul"
    $env:DB_USERNAME='skope';$env:DB_PASSWORD=$secrets.dbPassword;$env:DEMO_PASSWORD=$secrets.demoPassword
    $env:SPRING_PROFILES_ACTIVE='mysql';$env:PORT=$Port.ToString();$env:COOKIE_SECURE='false'
    $env:AI_MODE='LIVE';$env:EMBEDDING_MODE='LIVE'
    if($DemoRouting){$env:DEMO_ROUTING_ENABLED='true'}
    $env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
    $jar=Join-Path $projectRoot 'backend/build/libs/skope-0.1.0.jar'
    if(!(Test-Path -LiteralPath $jar)){throw 'Build the current backend jar before starting verification.'}
    $process=Start-Process -FilePath 'java' -ArgumentList @('-jar',"`"$jar`"") -WorkingDirectory (Join-Path $projectRoot 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'live-e2e-out.log') -RedirectStandardError (Join-Path $runtime 'live-e2e-error.log')
    $process.Id | Set-Content (Join-Path $runtime 'live-e2e.pid')
    Write-Output "LIVE verification backend started, PID $($process.Id), loopback port $Port, synthetic database skope_ai_quality. Starting alone makes no inference calls. Stop this process after verification."
} finally {foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$previous[$name])}}
