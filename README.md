# P-Link Demo

**P-Link** — 중요한 링크를 안전하게 공유하는 서비스 데모

원본 URL에 비밀번호, 만료 시간, 최대 열람 수를 설정하고 공유 코드로 전달합니다. 관리 화면에서 생성한 링크와 열람자 이름·열람 시간을 확인할 수 있습니다.

## 구현 현황

- 보호 링크 생성 및 공유 코드 복사
- 링크 목록, 상세 조회 및 삭제
- 비밀번호·만료 시간·최대 열람 수 검증
- 열람자 이름과 열람 시각 기록
- 로그인, 통계, 사용자 가이드는 안내용 화면만 구현

## 요구사항

| 런타임 | 버전 | 용도 |
|---------|------|------|
| Java | 8+ | Spring Boot 백엔드 + Maven 빌드 |
| Node.js | 20+ | Vite 프론트엔드 개발 서버 (npm 포함) |

Maven은 `./mvnw` 래퍼를 사용하므로 별도 설치 불요.

## 빠른 시작

```bash
# 개발 서버 실행 (백엔드 :8080 + 프론트 :3000)
git clone https://github.com/haelyeonkim/plink-demo.git
cd plink-demo
bash scripts/run-dev.sh

# 브라우저에서 접속
#   http://localhost:3000

# 종료
bash scripts/run-dev.sh stop
```

포트 변경은 `--backend-port 9090 --frontend-port 5173` 옵션으로 지정할 수 있습니다. 백엔드 포트를 바꾸면 `frontend/vite.config.ts`의 프록시 대상도 같은 포트로 수정해야 합니다.

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

빌드는 프로젝트 루트에서 `./mvnw package`, `frontend/`에서 `npm run build`로 실행합니다. 프론트엔드 결과물은 `frontend/dist/`, 백엔드 JAR은 `target/`에 생성됩니다. 현재 백엔드 JAR에 프론트엔드가 자동 포함되지는 않습니다.

## 데모 사용 순서

1. `http://localhost:3000/create`에서 실제로 열 수 있는 URL과 보호 옵션을 입력합니다.
2. 생성된 공유 코드를 복사하고 `/s/공유코드`에 접속하거나 홈에서 코드를 입력합니다.
3. 열람자 이름과 설정한 비밀번호를 입력해 원본 링크 열기 버튼을 확인합니다.
4. `/manage`에서 해당 링크의 상세 화면을 열어 열람 기록을 확인합니다.

## 데모 데이터와 제한 사항

- H2 메모리 DB를 사용하므로 백엔드를 종료하면 변경 데이터가 사라집니다. 시작할 때 `data.sql`의 샘플 링크 3개와 열람 기록이 생성됩니다.
- 샘플 URL은 예시 주소입니다. 비밀번호가 있는 샘플 `abc123`, `demo01`은 더미 해시를 사용하므로 인증 시연에는 새 링크를 생성하세요.
- 계정 인증과 링크 소유자별 권한 검사는 구현되어 있지 않습니다. 수신자 이름은 표시용으로 저장하며 접근 제한에 사용하지 않습니다.
- 비밀번호 처리는 데모용 `String.hashCode()` 방식입니다. 실제 비공개 링크 서비스로 운영하기 전에 비밀번호 저장 방식과 관리 API의 인증·권한 처리를 구현해야 합니다.
- 자동 테스트는 아직 없으며 Maven 설정은 테스트를 건너뛰도록 되어 있습니다.

## 기술 스택

- **백엔드**: Spring Boot 2.7 (Java 8) + spring-boot-starter-jdbc + H2 (in-memory)
- **프론트엔드**: Vite + React 19 + TypeScript
- **DB**: H2 (기본, 별도 설치 불요) / MySQL 전환 가능

## 프로젝트 구조

```
plink/
├── pom.xml                          # Maven 설정
├── mvnw                             # Maven wrapper
├── scripts/run-dev.sh               # 개발 서버 실행 스크립트
├── src/main/java/com/plink/         # Spring Boot 백엔드
│   ├── PlinkApplication.java
│   ├── config/WebConfig.java
│   ├── controller/LinkController.java
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
