$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$secrets=Get-Content (Join-Path $projectRoot '.runtime/local-secrets.json') -Raw | ConvertFrom-Json
$config=Get-Content (Join-Path $projectRoot '.runtime/local-config.json') -Raw | ConvertFrom-Json
$mysql=Join-Path 'C:\Program Files\MySQL\MySQL Server 8.0\bin' 'mysql.exe'
$adminCnf=Join-Path $projectRoot '.runtime/mysql-admin.cnf'
$dataPath=Join-Path $projectRoot '.runtime/mysql'
$identity=& $mysql "--defaults-extra-file=$adminCnf" --batch --skip-column-names --execute='SELECT @@datadir'
if($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($identity.Trim()).TrimEnd('\') -ne [IO.Path]::GetFullPath($dataPath).TrimEnd('\')){throw 'Expected the isolated workspace MySQL server.'}
# A dedicated synthetic-data database, separate from the user's browser demo and regression database.
@"
CREATE DATABASE IF NOT EXISTS skope_verification CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL ON skope_verification.* TO 'skope'@'localhost';
"@ | & $mysql "--defaults-extra-file=$adminCnf"
if($LASTEXITCODE -ne 0){throw 'Verification database preparation failed'}
$env:DB_URL="jdbc:mysql://127.0.0.1:$($config.mysqlPort)/skope_verification?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Seoul"
$env:DB_USERNAME='skope';$env:DB_PASSWORD=$secrets.dbPassword;$env:DEMO_PASSWORD=$secrets.demoPassword
$env:SPRING_PROFILES_ACTIVE='mysql';$env:PORT='8080';$env:COOKIE_SECURE='true'
$env:AI_MODE='DEMO';$env:EMBEDDING_MODE='RULE_ONLY';$env:AI_API_KEY=''
$env:GRADLE_USER_HOME=Join-Path $projectRoot '.gradle'
Push-Location (Join-Path $projectRoot 'backend')
try{& .\gradlew.bat bootRun; if($LASTEXITCODE -ne 0){throw 'Verification backend exited'}}finally{Pop-Location}
