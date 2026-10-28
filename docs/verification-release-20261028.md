# 2026-10-28 Azure 첫 배포용 기능 통합 검증

## 범위

- 기존 main `ac233a7`에 PR #9 단일 로그인·공용 팝업 제거(`7b3a5df`), 팀원 업무 그룹 변경(`6647c27`)과 PR #10 정정 보완(`14d575b`)을 통합했다.
- 제품 기능을 추가하지 않았다. 새 파일 `WorkGroupReleaseIntegrationTest`는 저장된 실제 LLM의 파싱된 출력으로 HTTP·세션·CSRF·DB 저장 흐름을 검사한다. 공급자 네트워크 대체 호출은 허용하지 않는다.
- `.env.example`에 기존 로그인 요청 제한 및 AI 비용·호출 제한의 배포 설정 예시를 추가했다. 로컬 기본값은 DEMO/RULE_ONLY다. 공개 HTTPS에서는 `COOKIE_SECURE=true`, `PUBLIC_AUTH_LIMIT_ENABLED=true`를 별도로 설정해야 한다.

## 결과

| 검사 | 결과 | 범위 |
|---|---|---|
| 백엔드 H2 전체 | **516 PASS / 0 FAIL / 14 SKIP**, 총 530 | 기존 선택 검사는 SKIP, 새 유료 호출 없음 |
| 별도 MySQL | **80 PASS / 0 FAIL / 0 SKIP** | 접수 API·계정 API·공개 인증 제한·업무 그룹·저장 LIVE 출력 재생 |
| 새 HTTP·DB 재생 3건 | **H2 3 PASS / MySQL 3 PASS** | 위 합계에 포함 |
| 프런트엔드 | **84 PASS / 0 FAIL**, 빌드 성공 | 기존 API 계약·학생/관리자·추가 질문·사진·세션 흐름 |
| 백엔드 bootJar | **PASS** | 실행 중인 서버 JAR를 덮어쓰지 않고 `.runtime/release-integration-20261028/package`로 빌드 |
| Flyway | **V1→V10 PASS** | 별도 MySQL 평가 DB, V8·V9·V10 성공 확인 |
| 추가 실제 API 호출 | **LLM 0 / Embedding 0 / 비용 $0** | 이전 개발 LIVE 5회 결과 재사용 |

새 재생 검사:

1. 학교 거울 → 개인 거울 정정: 개인 물건·일반 문의로 저장, 시설 자동 배정 없음, 같은 요청 재전송 시 중복 저장 없음, 총괄 처리 중→완료, 학생 결과 조회, 다른 학생 조회 차단.
2. 거울을 부정하고 다른 물건이라고 답변: 이전 대상·근거 제거, 은주2관 3층 동시 반영, 대상 질문 유지, 학생이 관리자 검토를 선택하기 전에는 저장하지 않음, 선택 후 대상 미확정 상태로 검토 접수.
3. 대여 실습복 파손: 물리 장소를 강요하지 않고 학교 서비스·공용 물품으로 저장, 확인된 개별 배정 규칙이 없으므로 총괄 검토 유지.

MySQL 데이터는 `skope_release_20261028`에만 생성했다. 테스트 DB를 만들기 전 기존 격리 MySQL의 실제 data directory를 확인했다. 기존 공유 DB의 계정·접수·사진·AI 사용량은 수정하지 않았다. 테스트 XML은 Git에서 제외된 `.runtime/release-integration-20261028/{h2,mysql}`에 보존한다.

## AI 결과의 의미와 한계

- 재생 원본: `evaluation/work-group-v1-results.json`, 프롬프트 `hybrid-intake-v6`, 정책 `hybrid-check-v7`. 원본은 2026-10-27의 합성 개발 LIVE 5회이며 독립 품질 평가가 아니다.
- 이번에는 원래 LLM 응답을 재주입했으며, 새 모델 추론·실제 Embedding·외부 HTTPS·브라우저 클릭·Azure 실제 서버를 검증한 것이 아니다. 추가 답변의 이전 분석 정보·근거가 저장 당시 맥락과 일치하는지 검사한다. 재생 성공을 새 LIVE E2E나 전체 정확도로 보고하지 않는다.
- 기존 동의어 호환은 `미러`/`mirror`→`거울`에 한정된다. 미등록 동의어 누락과 소유 정정 후 증상 요약의 이전 표현 잔존 가능성은 [업무 그룹 검증](work-group-routing.md)에 남겨 두었다.
- 사진 기능·권한·AI 예산 정책을 변경하지 않았으며, 이번 재생 3건에 사진 업로드는 포함하지 않았다.

## 병합·Azure 인수인계

- 순서: PR #10 → 팀원 업무 그룹 브랜치, PR #9 → main, 검증된 업무 그룹·통합 검사·설정 안내 → main. 실제 병합 후 main의 Git tree가 로컬 검증본과 같은지 확인한다.
- 배포 담당자는 최종 main의 **커밋 SHA를 고정**해 릴리스를 만든다. 로컬 `.runtime`·DB·키·계정 안내 파일을 소스에 포함하지 않는다. 기존 로컬 DB를 Azure에 자동 복사하지 않는다.
- Azure 설치 절차는 별도 `deploy/azure-vm` 브랜치 `6ac4161`에 있다. 이 배포 스크립트 자체의 전체 검토·Azure 실행 성공을 이번 기능 통합으로 대신하지 않는다.
- 서버의 `/etc/skope/skope.env`에 로그인 요청 제한을 활성화한다. 현재 Azure 예시에는 이 항목이 없으므로 아래를 추가한다. 공용 Basic 암호 팝업은 켜지 않는다.

```properties
COOKIE_SECURE=true
PUBLIC_AUTH_LIMIT_ENABLED=true
PUBLIC_AUTH_LOGIN_PER_MINUTE=120
PUBLIC_AUTH_REGISTER_PER_MINUTE=10
```

- API 키는 서버에서만 설정한다. LIVE 전환 전에 `AI_BUDGET_PERIOD`·`AI_BUDGET_MAX_USD`·LLM/Embedding 횟수 상한을 명시한다. `.env.example`의 $1·각 200회는 기존 팀 공유 기준의 설정 예시이며 공급자 청구액 보장이나 추가 유료 테스트 승인 자체가 아니다.
- Azure에서 남은 확인: 고정 HTTPS 주소, PC·휴대폰 로그인/새로고침/접수/사진/관리자 처리/학생 조회, 실제 최소 LIVE 연동, 재부팅·자동 재시작, DB·사진·사용량 보존, 서버 밖 백업과 복원.
- 현재 PC 공유 서버는 이번 작업에서 재시작·배포하지 않았다. Azure 검증이 끝날 때까지 기존 실행을 유지한다. 11/2~11/9 코드 수정 불가 조건을 지키도록 그 전에 제출 커밋을 확정한다.
