# 사용자 API 계약 초안

**제품 계약 0.1.0-draft**. 문제 CRUD·라이브러리 API·Mock 큐·법령 저장/검색 기반과 OIDC/JDBC 인증을 구현했다. Google 로그인과 기본 문제 관리 화면은 실제 API에 연결되며 Python 연결/품질·전체 분석 화면 완료는 별도다. 익명 GET `/app/config`는 로그인 가능 여부·로컬 여부·분석 가능 여부의 boolean만 no-store로 제공한다. 현재 분석 가능 여부는 false이며 키/회원 정보는 포함하지 않는다. 계약 원본은 [external.openapi.yaml](external.openapi.yaml)이며 모든 예시는 합성 데이터다.

## 사용자 흐름과 구현 순서

| 사용자 행동 | 계약 | 구현 / 예정 |
|---|---|---|
| 문제 입력·저장·조회 | POST /questions, GET /questions/{id} | 2주차 구현 |
| 제출 라이브러리·수정·삭제 | GET /questions, PUT/DELETE /questions/{id} | 3주차; 인증 통합 6주차 |
| 분석 접수·상태 확인 | POST /questions/{id}/trace-runs, GET /jobs/{id} | 3주차 Mock 큐 |
| 실행 이력·결과·중단 요청 | GET /questions/{id}/trace-runs, GET /trace-runs/{id}, GET /trace-runs/{id}/result, POST /trace-runs/{id}/cancel | 3주차 기반; 실제 AI 연결 6~7주차 |
| 원문·시행일 확인 | GET /law-articles/{id} | 4주차 기반 |
| 로그인·내 정보·로그아웃 | OAuth 시작/callback, GET /me, GET /auth/csrf, POST /auth/logout | 통합4/원본5주차 구현; 테스트 OIDC 검증, Google 실계정 미검증 |
| 관련 문제 추천 | GET /trace-runs/{id}/recommendations | 8주차 |

JSON API 기본 경로는 `/api/v1`. OAuth 경로 `/oauth2/authorization/google`, `/login/oauth2/code/google`에는 이 접두사를 붙이지 않는다. 수집·평가는 권한이 제한된 서비스/CLI 명령의 스키마만 정의했고 HTTP 관리 API는 보류했다.

## 입력 규칙

문제 본문은 subject/stem/questionType/choices/providedAnswerNumber/referenceDate. [YAML의 등록 요청](external.openapi.yaml)과 [정상·오류 예시](examples/contract-cases.json)를 함께 확인한다.

- 초기 subject는 BROKER_LAW. questionType은 SELECT_CORRECT 또는 SELECT_INCORRECT다. 틀린 설명을 고르는 문제의 정답 선지가 조문과 일치한다고 가정하지 않는다.
- v1은 정확히 5개 선지, 번호 1~5 각각 한 번, 제공 정답 1~5. 입력 순서는 달라도 저장 시 번호순으로 정렬한다. 제공 정답은 사용자 입력이며 AI 검증 완료를 뜻하지 않는다.
- referenceDate는 사용자가 지정한 법령 판단 기준일이며 필수. 오늘이나 출제연도로 자동 추정하지 않는다.
- 지문 최대 10,000, 선지 최대 2,000 Unicode code points. CRLF/CR→LF와 앞뒤 공백 제거 후 확인하며 내부 공백·부정 표현은 보존한다. Java UTF-16 길이와 구분한다.
- ownerId/memberId/visibility/상태/모델/AI 결과와 알 수 없는 필드는 입력 금지(400). 신규 제출은 PRIVATE, 소유자는 서버 인증 주체에서 결정한다.
- ID는 UUID 문자열, 날짜는 YYYY-MM-DD, 응답 시각은 UTC Z. nullable과 누락을 구분한다.
- JSON 중복 키·뒤에 붙은 JSON·숫자/문자열 자동 변환·날짜 배열은400으로 거절한다. 본문은128KiB를 넘으면413이며 Content-Length가 없는 요청도 같은 제한을 적용한다. 같은 회원의 정규화된 중복 문제는409 DUPLICATE_QUESTION이다.

## 응답과 사용자 데이터 보호

등록은 201 + Location + ETag, 단건 조회/수정은 200 + ETag, 삭제는 204. 수정/삭제의 `If-Match: "1"`은 question revision이며 누락 428, 버전 충돌 409다. 실행은 입력 스냅샷을 저장해 문제 수정 후에도 과거 분석을 재현한다.

목록은 `{items:[],nextCursor:null}`. limit 기본20/1~50, createdAt DESC/id DESC 정렬, 검증 가능한 불투명 cursor. scope 기본 mine이며 공개 목록도 이 초안에서는 로그인 후 조회한다. 공개 단건과 검수된 법령 원문은 익명 조회 가능. 비공개/타인 자원은 404로 가리고 이력·결과·중단 요청은 소유권을 확인한다. 추천 limit 기본5/최대10, 검증된 LIVE 완료·SUPPORTED 실행에서만 조회한다.

분석 접수는 `{"questionRevision":1}`과 UUID Idempotency-Key로 202 + runId/jobId/statusUrl/resultUrl을 받는다. 동일 소유자·키·payload는 같은 결과, 다른 payload는 409. 중단 요청 202는 완료가 아니며 실제 CANCELLED 여부는 상태 조회로 확인한다.

| 상태 축 | 값 |
|---|---|
| job | PENDING/QUEUED/RUNNING/RETRY_WAIT/SUCCEEDED/FAILED/CANCELLED |
| trace run | QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED |
| 근거 판정 | SUPPORTED/HOLD/REJECTED |

HOLD도 정상 결과이면 job SUCCEEDED + run COMPLETED. 기술 장애를 HOLD로 숨기지 않는다. 완료 전 판정 null, 결과 조회 409 RESULT_NOT_READY. Mock는 executionMode=MOCK로 구분한다.

결과는 inputSnapshot/기준일/판정 이유/원문 인용·시행 기간·공식 링크/실행 버전. quote는 청크 원문 `[start,end)`와 일치해야 한다. 선지 분석은 미제공 시 null, 제공 시 1~5 각각 한 번의 relation/explanation/correctedText/tags/evidenceIds. 근거 없는 정정문은 null. 수집하지 않은 선택률·오답률은 반환하지 않는다.

## 인증과 오류

SESSION 쿠키와 쓰기 요청 X-CSRF-TOKEN을 사용한다. 인증용 memberId를 클라이언트가 보내지 않는다. GET `/auth/csrf`는 익명200/no-store, GET `/me`는 인증된 `{id,displayName}`만200/미인증401/비활성403이다. POST `/auth/logout`는 현재 세션 무효화·쿠키 만료204, CSRF 누락/불일치403이며 GET은 로그아웃하지 않는다. OIDC와 local 고정 개발 인증은 상호배타다. 이전 local 전용 `/api/v1/dev/csrf`도 개발 인증 명시 설정에서만 유지한다. [인증 설정·검토](../runbooks/oauth-preparation.md)를 따른다.

오류는 code/message/requestId/timestamp/fieldErrors. 입력 원문·rejectedValue·스택트레이스는 반환하지 않는다. 400 입력/JSON, 401 인증, 403 CSRF/권한, 404 접근 가능한 자원 없음, 409 중복/멱등/버전 충돌/결과 미완료, 413 크기 초과, 428 수정 전제 누락을 구분한다. 미래 오류 코드가 현재 예외 처리기에 전부 구현된 것은 아니다.

## 검토와 검사

Spring 독립 구현 기준이며 공동 확정 전 초안. Python wire·태그·모델·인용 단위는 [내부 계약](internal-api.md)의 협의 항목이다. 다음 migration/CRUD에서 문서와 스키마를 함께 갱신한다.

Python 3.11+ 가상환경에서 `pip install -r scripts/requirements-contracts.txt`, `python scripts/check_contracts.py`, `python -m unittest discover -s scripts/tests -v`. OpenAPI 3.1 검증기 0.9.0·JSON Schema 형식·왕복 fixture를 검사한다. 등록·단건 조회는 실제 PostgreSQL 통합 검사와 로컬 HTTP 재현으로 확인한다. 미래 기능·실제 모델 품질 검사는 해당 구현 단계에서 추가한다.
