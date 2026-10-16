param([string]$MySqlBin='C:\Program Files\MySQL\MySQL Server 8.0\bin',[ValidateRange(1,65535)][int]$Port=3307)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$runtime=Join-Path $projectRoot '.runtime'
New-Item -ItemType Directory -Force $runtime | Out-Null
$dataPath=Join-Path $runtime 'mysql'
$secretPath=Join-Path $runtime 'local-secrets.json'
$configPath=Join-Path $runtime 'local-config.json'
$server=Join-Path $MySqlBin 'mysqld.exe'
$client=Join-Path $MySqlBin 'mysql.exe'
if(!(Test-Path $server)){throw 'MySQL 8 binary not found. Pass -MySqlBin or use the H2 demo profile.'}
if(!(Test-Path (Join-Path $dataPath 'auto.cnf'))){
    New-Item -ItemType Directory -Force $dataPath | Out-Null
    & $server --no-defaults --initialize-insecure "--datadir=$dataPath" --console
    if($LASTEXITCODE -ne 0){throw 'MySQL initialization failed'}
}
if(Test-Path $secretPath){$secrets=Get-Content $secretPath -Raw | ConvertFrom-Json}else{
    $secrets=[PSCustomObject]@{dbPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24));rootPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24));demoPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(12))}
    $secrets | ConvertTo-Json | Set-Content -Encoding UTF8 $secretPath
}
$adminCnf=Join-Path $runtime 'mysql-admin.cnf'
@"
[client]
host=127.0.0.1
port=$Port
user=root
password=$($secrets.rootPassword)
"@ | Set-Content -Encoding ascii $adminCnf
$listening=$false
try{$tcp=[Net.Sockets.TcpClient]::new();$tcp.Connect('127.0.0.1',$Port);$listening=$true;$tcp.Dispose()}catch{}
if(!$listening){
    $process=Start-Process -FilePath $server -ArgumentList @('--no-defaults',"--datadir=`"$dataPath`"","--port=$Port",'--bind-address=127.0.0.1','--mysqlx=0','--console') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'mysql-out.log') -RedirectStandardError (Join-Path $runtime 'mysql-error.log')
    $process.Id | Set-Content (Join-Path $runtime 'mysql.pid')
    for($i=0;$i -lt 30;$i++){Start-Sleep -Seconds 1;try{$tcp=[Net.Sockets.TcpClient]::new();$tcp.Connect('127.0.0.1',$Port);$tcp.Dispose();break}catch{}}
}
$identity=& $client "--defaults-extra-file=$adminCnf" --batch --skip-column-names --execute='SELECT @@datadir' 2>$null
if($LASTEXITCODE -ne 0){
    $identity=& $client --no-defaults --host=127.0.0.1 "--port=$Port" --user=root --batch --skip-column-names --execute='SELECT @@datadir' 2>$null
    if($LASTEXITCODE -ne 0){throw 'Port is unavailable or belongs to a different MySQL instance. No changes made.'}
    if([IO.Path]::GetFullPath($identity.Trim()).TrimEnd('\') -ne [IO.Path]::GetFullPath($dataPath).TrimEnd('\')){throw 'Refusing to modify a MySQL server outside this workspace.'}
    "ALTER USER 'root'@'localhost' IDENTIFIED BY '$($secrets.rootPassword)';" | & $client --no-defaults --host=127.0.0.1 "--port=$Port" --user=root
    if($LASTEXITCODE -ne 0){throw 'Root password setup failed'}
}
if([IO.Path]::GetFullPath($identity.Trim()).TrimEnd('\') -ne [IO.Path]::GetFullPath($dataPath).TrimEnd('\')){throw 'Unexpected MySQL data directory.'}
@"
CREATE DATABASE IF NOT EXISTS skope CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS skope_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'skope'@'localhost' IDENTIFIED BY '$($secrets.dbPassword)';
GRANT ALL ON skope.* TO 'skope'@'localhost';
GRANT ALL ON skope_test.* TO 'skope'@'localhost';
"@ | & $client "--defaults-extra-file=$adminCnf"
if($LASTEXITCODE -ne 0){throw 'Database setup failed'}
# Persist only after verifying the workspace server and successfully preparing both databases.
# Start-Backend and Test-Backend share this port; existing installations without this file use 3307.
[PSCustomObject]@{mysqlPort=$Port} | ConvertTo-Json | Set-Content -Encoding UTF8 $configPath
@"
LOCAL DEMO ONLY. Do not publish this file.
student1 / $($secrets.demoPassword)
student2 / $($secrets.demoPassword)
admin / $($secrets.demoPassword)
"@ | Set-Content -Encoding UTF8 (Join-Path $runtime 'demo-accounts.txt')
Write-Output 'Isolated MySQL ready. Credentials: .runtime/demo-accounts.txt (ignored).'
