# 로컬 실행과 문제 해결

## 실행

1. Java/Javac 21과 Docker Desktop Linux 엔진을 준비한다.
2. 루트 `.env.example`을 `.env`로 복사하고 두 비밀번호를 채운다. `.env`는 커밋하지 않는다.
3. 루트에서 `docker compose config --quiet`, `docker compose up -d --wait`를 실행한다.
4. `cd backend` 후 Windows는 `.\gradlew.bat bootRun`, Linux/macOS는 `./gradlew bootRun`을 실행한다.
5. `.env`의 `SERVER_PORT=8081`을 확인하고 `http://localhost:8081/actuator/health/readiness`가 UP인지 확인한다. 사용자 화면은 `http://localhost:8081`, Swagger는 `/swagger-ui/index.html`이다. Google callback도 같은 localhost:8081을 사용한다.

V001~V008이 적용된다. V008은 선택적인 선지5개와 복수인용의 원자 저장/결과 응답이다. 사용자 화면에서 Google 로그인·문제 입력/확인·DB 저장·내 라이브러리·상세/수정/삭제를 사용할 수 있다. 프런트는 Spring에 포함되며 별도 서버가 필요 없다. 검색 워커·Spring 검증 core는 별도 설정이며 실제 Python LLM이 미구현이므로 화면 분석은 비활성 상태이고 가짜 결과를 생성하지 않는다. 골든셋/평가 내부 서비스도 기본 OFF다. 기존 볼륨을 유지한다. [평가 범위](evaluation.md), [프런트 범위](../../frontend/README.md), [인증 설정·검증](oauth-preparation.md)을 따른다.

Python 실제 모델의 내부 HTTP 호출은 [AI 연결 검사](ai-connection.md)를 따른다. 사용자 분석 워커 전체 연결과 별도로 검수하며 기본 기동에서 진단 호출을 실행하지 않는다.

## 문제 등록·조회 재현 (local 전용)

아래는 Google 로그인과 별개인 선택적 개발 회원 재현이다. 실제 사용자 화면은 [Google 설정](oauth-preparation.md)과 `LAWGROUND_DEV_AUTH_ENABLED=false`를 사용한다. 개발 회원 재현 시에만 `LAWGROUND_OIDC_ENABLED=false`, `LAWGROUND_DEV_AUTH_ENABLED=true`를 명시하고 local/127.0.0.1로 기동한다. prod/test 또는 외부 바인딩에서는 차단한다. 개발 회원 하나를 서버에서 생성하며 클라이언트 memberId로 계정을 고를 수 없다.

```powershell
$lawgroundSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$lawgroundCsrf = Invoke-RestMethod -Uri 'http://localhost:8081/api/v1/dev/csrf' -WebSession $lawgroundSession
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
$lawgroundCreated = Invoke-RestMethod -Uri 'http://localhost:8081/api/v1/questions' -Method Post -WebSession $lawgroundSession -Headers $lawgroundHeaders -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($lawgroundBody))
Invoke-RestMethod -Uri "http://localhost:8081/api/v1/questions/$($lawgroundCreated.id)" -WebSession $lawgroundSession
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
