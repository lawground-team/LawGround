# 개발 안내

> 저장소 첫 화면(README)을 서비스 소개용으로 바꾸면서, 이전 README에 있던 개발용 내용을 이 문서로 옮겼다(2026-11-15). 각 단계의 구현 시점 설명이 섞여 있으니 현재 동작은 각 runbook을 기준으로 본다.

## 기술 기준

골든셋 검수·고정과 별도 평가 실행/집계는 기본 OFF인 내부 서비스로 제공합니다. V007을 추가했으며 일반 사용자 이력에 평가 실행이나 정답 라벨을 노출하지 않습니다. 실제 사람 검수 평가셋과 LLM 품질 결과는 아직 없습니다. [평가 서비스와 검수 범위](runbooks/evaluation.md)를 참고하세요.

기본 관련 문제 추천 API와 내부 문제 임베딩 색인을 제공합니다. 같은 근거·시행기간을 우선하고 현재 revision/hash·동일 모델·소유권을 검사합니다. 조회는 DB만 읽으며 색인 자동화·화면·실제 추천 품질 검수는 후속입니다. [추천 API와 색인 정책](runbooks/recommendations.md)을 참고하세요.

V008은 선택적인 선지5개·복수인용의 검증/원자 저장과 기존 결과 API 응답을 지원합니다. 원문은 실행 snapshot을 보존하고 미제공/null은 그대로 유지합니다. 태그 사전·실제 LLM/의미 검수·결과 화면은 후속입니다. [선지 분석 지원 범위](runbooks/choice-analysis.md)를 참고하세요.

| 구성 | 버전 / 용도 |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.16 |
| Gradle Wrapper | 8.14.3, 체크섬 검증 |
| PostgreSQL + pgvector | PostgreSQL 16 / pgvector 0.8.2 |
| RabbitMQ | 4.2.9 management |
| springdoc | 2.8.17, Boot 3.x 계열 |

Spring MVC·Validation·JPA·Security·OAuth2 Client·Spring Session JDBC 3.5.7(Boot BOM)·AMQP·Actuator·WebClient·Flyway·PostgreSQL JDBC를 사용합니다. 테스트는 JUnit·MockMvc·Spring Security Test·Testcontainers 및 실제 HTTP 테스트 OIDC 공급자를 사용합니다.

## 디렉터리

```text
LawGround/
  backend/       Spring 백엔드 (윤영주)
  ai-service/    Python AI 서비스 자리 (임수종)
  frontend/      Spring이 제공하는 ES modules/CSS 사용자 화면
  infra/         DB·프록시 등 인프라 설정
  docs/          팀 공유 설계·API·실행 문서
  scripts/       저장소 제외·비밀값 경계 검사
  .github/       이슈·PR 템플릿과 CI
  AI/            주차별 개인 AI 계획, 로컬 전용·Git 제외
```

Windows에서 `AI/`와 `ai/`는 충돌하므로 Python 서비스 이름은 `ai-service/`로 사용합니다.

## 로컬 실행 (Windows PowerShell)

Java 21과 Docker Desktop의 Linux 엔진이 필요합니다. 저장소 루트에서 시작하세요.

```powershell
# .env가 없을 때 한 번만 복사
Copy-Item .env.example .env
# .env의 POSTGRES_PASSWORD와 RABBITMQ_PASSWORD를 채운 뒤
docker compose config --quiet
docker compose up -d --wait
Set-Location backend
.\gradlew.bat bootRun
```

이미 `.env`가 있으면 복사로 덮어쓰지 않습니다. local 프로파일은 루트 `.env`를 읽습니다. 사용자 화면은 [http://localhost:8081](http://localhost:8081)입니다. `.env.example`의8081포트/Google callback과 동일하게 맞춥니다. [프런트 화면/검증](../frontend/README.md)을 참고하세요.

- 상태: `http://localhost:8081/actuator/health`
- DB·큐 준비: `http://localhost:8081/actuator/health/readiness`
- 개발용 Swagger: `http://localhost:8081/swagger-ui/index.html`
- RabbitMQ 관리: `http://127.0.0.1:15672` (로컬 `.env` 계정)

등록은 활성 회원과 CSRF가 필요하고 단건 조회는 본인/공개만 허용합니다. local 전용 고정 개발 회원·CSRF 사용법은 [실행 안내](runbooks/local-development.md)에 있습니다. 운영은 prod와 필수 환경변수를 명시하며 dev-auth를 금지하고 Swagger를 기본 비활성화합니다.

## 검증

```powershell
# 저장소 루트 (Python 3 필요)
python scripts/check_repository.py
node frontend/scripts/check.mjs

# backend
.\gradlew.bat test spotlessCheck bootJar --no-daemon
.\gradlew.bat integrationTest --no-daemon
.\gradlew.bat clean check bootJar --no-daemon
```

`test`는 Docker 없이 실행하는 공통 오류·요청 ID·보안 테스트입니다. `integrationTest`는 **실제 PostgreSQL/pgvector·FTS·RabbitMQ·Flyway·health·Swagger**를 검사합니다. `check`에는 포맷과 통합 테스트가 포함되어 Docker 없이 성공으로 넘어가지 않습니다. macOS/Linux는 `./gradlew`를 사용하세요.

API 계약 검사는 Python 3.11+ 가상환경에서 저장소 루트 기준으로 실행합니다.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r scripts/requirements-contracts.txt
.\.venv\Scripts\python.exe scripts/check_contracts.py
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -v
```

OpenAPI 초안은 [사용자 계약](api/external-api.md)과 [내부 계약](api/internal-api.md)에 있습니다. 예시 검증 통과는 업무 API 구현 완료나 Python 담당자 합의를 뜻하지 않습니다.

CI는 PR → main, main/codex 브랜치 push에서 실행됩니다. 백엔드 전체 검사·JAR 생성·Compose 문법·파일 제외 경계·독립 계약·프런트 검사를 확인하고 `ci-summary`가 최종 상태를 합칩니다. Python 서비스 검사는 `ai-service/requirements.txt`가 있을 때 실행합니다.

## 파일 관리

IDE 설정(`.vscode/`, `.idea/`, Eclipse 설정 등), 빌드 결과, `.env`, `AI/`, `AGENTS.md`, `CLAUDE.md` 및 에이전트 설정 폴더를 Git에서 제외했습니다. `.dockerignore`도 개인 AI 파일과 비밀값을 빌드 컨텍스트에서 제외합니다. 공유 설정인 `.editorconfig`·`.gitattributes`·`.env.example`·공식 Gradle Wrapper는 커밋합니다.

로컬 `AI/`와 에이전트 파일은 clone으로 복원되지 않으므로 개인적으로 보관하세요. 팀 공유 문서는 `docs/`에 둡니다.

자세한 안내는 [공유 문서](README.md), [백엔드 구조](architecture/backend.md), [문제 해결](runbooks/local-development.md)을 참고하세요.

## 문제 라이브러리·개발 Mock 분석·검색 기반

문제 목록/수정/삭제, 멱등 분석 접수·작업/결과 조회·취소, RabbitMQ 복구, 법령 fixture import, PostgreSQL cosine/FTS/RRF 기반을 추가했습니다. 개발 Mock은 기본 비활성화이며 실제 법령 판정·모델 연결은 미완료입니다. Google 로그인과 기본 문제 관리 화면은 실제 DB에 연결됩니다.

[아키텍처·저장 구조](architecture/analysis-search.md) · [Swagger 검토 순서](runbooks/week-02-03-review.md) · [OAuth 준비](runbooks/oauth-preparation.md). backend 검사 후 `python scripts/check_runtime_responses.py`로 합성 API 응답을 공유 계약과 대조할 수 있습니다.
