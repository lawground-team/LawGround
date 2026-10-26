# LIVE 색인과 검색·리랭크 내부 연결

이 경로는 검색 후보를 반환하는 내부 Spring service다. 공개 검색/관리 API·사용자 분석 버튼·실제 큐 워커 전환·법률 판정은 포함하지 않는다. Python 요청/응답은 현재 구현한 0.1.0-draft를 사용하며 공동 최종 계약 확정과 구분한다.

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

`LiveModelProbe`는 src/test에만 있고 일회용 Testcontainers DB와 합성 문장을 사용한다. 기존 사용자 DB에는 접속하지 않는다. 원문·키·벡터를 출력하지 않고 성공 시 `LIVE_MODEL_PROBE` 메타데이터만 표시한다. 일반 check/CI는 이 task를 실행하지 않는다. index 모드 성공은 retrieval 모드/동시 모델·전체 성능 검수와 별개이며 둘 다 법률 품질 평가가 아니다.
