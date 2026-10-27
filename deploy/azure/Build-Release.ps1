# Builds a release archive for the Azure VM from one exact commit, in a clean temporary checkout.
# Output: <OutDir>\skope-release-<short>.tar.gz containing skope.jar, web\ (React build), COMMIT and SHA256SUMS.
# Product code is not changed. Tests are not run here (run them before choosing the commit).
#
# Usage (from the repository, Windows PowerShell 5.1 or 7):
#   .\deploy\azure\Build-Release.ps1                      # latest origin/main
#   .\deploy\azure\Build-Release.ps1 -Ref ac233a7
param(
    [string]$Ref = 'origin/main',
    [string]$OutDir = (Join-Path $env:USERPROFILE 'skope-releases')
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$utf8 = New-Object System.Text.UTF8Encoding $false

git -C $repo fetch --quiet origin
$commit = (git -C $repo rev-parse --verify "$Ref^{commit}").Trim()
$short = $commit.Substring(0, 7)
$work = Join-Path ([IO.Path]::GetTempPath()) "skope-release-$short"
$stage = Join-Path ([IO.Path]::GetTempPath()) "skope-release-$short-stage"
foreach ($dir in @($work, $stage)) { if (Test-Path $dir) { Remove-Item -Recurse -Force $dir } }
New-Item -ItemType Directory -Force $OutDir | Out-Null

git -C $repo worktree add --detach $work $commit | Out-Null
try {
    Push-Location (Join-Path $work 'backend')
    try { & .\gradlew.bat bootJar --console=plain; if ($LASTEXITCODE -ne 0) { throw 'backend build failed' } } finally { Pop-Location }
    Push-Location (Join-Path $work 'frontend')
    try {
        & npm.cmd ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
        & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw 'frontend build failed' }
    } finally { Pop-Location }

    New-Item -ItemType Directory -Force (Join-Path $stage 'web') | Out-Null
    Copy-Item (Join-Path $work 'backend\build\libs\skope-0.1.0.jar') (Join-Path $stage 'skope.jar')
    Copy-Item (Join-Path $work 'frontend\dist\*') (Join-Path $stage 'web') -Recurse
    [IO.File]::WriteAllText((Join-Path $stage 'COMMIT'), "$commit`n", $utf8)

    # sha256sum format: "<hash>  <relative/path>" with LF line endings, verified on the VM by deploy-release.sh
    $lines = Get-ChildItem $stage -Recurse -File | Sort-Object FullName | ForEach-Object {
        $rel = $_.FullName.Substring($stage.Length + 1).Replace('\', '/')
        '{0}  {1}' -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $rel
    }
    [IO.File]::WriteAllText((Join-Path $stage 'SHA256SUMS'), (($lines -join "`n") + "`n"), $utf8)

    $archive = Join-Path $OutDir "skope-release-$short.tar.gz"
    if (Test-Path $archive) { Remove-Item -Force $archive }
    & tar.exe -czf $archive -C $stage .
    if ($LASTEXITCODE -ne 0) { throw 'archive failed' }
    Write-Output "release:  $archive"
    Write-Output "commit:   $commit"
    Write-Output "sha256:   $((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant())"
} finally {
    git -C $repo worktree remove --force $work 2>$null | Out-Null
    if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
}
