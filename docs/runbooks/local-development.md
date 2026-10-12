# 로컬 실행과 문제 해결

## 실행

1. Java/Javac 21과 Docker Desktop Linux 엔진을 준비한다.
2. 루트 `.env.example`을 `.env`로 복사하고 두 비밀번호를 채운다. `.env`는 커밋하지 않는다.
3. 루트에서 `docker compose config --quiet`, `docker compose up -d --wait`를 실행한다.
4. `cd backend` 후 Windows는 `.\gradlew.bat bootRun`, Linux/macOS는 `./gradlew bootRun`을 실행한다.
5. `http://127.0.0.1:8080/actuator/health/readiness`가 UP인지 확인한다. Swagger는 `/swagger-ui/index.html`에 있다.

V001~V005가 적용된다. 문제 CRUD·제출 목록·개발 Mock 분석과 검색 계산 기반을 구현했다. 실제 모델·수집·로그인은 후속 범위다. 기존 볼륨은 유지하고 새 Flyway 버전이 자동 적용되는지 기동 로그로 확인한다. [새 기능 검토 순서](week-02-03-review.md)를 따른다.

## 문제 등록·조회 재현 (local 전용)

루트 .env의 `LAWGROUND_DEV_AUTH_ENABLED=true`를 명시하고 local/127.0.0.1로 기동한다. 기본은 false다. prod/test 또는 외부 바인딩에서 이 설정을 켜면 기동을 차단한다. 개발 회원 하나를 서버에서 생성하며 클라이언트 memberId로 계정을 고를 수 없다.

```powershell
$lawgroundSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$lawgroundCsrf = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/v1/dev/csrf' -WebSession $lawgroundSession
$lawgroundBody = @{
  subject = 'BROKER_LAW'
  stem = '[개발용 합성 문제] 옳은 설명은?'
  questionType = 'SELECT_CORRECT'
  choices = @(1..5 | ForEach-Object { @{number = $_; text = "합성 선지 $_"} })
  providedAnswerNumber = 2
  referenceDate = '2025-10-25'
} | ConvertTo-Json -Depth 5
$lawgroundHeaders = @{}
$lawgroundHeaders[$lawgroundCsrf.headerName] = $lawgroundCsrf.token
$lawgroundCreated = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/v1/questions' -Method Post -WebSession $lawgroundSession -Headers $lawgroundHeaders -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($lawgroundBody))
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/v1/questions/$($lawgroundCreated.id)" -WebSession $lawgroundSession
```

같은 개발 회원이 같은 문제를 다시 등록하면409 DUPLICATE_QUESTION이다. 입력 문구를 바꾸거나 기존 결과를 조회한다. 개발 회원은 로컬 임시 인증 주체이며 Google 로그인 완료로 간주하지 않는다. CSRF 경로의 토큰·쿠키를 로그에 붙이지 않는다.

## 확인 명령

```powershell
# 저장소 루트
python scripts/check_repository.py
docker compose config --quiet
docker compose ps

# backend
.\gradlew.bat test spotlessCheck bootJar --no-daemon
.\gradlew.bat integrationTest --no-daemon
.\gradlew.bat clean check bootJar --no-daemon
```

통합 테스트는 임시 컨테이너·임시 포트를 사용해 개발 DB와 분리한다. Docker가 없으면 integrationTest는 실패하며 검사 생략을 성공으로 표시하지 않는다. 결과는 `backend/build/reports/tests/test/`와 `backend/build/reports/tests/integrationTest/`에 있다.

## 오류 대응

- Docker named pipe/daemon 오류: Docker Desktop에서 Linux 엔진 실행 상태를 확인한다. `docker version`에 Server 정보가 있어야 한다.
- 5432·5672·15672 포트 충돌: `.env`의 포트만 변경하고 다시 실행한다. PostgreSQL/RabbitMQ 컨테이너 내부 포트는 바꾸지 않는다.
- 환경변수 누락: 루트 `.env`와 비밀번호 값을 확인한다. 값을 로그나 이슈에 붙이지 않는다.
- 기존 볼륨 비밀번호 불일치: 컨테이너는 초기 생성 후 `.env` 변경만으로 DB 계정 비밀번호를 바꾸지 않는다. 기존 데이터 보존 여부를 먼저 확인하고 비밀번호 변경 또는 새 볼륨을 선택한다.
- Flyway 체크섬 불일치: 적용된 V001을 수정하지 않는다. 새 버전 마이그레이션으로 변경한다. 무작정 repair/clean하지 않는다.
- Java 버전 불일치: IDE Gradle JVM과 `JAVA_HOME`을 JDK 21로 맞춘다.
- 한글 경로의 테스트 ClassNotFoundException: `gradle.properties`의 `file.encoding=COMPAT`을 유지한다. JVM 실행 전 인자 파일은 Windows의 기본 문자셋으로 읽힌다. 소스 컴파일과 테스트 실행은 별도로 UTF-8을 고정했다.
- 포맷 검사 실패: `.\gradlew.bat spotlessApply` 후 실제 변경을 확인하고 다시 검사한다.
- 401/403: 등록은 활성 회원/CSRF 필요. 개발 회원은 local 명시 설정에서만 사용 가능. 후속 기능 경로는 아직 거부한다. 익명 단건 조회는 PUBLIC만 가능하고 비공개/삭제/없는 문제는404다.

종료는 루트에서 `docker compose stop`. 데이터 삭제가 필요한 상황을 별도로 판단하기 전에는 `docker compose down -v`를 사용하지 않는다.
