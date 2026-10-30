# One command for a release: build -> data snapshot -> off-server backup -> deploy -> check.sh -> read-only checks
# -> data comparison -> report. Stops at the first failed step and still writes the report.
# Files no report and calls no AI provider, so it is safe while the server runs in LIVE mode.
#
# Usage (from this repository, PowerShell):
#   .\deploy\azure\Release-And-Verify.ps1 -Ref <commit from the team lead>
# Output: %USERPROFILE%\skope-releases\release-<short>-<time>\REPORT.md (+ raw step outputs). No secrets are written.
param(
    [Parameter(Mandatory = $true)][string]$Ref,
    [string]$HostName = 'skope-team10.koreacentral.cloudapp.azure.com',
    [string]$User = 'azureuser',
    [string]$Key = (Join-Path $env:USERPROFILE '.ssh\skope_azure'),
    # Rehearsal: every step except the deploy itself (the running commit must already be -Ref). No restart.
    [switch]$SkipDeploy
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$target = "$User@$HostName"
$ssh = @('-i', $Key, '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=20')
$steps = [System.Collections.Generic.List[string]]::new()
$out = $null

function Remote([string]$command) {
    $result = & ssh @ssh $target $command 2>&1 | Out-String
    return @{ Text = $result; Code = $LASTEXITCODE }
}
function Step([string]$name, [scriptblock]$body) {
    Write-Host "== $name"
    try { $detail = & $body; $steps.Add("| $name | PASS | $detail |") }
    catch { $steps.Add("| $name | FAIL | $($_.Exception.Message -replace '\|','/') |"); throw }
}
function Save([string]$file, [string]$text) { [IO.File]::WriteAllText((Join-Path $out $file), $text, (New-Object System.Text.UTF8Encoding $false)) }

$repo = (Resolve-Path (Join-Path $here '..\..')).Path
git -C $repo fetch --quiet origin
$commit = (git -C $repo rev-parse --verify "$Ref^{commit}").Trim()
$short = $commit.Substring(0, 7)
$out = Join-Path $env:USERPROFILE ("skope-releases\release-$short-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $out | Out-Null
$archive = $null

try {
    Step '1. 빌드' {
        $log = & (Join-Path $here 'Build-Release.ps1') -Ref $commit *>&1 | Out-String
        Save 'build.log' $log
        $script:archive = ([regex]::Match($log, 'release:\s+(\S+)')).Groups[1].Value
        if (-not $script:archive -or -not (Test-Path $script:archive)) { throw 'release archive not produced' }
        "커밋 $commit, sha256 $((Get-FileHash $script:archive -Algorithm SHA256).Hash.ToLowerInvariant().Substring(0,16))"
    }
    Step '2. 서버 접속·도구 설치' {
        & scp @ssh (Join-Path $here 'snapshot.sh') (Join-Path $here 'verify-readonly.sh') "${target}:/tmp/" 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'ssh/scp failed (allowed IP?)' }
        $r = Remote "set -e; for f in snapshot.sh verify-readonly.sh; do sed -i 's/\r$//' /tmp/`$f; sudo install -m 755 /tmp/`$f /opt/skope/deploy/`$f; rm -f /tmp/`$f; done; cat /opt/skope/RELEASE"
        if ($r.Code -ne 0) { throw $r.Text }
        "배포 전 실행 커밋 $($r.Text.Trim())"
    }
    Step '3. 배포 전 데이터 기록' {
        $r = Remote 'sudo bash /opt/skope/deploy/snapshot.sh'
        if ($r.Code -ne 0) { throw $r.Text }
        Save 'snapshot-before.txt' $r.Text
        ($r.Text -split "`n" | Where-Object { $_ -like 'LIMIT*' }).Count.ToString() + '개 표 기준 ID 기록'
    }
    Step '4. 백업 (서버 + PC 사본)' {
        $log = & (Join-Path $here 'Pull-Backup.ps1') -HostName $HostName -User $User -Key $Key *>&1 | Out-String
        Save 'backup.log' $log
        if ($log -notmatch 'verified backup copied to (\S+)') { throw 'off-server backup not verified' }
        "PC 사본 $($Matches[1])"
    }
    Step '5. 배포' {
        if ($SkipDeploy) { return '건너뜀 (-SkipDeploy 리허설, 재시작 없음)' }
        & scp @ssh $archive "${target}:/tmp/" 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'upload failed' }
        $name = Split-Path $archive -Leaf
        $r = Remote "sudo bash /opt/skope/deploy/deploy-release.sh /tmp/$name; code=`$?; rm -f /tmp/$name; exit `$code"
        Save 'deploy.log' $r.Text
        if ($r.Code -ne 0 -or $r.Text -notmatch "running $commit") { throw 'deploy-release.sh failed (it restores the previous version on failure)' }
        "실행 커밋 $short"
    }
    Step '6. check.sh' {
        $r = Remote 'sudo bash /opt/skope/deploy/check.sh'
        Save 'check.txt' $r.Text
        $pass = ([regex]::Matches($r.Text, '(?m)^\[PASS\]')).Count; $fail = ([regex]::Matches($r.Text, '(?m)^\[FAIL\]')).Count
        if ($fail -gt 0) { throw "check.sh FAIL $fail" }
        "PASS $pass / FAIL 0"
    }
    Step '7. 조회 전용 점검 (AI 호출 없음)' {
        $r = Remote "sudo bash /opt/skope/deploy/verify-readonly.sh $commit"
        Save 'verify-readonly.txt' $r.Text
        $pass = ([regex]::Matches($r.Text, '(?m)^\[PASS\]')).Count
        if ($r.Code -ne 0) { throw "verify-readonly FAIL: $((($r.Text -split "`n") | Where-Object { $_ -like '[[]FAIL*' }) -join '; ')" }
        "PASS $pass / FAIL 0"
    }
    Step '8. 기존 데이터 보존 비교' {
        & scp @ssh (Join-Path $out 'snapshot-before.txt') "${target}:/tmp/snapshot-before.txt" 2>&1 | Out-Null
        $r = Remote 'sudo bash /opt/skope/deploy/snapshot.sh /tmp/snapshot-before.txt; code=$?; rm -f /tmp/snapshot-before.txt; exit $code'
        if ($r.Code -ne 0) { throw $r.Text }
        Save 'snapshot-after.txt' $r.Text
        $keep = { param($t) ($t -split "`n" | Where-Object { $_ -match '^(ROWS|LEDGER|PHOTOS|SETTING|ENVFILE) ' }) -join "`n" }
        $before = & $keep (Get-Content (Join-Path $out 'snapshot-before.txt') -Raw); $after = & $keep $r.Text
        if ($before -ne $after) {
            $diff = Compare-Object ($before -split "`n") ($after -split "`n") | ForEach-Object { "$($_.SideIndicator) $($_.InputObject)" }
            Save 'snapshot-diff.txt' ($diff -join "`n"); throw "changed: $($diff -join '; ')"
        }
        '기존 행 해시·사용량 장부·사진·설정 파일 모두 동일'
    }
}
catch { Write-Host "STOPPED: $($_.Exception.Message)" -ForegroundColor Red }
finally {
    $ok = -not ($steps | Where-Object { $_ -like '*| FAIL |*' }) -and $steps.Count -eq 8
    $report = @(
        "# 서버 반영 결과 ($short)", '',
        "- 대상: https://$HostName", "- 커밋: $commit", "- 시각: $(Get-Date -Format 'yyyy-MM-dd HH:mm')",
        "- 결과: $(if ($ok) { '전체 PASS' } else { '중단 (아래 FAIL 단계 확인)' })$(if ($SkipDeploy) { ' — 리허설(배포 단계 건너뜀)' })",
        '- AI 호출: 0회 (접수 없이 조회만 점검)', '',
        '| 단계 | 결과 | 내용 |', '|---|---|---|') + $steps + @('', "원본 출력: $out")
    Save 'REPORT.md' ($report -join "`n")
    Write-Host ($report -join "`n")
}
