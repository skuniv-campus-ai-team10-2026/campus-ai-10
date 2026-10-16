# 고정 합성 평가 v1 결과

실행일: 2026-10-11 KST. 기존 구현에 맞춰 보완한 30문장 회귀셋과 별도로 새 문장 30개, 유사 후보 쌍 20개, 사건 시나리오 6개를 준비했다. 작성 에이전트는 이전 구현 경험이 있으므로 **사람이 독립 검수한 평가셋이 아니다.** 실행 전 SHA-256을 고정했으며 측정 결과에 맞춰 라벨·DEMO 규칙·임계값을 수정하지 않았다.

고정 파일: [holdout-v1.json](evaluation/holdout-v1.json)

SHA-256: `7ea381d2fedb7a2ab0f21237457acc5ad51dd049a6ba4086aa13566e9f3a923a`

| 평가 | DEMO / RULE_ONLY 결과 | 해석 |
|---|---|---|
| 문장 30개, 평가 필드 전체 일치 | **9/30 (30.0%)** | 모든 응답이 오류 없이 나왔어도 추출 품질은 낮음 |
| 의도 | 16/30 (53.3%) | 비정형 표현·질문·관찰 장애 구분에 한계 |
| category / subcategory | 각각 23/30 (76.7%) | 누수·전기 동의어 등 누락 |
| building / floor | 29/30, 28/30 | 한글 층수·영문 지하층 등 실패 |
| 긴급 안내 신호 | 29/30 | 우회 표현 1건 누락; 긴급 대응 시스템으로 사용 불가 |
| 필수 symptom / location의 비어 있지 않음 | **9/18, 16/16** | 필수인 사례만 분모로 집계. 문구 의미 정확도 점수가 아님 |
| 유사 후보 20쌍 | TP 9, FP 2, TN 9, FN 0 | precision 81.8%, recall 100%; 임베딩 0개, 순수 규칙 후보 |
| 사건 6개 | 6/6 PASS | H2 서비스 불변조건·관리자 결정 검증이며 AI 추론 성능이 아님 |

RULE_ONLY는 같은 장소·유형의 Wi-Fi 장애와 계정 보안 문제를 후보로 함께 올리는 오탐(P18, P19)을 보였다. 후보는 자동 병합되지 않으므로 관리자 검토를 유지한다. 실제 embedding을 실행하지 않은 결과로 0.82 임계값이 적절하다고 판단할 수 없다.

문장 평가는 intent/category/subcategory/building/floor/emergencyHint 및 symptom/location 존재 여부의 8개 기준이다. 증상 문구의 의미 정밀도, follow-up 품질, 모든 날짜 해석을 종합한 전체 정확도는 아니다.

원본의 symptomPresence 21/30과 locationPresence 30/30에는 해당 값이 필수가 아닌 사례의 자동 충족도 포함된다. 위 표는 보존된 rows에서 필수 사례만 다시 집계한 값이다. 라벨·분석 동작·전체 9/30 판정은 바꾸지 않았다. 쌍 지표는 처리된 20쌍(오류 0)에 대한 값이다. 향후 LIVE에서는 예외·벡터 미확보·RULE_ONLY fallback을 제외한 처리 수와 함께 보고해야 한다. 사례별 elapsedMs는 DB 준비 등을 포함하며 개별 API 지연이 아니다.

## 실제 AI 상태

[14:09의 키 미설정 기록](evaluation/holdout-v1-live-before-key.md)은 SKIPPED로 보존했다. 이후 권한 수정 후 실제 AI를 호출한 [23시 LIVE 기록](evaluation/holdout-v1-live-results.md)을 별도로 생성했다. JUnit 또는 Gradle 성공은 측정 파일을 만들었다는 뜻이며 품질 통과를 뜻하지 않는다.

실제 분석 30/30·임베딩 40/40 성공, 문장 전체 26/30, 유사 후보 TP2/FP0/TN11/FN7이었다. DEMO보다 추출은 개선됐지만 현재 유사도 기준에서 정답 9쌍 중 7쌍이 누락됐다. [최신 LIVE 해석](live-ai-verification.md)

입력·기대·실제·실패 필드·지연은 [DEMO 상세](evaluation/holdout-v1-demo-results.md)와 [JSON](evaluation/holdout-v1-demo-results.json)에 보존했다. 사용법과 호출 한도는 [AI 모델·키 설정](ai-model-selection.md)에 있다.

## 남은 품질 검증

실제 모델 접근·JSON 계약·usage·실패 복구를 먼저 확인하고, 사람 2명이 결과를 보지 않고 라벨을 검수해야 한다. 불일치는 근거와 함께 합의하고 버전을 새로 고정한다. 임계값 조정용 데이터와 최종 평가 데이터를 분리하며 실제 AI 실패나 fallback은 별도 분모로 보고한다. 현재 20쌍으로 운영 정확도나 일반화 성능을 확정하지 않는다.
