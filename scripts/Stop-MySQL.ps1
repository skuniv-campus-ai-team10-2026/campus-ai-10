param([string]$MySqlBin='C:\Program Files\MySQL\MySQL Server 8.0\bin')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$cnf=Join-Path $projectRoot '.runtime/mysql-admin.cnf'
$expected=Join-Path $projectRoot '.runtime/mysql'
$data=& (Join-Path $MySqlBin 'mysql.exe') "--defaults-extra-file=$cnf" --batch --skip-column-names --execute='SELECT @@datadir'
if($LASTEXITCODE -ne 0){throw 'Cannot verify running MySQL instance'}
if([IO.Path]::GetFullPath($data.Trim()).TrimEnd('\') -ne [IO.Path]::GetFullPath($expected).TrimEnd('\')){throw 'Refusing to stop a database outside this workspace'}
& (Join-Path $MySqlBin 'mysqladmin.exe') "--defaults-extra-file=$cnf" shutdown
if($LASTEXITCODE -ne 0){throw 'MySQL shutdown failed'}
