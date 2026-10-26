param(
    [ValidateRange(1024,65535)][int]$BackendPort=8086,
    [ValidateRange(1024,65535)][int]$SharePort=4186,
    [switch]$Live,
    [string]$AiEnvPath
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$runtime=Join-Path $root '.runtime'
$tools=Join-Path $runtime 'free-share-tools'
$caddy=Join-Path $tools 'caddy/caddy.exe'
$cloud=Join-Path $tools 'cloudflared.exe'
$jar=Join-Path $root 'backend/build/libs/skope-0.1.0.jar'
$dist=Join-Path $root 'frontend/dist'
$mysql='C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
if($BackendPort -eq $SharePort){throw 'Backend and web ports must differ.'}
foreach($file in @($caddy,$cloud,$jar,(Join-Path $dist 'index.html'),(Join-Path $runtime 'local-secrets.json'),(Join-Path $runtime 'local-config.json'),$mysql)){
    if(!(Test-Path -LiteralPath $file)){throw "Required file not found: $file. Follow deploy/FREE-SHARE.md first."}
}
foreach($port in @($BackendPort,$SharePort)){
    $socket=[Net.Sockets.TcpClient]::new()
    try{$socket.Connect('127.0.0.1',$port);$occupied=$true}catch{$occupied=$false}finally{$socket.Dispose()}
    if($occupied){throw "Port $port is occupied; no existing process stopped."}
}
$secrets=Get-Content (Join-Path $runtime 'local-secrets.json') -Raw | ConvertFrom-Json
$config=Get-Content (Join-Path $runtime 'local-config.json') -Raw | ConvertFrom-Json
$identity=& $mysql "--defaults-extra-file=$runtime/mysql-admin.cnf" --batch --skip-column-names --execute='SELECT @@datadir'
if($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($identity.Trim()).TrimEnd('\') -ne [IO.Path]::GetFullPath((Join-Path $runtime 'mysql')).TrimEnd('\')){throw 'Expected the isolated MySQL belonging to this checkout. No database changed.'}
$accessPath=Join-Path $runtime 'team-access.json'
if(!(Test-Path -LiteralPath $accessPath)){
    $password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(20))
    # Caddy expects an LF-terminated line; avoid PowerShell's extra CR byte.
    $hashProcess=[Diagnostics.Process]::new()
    $hashProcess.StartInfo=[Diagnostics.ProcessStartInfo]::new($caddy,'hash-password --algorithm bcrypt --bcrypt-cost 12')
    $hashProcess.StartInfo.UseShellExecute=$false;$hashProcess.StartInfo.CreateNoWindow=$true
    $hashProcess.StartInfo.RedirectStandardInput=$true;$hashProcess.StartInfo.RedirectStandardOutput=$true
    [void]$hashProcess.Start()
    $hashProcess.StandardInput.Write($password+"`n");$hashProcess.StandardInput.Close()
    $hash=$hashProcess.StandardOutput.ReadToEnd().Trim();$hashProcess.WaitForExit()
    $hashExit=$hashProcess.ExitCode;$hashProcess.Dispose()
    if($hashExit -ne 0 -or !$hash.StartsWith('$2')){throw 'Team password hashing failed; sharing was not started.'}
    @{username='team';password=$password;hash=$hash.Trim()} | ConvertTo-Json | Set-Content -Encoding UTF8 $accessPath
}
$access=Get-Content -LiteralPath $accessPath -Raw | ConvertFrom-Json
if($access.username -ne 'team' -or $access.password.Length -lt 24 -or !$access.hash.StartsWith('$2')){throw 'Invalid team access configuration.'}
"TEAM ACCESS (first browser prompt):`nUsername: team`nPassword: $($access.password)`n`nSKOPE accounts (second login): .runtime/demo-accounts.txt`nDo not publish this file or commit it to GitHub." | Set-Content -Encoding UTF8 (Join-Path $runtime 'team-access.txt')
$aiNames=@('AI_BASE_URL','AI_API_KEY','AI_MODEL','EMBEDDING_MODEL','AI_TIMEOUT_SECONDS','AI_MAX_COMPLETION_TOKENS','AI_REASONING_EFFORT')
$values=@{
    DB_URL="jdbc:mysql://127.0.0.1:$($config.mysqlPort)/skope?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Seoul"
    DB_USERNAME='skope'; DB_PASSWORD=$secrets.dbPassword; DEMO_PASSWORD=$secrets.demoPassword
    SPRING_PROFILES_ACTIVE='mysql'; PORT="$BackendPort"; SERVER_ADDRESS='127.0.0.1'
    COOKIE_SECURE='true'; SERVER_FORWARD_HEADERS_STRATEGY='framework'
    SERVER_SERVLET_SESSION_COOKIE_NAME='SKOPE_SHARE_SESSION'
    APP_DEMO_RESET_PASSWORD='false'; DEMO_ROUTING_ENABLED='true'
    ATTACHMENT_DIR=(Join-Path $runtime 'shared-attachments')
    AI_MODE='DEMO'; EMBEDDING_MODE='RULE_ONLY'; AI_API_KEY=''; AI_MODEL=''; EMBEDDING_MODEL=''
    AI_BASE_URL='https://api.openai.com/v1'; AI_TIMEOUT_SECONDS='20'; AI_MAX_COMPLETION_TOKENS='1536'; AI_REASONING_EFFORT=''
    JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
    WEB_ROOT=$dist.Replace('\','/'); BACKEND_PORT="$BackendPort"; SHARE_PORT="$SharePort"
    SHARE_PASSWORD_HASH=$access.hash
}
if($Live){
    if(!$AiEnvPath){$AiEnvPath=Join-Path $runtime 'ai-verification.env'}
    foreach($line in [IO.File]::ReadAllLines($AiEnvPath)){
        $trimmed=$line.Trim();if(!$trimmed -or $trimmed.StartsWith('#')){continue}
        if($trimmed -notmatch '^([A-Z_]+)=(.*)$' -or $Matches[1] -notin $aiNames){throw 'Invalid AI environment format.'}
        $values[$Matches[1]]=$Matches[2].Trim()
    }
    if(!$values.AI_API_KEY -or !$values.AI_MODEL -or !$values.EMBEDDING_MODEL){throw 'LIVE key and both models are required.'}
    if($values.AI_BASE_URL.TrimEnd('/') -ne 'https://api.openai.com/v1'){throw 'Use the official OpenAI endpoint.'}
    $values.AI_MODE='LIVE';$values.EMBEDDING_MODE='LIVE'
    Write-Warning 'LIVE AI calls incur OpenAI charges. Hosting is free; this script does not enforce an API spending cap.'
}
$saved=@{};$started=@();$statePath=Join-Path $runtime 'free-share-processes.json'
try {
    foreach($name in $values.Keys){$saved[$name]=[Environment]::GetEnvironmentVariable($name);[Environment]::SetEnvironmentVariable($name,$values[$name])}
    & $caddy validate --config (Join-Path $root 'deploy/Caddyfile.free-share') --adapter caddyfile
    if($LASTEXITCODE -ne 0){throw 'Proxy configuration validation failed.'}
    $backend=Start-Process -FilePath 'java' -ArgumentList @('-jar',"`"$jar`"") -WorkingDirectory (Join-Path $root 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtime/free-share-backend.log" -RedirectStandardError "$runtime/free-share-backend-error.log"
    $started+=$backend
    $ready=$false
    for($i=0;$i -lt 60;$i++){
        if($backend.HasExited){throw 'Backend stopped; check .runtime/free-share-backend-error.log.'}
        try{$meta=Invoke-RestMethod -Uri "http://127.0.0.1:$BackendPort/api/meta" -TimeoutSec 2
            if($meta.databaseMode -eq 'MYSQL' -and $meta.aiMode -eq $values.AI_MODE){$ready=$true;break}
        }catch{}
        Start-Sleep -Seconds 1
    }
    if(!$ready){throw 'Backend readiness check timed out.'}
    $proxy=Start-Process -FilePath $caddy -ArgumentList @('run','--config',"`"$(Join-Path $root 'deploy/Caddyfile.free-share')`"",'--adapter','caddyfile') -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtime/free-share-proxy.log" -RedirectStandardError "$runtime/free-share-proxy-error.log"
    $started+=$proxy
    $proxyReady=$false
    for($i=0;$i -lt 20;$i++){
        if($proxy.HasExited){throw 'Proxy stopped; check .runtime/free-share-proxy-error.log.'}
        try{
            $probe=Invoke-WebRequest -Uri "http://127.0.0.1:$SharePort/api/meta" -TimeoutSec 2 -SkipHttpErrorCheck
            if([int]$probe.StatusCode -ne 401 -or ($probe.Headers['WWW-Authenticate'] -join '') -notmatch '^Basic '){throw 'Team authentication gate is not active.'}
            $auth='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("team:$($access.password)"))
            $meta=Invoke-RestMethod -Uri "http://127.0.0.1:$SharePort/api/meta" -Headers @{Authorization=$auth} -TimeoutSec 5
            if($meta.databaseMode -ne 'MYSQL' -or $meta.aiMode -ne $values.AI_MODE){throw 'Unexpected proxy backend.'}
            $proxyReady=$true;break
        }catch{}
        Start-Sleep -Milliseconds 500
    }
    if(!$proxyReady){throw 'Proxy readiness check timed out.'}
    Write-Output "Mode: $($values.AI_MODE) / $($values.EMBEDDING_MODE). Use the HTTPS URL printed below. Ctrl+C ends sharing."
    $arguments=@('tunnel','--no-autoupdate','--url',"http://127.0.0.1:$SharePort",'--http-host-header',"127.0.0.1:$SharePort")
    Write-Output 'Team password protection is active. Credentials: .runtime/team-access.txt (not printed). Email verification is not used.'
    $tunnel=Start-Process -FilePath $cloud -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runtime/free-share-tunnel.log" -RedirectStandardError "$runtime/free-share-tunnel-error.log"
    $started+=$tunnel
    @($started | ForEach-Object {@{id=$_.Id;startedUtc=$_.StartTime.ToUniversalTime().ToString('o');path=$_.Path}}) | ConvertTo-Json | Set-Content -Encoding UTF8 $statePath
    $printed=$false
    while(!$tunnel.HasExited){
        if(!$printed){
            $logText='';$reader=$null
            try {
                $stream=[IO.File]::Open("$runtime/free-share-tunnel-error.log",[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
                $reader=[IO.StreamReader]::new($stream);$logText=$reader.ReadToEnd()
            } catch [IO.IOException] {
                # The redirect file may still be opening; the next loop reads it.
            } finally {if($reader){$reader.Dispose()}}
            if($logText -match 'https://[a-z0-9-]+\.trycloudflare\.com'){
                $Matches[0] | Set-Content -Encoding UTF8 (Join-Path $runtime 'free-share-url.txt')
                Write-Output "Shared HTTPS URL: $($Matches[0])"
                $printed=$true
            }
        }
        if($backend.HasExited -or $proxy.HasExited){throw 'Local service stopped; closing the tunnel.'}
        Start-Sleep -Seconds 1
    }
    if($tunnel.ExitCode -ne 0){throw 'Tunnel stopped with an error; it was not automatically restarted. Check .runtime/free-share-tunnel-error.log.'}
} finally {
    foreach($process in ($started | Sort-Object Id -Descending)){
        if(!$process.HasExited){$process.Kill();$process.WaitForExit(5000) | Out-Null}
    }
    foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name])}
    if(Test-Path -LiteralPath $statePath){Remove-Item -LiteralPath $statePath}
    Write-Output 'Sharing origin stopped. Local MySQL and stored data were preserved.'
}
