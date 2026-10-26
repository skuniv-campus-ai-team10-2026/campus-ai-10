# Portable tools only; no system install, account, or tunnel is created.
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$dir=Join-Path $root '.runtime/free-share-tools'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$headers=@{'User-Agent'='SKOPE-free-share'}
$caddyVersion='2.11.7'
$caddyName="caddy_${caddyVersion}_windows_amd64.zip"
$release="https://github.com/caddyserver/caddy/releases/download/v$caddyVersion"
Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri "$release/caddy_${caddyVersion}_checksums.txt" -OutFile "$dir/checksums.txt"
Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri "$release/$caddyName" -OutFile "$dir/caddy.zip"
$line=Get-Content "$dir/checksums.txt" | Where-Object {($_ -split '\s+')[-1] -eq $caddyName}
if(@($line).Count -ne 1){throw 'Missing or ambiguous official Caddy checksum.'}
$expected=($line -split '\s+')[0]
if($expected.Length -ne 128 -or (Get-FileHash "$dir/caddy.zip" -Algorithm SHA512).Hash -ne $expected){throw 'Caddy SHA512 checksum mismatch.'}
Expand-Archive -LiteralPath "$dir/caddy.zip" -DestinationPath "$dir/caddy" -Force
$cloudVersion='2026.10.4'
$cloudHash='411b16d40c8d872243efeb52c550053d00af0a8a97fbb32e730fb8aba429eaa8'
Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri "https://github.com/cloudflare/cloudflared/releases/download/$cloudVersion/cloudflared-windows-amd64.exe" -OutFile "$dir/cloudflared.exe"
if((Get-FileHash "$dir/cloudflared.exe" -Algorithm SHA256).Hash -ne $cloudHash){throw 'cloudflared SHA256 checksum mismatch.'}
& "$dir/caddy/caddy.exe" version
& "$dir/cloudflared.exe" version
if($LASTEXITCODE -ne 0){throw 'Tool verification failed.'}
Write-Output 'Portable tools ready. No tunnel started.'
