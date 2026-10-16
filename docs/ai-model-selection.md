# 첫 실제 AI 평가용 모델과 설정

**후속 검증 안내:** 이 문서의 모델·요금 조사와 초기 측정은 2026-10-11 기준이다. 같은 모델로 진행한 후속 품질 개선의 실제 29/30, 캐시 정규화 30/30, 유사 후보 TP9/FP0 및 한계는 [최종 비교](ai-quality-comparison.md)에 있다. 기존 결과 재현에는 `scripts/Test-AiQualityReplay.ps1`을 사용하면 추가 API 호출이 없다.

확인일: 2026-10-11 KST. 아래 추천 조합으로 **실제 분석 30회·임베딩 40회 성공**을 확인했다. 고정 합성셋 전체 필드 일치 26/30, 유사 후보 recall 2/9로 운영 품질까지 승인한 것은 아니다. [최신 키 확인](ai-key-verification.md), [실제 AI 평가](live-ai-verification.md)

| 용도 | 첫 평가 추천 | 이유 |
|---|---|---|
| 의도·유형·장소·증상 구조화 | OpenAI `gpt-6-luna`, `reasoning_effort=none` | 제한된 JSON 추출 작업에 먼저 비용이 낮은 모델을 평가한다. Chat Completions와 Structured Outputs 지원은 공식 문서에서 확인했다. |
| 문장 유사 후보 점수 | OpenAI `text-embedding-3-small` | 짧은 신고 텍스트의 관련도 비교부터 시작한다. 한국어 유사 장애 구분 성능은 고정 평가셋으로 확인해야 한다. |

근거: [GPT-6 Luna 공식 문서](https://developers.openai.com/api/docs/models/gpt-6-luna), [Embedding 모델 공식 문서](https://developers.openai.com/api/docs/models/text-embedding-3-small). 같은 제공자의 두 API를 사용하면 현재 구현을 유지하며 평가할 수 있다는 판단은 프로젝트 코드에 근거한 추천이다. 모델의 일반 성능 소개를 SKOPE 정확도로 바꾸어 해석하지 않는다.

품질이 부족할 경우 실패 유형을 먼저 분리하고, 새 검수셋에서 상위 LLM 및 다른 embedding 모델을 비교한다. 비용이 더 드는 모델로 자동 전환하지 않는다. 모델을 바꾸면 기존 벡터와 섞어 비교하지 말고 다시 생성해야 한다. 현재 RAG·벡터 DB·Fine-tuning·자율 에이전트는 P0에 추가하지 않는다. AI는 담당 부서나 전화번호를 결정하지 않으며 DB 규칙과 관리자 판단을 유지한다.

## 비용의 범위

공식 Standard 요금의 100만 토큰 기준으로 Luna 입력 **$0.10**, 출력 **$0.50**, embedding-small 입력 **$0.02**를 확인했다. [공식 요금](https://developers.openai.com/api/docs/pricing)

예를 들어 요청 1,000건이 각각 입력 2,000·출력 300토큰이면 LLM 기본 토큰 비용 계산은 약 **$0.35**다. 각 신고의 임베딩 입력을 100토큰으로 가정하면 1,000건은 약 **$0.002**다. 이는 캐시·서비스 등급·지역 처리·재시도·세금·환율을 포함하지 않은 가정 계산이며, 실제 청구나 입력 길이를 측정한 견적이 아니다. 출력 상한에는 추론 토큰도 포함된다.

평가 실행기는 최대 **70회**(문장 분석 30 + 쌍별 임베딩 40) 호출, 연속 실패 3회 후 신규 호출 중단, 생성 출력 상한 1,536토큰을 적용한다. API usage를 수집하지만 결제 내역 검증과 동일하지 않다.

이번 실제 usage는 LLM 입력 17,718·출력 2,574토큰, 임베딩 입력 3,145토큰이다. 모두 일반 Standard 기본단가라고 가정하면 약 **$0.0031217**이다. 캐시 읽기/쓰기·등급·지역 가산·세금·환율은 포함하지 않은 계산이며 실제 청구액 확인이 아니다. [계산과 가정](evaluation/live-cost-estimate.json)

## API 키 설정

1. [API 키 관리](https://platform.openai.com/api-keys)에 로그인하고 새 secret key를 생성한다. 전체 키는 생성 시에만 표시되므로 로컬 설정 파일에 저장한다. [공식 키 안내](https://help.openai.com/en/articles/4936850-where-do-i-find-my-openai-api-key)
2. 프로젝트의 `.runtime/ai-verification.env`를 편집하여 `AI_API_KEY=` 뒤에 키를 넣고 저장한다. 키를 채팅·스크린샷·소스 코드에 넣지 않는다. 이 폴더는 `.gitignore` 대상이다.
3. 다른 설정은 아래 값이 준비되어 있다. `AI_REASONING_EFFORT=none`으로 좁은 추출 작업의 첫 평가를 시작한다.

```dotenv
AI_BASE_URL=https://api.openai.com/v1
AI_API_KEY=
AI_MODEL=gpt-6-luna
EMBEDDING_MODEL=text-embedding-3-small
AI_REASONING_EFFORT=none
AI_MAX_COMPLETION_TOKENS=1536
AI_TIMEOUT_SECONDS=30
```

ChatGPT 구독과 API 결제는 별개다. 필요한 경우 [API 결제 설정](https://platform.openai.com/settings/organization/billing/overview)을 완료한다. [공식 결제 구분 안내](https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform)

프로젝트 루트에서 다음 명령을 실행한다. 키 파일은 이 평가 실행기만 로드하며 앱 시작 스크립트가 자동으로 읽지 않는다.

```powershell
.\scripts\Test-AiHoldout.ps1 -Mode DEMO  # 외부 호출 없음
.\scripts\Test-AiHoldout.ps1 -Mode LIVE  # 키가 있으면 유료 API 호출 가능
```

키가 없으면 LIVE를 성공으로 처리하지 않고 `AI_SKIPPED_MISSING_CONFIGURATION` 기록을 생성한다. 호출 실패는 실제 AI 성공으로 계산하지 않으며 임베딩 실패의 RULE_ONLY 대체도 LIVE 점수로 세지 않는다. 원시 오류 응답과 키를 출력하지 않는다.

## 데이터 전송

이번 평가의 입력은 합성 문장뿐이다. 앱 LIVE 모드에서는 신고 텍스트·추가 답변·이전 구조화 내용이 공급자로 전송될 수 있다. `store=false`를 사용하지만 이것이 무보관을 뜻하지는 않는다. 공식 문서에는 악용 방지 로그의 기본 보관 기간과 예외, 모델별 캐시 보관 조건이 따로 있다. 실제 개인정보 입력 전 별도 정책을 결정해야 한다. [공식 데이터 제어 문서](https://developers.openai.com/api/docs/guides/your-data)
