# GitHub 업로드용 소스 패키지

기준일: 2026-10-16 / 서버 정책 hybrid-check-v2.

- backend, frontend, scripts, docs, 원본 구현 명세, .env.example, .gitignore, .gitattributes를 포함합니다.
- API 키·실제 비밀번호·.runtime·AI 원시 캐시·로컬 DB·빌드 결과·node_modules·로그·기존 Git 이력은 제외했습니다.
- 새 팀원의 실행 방법: docs/TEAM_HANDOFF.md. GitHub 업로드 방법: docs/GITHUB_UPLOAD.md.
- 최신 검증 상태: docs/verification.md. 132개 관련 회귀 PASS, 저장된 LIVE 응답 재생 37 PASS, 후속 3단계 LIVE 미검증. 독립 AI 정확도가 아닙니다.
- 이번 정리는 문서·Git 제외/줄바꿈 설정·패키징만 변경했습니다. 앱 소스, DB, 실행 서버는 변경하지 않았고 신규 유료 API 호출도 없습니다.
- 실제 로컬 비밀값 일치 검사, 키/개인키 형태 검사, 패키지 경로 제외 검사, Git ignore 검사(13 제외/7 유지), 시연 이미지 6장 육안 확인을 수행했습니다. 모든 잠재적 비밀정보의 부재를 보장하는 전문 보안 감사는 아닙니다.
- SOURCE_MANIFEST.sha256에는 이 파일과 포함 소스의 SHA-256을 기록했습니다. 목록 파일 자신의 해시는 제외합니다.
- ZIP은 별도 작업 사본입니다. 압축을 푼 소스를 업로드하고, 업로드 이후 사용할 작업 폴더를 하나 정하세요. ZIP 자체를 GitHub에 올리지 않습니다.
