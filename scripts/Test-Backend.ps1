param([switch]$MySQL)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$env:GRADLE_USER_HOME=Join-Path $projectRoot '.gradle'
if($MySQL){
    $secrets=Get-Content (Join-Path $projectRoot '.runtime/local-secrets.json') -Raw | ConvertFrom-Json
    $mysqlPort=3307
    $configPath=Join-Path $projectRoot '.runtime/local-config.json'
    if(Test-Path $configPath){
        $config=Get-Content $configPath -Raw | ConvertFrom-Json
        if($null -ne $config.mysqlPort){$mysqlPort=[int]$config.mysqlPort}
    }
    if($mysqlPort -lt 1 -or $mysqlPort -gt 65535){throw 'Invalid MySQL port in .runtime/local-config.json'}
    $env:TEST_DB_URL="jdbc:mysql://127.0.0.1:$mysqlPort/skope_test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Seoul"
    $env:TEST_DB_USERNAME='skope';$env:TEST_DB_PASSWORD=$secrets.dbPassword
}else{Remove-Item Env:TEST_DB_URL,Env:TEST_DB_USERNAME,Env:TEST_DB_PASSWORD -ErrorAction SilentlyContinue}
Push-Location (Join-Path $projectRoot 'backend')
try{& .\gradlew.bat clean test bootJar; if($LASTEXITCODE -ne 0){throw 'Tests failed'}}finally{Pop-Location}
