# 부서 매칭 보완과 LIVE 사용량 제한

기준일: 2026-10-27. 브랜치 `feature/department-routing-budget` (기준 `main` `8e46408`). 새 유료 AI 호출 0회.

## 1. 추가한 유형과 담당 부서

판정 기준은 [data-sources.md](data-sources.md)와 같다. 공식 URL에서 부서·업무·연락처를 모두 확인한 경우만 "확인됨"이다. 이번 세 유형은 그런 근거를 찾지 못해 확인됨으로 표시하지 않았다.

| 유형 코드 | 화면 표시 이름(제안) | 담당 부서 | 배정 | 근거와 한계 |
|---|---|---|---|---|
| FACILITY/HVAC | 냉난방 | 총무처 건설관리과 | 시연용(`demo_only`) | 사무분장규정의 건설관리과 시설·영선 업무, [냉온수 설비 공사 단수 안내](https://www.skuniv.ac.kr/notice/49669)(건설관리과). 냉난방 접수 담당 명시는 못 찾음 |
| FACILITY/WATER_DISPENSER | 정수기 | 총무처 건설관리과 | 시연용(`demo_only`) | [저수조 청소 단수 안내](https://www.skuniv.ac.kr/272898). 정수기 관리 주체(외부 렌탈 가능성)는 미확인 |
| FACILITY/FURNITURE | 책상·의자 등 집기 | 없음 | 규칙 없음 | 담당 근거를 찾지 못함. "배정 규칙 없음"으로 총괄이 검토 |

- 시연용 규칙은 `DEMO_ROUTING_ENABLED=true`일 때만 배정되고, 연락처를 보여주지 않는다.
- 엘리베이터는 2022년 공지(건설본부)뿐이라 이번에 넣지 않았다. 기타(OTHER/UNKNOWN)를 한 부서로 보내지 않는다.
- 기존 유형(WIFI·ELECTRIC·STUDENT_RECORD 확인됨, LEAK 시연용)은 그대로다. 부서별 접근 권한과 총괄 재배정 흐름도 그대로다.
- **기존 DB 반영**: `SeedData`가 서버를 시작할 때마다 "그 유형의 규칙이 없을 때만" 추가한다. 이미 있으면(관리자가 수정·비활성화한 경우 포함) 건드리지 않는다.
- **AI 분류**: 프롬프트 유형 설명·응답 스키마·DEMO 키워드에 세 유형을 추가하고 프롬프트를 `hybrid-intake-v4`로 올렸다. 고장 난 물건이 담당 부서를 정하므로 "정수기에서 물이 샘", "에어컨에서 물이 떨어짐"은 누수가 아니라 정수기·냉난방이다. 추가 질문·긴급 판단·유사도 임계값은 바꾸지 않았다.

## 2. 미배정 원인 (`unassignedReason`)

관리자(총괄·부서) 신고 목록·상세 응답에만 붙는다. 학생 응답에는 없다. 저장된 값으로 계산하므로 DB 칼럼을 추가하지 않았다. 신고 저장 시 배정을 보류하는 순서와 같은 순서로 판정한다.

| 코드 | 화면 표시(제안) | 조건 |
|---|---|---|
| `null` | - | 담당 부서가 있음(자동 또는 총괄 재배정) |
| `REVIEW_HOLD` | 관리자 검토 보류 | 검토 메모가 있음: 학생 검토 접수, AI 분석 실패, 긴급 검토 |
| `AI_UNCLASSIFIED` | AI 분류 불가 | 유형이 OTHER/UNKNOWN |
| `LOCATION_MISSING` | 장소 확인 필요 | 현장 신고인데 건물·장소가 없어 자동 배정을 보류함 |
| `NO_RULE` | 배정 규칙 없음 | 유형은 알지만 활성 규칙이 없음(시연 배정 꺼짐·부서 비활성 포함) |

- 요청받은 세 원인에 `LOCATION_MISSING`을 더했다. 기존 코드는 건물 없는 현장 신고를 일부러 배정하지 않는데, 이를 "규칙 없음"이나 "AI 분류"로 표시하면 사실과 달라서다.
- "AI 분류 **오류**"는 AI가 틀렸는지 정말 해당 유형이 없는지 서버가 구분할 수 없어 "AI 분류 불가"로 했다.

## 3. LIVE 사용량 제한

### 동작

- LLM과 Embedding 호출은 모두 `AiService.post()`를 지난다. 여기서 **보내기 전에** 최악의 비용을 DB에 예약하고, 응답의 실제 토큰으로 **정산**한다.
- 예약은 조건부 UPDATE 한 문장(`사용량 + 예약분 <= 한도`)이라 동시 요청이 몰려도 한도를 넘지 않는다.
- 한도를 넘거나 사용량 DB에 쓸 수 없으면 네트워크 호출 없이 기존 `AI_UNAVAILABLE` 경로로 간다.
  - 접수 화면: `REVIEW_REQUIRED` + "입력한 내용을 그대로 관리자 검토로 접수할 수 있습니다" → 검토 접수 가능(`REVIEW_HOLD`)
  - 직접 접수(`POST /reports`): AI를 쓰지 않으므로 그대로 가능
  - Embedding: 기존처럼 규칙 기반 유사도(RULE_ONLY)로 대체되고 신고는 저장됨
  - 기존 신고·사진·처리 결과는 영향 없음
- 응답에 사용량이 없으면(실패·형식 오류) 예약액 전체를 사용한 것으로 남긴다. 실패한 호출도 과금됐을 수 있어서다.
- DEMO / RULE_ONLY 모드는 유료 호출이 없으므로 계산하지 않는다.

### 서버 재시작 시

- 사용량은 DB 테이블 `ai_usage_budget`에만 있다. 서버는 메모리에 숫자를 들고 있지 않으므로 **재시작해도 누적값이 그대로이고 한도도 초기화되지 않는다.**
- `TOTAL`은 수동으로 초기화하기 전까지 누적, `DAILY`는 한국 시간 날짜별 새 행이다.
- 응답을 받은 직후 정산 전에 서버가 죽으면 그 호출은 예약액(최악값)으로 남는다. 한도가 조금 더 엄격해질 뿐 초과되지는 않는다.

### 환경변수 (`application.properties` 수정 없음, 코드 기본값)

| 변수 | 기본값 | 의미 |
|---|---|---|
| `AI_BUDGET_PERIOD` | `TOTAL` | `TOTAL` 또는 `DAILY` |
| `AI_BUDGET_MAX_USD` | `2.00` | 예상 비용 상한(추정, 청구서 아님) |
| `AI_BUDGET_MAX_LLM_CALLS` | `500` | LLM 호출 수 상한 |
| `AI_BUDGET_MAX_EMBEDDING_CALLS` | `500` | Embedding 호출 수 상한 |
| `AI_PRICE_INPUT_PER_MTOK` | `0.10` | LLM 입력 100만 토큰당 USD |
| `AI_PRICE_OUTPUT_PER_MTOK` | `0.50` | LLM 출력 100만 토큰당 USD |
| `EMBEDDING_PRICE_PER_MTOK` | `0.02` | Embedding 100만 토큰당 USD |

- 하나라도 닿으면 그 종류의 유료 호출을 막는다. 상한을 `0`으로 두면 해당 종류를 전부 막는다. 음수·잘못된 기간은 서버 시작을 거부한다.
- 가격은 기존 문서의 `gpt-6-luna`·`text-embedding-3-small` 요율이다. 모델을 바꾸면 함께 바꿔야 한다.
- 입력 토큰 예약은 요청 글자 수로 어림해 실제보다 크게 잡는다. 한도 직전에는 실제 남은 금액보다 조금 일찍 막힐 수 있다.
- OpenAI 대시보드의 프로젝트 사용 한도도 함께 걸 것을 권장한다(서버 계산은 추정이라 이중 안전장치).

### 확인과 초기화

- 총괄 관리자: `GET /api/admin/ai-usage` → 기간, 한도, 사용량(호출·토큰·예상 USD), 차단 횟수, `llmBlocked`/`embeddingBlocked`. 부서 담당자·학생은 403.
- 초기화(운영자가 DB에서 직접): `DELETE FROM ai_usage_budget WHERE period_key='TOTAL';` (`DAILY`는 해당 날짜 키)

### DB

`V8__ai_usage_budget.sql` 테이블 1개. **번호는 마이그레이션 담당자가 조정할 수 있다.** 다른 V8이 먼저 병합되면 파일 이름만 바꾸면 된다(내용 의존 없음).

## 4. 검증

| 실행 | 결과 |
|---|---|
| 백엔드 전체 `clean test` H2 | 431건 / 실패 0 / 건너뜀 14(기존 opt-in) |
| 백엔드 전체 `clean test bootJar` MySQL 8.0.45 (`skope_test`) | 431건 / 실패 0 / 건너뜀 14, bootJar 성공 |
| 새 테스트 | 35건 (`RoutingTypesAndReasonsTest` 15, `DepartmentRoutingIntegrationTest` 5, `AiBudgetTest` 8, `AiBudgetIntegrationTest` 7) |

- `AiBudgetIntegrationTest`는 실제 앱을 LIVE 모드로 띄우되, 요청은 테스트가 띄운 **로컬 가짜 OpenAI 서버**로만 간다. 한도 도달, 10개 동시 요청, 실패 응답, 한도 초과 시 검토·직접 접수, 관리자 조회 권한을 확인했다.
- 일부러 코드를 망가뜨려 테스트가 잡는지 확인했다: 한도 무시, `post()`의 예약 제거, DEMO 분류 순서 변경 → 모두 해당 테스트가 실패.
- 기존 테스트 변경: `RoutingServiceTest`의 시연 모드 활성 규칙 수 4 → 6 한 줄(의도한 변경).

## 5. 미검증·후속

- **프롬프트 `hybrid-intake-v4`는 실제 LLM으로 확인하지 않았다.** 제안: 새 유형 3개 × 2문장 + 기존 유형 4문장 = LLM 최대 10회, Embedding 0회, 상한 $0.05(예상 약 $0.003). 고정 기대값을 먼저 커밋한 뒤 실행한다.
- "재시작"은 같은 DB를 쓰는 새 `AiBudget` 객체로 확인했다. 실제 프로세스를 내렸다 올리는 검증은 하지 않았다(사용량은 DB에만 있음).
- 프런트 표시 이름(`ui.tsx` `typeLabels`, 학생 직접 신고 유형 목록 `student.tsx`)과 `unassignedReason` 표시는 프런트 작업이다.
- `docs/api-contract.md`는 공용 문서라 수정하지 않았다. 반영할 내용: 새 유형 3개, 관리자 응답의 `unassignedReason`, `GET /api/admin/ai-usage`.
- 냉난방·정수기 담당 부서를 공식 자료로 확인하면 `demo_only`를 풀고 출처·확인일을 넣으면 된다.
