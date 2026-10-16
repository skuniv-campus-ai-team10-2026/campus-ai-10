# AI quality v2 — OFFLINE 재처리

기존 실제 LIVE 출력과 벡터를 재사용했습니다. 추가 API 호출 0회이며 새 LLM 추론·프롬프트 재평가가 아닙니다. 문장은 실제 운영 코드의 floor 정규화만 재처리했고, 유사 후보는 현행 SimilarityService를 H2에서 실행했습니다. DEMO를 사용하지 않았습니다.

## before

```json
{
  "originalSentencesCorrect" : 26,
  "updatedPromptLiveSentencesCorrect" : 29,
  "pairs" : {
    "count" : 20,
    "truePositive" : 2,
    "falsePositive" : 0,
    "trueNegative" : 11,
    "falseNegative" : 7,
    "errors" : 0,
    "precision" : 1.0,
    "recall" : 0.2222222222222222,
    "threshold" : 0.82,
    "windowDays" : 30
  }
}
```

## sentences

```json
{
  "count" : 30,
  "allFieldsCorrect" : 30,
  "outputsChangedByNormalization" : 1,
  "newModelCalls" : 0
}
```

## pairs

```json
{
  "count" : 20,
  "truePositive" : 9,
  "falsePositive" : 0,
  "trueNegative" : 11,
  "falseNegative" : 0,
  "errors" : 0,
  "precision" : 1.0,
  "recall" : 1.0,
  "threshold" : 0.82,
  "windowDays" : 30
}
```

상세 before/after와 게이트·점수·결정 근거는 동명의 JSON에 있습니다. 같은 개발셋을 재사용한 결과이며 독립 품질 검증은 아닙니다. 기존 결과 파일과 공급자 캐시 해시가 변경되지 않았음을 확인했습니다.
