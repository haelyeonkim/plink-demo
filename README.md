# P-Link Demo

**P-Link** — 중요한 링크를 안전하게 공유하는 서비스 데모

원본 URL에 비밀번호, 만료 시간, 최대 열람 수를 설정하고 공유 코드로 전달합니다. 관리 화면에서 생성한 링크와 열람자 이름·열람 시간을 확인할 수 있습니다.

## 구현 현황

- 보호 링크 생성 및 공유 코드 복사
- 링크 목록, 상세 조회 및 삭제
- 비밀번호·만료 시간·최대 열람 수 검증
- 열람자 이름과 열람 시각 기록
- Google 로그인, 로그인 상태·이름 표시 및 로그아웃
- 수신자 패스키 최초 등록 및 등록된 패스키만 재접근 허용
- **입장권(Phase 0-1)**: 개인 URL 발급·메일 발송, 이메일 OTP 확인, URL당 패스키 1개 바인딩, 회전형 QR 제시, 게이트 단말 스캔, 재입장 원장과 회차 정책, 주최자 콘솔
- 통계, 사용자 가이드는 안내용 화면만 구현

## 요구사항

| 런타임 | 버전 | 용도 |
|---------|------|------|
| Java | 17+ | Spring Boot 4 백엔드 + Maven 빌드 |
| Node.js | 22+ | Vite 프론트엔드 개발 서버 (npm 포함) |

Maven은 `./mvnw` 래퍼를 사용하므로 별도 설치가 필요 없습니다. 이 개인 프로젝트는 `.mvn/settings.xml`에서 Maven Central만 사용하도록 고정되어 있어 사용자 홈의 사내 저장소 설정을 사용하지 않습니다.

## 빠른 시작

```bash
# 개발 서버 실행 (내부 백엔드 :8080 + 프론트 :3000)
git clone https://github.com/haelyeonkim/plink-demo.git
cd plink-demo
bash scripts/run-dev.sh

# 로컬 브라우저에서 접속
#   http://localhost:3000

# 종료
bash scripts/run-dev.sh stop
```

`bash scripts/run-dev.sh`는 백엔드와 프론트엔드를 백그라운드로 시작하고, 시작 메시지와 헬스체크 결과를 현재 터미널에 직접 출력한 뒤 프롬프트를 돌려줍니다. 서비스 로그는 `logs/backend.log`, `logs/frontend.log`에 저장됩니다. 프론트엔드는 IPv4 `0.0.0.0`에 바인딩하고 `lyuni.ddak.app`을 허용해 Nginx가 도메인 Host 헤더로 연결할 수 있습니다. 실시간 확인은 `tail -f logs/backend.log` 또는 `tail -f logs/frontend.log`를 사용하세요. 로그 파일은 Git에 커밋하지 않습니다.

`run-dev.sh`는 두 서비스를 시작한 뒤 백엔드 `/api/auth/session`과 프론트엔드 `/`에 자동으로 HTTP 헬스체크를 수행합니다. `Health check passed.`가 출력되면 두 포트가 응답하는 상태입니다. 스크립트가 종료되어도 두 서비스는 백그라운드에서 계속 실행됩니다.

포트 변경은 `--backend-port 9090 --frontend-port 5173` 옵션으로 지정할 수 있습니다. 실행 스크립트는 Vite 프록시와 기본 로그인 복귀 주소를 해당 포트에 맞춥니다. 프론트엔드 포트를 바꾸면 Google에 등록한 리디렉션 URI도 변경하세요. 개별 실행 시에는 `BACKEND_URL`(Vite)과 `APP_BASE_URL`(백엔드)을 지정하세요.

`80` 또는 `443`은 공개 서비스의 외부 포트입니다. 운영 서버에서는 Nginx 같은 리버스 프록시가 `lyuni.ddak.app`의 HTTPS 요청을 받아 프론트엔드와 백엔드로 전달합니다. `run-dev.sh`가 사용하는 `3000`과 `8080`은 서버 내부 개발 포트이므로 공개 URL에 포트 번호를 붙이지 않습니다.

### 개별 실행 및 빌드

백엔드:

```bash
./mvnw spring-boot:run
```

별도 터미널에서 프론트엔드:

```bash
cd frontend
npm ci
npm run dev
```

빌드는 프로젝트 루트에서 `./mvnw package`, `frontend/`에서 `npm run build`로 실행합니다. 최초 Maven 빌드에는 Maven Central에서 의존성을 다운로드할 수 있는 네트워크가 필요합니다. 프론트엔드 결과물은 `frontend/dist/`, 백엔드 JAR은 `target/`에 생성됩니다. 현재 백엔드 JAR에 프론트엔드가 자동 포함되지는 않습니다.

## 입장권 (Phase 0-1)

QR은 자격증명이 아니라 **패스키 인증을 통과한 직후 10초간만 유효한 영수증**입니다. 권리는 서버의 티켓 행에, 신원은 검증된 이메일과 바인딩된 패스키에, 현재 위치(장내·장외)는 입·퇴장 원장에 있습니다.

### 흐름

```
주최자 발급 ──▶ 메일로 개인 URL   /t/{sessionId}/{personalToken}
관람객 열기 ──▶ 이메일 OTP 확인 ──▶ 이 휴대폰에 패스키 등록(URL당 1개)
입장할 때  ──▶ [입장하기] ──▶ 지문·얼굴 인증 ──▶ 제시 권한 90초 발급
           ──▶ 10초마다 새로 만들어지는 QR ──▶ 게이트 태블릿 스캔 ──▶ 원장 기록
```

개인 URL은 **해시만 저장**하므로 DB를 읽어도 유효한 링크가 나오지 않습니다. 발급 메일 외에는 어디에도 남지 않으며, 주최자 콘솔도 링크를 돌려받지 않습니다.

### 화면

| 경로 | 대상 | 설명 |
|------|------|------|
| `/t/{sessionId}/{token}` | 관람객 | 등록, 입·퇴장, 회전 QR. 모바일 전용 정책이 적용되는 유일한 경로 |
| `/gate` | 게이트 단말 | 태블릿 카메라 하나로 QR 인식. 게이트 ID·토큰으로 연결 |
| `/manage/tickets` | 주최자 | 회차·발급·정책·단말 등록·실시간 장내 인원 (Google 로그인 필요) |

### 재입장과 입·퇴장 원장

재입장이 가능하므로 "한 표는 한 번만"이라는 제약을 쓸 수 없습니다. 대신 **재실 상태 불변식**이 그 역할을 합니다.

- `IN`은 장외 상태에서만, `OUT`은 장내 상태에서만 성립합니다.
- 전이는 `SELECT … FOR UPDATE`로 직렬화되어 두 게이트에 동시에 찍혀도 한쪽만 성공합니다.
- 따라서 **입장한 사람이 QR을 밖으로 넘겨도 두 번째 입장은 거부**됩니다. 표 한 장으로 장내에 있을 수 있는 사람은 항상 한 명입니다.
- `admission_event`는 append-only이고, 거부와 중복 스캔도 함께 기록됩니다. 스태프 보정도 수정이 아니라 새 행으로 남습니다.
- 퇴장 미스캔은 반드시 생기므로 `unmatched_exit` 정책으로 다룹니다. 다만 **장내 상태의 재입장은 `auto_exit_after_minutes`(기본 240분)가 지나야 보정**됩니다 — 즉시 용인하면 그 자체가 QR 공유 통로가 되기 때문입니다.

회차 정책(`재입장 모드·횟수·유효시간·중복 스캔 무시·퇴장 스캔 필수·퇴장 미스캔`)은 주최자 콘솔에서 즉시 바꿀 수 있고, 판정 시점의 값이 원장에 함께 남습니다.

### 게이트 단말 등록

콘솔에서 단말을 등록하면 **토큰이 한 번만** 표시됩니다. 서버는 해시만 보관하므로 다시 확인할 수 없고, 분실하면 재등록해 교체합니다. 방향(`IN`/`OUT`/`BIDIRECTIONAL`)은 단말 신원에 박혀 있어 **스캔된 코드가 방향을 주장할 수 없습니다**. 겸용 단말은 재실 상태로 방향을 추론합니다.

### 모바일 전용 정책

`TICKET_MOBILE_ONLY`는 `OFF` / `ADVISE` / `ENFORCE`이며 **관람객 경로(`/api/t/**`)에만** 적용됩니다. 게이트와 콘솔은 태블릿·데스크톱이 정상 환경입니다.

UA는 위조되므로 이 검사는 보안 통제가 아니라 UX 통제입니다. 실제 기기 결속은 두 곳에서 나옵니다 — 등록 시 **플랫폼 인증기 강제**(USB 보안키·크로스플랫폼 배제, `allowed-aaguids`로 기기 목록 제한 가능), 그리고 제시 권한 시크릿이 **그 브라우저를 떠나지 않는다**는 점입니다. 카카오톡·인스타그램 등 인앱 브라우저는 패스키를 쓸 수 없어 `X-InApp-Browser` 헤더로 알려 주고, 화면에서 기본 브라우저로 열도록 안내합니다.

### 기기 분실·교체

URL당 패스키가 하나라는 불변식을 유지하기 위해, 기기를 추가하지 않고 **토큰을 회전**합니다. 이메일 OTP 확인 → 기존 바인딩·제시 권한 폐기 → 새 URL 메일 발송 → 새 기기에서 재등록. 이전 URL은 즉시 404가 됩니다. 횟수는 `TICKET_REBIND_MAX`(기본 2회)로 제한하며, 입·퇴장 이력과 재입장 횟수는 보존됩니다.

### 아직 없는 것

양도(Phase 2), 얼굴 인식(Phase 3), 위치 바인딩과 오프라인 열화 모드(Phase 4)는 설계만 되어 있고 구현되지 않았습니다. 메일은 `spring.mail.host`가 없으면 발송되지 않고 경고와 함께 로그로만 남습니다.

## 데이터베이스

기본값은 H2 인메모리라 아무 설정 없이 실행됩니다. 스키마는 Flyway가 관리하므로 H2와 PostgreSQL에서 같은 마이그레이션이 적용됩니다.

```
src/main/resources/db/migration/V1__initial_schema.sql   # 모든 환경에 적용
src/main/resources/db/dev/V2__demo_data.sql              # 개발·테스트 전용 샘플 데이터
```

### Supabase(PostgreSQL) 전환

`.env`에 접속 정보만 넣으면 됩니다. 드라이버는 URL에서 자동으로 결정되므로 코드 변경이 필요 없습니다.

```properties
DB_URL=jdbc:postgresql://db.<project-ref>.supabase.co:5432/postgres?sslmode=require
DB_USERNAME=postgres
DB_PASSWORD=<Supabase 프로젝트의 DB 비밀번호>
FLYWAY_LOCATIONS=classpath:db/migration
H2_CONSOLE_ENABLED=false
```

주의할 점:

- **연결 방식**: JDBC는 **직접 연결(5432)** 또는 세션 모드 풀러를 사용하세요. 트랜잭션 모드 풀러(6543)는 JDBC prepared statement와 충돌하므로 쓰려면 URL에 `&prepareThreshold=0`을 붙여야 합니다.
- **SSL**: Supabase는 TLS를 요구하므로 `sslmode=require`가 필요합니다.
- **샘플 데이터 제외**: `FLYWAY_LOCATIONS`에서 `classpath:db/dev`를 빼야 데모 링크가 운영 DB에 들어가지 않습니다.
- **마이그레이션**: 애플리케이션 시작 시 Flyway가 자동 적용합니다. 스키마를 바꿀 때는 기존 파일을 수정하지 말고 `V3__*.sql`을 추가하세요.

## 데모 사용 순서

1. `http://localhost:3000/create`에서 실제로 열 수 있는 URL과 보호 옵션을 입력합니다.
2. 생성된 공유 코드를 복사하고 `/s/공유코드`에 접속하거나 홈에서 코드를 입력합니다.
3. 열람자 이름과 설정한 비밀번호를 입력해 원본 링크 열기 버튼을 확인합니다.
4. `/manage`에서 해당 링크의 상세 화면을 열어 열람 기록을 확인합니다.

패스키 수신 흐름에서는 수신자가 링크 화면에서 **패스키 등록하고 수신 확정**을 누릅니다. 등록이 완료된 첫 번째 패스키가 링크에 귀속되고, 이후 다른 패스키는 거부됩니다. 링크의 수신 확정 상태는 관리자 상세 화면에서 확인할 수 있습니다. 동기화 패스키를 사용하는 경우 같은 패스키가 등록된 다른 기기에서도 열 수 있습니다.

패스키는 보안 컨텍스트가 필요하므로 `https://` 배포 주소 또는 `http://localhost`에서만 동작합니다. 서버는 WebAuthn 공개키만 저장하고 개인키·생체정보는 저장하지 않습니다. 수신 확정은 링크를 먼저 연 사람에게 귀속되므로, 관리자는 링크를 올바른 수신자에게만 전달해야 합니다.

## Google 로그인 설정

Spring Security의 OAuth 2.0 / OpenID Connect 로그인으로 Google 인증 결과를 백엔드에서 검증하고 서버 세션을 생성합니다. 로그인 후 `/manage`로 이동하며 헤더에 이름과 로그아웃 버튼이 표시됩니다. 토큰과 Client Secret은 프론트엔드에 전달하지 않습니다.

1. Google Cloud의 **Google Auth Platform → 클라이언트**에서 기존 **웹 애플리케이션** 클라이언트를 선택하거나 새로 생성합니다.
2. **승인된 리디렉션 URI**에 `http://localhost:3000/login/oauth2/code/google`을 추가합니다. 승인된 JavaScript 원본만 등록해서는 이 방식의 로그인을 완료할 수 없습니다.
3. 프로젝트 루트에서 `cp .env.example .env`를 실행하고 `.env`의 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`에 값을 입력합니다. 따옴표나 `export` 없이 `이름=값`으로 작성하세요. `.env`는 Git에서 제외됩니다. 환경변수로도 설정할 수 있습니다.
4. 프로젝트 루트에서 백엔드를 재시작하고 `http://localhost:3000/login`에서 **Google로 계속하기**를 누릅니다. 클라이언트 설정이 없으면 서버는 정상 실행되지만 로그인 버튼은 비활성화됩니다.

로그인 취소나 인증 실패 시 로그인 화면에서 다시 시도할 수 있습니다. 이 방식에는 **승인된 JavaScript 원본**이 필요하지 않습니다. 이 클라이언트를 현재 P-Link에서만 사용한다면 기존 원본 설정을 삭제해도 됩니다. 다른 사이트의 브라우저 Google 로그인이 같은 클라이언트를 사용한다면 해당 원본은 유지하세요. Google 콘솔에서 대상 사용자/테스트 사용자 설정에 따라 로그인할 계정을 허용하세요.

배포 시 `APP_BASE_URL`을 실제 HTTPS 사이트 주소(마지막 `/` 제외)로 설정하고 동일한 `/login/oauth2/code/google` 주소를 Google에 등록하세요. 프론트엔드와 같은 호스트에서 `/api`, `/oauth2`, `/login/oauth2` 요청을 백엔드로 전달해야 합니다. Vite 프록시는 개발 환경에만 적용됩니다.

### 로컬과 lyuni.ddak.app 배포 설정

로컬 `.env`는 유지하고, 배포 서버의 환경변수 또는 프로젝트 루트 `.env`에 서버용 값을 설정합니다. `.env.example`은 값의 형식을 보여주는 템플릿이며 실제 인증값은 넣지 않습니다.

| 설정 | 로컬 | 배포 서버 |
|------|------|-----------|
| `APP_BASE_URL` | `http://localhost:3000` | `https://lyuni.ddak.app` |
| `SESSION_COOKIE_SECURE` | `false` | `true` |
| Google 승인된 리디렉션 URI | `http://localhost:3000/login/oauth2/code/google` | `https://lyuni.ddak.app/login/oauth2/code/google` |

Google 클라이언트에 위 리디렉션 URI 두 개를 모두 등록하면 됩니다. 백엔드는 `APP_BASE_URL`에 맞춰 로그인 복귀 주소와 API의 허용 원본을 설정합니다. `GOOGLE_CLIENT_ID`와 `GOOGLE_CLIENT_SECRET`은 각 실행 환경에서 설정해야 하며, 서버 재시작 후 반영됩니다.

배포 서버는 80/443에서 HTTPS를 제공하고 `/api`, `/oauth2`, `/login/oauth2`를 백엔드로 프록시해야 합니다. 그 외 화면 경로는 프론트엔드 `index.html`로 연결하세요. 공개 배포 시 H2 개발 콘솔은 `SPRING_H2_CONSOLE_ENABLED=false`로 끌 수 있습니다. `scripts/run-dev.sh`는 로컬 개발용이며 서버에서는 빌드된 JAR과 프론트엔드 정적 파일을 실행·호스팅하세요.

설정 참고: [Google 웹 서버 OAuth 안내](https://developers.google.com/identity/protocols/oauth2/web-server), [Spring OAuth 로그인 안내](https://spring.io/guides/tutorials/spring-boot-oauth2/).

### 인증 테스트

`./mvnw test`로 세션 조회, Google 인증 시작, 잘못된 콜백 거부, CSRF 보호, 로그아웃 및 기존 링크 생성 API를 검증합니다. 테스트에는 가짜 클라이언트 설정과 인증 사용자를 사용하므로 실제 Google 계정이 필요하지 않습니다. 실제 계정 선택·동의 과정은 위 설정 후 브라우저에서 확인해야 합니다.

## 데모 데이터와 제한 사항

- 기본값이 H2 인메모리 DB이므로 백엔드를 종료하면 변경 데이터가 사라집니다. 시작할 때 Flyway가 `db/migration`의 스키마와 `db/dev`의 샘플 링크 3개·열람 기록을 적용합니다. 영속 저장이 필요하면 아래 **데이터베이스** 절을 따라 Supabase로 전환하세요.
- 샘플 URL은 예시 주소입니다. 비밀번호가 있는 샘플 `abc123`, `demo01`은 더미 해시를 사용하므로 인증 시연에는 새 링크를 생성하세요.
- Google 로그인은 계정 식별과 세션 유지 기능입니다. 현재 링크 관리 화면과 API는 공유 데모로 누구나 접근할 수 있으며, 링크 소유자별 권한 검사는 아직 구현되어 있지 않습니다. 수신자 이름은 표시용으로 저장하며 접근 제한에 사용하지 않습니다.
- Google 로그인은 관리자 계정 식별과 세션 유지에 사용하며 링크 생성·관리 API는 로그인한 관리자만 이용할 수 있습니다. 수신자는 계정 없이 공유 링크에서 패스키를 등록합니다. 현재 관리자별 링크 소유자 데이터는 저장하지만 운영 수준의 계정 복구·관리자 역할 정책은 별도로 구현해야 합니다.
- 비밀번호 처리는 데모용 `String.hashCode()` 방식입니다. 실제 비공개 링크 서비스로 운영하기 전에 비밀번호 저장 방식과 관리 API의 인증·권한 처리를 구현해야 합니다.
- `./mvnw test`와 `./mvnw package`는 인증 테스트를 실행합니다. 개발 서버 실행 스크립트는 빠른 시작을 위해 테스트를 건너뜁니다.

## 기술 스택

- **백엔드**: Spring Boot 4.1 (Java 17) + spring-boot-starter-jdbc + H2 (in-memory)
- **프론트엔드**: Vite 8 + React 19 + TypeScript 7 (QR 생성 `qrcode`, 디코딩 `jsqr`)
- **DB**: H2 인메모리(기본, 별도 설치 불요) / Supabase(PostgreSQL) 전환 가능 — 스키마는 Flyway 마이그레이션이 관리

## 프로젝트 구조

```
plink/
├── pom.xml                          # Maven 설정
├── mvnw                             # Maven wrapper
├── scripts/run-dev.sh               # 개발 서버 실행 스크립트
├── src/main/java/com/plink/         # Spring Boot 백엔드
│   ├── PlinkApplication.java
│   ├── config/                     # SecurityConfig, PasskeyConfig, WebConfig
│   ├── controller/                 # AuthController, LinkController, PasskeyController
│   ├── model/
│   ├── repository/
│   └── service/LinkService.java
├── src/main/resources/
│   ├── application.yml
│   ├── schema.sql
│   └── data.sql                     # 데모 데이터
├── frontend/                        # Vite + React
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
├── index.html                       # 정적 랜딩 페이지
└── app/globals.css
```

## API

| Method | Path | 설명 |
|--------|------|------|
| GET | /api/links | 전체 링크 목록 |
| GET | /api/links/{id} | 링크 상세 + 열람 기록 |
| POST | /api/links | 보호 링크 생성 |
| DELETE | /api/links/{id} | 링크 삭제 |
| GET | /api/links/s/{code} | 공유 코드로 링크 접근 정보 |
| POST | /api/links/s/{code}/verify | 비밀번호 검증 + 열람 기록 |
| GET | /api/auth/session | 로그인 사용자, Google 설정 여부 및 CSRF 토큰 |
| GET | /oauth2/authorization/google | Google 로그인 시작 |
| POST | /api/auth/logout | 세션 종료 |

POST/DELETE 요청에는 `/api/auth/session` 응답의 `csrfHeader` 이름으로 `csrfToken` 값을 보내야 하며 동일한 세션 쿠키를 유지해야 합니다. 프론트엔드는 이를 자동 처리합니다.
