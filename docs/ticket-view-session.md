# 등록된 입장권의 본인 확인 유지 방식

등록이 끝난 입장권 링크는 패스키로 본인 확인을 거쳐야 좌석·이메일·입퇴장 상태·쿠폰이 보인다
(`efb738b`). 이 문서는 확인 결과가 지금 어떻게 유지되는지와, 바꾼다면 고를 수 있는 방안을 정리한다.
**현재 결정: 지금 방식을 유지한다 (2026-10-10).**

## 현재 방식

- 패스키 확인에 성공하면 서버가 그 브라우저의 HTTP 세션에 `plink.ticket.viewer.{ticketId}` 속성을
  기록한다. 값은 `만료시각|보유자ID@등록시각` 형태다 (`TicketPasskeyService.markViewer`).
- 브라우저는 세션 쿠키(`JSESSIONID`, HttpOnly, SameSite=Lax, 운영은 Secure)만 가진다.
- 입장권 조회(`GET /api/tickets/{sessionId}/{token}`)마다 세 가지를 확인한다.
  1. 세션에 확인 기록이 있는가
  2. 만료시각(확인 후 12시간)이 지나지 않았는가
  3. 기록한 등록 정보가 지금과 같은가. 재발급·양도로 다시 등록되면 즉시 무효가 된다.
- 하나라도 맞지 않으면 행사 이름·일시·장소만 돌려주고, 화면은 패스키 확인을 띄운다.
- 얼굴 등록 상태 조회와 얼굴 동의·등록·삭제, 실시간 알림(WebSocket)도 같은 확인을 요구한다.
- 입장 QR은 이와 별개로 열 때마다 지문·얼굴 인증을 거친다.

### 실제로 확인이 풀리는 경우

| 경우 | 비고 |
|---|---|
| 확인 후 12시간 경과 | `TicketPasskeyService.VIEW_MILLIS` |
| 30분 동안 서버 요청 없음 | `server.servlet.session.timeout`을 설정하지 않아 Tomcat 기본값 30분이 적용된다. 실제로는 대부분 이 조건이 먼저 걸린다. |
| 서버 재시작·배포 | 세션을 서버 메모리에만 둔다. |
| 재발급·양도 | 등록 정보가 바뀌는 즉시 무효. 열려 있던 실시간 연결도 끊는다. |
| 다른 브라우저·시크릿 창 | 쿠키가 따로라 각자 확인한다. |

입장권 화면이 열려 있으면 혼잡도 정보를 1분마다 요청하므로 세션이 유지된다. 휴대폰을 잠그거나
다른 앱에 있다가 30분이 지나면 보통 다시 확인해야 한다. 행사장 대기 중에 지문을 한 번 더 요구할 수
있다는 뜻이다.

## 바꾼다면 고를 수 있는 방안

### 1. 세션 유지 시간을 늘린다

`application.yml`에 `server.servlet.session.timeout: 12h`를 추가한다.

- 장점: 설정 한 줄. 지금 구조를 그대로 쓴다.
- 단점: 관리자 콘솔 로그인도 같은 세션 설정을 쓰므로 관리자 세션도 12시간 유지된다.
  서버 재시작 시 풀리는 것은 그대로다. 유휴 세션이 메모리에 오래 남는다.

### 2. 입장권 전용 서명 쿠키 (추천)

확인에 성공하면 입장권별 쿠키(예: `plink_tv_{ticketId}`)를 내려준다.

- 값: `ticketId`, 만료시각, 등록 정보(`보유자ID@등록시각`)를 `TICKET_TOKEN_SECRET`에서 파생한 키로
  HMAC 서명한다.
- 속성: HttpOnly, Secure, SameSite=Lax, Path는 `/api/tickets/{sessionId}/` 와 `/ws/tickets/{sessionId}/`
  로 한정, Max-Age 12시간.
- 조회 시 세션 대신 이 쿠키를 검증한다. 등록 정보가 바뀌면 서명은 맞아도 내용이 달라 무효가 된다.
- 장점: 관리자 세션과 분리된다. 서버를 재시작해도 유지된다. 여러 서버로 늘려도 공유 저장소가 필요
  없다. 재발급·양도 시 즉시 무효인 성질도 지킨다.
- 단점: 구현량이 1번보다 많다 (발급·검증, WebSocket 핸드셰이크에서 쿠키 읽기, 테스트).
  쿠키를 강제로 끝낼 서버 쪽 수단이 등록 정보 변경뿐이다. 필요하면 서명 키를 바꿔 전부 끊는다.

### 3. 지금처럼 둔다 (현재 선택)

- 장점: 가장 엄격하다. 휴대폰이 잠깐 남의 손에 있어도 30분 뒤면 다시 확인을 요구한다.
- 단점: 자주 다시 확인해야 한다. 배포할 때마다 모든 관람객이 다시 확인한다.

## 관련 코드

- `src/main/java/com/plink/ticket/service/TicketPasskeyService.java`: `markViewer`, `canView`, `viewerUntil`
- `src/main/java/com/plink/ticket/service/TicketService.java`: `view(resolved, viewer)` (잠긴 응답)
- `src/main/java/com/plink/ticket/controller/TicketController.java`: `requireViewer`
- `src/main/java/com/plink/ticket/live/TicketSocketHandler.java`, `LiveEvents.java`: 실시간 연결 확인·만료·끊기
- `frontend/src/components/TicketPage.tsx`: 잠금 화면, 자동 확인, 휴대폰 변경 시 재발급
