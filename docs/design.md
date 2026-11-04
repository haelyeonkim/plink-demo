# P-Link 설계와 의도

이 문서는 링크 공유와 입장권 관리의 주요 설계 결정, 그 이유와 제약을 설명한다.
2026-11-04의 구현을 기준으로 작성했다. 설치·실행은 [README](../README.md),
DB 구조와 적용 이력은 [DB 문서](database-schema.md)를 참고한다.
기능을 변경할 때에는 아래 관련 코드와 설계 설명을 함께 갱신한다.

## 두 도구와 공통 기반

링크 앱은 `/links`, 입장권 앱은 `/tickets`를 중심으로 동작하며 하나의 출처(origin)와
Spring Boot 백엔드를 공유한다. 앱별 화면은 분리하고 인증·헤더·대화상자·기본 스타일은
`frontend/shared/src`에 둔다. 공통 경험을 유지하면서 한 도구의 화면이 다른 도구의 구현에
직접 의존하지 않도록 하는 구조다. 앱 사이 이동은 공통 `AppLink`와 `useGo`를 사용한다.

백엔드는 컨트롤러에서 HTTP 요청을 처리하고 서비스에서 업무 규칙과 트랜잭션을 다룬다.
스키마 변경은 Flyway 마이그레이션으로 기록하며 개발용 데이터와 운영 스키마를 분리한다.

관련 기준: [저장소 구조와 협업 규칙](../AGENTS.md).

## 작품 발송 기록과 현재 정보의 분리

수신자별 작품 링크를 만들 때 선택 작품, 콘텐츠 스냅샷, 링크와 수신자를 하나의 트랜잭션으로
저장한다. 선택 작품은 관리자 소유인지 확인한다. 메일은 저장이 끝난 뒤 발송한다.
메일 서버를 기다리는 동안 DB 트랜잭션을 붙잡거나, 롤백된 주소를 수신자에게 보내지 않기 위해서다.
발송 실패와 저장 실패는 따라서 별도로 다뤄야 한다.

저장된 발송 스냅샷은 유지하되, 조회할 때 연결된 작품의 현재 설명·가격·판매 상태를 반영한다.
이미지는 발송별 접근 권한과 참조를 유지한다. 이는 발송 기록을 보존하면서 판매 정보의 변경도
전달하려는 설계다. 수신자가 보는 모든 내용이 발송 시점 그대로 고정된다는 뜻은 아니다.

관련 코드: [ArtworkDeliveryService](../src/main/java/com/plink/service/ArtworkDeliveryService.java),
[ArtworkStatusService](../src/main/java/com/plink/service/ArtworkStatusService.java).

## 티켓 권리, 본인 확인, QR의 분리

입장 권리는 서버의 티켓 상태로 관리하고, 등록된 보유자의 본인 확인은 패스키로 수행한다.
QR은 제한된 시간 동안 제시 권한을 전달하는 수단이다. QR만으로 권리 상태가 확정되지는 않으며,
게이트 처리 시 서버가 티켓 상태와 입·퇴장 정책을 다시 확인한다.

등록 후 본인 확인 결과는 HTTP 세션에 유지한다. 현재는 유효한 확인 세션으로 QR을 열 수 있으며,
매번 패스키를 다시 요구하지 않는다. 세션의 확인 기록에는 등록 정보를 연결해 양도·재발급으로
보유자 등록이 바뀌면 이전 확인이 효력을 잃도록 한다. 양도는 별도의 패스키 확인을 요구한다.
이 구분은 반복 입·퇴장의 편의와 권리 이전의 확인 강도를 함께 고려한다.
세션 만료·서버 재시작에 따른 제약은 [본인 확인 유지 설계](ticket-view-session.md)에 정리한다.

개인 URL 검증에는 토큰의 HMAC을 사용한다. 현재 구현은 재표시·재발송을 위해 토큰 암호화본도
저장한다. 따라서 DB에 해시만 저장해 URL을 복원할 수 없다는 보장은 하지 않는다.
암호화 키와 복호화 기능에 대한 접근 관리도 이 설계의 일부다.

관련 코드: [TicketService](../src/main/java/com/plink/ticket/service/TicketService.java),
[TicketController](../src/main/java/com/plink/ticket/controller/TicketController.java),
[PresentationService](../src/main/java/com/plink/ticket/service/PresentationService.java),
[TextCipher](../src/main/java/com/plink/ticket/service/TextCipher.java).

## 재입장과 입·퇴장 원장

재입장을 지원하므로 티켓을 한 번 사용한 뒤 폐기하는 방식으로는 운영할 수 없다.
대신 장외에서 입장하고 장내에서 퇴장한다는 상태 전이 규칙을 적용한다.
티켓 행을 잠근 상태에서 판정해 동시 스캔이 같은 상태를 각각 승인하지 않도록 한다.
이는 이미 장내에 있는 티켓의 QR을 다른 사람이 다시 입장에 사용하는 경로를 제한한다.
퇴장 후 다른 사람이 사용하는 순차 공유까지 재실 상태만으로 막는 것은 아니다.

입·퇴장 이력은 원장에 추가하며 보정도 별도 기록으로 남긴다. 퇴장 누락은 현장에서 발생하므로
정책으로 처리하되, 같은 날 즉시 재입장을 허용하는 보정은 제한한다.
현재 구현은 경과 시간과 정책을 확인하고, 행사장 기준 운영일이 바뀐 경우도 별도로 처리한다.
여러 날 진행되는 행사에서 전날의 퇴장 누락이나 재입장 횟수가 다음 날 입장을 막지 않게 하려는 의도다.

관련 코드: [AdmissionService](../src/main/java/com/plink/ticket/service/AdmissionService.java),
[VenueDay](../src/main/java/com/plink/ticket/service/VenueDay.java).

## 기기 교체와 양도

기기 교체는 기존 티켓에 인증기를 계속 추가하는 대신 토큰을 회전하고 기존 등록을 해제한 뒤
다시 등록하는 방식이다. 기존 기기의 접근을 끊으면서 티켓 자체와 운영 이력을 유지하려는 목적이다.

양도는 보유자의 신원을 복사하지 않고 권리만 이전한다. 받는 사람은 자신의 이메일을 확인하고
자신의 패스키를 등록한다. 양도 대기 중에는 별도의 수령 토큰을 사용하고, 완료되면 이전 보유자의
URL이 무효가 된다. 대기 중 기존 토큰을 유지하는 것은 보내는 사람의 취소를 지원하기 위해서다.
수락·취소·만료·재발급은 티켓과 양도 행을 같은 순서로 잠가 동시 요청 간 충돌을 제어한다.

관련 코드: [TicketService](../src/main/java/com/plink/ticket/service/TicketService.java),
[TransferService](../src/main/java/com/plink/ticket/service/TransferService.java).

## 얼굴 정보의 처리 경계

얼굴 등록은 동의를 기록하고 특징 벡터를 암호화해 저장한다. 동의 철회 시 템플릿을 삭제하고
보유기간이 끝난 템플릿도 정리한다. 회차 내 후보 검색에서는 최고 점수뿐 아니라 차순위와의 차이도
확인해 근접한 후보를 무리하게 동일인으로 판단하지 않도록 한다.
입장·재입장에 얼굴 확인을 요구할지는 행사 정책에 따라 달라진다.

특징 추출은 `FaceEmbedder` 인터페이스로 분리한다. 외부 얼굴 서비스가 없을 때 사용하는
`LocalFaceEmbedder`는 파이프라인 개발용 대역이며 실제 얼굴 인식을 제공하지 않는다.
외부 서비스 분리는 모델 실행 경계를 나누지만, 서비스 간 전송이나 백엔드의 생체 정보 접근을
자동으로 없애는 것은 아니다.

관련 코드: [FaceService](../src/main/java/com/plink/ticket/face/FaceService.java),
[TemplateCipher](../src/main/java/com/plink/ticket/face/TemplateCipher.java).
모델 실행 설명: [얼굴 서비스](../face-service/README.md).

## 위치 확인과 오프라인 운영의 한계

위치 확인은 멀리서 QR을 열어 전달하는 사용을 제한하기 위한 보조 신호다.
실내 위치 오차를 고려해 부정확하거나 누락된 측정은 판정 불가로 구분한다.
브라우저 위치 정보만으로 현장에 있다는 사실을 보장하지 않는다.

오프라인 단말은 다른 게이트의 현재 재실 상태를 알 수 없다. 따라서 오프라인 통과는 온라인과
같은 일관성을 보장하지 못하며, 동기화 시 충돌을 확인하는 운영상의 절충이다.
서버는 제출된 묶음을 촬영 시각 순으로 정렬하고 각 건을 별도 트랜잭션으로 검증한다.
거부된 건은 상태에 강제 반영하지 않고 조사할 충돌 기록으로 남긴다.
이는 모든 단말의 과거 상태를 촬영 시점으로 완전히 복원한다는 의미는 아니다.

제시 권한의 폐기와 코드 소비도 구분한다. 새 권한 발급으로 폐기된 이전 권한은 온라인에서는
거부되지만 제한된 오프라인 재처리에서는 검증 대상이 될 수 있다. 이미 사용한 코드의 재사용과
시간 제한 검사는 별도로 유지한다.

관련 코드: [GeoCheck](../src/main/java/com/plink/ticket/service/GeoCheck.java),
[OfflineSyncService](../src/main/java/com/plink/ticket/service/OfflineSyncService.java),
[PresentationService](../src/main/java/com/plink/ticket/service/PresentationService.java).
