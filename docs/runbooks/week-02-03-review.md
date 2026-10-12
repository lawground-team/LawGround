# 통합 2~3주차 로컬 검토

실제 모델 없이 CRUD·큐·결과 조회를 검토한다. 개발 Mock 결과에는 실제 근거가 없다. DB 검색은 고립 Testcontainers fixture로 별도 검사한다.

## 실행

기존 `.env`를 덮어쓰지 않는다. DB/RabbitMQ 비밀번호를 설정한 로컬 환경에서 루트 `docker compose up -d --wait` 후 아래를 실행한다. 개인/운영 데이터가 없는 개발 DB를 사용한다.

```powershell
$env:SPRING_PROFILES_ACTIVE="local"
$env:LAWGROUND_DEV_AUTH_ENABLED="true"
$env:LAWGROUND_ANALYSIS_MOCK_ENABLED="true"
Set-Location backend
.\gradlew.bat bootRun
```

Swagger: `http://127.0.0.1:8080/swagger-ui/index.html`. local 고정 개발 회원은 실제 Google 로그인이 아니다. Mock 플래그는 기본 false이며 prod에서는 활성화할 수 없다.

## 순서

1. Swagger의 `GET /api/v1/dev/csrf`로 받은 token을 같은 브라우저 세션의 POST/PUT/DELETE `X-CSRF-TOKEN` 입력 칸에 넣는다. 자동 복사 기능은 없으며 세션이 바뀌면 다시 발급받는다. CSRF 없이 쓰기 요청하면 403이 정상이다. PowerShell 검토는 [세션 예시](local-development.md)를 따른다.
2. `POST /api/v1/questions`에 아래 합성 입력을 넣는다. 201, id, `ETag: "1"`을 확인한다.
3. `GET /api/v1/questions?scope=mine&limit=20`와 단건 GET으로 입력·선지·revision을 확인한다. 처음 latestOwnRun은 null이다.
4. `POST /api/v1/questions/{id}/trace-runs`에 새 UUID `Idempotency-Key` 헤더, 본문 `{"questionRevision":1}`을 보낸다. 202의 runId/jobId/조회 URL을 보관한다. 같은 키/입력 재전송은 동일 ID, 다른 입력 재사용은 409다.
5. `GET /api/v1/jobs/{jobId}`를 약 2초 간격으로 조회한다. 최종 상태에서 폴링을 중단한다. `SUCCEEDED` 후 결과 GET을 하면 MOCK/HOLD/MOCK_ONLY, 빈 근거, null 선지 분석이 반환된다. 아직 완료 전 결과는 409다. 큐 순번·완료 예상 시간을 표시하지 않는다.
6. `GET /api/v1/questions/{id}/trace-runs`와 목록 latestOwnRun을 확인한다. 다른 계정의 이력은 공유하지 않는다.
7. PUT에 등록과 같은 전체 입력과 `If-Match: "1"`을 보낸다. 새 revision=2/ETag를 확인한다. 같은 이전 헤더로 다시 PUT하면 409, 헤더 없으면 428이다. 과거 분석 결과의 inputSnapshot은 수정 전 내용이다.
8. 분석이 대기/진행 중이면 POST `/api/v1/trace-runs/{runId}/cancel`로 취소할 수 있다. 이미 완료된 실행은 409, 이미 취소된 실행은 200이다. 화면을 닫는 것만으로 취소되지 않는다.
9. DELETE에 최신 `If-Match`를 넣으면 204이며 이후 문제·작업·결과는 404다. 고정 개발 회원으로는 다중 계정 권한을 검증할 수 없으므로 아래 IT로 확인한다.

```json
{
  "subject":"BROKER_LAW",
  "stem":"[개발용 합성 문제] 옳은 설명은?",
  "questionType":"SELECT_CORRECT",
  "choices":[
    {"number":1,"text":"합성 설명 A"},
    {"number":2,"text":"합성 설명 B"},
    {"number":3,"text":"합성 설명 C"},
    {"number":4,"text":"합성 설명 D"},
    {"number":5,"text":"합성 설명 E"}
  ],
  "providedAnswerNumber":2,
  "referenceDate":"2025-10-25"
}
```

## 자동 검증

backend에서 `.\gradlew.bat clean check bootJar --no-daemon`. Docker Linux 엔진과 Java21이 필요하다. 실제 PostgreSQL16/pgvector0.8.2/RabbitMQ4.2.9 컨테이너를 사용하며 운영 DB에는 테스트를 실행하지 않는다.

`WorkflowIT`는 CRUD/소유권/동시 revision/멱등/불변 snapshot/실제 Rabbit 메시지·수동 ACK/재발행/lease·fencing/취소·삭제/재시도·deadline/법령 중복·시행일/벡터·FTS·RRF를 검사한다. `SearchMathTest`는 수동 RRF·평가 수식·code point 인용을 검사한다. 테스트 결과는 실행 후 보고서로 확인하고 실행 전 성공으로 기록하지 않는다.
