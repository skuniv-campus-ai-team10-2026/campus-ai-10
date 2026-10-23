# SKOPE 백엔드 협업 인수인계

## 2026-10-23 최신 통합 및 프런트 작업 시작점

이번 통합은 부서 공유본 `3b8d9bf`에 사진 PR #1의 `41512b6`(V5·V6)와 로컬의 최신 AI·장소 예시·평가 기록을 함께 반영한다. 프롬프트 `hybrid-intake-v2`, 정책 `hybrid-check-v3`가 기준이다. 아래 10월 21일의 "병합 전" 상태 및 AI 변경 제외 설명은 당시 기록이며, 현재 범위는 이 절을 따른다.

- V1~V5는 원본 그대로 유지하고 V6만 추가한다. 기존 DB/사진을 자동 초기화하지 않는다.
- API 키·DB 비밀번호·개인 설정·DB 파일·원시 AI 캐시는 공유하지 않는다. 평가 자료는 합성 입력과 저장된 결과이며 독립 운영 정확도를 의미하지 않는다.
- 사진 API는 접수된 신고 ID에 첨부한다. 사진 실패로 신고 생성 API를 다시 호출하면 안 된다. 사진 화면과 새 Figma 디자인은 아직 연결하지 않았으며 프런트 담당자가 구현한다.
- 현재 파일 저장·정리는 **한 백엔드 인스턴스와 해당 DB 전용 저장 폴더**를 기준으로 사용한다. 서로 다른 테스트/개발 DB가 같은 `ATTACHMENT_DIR`을 공유하지 않는다. 다중 인스턴스·공유 디스크 운영은 검증되지 않았다.
- V6 재처리는 시작 시 또는 사진 업로드·삭제 후 최대 20건이다. 재시작 전 기록은 10분 경과 후 대상이 되므로, 작업이 없으면 다음 실행까지 남을 수 있다. 실제 프로세스 강제 종료 검증은 팀원 보고와 이번 독립 재검증을 구분한다. [상세 제한](report-attachments.md).

### 프런트 담당자가 활용할 기존 파일

| 파일 (`frontend/src/` 기준) | 역할 / 유지할 동작 |
|---|---|
| `App.tsx`, `context.ts` | 로그인·로그아웃, 학생/총괄/부서 관리자 분기, 페이지 경로·사용자 정보 |
| `student.tsx` | 자연어 최초 입력, 선택적인 직접 수정, 접수 완료. 기존 수동 입력 양식을 필수 단계로 복원하지 않기 |
| `intake.tsx` | 서버의 `ACCEPTED`, `NEEDS_INPUT`, `REVIEW_REQUIRED`에 따른 이동·추가 답변·검토 접수 |
| `reports.tsx` | 목록·상세·답변/조치 결과·부서 이관·재배정 |
| `repeated.tsx` | 유사 후보에 대한 관리자 판단, 동일 사건·재발 검토 |
| `api.ts`, `types.ts` | 세션 쿠키·CSRF·오류 형식·요청/응답 타입. 화면마다 인증 요청을 새로 구현하지 않기 |
| `ui.tsx`, `styles.css` | 공통 UI와 임시 디자인. Figma에 맞춰 변경·분리 가능 |

시작: 루트 README대로 DEMO 백엔드 실행 → `frontend`에서 `npm ci`, `npm run dev` → 학생 입력/추가 질문/결과 확인 → 부서·총괄 처리 확인. Vite는 5173에서 `/api`를 8080으로 전달한다. 개인 API 키 없이 DEMO/H2로 시작할 수 있다. 본인 PC의 계정 비밀번호는 초기화 스크립트가 생성한다.

새 Figma 시안은 화면 디자인과 이동 명세이며 모든 동작이 이미 구현됐다는 뜻이 아니다. 화면 구조를 바꿔도 `clientRequestId`, 서버 접수 결과 확인, 이중 클릭 방지, 권한별 동작은 유지한다. AI 판단을 프런트에서 중복 구현하지 않는다. 현재 로그인 만료/로그아웃은 민감한 초안을 삭제하므로, 시안의 재로그인 후 초안 복원은 합의 없이 변경하지 않는다.

사진 연결은 파일 선택·미리보기 → 신고 ID 확보 → `POST /api/reports/{id}/attachments` 순서다. `FormData`의 필드명은 `file`이며 한 요청에 한 장이다. 기존 JSON용 `api.ts`는 모든 body에 JSON Content-Type을 붙이므로, multipart 지원을 명시적으로 추가해야 한다(브라우저가 boundary를 설정하도록 Content-Type 직접 지정 금지, 세션/CSRF 유지). [API 계약](api-contract.md#사진-첨부-api-v5v6).

검증 명령: 프런트 `npm test`, `npm run build`; 백엔드 루트 `scripts/Test-Backend.ps1`(기본 H2). 유료 평가 스크립트는 별도 합의 없이 실행하지 않는다. 화면 구현/DEMO 연동/LIVE 검증/미연결 항목을 PR에서 구분한다.

### 이번 통합 검증

- H2 전체 회귀: 361건 중 347 PASS / 0 FAIL / 14 SKIP. SKIP은 유료·캐시 평가의 명시적 opt-in이며 성공으로 합산하지 않았다. 백엔드 `bootJar` PASS.
- MySQL 포함 첨부·접수·부서 회귀: 92 PASS / 0 FAIL / 0 SKIP. 이 중 DB를 사용하는 59건은 기존 `skope_test`, 이미지/파일 단위 33건은 임시 파일 기반이다. V4가 적용된 테스트 DB에서 V5·V6 마이그레이션 성공을 확인했다. 기존 V1~V5 파일은 변경하지 않았다.
- 새 HTTP 통합 사례: 자연어 DEMO 접수 → 사진 저장 → 접수 재조회 시 중복 없음 → 담당 부서 사진 조회 → 총괄 재배정 → 이전 부서 차단 → 새 부서 처리 완료 → 학생 결과 조회 → 완료 후 사진 삭제/결과 보존. 위 MySQL 92건에 포함된다. 이 추가 사례만 H2에서도 별도 실행하여 1 PASS(전체 회귀와 합쳐 H2 348 PASS / 14 SKIP).
- 프런트: 57 PASS / 0 FAIL, TypeScript/Vite 빌드 PASS. Figma 디자인·사진 프런트는 이번에 구현하지 않았다.
- 유료 API 호출 0회. 실제 LIVE 품질, 새 디자인/사진의 브라우저 통합, 실제 휴대폰 사진, 실제 업로드 중 프로세스 강제 종료는 이번 독립 검증 범위가 아니다.
- 기존 테스트 DB를 로컬 비공개 백업한 뒤 기존 계정으로 사용했다. 개발·시연 DB는 변경하지 않았으며 새 DB 사용자/권한은 만들지 않았다.
- 게시 후보 249개 파일 및 기존 Git 이력 227개 blob에서 알려진 로컬 비밀값 4개·일반 키 패턴·금지 경로 검사 결과 검출 0건. 모든 종류의 비밀정보 부재를 보증하는 검사는 아니다.

## 2026-10-21 사진 첨부 PR #1 검토 — 병합 전 보완 권장

- PR: https://github.com/skuniv-campus-ai-team10-2026/campus-ai-10/pull/1
- 검토 HEAD: `8532b56ee34cba74228775a087e023f8700917ed`, 대상 `feature/department-workflow`. GitHub 충돌 없음, 등록된 check run 없음. 아직 병합·리뷰 댓글 게시·수정 push는 하지 않았다.
- 실제 재실행: 첨부 54개(HTTP 10, 서비스 12, 저장소 16, 이미지 16) + 기존 API 20개 + 부서 흐름 8개 = **82 PASS / 0 FAIL / 0 SKIP**, `bootJar` 성공. 격리 H2 및 임시 사진 폴더, DEMO/RULE_ONLY. 유료 API 0회, 기존 MySQL 변경 없음.
- 학생 소유권/부서별 조회/이관 후 차단/CSRF/동시 3장 제한/완료 후 삭제/이미지 방향 보정 등은 위 범위에서 통과했다. 팀원의 MySQL 전체 테스트 PASS는 PR 작성자의 보고이며 이번 검토에서 독립 재실행하지 않았다. 화면 통합·실제 휴대폰 사진도 미검증이다.

### 발견: 파일 삭제 실패를 성공 처리하고 재처리할 연결 정보를 잃음 (P2)

`AttachmentService.java:76-86`은 첨부 DB 행과 삭제 이력을 먼저 커밋한 뒤 `storage.delete(key)`의 false 결과를 무시한다. `AttachmentStorage.delete`는 디스크 삭제 오류를 로그로만 남긴다. 따라서 삭제 API는 204를 반환하지만 실제 사진이 남을 수 있으며, 같은 첨부를 다시 삭제하면 404라 복구되지 않는다. 공개 접근이 가능해지는 문제는 아니지만 삭제 요청 후 사진 보존 및 저장 공간 누적 문제다. `AttachmentService.java:56-59`의 업로드 실패 정리도 같은 반환값을 무시한다.

- 추가 진단 1: 합성 사진 정상 저장 → 파일 삭제만 실패하도록 주입 → 서비스 정상 반환, DB 행 0개, 실제 파일 1개, 삭제 이력 존재, 재삭제 404. **문제 재현 확인**.
- 추가 진단 2: 첨부 DB INSERT 실패와 파일 정리 실패를 함께 주입 → 신고 유지, 첨부 행 0개, 실제 파일 1개. **문제 재현 확인**.
- 두 진단 테스트는 위 결함 상태를 확인하도록 작성되어 테스트 자체는 2/2 통과했다. 제품 기능 PASS로 합산하지 않는다. 실제 디스크 장애나 프로세스 강제 종료를 재현한 것은 아니다.
- 보완 요청: 삭제 성공/이미 없음/삭제 실패를 구분하고, 실패한 저장 키를 잃지 않는 지속 가능한 재처리 경로를 마련한다. 단순히 503만 반환하거나 파일 삭제를 DB 커밋 앞으로 옮기면 재시도 불능 또는 롤백 불일치가 남는다. 테스트 DB 기준으로 공유 폴더의 모든 미참조 파일을 일괄 삭제하는 방식도 피한다. 위 두 실패 사례를 회귀 검증한 뒤 재검토한다.

### 보존 자료와 다음 순서

- 로컬 `.runtime/attachment-pr-review/`: `baseline-results.json`, `AttachmentDeletionReviewTest.java`, `diagnostic-results.xml`, `secret-audit.json`. 진단 소스는 검토 후 PR 체크아웃 밖으로 이동해 원본 PR 파일을 유지했다. Git 작업 폴더는 검토 HEAD의 detached 상태다.
- PR 포함 Git 파일/이력에서 알려진 로컬 비밀값 4개·일반 키 패턴·금지 경로 검사: 검출 0건. 모든 종류의 비밀정보 부재를 보증하는 검사는 아니다.
- 원래 작업 폴더의 제품 코드와 실행 서버는 변경하지 않았다. 팀원에게 위 파일 정리 실패 보완을 요청 → 수정 커밋 재검증 → 부서 브랜치 병합 → 프런트 사진 첨부 연결 → 학생·담당 부서 브라우저 통합 확인 순서가 적절하다.

## 2026-10-21 GitHub 공유 완료 / 첨부 담당자 작업 경계

- 원격: `https://github.com/skuniv-campus-ai-team10-2026/campus-ai-10.git`
- 공유 브랜치: `feature/department-workflow`, 커밋 `3b8d9bf417d112ec3151e5a7c6b882e71c989566`. 원격 조회로 업로드 확인. main은 `b64185c60f7e1409e3ff1bae97c1179a35c2ca46` 그대로다.
- 로컬 Git 연결 사본: `exports/campus-ai-10-department-sync`. 현재 원래 작업 폴더는 Git 저장소가 아니므로 이 폴더에서 만든 변경이 자동으로 원격에 올라가지는 않는다.
- 공유 범위는 V4, DepartmentAccess, 부서별 권한·이관/재배정·시연 배정과 관련 프런트/테스트/실행 안내다. 원래 작업 폴더의 이후 AI 프롬프트/정책 및 장소 예시 정정은 이번 브랜치에서 제외했다. 브랜치의 AI 버전은 hybrid-intake-v1 / hybrid-check-v2다.
- 분리한 브랜치에서 백엔드 101 PASS(H2/DEMO), 프런트 57 PASS, 양쪽 빌드 PASS. 실제 로컬 비밀값·흔한 키 패턴·제외 경로를 후보 파일과 Git 기록에서 검사하여 검출 0건. 새로운 유료 API 호출과 기존 DB 변경은 없다.
- 사진 첨부 담당자는 이 커밋을 기준으로 V5를 사용한다. application.properties의 multipart 한도와 Errors.java의 413 처리는 첨부 담당자 브랜치에서 함께 수정하기로 했으므로 중복 구현하지 않는다.
- 상세 첨부 합의(5MB, 요청당 1장/신고당 3장, JPEG/PNG 재인코딩, 완료 후 본인 삭제와 이력, 완료 후 추가 금지, 부서 범위 검사, 파일 정리)는 공유 브랜치의 docs/TEAM_HANDOFF.md 상단에 기록했다. 첨부 API/테스트는 팀원, 프런트 연결은 별도이며 아직 구현 완료로 간주하지 않는다.

아래는 기존 로컬 개발 당시의 기록이다. 원격 업로드 전이라는 과거 설명은 위 공유 완료 상태로 대체한다.

기준일: 2026-10-18. 서버 정책 `hybrid-check-v2`, 프롬프트 `hybrid-intake-v1` 유지. 부서별 처리 권한과 시연용 배정 추가.

## 부서별 처리·시연 배정 (2026-10-18)

현재 등록된 부서 4개를 모두 지원한다. 학교 전체 조직 목록을 확인한 것은 아니다. 아래 근거 상태는 기존 코드/기록 기준이며 이번 작업에서 공식 홈페이지를 다시 검증하지 않았다.

| 부서 | 업무 | 배정 근거 | 신규 DB 계정 예시 |
|---|---|---|---|
| 정보전산원 | WIFI | 기존 검증 규칙 | dept1 |
| 건설관리과 / 전기실 | ELECTRIC | 기존 검증 규칙 | dept2 |
| 총무처 건설관리과 | LEAK | 시연용 가정 · 실제 담당 업무 미확인 | dept3 |
| 교무처 교무과 | STUDENT_RECORD | 기존 검증 규칙 | dept4 |

계정명은 실제 DB의 `dept{부서ID}`다. 기존 DB의 ID가 다르면 예시와 다를 수 있다. 최초 생성 비밀번호는 `DEMO_PASSWORD`(로컬 실행에서는 `.runtime/demo-accounts.txt`에 있는 비밀번호)를 사용한다. 기존 계정의 비밀번호·소속·활성 상태는 덮어쓰지 않는다.

- `ADMIN`: 총괄. 전체/미배정/이관 대기 목록, 예외 조정과 재배정.
- `DEPT_ADMIN`: 소속 부서 신고 조회·처리·이관 요청. 소속/부서 활성 여부는 매 요청 DB에서 확인하며 브라우저의 departmentId를 신뢰하지 않는다.
- 이관에는 사유가 필요하고, 총괄 재배정 전까지 기존 부서가 책임과 처리 권한을 유지한다. 재배정 또는 완료하면 대기가 해제된다. 같은 규칙을 다시 선택하고 사유를 남기면 기존 부서 유지 결정을 기록할 수 있다.
- 유사 후보는 양쪽 신고, 사건은 모든 구성 신고에 접근할 수 있어야 한다. 혼합 부서 사건은 총괄만 검토한다. 여러 신고가 묶인 사건의 일부만 다른 부서로 보내는 재배정은 409로 막는다. 사건 단위 일괄 이관은 이번 범위에 없다.
- `DEMO_ROUTING_ENABLED=false`가 기본값. true일 때만 기존 LEAK 샘플의 시연용 규칙을 사용하고 활성 등록 부서 전체에 담당 계정을 생성한다. 출처·검증일을 조작하지 않으며 미확인 연락처·출처는 노출하지 않는다. OTHER/UNKNOWN 및 정보 부족 신고는 총괄 검토 대상으로 남는다.
- AI의 LIVE/DEMO와 배정 근거의 VERIFIED/DEMO는 독립적이다. LIVE AI + 시연용 배정도 가능하다. 실제 학교에는 전송하지 않는다.

기존 서버 종료 후 프로젝트 루트에서 시연 배정을 활성화한다. 첫 명령의 AI 모드는 기존 환경을 따르며 명령 자체가 LIVE를 켜지는 않는다.

```powershell
.\scripts\Start-Backend.ps1 -DemoRouting
```

기존 소유자 PC에서 `.runtime/ai-verification.env`가 준비돼 있다면 다음 기존 LIVE 실행기를 사용할 수 있다. `skope_ai_quality`를 사용하며 서버 시작 자체는 추론 호출이 없다.

```powershell
.\scripts\Start-LiveVerificationBackend.ps1 -DemoRouting
```

시연 배정을 끄려면 `$env:DEMO_ROUTING_ENABLED='false'`로 지정하고 옵션 없이 재시작한다. 기존 계정·접수 기록은 삭제하지 않는다.

**협업:** Flyway `V4__department_workflow.sql`을 추가했다. 이미지 담당자의 신규 마이그레이션과 번호 충돌을 병합 전에 확인하고, 이미 적용한 파일을 임의 변경하지 않는다. 이미지 API도 학생 본인 소유권 또는 관리자 `DepartmentAccess.requireReport(actorId,reportId)`를 적용해야 한다. 역할만 확인하면 다른 부서 자료 접근 제한이 빠진다. 팀원 브랜치 병합·원격 반영은 아직 하지 않았다.

이번 검증: 백엔드 101 PASS(HTTP/MySQL 28 포함), 프런트 관련 52 PASS, 빌드 PASS. 별도 MySQL DB의 브라우저 DEMO 접수·이관·처리·학생 조회 PASS. 새 LIVE 검증과 이미지 통합은 별도다.

## 이전 AI 수정의 검증 상태 (2026-10-16)

- 최신 수정의 관련 회귀 검사 132개 PASS: 단위/결정 로직 107개, HTTP/MySQL 25개. 전체 프로젝트의 모든 테스트 수를 뜻하지 않는다.
- 저장된 실제 AI 응답 37건을 수정 정책으로 재생해 37 PASS. 같은 개발 데이터의 재검증이며 독립 AI 정확도가 아니다.
- S26-2/3, S28-2는 원래 호출된 응답이 없어 LIVE 미검증이다. 수정 후 새 LIVE 브라우저 E2E도 별도 확인이 필요하다.
- 기존 문서의 PASS·FAIL은 실행 당시 버전의 결과다. 현재 판단은 [verification.md](verification.md)의 가장 위 v2 절을 우선한다.
- 학교 공식 시스템으로 전달하지 않는 시연용 자체 접수 시스템이다.

## 이 저장소에 포함된 것과 없는 것

소스, Gradle Wrapper, npm lockfile, Flyway V1~V4, 합성 시드 생성 코드, 테스트, 합성 평가 입력·결과, 시연 화면 증거를 포함한다. 스크린샷은 과거 화면의 증거이며 현재 디자인 명세가 아니다.

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
