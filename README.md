# SKOPE — 캠퍼스 문제 접수 실험

자연어 입력 → 필요한 경우에만 추가 질문 → 자체 접수·DB 규칙 배정 → 관리자 답변·조치 → 학생 결과 조회를 연결하는 로컬 웹 애플리케이션입니다. 일반 질문, 추가 질문, 유사 후보 검토, 동일 사건 묶음, 완료 후 재발 검토까지 P0를 구현합니다.

**시연용 시스템이며 학교 공식 민원 접수가 아닙니다.** 실제 학교·Notion·공식 저장소·학교 계정을 변경하거나 민원을 전송하지 않습니다. RAG, 벡터 DB, Fine-tuning, 알림·통계는 포함하지 않습니다. 사진 첨부 백엔드 API와 웹 화면이 연결되어 있습니다. 작성 화면에서 JPEG/PNG 사진을 장당 5MB 이하, 최대 3장 선택할 수 있으며 신고 접수 후 한 장씩 업로드합니다. 학생은 접수 완료·상세 화면에서 사진을 추가·삭제하고, 관리자는 상세 화면에서 조회합니다. 업로드에 실패한 사진은 재시도할 수 있습니다. 접수 전 선택 파일은 메모리에만 보관되므로 새로고침하면 다시 선택해야 합니다.

## 협업용 소스와 현재 상태

**2026-10-23 통합:** 부서별 관리자·이관 기능, 최신 AI 프롬프트 `hybrid-intake-v2` / 정책 `hybrid-check-v3`, 공식 장소 예시와 기존 평가 기록, 사진 첨부 PR #1의 V5·V6를 한 소스에 통합했습니다. 기존 마이그레이션은 변경하지 않습니다. 사진은 JPEG/PNG·장당 5MB·신고당 3장까지이며, 실패한 파일 정리는 V6 장부로 추적합니다. [사진 API·운영 제한](docs/report-attachments.md), [프런트 인수인계와 통합 검증](docs/TEAM_HANDOFF.md)을 먼저 확인하세요. 아래의 과거 LIVE 결과는 이번 통합 버전의 새 LIVE 검증 결과가 아닙니다.

**2026-10-20 장소 예시 정정:** 학교 공식 자료에서 확인한 장소로 향후 평가·화면 예시를 교체했습니다. 과거 LIVE 기록의 가상 장소는 원본 증거로 보존하며, 현재 임시 화면의 신고 4건은 수동 DEMO입니다. 관련 로컬 166 PASS, 저장·조회 4/4 PASS, 신규 API 호출 0회. [확인한 장소·정정 범위](docs/evaluation/campus-locations-review.md).

처음 받은 작업자는 [팀원 실행·연동 검증 안내](docs/TEAM_HANDOFF.md)를 먼저 읽으세요. GitHub 첫 업로드는 [업로드 방법](docs/GITHUB_UPLOAD.md)을 따릅니다. API 키·비밀번호·로컬 DB·AI 원시 캐시는 배포 소스에 포함되지 않으며 각자 환경을 준비해야 합니다.

최신 프롬프트는 **hybrid-intake-v2**, 서버 정책은 **hybrid-check-v3**입니다. 표현 변형 오류 수정 후 관련 로컬 175개·빌드 PASS, 새 LIVE 개발 사례 2개·새 합성 사례 4개 PASS, 실제 응답 캐시를 사용한 최종 HTTP/H2 접수·조회 6개 PASS입니다. 수정 후 브라우저 클릭·MySQL·Embedding E2E는 다시 검사하지 않았습니다. 소규모 합성 평가와 재생 결과는 전체 서비스 정확도가 아닙니다. [표현 변형 수정 결과](docs/evaluation/language-variation-v2-review.md), [전체 검증 기록](docs/verification.md)을 확인하세요. 아래 과거 품질·브라우저 측정은 실행 당시 버전의 기록입니다.

**2026-10-18 추가:** 등록된 4개 부서의 담당자 권한, 총괄 이관·재배정, 명시적 시연용 배정을 구현했습니다. 관련 백엔드 101개·프런트 52개와 빌드 PASS, 별도 MySQL의 브라우저 DEMO 흐름 PASS입니다. 새 LIVE 호출은 0회입니다. `-DemoRouting` 실행 옵션과 부서 계정은 [인수인계 안내](docs/TEAM_HANDOFF.md)를 확인하세요.

## 구성

- `backend/`: Java 25, Spring Boot 4.1.1, Gradle 9.7.1 Groovy, Spring Security 세션·CSRF, JDBC, Flyway.
- `frontend/`: React 19, TypeScript, Vite 7, 학생/관리자 화면. 최종 버전은 `package-lock.json`.
- `backend/src/main/resources/db/migration/`: V1 핵심 테이블, V2 사건 테이블·연결 확장, V3 문의/접수 맥락, V4 부서별 권한·시연 배정, V5 사진 첨부, V6 파일 정리 장부.
- `scripts/`: Windows 로컬 DB·계정 준비, 실행·테스트·종료.
- `docs/`: 데이터 출처, AI 평가, 테스트·구현 결과.
- `SKOPE_CODEX_IMPLEMENTATION_SPEC.md`: 사용자가 제공한 원본 구현 기준. Downloads 원본은 수정하지 않았습니다.

## 빠른 실행 — Windows PowerShell / MySQL

확인 환경: Temurin JDK **25.0.4.1**, Node **24.20.0**, npm **11.19.0**, MySQL Community **8.0.45**. JDK와 Node가 PATH에 있어야 합니다. 아래 명령은 프로젝트 루트에서 실행합니다. PowerShell 7 기준입니다.

```powershell
# MySQL 설치 경로가 다르면 -MySqlBin '...\bin' 지정
.\scripts\Initialize-Local.ps1

# 첫 번째 터미널: http://127.0.0.1:8080
.\scripts\Start-Backend.ps1

# 두 번째 터미널: http://127.0.0.1:5173
cd frontend
npm.cmd ci --cache .npm-cache
npm.cmd run dev -- --host 127.0.0.1
```

브라우저에서 `http://127.0.0.1:5173`을 엽니다. 계정은 **student1**, **student2**, **admin**입니다. 비밀번호는 최초 준비 시 암호학적 난수로 생성하며 **`.runtime/demo-accounts.txt`**에서 확인합니다. 공개 코드나 SQL에 비밀번호를 넣지 않습니다. 기존 계정 비밀번호는 재시작으로 초기화하지 않습니다.

`Initialize-Local.ps1`은 기존 시스템 MySQL 서비스와 별개로 기본 **127.0.0.1:3307**, **이 폴더의 `.runtime/mysql`**에서 실행합니다. 포트가 사용 중이면 `-Port 3317`처럼 다른 포트로 최초 준비할 수 있습니다. 성공한 포트는 `.runtime/local-config.json`에 저장되며 백엔드 시작과 MySQL 테스트가 함께 사용합니다. 설정 파일이 없는 기존 설치는 3307을 사용합니다. 연결된 서버의 실제 data directory를 확인한 뒤 `skope`와 `skope_test` DB만 준비합니다. MySQL 바이너리 설치 자체는 변경하지 않습니다. MySQL 종료는 `.\scripts\Stop-MySQL.ps1`, 앱 서버는 실행 터미널에서 Ctrl+C입니다. 데이터는 재시작 후 유지됩니다.

### MySQL 없이 실행하는 H2 대체 경로

```powershell
.\scripts\Initialize-Demo.ps1
.\scripts\Start-Backend.ps1 -H2
# 프런트 실행은 위와 동일
```

H2 파일 DB는 `backend/data/`에 저장하고 화면에 `H2_DEMO`라고 표시합니다. H2 실행 성공은 MySQL 검증을 뜻하지 않습니다. MySQL/H2 간 데이터는 자동 이전되지 않습니다.

### 이 PC의 인증서 환경

이 PC에서 백신의 HTTPS 검사 인증서 때문에 Java/npm의 다운로드가 처음 실패했습니다. 인증서 검증을 끄지 않고 OS의 신뢰 저장소를 사용해 해결했습니다. 같은 문제가 생긴 터미널에만 아래를 적용하세요.

```powershell
$env:JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
$env:NODE_OPTIONS='--use-system-ca'
```

인증서 검증을 비활성화하거나 OS 인증서를 변경하지 않습니다. 이 설정은 Windows 전용이며 일반 환경에는 필요하지 않습니다. Gradle 캐시는 실행 스크립트가 루트 `.gradle/`로 제한합니다.

## 실제 AI 연결

기본은 **AI_MODE=DEMO**, **EMBEDDING_MODE=RULE_ONLY**입니다. DEMO는 입력에 따른 키워드·정규식 처리이며 실제 생성형 모델이 아닙니다. RULE_ONLY는 가짜 임베딩을 만들지 않고 후보의 `similarityScore=null`을 반환합니다.

실 공급자 경로는 OpenAI 호환 Chat Completions JSON Schema 및 Embeddings HTTP API로 구현했습니다. 최초 실제 분석 30회·임베딩 40회 이후 품질 개선을 위해 분석 30회·고유 임베딩 21회를 재측정했습니다. 같은 개발셋의 문장 전체 필드 일치는 **26/30 → 실제 응답 29/30 → 저장 응답의 층 정규화 후 30/30**, 유사 후보는 저장된 실제 벡터로 **2/9 → 9/9 탐지, 오탐 0/11**입니다. 마지막 수치는 추가 추론 없는 재생이며 독립 운영 정확도가 아닙니다. [품질 비교](docs/ai-quality-comparison.md), [원래 실제 평가](docs/live-ai-verification.md). 앱 기본값은 DEMO이며 키·모델을 설정한 실행 터미널에서 서버를 다시 시작해야 LIVE가 적용됩니다.

```powershell
$env:AI_MODE='LIVE'
$env:AI_API_KEY='<본인의 API 키>'
$env:AI_MODEL='<접근 가능한 structured-output 지원 모델>'
$env:EMBEDDING_MODE='LIVE'
$env:EMBEDDING_MODEL='<접근 가능한 embedding 모델>'
.\scripts\Start-Backend.ps1
```

환경 변수 목록은 `.env.example`에 있습니다. 앱은 `.env` 파일을 자동 로드하지 않으며 환경 변수로 전달해야 합니다. 이번 실제 측정 모델은 `gpt-6-luna` + `text-embedding-3-small`이며 앱 기본값은 DEMO입니다. LIVE에서 설정 누락·스키마 오류·시간 초과는 `503 AI_UNAVAILABLE`이며 조용히 DEMO로 바꾸지 않습니다. 화면에서 재시도 또는 수동 신고가 가능합니다. LIVE 모드는 학생이 입력한 내용을 설정한 AI 공급자로 전송합니다.

키 발급·로컬 저장·별도 API 결제·호출 한도는 [AI 설정 안내](docs/ai-model-selection.md)에 있습니다. 원래 검증 PC의 `.runtime/ai-verification.env`를 아래 평가 실행기가 읽습니다. 이 파일은 저장소에 포함되지 않으므로 새 작업자는 먼저 팀원 실행 안내를 따르세요. 키를 채팅에 붙이지 마세요. 생성 출력 상한은 `AI_MAX_COMPLETION_TOKENS=1536`, 첫 평가의 `AI_REASONING_EFFORT=none`입니다.

```powershell
.\scripts\Test-AiHoldout.ps1 -Mode DEMO
.\scripts\Test-AiHoldout.ps1 -Mode LIVE
```

위 명령은 초기 holdout 평가 실행기입니다. DEMO에는 외부 호출이 없으며 LIVE 재실행은 키가 있을 때 최대 70회 유료 API 호출이 가능합니다. 기존 결과 확인에는 다시 LIVE를 실행할 필요가 없습니다. 키가 없으면 명시적으로 SKIPPED를 기록합니다. Gradle 성공과 품질 평가 통과는 별개입니다. [고정셋 결과](docs/holdout-verification.md)에서 DEMO 한계를 확인하세요.

최종 품질 수정의 재현은 아래 캐시 전용 명령을 사용합니다. 환경 키 파일을 읽지 않고 기존 LIVE 응답·벡터만 사용하며, 추가 API 호출은 0회입니다. 로컬 `.runtime/ai-quality-cache/v2`가 필요합니다. 캐시가 없으면 실패하며 자동 유료 대체 호출을 하지 않습니다. 별도 품질 LIVE 실행기는 누적 51회 한도를 이미 사용했습니다.

```powershell
.\scripts\Test-AiQualityReplay.ps1
# 같은 경로: .\scripts\Test-AiQuality.ps1 -Mode OFFLINE
```

실제 브라우저의 학생 분석·신고 → MySQL 저장 → 관리자 처리 → 학생 결과 조회·새로고침도 완료했습니다. 품질 평가 51회와 별도로 분석·임베딩 각 1회만 사용했습니다. 10월 13일 마무리 검증은 추가 API 0회입니다. [LIVE 브라우저 증거와 한계](docs/live-browser-verification.md)

Embedding 호출은 신고 저장 커밋 이후 실행하며 실패해도 접수는 유지됩니다. 같은 모델·차원의 유효한 벡터만 비교하며 재색인 시 유효한 기존 벡터를 재사용합니다. 현재 호출은 요청 후처리 방식이라 공급자가 느리면 응답에 최대 설정 timeout이 추가될 수 있습니다. 실패한 임베딩은 규칙 후보로 남으며 자동 재시도 작업은 없습니다. 후보 기간 30일·기본 임계값 0.82를 유지하고, 양쪽이 명시적인 WIFI 연결 장애이며 기존 장소·유형·시간 조건을 통과한 경우만 0.70 보조 기준을 적용합니다. 안내 스티커·비밀번호 문제 등은 보조 경로에서 제외합니다. 이는 같은 개발셋의 보정 결과이며 독립 검증은 남았습니다. 자동 사건 병합은 하지 않습니다.

## 첫 시연 흐름

1. student1 로그인 → “북악관 502호에서 와이파이가 계속 끊겨요.” 입력 → 문의·신고 보내기 → 충분하면 바로 접수번호 확인. 날짜·시간 입력이나 상세 확인 화면은 필수가 아닙니다.
2. 로그아웃 → admin 로그인 → 신고 관리 → 해당 접수번호 → 처리 시작 → 결과 입력 → 처리 완료.
3. student1로 다시 로그인 → 내 신고 → 같은 접수번호·처리 결과 확인 및 새로고침.
4. 같은 호실의 다른 표현 3건 접수 → 관리자 유사 후보에서 동일 사건 승인 → 신고 3건/사건 1건 확인.
5. 모든 신고 완료 후 같은 장소에 신규 신고 → 새 사건 처리 시작 → 이전 종료 사건을 참조한 재발 후보 검토. 발생일이 없으면 접수일 기반 불확실성을 유지합니다.

일반 질문도 관리자 답변 대상으로 접수합니다. “강의실이 너무 불편해요”는 실제 문제 내용을 먼저 묻고, “학교 인터넷이 안 돼요”는 장소를 묻습니다. 추가 설명이 어렵거나 AI 분석이 실패하면 관리자 검토로 접수할 수 있습니다. 기존 상세 입력 양식은 수동 수정 경로에 남아 있습니다. 문의는 유사 신고·사건 묶음 대상에서 제외합니다.

## 테스트와 빌드

```powershell
# H2 대체 DB + 단위/HTTP 테스트
.\scripts\Test-Backend.ps1
# 실제 MySQL 8의 별도 skope_test DB에서 HTTP 통합 테스트
.\scripts\Test-Backend.ps1 -MySQL

cd frontend
npm.cmd test
npm.cmd run build
```

통합 테스트는 테스트 DB의 신고·이력·사건을 지우고 합성 계정 비밀번호를 갱신합니다. **TEST_DB_URL에 운영 DB나 skope DB를 지정하지 마세요.** MySQL 플래그는 `skope_test`만 지정합니다. 서버 테스트 보고서는 `backend/build/reports/tests/test/index.html`, AI 합성 평가 결과는 `backend/build/reports/ai-evaluation/`에 생성됩니다.

백엔드 JAR은 `backend/build/libs/skope-0.1.0.jar`, 프런트 빌드 출력은 `frontend/dist/`입니다. 개발 시 Vite `/api` 프록시로 같은 출처의 쿠키·CSRF를 사용합니다. 정적 배포에는 동일 출처 reverse proxy와 SPA fallback이 별도로 필요하며, 외부 배포는 수행하지 않았습니다.

## 구현 정책과 변경 근거

- 확정 P0 범위를 유지했고, 화면 계약상 허용된 분석/확인 화면 통합을 사용했습니다.
- Spring Boot 4.1.1과 Java 25를 그대로 사용했습니다. JDBC로 명시적 SQL·행 잠금·트랜잭션을 구현했습니다.
- MySQL과 H2의 다중 ALTER 문법 차이 때문에 V2를 동등한 개별 ALTER/CREATE INDEX로 분리했습니다. 생성키도 ID 열만 명시해 드라이버 차이를 처리했습니다.
- 자동 배정은 활성·출처·검증일이 있는 DB 규칙만 사용합니다. 누수 업무는 정확한 공식 근거가 부족해 시드 규칙을 비활성화했습니다. 신고 자체는 가능하며 관리자가 활성 규칙으로 재배정할 수 있습니다. [출처 기록](docs/data-sources.md)
- 서버가 로그인 사용자/역할을 확인하며 본인 소유 신고만 학생에게 반환합니다. 비활성화·역할 변경 시 기존 세션도 무효화합니다. 비밀번호 BCrypt, HttpOnly/SameSite=Lax 세션 쿠키, 로그인 포함 CSRF 보호를 적용했습니다.
- 상태 전이는 RECEIVED → IN_PROGRESS → RESOLVED, 완료 결과 필수입니다. 상태·사건 연결·감사 이력은 하나의 트랜잭션입니다. 중복 UUID는 사용자별 unique 제약과 409로 방어합니다.
- 유사 후보는 확정이 아닙니다. 관리자가 동일 사건 확인 전 자동 병합하지 않습니다. 기존 별도/반려 결정과 충돌하거나 종료·다른 사건에 연결된 경우 409입니다.
- 발생일은 DATE 정밀도이므로 이전 종료일과 같은 날의 재발은 선후를 입증할 수 없어 확정 참조를 거절합니다. 미확인 발생일은 끝까지 `REPORTED_AT_ONLY`로 표시합니다.
- V1/V2는 구분했지만 초기 프로젝트 부팅에서는 두 마이그레이션을 연속 적용합니다. 기능 구현·검증은 핵심 흐름과 확장 단계로 진행했습니다.

## 보안·한계

`.runtime/`, `.env*`(예시 제외), `backend/data/`, Gradle/npm 캐시, 빌드 결과는 Git 제외 대상입니다. DB 파일·덤프·비밀번호·AI 키를 공개하지 마세요. 로컬 HTTP 시연용이라 COOKIE_SECURE 기본은 false이며 운영 HTTPS에서는 true가 필요합니다. 운영 배포·침투 테스트·대용량 성능·독립 데이터의 AI 품질은 별도 검증 대상입니다.

합성 평가의 라벨은 구현 경험이 있는 에이전트가 작성했고 사람의 독립 검수 전입니다. 일반화된 AI 정확도로 발표할 수 없습니다. 최신 접수 흐름 검증은 **2026-10-16 백엔드 144개(실제 MySQL HTTP 통합 18개 포함), 프런트 46개 PASS·빌드 PASS**입니다. 이번 추가 유료 API 호출은 0회이며 새 프롬프트 LIVE 품질은 미검증입니다. 이전 학생 화면의 실제 브라우저 확인은 10월 13일 기록과 구분합니다. 과거 DEMO 평가 문장 전체 일치 9/30은 LIVE 결과와 별개입니다. [AI 평가 설명](docs/ai-evaluation.md)과 [실행 검증 결과](docs/verification.md)를 확인하세요.

빌드 번들을 제공하는 로컬 동일 출처 프록시와 별도 MySQL 부하 시험은 [프록시 검증](docs/deployment-verification.md)에 있습니다. 400개 HTTP 요청은 통과했지만 HTTPS는 이 PC의 Avast 인증서 교체로 차단되어 미검증입니다. [API 계약](docs/api-contract.md), [접근·보관 검토](docs/privacy-security-review.md), [공식 자료 재확인](docs/source-reverification.md)도 추가했습니다.
