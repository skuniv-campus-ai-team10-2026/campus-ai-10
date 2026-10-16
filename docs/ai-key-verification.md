# API 키 입력·접근 확인

확인일: 2026-10-11 KST. 키 원문을 출력하거나 문서·소스에 복사하지 않았다.

## 최신 결과 — 권한 변경 후 23시 재검증

두 모델 조회가 **HTTP 200**으로 성공했다. [재조회 기록](evaluation/ai-key-preflight-after-permission.json)

이어 실제 `gpt-6-luna` 분석 **30/30회**, `text-embedding-3-small` 임베딩 **40/40회**가 성공했다. 키·모델·두 API의 호출 권한은 실제 동작으로 확인했다. 키 원문은 출력하지 않았고 합성 입력만 전송했다. 출력/로그 3개 파일에서 실제 키 문자열 일치도 0건이었다.

평가의 전체 필드 일치는 26/30, 유사 후보 recall은 2/9다. 키가 정상이라는 것과 품질 기준 충족은 다르다. [실제 AI 측정](live-ai-verification.md)

이전의 자동 승인 검토 한도는 이번 실행을 막지 않았다. 아래는 원인을 추적하기 위해 남긴 **14시의 이전 기록**이다.

## 이전 확인 결과

- `.runtime/ai-verification.env` 존재, 키 값 입력됨, OpenAI 키 형태 일치, 따옴표·공백·중복 설정·잘못된 설정 행 없음.
- base URL은 공식 `https://api.openai.com/v1`, 모델은 `gpt-6-luna` / `text-embedding-3-small`, reasoning none / 출력 상한 1536.
- 두 모델에 대한 인증 헤더 포함 모델 조회는 각각 **HTTP 403**이었다. [조회 결과](evaluation/ai-key-preflight.json)
- LLM 모델 조회를 진단한 JSON 오류에서 **`Missing scopes: api.model.read`**를 확인했다. 키 또는 해당 프로젝트 역할의 모델 조회 권한이 부족하다. 모델이 존재하지 않는다는 오류로 해석하지 않는다.
- 조회/진단 GET은 총 4회 실행했다. 신고 원문 전송, Chat Completions 생성, Embeddings 생성은 **0회**다. 키의 분석/임베딩 권한, 선택 모델의 실제 호출 가능성, 결제 잔액·과금은 미검증이다.

키 파일 저장은 정상이다. 다만 모델 조회 권한과 모델 생성 권한은 별개이므로 이번 403만으로 앱의 두 생성 API가 모두 불가능하다고 단정하지 않는다.

## 필요한 설정 확인

[API 키 관리](https://platform.openai.com/api-keys)에서 해당 프로젝트와 키의 권한을 확인한다.

1. 모델 조회 검증용 **List models → Read (`api.model.read`)**.
2. 앱의 Chat Completions·Embeddings 사용을 위한 **Model capabilities → Request**. 제한 키의 엔드포인트별 설정을 쓴다면 `/v1/chat/completions`와 `/v1/embeddings` 요청 허용 여부를 확인한다.
3. 키 권한뿐 아니라 그 키를 사용하는 계정의 프로젝트 역할에도 필요한 권한이 있어야 한다. 직접 수정할 수 없으면 프로젝트 관리자에게 해당 범위만 요청한다.

이 권한 관계는 [OpenAI 공식 RBAC 문서](https://developers.openai.com/api/docs/guides/rbac)에서 확인했다. 관리자 전체 권한이나 모든 API 권한을 일괄 부여할 필요는 없다. 키 값이 바뀌지 않았다면 로컬 파일을 다시 붙여 넣을 필요도 없다.

## 이전 추가 호출의 실행 제한 — 이번 실행에서는 해소

작은 합성 입력으로 Chat Completions와 Embeddings를 각각 한 번 검사하려 했으나, **Codex 자동 승인 검토의 사용량 한도 때문에 명령 자체가 실행되지 않았다.** 이는 OpenAI API의 429/결제 오류도, 해당 요청의 안전성 거절도 아니다. 다른 실행 경로로 승인 검토를 우회하지 않았다. 권한 설정과 실행 환경의 승인 검토 제한이 해결된 후 실제 생성 호출·고정셋 평가를 수행해야 한다.

키 미설정 SKIPPED는 14:09 실행의 역사적 기록으로 [별도 보존](evaluation/holdout-v1-live-before-key.md)했다. 현재 `holdout-v1-live-results`는 23시의 실제 호출 결과다. 이전 결과를 성공으로 소급 변경하지 않았다.
