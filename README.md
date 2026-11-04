# P-Link Demo

보호 링크 공유와 입장권 발급·입장 관리를 제공하는 웹 프로젝트입니다. 이 문서는 **Git을 처음 사용하는 사람**이 코드를 내려받고 자기 컴퓨터에서 실행하는 순서로 안내합니다.

- **링크 도구**: 콘텐츠와 작품을 선택해 보호 링크를 만들고 공유합니다.
- **입장권 도구**: 입장권 발급, 패스키 인증, QR 확인과 게이트 운영을 지원합니다.

## 1. 시작하기 전에

**Git**은 파일 변경 이력을 저장하는 프로그램이고, **GitHub**는 그 저장소를 온라인으로 공유하는 서비스입니다. 코드를 내려받는 `clone`은 처음 한 번, 이후 변경 내용을 받는 `pull`은 필요할 때 사용합니다.

아래 프로그램을 준비하세요. 명령어는 브라우저 주소창이 아니라 **터미널**에 입력합니다.

| 프로그램 | 필요한 버전·용도 |
| --- | --- |
| Git | 저장소 다운로드와 변경 이력 관리 |
| JDK | Java 17 이상. 백엔드 빌드·실행에 사용 |
| Node.js / npm | Node.js 22.12 이상. npm도 함께 필요 |
| Python 3 | 실행 스크립트의 포트 확인에 사용 |
| Bash, curl | 개발 서버 실행 스크립트에 사용 |

macOS·Linux에서는 터미널을 사용하세요. **Windows에서는 WSL의 Ubuntu 터미널을 기준으로 진행**하고, 위 프로그램도 WSL 안에 설치하세요. 아래 Bash 명령은 PowerShell이나 명령 프롬프트용이 아닙니다.

설치 후 새 터미널에서 한 줄씩 실행합니다. 모두 버전이 출력되면 준비된 상태입니다.

```bash
git --version
java -version
javac -version
node --version
npm --version
python3 --version
bash --version
curl --version
```

Maven은 저장소에 포함된 `mvnw`로 실행하므로 별도로 설치하지 않아도 됩니다. 최초 실행에는 의존성을 내려받기 위한 인터넷 연결이 필요합니다.

## 2. 내 컴퓨터로 다운로드하기

프로젝트를 보관할 폴더에서 다음을 실행하세요.

```bash
git clone https://github.com/haelyeonkim/plink-demo.git
cd plink-demo
```

- `git clone`: GitHub의 코드와 변경 이력을 `plink-demo` 폴더에 내려받습니다.
- `cd plink-demo`: 내려받은 폴더 안으로 이동합니다.

이후 명령은 별도 안내가 없으면 **`README.md`, `pom.xml`, `mvnw`가 있는 프로젝트 최상위 폴더**에서 실행하세요. 터미널을 새로 열었다면 다시 이 폴더로 이동해야 합니다.

## 3. 로컬 설정과 로그인 계정 만들기

처음 한 번만 설정 예제 파일을 복사합니다. 이미 `.env`가 있다면 덮어쓰지 말고 기존 파일을 편집하세요.

```bash
cp .env.example .env
```

편집기로 `.env`를 열고 다음 항목을 설정하세요. 관리자 항목 두 개는 파일 끝에 추가합니다.

```properties
APP_BASE_URL=http://localhost:3000
SESSION_COOKIE_SECURE=false
PLINK_ADMIN_EMAIL=your-email@example.com
PLINK_ADMIN_PASSWORD=replace-with-your-own-password
```

이메일은 자신의 주소로, 비밀번호는 **직접 정한 10자 이상의 값**으로 바꾸세요. 따옴표나 `export` 없이 `이름=값` 형식으로 작성합니다. 위 비밀번호 예시를 그대로 사용하지 마세요.

서버가 시작될 때 이 관리자 계정을 생성합니다. 같은 계정이 이미 있으면 `.env`에 지정한 비밀번호로 재설정됩니다. Google 설정 없이 이 이메일·비밀번호로 로그인할 수 있습니다.

`.env`에는 비밀번호 등 비밀값이 들어갑니다. Git에서 제외되므로 GitHub에 올리지 마세요. 설정 형식만 공유할 때는 실제 비밀값 없이 `.env.example`을 수정합니다.

## 4. 실행하고 접속하기

```bash
bash scripts/run-dev.sh
```

스크립트가 백엔드를 빌드하고 프론트엔드 의존성을 설치한 뒤 서버를 실행합니다. 첫 실행은 다운로드 때문에 시간이 걸릴 수 있습니다. `Health check passed.`가 나오면 브라우저에서 다음 주소를 여세요.

| 주소 | 화면 |
| --- | --- |
| http://localhost:3000 | 서비스 소개 |
| http://localhost:3000/links | 링크 관리 |
| http://localhost:3000/links/login | 링크 도구 로그인 |
| http://localhost:3000/tickets | 입장권 관리 |
| http://localhost:3000/tickets/login | 입장권 도구 로그인 |
| http://localhost:3000/tickets/gate | 게이트 화면 |

앞에서 설정한 이메일·비밀번호로 로그인하세요. 게이트 사용에는 별도 단말 등록이 필요합니다.

내부적으로 백엔드는 `8080`, 링크 앱은 `3000`, 입장권 앱은 `3010` 포트를 사용합니다. 브라우저에서는 **3000 포트 하나**로 두 도구를 이용합니다.

기본 데이터베이스는 설치가 필요 없는 H2 메모리 DB입니다. **백엔드를 종료하면 추가·수정한 DB 데이터가 사라집니다.** SMTP를 설정하지 않으면 실제 이메일은 발송되지 않습니다. 얼굴 서비스가 없을 때의 개발용 대역은 실제 얼굴 인식을 제공하지 않습니다.

### 종료와 재시작

서버는 백그라운드에서 실행되므로 실행 명령이 끝나도 계속 동작합니다. 종료할 때는 다음 명령을 사용하세요.

```bash
bash scripts/run-dev.sh stop
```

다시 시작할 때는 `bash scripts/run-dev.sh`를 실행합니다. `.env`나 백엔드 코드를 바꾼 뒤에는 종료 후 다시 실행하세요.

## 5. 최신 코드 받기

이미 내려받은 저장소에서는 다시 `clone`하지 않습니다. 먼저 변경 중인 파일이 있는지 확인합니다.

```bash
git status
```

저장하지 않은 변경이 없고 서버를 종료한 상태에서 다음을 실행하세요.

```bash
git switch main
git pull --ff-only origin main
bash scripts/run-dev.sh
```

변경 파일이 표시된다면 먼저 아래 과정으로 작업 브랜치에 커밋하세요. `pull`이 실패하면 강제로 덮어쓰지 말고 오류 내용을 확인하세요.

## 6. 수정한 내용을 PR로 올리기

이 저장소는 **작업 브랜치 생성 → 수정·검증 → PR → 사람의 Approve → merge** 순서로 작업합니다. `main`에 직접 커밋하거나 push하지 않습니다. Codex와 Claude에도 같은 규칙이 적용됩니다.

| 용어 | 의미 |
| --- | --- |
| 브랜치(branch) | 원본과 분리해 작업하는 변경 이력 |
| 커밋(commit) | 내 컴퓨터에 변경 내용을 기록하는 단위 |
| 푸시(push) | 커밋을 GitHub로 올리는 작업 |
| PR(Pull Request) | 작업 브랜치의 변경을 `main`에 합쳐 달라는 검토 요청 |
| Approve | 사람이 PR을 검토하고 승인하는 단계 |
| merge | 승인된 변경을 `main`에 합치는 작업 |

처음 커밋하기 전에 이 저장소에서 사용할 작성자 정보를 설정합니다. 아래 값은 자신의 이름과 이메일로 바꾸세요. GitHub에 이메일을 공개하고 싶지 않다면 GitHub 설정에서 제공하는 비공개 커밋 이메일을 사용하세요.

```bash
git config user.name "Your Name"
git config user.email "your-email@example.com"
```

최신 `main`에서 새 브랜치를 만듭니다. 브랜치 이름은 작업마다 다르게 정하세요.

```bash
git switch main
git pull --ff-only origin main
git switch -c docs/update-readme
```

파일을 수정한 뒤 변경 내용을 확인하고, 올릴 파일을 지정해 커밋합니다. 아래는 README만 수정한 예시입니다.

```bash
git status
git diff
git add README.md
git diff --cached
git commit -m "Improve getting started instructions"
git push -u origin docs/update-readme
```

`commit`은 내 컴퓨터에만 저장하며, `push`까지 해야 GitHub에 올라갑니다. Push에는 GitHub 인증과 저장소 쓰기 권한이 필요합니다. 권한이 없다면 GitHub에서 저장소를 Fork하고 자신의 Fork에 작업 브랜치를 push한 뒤 원본 저장소로 PR을 보내세요. GitHub 계정 비밀번호를 터미널 인증 비밀번호로 사용하지 마세요.

GitHub 저장소에서 **Compare & pull request** 또는 **Pull requests → New pull request**를 선택합니다.

1. `base`는 `main`, `compare`는 자신이 만든 작업 브랜치로 지정합니다.
2. 변경 이유, 변경 내용, 검증 결과를 작성하고 PR을 만듭니다.
3. 사람에게 리뷰를 받고 **Approve**를 기다립니다. 수정 요청이 있으면 같은 브랜치에 추가 커밋을 push합니다.
4. 승인과 필요한 검증이 완료된 뒤 병합 권한이 있는 사람이 merge합니다. AI는 명시적인 병합 허가 없이 merge하지 않습니다.
5. 병합 후 로컬에서 `git switch main`, `git pull --ff-only origin main`으로 결과를 받습니다.

세부 협업 규칙은 [AGENTS.md](AGENTS.md), Claude의 진입 문서는 [CLAUDE.md](CLAUDE.md)를 참고하세요.

## 7. 자주 만나는 문제

| 증상 | 확인할 내용 |
| --- | --- |
| `command not found` | 해당 프로그램이 현재 터미널 환경에 설치되어 있는지 확인하고 새 터미널을 여세요. |
| `not a git repository` | `cd`로 내려받은 `plink-demo` 폴더 안으로 이동하세요. |
| `destination path ... already exists` | 이미 다운로드한 폴더로 이동하세요. 다시 clone할 필요가 없습니다. |
| `Permission denied`로 `mvnw` 실행 실패 | 프로젝트 폴더에서 `chmod +x mvnw`를 실행하세요. |
| 포트를 사용할 수 없다는 오류 | 기존 개발 서버를 `bash scripts/run-dev.sh stop`으로 종료하세요. 다른 프로그램이 쓰는 포트라면 아래처럼 변경하세요. |
| 로그인 실패 | `.env`의 관리자 이메일·비밀번호를 확인하고 서버를 재시작하세요. 비밀번호는 10자 이상이어야 합니다. |
| Google 로그인 불가 | 기본 시작 방법은 이메일·비밀번호 로그인입니다. Google은 클라이언트 설정과 허용 이메일 또는 도메인이 별도로 필요합니다. |
| Git push 권한 오류 | GitHub 인증 상태와 저장소 쓰기 권한을 확인하세요. 권한이 없다면 Fork를 사용하세요. |
| 충돌 또는 `pull --ff-only` 실패 | `git status`로 상태를 확인하고 담당자에게 오류를 공유하세요. `reset --hard`나 강제 push로 해결하지 마세요. |

포트를 바꾸는 예시입니다. `.env`의 `APP_BASE_URL`도 `http://localhost:5173`으로 변경하세요.

```bash
bash scripts/run-dev.sh --backend-port 9090 --frontend-port 5173 --ticket-port 5174
```

종료할 때도 같은 포트를 지정합니다.

```bash
bash scripts/run-dev.sh stop --backend-port 9090 --frontend-port 5173 --ticket-port 5174
```

실행 실패 원인은 로그 마지막 부분에서 확인할 수 있습니다. 로그를 공유할 때는 개인 링크·인증번호 등 민감한 내용을 제거하세요.

```bash
tail -n 80 logs/backend.log
tail -n 80 logs/frontend.log
tail -n 80 logs/ticket.log
```

## 개발자를 위한 참고

백엔드 테스트는 프로젝트 최상위 폴더에서 실행합니다.

```bash
./mvnw test
```

프론트엔드 타입 검사와 빌드는 다음과 같습니다.

```bash
cd frontend
npm ci
npm run build
cd ..
```

백엔드 JAR은 `./mvnw clean package -DskipTests`로 생성합니다. 결과물은 `target/`, 프론트엔드 결과물은 `frontend/dist/`에 저장됩니다. 프론트엔드는 백엔드 JAR에 자동 포함되지 않습니다.

```text
plink-demo/
├── src/main/java/com/plink/    # Java 백엔드, ticket/ 아래 입장권 기능
├── src/main/resources/        # 설정 및 Flyway DB 마이그레이션
├── src/test/java/             # 백엔드 테스트
├── frontend/apps/link/        # 서비스 소개·계정·링크 도구
├── frontend/apps/ticket/      # 입장권 도구
├── frontend/shared/src/       # 두 앱이 함께 쓰는 UI·인증
├── frontend/public/           # 정적 이미지·아이콘
├── face-service/              # 선택적으로 실행하는 얼굴 서비스
├── scripts/run-dev.sh         # 개발 서버 시작·종료
├── .env.example               # 로컬 설정 예제
└── docs/                      # 상세 문서
```

- [상세 문서 목록](docs/README.md)
- [설계와 의도](docs/design.md): 주요 설계 결정, 이유와 제약
- [얼굴 서비스](face-service/README.md)
- [협업 및 리뷰 규칙](AGENTS.md)

개발 서버 실행 안내는 로컬 사용 기준입니다. 운영 배포에서는 영속 DB, 고정된 `TICKET_TOKEN_SECRET`, HTTPS `APP_BASE_URL`, 보안 세션 쿠키, 개발 데이터 제외 및 H2 콘솔 비활성화를 별도로 설정해야 합니다.
