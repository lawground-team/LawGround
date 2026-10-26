# LIVE 색인과 검색·리랭크 내부 연결

이 경로는 실제 검색 후보를 반환하는 내부 Spring service와 선택적으로 켜는 LIVE 큐 워커다. 공개 검색/관리 API·사용자 분석 버튼·법률 판정은 포함하지 않는다. Python 요청/응답은 현재 구현한 0.1.0-draft를 사용하며 공동 최종 계약 확정과 구분한다.

## 선행과 사용

1. 공식 응답·시행 기간·원문·청크 범위를 검수해 `LawImportService`로 LAW_GO_KR/VERIFIED 버전을 저장한다. 원문 URL만 있다는 이유로 VERIFIED로 올리지 않는다. 공식 API parser/수집과 과거 버전 확보는 아직 별도다.
2. `AiProperties`에 승인한 BGE-M3 commit, Kiwi 버전, 서비스 URL/키를 설정하고 실제 Python을 실행한다. [기존 실행 안내](ai-connection.md)를 따른다. 모델 핀은 readiness에서 자동 채택하지 않는다.
3. `LiveLawIndexService.indexChunk(chunkId)`를 청크별로 호출한다. 한 번에1개라 CPU/메모리 상한을 낮추며 자동 HTTP 재시도는 없다. 실패한 청크는 원인을 수정한 뒤 재호출한다. 완료 행은 REUSED, 새 저장은 INDEXED다.
4. `LiveRetrievalService.search(new Query(lawIds,referenceDate,text,"article-span-v1",candidateLimit,topK))`를 호출한다. 초기 CPU 검수는 작은 후보 예산으로 시작한다. 전체30개 처리 시간/동시 호출/실제 법령 길이를 측정하기 전 최대치를 운영 기준으로 확정하지 않는다.

호출자의 DB 트랜잭션 안에서는 모델 호출을 거절한다. 저장 전 원문·검색문 hash/범위/버전을 다시 대조하고 토큰·벡터를 한 트랜잭션에 READY로 만든다. 기존 V005를 사용하며 migration은 추가/수정하지 않는다. 검수 상태·고정 pin·source hash가 같은 완료 행을 재사용하며 동시 저장은 UNIQUE와 upsert로1행만 유지한다. 재실행이 동시 모델 추론까지1회로 제한한다는 의미는 아니다.

검색은 전체 선택 코퍼스의 조문/청크가 같은 모델·토큰·chunker로 완성되어야 진행한다. `LAW_VERSION_UNAVAILABLE`은 해당 기준일 검수 버전 미확보, `INDEX_NOT_READY`는 불완전 색인, `NO_CANDIDATES`는 준비된 코퍼스의 빈 검색, `RETRIEVED`는 후보 반환이다. 기술/계약/원문 오류는 예외이며 빈 검색이나 법령 HOLD로 바꾸지 않는다. `verificationPerformed=false`를 유지한다.

질의 tokenize/embed → PostgreSQL exact cosine + FTS/ts_rank_cd → RRF(k=60) → 명시 예산만큼 rerank → 원문과 manifest 재검사 순서다. 인용용 `sourceText`와 검색용 `searchText`를 구분한다. 반환값에는 고정 버전·manifest hash·전체/리랭크 전송 후보 수·RRF 점수·rerank 원점수·원문 식별자가 포함된다. 점수는 확률이나 법적 타당성 판정이 아니다.

## 일반 통합 검수

```powershell
Set-Location backend
.\gradlew.bat integrationTest --tests com.lawground.law.search.LiveRetrievalIT --no-daemon
.\gradlew.bat clean check bootJar --no-daemon
```

실제 PostgreSQL/pgvector와 실제 loopback HTTP를 사용하지만 모델 응답은 합성이다. 공식 법령 수집·실제 가중치·검색 품질을 이 검사로 인증하지 않는다. 일회용 DB의 합성 원문은 테스트 전용이며 사용자 DB와 분리한다.

## 실제 가중치 선택 검사

Docker와 실제 Python 서버를 먼저 준비한다. 별도 터미널의 프로세스 환경변수에 `.env`와 같은 `AI_SERVICE_BASE_URL`, `AI_SERVICE_API_KEY`, `AI_EMBEDDING_MODEL_REVISION`, `AI_TOKENIZER_VERSION`, 필요 시 `AI_RERANKER_MODEL_REVISION`을 설정한다. probe는 test 프로파일이라 `.env`를 자동으로 읽지 않는다. 키 값을 명령 기록·스크린샷·공유 문서에 적지 않는다.

```powershell
# 실제 Kiwi·임베딩만 켠 상태: 색인 생성/재사용·DB READY 검수
.\gradlew.bat liveModelSmoke --no-daemon
# 실제 임베딩·리랭커를 함께 켤 메모리가 있는 상태: 질의→검색→rerank까지
.\gradlew.bat liveModelSmoke -PprobeMode=retrieval --no-daemon
```

`LiveModelProbe`는 src/test에만 있고 일회용 Testcontainers DB와 합성 문장을 사용한다. 기존 사용자 DB에는 접속하지 않는다. 원문·키·벡터를 출력하지 않고 성공 시 `LIVE_MODEL_PROBE` 메타데이터만 표시한다. 일반 check/CI는 이 task를 실행하지 않는다. index/retrieval/worker는 각각 별도 검사다. worker 모드는 실제 RabbitMQ도 격리 생성하고 인증된 합성 회원의 문제 저장→분석 접수→발행→리스너→검색 checkpoint→VERIFIER_NOT_CONFIGURED 실패를 검사한다. 어느 모드도 법률 품질·전체 성능 평가가 아니다.

```powershell
.\gradlew.bat liveModelSmoke -PprobeMode=worker --no-daemon
```


## 선택적 LIVE 큐 워커

일반 기동은 변경하지 않는다. 검수된 법령과 전체 READY 색인, 실제 모델을 먼저 준비한 뒤 `.env`의 다음 값을 명시한다. 기본 실행 설정이 이 환경변수를 Spring에 전달하는지 확인한다.

```dotenv
LAWGROUND_ANALYSIS_MOCK_ENABLED=false
LAWGROUND_ANALYSIS_LIVE_ENABLED=true
# laws.id를 쉼표로 나열. 비워 두거나 출처 제공자 ID를 넣으면 안 됨.
LAWGROUND_ANALYSIS_LIVE_LAW_IDS=<첫 법령 UUID>,<두 번째 법령 UUID>
LAWGROUND_ANALYSIS_LIVE_CANDIDATE_LIMIT=5
LAWGROUND_ANALYSIS_LIVE_TOP_K=5
```

Mock와 LIVE를 함께 켜면 시작을 거절한다. LIVE의 법령 범위는 서버 설정이며 사용자 요청에서 임의 corpus/model을 받지 않는다. 공유 exchange `lawground.analysis.v1`에서 LIVE는 routing key `analysis.live`, durable queue `lawground.analysis.live.v1`을 사용한다. 이전 Mock 작업을 LIVE로 자동 전환하지 않는다. 복구 스캔과 선점도 실행 모드를 검사한다.

인증된 사용자가 CSRF 토큰과 함께 문제를 저장한 후 기존 `POST /api/v1/questions/{id}/trace-runs`에 `Idempotency-Key` 및 `{"questionRevision":1}`을 보낸다. 접수 시 revision·지문·5선지·제공 정답·기준일을 저장하고 명시 법령, 모델/토크나이저/리랭커 pin, 후보 예산, 원문/색인 manifest를 고정한다. 전체 법령이 미확보/미색인이면 503이며 작업을 만들지 않는다. 이후 문제 수정은 접수된 snapshot을 바꾸지 않는다. 질의는 지문·선지·문제 유형/부정 표현·제공 정답을 보존한 결정적 표현이며 HyDE는 사용하지 않는다.

워커는 짧은 DB 선점 후 트랜잭션 밖에서 실제 HTTP를 호출한다. 취소·삭제·회원 비활성화·lease 만료·fencing token 변경 시 늦은 검색 결과를 저장하지 않는다. 선택한 corpus나 모델이 접수 후 달라지면 새 값으로 자동 교체하지 않고 안전한 오류 코드로 실패한다. 원문 검수는 별도이며 검색 점수는 법적 타당성이나 확률이 아니다.

`trace_runs.checkpoint.retrieval`은 schemaVersion=1, mode=RETRIEVAL_ONLY, verificationPerformed=false 및 검색 result를 저장한다. SEARCH는 tokenize/embed/Hybrid/rerank 전체 구간이다. RERANK checkpoint는 실제 rerank 수행 시에만 true다. 개별 HTTP 응답을 매번 저장하지 않으므로 검색 기록 저장 전 중단은 검색 전체를 재실행할 수 있다. 저장 후 재선점은 해당 기록과 pin/manifest를 재검사하고 모델 호출을 건너뛴다. 리스너의 수동 ACK는 DB 상태 저장 이후이며 DB 오류는 ACK하지 않는다. 재시도 소유자는 Spring worker이고 HTTP 클라이언트는 자동 재시도하지 않는다. retryable 통신/timeout 또는 429/502/504에 최초 포함 최대3회·5초/20초+jitter를 적용한다. Retry-After(초/HTTP-date)가 더 늦으면 존중하고 10분 deadline을 넘으면 실패한다. off503·버전/입력/프로토콜 오류는 재시도하지 않는다.

현재 `/verify`를 구현하지 않았으므로 검색 성공/정상0건 모두 VERIFY 단계에서 `job=FAILED`, `run=FAILED`, `reasonCode=VERIFIER_NOT_CONFIGURED`, `verdict=null`로 끝난다. COMPLETED나 법적 HOLD를 만들지 않는다. 실패 상태는 기존 작업 조회의 errorCode로 확인하고 결과 endpoint는 409 RESULT_NOT_READY다. 중간 검색 기록은 공개 결과 API로 노출하지 않는다. 사용자 UI의 analysisAvailable=false도 유지한다. 최종 검증·인용 저장·UI 활성화는 별도 작업이다.

검수: `LiveWorkerIT`는 실제 PostgreSQL/pgvector·RabbitMQ·loopback HTTP와 합성 모델 응답으로 정상 리스너, snapshot/멱등, 재시도/Retry-After/deadline, 비활성 모델/불법 후보, 저장 후 재개, 취소/삭제/회원 철회, 고정 pin/manifest, 모드 격리, 권한, 잘못된 메시지 및 DB 실패 ACK 경계를 검사한다. 실제 가중치는 선택 `liveModelSmoke -PprobeMode=worker`에서 별도로 검수한다. 합성 소량 검사로 실제 법령 품질·대규모 속도·동시성 상한을 주장하지 않는다.
