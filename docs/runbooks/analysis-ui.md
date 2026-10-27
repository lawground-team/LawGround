# 사용자 분석 화면 연결과 브라우저 검사

홈 → 로그인 → 문제 입력 → 확인·저장 → 문제 상세의 근거 분석 → 대기/처리 → 실행 결과 → 관련 문제·라이브러리 흐름이다. 화면은 같은 Spring 서버의 `/app` 정적 소스와 `/api/v1`을 사용한다. 선택률·오답률·가짜 진행률은 없다.

## 활성화

`LAWGROUND_UI_ANALYSIS_ENABLED` 기본false. LIVE 검색과 검증도 모두 활성화된 운영자 명시 설정에서만 `app-config.analysisAvailable=true`다. 이는 실제 추론 품질 보증이 아니다. 키·고정 revision·법령 색인·기능별 readiness는 서버가 접수/처리 단계에서 검사한다. 준비가 안 된 상태에서 UI 플래그만 켜지 않는다. 실제 LLM 미준비 상태에서는 기본 비활성을 유지한다.

## 사용자 동작

- 비공개 문제는 본인만 분석할 수 있다. 다른 사람이 공급한 문제는 공개 승인된 EXAM_IMPORT/OFFICIAL_VERIFIED 기출에 한해 분석한다. 실행 이력과 결과는 요청한 회원에게만 공개한다.
- 분석 접수 시 member/question/revision별 UUID 멱등 키를 만든다. sessionStorage에는 진행 중 요청 키만 보관하고 본문·세션ID·토큰은 저장하지 않는다. 연결이 끊겨 접수 여부가 불명확하면 사용자가 ‘같은 분석 요청 확인’을 눌러 같은 키로 확인한다. 쓰기 요청은 자동 재전송하지 않는다.
- `GET /trace-runs/{id}/job`으로 새로고침 후에도 실제 처리 단계를 복구한다. PENDING·QUEUED·RUNNING·RETRY_WAIT만 조회를 반복한다. 2~10초 간격이며 백그라운드에서는10초로 줄인다. 화면을 떠나도 서버 작업은 유지된다.
- 취소 후 서버 상태를 다시 조회한다. 네트워크 실패·AI 미준비·계약 오류는 기술 실패로, HOLD는 판단 보류 결과로 구분한다.
- 결과는 당시 입력 snapshot·기준일·revision과 원래 선지를 표시한다. 선택적 선지 설명이 없으면 ‘설명 미제공’으로 남긴다. 정정문은 근거가 연결된 CONTRADICTS에서만 표시한다. 결과에서 새로운 정답 번호를 추정하지 않는다.
- 선지별 근거 링크는 실제 evidence로 이동하고 검수된 원문·공식 출처를 열 수 있다. SUPPORTED 실행에는 실제 추천 API 결과만 표시한다. 빈 추천을 임의 문제로 채우지 않는다.
- 로그아웃/401/계정 변경 시 개인 화면·진행 키·입력 상태를 지운다. 늦게 도착한 이전 계정 응답은 새 화면을 갱신할 수 없다.

## 재현 가능한 E2E

Node20+ / Java21 / Docker가 필요하다. 저장소 루트에서:

```sh
npm ci --prefix frontend --ignore-scripts
cd frontend
npx playwright install --with-deps chromium
cd ../backend
./gradlew frontendE2E --no-daemon
```

검사는 임시 DB·큐·별도 서명 OIDC issuer·합성 법령/문제·합성 추론 HTTP로 실행한다. 실사용 Google 계정/쿠키/개발 DB를 사용하지 않는다. 테스트 로그인 경로와 제어 서버는 test 소스에서만 생성된다. 운영 코드에 인증 우회를 추가하지 않는다.

검수 결과와 데스크톱·모바일 스크린샷은 `backend/build/browser-e2e/`에 저장한다. 실제 Google 연동 및 실제 LLM 법률 판정 품질을 이 테스트로 인증하지 않는다. CI의 `browser-check`도 같은 검사를 실행한다.
