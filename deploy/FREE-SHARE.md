# 비용 없는 팀 테스트용 공유

기준 소스: `8cbf3f1079ccb4957dfeba4668ee0796fcfc6a69` (PR #4 병합). 학생/관리자 기능과 DB 마이그레이션은 변경하지 않는다.

## 어떤 방식인가요?

현재 Windows PC를 서버로 사용한다. Cloudflare Quick Tunnel이 임시 HTTPS 주소를 제공하고, 로컬 Caddy가 React 배포 파일과 Spring API를 같은 주소로 제공한다. MySQL과 사진은 이 작업 폴더의 `.runtime`에 저장된다.

- 서버 임대료·도메인 구매·AWS/Cloudflare 계정 불필요. PC 전기·인터넷은 기존 환경을 사용한다.
- 기본 실행은 **AI DEMO / Embedding RULE_ONLY**로 유료 AI 호출이 없다. **LIVE OpenAI는 별도 사용료가 발생**하며 무료 AI로 대체한 것이 아니다.
- PC가 켜져 있고 MySQL·공유 프로그램이 실행 중이어야 한다. 주소는 다시 시작할 때 바뀐다. 상시 운영 서버가 아니라 팀 테스트/발표 시연용이다.
- 허용한 이메일의 일회용 PIN 인증 후 SKOPE에 로그인한다. 이메일 인증과 학생/관리자 권한은 별개다.
- 서비스는 학교의 공식 접수 시스템과 연결되지 않았다. 합성 데이터로 테스트한다.

근거: [Cloudflare Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/), [무료 이메일 보호 안내](https://blog.cloudflare.com/protected-quick-tunnels/). 공식 제한은 동시 진행 요청 200개, SSE 미지원, 가용성 보장 없음이다. 현재 SKOPE는 이 경로에서 SSE를 사용하지 않는다.

## 현재 PC에서 실행

현재 별도 작업 폴더에는 도구, 빌드, MySQL 초기 준비를 완료했다. 기존 루트 프로젝트의 DB와 계정은 사용하지 않는다.

PowerShell에서 아래 이메일을 실제 본인/팀원 이메일로 바꾼다.

```powershell
cd C:\Users\82107\SKOPE-Experiment\exports\campus-ai-10-deploy
.\scripts\Initialize-Local.ps1 -Port 3310
.\scripts\Start-FreeShare.ps1 -AllowedEmail '본인이메일','팀원이메일'
```

표시된 `https://....trycloudflare.com` 주소를 휴대폰 또는 PC에서 연다. 본인 이메일로 인증번호를 받아 인증한 뒤 SKOPE에 로그인한다. 인증 메일은 방문자가 인증을 요청할 때 전송된다. 로컬 HTTP 주소로 접속하는 방식은 Secure 쿠키 검증에 사용할 수 없다.

계정/비밀번호는 **이 작업 폴더의 `.runtime/demo-accounts.txt`**에 있다. 기존 루트 폴더의 비밀번호와 다르다. 부서 계정은 `dept1`~`dept4`이며 초기 비밀번호는 같은 파일의 시연 비밀번호다. 시연 계정들이 비밀번호를 공유하므로 신뢰하는 팀원에게만 접근을 허용한다. 이 구조로 전체 학생에게 공개하지 않는다.

종료는 공유 PowerShell 창에서 **Ctrl+C**, 또는 별도 PowerShell에서 `./scripts/Stop-FreeShare.ps1`을 실행한다. 후자는 시작 시각·실행 파일·프로젝트 경로를 확인하여 기록된 공유 프로세스만 종료한다. MySQL과 데이터는 보존한다. 강제 창 종료/프로세스 강제 종료 때의 정리는 보장하지 않는다. 다음 실행에서 포트 점유가 발견되면 기존 프로세스를 임의 종료하지 않고 중단한다.

백그라운드에서 실행한 경우 현재 주소는 `.runtime/free-share-url.txt`, 도구 로그는 `.runtime/free-share-*.log`에서 확인한다. 주소가 기록되었다고 실제 터널 연결이 완료된 것은 아니다. 연결 성공과 이메일 인증 화면을 별도로 확인한다.

## 다른 PC에서 처음 준비

Windows PowerShell 7+, Java 25, Node.js 24, MySQL 8 실행 파일이 필요하다. 기존 프로젝트의 동일 실행 환경을 활용한다. MySQL 기본 위치는 `C:\Program Files\MySQL\MySQL Server 8.0\bin`이다.

저장소 루트에서:

```powershell
.\scripts\Install-FreeShareTools.ps1
.\scripts\Initialize-Local.ps1 -Port 3310
$env:GRADLE_USER_HOME = "$PWD\.gradle"
.\backend\gradlew.bat -p backend bootJar
Push-Location frontend
npm.cmd ci --no-audit --no-fund
npm.cmd run build
Pop-Location
.\scripts\Start-FreeShare.ps1 -AllowedEmail '실제이메일'
```

도구는 공식 릴리스에서 받으며 Caddy SHA512 / cloudflared SHA256을 확인한다. 시스템 설치나 자동 업데이트는 하지 않는다. `cloudflared --allowed-mail` 지원 버전을 고정했다. npm 인증서 오류가 Windows에 설치된 보안 프로그램 인증서 때문이면 신뢰 저장소를 확인하고 `NODE_USE_SYSTEM_CA=1`을 사용할 수 있다. TLS 검증을 끄지 않는다.

## LIVE를 쓰려면

무료 공유와 유료 AI는 별개다. 실제 AI가 필요할 때만 기존 키 설정 파일을 명시적으로 지정한다. 키를 프론트엔드·GitHub·이 문서에 넣지 않는다.

```powershell
.\scripts\Start-FreeShare.ps1 -AllowedEmail '실제이메일' -Live -AiEnvPath 'C:\Users\82107\SKOPE-Experiment\.runtime\ai-verification.env'
```

기존 AI 모델/라우팅/저장 API를 그대로 사용한다. 이 실행 스크립트에는 API 지출 강제 상한이 없으므로 팀 테스트의 호출 수·예산을 정하고 실행한다. 자동 유료 재시도는 추가하지 않았다. 이번 준비 작업에서는 LIVE API를 호출하지 않았다.

## 데이터와 변경 범위

- MySQL: 별도 작업 폴더의 `.runtime/mysql`, 포트 3310.
- 사진: `.runtime/shared-attachments`.
- 웹/백엔드: loopback 포트 4186/8086. DB/백엔드 포트를 인터넷에 직접 열지 않는다.
- 쿠키: `SKOPE_SHARE_SESSION`, Secure/HttpOnly/SameSite=Lax. CSRF와 기존 역할/소유권 검사를 유지한다.
- API/사진 캐시 금지, 프록시 요청 한도 6 MiB, 서버 사진 한도 5 MiB.
- 새 스크립트/프록시 설정만 추가했다. PWA, Docker, 유료 서버, 도메인, 기존 기능 변경은 포함하지 않는다.
- 데이터 보존은 백업과 다르다. PC 디스크 고장에 대비한 백업/복원은 추후 별도 작업이다.

## 검증 상태

2026-10-26: 프론트 배포 빌드와 백엔드 bootJar 빌드 PASS. 도구 해시·Caddy 설정·PowerShell 구문 PASS.
로컬 MySQL/DEMO 프록시 검증 **14 PASS**:

1. 실제 React 배포 파일과 상세 경로 접근.
2. 없는 정적 파일/`.env` 접근 404.
3. 실제 MySQL 연결, DEMO/RULE_ONLY 모드, API `no-store`.
4. 미로그인 신고 접근 401.
5. CSRF 없는 로그인 403.
6. 세션 쿠키 Secure/HttpOnly/SameSite 속성(HTTP 클라이언트 확인).
7. 로그인 성공 및 학생의 관리자 API 접근 차단.
8. 수동 신고를 프록시를 통해 MySQL에 저장.
9. 5 MiB 합성 PNG를 실제 사진 서비스에 업로드.
10. 저장된 사진 조회 및 `no-store`.
11. 6 MiB 초과 multipart 업로드 413.
12. 백엔드 재시작 후 신고·사진 유지 및 재로그인.
13. 합성 첨부 삭제.
14. 로그아웃 후 세션 무효화.

유료 AI 호출 0회. 증거는 `.runtime/free-share-test-results.json`, 재현 스크립트는 `.runtime/test-free-share.mjs`에 저장했다. 별도 DB에 합성 검증 신고가 남아 있으며 기존 개발 DB와 섞이지 않는다. 프록시의 중복 Cache-Control 헤더를 검증 중 정리했다. 기존 학생/관리자 기능 코드는 수정하지 않았다.

외부 터널 등록(서울 icn05, QUIC)과 공개 HTTPS 주소의 이메일 인증 화면 접근은 PASS. 미인증 요청에 SKOPE 화면이 노출되지 않는 것을 확인했다. Windows 로그 파일을 동시에 읽을 때의 공유 모드 오류를 실행 과정에서 수정했고 정상 시작을 확인했다.

이메일 PIN 입력 완료, 인증 후 실제 브라우저에서의 Secure 쿠키 전송, 실제 휴대폰, LIVE AI는 아직 미검증이다. 인증번호 발송은 대신 요청하지 않았다. 로컬 HTTP 검증으로 이를 대체하지 않는다. 네트워크 사전 검사에서 TCP/HTTP2 연결은 실패했고 QUIC 연결로 동작했으므로, 사용 네트워크가 QUIC까지 차단하면 공유할 수 없다.
