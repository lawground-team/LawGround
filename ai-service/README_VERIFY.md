# 실제 /verify (LLM) — 설정·한계·manifest

`VERIFY_BACKEND=gemini` 일 때만 켜진다. 기본값(off)·mock 동작은 그대로다.

## 환경변수
| 이름 | 필수 | 설명 |
|---|---|---|
| `VERIFY_BACKEND` | ✅ | `gemini` |
| `LLM_API_KEY` | ✅ | Google AI Studio 키. **커밋 금지**, 로그·오류 메시지에 나오지 않는다 |
| `LLM_MODEL` | ✅ | 모델 ID(영문·숫자·`._-`). **미리보기(preview)·`latest` 같은 이동 별칭보다 안정 버전 ID** 권장 |
| `LLM_MODEL_REVISION` | | 고정 식별자(≤160자). 비우면 모델 ID. 공급자가 불변 revision 을 주지 않아서 우리가 적는 값이다 |
| `LLM_TIMEOUT_SECONDS` | | 호출 1회 제한(기본 50, 최대 55). 형식 오류 재시도 1회까지 합쳐 Spring 제한 120초 안에 끝나게 한다 |
| `LLM_MAX_INPUT_CHARS` | | 입력 상한(기본 150000자). 넘으면 자르지 않고 422 `INPUT_TOO_LONG` |

## Spring 쪽 설정(같은 값이어야 한다)
- `AI_VERIFIER_MODEL_NAME` = `verify:<LLM_MODEL>` (예: `verify:gemini-…`). `/health/ready` 가 광고하는 이름과 글자까지 같아야 한다.
- `AI_VERIFIER_MODEL_REVISION` = `LLM_MODEL_REVISION`(또는 모델 ID), `AI_VERIFIER_PROMPT_VERSION` = `verify-prompt-v1`.
- 모델·프롬프트가 바뀌면 이 세 값도 같이 바꾼다(Spring 은 자동 채택하지 않는다).

## 동작 규칙
- LLM 은 판정과 인용 **문구**만 낸다. 인용의 `start/end/quote` 는 Python 이 원문(sourceText)에서 다시 찾아 만든다(공백 차이만 허용, 없으면 버림).
- 근거 인용이 하나도 확인되지 않으면 SUPPORTED/REJECTED 대신 **HOLD(`NO_VERIFIED_QUOTE`)**.
- 선지 분석이 정확히 5개로 안 맞으면 `choiceAnalyses=null`(미제공). 근거 없는 SUPPORTS/CONTRADICTS 는 INSUFFICIENT 로 내린다. `tags=[]`, `tagSetVersion=null` 만 낸다.
- 판정과 선지 분석이 모순이면 **HOLD(`INCONSISTENT_ANALYSIS`)**.
- 형식 오류(JSON 깨짐)만 1회 다시 묻는다. 외부 장애(429/5xx/시간 초과)는 다시 부르지 않는다.
- 오류 대응: 429 `RATE_LIMITED`(Retry-After), 504 `MODEL_TIMEOUT`, 502 `MODEL_UPSTREAM_ERROR`, 502 `MODEL_OUTPUT_INVALID`, 422 `INPUT_TOO_LONG`.
- reasonCode: `SUFFICIENT_EVIDENCE` / `CONTRADICTED_BY_LAW` / `INSUFFICIENT_EVIDENCE` / `NO_VERIFIED_QUOTE` / `INCONSISTENT_ANALYSIS` **[제안 — 영주 님과 합의 필요]**

## manifest (영주 님 요청서 C10 양식)
| 항목 | 값 |
|---|---|
| 역할 | 근거 검증(/verify) |
| 공급자 | Google (Gemini API, REST) |
| 모델명 | `LLM_MODEL` 값 |
| revision | `LLM_MODEL_REVISION` 값(공급자가 불변 revision 을 주지 않음 → 안정 버전 ID 를 고정해 쓰고, 바뀌면 이 값을 갱신) |
| promptVersion | `verify-prompt-v1` |
| 생성 파라미터 | temperature 0, JSON 출력, maxOutputTokens 8192 |
| 입력 상한 | 150000자(설정), 자르지 않고 거절 |
| 실행 환경 | 외부 API(로컬 GPU 없음) |
| 멱등성 | **지원하지 않음.** 같은 요청도 호출마다 비용이 든다 |
| 취소 | **지원하지 않음.** 연결이 끊겨도 이미 보낸 LLM 호출은 끝까지 간다 |

## 모델 선택 주의 (2026-10-27 실제로 겪음)
- 모델 목록(`/v1beta/models`)에 이름이 있어도 **새 계정에는 막힌 모델**일 수 있다. 예: `gemini-2.5-flash` 는 목록에 있었지만 호출하면 404
  (`no longer available to new users`, 대신 `gemini-3.8-flash` 권장). 목록 조회는 가용성을 보장하지 않으니 **실제로 한 번 호출해 보고** 고른다.
- 공급자가 모델을 몇 달 단위로 닫을 수 있다. 닫히면 서비스가 멈추므로 `LLM_MODEL`·`LLM_MODEL_REVISION`·Spring 의 `AI_VERIFIER_*` 를 같이 바꾼다.
- 오류 사유(`status`, `message`)는 키를 가린 채 오류 메시지와 `verify_probe.py` 출력에 나온다.

## 한계
- Gemini 요청 형식은 문서·기억에 기댄 것이며 **실제 키로 호출해 확인하지 못했다**(테스트는 가짜 전송). 첫 실제 호출에서 어긋나면 `scripts/verify_probe.py` 로 확인한다.
- 무료 티어는 한도가 낮고 요청 내용이 제품 개선에 쓰일 수 있다(공개 법령·기출 문제만 보낸다).
- LLM 판정 품질은 측정 전이다. `scripts/verify_probe.py` 로 정답 번호(SUPPORTED)·틀린 번호(REJECTED, 오탐) 두 방향을 잰다. 후보에 정답 조문을 넣으므로 **검색이 아니라 판정 능력만** 잰다.
