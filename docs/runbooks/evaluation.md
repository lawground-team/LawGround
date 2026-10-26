# 골든셋 고정과 평가 실행

V007은 골든셋5개 테이블과 평가용 trace 연결을 추가한다. 기존 V001~V006은 수정하지 않는다. 현재는 Spring **내부 서비스**이며 새 Swagger/관리 HTTP·화면·CLI·자동 집계 스케줄러는 없다. 명시적으로 인증된 평가 관리자가 서비스를 호출하는 제한된 운영/테스트 진입점을 위한 기반이다. 기본 OFF이며 일반 로그인만으로 관리 권한을 얻지 않는다.

```dotenv
LAWGROUND_EVALUATION_ENABLED=false
# 명시적으로 허용한 내부 members.id UUID 목록. 이메일이나 회원 ID 헤더가 아님.
LAWGROUND_EVALUATION_MANAGERS=
LAWGROUND_EVALUATION_MAX_CASES=20
# 실제 실행 코드의 40자리 commit SHA. 데이터 검수/고정만 할 때는 불필요.
LAWGROUND_EVALUATION_BUILD_REVISION=
```

관리자 호출은 ACTIVE 회원 principal과 위 allowlist를 모두 검사하고, 자신이 만든 평가셋/실행만 읽고 관리한다. 모델 호출도 allowlist·회원 상태를 재검사한다. 관리자 권한을 헤더로 받거나 공개 API를 permitAll로 바꾸지 않는다. 별도 외부 관리 진입점은 후속이며 이번 서비스만으로 일반 사용자가 평가 정답에 접근할 수 없다.

## 데이터 검수와 고정

1. `GoldenSetService.create(name,version,split,origin)`으로 DRAFT를 만든다. split은 DEV/FINAL, origin은 SYNTHETIC/HUMAN_REVIEWED다. origin은 자료의 검수 출처 선언이며 모델 모드와 다르다. 합성 계약 서버/가짜 가중치·문제를 사람이 검수한 실제 품질 자료로 해석하지 않는다. 이번 자동 검수의 데이터는 모두 SYNTHETIC이다.
2. `addCase(setId,questionId,revision,leakageGroup,expectedVerdict,expectedArticleIds,sourceNote)`는 접근 가능한 문제의 snapshot과 hash를 복사한다. 검토자·검토 시각·출처를 기록하고 기대 조문은 별도 FK로 저장한다. 법령은 LAW_GO_KR/VERIFIED이며 문제 기준일에 적용되어야 한다. sourceNote는 사람이 검토한 원본/근거를 식별하도록 작성한다. 이 시스템이 사람 검수의 의미적 정확성을 자동 입증하지는 않는다.
3. DRAFT의 잘못된 사례는 `removeCase` 후 다시 검토해 넣을 수 있다. 같은 입력 hash 중복은 거절한다. 초기 셋 크기는100건 이하이고 한 실행은 설정 기본20건/최대100건이다.
4. `freeze(setId)`는 빈 집합, 인용 없는 SUPPORTED/REJECTED 라벨, 미확인 시행 범위·비활성 검토자를 거절한다. HOLD는 정답 조문0개일 수 있다. 기존 FROZEN 반대 split의 같은 leakageGroup 또는 같은 input hash가 있으면 거절한다. 그룹 키는 연관 문제/변형을 사람이 같은 계열로 묶는 값이다. 의미가 비슷한 모든 변형을 자동 식별하지는 않는다.
5. 서비스와 DB가 freeze를 직렬화하고, FROZEN 이후 사례/기대 조문/셋 수정·삭제를 거절한다. 라벨 변경은 새 버전으로 만든다. 이후 현재 문제를 수정해도 고정한 snapshot은 바뀌지 않는다.

## 평가 실행과 재개

`EvaluationService.start(setId,idempotencyKey)`는 같은 관리자·키의 같은 셋이면 기존 실행을 반환한다. 같은 키의 다른 셋은409다. 새 실행에는 LIVE 검색과 검증 설정, 명시적 코드 commit이 필요하다. Python 실제 verifier가 아직 없으므로 실제 법률 품질 평가는 미완료다. [검증 연결 안내](verification.md)의 실제 모델/준비 광고·프롬프트 확인을 먼저 완료해야 한다. 합성 HTTP 서버 통합 검수는 별개다.

준비 단계는 짧은 읽기 트랜잭션에서 사례별 법령/색인 manifest를 확인한다. 이후 하나의 접수 트랜잭션에서 실행과 각 사례의 trace/job 또는 명시적 실패 결과를 만든다. source commit·셋 버전/라벨 hash·모델/prompt/tokenizer·기준일별 코퍼스 계획을 저장한다. 라벨, 기대 조문, 검토 노트는 AI 요청에 넣지 않는다. 입력은 기존7필드 문제 snapshot뿐이며 제공 정답 번호는 원래 문제 입력에 해당한다. 기대 조문을 검색 후보로 직접 주입하지 않는다.

평가 trace는 `scope=EVALUATION`, `operation=EVALUATION_TRACE_CREATE`와 평가 run/case FK로 연결한다. 일반 사용자 실행의 operation/멱등 키 공간과 분리한다. 기존 RabbitMQ LIVE 큐·검색/검증 워커를 사용하고 lease/fencing/취소·재시도/복구 규칙을 유지한다. 평가 실행은 일반 분석 API, 문제의 분석 이력, 라이브러리의 최근 사용자 분석에 노출하지 않는다. 현재 문제의 soft delete는 우회하지 않으며 접수 전 삭제는 실패로 기록하고 진행 중 삭제는 취소로 집계한다. 고정 기록은 관리자의 이력 검토용으로 남는다.

`get(runId)`는 진행 상태를 읽고, `refresh(runId)`는 완료된 trace를 최종 결과 행으로 수집한다. 운영 호출자가 작업 완료 후 refresh를 반복 호출해야 한다. 같은 run/case는 한번만 집계하며 부분 결과에서 재개한다. 전체 사례가 끝나기 전에는 비율 metrics=null과 pending 수를 반환한다. 모두 끝나면 metrics와 최종 상태를 저장한다. 사례 기술 실패가 있어도 집계 자체가 끝났으면 평가 run은 COMPLETED이며 실패 건수는 따로 보존한다.

워커와 집계 서비스는 실행에 고정한 sourceCommit과 현재 설정의 build revision도 비교한다. 다른 코드 버전의 워커는 모델 호출·저장 전에 `EVALUATION_VERSION_MISMATCH`로 실패 처리하고, 다른 버전의 refresh는409로 거절한다. 기존 결과의 조회는 가능하다. 재배포 시 기존 실행을 새 코드로 이어 처리하지 않으며, 원래 release에서 집계하거나 새 코드/새 멱등 키로 별도 평가를 실행한다. build revision은 운영자가 실제 실행 코드의 commit으로 정확히 설정해야 한다.

셋과 다른 case·입력 hash/기준일·모델/코퍼스 계획이 연결된 결과를 거절한다. 성공 결과는 실제 해당 EVALUATION trace의 LIVE/COMPLETED·job SUCCEEDED와 대응해야 한다. 일반 USER trace를 평가 결과로 끼워 넣지 않는다. 결과의 실제 판정과 랭킹 snapshot을 보존하고 완료 실행의 설정/버전/metrics 변경을 차단한다. 초기 서비스는 LIVE 평가만 실행하며 Mock 지표 집계는 거절한다. 순수 수식의 Mock 제외 규칙은 별도 유지한다.

## 지표 해석

- Recall@5: 반환된 순위에서 article ID를 중복 제거한 최대5개와 정답 조문 집합의 교집합 비율을 사례별 평균. 현재 파이프라인의 반환 chunk topK≤5에서 조문을 얻으므로 실제 topK/후보 예산을 함께 비교해야 한다.
- Hit@5: 정답 조문을 하나 이상 찾은 사례 비율. 복수 정답이면 Recall과 다를 수 있다.
- 판정 정확도: 모든 평가 사례의 기대 판정과 실제 판정 일치율. 기대 HOLD도 포함한다.
- 정답 조문0개인 HOLD는 검색 Recall/Hit 분모에서 제외한다. 기술 실패 사례는 해당 검색 분모에서0점, 판정 분모에서 오답으로 포함한다. 실패를 성공 표본에서 제외해 정확도를 높이지 않는다.
- 미확보 법령/불완전 색인·기대 조문이 선택 코퍼스 밖인 경우도 명시적인 실패다. 정상 HOLD로 바꾸거나 사례를 조용히 빼지 않는다.

metrics에는 버전, searchCases/verdictCases/technicalFailures/holdCases, 전체·성공·실패·pending 및 origin/split이 있다. 분모0인 지표는 null이다. 합성 검사에서 계산한 수치나 전부 기술 실패인 실행을 실제 검색/법률 정확도라고 발표하지 않는다. 골든셋은 학습·파인튜닝·문제 자동 생성 데이터가 아니다.

## 검사

`EvaluationIT`는 일회용 PostgreSQL/pgvector·RabbitMQ·합성 HTTP에서 고정·라벨 불변/누출·동시 freeze·권한·격리·멱등/재개·삭제/실패와 수동 계산을 검사한다. 검사용 예시의 기대값은 Recall0.25, Hit0.5, 판정2/3이지만 **실제 서비스 품질 측정값이 아니다**. V006→V007 업그레이드에서 기존6개 checksum과 회원 보존도 검사한다.

```powershell
# backend
.\gradlew.bat integrationTest --tests com.lawground.evaluation.EvaluationIT
.\gradlew.bat clean check bootJar
```

현재 실제 사람 검수 골든셋·Python LLM·공식 코퍼스 품질 평가 결과는 없다. 실제 자료 확보와 평가를 실행할 제한된 운영 진입점, 검색 실험/성능 비교·보고서, 관리 화면은 후속이다.
