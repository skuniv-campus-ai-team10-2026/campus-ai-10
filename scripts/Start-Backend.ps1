param([switch]$H2)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$secretsPath=Join-Path $projectRoot '.runtime/local-secrets.json'
if(!(Test-Path $secretsPath)){throw 'Run scripts/Initialize-Local.ps1 first, or configure the environment as described in README.'}
$secrets=Get-Content $secretsPath -Raw | ConvertFrom-Json
$env:DEMO_PASSWORD=$secrets.demoPassword
if($H2){$env:SPRING_PROFILES_ACTIVE='demo';$env:DB_URL='jdbc:h2:file:./data/skope;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH';$env:DB_USERNAME='sa';$env:DB_PASSWORD=''}else{
    $mysqlPort=3307
    $configPath=Join-Path $projectRoot '.runtime/local-config.json'
    if(Test-Path $configPath){
        $config=Get-Content $configPath -Raw | ConvertFrom-Json
        if($null -ne $config.mysqlPort){$mysqlPort=[int]$config.mysqlPort}
    }
    if($mysqlPort -lt 1 -or $mysqlPort -gt 65535){throw 'Invalid MySQL port in .runtime/local-config.json'}
    $env:SPRING_PROFILES_ACTIVE='mysql'
    $env:DB_URL="jdbc:mysql://127.0.0.1:$mysqlPort/skope?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Seoul"
    $env:DB_USERNAME='skope';$env:DB_PASSWORD=$secrets.dbPassword
}
$env:GRADLE_USER_HOME=Join-Path $projectRoot '.gradle'
Push-Location (Join-Path $projectRoot 'backend')
try{& .\gradlew.bat bootRun; if($LASTEXITCODE -ne 0){throw 'Backend exited with an error'}}finally{Pop-Location}
