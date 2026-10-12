# Spring ↔ Python 내부 계약 제안

**Python 담당자 검토 전 초안 0.1.0-draft**. 원본은 [internal.openapi.yaml](internal.openapi.yaml). JSON 예시와 검증기를 준비했고 Python 개발 Mock 서버 PR #5가 main에 병합됐다. Spring HTTP 어댑터·실제 모델 연결은 아직 없다. 이번 Spring 큐는 자체 Mock이며 wire 공동 합의/실제 모델 완료와 구분한다. 모델명 fixture 접두사는 합성 예시다.

## 역할과 흐름

Spring이 문제·회원·법령·작업/결과 DB와 RabbitMQ 큐를 관리하고 Python이 임베딩·토큰화·검색 보조 문서·리랭킹·근거 판단을 제공한다. 현재 구상은 Spring 워커가 Python HTTP API를 호출한다. Python 워커 전환은 결정하지 않았다. FastAPI는 별도 ai-service 서버의 선택 후보다.

사전 준비: 법령 원문/버전 저장 → 조건을 보존한 청크 → /tokenize, /embed → Spring이 PostgreSQL tsvector와 pgvector 저장.

분석: 입력 스냅샷/기준일 고정 → 큐 → 원래 문제 및 /hyde 검색 보조 표현 → /embed, /tokenize → Spring이 동일 법령/모델/토큰 버전에서 dense30 + lexical30 → chunkId별 RRF(k=60) 상위30 → /rerank 상위5 → /verify → Spring이 인용·ID·형식 재검사 → 결과 저장 → 사용자 조회.

HyDE 가상 문서는 검색 보조이며 최종 인용이 아니다. 단어 검색은 tsvector/tsquery + ts_rank_cd로 코사인 검색과 구분한다. 초기 벡터는 1024차원 기준; 실제 embedding revision·토큰·청킹 버전은 연결 전에 고정한다.

## 작업별 계약

기본 URL `http://127.0.0.1:8001/internal/v1`.

| 경로 | 입력 → 출력 | 상한 / timeout 제안 |
|---|---|---|
| POST /embed | requestId, texts:[{id,text}], modelRevision → items:[{id,vector}], dim, modelRevision | 1~32개 / 30초 |
| POST /tokenize | requestId, texts:[{id,text}], tokenizerVersion → items:[{id,tokens}], tokenizerVersion | 1~32개 / 30초 |
| POST /hyde | requestId, questionSnapshot, referenceDate → hypotheticalText, searchTerms, modelRevision, promptVersion | 1문제 / 60초 |
| POST /rerank | requestId, query, candidates:[{chunkId,text}], topK → ranked:[{chunkId,score}], modelRevision | 후보1~30개, topK1~5 / 60초 |
| POST /verify | requestId, questionSnapshot, referenceDate, candidates:[{chunkId,sourceText,metadata}] → verdict/reasonCode/summary/evidences/choiceAnalyses/버전 | 후보1~5개 / 120초 |
| GET /health/ready | → status, models:[{name,revision,ready}] | 준비200, 미준비503 |
| POST /answer | message/history/context/referenceDate → answerText/citations/usage/버전 | **후속 확장** / 120초 |

연결 timeout 3초 제안. 배치·본문·시간 상한은 Python 담당자의 모델/GPU 환경에 맞춰 확정한다. Spring의 30/60/120초 설정은 사용 준비값이며 호출 구현 완료가 아니다.

## 식별자·원문·결과 검사

- 검색/리랭크/인용 단위는 chunkId. articleId는 표시·조문 묶음이다.
- 배치 응답 ID·순서는 요청과 같고 누락/중복 금지. 요청 embedding/tokenizer 버전을 사용한다.
- vector는 1024개 유한 실수, 영벡터 금지. 같은 차원이라도 모델 revision이 다르면 섞지 않는다.
- rerank는 입력 후보 ID만 중복 없이 score 내림차순, topK 이하로 반환. score는 보정 전 실수이므로 0~1 확률로 강제하지 않는다.
- /verify의 sourceText가 인용 기준. searchText/가상 문서/재작성 조문을 인용하지 않는다. quote와 Spring 원문 `[start,end)`가 정확히 같아야 한다.
- start/end는 Unicode code points, 시작 포함·끝 제외. 원문 `앞😀근거 문장\n뒤`의 `근거 문장`은 start=2/end=7. Java는 offsetByCodePoints로 변환하며 UTF-16 인덱스를 그대로 보내지 않는다.
- evidenceKey는 응답 안의 유일한 임시 문자열, choiceAnalyses.evidenceKeys에서 참조. Spring 저장 후 외부 evidenceId(UUID)로 매핑한다. **이번 wire 초안의 협의 제안**이며 Python이 DB ID를 만들지 않는다.
- 선지 분석 제공 시 choiceNumber 1~5 각각 한 번. originalText는 Spring 입력 스냅샷에서 복원. relation은 SUPPORTS/CONTRADICTS/INSUFFICIENT 제안이며 태그/판정 기준은 별도 합의한다.
- 후보 밖 ID·변경 버전·인용 불일치·형식 오류는 계약 실패. 형식 검사가 법적 타당성을 증명하지 않으며 골든셋 평가·표본 검수를 별도로 수행한다.

## 헤더·오류·재시도 제안

서비스 인증 X-Internal-Token, X-Request-Id, X-Contract-Version: 0.1.0-draft 필수. 작업 호출은 X-Job-Id와 UUID Idempotency-Key 추가. readiness도 내부 인증 필요. 토큰은 환경변수에만 보관한다.

오류는 code/message/requestId/timestamp/retryable. 400·422 INVALID_REQUEST는 재시도하지 않는다. 429 RATE_LIMITED는 Retry-After, 503 MODEL_NOT_READY, 504 MODEL_TIMEOUT, 500 INTERNAL_ERROR는 제한된 재시도 후보. HTTP 어댑터가 따로 재시도하지 않고 job 단계가 정책을 관리한다. 최초 포함 최대3회·HOLD 의미 재검색 최대1회는 계획 제안이며 멱등 지원·비용 중복 방지와 함께 협의한다. outbox는 현재 필수 범위에서 제외했다.

## 연결 전 공동 검토

- [ ] wire 필드·nullable·ID/순서·chunkId/evidenceKey 매핑·계약 버전.
- [ ] 실제 embedding/reranker/LLM revision·토큰 한도·GPU·배치·timeout.
- [ ] tokenizer/chunker 버전·기준일별 법령 범위.
- [ ] 인증·job/request ID·멱등 지원·429/5xx 재시도.
- [ ] code point와 한글·이모지·줄바꿈 원문 일치.
- [ ] 부정형 문제·충돌/근거 부족 판정·정정문·태그 사전.

[contract-cases.json](examples/contract-cases.json)과 `python scripts/check_contracts.py`로 스키마·예시·왕복 오류를 확인한다. Python 담당자 수락과 실제 HTTP 연동은 별도의 완료 기준이다.
