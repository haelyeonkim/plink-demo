# 작품 목록·공유 판매 상태 검증 — 2026-10-07

최신 `main` (`34293c3`) 통합 및 아래 검증을 완료했다. 실제 브라우저에서 발견한 PATCH CORS 누락도 수정했다.

## 구현 범위

- 계정 관리 권한을 가진 관리자의 전체 작품 목록, 검색, 판매 상태 필터, 페이지 이동 및 비공개 이미지 조회.
- 미판매 / hold / sold 변경과 원본·수신자별 선택 링크의 판매 상태 공유.
- SSE로 열린 링크에 상태를 전달하고 인증 권한을 주기적으로 재검사.
- 기존 선택 링크의 미연결 작품을 동일 계정의 원본 작품에 명시적으로 연결.
- 작품 편집·순서 변경 시 ID 유지. 원본 목록에서 제거한 작품도 이전 링크의 연결을 유지하며, 전달 당시 설명·가격은 보존.

## 이번 검증에서 보완한 내용

- 기존 SSE 테스트의 명칭을 실제 검사하는 링크 만료 조건에 맞췄다.
- 수신자 철회와 세션 종료 후 스트림이 종료되고, 후속 판매 상태가 전달되지 않으며, 재연결이 거부되는 회귀 테스트를 추가했다.
- 연결된 PostgreSQL의 Flyway 이력을 읽기 전용으로 조회한 결과 V30은 `coupon quantities`, V31은 `gate coupon grants`로 이미 적용되어 있었다.
- 최신 `origin/main` (`34293c3`)을 fast-forward로 통합하고 작품 변경사항을 복원했다. 통합 전 백업은 `artwork-status-before-main-integration-2026-10-07` stash에 보존했다.
- 작품 마이그레이션은 V32 `artwork sale status`, V33 `shared artwork status`다. 기존 쿠폰 V30·V31과 함께 검증했다.
  - 2026-10-10 병합 시 운영 DB에 적용된 번호에 맞춰 V33 `artwork sale status`, V34 `shared artwork status`로 파일 이름을 바꿨다 (V32는 테스트 행사). 내용은 같아 체크섬이 운영 이력과 일치한다.
- 실제 브라우저가 보내는 Origin 헤더 때문에 PATCH가 CORS에서 거부되는 오류를 수정했다. 허용 Origin의 PATCH 및 preflight 성공, 다른 Origin 거부를 회귀 테스트로 확인했다.

## 검증 결과

- 통합 후 최종 백엔드 테스트: 212개, 실패 0, 오류 0, 건너뜀 0. H2 테스트 DB 사용. `./mvnw -q test package -Dbuild.dir=.stage/build`로 테스트 및 실행 JAR 빌드 통과.
- 샌드박스 내부에서는 Mockito JVM self-attach가 실패하여 승인된 샌드박스 외 실행으로 검증했다.
- 프론트엔드 `npm run build` 통과. 통합 후 약 652kB JS 번들의 500kB 경고는 남아 있다.
- Playwright의 모의 API 기반 브라우저 검증 통과: 페이지 이동, 판매 상태 필터, 검색 결과 없음, 서버 오류, 계정 관리 권한 차단, 390px 모바일 가로 넘침 검사. 브라우저 오류 없음.
- 두 링크의 판매 상태 동기화, 링크 만료, 수신자 철회, 세션 종료, 원본 ID 유지, 스냅샷 보존, 타 계정 연결 거부 및 이미지 접근 제어는 서버 통합 테스트로 확인했다.
- PostgreSQL 17.6의 별도 `stage_artwork_20261007` 스키마를 Flyway V31까지 구성하고 합성 원본 작품 2개 및 기존 선택 스냅샷을 넣은 뒤 V32·V33으로 업그레이드했다. 동명 작품의 ID·가격 보존, 원본 연결 생성, 기존 선택 스냅샷 자동 연결 방지를 SQL로 확인했다. 운영 `public` 스키마는 V31 그대로임을 확인했다.
- 실제 PostgreSQL·HTTP 서버·Chromium을 사용한 브라우저 검증 통과: 관리자 로그인, 수신자 2명의 WebAuthn 등록 및 새로고침 후 재인증, 관리자 화면에서 hold/sold/미판매 변경, 두 브라우저의 SSE 갱신, 원본 편집·정렬 후 ID 및 스냅샷 보존, 수신자 철회 후 화면 닫힘 및 다른 수신자의 계속된 갱신, 익명 접근 차단.
- WebAuthn은 Chromium 가상 인증기를 사용했다. 실제 휴대폰의 지문·Face ID 및 패스키 동기화는 별도 실기기 점검 대상이다.
- 브라우저 스크립트: `node scripts/check-artwork-browser.mjs`. 로컬 스테이징 `http://localhost:11000` 전용이며 `.stage/e2e-credentials.json`의 합성 관리자 자격 증명을 읽는다. 합성 데이터만 추가하고 이메일은 발송하지 않는다. 운영 서버에 실행하지 않는다.
- 스크린샷: `.stage/screenshots/artwork-admin.png`, `.stage/screenshots/artwork-recipient.png`. 자격 증명·스테이징 설정·이미지는 Git에서 제외한다.

## 배포 전에 남은 작업

1. 검증된 변경사항을 검토하고 운영 배포 범위를 확정한다. 운영 DB V32·V33 적용 및 3000/8080 서버 재시작은 아직 수행하지 않았다.
2. 운영 배포 시 기존 DB 백업·복구 지점을 확인하고 마이그레이션 적용 후 관리자 목록 및 수신자 접근을 점검한다. 기존 선택 링크는 관리자가 명시적으로 원본을 연결해야 판매 상태를 공유한다.

검증 서버는 `.stage/app.jar`와 `.stage/dist`를 사용하며, `bash scripts/run-stage.sh start`로 시작한다. 검증용 주소는 localhost이고 공개 HTTPS 배포 주소는 아직 지정하지 않았다.

PDF LLM·OCR 연동 등 별도 기능은 기존 설계 및 후속 작업 목록에 남아 있다.
