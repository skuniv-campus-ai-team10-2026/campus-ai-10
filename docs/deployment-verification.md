# 로컬 프로덕션 번들·프록시·부하 검증

검증일: 2026-10-11 KST. **HTTP 프록시 기능·보안 12항목 및 부하 요청 400건 통과. HTTPS 연결은 Avast 인증서 교체로 차단.** 외부 배포·학교 시스템 연결·시스템 인증서/백신 설정 변경은 하지 않았다.

## 구성과 재현

`Preview-Production.mjs`는 127.0.0.1에만 바인딩하며 `frontend/dist`를 제공한다. `/api`는 loopback Spring 서버로 전달하고 알려진 앱 경로에만 SPA fallback을 적용한다. 원격 주소·임의 정적 루트는 허용하지 않는다. 요청 본문 256 KiB, upstream timeout 60초, CSP·nosniff·frame 차단·Host/Origin 검사·경로 경계 검사를 적용했다.

검증 DB는 **skope_verification**, MySQL은 기존 작업 폴더의 별도 3307 인스턴스다. `Start-VerificationBackend.ps1`은 실제 MySQL data directory를 확인한 뒤 이 전용 DB를 준비한다. 일반 시연 `skope`와 회귀 `skope_test`를 성능 데이터로 채우지 않는다. AI는 DEMO/RULE_ONLY다.

```powershell
# 루트에서 준비. 먼저 기존 8080 서버를 종료한다.
.\scripts\Initialize-Local.ps1
.\scripts\Start-VerificationBackend.ps1
# 별도 터미널
node scripts/Preview-Production.mjs --port 4173
# 또 다른 터미널
$env:SKOPE_VERIFICATION_WRITES='1'
$env:VERIFY_ORIGIN='http://127.0.0.1:4173'
node scripts/Test-Production.mjs
```

이 검증 서버는 쿠키 속성 점검을 위해 `COOKIE_SECURE=true`다. HTTP 클라이언트는 쿠키를 직접 전달한다. 이를 일반 브라우저의 HTTP 로그인 설정이나 HTTPS 쿠키 보호 증거로 해석하지 않는다. 일반 시연에는 README의 `Start-Backend.ps1`을 사용한다.

## 실제 HTTP 결과

[production-http-results.json](evaluation/production-http-results.json): **PASS_HTTP_ONLY**, 12 PASS / TLS 1 SKIPPED.

- dist HTML·JS·CSS MIME, 깊은 경로·HEAD, 잘못된 정적 메서드 405, 없는 asset 404 확인.
- 정적/API 보안 헤더와 no-store, 경로 탈출·dotfile·중첩 인코딩·backslash 차단 확인.
- Host/외부 Origin, 본문 초과 413, 비로그인 401, CSRF 누락 403 확인.
- 로그인 세션 ID 변경과 Set-Cookie의 Secure·HttpOnly·SameSite=Lax 확인.
- SQL injection 형태 로그인 실패, 학생 소유권·관리자 권한, 클라이언트의 신원/상태/부서 강제 지정 무시 확인.
- HTML 형태 원문을 JSON으로 저장/조회. React 실행형 XSS의 전수 브라우저 검증은 아님.
- 같은 UUID 동시 8요청은 201 1개 / 409 7개. 관리자 동시 처리 시작은 200 1개 / 409 1개. 완료 뒤 상태 전이 이력 2개와 사건 종료 확인.
- 로그아웃 뒤 인증 정보 조회 401 확인.

| 동시 세션 | 생성+조회 요청 | 실패 | 읽기 p95 | 쓰기 p95 | 처리량 |
|---:|---:|---:|---:|---:|---:|
| 4 | 40 | 0 | 24.52 ms | 57.57 ms | 126.76 req/s |
| 12 | 120 | 0 | 99.50 ms | 184.73 ms | 142.81 req/s |
| 24 | 240 | 0 | 196.59 ms | 332.11 ms | 146.18 req/s |

학생 계정은 **2개**, 세션마다 5회 생성+본인 조회를 수행한 짧은 로컬 closed-loop 실험이다. 로그인은 처리량 분모에 포함하지 않는다. 장소를 분리한 합성 데이터로 후보 조합 증가를 피했으므로 같은 장소 대량 신고나 유사 후보 검색 성능을 측정한 결과가 아니다. LIVE AI·인터넷·모바일 지연·장시간 내구성·24명의 독립 사용자를 검증하지 않았다. 운영 수용량/SLA 보증이 아니다.

직접 MySQL 확인: `skope_verification.reports` 203건(부하 200 + 보안 3), 중복 UUID 그룹 0, 기존 `skope.reports` 1건 보존.

백엔드 종료 후 [proxy-edge-results.json](evaluation/proxy-edge-results.json)에서 **3/3 PASS**: 수정 후 새 JS 번들/HEAD 제공, upstream 부재 시 502 JSON, dist 밖 합성 파일을 가리키는 Windows junction 403. 검증용 junction은 정확한 대상을 확인하고 제거했다. 60초 timeout·모든 chunked 경계·모든 브라우저 캐시는 별도 전수 검증하지 않았다.

## HTTPS 실행과 환경 장애

작업 폴더 안에서 3일짜리 CA와 2일짜리 localhost 서버 인증서를 생성했다. CA 개인키는 저장하지 않고 서버 개인키만 `.runtime/tls`에 보관했다. Windows/browser 신뢰 저장소에는 설치하지 않았다.

```powershell
.\scripts\New-LocalTlsCertificate.ps1
node scripts/Preview-Production.mjs --port 4174 --cert .runtime/tls/localhost-cert.pem --key .runtime/tls/localhost-key.pem
# TLS 경로로 검사하려면 VERIFY_ORIGIN을 아래로 지정
$env:VERIFY_ORIGIN='https://127.0.0.1:4174'
node scripts/Test-Production.mjs
```

- 로컬 파일은 `openssl verify -purpose sslserver -CAfile .runtime/tls/ca-cert.pem .runtime/tls/localhost-cert.pem`에서 **OK**.
- 실제 TLS 연결은 Node에서 `UNABLE_TO_VERIFY_LEAF_SIGNATURE`, curl에서 certificate chain incomplete, OpenSSL의 `-verify_return_error`에서도 검증 실패.
- 서버에 설정한 발급자는 `CN=SKOPE workspace test CA`였으나 실제 연결의 인증서는 `Avast Web/Mail Shield Untrusted Root`가 발급한 것으로 교체되어 있었다. 공통 이름만 같고 SHA-256 지문은 다르다.
- 인증서 검증을 끄거나 백신·시스템 신뢰 설정을 바꾸지 않았다. 따라서 **신뢰된 HTTPS 연결·브라우저 Secure-cookie 전송은 미검증**이다. HTTP 성공으로 대체 완료 처리하지 않았다.

실패 실행 원본은 [production-results.json](evaluation/production-results.json), 공개 인증서 진단 요약은 [tls-diagnostic.json](evaluation/tls-diagnostic.json)이다. 서버/개인키/로컬 로그는 공개 문서에 넣지 않았다. 신뢰 가능한 TLS 배포 환경에서 다시 실행해야 하며 정식 도메인·공인 인증서 갱신·HSTS·외부 로드밸런서는 아직 검증하지 않았다.

## 종료와 운영 경계

이번 서버와 격리 MySQL은 검증 후 종료하며 데이터와 빌드 결과는 보존한다. TLS 인증서는 단기 테스트용이라 재실행일에 만료됐으면 재생성 후 프록시를 다시 시작한다. 이 프록시는 운영 배포 승인이 아니다. [접근·보관 검토](privacy-security-review.md)와 [현재 API 계약](api-contract.md)의 팀 결정 항목이 남아 있다.
