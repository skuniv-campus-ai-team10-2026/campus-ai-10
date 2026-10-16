# GitHub에 처음 올리는 방법

이번 ZIP은 GitHub에 업로드할 소스 사본이다. GitHub 저장소 생성·커밋·push는 아직 하지 않았다. 원래 작업 폴더의 실행 서버와 데이터는 별도로 유지된다.

## 1. 압축 해제와 저장소 생성

ZIP을 **기존 실행 폴더와 다른 위치**에 풀고, README.md, backend, frontend가 직접 보이는 `SKOPE-Experiment` 폴더를 연다. ZIP 파일 자체를 저장소에 올리는 것이 아니라 압축을 푼 소스 파일을 올린다. `.gitignore`, `.gitattributes`, `.env.example`도 포함해야 한다.

GitHub에 로그인하고 New repository에서 저장소 이름(예: `skope`)과 **Private**을 선택한다. 기존 README와 .gitignore를 올릴 것이므로 GitHub의 README, .gitignore, License 초기 생성 옵션은 선택하지 않는다. 라이선스는 팀의 공유·공개 방침을 합의한 뒤 정한다.

## 2. 로컬 Git으로 첫 커밋

Git 설치 후 새 PowerShell에서 `git --version`으로 확인한다. **압축 해제한 프로젝트 루트**에서 실행한다. 사용자 이름과 이메일은 실제 본인 값으로 바꾼다. 저장소에 기록되는 이메일이므로 필요하면 본인의 GitHub noreply 이메일을 사용한다.

```powershell
git init -b main
git config user.name "본인 이름"
git config user.email "본인 GitHub 이메일"
git add .
git status --short
git diff --cached --stat
```

커밋 목록에 `.runtime`, 실제 `.env`, API 키, 비밀번호, DB 파일, node_modules, build, exports가 없어야 한다. 이번 ZIP은 해당 파일을 제외했지만 압축 해제 후 추가한 파일은 다시 확인한다. 로컬 `.env.example`은 빈 값의 설정 예시이므로 포함한다.

```powershell
git commit -m "chore: import verified SKOPE baseline"
git remote add origin https://github.com/OWNER/REPOSITORY.git
git push -u origin main
```

`OWNER/REPOSITORY`는 방금 만든 저장소의 실제 값으로 바꾼다. Git 인증 화면이 나오면 본인 GitHub 계정으로 인증한다. 토큰을 URL이나 명령에 넣지 않는다. 원격에 이미 커밋이 있어 push가 거절되면 강제 push하지 말고 기존 내용부터 확인한다.

## 3. 업로드 확인과 협업 시작

GitHub에서 backend, frontend, scripts, docs, .gitignore, README가 보이는지 확인하고 저장소 설정에서 상대 작업자를 Collaborator로 초대한다. 상대방은 clone 후 `docs/TEAM_HANDOFF.md`를 따라 본인 환경을 설정한다.

```powershell
git clone https://github.com/OWNER/REPOSITORY.git
cd REPOSITORY
git switch -c verify/live-integration
```

원래 실행 중인 프로젝트는 ZIP과 별도의 사본이다. 이후 양쪽 폴더를 계속 번갈아 수정하지 말고 저장소에 연결된 작업 폴더를 하나 정한다. 기존 로컬 DB와 비밀 설정의 이전이 필요하면 별도로 진행하며 Git에 넣지 않는다.

위 절차는 [GitHub 공식 기존 코드 업로드 안내](https://docs.github.com/en/migrations/importing-source-code/using-the-command-line-to-import-source-code/adding-locally-hosted-code-to-github)에 따른다.
