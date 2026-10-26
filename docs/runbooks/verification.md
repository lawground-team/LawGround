# Spring 근거 검증 연결과 저장

Spring의 `/verify` HTTP 어댑터·원문 검사·판정 저장을 구현했다. Python의 실제 LLM, 모델 선정, 법률 정확도 검수 완료를 의미하지 않는다. 현재 Python은 `/verify`에 off/Mock만 있고 readiness에도 실제 verifier를 광고하지 않는다. 기본 설정과 사용자 분석 UI는 계속 OFF다. 기존 wire `0.1.0-draft`의 core 부분을 사용하며 Python 코드/기존 migration은 변경하지 않는다.

## 활성화 전 확인

```dotenv
LAWGROUND_ANALYSIS_MOCK_ENABLED=false
LAWGROUND_ANALYSIS_LIVE_ENABLED=true
LAWGROUND_VERIFICATION_ENABLED=false
AI_VERIFIER_MODEL_NAME=
AI_VERIFIER_MODEL_REVISION=
AI_VERIFIER_PROMPT_VERSION=
```

실제 검증 기능이 준비되면 마지막 세 값을 명시하고 검증 설정을 켠다. 모델명/모델 revision/프롬프트 version은 Spring이 readiness나 응답에서 자동 채택하지 않는다. 현재 빈 값을 임의로 채워도 실제 기능이 생기지 않는다. 실제 모델 선택·토큰/성능 상한은 Python 담당자와 확인할 사항이다.

Spring의 활성화 정책은 `/health/ready.models`에 정확한 `verify:<서비스가 광고하는 이름>`과 고정 revision, ready=true가 있어야 한다는 것이다. 기존 ReadyOutput 형식 안의 식별 규칙이며 현재 Python 구현이나 공동 최종 합의로 표현하지 않는다. 실제 광고명은 연결 전에 양측이 맞춰야 한다. 현재 tokenizer/embed/rerank 준비 상태만으로 verify를 켜지 않는다. `verify:mock`, `verify:off`, fixture 식별자는 설정에서 거절한다. 검증 ON은 LIVE ON/Mock OFF를 요구한다.

## 실행 흐름

1. 접수 시 지문·부정형 문제 유형·5선지·제공 정답·기준일과 법령/색인 범위·모델/프롬프트 버전을 고정한다. 검증을 켠 새 실행은 `spring-live-verify-v1`, config schemaVersion=2(`retrieval`과 `verification`)다. 이전 `spring-live-retrieval-v1`/config1 실행은 나중에 설정을 켜도 자동 승격하지 않는다.
2. 기존 tokenize/embed → Hybrid/RRF → rerank 결과를 SEARCH checkpoint에 저장한다. 검증 후보는 최대5개이며 빈 후보를 minItems=1인 `/verify`에 보내지 않는다. 이번 core 단계는 후보0건을 `VERIFICATION_CANDIDATES_UNAVAILABLE`로 끝낸다. 정상0건→HOLD 정책은 실제 검증 정책과 함께 별도로 연결할 항목이다.
3. Spring이 후보 ID·원문·조문/법령버전·제목·시행 기간을 DB에서 다시 조회한다. sourceText와 검색 checkpoint, 기준일, manifest를 검사한다. 검증 요청은 `requestId`, 원래 `questionSnapshot`, 동일 `referenceDate`, `candidates[{chunkId,sourceText,metadata}]`다. 날짜는 ISO 문자열, nullable effectiveTo는 명시 null이다.
4. 짧은 준비 트랜잭션이 끝난 뒤 readiness와 실제 `/verify` HTTP를 호출한다. 서비스 인증/계약/요청 ID 헤더를 유지한다. readiness와 POST가 검증용 제한 시간(기본120초)을 공유하며 HTTP 자동 재시도는 없다. 기술 재시도는 기존 워커가 소유한다.
5. 응답의 판정·버전·길이·필수 nullable·후보 ID·유일 evidenceKey·인용문과 Unicode code point `[start,end)`를 검사한다. 다른 후보·없는 위치·수정된 원문·모델/프롬프트 불일치를 거절한다. SUPPORTED와 REJECTED에는 인용이 있어야 한다는 Spring 정책을 적용한다. Python 최소 스키마는 SUPPORTED에만 필수 조건이 있으므로 REJECTED 인용 요구도 연결 시 확인해야 한다. 이 형식/원문 검사가 법적 의미를 증명하지는 않는다.
6. V008에서 기존 optional choiceAnalyses를 지원한다. null은 그대로 미제공이며, 제공 시 정확히5개·관계/인용키·정정문을 검사하고 인용/최종 상태와 원자 저장한다. 태그 사전은 미확정이므로 tags=[]/tagSetVersion=null만 지원하고 나머지는 거절한다. [선지 저장·응답과 검수](choice-analysis.md)를 참고한다. 실제 Python 생산 응답과 의미 검수·화면은 후속이다.
7. HTTP 전에 실제 보낸 내용의 canonical hash를 만든다(전송 requestId 제외). 응답 뒤 DB에서 내용을 재구성해 같은 hash인지 확인한다. 처리 중 제목/조문 정보가 바뀌면 다른 입력의 응답으로 저장하지 않는다. 질문→실행→작업 잠금과 lease/fencing/권한을 검사한 다음 코퍼스의 원문/색인 행을 짧게 SHARE 잠금하고 manifest를 재확인한다. 모델 HTTP 동안 DB 잠금은 유지하지 않는다.
8. 검증 응답을 `checkpoint.verification`에 schemaVersion=1, verificationPerformed=true, requestHash, result로 저장하고 VERIFY 완료를 기록한다. 저장 후 재시작은 모델을 다시 호출하지 않고 pin/hash/원문을 재검사한다. 저장 직전 중단된 HTTP는 재호출될 수 있으며 exactly-once 추론을 보장하지 않는다.
9. PERSIST에서 원문을 다시 검사하고 `evidence_results`와 실행 COMPLETED/판정·요약, 작업 SUCCEEDED를 하나의 트랜잭션에 저장한다. 중간 INSERT 실패는 모두 롤백한다. 취소·삭제·회원 비활성·만료 lease·오래된 fencing token은 저장하지 못한다. DB 예외는 ACK하지 않고 복구 대상 상태를 유지한다.

검증 설정을 끈 기본 경로는 이전과 같이 `FAILED/VERIFIER_NOT_CONFIGURED`, verdict=null이다. 설정을 켰어도 실제 verifier가 준비되지 않으면 `MODEL_NOT_READY`이며 HOLD로 바꾸지 않는다. 유효한 core 응답의 HOLD는 정상 완료이고 기술 실패와 다르다. 결과 API와 소유권/CSRF 규칙은 기존 그대로이며 외부 Versions는 기존6개 필드를 유지한다. 중간 checkpoint는 공개 결과로 노출하지 않는다.

## 검수와 남은 연결

`VerificationClientTest`는 실제 loopback HTTP로 core3판정, 필수/nullable/unknown/타입·인용/Unicode·버전·지원범위·readiness·timeout을 검사한다. `VerificationWorkerIT`는 일회용 PostgreSQL/pgvector·RabbitMQ·합성 모델 서버에서 저장/조회·재시도·재개·취소/삭제/권한·DB 롤백/ACK와 실제 요청/응답의 계약 대조를 검사한다. 이 테스트의 광고 모델은 합성 계약 서버이며 실제 LLM이 아니다.

```powershell
# backend 디렉터리
.\gradlew.bat test --tests com.lawground.integration.ai.VerificationClientTest
.\gradlew.bat integrationTest --tests com.lawground.workflow.VerificationWorkerIT
.\gradlew.bat clean check bootJar
# 저장소 루트, 기존 계약 검사 가상환경
.\.venv\Scripts\python.exe scripts/check_contracts.py
.\.venv\Scripts\python.exe scripts/check_runtime_responses.py
```

실제 연결 전 남은 사항은 Python 실제 `/verify` 구현, 정확한 readiness 광고명/모델·프롬프트 pin 확인, core/null 확장 범위와 REJECTED 인용 정책 확인, 검수된 실제 법령·골든셋 평가다. UI 활성화·공식 수집·평가 저장·추천·클라우드 배포는 이번 범위에 포함하지 않는다. 되돌리기는 검증 OFF이며 이미 검증 설정을 고정한 실행을 검색 전용이나 다른 모델로 임의 전환하지 않는다.
