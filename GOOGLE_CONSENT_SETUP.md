# Google 동의 화면 개인정보처리방침 연결

사이트 로그인 화면과 푸터는 `/privacy`에 연결되어 있습니다.

Google 동의 화면의 링크는 앱 코드가 아니라 해당 OAuth 클라이언트를 소유한 Google Cloud 프로젝트에서 등록합니다.

1. 운영자명과 개인정보 문의 주소를 확정하고 개인정보처리방침 초안을 검토합니다. 적용 예정인 365일 자동 삭제와 계정 삭제 기능의 실제 제공 여부도 맞춥니다.
2. 사이트 배포 후 로그인하지 않은 상태에서 `https://lyuni.ddak.app/privacy`가 열리는지 확인합니다.
3. Google Cloud Console → Google Auth Platform → Branding에서 앱 링크를 설정합니다.
   - 홈페이지: `https://lyuni.ddak.app/`
   - 개인정보처리방침: `https://lyuni.ddak.app/privacy`
4. 실제 운영자의 사용자 지원 이메일과 개발자 연락처를 등록하고 저장합니다. 콘솔에서 검증 또는 게시를 요구하면 해당 절차를 진행합니다.
5. Google 로그인 동의 화면에서 개인정보처리방침 링크가 같은 주소를 여는지 확인합니다. 계정 선택 화면에는 이 링크가 표시되지 않을 수 있습니다.

OAuth 콜백 주소는 기존 `https://lyuni.ddak.app/login/oauth2/code/google`을 유지합니다. 개인정보처리방침 주소를 콜백 주소로 넣지 않습니다.

현재 상태: 사이트 코드 연결 완료. Google 콘솔 등록은 미완료.

공식 안내: https://support.google.com/cloud/answer/15549049
