# 기본 관련 문제 추천

```http
GET /api/v1/trace-runs/{runId}/recommendations?limit=5
```

로그인 세션으로 자신의 분석 실행을 조회한다. 기본5개, 최대10개인 고정 top-N이며 `nextCursor`는 null이다. 페이지 이동/자동 생성 API가 아니다. 화면 연결은 후속이고 API는 현재 구현되어 있다.

```json
{
  "items": [{
    "questionId": "00000000-0000-4000-8000-000000000001",
    "reason": "같은 근거 조문을 다룬 문제입니다.",
    "sharedArticleIds": ["00000000-0000-4000-8000-000000000002"],
    "similarity": null
  }],
  "nextCursor": null
}
```

ID는 형식 설명용이다. 실제 DB에 없는 ID를 실행 예시로 사용하지 않는다. similarity는 같은 임베딩 모델에서 계산한 cosine [-1,1]이며 정답 확률/법률 정확도 점수가 아니다. 공유 조문 ID는 최대20개만 표시한다.

## 추천 기준과 권한

- 기준 실행은 본인의 `scope=USER`, LIVE, Spring verifier pipeline의 COMPLETED/SUPPORTED 결과다. 다른 사용자·평가/배치·삭제된 문제의 실행은404. 미완료는409/RESULT_NOT_READY, 현재 문제 revision과 다른 과거 실행은409/VERSION_CONFLICT다. Mock/HOLD/REJECTED/이전 retrieval-only 결과는 빈 목록이다.
- 후보는 같은 과목의 미삭제 문제 중 **본인의 현 revision 검증 완료 문제**, 또는 EXAM_IMPORT/OFFICIAL_VERIFIED/PUBLIC 문제다. 본인 미검증 문제와 타인 비공개 문제, 현재 문제 자체는 제외한다. 평가/배치 실행으로 본인 문제를 검증 완료로 인정하지 않는다.
- 공유 조문은 요청자 자신의 현 revision USER/LIVE/SUPPORTED 검증 완료 분석에서만 읽는다. PUBLIC 문제여도 다른 사람의 비공개 trace/evidence를 추천 사유로 사용하지 않는다. PUBLIC 문제의 공유 근거가 없더라도 준비된 벡터로는 추천할 수 있다.
- 기준 실행의 모든 근거가 LAW_GO_KR/VERIFIED이며 그 실행 기준일에 적용되어야 한다. 각 근거 법령 버전의 `[effectiveFrom,effectiveTo)`에 후보 문제 기준일이 모두 포함되어야 한다. 개정 전후 문제를 법령 제목만으로 묶지 않는다.
- 공유 조문 존재 → 공유 조문 수 DESC → 같은 모델 cosine DESC(NULL LAST) → question UUID ASC로 정렬한다. 여러 분석/인용이 같은 문제를 가리켜도 문제 하나만 반환한다. 정렬에는 전체 공유 수를 사용하고 표시 ID만20개로 제한한다.
- 공유 근거가 있으면 벡터 없이도 추천하고 similarity=null이다. 벡터 유사도만으로 추천할 때는0.65 이상만 사용한다. 이는 초기 구현 정책이며 실제 골든셋으로 검증한 최적 임계값이 아니다. 유효 후보가 없으면 정상 빈 배열이다.

추천은 동일한 근거를 공부하거나 비슷한 문제를 찾는 탐색 기능이다. 추천된 문제의 답을 보증하지 않으며 자동으로 공개 승인을 부여하지 않는다. 사용자 선택률·오답률·학습자 통계는 계산하지 않는다.

## 문제 임베딩 색인

기존 V005 `question_embeddings`를 사용하고 신규 migration은 없다. `QuestionEmbeddingService.index(questionId)`는 제한된 내부 Java 서비스 호출이다. 별도 공개 색인 HTTP/CLI/scheduler나 자동 worker 연결은 이번 범위에 포함하지 않는다. 운영 호출자는 모델 준비 후 로그인된 활성 회원 context에서 읽을 수 있는 문제를 명시적으로 색인해야 한다. 이 서비스와 기존 `LiveLawIndexService`의 법령 청크 색인은 별개다.

1. 현재 문제 revision과 PostgreSQL JSONB 텍스트로 만든 `question-recommendation-v1` 입력을 읽는다. 입력은 형식 버전·문제유형·질문·번호순 선지뿐이다. 제공 정답·기준일·회원ID·골든 라벨은 모델 텍스트에 넣지 않는다. API의 입력 항목 식별에는 문제 UUID를 사용한다.
2. 고정된40자리 embedding model commit과 입력의 UTF8 SHA256가 맞는 정상 READY 행은 재사용한다. 같은 revision이어도 손상된 hash/벡터의 READY 행은409로 거절한다.
3. DB 트랜잭션 밖에서 기존 실제 readiness 검사를 거쳐 `/embed`를1건 호출한다. 실제 Python 연결의 pin/서비스 인증은 [AI 연결 안내](ai-connection.md)를 따른다. 오류를 가짜 벡터/빈 성공으로 바꾸지 않는다. 외부 트랜잭션에서 이 서비스를 호출하면 모델 요청 전에 거절한다.
4. 짧은 쓰기 트랜잭션에서 문제 잠금 후 현재 revision/입력·권한/활성 회원 상태를 다시 확인한다. 삭제·수정·공개 취소·회원 비활성화가 발생했으면 저장하지 않는다.1024차원 finite 단위 벡터만 READY로 저장한다. 동일 색인의 동시 발행은 한 행으로 수렴하고 과거 revision은 이력으로 남는다.
5. 추천 GET은 모델 호출이나 색인 쓰기를 하지 않는다. 현재 revision/모델/input hash/READY/단위 벡터를 DB에서 재검사한 뒤 exact cosine으로 비교한다. 기준 벡터가 없으면 벡터 채널은 사용하지 않으며 공유 조문 채널은 계속 가능하다. 준비되지 않은 색인을 자동으로 만들어서 지연을 숨기지 않는다.

JSONB 입력 표현/형식이 바뀌면 입력 hash가 달라진다. 형식 변경은 별도 버전/마이그레이션 정책과 재색인 검토가 필요하며 이전 벡터를 임의로 재사용하지 않는다. HTTP 응답을 기다리는 동안 DB 연결·행 잠금을 유지하지 않는다.

## 검수와 남은 작업

`RecommendationIT`는 일회용 PostgreSQL/pgvector·RabbitMQ와 합성 HTTP를 사용한다. 합성 문제·공식 승인 플래그·검증 완료 trace를 명시적으로 구성해 SQL 순위/권한·시행일·모델/hash/revision·NULL·중복·동시 색인·저장 경합·API 계약을 검사한다. 이러한 fixture는 공식 기출 수집이나 실제 모델 정확도 증거가 아니다.

```powershell
# backend
.\gradlew.bat integrationTest --tests com.lawground.recommendation.RecommendationIT
.\gradlew.bat clean check bootJar
```

실제 LLM 검증·사람 검수 기출 코퍼스/추천 품질·색인의 운영 자동화·화면 통합·대규모 부하/HNSW는 후속이다. 초기에는 읽을 수 있는 후보의 exact 검색을 사용하며 큰 코퍼스 운영 성능을 검증했다고 주장하지 않는다.
