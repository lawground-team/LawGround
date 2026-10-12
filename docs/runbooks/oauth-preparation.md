# 다음 인증 단계의 준비 항목

이번 단계에는 Google OAuth 로그인과 JDBC 세션을 구현하지 않았다. 기존 개발 principal로 CRUD·실행자 권한을 검증한다.

다음 인증 구현에서 사용할 환경변수 이름은 `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`다. 아직 Spring OAuth registration으로 연결하지 않았으며 `.env.example`의 빈 항목은 준비 목록이다. 비밀값은 Git·이슈·문서·로그에 기록하지 않는다.

개발과 운영 Google OAuth Client를 분리하고 허용 redirect URI를 실제 접속 origin과 일치시킨다. 예정 callback 경로는 `/login/oauth2/code/google`이다. localhost와 127.0.0.1을 섞으면 세션/쿠키 origin이 달라지므로 서비스 실행 주소 하나를 선택한 뒤 provider 설정과 함께 확정한다. 운영은 HTTPS와 동일 출처 쿠키 정책으로 검증한다.

키와 승인 URI가 준비되면 Google OIDC + Spring Session JDBC, `/me`, CSRF, 현재 세션 로그아웃을 구현한다. 실제 Google 접근 전에는 별도 테스트 OIDC provider로 issuer/subject·nonce·state·세션 만료/회전·권한을 검증한다. 테스트 provider는 아직 이 저장소에 추가하지 않았다. 제공되지 않은 키를 만들거나 실제 OAuth 성공으로 처리하지 않는다.
