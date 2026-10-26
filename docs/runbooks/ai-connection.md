# Spring ↔ 실제 Python AI 연결

현재 연결 범위는 내부 HTTP 클라이언트의 readiness·tokenize·embed·rerank와 명시적으로 실행하는 로컬 검사다. 사용자 분석 워커, 전체 법령 색인, 실제 `/verify`·HyDE·추천은 후속이며 화면의 분석 기능은 아직 비활성 상태다. 첫 연결 성공을 최종 법령 판정 완료로 표시하지 않는다.

## 설정과 실행

Java21·Docker Linux 엔진·Python 실행 환경을 준비한다. Python은 기존 `ai-service/requirements.txt`와 실제 모델용 `requirements-models.txt`를 별도 가상환경에 설치한다. CPU용 PyTorch 설치는 [공식 안내](https://pytorch.org/get-started/locally/)를 따른다. 실제 가중치는 수 GB이며 시작 때 다운로드/로드된다. 설치가 성공한 환경의 버전 목록을 기록한다.

루트 `.env`에 다음 항목을 설정한다. 기존 파일을 덮어쓰지 않는다.

```properties
AI_SERVICE_BASE_URL=http://127.0.0.1:8001/internal/v1
AI_SERVICE_API_KEY=<local service secret>
INTERNAL_API_TOKEN=<same local service secret>
AI_EMBEDDING_MODEL_REVISION=5617a9f61b028005a4858fdac845db406aefb181
AI_TOKENIZER_VERSION=kiwipiepy-0.24.0+rules-1
AI_RERANKER_MODEL_REVISION=953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e
```

Python은 `.env`를 자동으로 읽지 않는다. 제공된 Windows 실행기가 필요한 AI 항목만 프로세스 환경에 전달한다. 서비스 키는 따옴표·공백·escape 없는 URL-safe 한 줄(16~256자)로 입력한다. 이 형식만 지원해 Spring properties와 Python의 값 해석 차이를 막는다. Google 키를 Python에 복사하지 않는다. 모델 revision은 실제 가중치와 일치해야 하며 불일치 시 자동 변경하지 않는다.

```powershell
# 저장소 루트, 별도 터미널. 기본 Python은 ai-service/.venv/Scripts/python.exe
.\scripts\start_ai_service.ps1 -Mode embed
# 다른 가상환경/캐시는 -PythonPath 및 -ModelCache로 지정 가능

# DB/큐는 기존 볼륨을 유지
docker compose up -d --wait
Set-Location backend
.\gradlew.bat bootRun --args="--lawground.ai.smoke-enabled=true --lawground.ai.smoke-capabilities=TOKENIZE,EMBED"
```

성공하면 Spring 로그의 `AI_CONNECTION_SMOKE` JSON에 `passed:true`, 토큰 개수·1024차원·정규화·고정 revision·소요시간이 표시된다. 원문·인증키·벡터·원격 오류 본문은 출력하지 않는다. 검사는 합성 문장만 사용하고 사용자 DB를 읽거나 쓰지 않는다. 실패하면 정상 완료로 처리하지 않고 기동이 실패한다. 일반 실행에서는 smoke가 기본 OFF다.

메모리가 제한된 PC에서는 첫 Python 서버를 종료한 뒤 `-Mode rerank`로 재기동하고 Spring 검사도 `--lawground.ai.smoke-capabilities=RERANK`로 선택한다. 충분한 환경은 `-Mode all`과 `TOKENIZE,EMBED,RERANK`를 사용할 수 있다. 일부 모델만 켜면 전역 readiness가503이어도 해당 기능의 실제 백엔드·고정 revision·ready가 일치할 때 그 기능 검사는 가능하다. Mock READY는 실제 연결로 인정하지 않는다.

두 모델을 순차 검수한 결과는 동시 실행·최대 처리량 검증과 구분한다. 같은 Compose 네트워크의 컨테이너끼리는 서비스명, 호스트에서 실행하는 Spring은 게시된 localhost 포트를 사용한다. 이번 기본 구성에는 Python Compose 서비스가 아직 포함되지 않는다.

## 보호와 오류

내부 인증키와 UUID 요청ID/header-body 일치·계약 버전을 전송한다. 입력은 최대32건/30,000 Unicode code points이고 Kiwi 출력은10,000토큰/토큰200자까지다. 모델 자체 입력 토큰 한도는 별도이며 초과 시422를 처리하고 임의 절삭하지 않는다. 응답은 기본4MiB 상한과 엄격 JSON 검사를 적용한다.

응답의 ID·개수·순서·1024차원·유한값·영벡터·모델 버전을 확인한다. 리랭크는 요청 후보만 중복 없이, 유한한 원점수 내림차순으로 반환해야 한다. 점수를 확률로 표현하지 않는다. 매 호출 전 필요한 실제 모델 상태를 확인한다. HTTP 자동 재시도는 없고 후속 작업 단계에서 정책을 관리한다. 모델 OFF·설정 오류·입력 오류·통신 장애·계약 오류를 구분한다.

smoke는 `local & !prod & !test`와 loopback 바인딩에서만 허용된다. 운영 공개 진단 API는 추가하지 않는다. AI 설정이 없어도 기존 로그인/문제 관리 일반 기동은 가능하지만 AI 호출은 차단된다.

## 검증

`backend/gradlew.bat clean check bootJar --no-daemon`은 실제 PG/큐 및 loopback HTTP 경계 테스트를 실행한다. HTTP 테스트의 응답 서버는 합성이며 실제 모델 품질 검사가 아니다. 위 opt-in smoke를 실제 Kiwi/BGE 가중치 서버로 수행한 결과를 별도로 기록한다. `/verify` 미구현 상태와 법령 검색 품질·총량·최대 배치 지연은 별도 검수 항목이다.
