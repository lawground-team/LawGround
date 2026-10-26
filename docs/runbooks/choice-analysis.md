# 선택적인 선지 분석 저장·응답

V008은 기존 `/verify` 초안의 nullable choiceAnalyses를 Spring에서 검증·저장하는 기능이다. 실제 Python LLM/법률 판정·태그 사전이 준비되었다는 뜻은 아니다. 실제 verifier readiness/모델·prompt 인증과 원문 검사는 [기본 검증 안내](verification.md)를 따른다. 기본 검증 설정과 분석 UI는 계속 OFF다.

## 지원하는 응답

- 기존 choiceAnalyses=null/tagSetVersion=null은 그대로 미제공으로 유지한다. 제공 시 번호1~5를 각1회 포함한 정확히5개가 필요하다. 누락/중복/잘못된 타입/알 수 없는 필드는 거절한다. 기존 요청·pipeline/config/checkpoint 형상과 외부 Versions6필드는 유지한다.
- 관계는 현 초안의 SUPPORTS/CONTRADICTS/INSUFFICIENT다. 형식·연결 검사는 법적 의미의 정확도 검증을 대신하지 않는다. 제공 정답 번호와 관계를 동일시하지 않는다. 틀린 선지를 고르는 문제도 있다.
- SUPPORTS/CONTRADICTS는 실제 검증 인용 참조가 최소1개 필요하다. INSUFFICIENT는 근거0개를 허용하며 정정문은 null이다. correctedText는 CONTRADICTS+인용일 때만 허용한다. 정정문의 법적 의미 검수는 별도다.
- 태그 사전이 미확정이므로 tags=[]/tagSetVersion=null만 지원한다. 미지 태그/버전을 조용히 버리지 않고 계약 오류로 거절한다. 선택률·오답률·임의 빈출 태그를 생성하지 않는다.
- 선지당 evidenceKeys는 최대20개이며 같은 응답의 검증된 키만 중복 없이 참조한다. 인용 원문/Unicode code point 범위/법령 출처·시행일 검사는 기존과 같다.

## 저장과 결과

HTTP 응답 검증 → request fingerprint/VERIFY checkpoint → 고정 원문/코퍼스 재검사 → PERSIST 순서다. `(chunkId,start,end,quote)`로 검증된 인용을 서버가 생성한 UUID에 대응시켜 evidenceKeys를 evidenceIds로 변환한다. 모델이 외부 UUID를 정하지 않는다.

choice_analyses는 실행 snapshot의 원문·번호·관계·설명·정정문·태그/버전을 저장한다. 현재 수정된 question_choices로 과거 원문을 덮어쓰지 않는다. choice_analysis_evidences의 같은-run 복합FK로 복수 인용을 연결한다. 인용·5선지·연결·run COMPLETED/job SUCCEEDED는 한 트랜잭션이며 deferred 제약이 완전성·근거·snapshot·최종 성공을 확인한다. 완료된 선지/연결/해당 trace는 불변이다.

DB 실패는 PERSIST 전체 rollback 후 기존 큐 재전달/lease 만료 복구 정책을 따른다. 검증 checkpoint를 재사용해 모델 재호출 없이 재개한다. 이를 정상 HOLD로 숨기거나 불완전 성공을 ACK하지 않는다.

기존 `GET /api/v1/trace-runs/{id}/result`가 번호순5개 또는 null을 반환한다. 각 항목은 choiceNumber/originalText/relation/explanation/correctedText/tags/evidenceIds다. evidenceIds는 같은 결과의 evidences UUID만 참조한다. core-only/Mock은 null을 유지하고, 기존 로그인·소유권·삭제·scope 규칙을 적용한다. 타인/평가/배치 이력을 일반 사용자 API로 노출하지 않는다. 화면 연결은 후속이다.

## 검수와 후속

VerificationChoiceTest는 완전성·관계/정정문·키·태그 경계를, ChoiceAnalysisIT는 일회용 PG/pgvector·RabbitMQ·합성 HTTP에서 저장/실제 API·복수인용·Unicode/수정 snapshot·strict wire·rollback/재개·불변/교차run·취소·V007→V008 checksum/회원 보존을 검사한다. 기존 null/core/인증/평가/추천 회귀도 유지한다. 합성 관계·설명은 실제 법률 정확도 증거가 아니다.

```powershell
# backend
.\gradlew.bat test --tests com.lawground.integration.ai.VerificationChoiceTest
.\gradlew.bat integrationTest --tests com.lawground.workflow.ChoiceAnalysisIT
.\gradlew.bat clean check bootJar
```

실제 Python 생산 응답/프롬프트·의미 기준의 공동 검수, 태그 사전·분류 품질·기출 배치/빈도 집계·선지 화면/E2E는 후속이다. 태그 지원은 버전 관리 계약과 새 검수로 확대하며 기존 migration은 수정하지 않는다.
