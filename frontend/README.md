# 실제 로컬 사용자 화면

참고 화면의 흰색·파란색 디자인을 표준 ES modules/CSS로 구현했다. 외부 프레임워크·런타임 npm 패키지 없이 Spring이 같은 출처에서 제공한다. 원본은 src/, Gradle processResources가 static/app으로 포함한다. bootRun과 배포 JAR/Docker가 동일한 소스를 사용한다.

## 현재 연결된 기능

홈 → Google 로그인 → 문제 입력 → 내용 확인 → 실제 DB 저장 → 내 라이브러리 → 상세/수정/삭제 → 로그아웃/재로그인. 기존 실행 이력·원문 조회는 저장된 API 응답이 있을 때만 표시한다. SESSION/CSRF는 같은 출처의 실제 서버와 통신한다. 인증 정보와 사용자 문제를 localStorage/sessionStorage에 보관하지 않는다.

AI HTTP 파이프라인은 아직 연결되지 않아 새 분석 버튼은 비활성 상태다. 가짜 목록·점수·진행률·법령 근거·추천을 생성하지 않는다. 기존 개발 Mock 실행은 테스트 기록으로만 구분하며 실제 검증 결과처럼 표시하지 않는다. 라이브러리 검색/필터는 불러온 목록 범위이고 다음 페이지는 실제 cursor로 조회한다.

## 실행

[Google 설정](../docs/runbooks/oauth-preparation.md)을 따라 로컬 .env를 준비한 뒤 루트에서 docker compose up -d --wait, backend에서 gradlew.bat bootRun을 실행한다. 접속은 http://localhost:8081, 승인 callback은 http://localhost:8081/login/oauth2/code/google로 통일한다. .env의 SERVER_PORT도8081로 설정한다. 별도 프런트 개발 서버/프록시/CORS 허용은 필요하지 않다.

## 검증

Node20+에서 루트 기준 `node frontend/scripts/check.mjs` 또는 frontend에서 `npm test`. JS 구문/입력과 Unicode·한국 날짜·XSS·공식 링크·Mock 분리·세션/CSRF/오류·자동 재전송 금지 등을 검사한다. Java의 AuthFlowIT가 실제 HTTP 정적 리소스/새로고침 경로·공개 설정 경계와 실제 PG OIDC 세션을 검사한다. 실제 Google와 브라우저의 입력/저장/수정/삭제·모바일 검수는 별도 증거로 확인한다.
