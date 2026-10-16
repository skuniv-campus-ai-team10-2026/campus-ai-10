# Holdout v1 — DEMO

고정 라벨 SHA-256: `7ea381d2fedb7a2ab0f21237457acc5ad51dd049a6ba4086aa13566e9f3a923a`

실행: 2026-10-11T14:06:09.510530+09:00. MEASURED

구현 경험이 있는 에이전트가 별도로 작성한 합성셋이며 사람의 독립 검수가 아닙니다. 결과에 맞춰 라벨이나 코드를 수정하지 않았습니다. 문장·쌍 품질 실패는 아래에 그대로 표시합니다. JUnit 성공은 측정 파일 생성 성공을 뜻합니다.

## sentences

상태: MEASURED

```json
{
  "status" : "MEASURED",
  "count" : 30,
  "allFieldsCorrect" : 9,
  "fieldCorrect" : {
    "intent" : 16,
    "category" : 23,
    "subcategory" : 23,
    "building" : 29,
    "floor" : 28,
    "emergencyHint" : 29,
    "symptomPresence" : 21,
    "locationPresence" : 30
  },
  "errors" : 0,
  "unexpectedAuthorityFieldCount" : 0,
  "intentConfusionMatrix" : {
    "QUESTION" : {
      "QUESTION" : 3,
      "REPORT" : 1,
      "UNKNOWN" : 5
    },
    "REPORT" : {
      "REPORT" : 11,
      "UNKNOWN" : 7
    },
    "UNKNOWN" : {
      "REPORT" : 1,
      "UNKNOWN" : 2
    }
  }
}
```

| 사례 | 기대 근거 | 결과 | 실패 필드/원인 |
|---|---|---|---|
| S01 | 연결 단절을 구체적으로 관찰한 신고 | PASS | [] |
| S02 | 한글 층수와 비정형 고장 표현 | FAIL | ["intent","floor","symptomPresence"] |
| S03 | 무선랜 동의어와 인터넷 이용 불가 | PASS | [] |
| S04 | 담당 부서 문의 | FAIL | ["intent"] |
| S05 | 이용 방법 질문이며 확인된 절차가 없으면 답변 금지 | FAIL | ["intent"] |
| S06 | 명시적 신고 선택 및 무선 장비 문제 | FAIL | ["category","subcategory","symptomPresence"] |
| S07 | 약한 신호로 실제 이용 장애 | FAIL | ["intent","symptomPresence"] |
| S08 | 고장과 질문 의도가 모두 명확하지 않은 설명 | PASS | [] |
| S09 | 배관 누수 | FAIL | ["intent","category","subcategory","symptomPresence"] |
| S10 | 지하층 위치와 천장 누수 | FAIL | ["category","subcategory"] |
| S11 | 창틀 침수도 시설 누수 신고 | FAIL | ["category","subcategory"] |
| S12 | 담당자 문의이며 미검증 연락처 생성 금지 | FAIL | ["intent"] |
| S13 | 사용자 라우팅 지시보다 실제 증상 분류 | FAIL | ["category","subcategory","building","symptomPresence"] |
| S14 | 감전 상황을 우회 표현한 긴급 안내 신호 | FAIL | ["intent","emergencyHint","symptomPresence"] |
| S15 | 분전반 연기와 탄내 | FAIL | ["category","subcategory"] |
| S16 | 조명 불량 신고 | PASS | [] |
| S17 | 검증된 전기 문의처 요청 | PASS | [] |
| S18 | 위치 누락과 콘센트 결함 | FAIL | ["intent","symptomPresence"] |
| S19 | 공식 절차 근거 없는 학적 질문 | FAIL | ["intent"] |
| S20 | 절차 문의와 실제 장애가 모호함 | FAIL | ["intent"] |
| S21 | 사용자가 직접 신고 모드를 선택 | PASS | [] |
| S22 | 명시적 질문 모드 | PASS | [] |
| S23 | 학적 문의처 질문 | PASS | [] |
| S24 | 현재 미지원 기자재 결함 | FAIL | ["intent","symptomPresence"] |
| S25 | 현재 미지원 승강기 문제 | FAIL | ["intent","symptomPresence"] |
| S26 | 검증 자료 밖 일반 질문 | FAIL | ["intent"] |
| S27 | 학교 문의처 근거로 답할 수 없는 범위 밖 질문 | FAIL | ["intent"] |
| S28 | 구체적인 문제·질문 불명 | PASS | [] |
| S29 | 전기 스위치의 파손 | FAIL | ["category","subcategory"] |
| S30 | 영문 지하층 표기 | FAIL | ["floor"] |

## pairs

상태: MEASURED

```json
{
  "status" : "MEASURED",
  "count" : 20,
  "truePositive" : 9,
  "falsePositive" : 2,
  "trueNegative" : 9,
  "falseNegative" : 0,
  "errors" : 0,
  "precision" : 0.8181818181818182,
  "recall" : 1.0,
  "storedEmbeddingCount" : 0,
  "threshold" : 0.82,
  "windowDays" : 30
}
```

| 사례 | 기대 근거 | 결과 | 실패 필드/원인 |
|---|---|---|---|
| P01 | 표현만 다른 동일 장소·유형 장애 | PASS | matches frozen relevance label |
| P02 | 같은 공간의 연결 안정성 장애 | PASS | matches frozen relevance label |
| P03 | 가까운 접수 시점의 같은 장애 후보 | PASS | matches frozen relevance label |
| P04 | 호실 미확인이므로 확정 아닌 검토 후보 | PASS | matches frozen relevance label |
| P05 | 층 누락이지만 같은 호실 | PASS | matches frozen relevance label |
| P06 | 공백·층 접미사 표기 차이 | PASS | matches frozen relevance label |
| P07 | 시간창 안의 유사 후보 | PASS | matches frozen relevance label |
| P08 | 세부 위치가 없어 검토 필요성을 표시할 후보 | PASS | matches frozen relevance label |
| P09 | 30일 실험 시간창 경계 포함 | PASS | matches frozen relevance label |
| P10 | 증상이 같아도 다른 건물 | PASS | matches frozen relevance label |
| P11 | 다른 층과 호실 | PASS | matches frozen relevance label |
| P12 | 같은 층의 다른 호실 | PASS | matches frozen relevance label |
| P13 | 같은 위치의 다른 업무 유형 | PASS | matches frozen relevance label |
| P14 | 같은 위치의 전기 신고 | PASS | matches frozen relevance label |
| P15 | 같은 위치의 미지원 문제 | PASS | matches frozen relevance label |
| P16 | 건물 확인이 없어 동일 장소 후보로 묶지 않음 | PASS | matches frozen relevance label |
| P17 | 30일 실험 시간창 밖 | PASS | matches frozen relevance label |
| P18 | 같은 Wi-Fi 범주·장소지만 연결 장애와 의미가 다른 문제 | FAIL | false positive: candidate passed current metadata/semantic filters |
| P19 | 연결 불안정과 다른 자격증명 노출 문제 | FAIL | false positive: candidate passed current metadata/semantic filters |
| P20 | 다른 지하층 | PASS | matches frozen relevance label |

## incidents

상태: MEASURED_H2_SERVICE_INVARIANTS

```json
{
  "status" : "MEASURED_H2_SERVICE_INVARIANTS",
  "count" : 6,
  "passCount" : 6
}
```

| 사례 | 기대 근거 | 결과 | 실패 필드/원인 |
|---|---|---|---|
| I01 | 관리자가 같은 사건으로 확인한 3건은 사건1건이며 모든 신고 완료 후에만 종료 | PASS | [] |
| I02 | 각자 열린 사건에 이미 연결된 신고를 자동으로 병합하면 안 됨 | PASS | [] |
| I03 | 3일 전 종료 후 어제 새로 발생한 같은 장소 장애는 새 사건으로 유지하며 재발 확인 | PASS | [] |
| I04 | 발생일을 모르는 신규 접수는 검토 후에도 발생일 확정으로 바뀌면 안 됨 | PASS | [] |
| I05 | DATE 정밀도로 이전 사건 종료 당일의 선후를 입증하지 못한다는 문서화된 현재 정책 | PASS | [] |
| I06 | 기존 별도 사건 결정을 수동 연결 경로로 우회하면 안 됨 | PASS | [] |

## 공급자 측정

```json
{
  "mode" : "DEMO",
  "model" : null,
  "embeddingModel" : null,
  "analyzeAttempts" : 30,
  "analyzeSuccesses" : 30,
  "embeddingAttempts" : 40,
  "embeddingSuccesses" : 0,
  "externalCallsAttempted" : 0,
  "maximumExternalCalls" : 70,
  "circuitOpenedAfterConsecutiveFailures" : false,
  "usage" : { },
  "usageStatus" : "NO_PROVIDER_USAGE_REPORTED"
}
```

실제 입력·기대·출력·시간은 동명의 JSON에 보존했습니다. 사건 시나리오는 격리 H2 서비스 검증이며 실제 AI 추론/실제 MySQL 성능이 아닙니다. 토큰 정보는 API usage 텔레메트리이며 실제 결제 명세가 아닙니다.
