$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$runtime=Join-Path $projectRoot '.runtime'
New-Item -ItemType Directory -Force $runtime | Out-Null
$secretPath=Join-Path $runtime 'local-secrets.json'
if(!(Test-Path $secretPath)){
    [PSCustomObject]@{dbPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24));rootPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24));demoPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(12))} | ConvertTo-Json | Set-Content -Encoding UTF8 $secretPath
}
$secrets=Get-Content $secretPath -Raw | ConvertFrom-Json
@"
LOCAL DEMO ONLY. Do not publish this file.
student1 / $($secrets.demoPassword)
student2 / $($secrets.demoPassword)
admin / $($secrets.demoPassword)
"@ | Set-Content -Encoding UTF8 (Join-Path $runtime 'demo-accounts.txt')
Write-Output 'H2 demo credentials ready: .runtime/demo-accounts.txt (ignored).'
