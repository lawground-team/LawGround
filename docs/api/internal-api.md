# Spring ↔ Python 내부 API 연결 기준

**0.1.0-draft / 2026-10-14 윤영주 선택과 현재 Python 구현을 대조한 연결 기준**이다. 원본은 [internal.openapi.yaml](internal.openapi.yaml). 임수종의 후속 수락을 포함한 공동 최종 합의를 의미하지 않는다. 기존 공유 스키마·합성 예시를 보존하면서 실제 Python 서버의 인증·오류·상한에 맞춘다. Spring 어댑터의 코드 검수와 실제 모델 HTTP smoke 결과는 별도 실행 증거로 확인하며, 이 문서만으로 연결 성공을 판단하지 않는다.

현재 Python은 실제 Kiwi `/tokenize`, BGE-M3 `/embed`, BGE reranker `/rerank` 구현을 제공한다. 모델 기능의 기본값은 off이며 `/hyde`, `/verify`에는 실제 LLM 구현이 없다. `/answer`는 공유 확장 설계만 있고 현재 Python 서버에 경로가 없다. Spring은 자체 Mock과 기본 OFF LIVE 검색 워커를 모드별로 제공하며 사용자 화면의 최종 분석은 활성화하지 않는다. 모든 예시는 합성 데이터이고 실제 모델 측정값·법률 정답이 아니다.

## 역할과 흐름

Spring이 문제·회원·법령·작업/결과 DB와 RabbitMQ 큐를 관리하고 Python이 모델 추론을 제공한다. 현재 Python은 별도 FastAPI 서버다. Spring 워커의 실제 파이프라인 연결과 Python 워커 전환을 구분하며 후자는 결정하지 않았다.

현재 내부 HTTP 클라이언트에 더해 검수된 원문의 LIVE 색인 저장과 질의 tokenize/embed→Hybrid/RRF→rerank 내부 service를 구현했다. 기존 local smoke는 합성 문장만 사용하고 사용자 문제·법령 색인·작업/결과 DB를 쓰지 않는다. 별도 선택 task liveModelSmoke는 일회용 DB에만 합성 원문을 색인해 실제 가중치를 검사한다. 공식 법령 수집·검수 코퍼스의 품질, 사용자 분석 UI와 실제 `/verify`를 포함한 최종 분석 흐름은 후속이다. LIVE 큐 워커는 실제 검색 후 checkpoint를 저장하고 미구성 verify에서 FAILED/VERIFIER_NOT_CONFIGURED·판정 null로 끝난다. [색인·검색 실행 안내](../runbooks/live-retrieval.md)를 따른다.

사전 준비: 법령 원문/버전 저장 → 조건을 보존한 청크 → /tokenize, /embed → Spring이 PostgreSQL tsvector와 pgvector 저장.

후속 분석 목표: 입력 스냅샷/기준일 고정 → 큐 → 원래 문제 및 선택적 /hyde 검색 보조 표현 → /embed, /tokenize → Spring이 동일 법령/모델/토큰 버전에서 dense30 + lexical30 → chunkId별 RRF(k=60) 상위30 → /rerank 상위5 → /verify → Spring이 인용·ID·형식 재검사 → 결과 저장 → 사용자 조회. HyDE는 기본 OFF로 진행할 수 있다. 실제 verify가 없으면 최종 법령 판정 완료로 표시하지 않는다.

HyDE 가상 문서는 검색 보조이며 최종 인용이 아니다. 단어 검색은 tsvector/tsquery + ts_rank_cd로 코사인 검색과 구분한다. 벡터는 1024차원 기준이다. 실제 embedding revision·tokenizerVersion을 설정으로 고정하고 readiness 및 응답과 비교한다. 409 이후 서버의 최신 revision을 자동 대입하거나 기존 색인과 섞지 않는다. 버전 변경 시 별도 재색인·검증 후 활성화한다. reranker revision도 별도로 고정·검사한다.

## 작업별 계약

기본 URL `http://127.0.0.1:8001/internal/v1`.

| 경로 | 입력 → 출력 | 상한 / Spring 요청 제한 시간 | 현재 Python |
|---|---|---|---|
| POST /embed | requestId, texts:[{id,text}], modelRevision → items:[{id,vector}], dim, modelRevision | 1~32개 / 30초 | BGE-M3·off·Mock |
| POST /tokenize | requestId, texts:[{id,text}], tokenizerVersion → items:[{id,tokens}], tokenizerVersion | 1~32개 / 30초 | 실제 Kiwi |
| POST /hyde | requestId, questionSnapshot, referenceDate → hypotheticalText, searchTerms, modelRevision, promptVersion | 1문제 / 60초 | 실제 미구현, off·Mock |
| POST /rerank | requestId, query, candidates:[{chunkId,text}], topK → ranked:[{chunkId,score}], modelRevision | 후보1~30개, topK1~5 / 60초 | BGE reranker·off·Mock |
| POST /verify | requestId, questionSnapshot, referenceDate, candidates:[{chunkId,sourceText,metadata}] → verdict/reasonCode/summary/evidences/choiceAnalyses/버전 | 후보1~5개 / 120초 | 실제 미구현, off·Mock |
| GET /health/ready | → status, models:[{name,revision,ready}] | READY 200 / NOT_READY 503 | tokenizer·embed·rerank 상태 |
| POST /answer | message/history/context/referenceDate → answerText/citations/usage/버전 | **후속 확장** / 120초 | 경로 미구현 |

연결 제한 시간은 3초, 작업별 30/60/120초는 Spring 설정값이며 Python 서버가 504를 만들어낸다는 의미가 아니다. 실제 서버의 모델 실행 제한 시간은 아직 구현되지 않았다. 최초 기능 연결은 소량 합성 입력으로 하고, 실제 청크·배치·후보 수의 지연과 메모리를 측정한 후 상한을 조정한다. 오프라인 법령 색인과 사용자 질의의 성능 조건을 구분한다.

현재 입력은 text/query/candidate text 최대 30,000 code points, Kiwi 응답은 문서당 최대 10,000토큰이다. 영주는 이 이중 제한과 422 `INPUT_TOO_LONG`을 임시 연결 기준으로 선택했다. 글자 수 안에 들어와도 Kiwi 출력 또는 BGE 모델의 실제 토큰 한도를 넘으면 전체 배치를 거절한다. 임의 절단·부분 성공·자동 청크 재작성은 하지 않는다. BGE 토큰은 Kiwi 형태소 토큰과 다르며, rerank는 질의와 후보를 합친 입력 토큰 한도를 검사한다. 최종 청크 최대 길이는 검색 문맥을 포함한 실제 법령 분포를 측정한 뒤 확정한다.

현재 Python 요청 본문 상한은 8 MiB다. Spring 응답 수신 상한은 별도 설정이며 Python의 응답 크기 보장을 의미하지 않는다. 정상 JSON 스키마 외에 추가 필드·중복 키·후행 JSON·잘못된 타입·과대 응답·시간 초과도 실패로 처리한다.

readiness의 200/503은 모두 `ReadyOutput`이다. 인증 실패 401은 `Error`다. 현재 전역 READY는 tokenizer/embed/rerank가 모두 ready인 경우이며 HyDE/verify를 포함하지 않는다. Mock도 ready=true가 될 수 있으므로 HTTP 200이나 revision 접두사만으로 실제 기능을 인정하지 않는다. 필요한 기능별 실제 backend·고정 revision을 검사한다. 시작 시 모델 로딩 중에는 HTTP 응답 대신 연결 거절이 발생할 수 있으며 시작 대기는 기한·backoff를 두고 작업 재시도와 구분한다.

## 식별자·원문·결과 검사

- 검색/리랭크/인용 단위는 chunkId. articleId는 표시·조문 묶음이다.
- 배치 응답 ID·순서는 요청과 같고 누락/중복 금지. 요청 embedding/tokenizer 버전을 사용한다.
- vector는 1024개 유한 실수, 영벡터 금지. 같은 차원이라도 모델 revision이 다르면 섞지 않는다.
- rerank는 입력 후보 ID만 중복 없이 score 내림차순, 1~min(topK, 후보 수)개로 반환하며 승인한 reranker revision과 일치해야 한다. score는 유한한 보정 전 실수이므로 음수를 허용하고 0~1 확률로 강제하지 않는다.
- /verify의 sourceText가 인용 기준. searchText/가상 문서/재작성 조문을 인용하지 않는다. quote와 Spring 원문 `[start,end)`가 정확히 같아야 한다.
- start/end는 Unicode code points, 시작 포함·끝 제외. 원문 `앞😀근거 문장\n뒤`의 `근거 문장`은 start=2/end=7. Java는 offsetByCodePoints로 변환하며 UTF-16 인덱스를 그대로 보내지 않는다.
- evidenceKey는 응답 안의 유일한 임시 문자열, choiceAnalyses.evidenceKeys에서 참조. Spring 저장 후 외부 evidenceId(UUID)로 매핑한다. **이번 wire 초안의 협의 제안**이며 Python이 DB ID를 만들지 않는다.
- 선지 분석 제공 시 choiceNumber 1~5 각각 한 번. originalText는 Spring 입력 스냅샷에서 복원. relation은 SUPPORTS/CONTRADICTS/INSUFFICIENT 제안이며 태그/판정 기준은 별도 합의한다.
- 후보 밖 ID·변경 버전·인용 불일치·형식 오류는 계약 실패. 형식 검사가 법적 타당성을 증명하지 않으며 골든셋 평가·표본 검수를 별도로 수행한다.

## 헤더·오류·재시도

POST는 서비스 인증 `X-Internal-Token`, UUID `X-Request-Id`, `X-Contract-Version: 0.1.0-draft`를 사용한다. 헤더와 본문 requestId는 같은 UUID 문자열로 보낸다. readiness GET도 내부 인증이 필수이며 Spring은 추적용 UUID를 보낸다. 현재 Python GET 가드는 인증 이후 반환하므로 POST의 계약 버전·본문 ID 검사를 수행하지 않는다. 키는 로컬 환경 설정 또는 운영 환경변수에 보관하고 요청·응답 원문과 함께 로그에 남기지 않는다.

기존 제안의 `X-Job-Id`, `Idempotency-Key`는 **현재 Python이 처리하거나 멱등성을 보장하지 않는다**. 후속 작업 계약으로 보존하며 이번 독립 smoke 요청에는 넣지 않는다. Spring의 외부 작업 멱등성과 Python 모델 호출의 멱등 지원을 동일시하지 않는다.

오류 DTO는 code/message/requestId/timestamp/retryable이다. 다음 표는 현재 실제 Python 동작과 영주의 처리 선택이며 공동 최종 합의와 구분한다.

| HTTP·code | 현재 Python retryable | Spring 처리 |
|---|---|---|
| 401 UNAUTHORIZED | false | 설정·키 확인, 동일 요청 자동 재시도 없음 |
| 400 UNSUPPORTED_CONTRACT_VERSION | false | 계약 버전 확인 |
| 400 INVALID_REQUEST | false | JSON·헤더/본문 ID 수정 |
| 422 INVALID_REQUEST | false | 스키마·범위·배치 수정 |
| 409 MODEL_REVISION_MISMATCH | false | 고정 버전과 색인 호환성 확인, 최신 버전 자동 대입 금지 |
| 422 INPUT_TOO_LONG | false | 한도 확인 후 호출자가 명시적으로 분할·재계획 |
| 413 PAYLOAD_TOO_LARGE | false | 본문 크기 확인 |
| 503 MODEL_NOT_READY | false | 현재 off/미로드 기능 오류. 설정·준비 상태 확인 |
| 500 INTERNAL_ERROR | false | 안전한 오류만 기록, 원인 확인 |

429 RATE_LIMITED·504 MODEL_TIMEOUT은 현재 실제 서버에서 생성하지 않으며 Mock 시나리오와 후속 계약에만 있다. Mock의 not_ready 시나리오는 503/retryable=true이고 실제 off503과 구분한다. `MODEL_OUTPUT_INVALID`, `MODEL_UPSTREAM_ERROR`, `IDEMPOTENCY_CONFLICT`는 Python enum에만 있는 후속 제안이며 실제 경로의 반환 구현을 뜻하지 않는다. Python 모델 출력 스키마 실패는 현재 500 INTERNAL_ERROR가 된다.

HTTP 어댑터는 자동 재시도하지 않는다. LIVE job 정책은 통신 장애·시간 초과·일시적 과부하와 영구 설정/입력 오류를 구분한다. Python retryable=false를 무시해 재시도하지 않는다. 현재 기술 재시도는 단계별 최초 포함 최대3회·5초/20초+jitter·10분 deadline이며 Retry-After를 존중한다. 외부 멱등 키는 실행을 재사용하고 checkpoint는 완료 검색을 건너뛰지만 Python 추론의 exactly-once를 보장하지 않는다. HOLD 의미 재검색은 미구현이다. 현재 LIVE는 정상 검색 0건도 중간 결과로만 저장하고 VERIFY 미구성 실패로 끝낸다. 후속 검증 정책에서 정상 0건과 HOLD의 관계를 확정하며, 네트워크·모델 장애를 법적 HOLD로 바꾸지 않는다. outbox는 현재 필수 범위에서 제외했다.

## 연결 전 공동 검토

- [ ] wire 필드·nullable·ID/순서·chunkId/evidenceKey 매핑·계약 버전.
- [ ] 실제 embedding/reranker/LLM revision·토큰 한도·GPU·배치·timeout.
- [ ] tokenizer/chunker 버전·기준일별 법령 범위.
- [ ] 인증·job/request ID·멱등 지원·429/5xx 재시도.
- [ ] code point와 한글·이모지·줄바꿈 원문 일치.
- [ ] 부정형 문제·충돌/근거 부족 판정·정정문·태그 사전.

[contract-cases.json](examples/contract-cases.json)과 `python scripts/check_contracts.py`로 스키마·예시·왕복 오류를 확인한다. Python 담당자 수락과 실제 HTTP 연동은 별도의 완료 기준이다.
