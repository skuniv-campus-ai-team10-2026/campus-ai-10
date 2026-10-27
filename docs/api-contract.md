# SKOPE 로컬 실험 API 구현 계약

기준일: 2026-10-27. `ApiController`, `AuthController`, `SecurityConfig`, 서비스 구현 및 `frontend/src/types.ts`를 읽어 정리했다. **현재 독립 실험 구현의 계약이며 팀의 최종 승인이나 공식 시스템 계약을 뜻하지 않는다.** 실제 실행 증거는 `verification.md`, AI 검증 범위는 `ai-evaluation.md`를 따른다.

## 부서별 접근·배정 확장

- ADMIN은 전체, DEPT_ADMIN은 DB의 활성 소속 부서 신고만 조회·처리한다. 미배정·타 부서 신고는 404, 소속 없거나 비활성 부서는 403. 모든 변경은 기존 CSRF 유지.
- `transferRequested=true` 필터와 재배정은 총괄 전용이다. 이관 요청은 현재 배정을 바꾸지 않으며 재배정/완료 시 대기가 해제된다. 관리자 응답에 `transferRequested`, `transferReason`을 추가한다.
- 유사 후보는 양쪽 신고를 검사하고 사건은 구성원 전체가 허용 범위여야 한다. 타 부서의 과거 사건 참조는 부서 응답에서 숨긴다. 다중 신고 사건의 일부 부서 변경은 409 `INCIDENT_DEPARTMENT_CONFLICT`.
- 배정 메타데이터에 `assignmentBasis`(VERIFIED/DEMO/기존 자료 UNVERIFIED), `demoOnly`, `warning` 추가. DEMO는 verified=false이고 연락처·출처를 노출하지 않는다. 신규 DEMO 배정은 `DEMO_ROUTING_ENABLED=true`에서만 허용한다. 공식 지식 답변의 근거로 DEMO 규칙을 사용하지 않는다.
- 신규 이미지 API도 학생 소유권/부서 범위를 적용해야 한다.

## 공통 형식과 인증

- 기본 경로 `/api`, JSON `camelCase`, ID는 JSON number, 시간은 서울 기준 ISO 8601 오프셋 포함, 날짜는 `YYYY-MM-DD`.
- 로그인은 학교 포털과 별개인 실험용 세션이다. 서버는 로그인 계정의 역할·활성 상태와 신고 소유권을 검사한다.
- 기본 세션 쿠키는 `JSESSIONID`, `HttpOnly`, `SameSite=Lax`; Secure 속성은 `COOKIE_SECURE`로 설정하며 로컬 HTTP 기본값은 false다. 기본 세션 타임아웃은 30분이다.
- 변경 요청 전에 `GET /api/auth/csrf`를 호출하고 응답의 `headerName` 이름으로 `token`을 보낸다. 로그인도 CSRF 토큰이 필요하다. 로그인·로그아웃 후 다시 조회한다. 프런트엔드는 401/403 발생 시 캐시된 토큰을 버린다.
- 브라우저 API 호출은 같은 출처의 `/api`와 `credentials: include`를 사용한다. CORS를 이용한 별도 도메인 배포는 현재 검증 범위가 아니다.
- 활성 계정이 삭제·비활성화되거나 역할·비밀번호가 바뀌면 기존 세션도 다음 요청에서 401로 무효화된다. V8의 `users.auth_version`과 서버 세션의 버전을 비교한다. V8 배포 전 생성한 버전 없는 세션도 재로그인이 필요하다.
- 학생은 본인 신고만 읽으며, 타인 신고 ID 접근은 `404 REPORT_NOT_FOUND`로 거절한다. 관리자 화면 숨김과 별개로 서버 권한 검사가 존재한다.

목록 기본 형식:

```json
{"items":[],"page":0,"size":20,"totalElements":0}
```

오류 기본 형식:

```json
{"code":"VALIDATION_ERROR","message":"입력 형식 또는 값이 올바르지 않습니다.","fieldErrors":{}}
```

계정 입력 오류는 `fieldErrors`에 필드별 메시지를 반환하며, 나머지 오류는 빈 객체일 수 있다. 신고·사건 응답은 DB Map 기반으로 아래 소비 필드 외의 식별자·메타데이터가 추가될 수 있다. 계정 응답은 아래 6개 공개 필드만 반환한다. 비밀번호 해시·인증 버전·비밀키는 반환하지 않는다. 고정 DTO와 OpenAPI 자동 스키마 확정은 팀 검토 대상이다.

## 엔드포인트

| Method / 경로 | 권한 | 입력·쿼리 | 성공 응답 |
|---|---|---|---|
| GET `/auth/csrf` | 공개 | 없음 | 200 `{token,headerName}` |
| POST `/auth/register` | 공개 + CSRF | `{username,displayName,password}` | 201 공개 계정 정보, 자동 로그인 없음 |
| POST `/auth/login` | 공개 + CSRF | `{username,password}` | 200 공개 계정 정보 + 새 세션 |
| GET `/auth/me` | 로그인 | 없음 | 200 공개 계정 정보 |
| PATCH `/auth/me` | 로그인 + CSRF | `{displayName}` | 200 갱신된 공개 계정 정보 |
| POST `/auth/password` | 로그인 + CSRF | `{currentPassword,newPassword}` | 200 `{success:true,reauthenticationRequired:true}` + 현재 세션 종료 |
| POST `/auth/logout` | 로그인 + CSRF | `{}` | 200 `{success:true}` |
| GET `/meta` | 공개 | 없음 | 200 `{aiMode,embeddingMode,databaseMode,officialSubmission:false,demoRoutingEnabled}` |
| GET `/report-types` | 로그인 (학생·관리자) | 없음 | 200 `{items:RoutingRule[]}` |
| POST `/assistant/intake` | STUDENT + CSRF | 최초 입력 / 추가 답변 / 재개 / 검토 접수 | 200 `NEEDS_INPUT`, `REVIEW_REQUIRED`, `ACCEPTED` |
| POST `/assistant/analyze` | STUDENT + CSRF | 분석 요청 | 200 분석 결과, DB 신고 생성 없음 |
| POST `/reports` | STUDENT + CSRF | 신고 생성 요청 | 201 `ReportDetail` |
| GET `/reports` | STUDENT | `page=0,size=20,status?` | 본인 신고 페이지 |
| GET `/reports/{id}` | STUDENT | 양의 ID | 본인 `ReportDetail` |
| GET `/admin/reports` | ADMIN / DEPT_ADMIN | `page=0,size=20,status?,routeStatus?,transferRequested=false` | 총괄 전체 / 부서 소속 신고 페이지 |
| GET `/admin/reports/{id}` | ADMIN / DEPT_ADMIN | ID | `ReportDetail` + `events` |
| PATCH `/admin/reports/{id}/status` | ADMIN / DEPT_ADMIN + CSRF | `{status,resolutionText?}` | 갱신된 관리자 신고 상세 |
| PATCH `/admin/reports/{id}/routing` | ADMIN + CSRF | `{routingRuleId,reason?}` | 갱신된 관리자 신고 상세 |
| PATCH `/admin/reports/{id}/transfer-request` | DEPT_ADMIN + CSRF | `{reason}` 필수, 최대 1000자 | 갱신된 관리자 신고 상세 |
| GET `/admin/reports/{id}/similar` | ADMIN / DEPT_ADMIN | ID | 유사 후보 응답 |
| PATCH `/admin/similar-pairs/{id}/review` | ADMIN / DEPT_ADMIN + CSRF | `{decision}` | 갱신된 pair Map + `incidentId` |
| PATCH `/admin/reports/{id}/incident` | ADMIN / DEPT_ADMIN + CSRF | `{incidentId}` | 연결된 사건 상세 |
| GET `/admin/incidents/{id}` | ADMIN / DEPT_ADMIN | ID | 사건 상세 |
| PATCH `/admin/incidents/{id}/recurrence` | ADMIN / DEPT_ADMIN + CSRF | `{previousIncidentId,decision}` | 갱신된 사건 상세 |
| GET `/admin/repeated-candidates` | ADMIN / DEPT_ADMIN | 없음 | `{items,generatedAt,aiMode:"RULE_ONLY"}` |

신고 목록은 ID 내림차순이다. `page`는 0~100000, `size`는 1~100이며 잘못된 범위·필터는 400이다. 유사 후보·반복 후보·유형 목록은 페이지네이션 없는 별도 응답이다.

## 회원가입·내 정보 연동

- 공개 계정 정보: `{id,username,displayName,role,departmentId,departmentName}`. 기존 로그인·조회 응답에 `username`을 추가한다.
- 회원가입 아이디는 영문 소문자·숫자·밑줄 4~30자이며 변경할 수 없다. 이름은 양끝 공백 제거 후 1~30자다. 비밀번호는 공백을 그대로 보존하며 12~64자, UTF-8 기준 72바이트 이하이고 제어 문자를 허용하지 않는다. 글자 수는 Unicode 코드 포인트 기준이다.
- 가입 시 역할은 항상 `STUDENT`다. `role`, `departmentId`, `id`, `passwordHash` 등 계약 외 필드는 거절한다. 이름 수정은 `displayName`만 받는다. 비밀번호 확인 입력은 프런트에서 비교하며 서버에 보내지 않는다. 이메일·학교 계정 인증·비밀번호 분실 복구는 이번 범위에 없다.
- 중복 아이디: `409 USERNAME_TAKEN`, `fieldErrors.username`. 입력 오류: `400 VALIDATION_ERROR`와 필드별 오류. 잘못된 현재 비밀번호: `400 INVALID_CURRENT_PASSWORD`, `fieldErrors.currentPassword`; 기존 로그인은 유지한다. 새 비밀번호가 현재 비밀번호와 같으면 400이다.
- 가입 성공 후 로그인 화면으로 이동한다. 로그인은 기존 세션을 교체하므로 이전 계정의 서버 초안이 이어지지 않는다. 로그인 후 CSRF 토큰을 새로 받는다.
- 비밀번호 변경 성공 시 현재 세션은 즉시 종료되고 같은 계정의 다른 세션은 다음 요청에서 401이 된다. 다른 계정 세션·기존 접수 데이터는 유지한다. 프런트는 성공 응답 후 사용자 정보·CSRF 캐시·비공개 초안·선택 사진을 비우고 로그인 화면으로 이동한다. 추가 로그아웃 요청은 필요 없다.
- 현재 프런트 API 도우미는 `/api`를 붙이므로 호출 경로는 `/auth/register`, `/auth/me`, `/auth/password`다. `/signup`, `/profile` 화면은 프런트 담당 구현 대상이며 공유 프록시의 새로고침 경로만 준비했다.
- 외부 팀 공용 암호는 서비스 전체 LIVE 사용량 제한이 통합되기 전까지 유지한다. 회원가입 API 구현만으로 외부 보호를 제거하지 않는다. 이 변경 자체에는 로그인/가입 요청 횟수 제한이나 학교 재학생 확인 기능이 포함되지 않는다.

## 허용 코드

| 항목 | 값 |
|---|---|
| 역할 | `STUDENT`, `ADMIN`(총괄), `DEPT_ADMIN`(부서) |
| 분류 조합 | `IT/WIFI`, `FACILITY/ELECTRIC`, `FACILITY/LEAK`, `ACADEMIC/STUDENT_RECORD`, `OTHER/UNKNOWN` |
| 선호 의도 | `AUTO`, `REPORT`, `QUESTION` |
| 분석 의도 | `REPORT`, `QUESTION`, `UNKNOWN` |
| 신고 상태 | `RECEIVED`, `IN_PROGRESS`, `RESOLVED` |
| 배정 상태 | `MATCHED`, `NEEDS_REVIEW`, `MANUAL` |
| 사건 상태 | `OPEN`, `RESOLVED` |
| 유사 후보 판단 | `CANDIDATE`, `SAME_INCIDENT`, `RELATED_DISTINCT`, `REJECTED` |
| 재발 판단 | `NOT_EVALUATED`, `CANDIDATE`, `CONFIRMED`, `REJECTED` |
| 시간 근거 | `CONFIRMED_OCCURRENCE`, `REPORTED_AT_ONLY` |

유형 코드가 허용된다는 것과 검증된 자동 라우팅 규칙이 있다는 것은 다르다. `RoutingRule`은 활성 부서·활성 규칙이고 규칙의 `verifiedAt`, `sourceUrl`이 존재할 때 조회된다. 유형 목록에는 `{id,category,subcategory,workName,departmentName,contactPhone,sourceUrl,verified:true}` 및 DB 메타데이터가 들어간다. 수동 유형 선택에는 미매칭 유형도 허용되며 담당 부서는 서버에서 다시 결정한다.

## 기본 자연어 접수 흐름 (2026-10-16)

기본 화면은 `/assistant/intake`를 사용한다. 최초 버튼은 **문의·신고 보내기**이며 충분한 내용은 별도 확인 양식 없이 저장한다. 부족하면 질문만 표시한다. 기존 `/assistant/analyze`와 `/reports`는 분석·수동 수정 호환 경로로 유지한다.

2026-10-16 하이브리드 충분성 판단: intake가 내부 분석 요청에 `hybrid:true`를 지정한다. AI는 추출 사실과 근거 인용·질문 사유를 제안하고, 서버가 유형별 최소 정보·중복·횟수를 검사한다. `/assistant/analyze`는 생략 시 기존 동작을 유지한다. 저장 API·DB 마이그레이션 추가 변경은 없다.

- `analysis.clarification`: `{reason,neededReasons,message,source}`. 사유는 `ISSUE_UNCLEAR|LOCATION_REQUIRED|LOCATION_DETAIL|SYMPTOM_DETAIL|TARGET_UNCLEAR|WORKFLOW_UNCLEAR|CONFLICT`, 종료 시 reason/message는 null. source는 `AI_AND_RULES|SERVER_RULES`이며 LIVE 여부와 별개다.
- `followUpState`에 `nextReason`, `askedReasons`, `unavailableReasons`, `neededReasons`, `knownEvidence`, `urgentReview`가 추가된다. intake에서는 서버 세션이 상태를 소유하며 클라이언트가 상태를 제출하지 않는다.
- 질문은 한 번에 하나의 필요한 사유를 다룬다. 추가 답변은 다른 항목도 동시에 보완할 수 있다. 이미 물었지만 해결되지 않은 사유나 “모르겠다”는 답변은 같은 질문을 반복하지 않고 MANUAL_REVIEW로 종료한다. 기존 추가 답변 최대 3회/무진전 2회 제한도 유지한다.
- 건물만 알려진 국소 고장은 층/공간을 확인하되 명시적 건물 전체 문제에는 요구하지 않는다. WIFI는 장애 방식, 전기 설비는 점검 대상, 모호한 학적 문제는 업무 단계가 필요하다. 구체적 누수·가구 고장·학사 문의에 시간·원인·분류를 추가 요구하지 않는다.
- 학생에게 표시할 질문/필요성 문구는 서버 템플릿이다. AI가 제안한 임의 질문 문장은 그대로 노출하지 않는다. 원문에 없는 근거 인용은 무시한다. 근거 인용이 있다는 것만으로 의미 해석이 정확하다는 보장은 없다.
- 긴급 징후는 추가 질문으로 지연하지 않는다. 정보가 부족한 긴급 입력은 검토 사유를 남겨 NEEDS_REVIEW로 접수한다. 안전 안내는 접수 완료 화면에도 표시한다. 실제 학교·긴급 기관 전송은 아니다.

- 최초 입력: `{clientRequestId: UUID, text: 5~4000자, preferredIntent?: AUTO|REPORT|QUESTION}`.
- 추가 답변: `{clientRequestId, revision, answer: 1~500자}`. 이전 추출값·질문 횟수는 서버 세션에서 사용하며 클라이언트가 보낸 상태로 덮어쓰지 않는다.
- 재개: `{clientRequestId, resume:true}`. 추가 AI 호출 없이 현재 결과 또는 이미 접수한 결과를 반환한다. 세션 초안이 만료되면 `409 DRAFT_EXPIRED`; 자동 재분석하지 않는다.
- 더 설명하기 어렵거나 AI가 실패한 경우: `{clientRequestId, revision, submitForReview:true, answer?: 1~500자}`. 입력 중인 답변은 추가 유료 분석 없이 미분석 자료로 함께 보존하고 `NEEDS_REVIEW`로 접수한다.
- `NEEDS_INPUT`: `{status,revision,analysis,message,latestAnswer,aiMode}`. 실제 질문은 `analysis.report.followUpQuestion`.
- `REVIEW_REQUIRED`: 같은 형식. 횟수 제한·무진전·충돌·AI 실패로 자동 접수하지 않고 검토 접수/수동 수정 선택 제공.
- `ACCEPTED`: `{status,report,aiMode}`. `report.id`가 있어야 접수 성공 화면으로 이동한다.
- 같은 학생의 동일 `clientRequestId`가 이미 저장됐으면 기존 접수 건을 반환한다. 같은 세션의 요청은 직렬 처리하며 오래된 `revision`의 답변은 다시 분석하지 않는다. 세션별 초안 최대 8개, 초안은 세션 만료/재시작 시 소실된다. UUID를 매번 바꾸는 악용 방지용 사용량 제한이나 여러 서버 간 분산 잠금은 아니다.

질문 기준은 문제 내용 우선이다. AI가 구체성이 부족하다고 추출한 내용과 서버의 모호한 표현 검사를 함께 사용한다. 일반 문의·학적 요청은 물리적 장소를 필수로 요구하지 않는다. 물리적 불편은 사용 가능한 장소가 필요하다. 유형 미분류·발생 시각 누락만으로 질문하지 않는다. 추가 답변 최대 3회, 연속 무진전 2회 종료 규칙 및 기존 안전 병합을 유지한다. 판단의 실제 LLM 일반화 정확도는 별도 LIVE 검증 대상이다.

`reports`에 `requestKind: REPORT|QUESTION`(기존 행 기본 REPORT), `reviewNote: string|null` 추가. 검토 사유와 필요한 경우 마지막 추가 답변만 저장하며 전체 대화 이력을 만들지 않는다. 일반 문의는 관리자 답변 후 기존 RECEIVED → IN_PROGRESS → RESOLVED로 종료한다. 문의는 Embedding/유사 후보/사건 연결 대상에서 제외한다. 검토 메모가 있는 미확정 신고도 자동 유사 후보 생성에서 제외한다. 기존 동일 사건·재발 점수 임계값은 바꾸지 않았다.

2026-10-27 검토 보완: V7에서 `review_note`를 `VARCHAR(2000)`으로 확장한다. 공개 `POST /reports`의 직접 입력 `reviewNote`는 기존 1000자 제한을 유지한다. 서버 내부 `createFromAnalysis`에서 조합한 메모만 최대 2000자를 허용해 마지막 분석 답변 500자와 미분석 답변 500자 및 안내 문구를 자르지 않고 보존한다. 외부 JSON으로 내부 한도를 선택할 수 없다. 와이파이 신고의 유효한 위치 답변 후에는 추가 진단 질문 없이 접수할 수 있으며, 이미 접수한 UUID에 대한 늦은 답변은 기존 접수 내용을 변경하지 않는다. 전기·전원이라는 일반 단어만으로 점검 설비가 확인됐다고 보지 않는다.

발생 시점은 언급된 자연어 표현을 유지한다. `createdAt`은 자동 접수 시각이고 `occurredOn`과 구분하며, 상대 날짜를 임의로 확정 날짜로 변환하지 않는다. 학생이 직접 수정하는 기존 `/reports`는 `requestKind`(생략 시 REPORT)와 `reviewNote`를 선택적으로 받는다. 검토 메모가 있으면 자동 배정하지 않는다.

## 기존 분석·추가 질문·수동 신고 생성

최초 분석:

```json
{"text":"북악관 502호에서 와이파이가 계속 끊겨요.","preferredIntent":"AUTO"}
```

서버 분석 입력은 3~5000자, 화면 입력은 5~4000자다. `preferredIntent` 생략 시 AUTO. 추가 질문 `field`는 `LOCATION|SYMPTOM|CATEGORY|OCCURRED_AT`, `answer`는 비어 있지 않은 최대 1000자다.

```json
{
  "text":"학교 인터넷이 안 돼요.",
  "preferredIntent":"REPORT",
  "followUp":{
    "field":"LOCATION",
    "answer":"북악관 502호",
    "previousReport":{"category":"IT","subcategory":"WIFI","locationText":null,"symptom":"인터넷 장애"}
  }
}
```

`followUp`은 `field`, `answer`, `previousReport`와 선택적인 `state`를 허용한다. 기존 세 필드 요청도 호환된다. `previousReport`는 분류 두 필드가 필수이며 `locationText`, `building`, `floor`, `symptom`, `occurredAtText`, `missingFields`, `followUpQuestion`, `emergencyHint`를 추가할 수 있다. **`occurredOn`은 AI 재분석 입력에 포함하지 않는다.** 프런트는 직접 확인한 날짜를 별도로 유지하고 신고 저장 요청에서 보낸다.

REPORT 분석 응답의 `followUpState`를 다음 요청의 `followUp.state`에 그대로 전달한다. 상태 필드는 `turns`(0~3), `noProgress`(0~2), `askedFields`(최대 3개), `reviewFields`(확인 필요 항목), `status`, `nextField`다. `status`는 `CONTINUE|COMPLETE|LIMIT|NO_PROGRESS|MANUAL_REVIEW`다. 질문은 장소·증상이 유효할 때 종료하며, 발생 시각은 필수가 아니다. 최대 추가 답변 3회 또는 연속 2회 정보 진전 없음에서 질문을 종료한다. 소진된 상태로 다시 요청하면 모델을 호출하지 않는다. 이 상태는 초안 흐름 제어이며, 조작 방지용 서버 세션·호출량 제한을 대신하지 않는다.

추가 답변은 질문 필드 외의 장소·시각·증상·유형도 동시에 보완할 수 있다. 모델의 최신 답변 인용 근거와 `KEEP|SET|CLEAR|CLARIFY`를 서버가 검사해 병합한다. 이 내부 `updates`는 공개 응답에 포함하지 않는다. 건물 변경 시 이전 층·호실은 제거하고 충돌은 `reviewFields`로 표시한다. 프런트의 수동 수정은 해당 항목의 초안 검토 표시를 해제하며 전체 질문 횟수를 초기화하지 않는다.

기존 `/assistant/analyze`의 `reviewFields`는 초안 정보다. 기본 `/assistant/intake`에서는 관리자 검토로 접수할 때 이 항목을 사람이 읽을 수 있는 `reviewNote`로 저장하고 `NEEDS_REVIEW`를 강제한다. **`MANUAL_REVIEW`(초안 질문 종료)와 `NEEDS_REVIEW`(저장 후 배정 상태)는 서로 다른 상태다.** 수동 `/reports`도 `reviewNote`를 받을 수 있다.

분석 결과 공통 필드:

```text
intent, report, questionAnswer, routingPreview, aiMode
report = category, subcategory, locationText?, building?, floor?, symptom?,
         occurredAtText?, missingFields[], followUpQuestion?, emergencyHint
routingPreview = routeStatus, routingRuleId?, departmentName?, contactPhone?, sourceUrl?, verified
questionAnswer = text, sourceUrl?, departmentName?, contactPhone?, verified
```

- REPORT는 학생이 수정·확인 후 별도 생성 API를 호출해야 접수된다.
- QUESTION은 `report=null`, `routingPreview=null`. 답변 근거가 없으면 답변 불가 텍스트와 `verified:false`를 반환하며 신고는 생성되지 않는다.
- UNKNOWN은 학생의 명시적 목적 선택을 기다리며 자동 저장되지 않는다.
- AI 제공자가 생성한 부서명을 사용하지 않는다. `routingPreview`와 답변 문의처는 서버의 검증된 규칙 조회 결과다.
- DEMO는 규칙 기반 분석이다. `aiMode=LIVE`는 실제 설정된 공급자 호출 경로를 뜻하되, 품질·정확도 검증 결과와는 별개다. 실패 시 `503 AI_UNAVAILABLE`이며 수동 신고 경로가 유지된다.

신고 생성 요청:

```json
{
  "clientRequestId":"c18a2bd4-1365-4c96-ac0f-4432274a5fb1",
  "originalText":"북악관 502호에서 와이파이가 계속 끊겨요.",
  "category":"IT","subcategory":"WIFI",
  "locationText":"북악관 502호","building":"북악관","floor":"5",
  "symptom":"와이파이 연결 불안정","occurredAtText":null,"occurredOn":null
}
```

`originalText`는 5~5000자, `symptom`은 필수 최대 500자. 위치 200자, 건물 100자, 층 20자, 발생 시점 설명 120자. `occurredOn`은 서울 기준 미래가 아닌 날짜 또는 null이다. 원문 전체는 별도로 보존된다.

서버가 학생 신원·UUID·허용 유형·규칙을 재검증한다. `studentId`, `departmentId`, `routingRuleId`를 생성 요청으로 지정할 수 없다. 일반 문의·학적 요청은 장소 없이 유형 규칙을 사용할 수 있다. 그 외 신고는 위치 또는 건물이 비어 있거나 검증된 규칙이 없으면 부서 null과 `NEEDS_REVIEW`. `reviewNote`가 있으면 유형과 무관하게 관리자 검토로 접수한다. 같은 학생의 동일 UUID 재전송은 409이며 1건만 저장된다. 저장 후 Embedding 작업의 실패는 접수 트랜잭션을 롤백하지 않는다.

`ReportDetail`의 프런트엔드 소비 필드:

```text
id, originalText, category, subcategory, locationText?, building?, floor?, symptom,
occurredAtText?, occurredOn?, emergencyHint, incidentId?, status, routeStatus,
assignedDepartment?, resolutionText?, createdAt, updatedAt, requestKind, reviewNote?
assignedDepartment = id, name, contactPhone?, sourceUrl?, verified
```

관리자 상세는 `events:[{eventType,oldValue?,newValue?,note?,changedAt,actorName}]`를 추가한다. 신고 상세에서 `embeddingJson`, `clientRequestId`는 제외된다.

## 관리자 상태·사건 계약

- 신고 상태는 `RECEIVED → IN_PROGRESS → RESOLVED`만 허용한다. 완료에는 최대 5000자의 비어 있지 않은 `resolutionText`가 필수다.
- REPORT 처리 시작 시 연결 사건이 없으면 사건을 생성한다. QUESTION은 사건을 생성하거나 연결하지 않는다. 연결된 신고가 모두 결과와 함께 완료되어야 사건이 RESOLVED가 된다.
- 재배정 입력은 활성·검증된 `routingRuleId`이며 규칙과 부서 ID를 함께 갱신한다. 신고 유형 자체를 바꾸는 API는 아니다.
- 동일 사건 승인은 자동 병합이 아니다. 미연결 쌍은 사건을 생성하고, 한쪽만 열린 사건에 연결되어 있으면 다른 쪽도 연결한다. 이미 서로 다른 사건에 속하거나 종료된 사건으로 합치려 하면 409다.
- pair 검토 입력은 `SAME_INCIDENT|RELATED_DISTINCT|REJECTED`. 최종 결정 이후 같은 값 재전송은 허용되지만 다른 결정으로 덮어쓰려 하면 충돌 처리한다.

유사 후보 응답:

```text
{reportId, items:[{
  pairId, relatedReportId, similarityScore?, reasonText, decision, reviewedBy?, reviewedAt?,
  aiMode, relatedReport:{id,originalText,locationText?,status,incidentId?,occurredOn?}
}], embeddingMode, threshold, windowDays, thresholdValidated:false}
```

실제 비교 가능한 벡터가 없는 후보의 점수는 null, 개별 `aiMode`는 RULE_ONLY다. 실제 벡터가 있고 모델명·차원·값이 정합하면 코사인 점수를 반환한다. 전역 Embedding 설정이 LIVE여도 개별 후보는 RULE_ONLY일 수 있다. 임계값은 검증 완료된 정확도 주장이 아니다.

사건 상세:

```text
id, category, subcategory, building?, floor?, status, previousIncidentId?, recurrenceDecision,
createdAt, closedAt?, recurrenceReviewedBy?, recurrenceReviewedAt?,
reports[], reportIds[], reportCount, timeBasis, reasonText, events[], previousIncident?
```

반복 후보 항목:

```text
kind = INCIDENT | UNCONFIRMED_REPORTS
incidentId?, category, subcategory, building?, floor?, reportCount, reportIds[],
incidentStatus?, previousIncidentId?, recurrenceDecision, reasonText, timeBasis
```

반복 응답의 `aiMode`는 RULE_ONLY다. 미연결 신고의 임시 후보는 `incidentId=null`. 사건을 생성한 뒤 재발 판단을 저장한다. 재발 판단은 `{previousIncidentId,decision:"CONFIRMED"|"REJECTED"}`이며 이전 사건은 종료되어야 한다. 유형·장소·시간 관계가 모순되면 422. 날짜 정밀도만으로는 종료 당일 내 선후를 판정하지 않는다. 발생일 미확인 시 접수 시각만으로 비교하므로 `REPORTED_AT_ONLY`와 불확실성을 표시한다.

## 오류 코드

| HTTP | 코드 | 의미 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 잘못된 JSON·입력·유형·날짜·필터·ID |
| 401 | `UNAUTHENTICATED` | 로그인 필요·세션 만료·계정 비활성/권한 변경 |
| 403 | `FORBIDDEN` | 역할 부족 또는 CSRF 실패 |
| 404 | `REPORT_NOT_FOUND` | 신고 없음·타인 신고·존재하지 않는 유사쌍 |
| 404 | `INCIDENT_NOT_FOUND` | 사건 없음 |
| 409 | `DUPLICATE_REQUEST` | 동일 접수 요청 UUID 중복 |
| 409 | `INVALID_STATUS_TRANSITION` | 허용하지 않는 상태 전이 |
| 409 | `INCIDENT_CONFLICT` | 사건 병합·연결·동시 변경 충돌 |
| 422 | `ROUTING_RULE_INVALID` | 검증된 활성 배정 규칙이 아님 |
| 422 | `INVALID_RECURRENCE_REFERENCE` | 이전 사건·시점 관계 불일치 |
| 503 | `AI_UNAVAILABLE` | AI 호출·출력 검증 실패 |
| 503 | `DATABASE_UNAVAILABLE` | DB 처리 실패 |

프런트엔드 `NETWORK_ERROR`는 HTTP 응답 없이 네트워크 호출이 실패했을 때 생성하는 클라이언트 오류다. 프리뷰 프록시의 `HOST_REJECTED`, `ORIGIN_REJECTED`, `PATH_REJECTED`, `UPSTREAM_UNAVAILABLE`, `UPSTREAM_TIMEOUT` 등은 애플리케이션 REST 오류와 구분한다.

## 사진 첨부 API (V5/V6)

신고가 저장된 뒤 받은 ID를 사용한다. 신고 생성 계약은 그대로이며 사진이 실패해도 기존 신고를 재생성하지 않는다. 사진 AI 분석은 없다. 로그인 세션이 필요하고 POST/DELETE에는 기존 CSRF 헤더를 붙인다.

| 메서드 | 경로 | 성공 / 권한 |
|---|---|---|
| POST | `/api/reports/{id}/attachments` | 201 / 학생 본인 신고 |
| GET | `/api/reports/{id}/attachments` | 200 `{items,maxCount}` / 학생 본인 신고 |
| GET | `/api/reports/{id}/attachments/{attachmentId}/content` | 200 이미지 바이트 / 학생 본인 신고 |
| DELETE | `/api/reports/{id}/attachments/{attachmentId}` | 204 / 본인 사진, 완료 후에도 가능 |
| GET | `/api/admin/reports/{id}/attachments` | 200 목록 / 총괄 또는 현재 배정 부서 |
| GET | `/api/admin/reports/{id}/attachments/{attachmentId}/content` | 200 이미지 / 총괄 또는 현재 배정 부서 |

- 업로드: `multipart/form-data`의 `file` 필드에 정확히 1개. 브라우저 `FormData` 사용 시 Content-Type을 직접 지정하지 않는다. 기존 JSON용 공통 요청 함수를 그대로 쓰면 안 된다.
- JPEG/PNG만 허용(내용 검사), 파일당 5MB·요청 6MB·신고당 3장·최대 4,000만 화소. 완료된 신고에는 추가할 수 없다. EXIF/GPS/원본 파일명은 보존하지 않으며 JPEG 방향을 픽셀에 반영한다.
- 첨부 정보: `id`, `reportId`, `contentType`, `sizeBytes`, `width`, `height`, `createdAt`, `contentPath`. 저장 키·원본 파일명은 반환하지 않는다. `contentPath`를 같은 출처의 이미지 주소로 사용한다. 관리자에게는 관리자 경로가 반환된다.
- 이미지 응답은 private/no-store. 다른 학생이나 이관 후 이전 부서는 404로 접근이 차단된다. 일반 역할·CSRF 오류는 기존 403 규칙을 따른다.
- 주요 오류: 400 `ATTACHMENT_ONE_PER_REQUEST`/`ATTACHMENT_INVALID_IMAGE`/`ATTACHMENT_TOO_MANY_PIXELS`, 413 `ATTACHMENT_TOO_LARGE`, 415 `ATTACHMENT_UNSUPPORTED_TYPE`, 409 `ATTACHMENT_LIMIT_EXCEEDED`/`ATTACHMENT_REPORT_CLOSED`, 503 `ATTACHMENT_STORAGE_FAILED`.
- 삭제 204는 첨부 접근 차단과 DB 삭제가 확정됐다는 뜻이다. 디스크 삭제 실패 시 V6 장부에 남겨 재처리하며 신고 내용·처리 결과는 유지한다. 실제 파일 정리 시점과 제한은 [운영 메모](report-attachments.md)를 따른다.
- API 통신 단절 시 업로드 결과가 불명확할 수 있으므로 목록을 다시 확인한 뒤 재시도를 안내한다. 사진 업로드 자체의 멱등성 키는 현재 없다.
