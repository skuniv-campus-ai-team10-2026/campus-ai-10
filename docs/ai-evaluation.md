# AI 구현 및 검증 범위

**2026-10-13 최종 갱신:** 아래는 초기 구현·평가 기록이다. 최신 AI 계약 테스트는 22개, 전체 백엔드 93개 성공이다. 실제 재측정 29/30과 저장 응답의 층 정규화 30/30, 저장 벡터의 TP9/FP0는 [품질 비교](ai-quality-comparison.md)에 구분했다. [LIVE 브라우저/MySQL E2E](live-browser-verification.md)도 완료했다. 같은 개발셋의 결과로 독립 운영 정확도를 주장하지 않는다.

2026-10-11 추가 검증: AI 스텁 테스트는 출력 예산/usage 검증을 포함해 **17개 통과**했다. 새 고정 합성셋의 DEMO 전체 필드 일치는 **9/30**, RULE_ONLY 쌍 오탐 **2/20**이었다. 이후 실제 분석 30회·임베딩 40회가 성공했고 LIVE 전체 필드는 **26/30**, 유사 후보 TP2/FP0/TN11/FN7이었다. [DEMO 상세](holdout-verification.md), [실제 AI 상세](live-ai-verification.md), [모델·키 설정](ai-model-selection.md)을 함께 읽어야 한다. 아래 초기 기록을 독립 운영 정확도로 해석하지 않는다.

`AiService`는 외부 호출 없는 `AI_MODE=DEMO`와 실제 공급자 연결을 위한 `AI_MODE=LIVE`를 분리한다. DEMO는 입력 키워드와 정규식으로 의도·유형·건물·층을 추출한다. 고정된 단일 분석 결과를 반환하지 않지만 생성형 AI는 아니며 자유 문장 전체를 이해하지 못한다. API와 화면에는 DEMO가 표시되어야 한다.

LIVE 구현은 OpenAI 호환 `/chat/completions`의 `response_format.json_schema`를 사용한다. `strict=true`, 객체의 추가 속성 금지, 허용 enum과 유형 조합 재검증, 길이 제한, duplicate JSON 키 거절, 종료 사유/거절 응답 검사, 시간 제한을 적용한다. 사용자 원문은 JSON 데이터로 별도의 user 메시지에 담고 시스템 지침과 분리한다. 프롬프트 및 모델 출력은 DB 조회·라우팅·권한 결정 권한을 갖지 않는다. 검증된 담당 부서와 연락처 응답은 서버의 DB 조회에서 별도로 구성한다.

추가 질문의 `previousReport`는 허용 필드·값·길이를 재검증한다. DEMO는 원문과 직접 추가한 답변을 다시 추출하고 클라이언트가 제공한 누락/위험 플래그를 신뢰하지 않는다. 누락 위치는 질문하고, 후속 답변 이후 반복 자동 질문 대신 수동 보완을 허용한다. 긴급 키워드는 모델이 false를 내더라도 서버 안내 신호를 켠다. 실제 긴급 상황 판정이나 신고 전송은 하지 않는다.

환경 변수는 `AI_MODE`, `AI_BASE_URL`(기본 `https://api.openai.com/v1`), `AI_API_KEY`, `AI_MODEL`, `AI_TIMEOUT_SECONDS`(기본 20초), `EMBEDDING_MODE=RULE_ONLY|LIVE`, `EMBEDDING_MODEL`이다. 모델 기본값은 공란이므로 LIVE를 사용할 때 구조화 출력 지원 모델과 접근 가능한 임베딩 모델을 명시해야 한다. API 키와 모델이 없으면 503으로 실패하며 조용히 DEMO로 바꾸지 않는다. 원문·키·외부 오류 응답을 로그나 사용자 오류 메시지로 출력하지 않는다. 전송은 HTTPS가 기본이며 로컬 시험 서버에 한하여 HTTP를 허용한다.

임베딩은 `EMBEDDING_MODE=RULE_ONLY`에서 `null`을 반환하고 벡터나 점수를 꾸며내지 않는다. LIVE는 `/embeddings`에 `encoding_format=float`를 요청하고 유한한 비영벡터, 응답 인덱스·모델을 검사한다. 실제 응답 모델을 벡터와 함께 반환한다. 저장된 신고의 사후 분석에서 호출해야 하며 실패가 접수 저장을 롤백하면 안 된다.

공식 API 계약은 [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)와 [Create embeddings](https://developers.openai.com/api/reference/resources/embeddings/methods/create)를 2026-10-10 직접 조회하여 확인했다. 실제 계정 모델 가용성·품질·과금은 별도 확인 대상이다.

## 자동 검증

`AiServiceTest`는 DEMO 추출·추가 질문·명시적 의도·입력 변조 거절과 로컬 HTTP 스텁 기반 LIVE 요청/응답 계약을 검증한다. 스텁 테스트는 실제 공급자의 모델 성능 시험이 아니다. HTTP 오류, JSON 오류, 잘못된 유형 조합, 추가 속성, 출력 거절·잘림, 시간 초과, 임베딩 응답과 비정상 벡터, 키 미설정이 검증 대상이다.

`AiEvaluationTest`에는 30개 합성 문장과 사전 기대값이 있다. 라벨은 구현 에이전트가 작성했고 사람의 독립 검수는 미완료다. 테스트 실행 시 `backend/build/reports/ai-evaluation/demo-results.json` 및 `.md`에 전체 입력·기대값·실제값·PASS/FAIL·원인·혼동 행렬을 기록한다. FAIL도 숨기지 않고 보고하며 정답을 구현 결과에 맞춰 바꾸지 않는다. DEMO 규칙과 중첩된 소규모 예제이므로 일반화 성능 주장이 불가능하다. 실제 측정 전 수치를 기재하지 않는다.

실행 예: `cd backend; .\gradlew.bat test --tests 'edu.skope.ai.*'`. 프로젝트 전체 실행 환경과 wrapper 경로는 루트 README를 기준으로 한다.

첫 실행에서 30개 합성 문장 중 전체 측정 필드 일치 26/30, 의도 27/30, 유형 29/30, 건물 30/30이었다. 관찰된 실패인 ‘연결되지 않아요’, ‘켜지지 않아요’, ‘멈췄어요’, ‘빗물이 스며들어요’의 DEMO 규칙을 보완했다. 동일 평가셋을 규칙 수정에 사용했으므로 재실행 결과는 회귀 검증 수치이며 독립 평가 성능이 아니다.

2026-10-11 10:07 KST 초기 DEMO 회귀 실행에서는 의도·유형·건물·긴급 신호 각각 30/30, 모든 측정 필드 일치 30/30이었다. [전체 입력·기대값·실제 결과](evaluation/demo-results.md)를 보존했다. 당시 `AiServiceTest` 16개와 `AiEvaluationTest` 1개도 통과했다. 이 DEMO 실행의 실제 공급자 호출은 0회이며 실제 LLM 또는 Embedding 정확도를 측정한 결과가 아니다. 이후 별도 고정셋으로 진행한 실제 AI 호출 결과는 문서 상단과 [LIVE 검증 기록](live-ai-verification.md)에 구분했다.
