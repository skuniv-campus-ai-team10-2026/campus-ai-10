# AI quality v2 — LIVE

실행: 2026-10-12T17:30:54.399242+09:00

기존 LIVE 결과를 보존하고 같은 합성 평가셋을 재사용했습니다. 독립 품질 검증이 아닌 오류 분석 후 개발셋 비교입니다. 캐시는 실제 공급자 결과이며 DEMO가 아닙니다. JUnit 성공과 개별 품질 PASS를 구분합니다.

원래 벡터는 보존되지 않았으므로 새 고유 입력 벡터로 원점수를 재구성했습니다. 원래 실행의 점수라고 해석하지 마세요.

## before

```json
{
  "sentences" : {
    "status" : "MEASURED",
    "count" : 30,
    "allFieldsCorrect" : 26,
    "fieldCorrect" : {
      "intent" : 29,
      "category" : 27,
      "subcategory" : 27,
      "building" : 30,
      "floor" : 30,
      "emergencyHint" : 30,
      "symptomPresence" : 30,
      "locationPresence" : 30
    },
    "errors" : 0,
    "unexpectedAuthorityFieldCount" : 0,
    "intentConfusionMatrix" : {
      "QUESTION" : {
        "QUESTION" : 8,
        "UNKNOWN" : 1
      },
      "REPORT" : {
        "REPORT" : 18
      },
      "UNKNOWN" : {
        "UNKNOWN" : 3
      }
    }
  },
  "pairs" : {
    "status" : "MEASURED",
    "count" : 20,
    "truePositive" : 2,
    "falsePositive" : 0,
    "trueNegative" : 11,
    "falseNegative" : 7,
    "errors" : 0,
    "precision" : 1.0,
    "recall" : 0.2222222222222222,
    "storedEmbeddingCount" : 40,
    "threshold" : 0.82,
    "windowDays" : 30
  }
}
```

## sentences

```json
{
  "count" : 30,
  "allFieldsCorrect" : 29,
  "errors" : 0,
  "fieldCorrect" : {
    "intent" : 30,
    "category" : 30,
    "subcategory" : 30,
    "building" : 30,
    "floor" : 29,
    "emergencyHint" : 30,
    "symptomPresence" : 30,
    "locationPresence" : 30,
    "authorityBoundary" : 30
  }
}
```

| ID | 수정 전 | 수정 후 | 변화 | 실패 필드 / 제외 원인 |
|---|---|---|---|---|
| S01 | PASS | PASS | UNCHANGED_PASS | [] |
| S02 | PASS | PASS | UNCHANGED_PASS | [] |
| S03 | PASS | PASS | UNCHANGED_PASS | [] |
| S04 | PASS | PASS | UNCHANGED_PASS | [] |
| S05 | PASS | PASS | UNCHANGED_PASS | [] |
| S06 | PASS | PASS | UNCHANGED_PASS | [] |
| S07 | PASS | PASS | UNCHANGED_PASS | [] |
| S08 | PASS | PASS | UNCHANGED_PASS | [] |
| S09 | PASS | PASS | UNCHANGED_PASS | [] |
| S10 | PASS | PASS | UNCHANGED_PASS | [] |
| S11 | PASS | PASS | UNCHANGED_PASS | [] |
| S12 | PASS | PASS | UNCHANGED_PASS | [] |
| S13 | PASS | PASS | UNCHANGED_PASS | [] |
| S14 | PASS | PASS | UNCHANGED_PASS | [] |
| S15 | PASS | PASS | UNCHANGED_PASS | [] |
| S16 | PASS | PASS | UNCHANGED_PASS | [] |
| S17 | PASS | PASS | UNCHANGED_PASS | [] |
| S18 | PASS | PASS | UNCHANGED_PASS | [] |
| S19 | PASS | PASS | UNCHANGED_PASS | [] |
| S20 | FAIL | PASS | IMPROVED | [] |
| S21 | PASS | PASS | UNCHANGED_PASS | [] |
| S22 | PASS | PASS | UNCHANGED_PASS | [] |
| S23 | FAIL | PASS | IMPROVED | [] |
| S24 | PASS | PASS | UNCHANGED_PASS | [] |
| S25 | PASS | FAIL | REGRESSION | ["floor"] |
| S26 | PASS | PASS | UNCHANGED_PASS | [] |
| S27 | FAIL | PASS | IMPROVED | [] |
| S28 | PASS | PASS | UNCHANGED_PASS | [] |
| S29 | FAIL | PASS | IMPROVED | [] |
| S30 | PASS | PASS | UNCHANGED_PASS | [] |

## pairs

```json
{
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
```

| ID | 수정 전 | 수정 후 | 변화 | 실패 필드 / 제외 원인 |
|---|---|---|---|---|
| P01 | FAIL | FAIL | UNRESOLVED | ["EMBEDDING_THRESHOLD"] |
| P02 | FAIL | FAIL | UNRESOLVED | ["EMBEDDING_THRESHOLD"] |
| P03 | FAIL | FAIL | UNRESOLVED | ["EMBEDDING_THRESHOLD"] |
| P04 | FAIL | FAIL | UNRESOLVED | ["EMBEDDING_THRESHOLD"] |
| P05 | FAIL | FAIL | UNRESOLVED | ["EMBEDDING_THRESHOLD"] |
| P06 | FAIL | FAIL | UNRESOLVED | ["EMBEDDING_THRESHOLD"] |
| P07 | PASS | PASS | UNCHANGED_PASS | [] |
| P08 | FAIL | FAIL | UNRESOLVED | ["EMBEDDING_THRESHOLD"] |
| P09 | PASS | PASS | UNCHANGED_PASS | [] |
| P10 | PASS | PASS | UNCHANGED_PASS | ["BUILDING","EMBEDDING_THRESHOLD"] |
| P11 | PASS | PASS | UNCHANGED_PASS | ["FLOOR","ROOM","EMBEDDING_THRESHOLD"] |
| P12 | PASS | PASS | UNCHANGED_PASS | ["ROOM","EMBEDDING_THRESHOLD"] |
| P13 | PASS | PASS | UNCHANGED_PASS | ["TYPE","EMBEDDING_THRESHOLD"] |
| P14 | PASS | PASS | UNCHANGED_PASS | ["TYPE","EMBEDDING_THRESHOLD"] |
| P15 | PASS | PASS | UNCHANGED_PASS | ["TYPE","EMBEDDING_THRESHOLD"] |
| P16 | PASS | PASS | UNCHANGED_PASS | ["BUILDING","EMBEDDING_THRESHOLD"] |
| P17 | PASS | PASS | UNCHANGED_PASS | ["TIME_WINDOW"] |
| P18 | PASS | PASS | UNCHANGED_PASS | ["EMBEDDING_THRESHOLD"] |
| P19 | PASS | PASS | UNCHANGED_PASS | ["EMBEDDING_THRESHOLD"] |
| P20 | PASS | PASS | UNCHANGED_PASS | ["FLOOR","EMBEDDING_THRESHOLD"] |

## thresholdSweep

```json
[ {
  "count" : 20,
  "truePositive" : 9,
  "falsePositive" : 2,
  "trueNegative" : 9,
  "falseNegative" : 0,
  "errors" : 0,
  "precision" : 0.8181818181818182,
  "recall" : 1.0,
  "threshold" : 0.5,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 9,
  "falsePositive" : 2,
  "trueNegative" : 9,
  "falseNegative" : 0,
  "errors" : 0,
  "precision" : 0.8181818181818182,
  "recall" : 1.0,
  "threshold" : 0.55,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 9,
  "falsePositive" : 2,
  "trueNegative" : 9,
  "falseNegative" : 0,
  "errors" : 0,
  "precision" : 0.8181818181818182,
  "recall" : 1.0,
  "threshold" : 0.6,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 9,
  "falsePositive" : 2,
  "trueNegative" : 9,
  "falseNegative" : 0,
  "errors" : 0,
  "precision" : 0.8181818181818182,
  "recall" : 1.0,
  "threshold" : 0.65,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 9,
  "falsePositive" : 2,
  "trueNegative" : 9,
  "falseNegative" : 0,
  "errors" : 0,
  "precision" : 0.8181818181818182,
  "recall" : 1.0,
  "threshold" : 0.7,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 6,
  "falsePositive" : 2,
  "trueNegative" : 9,
  "falseNegative" : 3,
  "errors" : 0,
  "precision" : 0.75,
  "recall" : 0.6666666666666666,
  "threshold" : 0.72,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 6,
  "falsePositive" : 1,
  "trueNegative" : 10,
  "falseNegative" : 3,
  "errors" : 0,
  "precision" : 0.8571428571428571,
  "recall" : 0.6666666666666666,
  "threshold" : 0.74,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 5,
  "falsePositive" : 1,
  "trueNegative" : 10,
  "falseNegative" : 4,
  "errors" : 0,
  "precision" : 0.8333333333333334,
  "recall" : 0.5555555555555556,
  "threshold" : 0.76,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 3,
  "falsePositive" : 0,
  "trueNegative" : 11,
  "falseNegative" : 6,
  "errors" : 0,
  "precision" : 1.0,
  "recall" : 0.3333333333333333,
  "threshold" : 0.78,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 2,
  "falsePositive" : 0,
  "trueNegative" : 11,
  "falseNegative" : 7,
  "errors" : 0,
  "precision" : 1.0,
  "recall" : 0.2222222222222222,
  "threshold" : 0.8,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 2,
  "falsePositive" : 0,
  "trueNegative" : 11,
  "falseNegative" : 7,
  "errors" : 0,
  "precision" : 1.0,
  "recall" : 0.2222222222222222,
  "threshold" : 0.82,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 2,
  "falsePositive" : 0,
  "trueNegative" : 11,
  "falseNegative" : 7,
  "errors" : 0,
  "precision" : 1.0,
  "recall" : 0.2222222222222222,
  "threshold" : 0.84,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 1,
  "falsePositive" : 0,
  "trueNegative" : 11,
  "falseNegative" : 8,
  "errors" : 0,
  "precision" : 1.0,
  "recall" : 0.1111111111111111,
  "threshold" : 0.86,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 0,
  "falsePositive" : 0,
  "trueNegative" : 11,
  "falseNegative" : 9,
  "errors" : 0,
  "precision" : null,
  "recall" : 0.0,
  "threshold" : 0.88,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 0,
  "falsePositive" : 0,
  "trueNegative" : 11,
  "falseNegative" : 9,
  "errors" : 0,
  "precision" : null,
  "recall" : 0.0,
  "threshold" : 0.9,
  "externalCalls" : 0
}, {
  "count" : 20,
  "truePositive" : 0,
  "falsePositive" : 0,
  "trueNegative" : 11,
  "falseNegative" : 9,
  "errors" : 0,
  "precision" : null,
  "recall" : 0.0,
  "threshold" : 0.95,
  "externalCalls" : 0
} ]
```

## providerMeasurement

```json
{
  "mode" : "LIVE",
  "model" : "gpt-6-luna",
  "embeddingModel" : "text-embedding-3-small",
  "analyzeAttempts" : 30,
  "embeddingAttempts" : 21,
  "externalCallsAttempted" : 51,
  "analysisCacheHits" : 0,
  "embeddingCacheHits" : 19,
  "newCallFailures" : 0,
  "maximumCumulativeExternalCalls" : 51,
  "persistedAttemptsIncludingPreviousRuns" : 51,
  "cacheDirectory" : ".runtime/ai-quality-cache/v2",
  "newCallsUsage" : {
    "/chat/completions" : {
      "responsesWithUsage" : 30,
      "inputTokens" : 24888,
      "outputTokens" : 2582,
      "totalTokens" : 27470
    },
    "/embeddings" : {
      "responsesWithUsage" : 21,
      "inputTokens" : 1473,
      "outputTokens" : 0,
      "totalTokens" : 1473
    }
  },
  "usageNote" : "Only new provider responses counted here; cache hits incur no new API call or token charge. Persisted cache entries retain original response usage."
}
```

쌍별 rawCosine, 장소 정규화, 유형/시간 게이트, 전체 before/after 출력은 동명의 JSON에 있습니다. 임계값 sweep은 추가 API 호출 없이 계산하며 자동으로 운영 임계값을 변경하지 않습니다.
