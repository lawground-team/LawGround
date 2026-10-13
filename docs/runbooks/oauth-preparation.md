# Google OIDC 설정과 인증 검토

통합4주차(원본5주차)에서 OIDC 회원 연결·JDBC 세션·내 정보·CSRF·현재 세션 로그아웃을 구현했다. 기본 프런트 로그인·문제 관리·라이브러리 화면도 실제 API에 연결되어 있다. 테스트 소스의 서명된 OIDC 공급자로 실제 loopback HTTP/임시 PostgreSQL 흐름을 검사한다. 이 자동 검사는 실제 Google 계정으로 수행한 브라우저 검수와 구분한다.

## Google 설정

기존 DB와 볼륨을 유지한다. 다음 기동 시 Flyway V006이 auth_identities, spring_session, spring_session_attributes를 추가한다. JDBC 세션 DDL은 Boot3.5.16 BOM이 선택하는 Spring Session JDBC3.5.7 artifact의 공식 PostgreSQL SQL이며 자동 schema 초기화는 never다.

Google OAuth Client에서 사용할 origin과 `/login/oauth2/code/google` callback을 승인한다. 개발과 운영 Client를 분리한다. localhost와127.0.0.1을 섞지 않는다. `.env` 또는 배포 비밀값 저장소에 아래 값을 직접 설정하되 Git·이슈·문서·로그에 값을 복사하지 않는다.

```properties
LAWGROUND_DEV_AUTH_ENABLED=false
LAWGROUND_OIDC_ENABLED=true
GOOGLE_CLIENT_ID=<Google client ID>
GOOGLE_CLIENT_SECRET=<Google client secret>
GOOGLE_REDIRECT_URI=http://localhost:8081/login/oauth2/code/google
SERVER_PORT=8081
```

현재 실제 사용자 화면은 localhost:8081로 통일한다. 승인 URI와 GOOGLE_REDIRECT_URI 양쪽 모두 같은 origin/포트여야 한다. 운영은 HTTPS가 필수이며 prod는 OIDC 비활성/필수 설정 누락/dev-auth/혼합 local·test 프로필이면 기동 실패한다. provider 주소는 Google 공식 기본값을 사용하며 런타임 테스트 공급자 환경변수는 제공하지 않는다. 공급자 콘솔 권한/키 발급은 별도다.

## API 검토 순서

1. 동일 origin 브라우저에서 GET `/api/v1/auth/csrf`: 익명200, `{token,headerName,parameterName}`, no-store, SESSION 쿠키.
2. GET `/api/v1/me`: 로그인 전 JSON401. 로그인 HTML로 자동 이동하지 않는다.
3. 브라우저에서 `/oauth2/authorization/google`로 이동하여 로그인. 성공은 실제 라이브러리 화면 `/library`, 취소/실패는 안내 화면 `/login?error=oauth_failed`로 이동한다. `/api/v1/me`로도 인증 상태를 확인한다. callback URL/authorization code를 로그나 문서에 붙이지 않는다.
4. GET `/api/v1/me`: `{id,displayName}`만200. 새 `/auth/csrf` 토큰으로 문제 등록 등 보호 API를 호출한다. 로그인 전에 받은 토큰은 거절돼야 한다.
5. POST `/api/v1/auth/logout`: 현재 CSRF를 X-CSRF-TOKEN에 넣고 본문 없이 요청. 정상204/SESSION 만료, CSRF 누락·불일치403. GET은 로그아웃하지 않는다.
6. `/me`는 다시401. 새 익명 CSRF를 받아도 보호된 쓰기는401. 다른 브라우저의 같은 계정 세션은 유지하고, 같은 계정 재로그인은 기존 회원/문제를 유지한다.

세션은 idle30분/인증 후 absolute12시간이다. 회원 비활성화를 감지하면403과 세션 폐기. local 개발 회원 데이터는 Google 계정으로 자동 이전하지 않는다.

## 독립 재현

Java21/Docker Linux 엔진 준비 후 backend에서:

```powershell
.\gradlew.bat integrationTest --tests com.lawground.auth.AuthFlowIT --no-daemon
.\gradlew.bat clean check bootJar --no-daemon
```

테스트는 authorization-code 교환·서명/JWKS·state/nonce/issuer/audience/만료·취소·userinfo sub를 실제 HTTP로 검사한다. JDBC 재조회/독립 repository 복원·만료 cleanup·세션 회전/CSRF 갱신·동시 가입 rollback·다른 계정 분리도 검사한다. 테스트 principal 주입만으로 OIDC 프로토콜 성공을 주장하지 않는다. 테스트 공급자는 src/test에만 있어 bootJar에는 포함되지 않는다. 실제 Google 성공/취소와 실제 UI E2E는 별도 증거가 있어야 완료로 기록한다.
