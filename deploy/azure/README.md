# 제출용 서버: Azure for Students 가상 서버 1대

작성 2026-10-28, 담당 조성익. 첫 배포 기준 소스 `main` `c259e35`(통합 검증 `docs/verification-release-20261028.md`). **제품 기능·DB 마이그레이션은 바꾸지 않고**, 서버 설치·실행·백업 절차만 담는다.

## 한눈에 보기

```
인터넷 ──443/80──▶ Caddy (HTTPS 자동 발급·갱신, React 정적 파일)
                      │ /api/*
                      ▼
              Spring Boot 127.0.0.1:8080 (systemd, 오류 시 재시작)
                      │
                      ▼
              MySQL 8 127.0.0.1:3306      사진: /var/lib/skope/attachments
```

- 서버 1대(Ubuntu 24.04)에 Java 25(Temurin JRE)·MySQL 8·Caddy를 설치한다. 외부에서 열린 포트는 SSH(관리자 IP만)·80·443뿐이다.
- 주소는 Azure가 주는 DNS 이름(예: `skope-team10.koreacentral.cloudapp.azure.com`)이다. 고정 공인 IP에 붙이므로 재시작해도 같다. 도메인 구매가 필요 없다.
- 공용 Basic 암호 팝업은 쓰지 않는다. 접근은 SKOPE 로그인만으로 하고, 로그인·가입 요청 제한(`PUBLIC_AUTH_LIMIT_ENABLED=true`)과 보안 쿠키(`COOKIE_SECURE=true`)를 켠다.
- 첫 배포는 `AI_MODE=DEMO`, `EMBEDDING_MODE=RULE_ONLY`다. **실제 AI 호출 없음.** LIVE 전환·OpenAI 키·한도는 조장이 서버에서 설정한다.

| 파일 | 위치(서버) | 역할 |
|---|---|---|
| `install.sh` | 처음 1회 | 패키지 설치, DB·계정·비밀값 생성, Caddy·systemd·방화벽 설정 |
| `skope.env.example` | → `/etc/skope/skope.env` (root 전용) | 백엔드 환경변수. 비밀값은 서버에서 무작위 생성 |
| `skope.service` | → `/etc/systemd/system/` | 부팅 시 자동 실행, 오류 시 5초 후 재시작 |
| `Caddyfile.template` | → `/etc/caddy/Caddyfile` | HTTPS, `/api` 프록시, 화면 경로 |
| `deploy-release.sh` | `/opt/skope/deploy/` | 릴리스 설치. 백업 → 교체 → 상태 확인 → 실패 시 이전 버전 복구 |
| `backup.sh` · `skope-backup.timer` | `/opt/skope/deploy/` | DB 덤프 + 사진 묶음 + 해시, 매일 04:00, 14개 보관 |
| `restore.sh` | `/opt/skope/deploy/` | 백업 복원(`--yes` 필수, 복원 전 현재 상태도 백업) |
| `check.sh` | `/opt/skope/deploy/` | 읽기 전용 점검(서비스·포트·HTTPS·데이터 건수·백업 나이) |
| `Build-Release.ps1` | 내 PC | 지정 커밋으로 jar + React 빌드를 묶은 릴리스 파일 생성 |
| `Pull-Backup.ps1` | 내 PC | 서버에서 백업을 만들어 PC로 내려받고 해시 검증(서버 밖 백업) |

## 0. 구독 확인 (생성 전)

- 구독 오퍼가 "Azure for Students"이고 크레딧이 남아 있는지, **지출 제한(Spending limit)이 켜져 있는지** 확인한다.
- **유료 구독 전환·지출 제한 해제는 하지 않는다.** 크레딧을 다 쓰면 과금되지 않고 구독이 비활성화된다.
- 학생 구독은 서버를 만들 수 있는 지역이 정책으로 제한될 수 있다. 가상 머신 만들기 화면에서 Korea Central과 B2s(또는 B2ls_v2)를 고를 수 있는지 확인한다.

## 1. 예상 비용 (11/13까지, 공식 가격 API 2026-10-27 조회)

| 항목 | 규격 | 단가 | 400시간(약 16.7일) |
|---|---|---|---|
| 가상 서버 | B2s 2 vCPU / 4GB (같은 가격 B2ls_v2 가능) | $0.052/시간 | $20.80 |
| OS 디스크 | Standard SSD E4 32GB | $2.40/월 | $1.32 |
| 고정 공인 IP | Standard Static IPv4 | $0.005/시간 | $2.00 |
| 스냅샷 | 사용량 기준, 10GB 가정 | $0.05/GB·월 | 약 $0.30 |
| 외부 전송 | 월 100GB까지 무료 | - | $0 |
| **합계** | | | **약 $24.4** |

출처: Azure Retail Prices API(`prices.azure.com`), Korea Central·Linux·종량제. 실제 청구는 포털 비용 관리에서 확인한다. 지역이 바뀌면 다시 계산한다.

## 2. 서버 만들기 (포털, 비용 확인 후)

1. 리소스 그룹 `skope-rg`(지역 Korea Central).
2. 가상 머신: 이미지 **Ubuntu Server 24.04 LTS (x64 Gen2)**, 크기 **B2s**, 인증 **SSH 공개 키**(사용자 `azureuser`). 비밀번호 로그인은 쓰지 않는다.
   - 키는 각자 PC에서 만든다: `ssh-keygen -t ed25519 -f $env:USERPROFILE\.ssh\skope_azure` (개인 키는 절대 공유·커밋하지 않음)
3. 디스크: OS 디스크 **Standard SSD**, 32GB.
4. 네트워킹: 새 공인 IP **Standard, 정적(Static)**. 인바운드 규칙은 SSH(22)·HTTP(80)·HTTPS(443).
5. 만든 뒤 네트워크 보안 그룹(NSG)에서 **SSH 규칙의 원본을 관리자 공인 IP로 제한**한다. 80·443은 모든 곳.
6. 공인 IP 리소스 → 구성 → **DNS 이름 레이블**(예 `skope-team10`) → 주소 `skope-team10.koreacentral.cloudapp.azure.com`.
7. 자동 종료(Auto-shutdown)는 **끈다**(평가 기간 상시 실행).

## 3. 설치

```powershell
# 내 PC: 배포 스크립트 업로드
scp -i $env:USERPROFILE\.ssh\skope_azure -r deploy\azure azureuser@<호스트>:~/skope-deploy
```
```bash
# 서버
sudo bash ~/skope-deploy/install.sh <호스트>
```

- 비밀값은 서버에서만 생성된다: `/etc/skope/skope.env`(DB 비밀번호 등), `/etc/skope/demo-accounts.txt`(시연 계정 초기 비밀번호). 둘 다 root만 읽을 수 있고 GitHub·채팅에 올리지 않는다.
- Caddy가 처음 시작할 때 인증서를 발급한다. 80·443이 열려 있고 DNS 이름이 이 서버를 가리켜야 한다.

## 4. 릴리스 배포

```powershell
# 내 PC: 정확한 커밋으로 릴리스 만들기 → 업로드
.\deploy\azure\Build-Release.ps1 -Ref <커밋>
scp -i $env:USERPROFILE\.ssh\skope_azure $env:USERPROFILE\skope-releases\skope-release-<short>.tar.gz azureuser@<호스트>:/tmp/
```
```bash
# 서버
sudo bash /opt/skope/deploy/deploy-release.sh /tmp/skope-release-<short>.tar.gz
sudo bash /opt/skope/deploy/check.sh
```

- 릴리스 안의 `COMMIT`이 `/opt/skope/RELEASE`에 기록된다. 제출 커밋과 실행 버전이 같은지 이 파일로 확인한다.
- 새 버전이 90초 안에 응답하지 않으면 이전 jar·화면으로 자동으로 되돌린다. 단, 실패한 버전의 DB 마이그레이션이 이미 적용됐다면 배포 직전 백업으로 `restore.sh`를 실행한다.
- 자동 배포는 없다. 사람이 명령을 실행할 때만 바뀐다.

## 5. 조장 요청 6가지와 확인 방법

| 요청 | 구성 | 확인 방법 |
|---|---|---|
| 1. 생성 전 비용 | 1절 표 | 생성 전에 조장 확인 |
| 2. 같은 주소로 HTTPS | 고정 공인 IP + Azure DNS 이름 + Caddy 자동 인증서 | `check.sh`의 HTTPS 항목, 재부팅 후 같은 주소로 접속 |
| 3. 부팅 시 자동 실행·오류 시 재시작 | `skope`·`mysql`·`caddy` enable, `Restart=on-failure` | `check.sh`(starts on boot), `sudo systemctl kill -s KILL skope` 후 5초 뒤 자동 재시작 확인 |
| 4. 영속 저장·서버 밖 백업·복원 | DB: MySQL 데이터 디스크, 사진: `/var/lib/skope/attachments`, 매일 백업, `Pull-Backup.ps1`, `restore.sh`, 디스크 스냅샷 | 10/30 복원 연습: 백업 → 테스트 신고 추가 → 복원 → 추가분 사라지고 원래 건수 확인 |
| 5. 외부 직접 접근 제한·기존 보안 유지 | NSG + ufw(22·80·443만), MySQL·백엔드 127.0.0.1, 앱의 로그인·CSRF·권한 그대로 | `check.sh`(loopback, 비로그인 401), 외부에서 `:3306`·`:8080` 접속 실패 확인 |
| 6. 재부팅 후 유지 | 위 모두 디스크에 저장 | 시연 데이터(신고·사진) 생성 → `sudo reboot` → 같은 주소·신고·사진·`ai_usage_budget` 그대로인지 `check.sh`와 화면으로 확인 |

## 6. 백업과 복원

- 매일 04:00(KST) 자동 백업: `/var/backups/skope/<시각>/`(DB 덤프, 사진 묶음, 실행 커밋, 건수, 해시). 최근 14개 보관. AI 사용량 장부(`ai_usage_budget`)도 DB 덤프에 포함된다.
- 서버 밖 사본: `.\deploy\azure\Pull-Backup.ps1 -HostName <호스트>` → `%USERPROFILE%\skope-backups\`. 합성 데이터뿐이어도 Git·공유 드라이브에 올리지 않는다.
- 디스크 스냅샷: 포털 → 디스크 → 스냅샷 만들기(증분, Standard). 큰 변경 전(LIVE 전환, 제출 확정)에 한 번씩.
- 복원: `sudo bash /opt/skope/deploy/restore.sh /var/backups/skope/<시각> --yes`. 복원 직전 상태를 먼저 백업하고, 기존 사진 폴더는 `attachments.before-restore-<시각>`으로 남긴다.
- 새 서버로 옮길 때: `install.sh`로 새 서버 설치 → 백업 폴더 업로드 → `restore.sh`. 시연 계정 비밀번호는 백업의 DB 값을 따른다.

## 7. 접근 권한 전달

- Azure 포털: 팀원을 **개별 계정 초대**로 리소스 그룹에 역할 부여(조장: 기여자, 나머지: 읽기 권한자). 구독 소유권·로그인 정보는 공유하지 않는다.
- SSH: 각자 만든 **공개 키**만 받아 `~/.ssh/authorized_keys`에 추가하고, NSG SSH 원본에 그 사람 IP를 추가한다.
- 시연 계정 비밀번호(`/etc/skope/demo-accounts.txt`)는 필요한 사람에게 개별로 전달한다.

## 8. LIVE 전환 (조장)

1. 스냅샷과 백업을 만든다.
2. `sudo nano /etc/skope/skope.env`에서 `AI_MODE=LIVE`, `EMBEDDING_MODE=LIVE`, 키·모델·`AI_BUDGET_*`·`AI_PRICE_*`를 채운다(파일은 root 전용, 0600 유지).
3. `sudo systemctl restart skope` → `check.sh`의 `/api/meta`가 LIVE인지 확인.
4. OpenAI 프로젝트 사용 한도는 별도로 설정한다. Azure 크레딧과 OpenAI 비용은 별개다.
5. 사용량 확인·조기 한도 도달 대응은 `docs/routing-and-budget.md`를 따른다(실제 사용량 확인 없이 장부를 지우지 않는다).

## 9. 일정과 동결

| 날짜 | 할 일 |
|---|---|
| 10/29 | 서버 생성 → 설치 → 릴리스 배포 → `check.sh` |
| 10/30 | 팀 전체 PC·휴대폰 접속, 재부팅·재시작 확인, 백업·복원 연습, 스냅샷 |
| 10/31 | 제출 커밋 확정·배포, 제출 링크 확정 |
| 11/1 | 최종 확인. 이후 변경 없음 |
| 11/2~11/9 | **코드·서버 변경 없음.** 하루 한 번 `check.sh`만 실행하고 결과 기록 |
| 11/12 | 본선 시연 |
| 11/13 이후 | 최종 백업을 PC로 내려받은 뒤 리소스 그룹 삭제(크레딧 소모 중단) |

## 10. 첫 배포 확인 결과 (2026-10-28)

- 서버: `skope-rg`/`skope-vm`, Korea Central, **Standard_B2ls_v2**(2 vCPU/4GB, $0.05/시간, B2s는 이 지역 학생 구독에 없음), Ubuntu 24.04.5, Standard SSD 32GB, 고정 IP. 지출 제한 On, 허용 지역 정책에 koreacentral 포함.
- 주소: `https://skope-team10.koreacentral.cloudapp.azure.com` (Let's Encrypt 인증서, 2027-01-26까지 자동 갱신 대상).
- 배포 커밋: `c259e354af31f4080f264286dae4132131d71aeb`, Flyway V10, DEMO/RULE_ONLY, 시연용 배정 켬. 유료 AI 호출 0회.
- NSG: SSH(22)는 관리자 IP 1개만, 80·443 공개. 그 외 포트 규칙 없음. 서버 안 ufw도 22·80·443만.

| 확인 | 결과 |
|---|---|
| `check.sh` | 전 항목 PASS(첫 백업 전 "no backup yet"만 예상대로 FAIL → 백업 후 해소) |
| 외부에서 3306·8080 접속 | 실패(차단됨), 443 성공, HTTP→HTTPS 이동, HSTS |
| `smoke.sh`(공개 HTTPS로 실제 요청) | 16/16 PASS: 학생 로그인, 세션 쿠키 Secure·HttpOnly, 자연어 접수 ACCEPTED(정수기→시연용 배정), 사진 업로드·조회, 관리자 처리 중→완료, 관리자 사진 조회, 학생 결과 조회, 다른 학생 404, 학생의 관리자 API 403, CSRF 없는 로그인 403 |
| 강제 종료(`systemctl kill -s KILL skope`) | 약 14초 뒤 자동 재시작·응답 |
| 재부팅 | 같은 주소·인증서, 서비스 자동 시작, 신고 1·사진 1 유지 |
| 서버 밖 백업 | `Pull-Backup.ps1`로 PC에 내려받고 해시 검증 |
| 복원 | 점검 전 백업으로 `restore.sh` → 신고·사진 0건으로 돌아감(점검 데이터 제거), 서비스 정상 |

남은 것: LIVE 전환(조장), 팀원 PC·휴대폰 화면 확인, 로그인 요청 제한(429) 실제 발동은 기존 테스트로만 확인, 제출 전 디스크 스냅샷.
