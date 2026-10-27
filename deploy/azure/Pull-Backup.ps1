# Takes a fresh backup on the VM and copies it to this PC (backup outside the VM), then verifies checksums.
# The copy contains synthetic demo data only; keep it out of Git and shared drives.
#
# Usage: .\deploy\azure\Pull-Backup.ps1 -HostName skope-team10.koreacentral.cloudapp.azure.com
param(
    [Parameter(Mandatory = $true)][string]$HostName,
    [string]$User = 'azureuser',
    [string]$Key = (Join-Path $env:USERPROFILE '.ssh\skope_azure'),
    [string]$OutDir = (Join-Path $env:USERPROFILE 'skope-backups')
)
$ErrorActionPreference = 'Stop'
$target = "$User@$HostName"
$sshArgs = @('-i', $Key, '-o', 'BatchMode=yes')

$remote = (& ssh @sshArgs $target 'sudo /opt/skope/deploy/backup.sh >/dev/null && sudo readlink -f /var/backups/skope/latest').Trim()
if ($LASTEXITCODE -ne 0 -or -not $remote) { throw 'remote backup failed' }
$name = Split-Path $remote -Leaf
# Backups are root-only on the VM; stage a user-readable copy, download it, then remove the copy.
& ssh @sshArgs $target "rm -rf /tmp/skope-pull && mkdir -m 700 /tmp/skope-pull && sudo cp -a '$remote'/. /tmp/skope-pull/ && sudo chown -R `$(id -u):`$(id -g) /tmp/skope-pull"
if ($LASTEXITCODE -ne 0) { throw 'staging failed' }
$dest = Join-Path $OutDir $name
New-Item -ItemType Directory -Force $dest | Out-Null
try {
    & scp @sshArgs -r "${target}:/tmp/skope-pull/." $dest
    if ($LASTEXITCODE -ne 0) { throw 'download failed' }
} finally { & ssh @sshArgs $target 'rm -rf /tmp/skope-pull' | Out-Null }

$bad = 0
foreach ($line in Get-Content (Join-Path $dest 'SHA256SUMS')) {
    $hash, $file = $line -split '\s+', 2
    $actual = (Get-FileHash (Join-Path $dest $file) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $hash) { Write-Output "MISMATCH $file"; $bad++ }
}
if ($bad) { throw "$bad file(s) failed verification" }
Write-Output "verified backup copied to $dest"
Get-Content (Join-Path $dest 'COUNTS')
