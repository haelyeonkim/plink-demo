# 2026-10-10 마이그레이션 충돌 및 실행 스크립트 변경

운영 DB와 작품 브랜치가 서로 다른 변경에 V32를 사용해 백엔드가 시작되지 않았다. 운영 V32 원본을 복원하고 작품 변경을 V33·V34로 이동해 두 환경의 Flyway 검증을 통과했다. 별도로 프론트엔드 종료 누락과 중복 실행 시 헬스체크 오판을 수정했다.

이 문서는 2026-10-10 작업 당시의 확인 결과다. 현재 서버의 실시간 상태를 의미하지 않는다. 테이블 구성은 [DB 스키마](database-schema.md)를 참고한다.

## 1. 확인한 오류

| 현상 | 확인한 원인 및 증거 |
|---|---|
| `run-dev.sh stop` 이후 화면이 계속 응답 | 기존 스크립트가 `npx` PID를 저장해 실제 Vite 자식 프로세스가 남을 수 있었다. 3000 포트에 남은 Vite를 확인하고 종료했다. |
| `Backend failed` | 백엔드 로그에 `Migration checksum mismatch for migration version 32`가 기록됐다. |
| 새 프론트엔드가 실행되지 않음 | 기존 Vite가 3000 포트를 사용해 `Port 3000 is already in use`가 발생했다. |
| 중복 실행 실패에도 `Health check passed` 출력 | 새 프로세스가 종료돼도 기존 서버의 HTTP 응답만으로 성공을 판단했다. |

V32는 테이블명이 아니라 Flyway 변경 버전이다.

| 기존 적용 대상 | V32 파일 | 체크섬 |
|---|---|---|
| 운영 `public` | `V32__test_events.sql` | `1145741098` |
| 작품 브랜치 및 stage | `V32__artwork_sale_status.sql` | `-1494080826` |

V31까지의 기존 파일명·체크섬은 운영 이력과 일치했다. 원격 `origin/main`을 가져온 뒤 운영 V32 원본을 찾았고, 원본 체크섬도 일치했다.

## 2. 최종 마이그레이션 구성

| 버전 | 파일 | 변경 내용 |
|---|---|---|
| V32 | [V32__test_events.sql](../src/main/resources/db/migration/V32__test_events.sql) | `event_session.test_event` 추가 |
| V33 | [V33__artwork_sale_status.sql](../src/main/resources/db/migration/V33__artwork_sale_status.sql) | `artwork.sale_status` 추가 |
| V34 | [V34__shared_artwork_status.sql](../src/main/resources/db/migration/V34__shared_artwork_status.sql) | `artwork.archived`, `position` BIGINT, `content_artwork` 연결 |

작품 SQL의 내용은 유지하고 버전·파일명만 변경했다. 운영에 적용된 V32 이력을 덮어쓰거나 체크섬만 바꾸는 `repair`는 실행하지 않았다.

적용 순서:

1. 운영과 stage 스키마를 각각 `pg_dump`로 백업하고 `pg_restore --list`로 목록을 확인했다. 실제 복원 리허설을 수행한 것은 아니다.
2. [stage 이력 조정 SQL](../scripts/reconcile-stage-v32.sql)을 트랜잭션으로 실행했다. 기존 작품 V32→V33, V33→V34의 버전·파일명만 바꾸고 체크섬·적용 시각은 유지했다.
3. Stage에 빠진 V32를 Flyway `outOfOrder=true`로 한 번 적용했다. 이후 기본 설정의 `validate`를 통과했다. 상시 설정에는 `outOfOrder`를 추가하지 않았다.
4. 운영에 V33·V34를 Flyway로 적용하고 기본 `validate`를 통과했다.
5. `.stage/app.jar`를 새 마이그레이션이 포함된 JAR로 교체했다. 이전 JAR는 백업했다.

이력 조정 SQL은 당시 stage 상태에만 사용하는 일회성 도구다. 예상 파일명·체크섬과 일치하지 않거나 이미 조정된 경우 중단한다. 정상 시작이나 재시작 때 다시 실행하지 않는다. 버전을 바꾸기 전 JAR로 되돌리면 다시 검증 충돌이 생길 수 있다.

백업 위치: `/home/lyuni/plink-db-backups/2026-10-10/`

- `public.dump`
- `stage_artwork_20261007.dump`
- `stage-app-before.jar`

백업 디렉터리는 권한 700, 파일은 600으로 보관했다. 저장소에는 포함하지 않았다.

## 3. 실행 스크립트 변경

[scripts/run-dev.sh](../scripts/run-dev.sh)에 다음 변경을 반영했다.

- `npx vite` 대신 `node .../vite`를 직접 실행해 실제 Vite PID를 저장한다.
- `stop`은 저장된 PID를 종료하고, 이 프로젝트의 Vite 개발 서버 실행 경로와 인자를 검사해 남은 프로세스도 종료한다. Stage의 `vite preview`는 이 추가 검색 대상에서 제외한다.
- Vite에 `--strictPort`를 지정해 지정 포트를 사용할 수 없으면 실패하게 한다.
- 빌드·PID 파일 갱신 전에 Python 3으로 두 포트의 바인딩 가능 여부를 검사한다.
- HTTP 헬스체크 전후에 이번에 시작한 PID가 살아 있는지 확인한다.

포트 사전 검사는 검사 시점의 상태만 확인한다. 헬스체크 성공도 장기 실행을 보장하지 않으며, 실행 실패 시 자동으로 모든 프로세스를 정리하는 기능은 없다.

## 4. 환경별 실행 방법

명령은 프로젝트 루트 `/home/lyuni/plink-demo`에서 실행한다.

| 대상 | 스크립트 | 기본 프론트 / 백엔드 | DB 스키마 |
|---|---|---|---|
| 공개 데모용 개발 서버 구성 | `scripts/run-dev.sh` | 3000 / 8080 | `public` |
| 별도 stage | `scripts/run-stage.sh` | 11000 / 11080 | `stage_artwork_20261007` |

저장소 문서는 `https://lyuni.ddak.app/`을 개발 서버 포트로 연결하는 구성으로 설명한다. 이번 작업에서 외부 프록시 설정 자체를 확인한 것은 아니다.

공개 데모 재시작 절차:

```bash
bash scripts/run-dev.sh stop
ss -ltnp
bash scripts/run-dev.sh
```

`ss`에서 대상 포트가 종료됐는지 먼저 확인한다. 시작 명령은 백엔드를 다시 빌드한다. `run-dev.sh`에는 별도 `restart` 인자가 없다.

Stage는 목적에 따라 다음 명령을 사용한다.

```bash
bash scripts/run-stage.sh status
bash scripts/run-stage.sh restart  # 마지막 배포 산출물로 재시작
bash scripts/run-stage.sh deploy   # 현재 코드를 빌드하고 재시작
```

실패 시 재실행 전에 확인할 항목:

```bash
tail -n 100 logs/backend.log
tail -n 60 logs/frontend.log
ss -ltnp
curl -fsS --max-time 5 http://127.0.0.1:8080/api/auth/session
```

로그는 누적되므로 오류 시각과 최신 `Starting PlinkApplication` 시각을 함께 확인한다. Stage 로그는 `.stage/logs/`에 있다. SQL 파일을 이름 변경한 뒤에는 기존 빌드 폴더에 옛 파일이 남지 않도록 `clean` 빌드를 사용한다.

## 5. 검증 결과 및 남은 확인

| 검증 | 결과 |
|---|---|
| `./mvnw -q clean test package` | 214개 테스트 통과, 패키징 성공 |
| [MigrationUpgradeTest](../src/test/java/com/plink/MigrationUpgradeTest.java) | 운영 업그레이드와 stage 이력 조정 후 데이터 보존 시나리오 2개 통과 |
| `frontend`에서 `npm run build` | 성공 |
| 운영·stage Flyway `validate` | 모두 성공 |
| `bash -n scripts/run-dev.sh`, `git diff --check` | 성공 |
| 마지막 포트·PID 헬스체크 보완 후 실제 재기동 | 미실행 |

테스트 최초 실행에는 샌드박스의 Mockito 연결 제한이 있었으며, 허용된 환경에서 전체 테스트를 다시 실행해 통과했다.

적용 전후 비교한 데이터:

| 환경 | 작품 | 입장권 | 콘텐츠 | 행사 |
|---|---:|---:|---:|---:|
| 운영 | 21 | 2 | 1 | 1 |
| Stage | 8 | 0 | 11 | 0 |

Stage 판매 상태도 미설정 7개·`hold` 1개로 유지됐다. 이 비교는 주요 테이블 건수와 판매 상태 확인이며 전체 데이터의 바이트 단위 대조는 아니다.

2026-10-10 03:35 UTC 백엔드 로그에서 V34 검증 통과 및 8080 기동 성공을 확인했다. 그러나 후속 조회에서는 백엔드 프로세스와 8080 리스너가 없었고 HTTP 연결도 실패했다. 해당 로그에는 종료 원인이 남아 있지 않아 이 문제는 미확정 상태다. 프론트엔드는 기존 3000 프로세스가 남아 있었다.

사용자의 요청에 따라 에러 확인 이후 추가 재시작은 하지 않았다. 다음 기동 시에는 스크립트 종료 후에도 백엔드 PID와 HTTP 응답이 유지되는지 확인하고, 다시 사라지면 프로세스 종료 주체 및 시스템 로그를 조사해야 한다.
