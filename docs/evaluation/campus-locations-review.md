# 서경대학교 장소 예시 정정 — 2026-10-20

## 원인과 확인 범위

표현 변형 평가에 사용한 다온관·해든관·솔빛관·푸른관·가온관·누리관은 합성 건물명이었다. 해당 평가 신고를 임시 화면에 그대로 표시하여 실제 교내 장소처럼 보이게 됐다. 학교 장소를 검증한 데이터가 아니었으며, 당시 언어 분석 PASS는 장소 실재 여부의 PASS가 아니다.

학교 공식 사이트에서 아래 **공개된 주소/행사 장소**를 확인했다. 전체 캠퍼스 건물 목록, 현장 운영 상태, 실제 장애를 확인한 것은 아니다.

| 장소 | 확인 근거 | 제한 |
|---|---|---|
| 북악관 608호 | [2026년 7월 전과 설명회 공지](https://comso.skuniv.ac.kr/index.php?document_srl=25893) | 공지에 층이 별도로 명시되지 않아 수동 시연 신고의 층은 비움. 모델 평가의 6층은 호실 번호에서 유추한 값으로 구분 |
| 은주2관 2층 205호 | [도시공학과 공식 주소](https://ur.skuniv.ac.kr/ur_main) | 사무실 주소이며 강의실로 단정하지 않음 |
| 유담관 12층 1202호 | [계약학과 공식 안내](https://ucon.skuniv.ac.kr/graduate_contract_manage) | 산업교육지원과 주소 |
| 유담관 14층 1404호 | [계약학과 공식 안내](https://ucon.skuniv.ac.kr/graduate_contract_manage) | 계약학과 사무실 주소 |

## 수정 범위

- `campus-locations.json`: 확인된 장소 4개와 공식 출처·확인 범위 저장.
- `language-variation-v3.json`: 기존 6개 표현 사례의 장소를 위 장소로 교체한 **파생 개발 평가셋**. 독립 신규 품질 평가가 아님. `Test-LanguageVariation.ps1`과 `LanguageVariationVerificationTest.java`의 기본 평가 대상·고정 해시·캐시 경로를 v3로 변경.
- 비고정 단위 테스트 7개 클래스(`AiServiceTest`, `AiFollowUpTest`, `FollowUpPolicyTest`, `ClarificationPolicyTest`, `HybridBoundaryAuditTest`, `AiEvaluationTest`, `SimilarityServiceTest`)의 가상 건물명을 북악관·은주2관·유담관으로 교체. **해당 단위 테스트의 층·호실 조합은 경계 조건 검사용 합성값이며 학교 실제 공간 목록이 아니다.** 위 공식 장소 예시 4개와 구별한다.
- `frontend/src/student.tsx`: 홈 예시·추천 입력·수동 입력 장소 예시를 북악관 608호로 변경. 기존 API·AI 정책·DB 스키마는 변경 없음.
- 사용자 승인으로 임시 H2 `language_variation_v2`의 합성 신고 4개를 별도 로컬 파일에 보존하고, 새 임시 H2 `campus_locations`에 수동 시연 신고 4개를 등록. 원문과 제목에 수동 시연/가상 상황 표시. 실제 학교에 장애가 있다는 뜻이 아님.

## 보존한 과거 기록

실행했던 LIVE 입력·정답·응답·캐시·화면 증거와 기존 MySQL은 변경하지 않았다. v1/v2 평가 입력의 SHA256은 각각 아래 값으로 유지된다.

- v1: `8b07e686069867f1c826e364d3c8cfaed326f1ed408eb9adf79f4268b3bc54c1`
- v2: `757d37542162f7302898b100132ff7efe1a1cbd9ea3b7a248e21dbd6828180e0`

과거 응답을 새 장소명으로 고쳐 LIVE 결과처럼 재사용하지 않았다. `LanguageVariationReplayTest` 등 고정 캐시 재생 테스트도 당시 입력을 유지한다. 다른 과거 평가셋의 합성 장소도 역사적 입력으로 남아 있으므로 현재 시연 데이터로 복사하지 않는다.

## 확인 결과

| 항목 | 결과 |
|---|---|
| 수정된 7개 클래스 + v3 사전 점검 | JUnit 166 PASS / 0 FAIL / 0 SKIP |
| v3 요청 생성·입력 해시·제품 소스 무변경 점검 | PASS, `language-variation-v3-preflight.json` |
| 새 시연 신고 등록 → H2 저장 → 학생 상세 조회 | 4/4 PASS, `campus-locations-http.json` |
| 새 장소의 LIVE AI 품질 | **미검증** — PREPARE만 실행, 기존 LIVE 성적 전용 금지 |
| 브라우저 클릭·시각 확인 | 미수행 — HTTP 저장·조회 확인과 구분 |
| 신규 LLM / Embedding / 비용 | 0회 / 0회 / $0 |

현재 5173 화면의 서버는 **DEMO / RULE_ONLY / 임시 H2**이며, 실제 LIVE 호출 서버가 아니다. 이전 서버의 정확 일치 캐시 응답 재생도 현재 화면에서는 사용하지 않는다. 서버 재시작으로 재로그인이 필요하다. 임시 DB는 메모리형이므로 서버 종료 시 사라진다. 실제 LIVE 환경 설정 파일과 기존 MySQL은 그대로 보존한다.

이 수정은 장소 예시의 정정이다. 사용자의 입력 장소가 실재하는지 자동 검사하거나 학교 외 장소를 차단하는 새 기능을 추가하지 않았다.
