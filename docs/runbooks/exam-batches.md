# 승인 기출 배치 실행과 집계

윤영주 담당 Spring 운영 기능이다. V009는 `batch_runs`, `batch_items`, `trace_runs.batch_run_id`를 추가한다. V001~V008은 수정하지 않는다. 기본 비활성이며 일반 사용자용 관리자 HTTP API는 제공하지 않는다.

## 실행 전제

- `LAWGROUND_BATCH_ENABLED=true`, `LAWGROUND_BATCH_MANAGERS`의 활성 회원만 실행·조회·재개한다. 같은 관리자 목록이어도 다른 관리자의 배치는 조회할 수 없다.
- `LAWGROUND_BATCH_BUILD_REVISION`에 배포한 코드의 소문자 40자리 commit을 고정한다. 모델/프롬프트/토크나이저/법령 manifest는 각 입력의 기준일로 admission 시 고정한다.
- LIVE 검색과 검증 서비스가 준비되어야 한다. `LAWGROUND_BATCH_MAX_ITEMS` 기본20, 허용1~100. 모델 미준비를 정상 법률 판정 HOLD로 바꾸지 않는다.
- 대상은 삭제되지 않은 PUBLIC + EXAM_IMPORT + OFFICIAL_VERIFIED 기출만 가능하다. 이 플래그는 출처·정답 검수 절차가 끝난 뒤 부여한다. 사용자 제출 입력으로 공개 승인할 수 없다.
- `datasetVersion`은 운영자가 부여하는 데이터 묶음 식별자다. 문자열을 지정하는 행위가 실제 기출 검수를 대신하지 않는다. 임의 문제·정답을 공식 기출로 승격시키지 않는다.

## 운영 호출과 데이터 흐름

인증된 관리자 SecurityContext의 내부 운영 호출에서 `ExamBatchService`를 사용한다. 토큰/사용자 ID를 받는 공개 우회 경로를 추가하지 않는다.

```java
var report = batches.start(requestKey, "reviewed-exam-v1", approvedQuestionIds);
var current = batches.get(report.id());
var reconciled = batches.refresh(report.id());
```

1. 서로 다른 문제 ID와 크기·권한을 검사한다. 정렬한 ID·datasetVersion을 요청 hash로 고정한다.
2. 문제 revision·본문·5개 선지·제공 정답·기준일의 입력 snapshot/hash와 법령·모델 계획을 보관한다.
3. `batch_items`와 BATCH/LIVE 실행·내구성 있는 job을 하나의 DB 트랜잭션으로 저장한다. commit 후 기존 RabbitMQ 발행 경로로 전달한다.
4. 기존 Spring 워커가 검색·리랭크·검증·인용 대조·결과 저장을 수행한다. 각 단계에서 관리자 활성 상태, 공개 승인, 입력 revision, 코드 revision을 다시 확인한다. 철회/변경되면 실패·취소로 남기고 뒤늦은 결과를 저장하지 않는다.
5. `refresh`는 종료된 job의 결과를 item에 한 번 반영한다. 실행 중 항목은 PENDING으로 남고, 종료 항목을 다시 분석하지 않는다. 큐 발행 실패/중복 전달/리스 만료는 기존 job 복구기가 처리한다. 재시작 후 같은 requestKey와 같은 입력은 같은 배치를 반환한다.
6. 전 항목이 종료되면 배치는 COMPLETED다. 이는 실패0 또는 법률 품질 합격을 뜻하지 않는다. 집계와 최종 item은 변경할 수 없다. 새 데이터/모델/코드로 비교하려면 새 requestKey로 새 배치를 만든다.

BATCH 실행은 USER 이력·결과·job·취소·추천 API에서 숨긴다. 관리자 내부 조회만 가능하다. batch item의 trace ID를 알아도 일반 사용자 API로 결과를 볼 수 없다.

## 집계 분모

| 필드 | 의미 |
|---|---|
| totalQuestions | 시작 시 고정한 전체 문제 수 |
| succeeded / failed / pendingQuestions | item 상태별 건수, 합계는 전체 문제 수 |
| verdictCounts | 성공한 실행의 SUPPORTED/HOLD/REJECTED 건수 |
| totalChoiceSlots | 전체 문제 수 × 5 |
| providedChoiceAnalyses | 성공한 실행에서 실제 저장된 선지 설명 수 |
| missingChoiceAnalyses | 성공 문제 수 × 5 − 실제 선지 설명 수 |
| classified / unclassified / unprocessedChoices | 분류됨 / 성공했지만 미분류 / 실패·대기 선지 수. 합계는 전체 선지 수 |
| articles | 검증된 근거 조문별 서로 다른 문제 수. 한 조문을 여러 번 인용해도 문제당1회 |

현재 합의된 태그 사전이 없어 `classifiedChoices=0`, `unclassifiedChoices=성공 문제×5`, `tagSetVersion=null`, `typeCounts=[]`다. 선지 설명 미제공은 `missingChoiceAnalyses`에도 별도 표시한다. 이 값은 미분류의 부분집합이므로 두 값을 더해 분모로 쓰지 않는다. 태그 이름·빈출률·사용자 오답률·선택률을 만들어 표시하지 않는다.

예: 3문제 중 성공1·실패1·대기1이고 성공 응답에 선지 설명이 없으면, 전체 선지15·미분류5·미처리10·설명 미제공5다. 성공한 문제만 분모로 잘라 전체 품질을 주장하지 않는다.

## 검증과 한계

`./gradlew integrationTest --tests '*ExamBatchIT'`는 실제 임시 PG/pgvector·RabbitMQ와 합성 HTTP 추론으로 멱등/재개·분모·권한·철회·버전·V008 업그레이드를 검사한다. 개발 DB를 사용하지 않는다. 실제 LLM 성능·기출 검수·태그 분류 정확도는 별도 수용 검사가 필요하다.
