# Holdout v1 — LIVE

고정 라벨 SHA-256: `7ea381d2fedb7a2ab0f21237457acc5ad51dd049a6ba4086aa13566e9f3a923a`

실행: 2026-10-11T14:09:34.411720300+09:00. AI_SKIPPED_MISSING_CONFIGURATION

구현 경험이 있는 에이전트가 별도로 작성한 합성셋이며 사람의 독립 검수가 아닙니다. 결과에 맞춰 라벨이나 코드를 수정하지 않았습니다. 문장·쌍 품질 실패는 아래에 그대로 표시합니다. JUnit 성공은 측정 파일 생성 성공을 뜻합니다.

## sentences

상태: SKIPPED

AI key or model configuration missing

## pairs

상태: SKIPPED

AI key or model configuration missing

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
  "status" : "SKIPPED",
  "calls" : 0
}
```

실제 입력·기대·출력·시간은 동명의 JSON에 보존했습니다. 사건 시나리오는 격리 H2 서비스 검증이며 실제 AI 추론/실제 MySQL 성능이 아닙니다. 토큰 정보는 API usage 텔레메트리이며 실제 결제 명세가 아닙니다.
