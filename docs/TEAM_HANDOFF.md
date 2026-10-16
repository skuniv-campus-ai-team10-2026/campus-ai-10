# SKOPE 백엔드 협업 인수인계

기준일: 2026-10-16. 서버 정책 `hybrid-check-v2`, 프롬프트 `hybrid-intake-v1`.

## 먼저 확인할 상태

- 최신 수정의 관련 회귀 검사 132개 PASS: 단위/결정 로직 107개, HTTP/MySQL 25개. 전체 프로젝트의 모든 테스트 수를 뜻하지 않는다.
- 저장된 실제 AI 응답 37건을 수정 정책으로 재생해 37 PASS. 같은 개발 데이터의 재검증이며 독립 AI 정확도가 아니다.
- S26-2/3, S28-2는 원래 호출된 응답이 없어 LIVE 미검증이다. 수정 후 새 LIVE 브라우저 E2E도 별도 확인이 필요하다.
- 기존 문서의 PASS·FAIL은 실행 당시 버전의 결과다. 현재 판단은 [verification.md](verification.md)의 가장 위 v2 절을 우선한다.
- 학교 공식 시스템으로 전달하지 않는 시연용 자체 접수 시스템이다.

## 이 저장소에 포함된 것과 없는 것

소스, Gradle Wrapper, npm lockfile, Flyway V1~V3, 합성 시드 생성 코드, 테스트, 합성 평가 입력·결과, 시연 화면 증거를 포함한다. 스크린샷은 과거 화면의 증거이며 현재 디자인 명세가 아니다.

API 키, DB/계정 비밀번호, `.runtime`, 실제 로컬 DB/덤프, 빌드·의존성 캐시, AI 공급자 원시 캐시는 포함하지 않는다. 따라서 기존 평가 JSON은 읽을 수 있지만 `.runtime/ai-quality-cache`에 의존하는 재생 실행기를 새 clone에서 바로 실행할 수는 없다. 캐시가 없다고 LIVE 모드로 바꾸어 전체 평가를 다시 호출하지 않는다. 필요하면 원 소유자에게 비밀정보를 제거한 해당 합성 캐시만 별도로 요청한다.

`.env.example`은 빈 설정 예시이며 Spring Boot가 자동으로 읽는 파일이 아니다. 다른 사람의 `.runtime` 폴더를 복사하지 않고 각자 초기화한다.

## 새 컴퓨터에서 실행하기

Windows PowerShell 7 기준. Java 25, Node.js/npm, MySQL 8 바이너리를 준비하고 `java -version`, `node --version`, `npm.cmd --version`으로 확인한다. 코드의 정확한 의존성은 `backend/build.gradle`, `backend/gradle/wrapper/gradle-wrapper.properties`, `frontend/package-lock.json`을 따른다. 첫 실행은 의존성 다운로드를 위한 인터넷 연결이 필요하다.

압축을 푼 프로젝트 루트(README.md와 backend, frontend가 있는 폴더)에서:

```powershell
# 본인 PC에 격리된 DB와 임의 비밀번호 생성. MySQL 설치 경로가 다르면 -MySqlBin 지정.
.\scripts\Initialize-Local.ps1

# 터미널 1: 기본 DEMO 분석 + RULE_ONLY, 실제 유료 AI 호출 없음
.\scripts\Start-Backend.ps1
```

다른 터미널에서:

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev -- --host 127.0.0.1
```

브라우저 `http://127.0.0.1:5173`에서 student1/student2/admin으로 로그인한다. 본인 PC에서 생성된 비밀번호는 `.runtime/demo-accounts.txt`에 있다. 서버 주소의 `localhost`와 `127.0.0.1`을 혼용하지 않는다. DB는 기본 3307이며 포트 충돌 시 초기화 전에 다른 포트를 선택한다. 기존 DB를 재초기화하거나 팀원의 DB 주소를 테스트 설정에 쓰지 않는다.

MySQL 준비가 어려우면 README의 H2 경로를 사용할 수 있지만 H2 성공은 MySQL 연동 성공이 아니다.

## 실제 AI 연동을 확인할 때

본인에게 발급된 키와 사용 가능한 모델이 필요하다. 기존 측정 모델 이름은 `gpt-6-luna`, `text-embedding-3-small`이며 팀원의 계정 접근 가능성은 별도 확인해야 한다. 모델을 바꾸면 기존 품질 결과를 새 모델의 결과로 인용하지 않는다. 호출 수와 비용 상한을 먼저 합의한다.

기존 백엔드를 종료한 후 프로젝트 루트의 PowerShell에서 설정한다. 아래 키 입력은 화면과 명령 기록에 키 원문을 남기지 않지만 실행 중 프로세스 환경에는 보관된다.

```powershell
$env:AI_API_KEY=[System.Net.NetworkCredential]::new('', (Read-Host '본인 API 키' -AsSecureString)).Password
$env:AI_MODE='LIVE'
$env:AI_MODEL='gpt-6-luna'
$env:AI_REASONING_EFFORT='none'
$env:EMBEDDING_MODE='LIVE'
$env:EMBEDDING_MODEL='text-embedding-3-small'
.\scripts\Start-Backend.ps1
```

서버 종료 후 해당 터미널을 닫거나 `Remove-Item Env:AI_API_KEY`로 값을 제거한다. `/api/meta`에서 LIVE/LIVE/MYSQL을 확인하되 이 표시는 호출 성공의 증거가 아니다. 실제 분석 요청·저장·결과 조회로 확인해야 한다. LIVE에서는 입력이 공급자로 전송되고 신고 저장 시 임베딩도 호출될 수 있으므로 합성 데이터만 사용한다.

`Start-LiveVerificationBackend.ps1` 등 과거 평가 전용 스크립트는 기존 소유자의 로컬 `.runtime` 설정을 전제로 한다. 새 작업자의 기본 실행 경로는 위 `Start-Backend.ps1`이다. 대량 LIVE 평가 스크립트는 온보딩 명령이 아니다.

## 첫 공동 작업: 최종 연동 검증

새 기능 개발보다 다음 흐름을 동일 커밋에서 확인하는 것을 우선한다. 실행 전에 기대값을 기록하고 결과를 보고 정답을 바꾸지 않는다.

1. 모호한 신고가 바로 저장되지 않고 필요한 질문을 받는지 확인한다.
2. 장소·시간·증상을 한 답변으로 보완하고, 장소만 정정해도 다른 정보가 보존되는지 확인한다.
3. 건물 변경 시 기존 층·호실이 제거되고 필요한 질문이 이어지는지 확인한다.
4. 충분한 정보가 확보되면 단일 접수·MySQL 저장·유사 후보 생성으로 이어지는지 확인한다. 의미가 다른 신고의 후보/연결 여부와 관리자 승인 전 자동 연결이 없는지도 확인한다.
5. 관리자 처리 시작/완료 후 학생 계정에서 결과 조회·새로고침을 확인한다.
6. AI/Embedding 실패 시 복구·접수 보존 여부를 확인한다. 의도적인 실패 주입은 별도 테스트 환경에서만 한다.

서버 저장 경계의 위험 플래그 보존은 이미 Stub+MySQL로 검증했다. 실제 LLM의 위험 인식 여부와 구분해서 보고한다. 과거 고정 기대값은 `docs/evaluation/hybrid-stress-v1.json`에 있다. 검증 결과에는 커밋, 모드, 입력·추가 답변, 기대·실제 결과, PASS/FAIL/미검증, API 횟수·토큰·비용을 남긴다. 키·쿠키·CSRF 토큰은 보고서에 넣지 않는다.

## 무료 로컬 검사

앱을 실행한 터미널과 분리하고 유료 평가용 opt-in 환경 변수를 설정하지 않은 새 터미널에서 실행한다.

```powershell
# H2 기반 일반 테스트 + JAR 빌드
.\scripts\Test-Backend.ps1
# 실제 MySQL로 확인하려면 로컬 DB 준비 후 이 경로 사용
.\scripts\Test-Backend.ps1 -MySQL

cd frontend
npm.cmd test
npm.cmd run build
```

MySQL 테스트는 `skope_test`의 신고·사건·이력을 지우고 합성 계정 비밀번호를 바꾼다. 운영·시연 DB를 `TEST_DB_URL`로 지정하지 않는다. 캐시 재생/유료 평가 테스트는 opt-in이며 일반 테스트의 SKIPPED를 LIVE 성공으로 세지 않는다. 별도 환경의 첫 실행·새 LIVE 검증은 이 배포 ZIP을 만들면서 수행한 검증이 아니다.

## 두 작업자의 합의

- 동일 기준 커밋에서 각자 브랜치를 만들고 한쪽은 연동 검증, 다른 쪽은 재현·수정을 담당한다.
- API 요청/응답, 인증·CSRF, Flyway 변경은 사전 합의한다. 적용된 기존 마이그레이션을 덮어쓰지 않고 신규 번호를 조율한다.
- PR에는 재현 입력, 변경 이유, 테스트 결과, 미검증 사항, API/DB 변경 여부를 기록한다.
- `main` 반영은 한 명이 담당하고 충돌을 임의로 덮거나 강제 push하지 않는다. 현재 시연 DB는 공유 개발 DB로 사용하지 않는다.
