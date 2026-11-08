# [제안] 0.2.0-draft — "이 문장만 확인" + "비슷한 기출" 기능 계약

- 작성: 임수종 측(Python), 2026-11-09 / 상태: **제안, 합의 전**(Python 쪽 경로는 구현됨, 아래 '구현 현황'). 윤영주 님 검토 후 확정한다.
- 대상 기능
  - **② 문장 확인**: 선지 하나(문장 1개)만 넣으면 현행 법령으로 맞는지 판정하고, 근거 인용과 틀린 부분을 보여 준다.
  - **③ 비슷한 기출**: 그 문장과 같은 쟁점의 기출 선지 3개를 O/X와 함께 보여 주고, 어디를 어떻게 바꿔 출제했는지와 공부 팁을 준다.
- 원칙
  - 기존 0.1.0-draft 계약(`/embed`, `/tokenize`, `/rerank`, `/verify`, `/health/ready`)은 **한 글자도 바꾸지 않는다**. 새 경로만 추가한다(하위 호환).
  - 검색은 기존 Spring LIVE 검색(Hybrid/RRF → rerank 상위 5)을 그대로 재사용한다.
  - ③은 LLM 없이 동작한다(빠르고 무료, 결정적). ②만 LLM(Gemini)을 쓴다.
  - 헤더(`X-Internal-Token`, `X-Request-Id`, `X-Contract-Version`), 오류 DTO, 인용 규칙(`[start,end)` Unicode code point, `sourceText` 기준)은 기존과 같다. 새 경로의 `X-Contract-Version`은 `0.2.0-draft`.

## 구현 현황 (2026-11-09, Python 쪽)

| 항목 | 상태 | 위치 |
|---|---|---|
| `POST /internal/v1/verify-statement` (② 판정, LLM) | **구현·테스트 완료**. `VERIFY_BACKEND=gemini`일 때 /verify와 같은 Gemini 클라이언트, `mock`이면 고정 응답 | `app/services/statement.py`, `promptVersion=statement-v1` |
| `POST /internal/v1/variation-diff` (③ 변형 비교, 규칙) | **구현·테스트 완료**. 권장안(4절)대로 Spring이 고른 기출 선지 1~5개를 받아 바뀐 구간·태그·공부 팁을 돌려준다. 모델 설정 없이 항상 켜짐 | `app/services/variation.py`, `algorithmVersion=variation-v1` |
| 기출 선지 은행 생성(Spring 적재용) | **완료**. 25~36회 범위 안 790문제 → 선지 3,950개(TRUE 1,519 / FALSE 1,726 / UNKNOWN 705). 선지가 5개로 안 나뉜 12문제(조합형 분리 실패·조문 빈칸형) 제외. 저장소에는 올리지 않음 | `scripts/build_choice_bank.py` |
| OpenAPI | `ai-service/openapi-0.2.0-draft.yaml` (새 경로만). 0.1.0 `openapi.yaml`은 그대로 | `scripts/export_openapi.py` |
| 헤더 | 새 경로는 `X-Contract-Version: 0.2.0-draft`만 받는다. 기존 경로는 `0.1.0-draft` 그대로 | `app/common.py` |

아래 원안과 달라진 점:
- 3-1 일관성 규칙 위반 시 **재시도하지 않고** 바로 `HOLD`로 낮춘다(무료 한도 절약). JSON 형식 오류만 1회 다시 묻는다.
- 3-1 `tags`는 최대 2개(5종 코드만), INCONSISTENT일 때만.
- 3-2 `/similar-items`는 만들지 않았다(권장안 채택) → `/variation-diff`: 요청 `{requestId, subject, text, items:[{itemId, text, truth}] 1~5}`, 응답 `{items:[{itemId, similarity, diff[]}], tip|null, tagSetVersion, algorithmVersion}`. `FEATURE_DISABLED` 새 오류 코드는 필요 없어졌다.
- 변형 비교는 띄어쓰기·문장부호를 빼고 글자 단위로 비교한다. 글자 유사도 0.55 미만이면 다른 문장으로 보고 `diff=[]`, 바뀐 곳이 4군데 이상이면 공부 팁 집계에서 뺀다(표현만 바꿔 쓴 문장). **품질 평가(6절 Precision@3, 사람 O/X 30문장)는 아직 안 했다.**

---

## 0. 선행 조건 (두 기능 공통)

| # | 내용 | 담당 | 비고 |
|---|---|---|---|
| P1 | **[결정 2026-11-09: 공법을 앱 분석 대상에 포함]** 과목 코드 추가: `subject`에 `PUBLIC_LAW`(부동산공법) | 공동 | 기존 `QuestionSnapshot.subject`는 `^BROKER_LAW$`로 고정돼 있다. 새 경로에서만 `BROKER_LAW | PUBLIC_LAW`를 허용하고, 기존 `/verify`는 그대로 둔다 |
| P2 | 공법 6개 법률(국토계획법·도시개발법·도시정비법·건축법·주택법·농지법)의 법·시행령·시행규칙 import와 색인, 과목별 `LAW_IDS` 분리 | Spring | Python 쪽 공법 코퍼스(2,155개)는 준비돼 있다. 과목별 설정 예: `LAWGROUND_ANALYSIS_LIVE_LAW_IDS_BROKER_LAW`, `..._PUBLIC_LAW` |
| P3 | **[결정 2026-11-09] 기출 문제 사용 가능, 출처 표시 필수** | 공동 | 기출이 보이는 화면 하단과 작품 설명서에 "본 기출문제의 저작권은 한국산업인력공단(Q-Net)에 있으며, 출처를 밝히고 사용하였습니다."와 "일부 재가공됨"(선지 분리·표기 정리·스캔본 재입력)을 표시한다. 기능 스위치는 유지하되 출처 표시가 있는 환경에서는 켤 수 있다 |

---

## 1. 화면과 사용자 흐름 (Spring·프론트)

### 1-1. 문장 확인 페이지 `/statements/new`
1. 입력은 **문장 한 칸**과 과목, 기준일 셋뿐이다.
   - 문장 칸: 필수, 최대 2,000자.
   - 과목: 자동 감지 후 바꿀 수 있다. 문장에 "국토의 계획", "건축법령상", "주택법령상", "도시개발법", "정비법", "농지법" 등이 있으면 공법으로 감지한다.
   - 기준일: 기본값은 오늘.
   - 선택 입력 "문제 머리말": 예 "공인중개사법령상"처럼 문장만으로 법령이 애매할 때 도움이 된다. 최대 500자.
2. "확인하기"를 누르면 작업이 접수된다(비동기). 진행은 기존 분석과 같은 단계 표시를 쓴다: `PREPARE → SEARCH → VERIFY → SIMILAR → PERSIST`.
3. 결과 카드
   - **판정 배지**: `조문과 일치` / `조문과 어긋남` / `판단 보류` (기획 문서 결정: "틀림"으로 단정하지 않는다. "옳지 않은 것은?" 문제에서는 맞는 문장이 오답이므로 정답 여부가 아니라 조문과의 관계로 표시)
   - **근거**: 조문명·조항과 인용 문구(원문에서 형광펜 표시)
   - **어긋난 부분**(조문과 어긋날 때): 사용자 문장에서 틀린 구간에 밑줄을 긋고, 그 옆에 법령 원문 표현을 보여 준다. 예: ~~3년~~ → **5년**
   - **고친 문장**: 맞게 고친 문장 한 줄
   - **비슷한 기출 3개**(③): 카드마다 `36회 59번 ①`, O/X 배지, 기출 문장, 내 문장과 다른 부분 색 표시
   - **공부 팁** 한 단락
4. 오류·한도
   - Gemini 한도(429): "잠시 후 다시" 안내. 작업은 `RETRY_WAIT` 규칙을 따른다.
   - 비슷한 기출을 찾지 못하면 판정 결과는 그대로 보여 주고, 기출 영역만 "비슷한 기출이 없습니다"로 둔다. ③은 실패해도 작업을 실패로 만들지 않는다.

### 1-2. 기존 5지선다 분석 결과 화면에 추가
- 선지 분석 줄마다 **"비슷한 기출"** 버튼을 단다. 누르면 동기 호출로 1초 안에 3개를 펼친다.
- 이 버튼은 판정(LLM)을 다시 하지 않는다. ③만 부른다.

---

## 2. 외부 API (Spring, 브라우저용) — 제안

### 2-1. `POST /api/v1/statement-checks` → 202
```json
{
  "subject": "PUBLIC_LAW",
  "statement": "국토교통부장관은 기본계획에 대하여 5년마다 타당성을 검토하여 그 결과를 기본계획에 반영하여야 한다.",
  "contextStem": "도시 및 주거환경정비법령상 도시·주거환경정비기본계획에 관한 설명",
  "referenceDate": "2026-11-17"
}
```
응답: `202 Accepted`, `Location: /api/v1/statement-checks/{id}`, 본문 `{ "id": "...", "status": "PENDING" }`.
- 검증 규칙
  - `statement`: 1~2,000자, 공백만 있으면 거절.
  - `contextStem`: 선택, 0~500자.
  - `subject`: `BROKER_LAW | PUBLIC_LAW`.
  - `referenceDate`: 필수.
- 멱등성: 기존 분석 접수와 같은 방식(같은 사용자·같은 입력의 중복 접수 처리)을 따른다.
- 권한: 기존 문제와 같다. 본인만 조회할 수 있고, 비공개로 저장한다.

### 2-2. `GET /api/v1/statement-checks/{id}` → 200
```json
{
  "id": "…", "status": "SUCCEEDED", "stage": "PERSIST",
  "input": { "subject": "PUBLIC_LAW", "statement": "…", "contextStem": "…", "referenceDate": "2026-11-17" },
  "result": {
    "verdict": "INCONSISTENT",
    "reasonCode": "CONTRADICTED_BY_LAW",
    "summary": "기본계획 타당성 검토 주체는 특별시장·광역시장·특별자치시장·특별자치도지사 또는 시장이다.",
    "evidences": [ { "evidenceId": "…", "lawTitle": "도시 및 주거환경정비법", "articleLabel": "제4조", "quote": "…5년마다 타당성을 검토하여…", "start": 120, "end": 168 } ],
    "wrongSpans": [ { "statementStart": 0, "statementEnd": 8, "lawExpression": "특별시장·광역시장·특별자치시장·특별자치도지사 또는 시장", "evidenceId": "…" } ],
    "correctedStatement": "특별시장·광역시장·특별자치시장·특별자치도지사 또는 시장은 기본계획에 대하여 5년마다 타당성을 검토하여 그 결과를 기본계획에 반영하여야 한다.",
    "similar": { "items": [ … 3절과 같은 모양 … ], "tip": { … }, "bankVersion": "…" } ,
    "versions": { "pipelineVersion": "…", "modelRevision": "…", "promptVersion": "…", "rerankerRevision": "…" }
  }
}
```
- `similar`는 `null`일 수 있다(기능이 꺼졌거나 실패한 경우). 그때 `similarUnavailableReason`에 `DISABLED`, `NO_MATCH`, `ERROR` 중 하나를 둔다.

### 2-3. `POST /api/v1/similar-items` → 200 (동기)
```json
{ "subject": "PUBLIC_LAW", "text": "…선지 문장…", "limit": 3, "analysisId": "…(선택)", "choiceNumber": 3 }
```
- 기존 분석 결과의 "비슷한 기출" 버튼용이다.
- `analysisId`와 `choiceNumber`를 주면, Spring이 저장된 검색 후보의 조문(articleKeys)을 함께 넘겨 정확도를 높인다.
- 응답 모양은 3절의 내부 응답에서 버전 필드만 정리해 그대로 전달한다.

---

## 3. 내부 API (Spring → Python) — 제안

### 3-1. `POST /internal/v1/verify-statement` (② 판정, LLM 사용, Spring 제한 120초)

요청
```json
{
  "requestId": "uuid",
  "statement": {
    "subject": "PUBLIC_LAW",
    "text": "…(1~2,000자)…",
    "contextStem": "…(선택, 0~500자, null 허용)…",
    "referenceDate": "2026-11-17"
  },
  "referenceDate": "2026-11-17",
  "candidates": [ { "chunkId": "uuid", "sourceText": "…", "metadata": { "articleId": "…", "lawVersionId": "…", "lawTitle": "…", "articleLabel": "…", "effectiveFrom": "…", "effectiveTo": null } } ]
}
```
- `candidates`는 기존 `VerifyCandidate`와 같고, 1~5개다.
- `referenceDate`는 `statement.referenceDate`와 같아야 한다(기존 `/verify` 규칙과 같다).

응답
```json
{
  "verdict": "INCONSISTENT",
  "reasonCode": "CONTRADICTED_BY_LAW",
  "summary": "…(1~10,000자)…",
  "evidences": [ { "evidenceKey": "e1", "chunkId": "uuid", "quote": "…", "start": 120, "end": 168 } ],
  "wrongSpans": [ { "statementStart": 0, "statementEnd": 8, "lawExpression": "…(1~500자)…", "evidenceKey": "e1" } ],
  "correctedStatement": "…(1~2,000자) 또는 null",
  "tags": [ { "code": "ACTOR" } ],
  "tagSetVersion": "choice-tags-v1",
  "modelRevision": "verify:gemini-…",
  "promptVersion": "statement-v1"
}
```
- `verdict`는 **새 enum** `StatementVerdict`이다: `CONSISTENT | INCONSISTENT | HOLD` (화면: 조문과 일치 / 조문과 어긋남 / 판단 보류).
  - 기존 `Verdict`(SUPPORTED/REJECTED/HOLD)는 "제공된 정답 번호가 맞는지"라는 다른 의미라서 재사용하지 않는다. 화면에서 헷갈리지 않게 하기 위해서다.
- `reasonCode`는 확정된 5개 사전을 그대로 쓴다.

| verdict | 허용 reasonCode |
|---|---|
| CONSISTENT | `SUFFICIENT_EVIDENCE` |
| INCONSISTENT | `CONTRADICTED_BY_LAW` |
| HOLD | `INSUFFICIENT_EVIDENCE`, `NO_VERIFIED_QUOTE`, `INCONSISTENT_ANALYSIS` |

- 일관성 규칙(Python이 보내기 전에 검사하고, Spring도 다시 검사한다)
  - `CONSISTENT`와 `INCONSISTENT`는 `evidences`가 1개 이상 있어야 한다.
  - `INCONSISTENT`이면 `wrongSpans`가 1개 이상 있어야 하고, `correctedStatement`는 null이 아니어야 한다.
  - `CONSISTENT`와 `HOLD`이면 `wrongSpans`는 빈 배열, `correctedStatement`는 null이다.
  - `wrongSpans`의 `statementStart` < `statementEnd` ≤ 문장 길이(code point)여야 하고, `evidenceKey`는 `evidences` 안에 있어야 한다.
  - 인용 `quote`는 `sourceText[start,end)`와 정확히 같아야 한다(기존 규칙).
  - Python은 모델 출력이 이 규칙을 어기면 1회 재시도한다. 그래도 어기면 `HOLD/INCONSISTENT_ANALYSIS` 또는 `NO_VERIFIED_QUOTE`로 낮춰서 보낸다(가짜 근거를 만들지 않는다).
- `tags`(선택): 틀린 이유의 변형 유형. 코드는 3-3절 표의 코드를 쓰고, 최대 5개.

### 3-2. `POST /internal/v1/similar-items` (③ 비슷한 기출, LLM 없음, Spring 제한 5초)

요청
```json
{
  "requestId": "uuid",
  "subject": "PUBLIC_LAW",
  "text": "…(1~2,000자)…",
  "articleKeys": [ { "lawTitle": "도시 및 주거환경정비법", "articleLabel": "제4조" } ],
  "limit": 3,
  "excludeItemIds": ["36-59-1"]
}
```
- `articleKeys`(선택, 0~10개): Spring 검색 결과 상위 조문이다. 같은 조문을 근거로 한 기출을 우선한다.
- `limit`: 1~5, 기본 3.
- `excludeItemIds`(선택, 0~20개): 사용자가 입력한 문장이 기출 그 자체일 때 자기 자신을 뺀다.

응답
```json
{
  "items": [
    {
      "itemId": "36-59-1",
      "exam": 36, "questionNumber": 59, "choiceNumber": 1,
      "stemHead": "도시 및 주거환경정비법령상 기본계획에 관한 설명으로 틀린 것은?",
      "text": "국토교통부장관은 기본계획에 대하여 5년마다 타당성을 검토하여 그 결과를 기본계획에 반영하여야 한다.",
      "truth": "FALSE",
      "articles": [ { "lawTitle": "도시 및 주거환경정비법", "articleLabel": "제4조" } ],
      "score": 0.91,
      "match": "SAME_ARTICLE",
      "diff": [
        { "type": "ACTOR", "userStart": 0, "userEnd": 8, "itemStart": 0, "itemEnd": 7, "userText": "…", "itemText": "국토교통부장관" }
      ]
    }
  ],
  "tip": {
    "categories": ["ACTOR", "NUMBER"],
    "text": "이 조문은 주로 '누가'(주체)와 '몇 년'(기간)을 바꿔 출제됩니다. 현행 법령: 특별시장·광역시장·특별자치시장·특별자치도지사 또는 시장, 5년마다."
  },
  "bankVersion": "bank-2026-11-09-<sha1 앞 12자>",
  "algorithmVersion": "similar-v1"
}
```
- `items`: 0~`limit`개, `score` 내림차순.
- `truth`: `TRUE`(기출에서 맞는 문장), `FALSE`(틀린 문장), `UNKNOWN`(조합형 등 자동 판정 불가). **3개를 고를 때 `UNKNOWN`은 마지막 순위**로 둔다.
- `match`: `SAME_ARTICLE`(같은 근거 조문) 또는 `TEXT_ONLY`(문장만 비슷).
- `diff`: 0~10개. 위치는 각각 사용자 문장과 기출 문장의 code point `[start,end)`.
- `tip`: 결과가 0개이면 null.
- 오류
  - 기능 꺼짐: 503 `FEATURE_DISABLED`(`retryable=false`). **새 ErrorCode 제안.**
  - 그 밖은 기존 오류 코드를 쓴다.

### 3-3. 태그 코드 (`tagSetVersion: choice-tags-v1`) — 2026-11-09 확정

선지 분석(`/verify`)·문장 확인·비슷한 기출 변형 표시가 **같은 5종**을 쓴다. 화면 글자는 Spring 이 code → 한글로 바꾼다.

| code | 화면 표시 | 예 |
|---|---|---|
| `TIMING` | 시점·기한 | 3개월 ↔ 6개월 이내, "날부터" ↔ "다음 날부터", 인가 전 ↔ 인가 후 |
| `CONDITION` | 조건 추가 | 조문에 없는 요건·예외를 붙이거나 뺌, 하여야 한다 ↔ 할 수 있다, 해당한다 ↔ 해당하지 않는다 |
| `ACTOR` | 주체·권한 | 국토교통부장관 ↔ 시·도지사 ↔ 시장·군수, 인가 ↔ 신고 |
| `CONCEPT` | 개념 오인 | 재건축 ↔ 재개발, 공장 ↔ 창고, 용어·제도 혼동 |
| `NUMBER` | 숫자·수치 오류 | 금액·비율·면적·횟수(2분의 1 ↔ 3분의 2, 1만㎡) |

- `/verify`(구현됨, `VERIFY_TAGS=1`일 때): LLM 이 CONTRADICTS 선지에만 1~2개 고르고, Python 이 5종 밖 코드를 버린다. 시점이 숫자로 바뀐 경우는 `TIMING` 우선.
- 이전 초안의 8종(NUMBER·ACTOR·MODALITY·NEGATION·SCOPE·PROCEDURE·OBJECT·OTHER)은 규칙 분류의 세부 신호로만 쓰고, 응답에는 위 5종으로 묶어 낸다: SCOPE(기간·기준점)→TIMING, MODALITY·NEGATION→CONDITION, PROCEDURE→ACTOR, OBJECT→CONCEPT, 숫자→NUMBER(기간이면 TIMING).

- 분류 방법(Python, 규칙 기반): 두 문장을 형태소로 비교해 다른 구간을 찾는다. 숫자나 단위가 있으면 NUMBER, 권한자 사전에 있으면 ACTOR, 서술어 끝 사전에 걸리면 MODALITY, NEGATION, SCOPE로 정한다. 사전은 `choice-tags-v1` 파일 하나로 관리한다.
- 공부 팁 문장은 3개 기출에서 가장 많이 나온 유형 1~2개로 템플릿을 고르고, `TRUE` 기출 문장(없으면 근거 조문)의 해당 구간을 "현행 표현"으로 채운다.

---

## 4. 기출 선지 은행

> **[2026-11-09 수정 방향]** 계약 C01("DB는 Spring 소유, Python은 DB를 갖지 않는다")과 이미 구현된 Spring 공개 기출·기출 배치(ExamBatch)·유사문제 추천(`recommendation` 패키지: 공통 근거 조문 + 임베딩 유사도)을 고려해, **권장안은 기출 선지 데이터와 후보 선택을 Spring이 맡고(기존 추천을 선지 단위로 확장), Python은 두 문장을 비교해 변형 유형·팁을 만드는 상태 없는 API만 맡는 것**이다. 이 경우 3-2의 `/similar-items`는 `/variation-diff`(요청: 사용자 문장 + 후보 기출 선지 1~5개 → 응답: 후보별 diff·유형 + tip)로 바뀌고, 아래 Python 소유 은행 파일은 Spring 적재 전 오프라인 평가용으로만 쓴다. 아래 원안은 비교용으로 남긴다.

### (원안) Python 소유, 읽기 전용

- 원천: 25~36회 2차 1교시 1과목·공법 960문제 × 선지 5개 = 4,800개.
- 선지 정답(truth) 자동 계산
  - 일반형 "옳은 것은?"이면 정답 번호는 `TRUE`, 나머지는 `FALSE`. "틀린 것은?"은 반대.
  - ㄱㄴㄷ 조합형과 "모두 고른 것" 형식, 범위 밖 문제는 `UNKNOWN`(조합형) 또는 제외(범위 밖, 전항정답, 개정으로 정답이 무효인 문제)로 둔다.
  - 개정으로 판단이 바뀐 메모가 있는 문제도 제외한다.
- 조문(articles): 문제 단위 정답 조문 라벨을 그 문제의 모든 선지에 붙인다(1차). 선지 단위 라벨은 나중에 늘린다.
- 파일: `ai-service/data/bank/bank-<version>.jsonl`과 `manifest.json`(버전, 건수, sha1). **저장소에는 P3 확인 전까지 올리지 않는다.** 서버는 환경변수 `SIMILAR_BANK_PATH`로 읽는다.
- 기능 스위치(Python): `SIMILAR_BACKEND=off | bank`. off이면 503 `FEATURE_DISABLED`. 기본값은 off.
- 기능 스위치(Spring): `LAWGROUND_SIMILAR_ENABLED`. 기본값 false, local 프로필에서만 true 권장.
- 검색 방식(`similar-v1`)
  1. `articleKeys`와 같은 조문을 가진 선지를 1차 후보로 잡는다.
  2. 선지 문장 BM25(Kiwi 토큰) 상위 50개를 더한다.
  3. 점수 = BM25 정규화 + 같은 조문 가산 0.5. 같은 문제(회차·번호)는 1개만 남긴다.
  4. TRUE/FALSE가 섞이도록 한쪽만 3개면 다른 쪽 최고점 1개로 바꾼다. 차이 비교용 대조 효과를 위해서다.
  5. 임베딩·LLM을 쓰지 않아 1초 안에 끝나고 메모리를 더 쓰지 않는다.

---

## 5. Spring 작업 흐름 (② 문장 확인)

- 작업 종류: `STATEMENT_CHECK`(새 job type). 큐와 워커는 기존 LIVE 워커 구조를 재사용한다.
- 단계
  1. **PREPARE**: 입력을 고정한다(스냅샷 해시).
  2. **SEARCH**: 질의 텍스트 = `subject: …\ncontext: …\nstatement: …`. 기존 Hybrid → rerank 상위 5개. 과목별 `LAW_IDS`를 쓴다.
  3. **VERIFY**: `/verify-statement`. 기존 검사(인용 일치, 후보 밖 ID, 버전)에 3-1절 일관성 규칙을 더한다.
  4. **SIMILAR**: `/similar-items`에 SEARCH 상위 조문을 `articleKeys`로 넘긴다. **best-effort**라서 실패하거나 시간이 넘으면 `similar=null`로 넘어간다. 재시도하지 않는다.
  5. **PERSIST**: 판정·인용·wrongSpans·고친 문장·similar 결과(`bankVersion` 포함)를 저장한다.
- 저장 테이블(제안): `statement_checks`(입력 스냅샷, 상태), `statement_check_results`(판정·요약·버전), `statement_check_evidences`, `statement_check_similar_items`(표시용 사본, bankVersion).
  - similar 결과를 사본으로 저장하는 이유는, 은행이 바뀌어도 사용자가 본 결과가 그대로 재현되게 하기 위해서다.
- 제한 시간: verify-statement 120초, similar-items 5초. rerank는 기존 설정을 따른다(현재 120초 상향 요청 중).
- 버전 고정: `promptVersion=statement-v1`, `algorithmVersion=similar-v1`, `bankVersion`을 결과에 남긴다.

---

## 6. 평가 계획 (구현과 함께 Python이 수행)

- **② 판정 정확도**
  - 은행의 `TRUE`/`FALSE` 선지(조합형 제외, 약 3,000개 예상)가 그대로 정답 있는 시험지가 된다.
  - 무작위 100개로 정확도와 **오탐(틀린 문장을 맞다고 함) 비율**을 잰다.
  - Gemini 비용이 드니 유료 결제 후에 한다.
  - 조건: 정답 조문이 후보에 있을 때 / 실제 검색 결과일 때를 나눠서 잰다.
- **③ 비슷한 기출 품질**
  - 문장 30개를 골라, 상위 3개가 "같은 쟁점"인지 사람이 O/X로 판정한다. 목표 Precision@3 0.8 이상.
  - 변형 유형 분류 정확도도 같은 표본으로 본다.
- **속도**: similar-items p95 1초 이하. verify-statement는 기존 VERIFY와 비슷하게 약 15~20초를 목표로 한다.

---

## 7. 역할과 순서

| 순서 | 할 일 | 담당 | 의존 |
|---|---|---|---|
| 1 | 이 문서 검토, P1·P3 결정 | 영주·수종 | — |
| 2 | 은행 생성 스크립트, `/similar-items`, 변형 사전, 테스트 | Python | — |
| 3 | `/verify-statement` 프롬프트·검사·테스트, OpenAPI 0.2.0-draft 생성 | Python | — |
| 4 | 공법 법령 import·색인, 과목별 LAW_IDS | Spring | P1 |
| 5 | `STATEMENT_CHECK` 작업, 테이블, 외부 API 2-1~2-3 | Spring | 2·3의 OpenAPI |
| 6 | 화면 1-1·1-2 | 프론트 | 5 |
| 7 | 평가(6절), 결과를 발표 문서에 반영 | Python | 2·3·5 |

- Python 쪽 2·3은 Spring 없이 단독으로 시험할 수 있게 만든다(합성 요청 + 실제 은행).
- Spring은 Python 완성 전에도 Mock(`SIMILAR_BACKEND=mock`, `VERIFY_BACKEND=mock`)으로 개발할 수 있다. Mock 응답은 3절 예시를 그대로 쓴다.

## 8. 결정이 필요한 것 (권장안)

| 질문 | 권장안 | 이유 |
|---|---|---|
| 문장 판정 enum을 새로 둘까, 기존 Verdict를 재사용할까 | **새 enum `CONSISTENT/INCONSISTENT/HOLD`** | 기존 SUPPORTED는 "정답 번호가 맞다"는 뜻이라, 화면에서 의미가 섞인다 |
| reasonCode 사전을 늘릴까 | **늘리지 않음**(5개 재사용) | 11/08 확정 사전과 일관 |
| 공부 팁을 LLM으로 만들까 | **규칙 기반(v1)**, LLM은 후속 | 무료·빠름·결정적, 거짓 설명 위험이 없음 |
| similar 결과를 저장할까 | **저장(표시용 사본)** | 은행이 바뀌어도 재현 가능 |
| 기출 원문 표시 | **표시함, 출처·재가공 고지 필수**(P3 결정) | 저작권 표시 의무 |
| ③을 5지선다 분석에도 붙일까 | **붙임(버튼, 동기 호출)** | LLM 비용 0, 학습 효과 큼 |
