# 실제 서버 (app.main) — tokenize 는 실제 Kiwi, 임베딩·리랭커는 환경변수로 선택

> **무엇이 실제이고 무엇이 아닌가**
> | 기능 | 상태 |
> |---|---|
> | `/tokenize` | **실제 Kiwi** (이 환경에서 검증됨) |
> | `/embed` | 실제 BGE-M3 코드 있음. **실제 모델로 실행해 본 적 없음** (수종 PC 에서 첫 실행). 테스트는 가짜 모델로 서버 로직만 검증 |
> | `/rerank` | 실제 BGE reranker 코드 있음. **실제 모델로 실행해 본 적 없음** |
> | `/hyde`, `/verify` | **실제 구현 없음** (LLM 공급자 미정). `mock` 으로 켜거나 `off`(503) |
> **기본값은 모두 꺼짐(off)이다.** 설정하지 않은 기능은 가짜 값을 내지 않고 503 `MODEL_NOT_READY` 를 반환한다.

## 실행 프로필 (Windows cmd)
토큰 값은 임의의 로컬 값이다. **저장소·노션·채팅에 쓰지 않는다.**

**A. tokenize 만 실제, 나머지는 mock** (모델 설치 불필요 — 가장 먼저 이걸로 확인)
```
set INTERNAL_API_TOKEN=<로컬 값>
set EMBED_BACKEND=mock
set RERANK_BACKEND=mock
set HYDE_BACKEND=mock
set VERIFY_BACKEND=mock
uvicorn app.main:app --port 8001
```
**B. 실제 임베딩·리랭커** (모델 설치와 첫 다운로드 필요. 서버 시작 때 모델을 미리 불러오므로 시작이 느리다)
```
pip install -r requirements-models.txt
set INTERNAL_API_TOKEN=<로컬 값>
set EMBED_BACKEND=bge-m3
set RERANK_BACKEND=bge-reranker
set HYDE_BACKEND=mock
set VERIFY_BACKEND=mock
uvicorn app.main:app --port 8001
```
**확인**: 다른 cmd 창에서 같은 `INTERNAL_API_TOKEN` 을 설정하고 `python scripts\smoke.py`

## 환경변수
| 이름 | 값 | 설명 |
|---|---|---|
| `INTERNAL_API_TOKEN` | 임의 문자열 | **필수.** 없으면 모든 요청을 401 로 거절한다(fail closed) |
| `EMBED_BACKEND` | `off`(기본) / `bge-m3` / `mock` | |
| `RERANK_BACKEND` | `off`(기본) / `bge-reranker` / `mock` | |
| `HYDE_BACKEND`, `VERIFY_BACKEND` | `off`(기본) / `mock` | 실제 구현 없음 |
| `EMBED_MODEL_REVISION`, `RERANK_MODEL_REVISION` | 40자 커밋 해시(선택) | 비우면 캐시된 최신 스냅샷의 해시를 `revision` 으로 쓴다. 고정하려면 지정 |

## readiness (`GET /internal/v1/health/ready`)
`tokenizer` + `embed` + `rerank` 가 모두 ready 일 때만 200 `READY`. 하나라도 꺼져 있으면 503 `NOT_READY` 이고 본문은 `ReadyOutput`(오류 DTO 아님)이다.
모델 이름에 백엔드가 드러난다: `embed:mock`, `embed:off`, `embed:BAAI/bge-m3`. Spring 은 여기서 `revision` 을 읽어 요청의 `modelRevision`/`tokenizerVersion` 에 넣는다(불일치면 409).

## 오류 동작 (제안, Spring 과 합의 전)
| 상황 | 응답 |
|---|---|
| 모델 입력 상한 초과 | 422 `INPUT_TOO_LONG` (자르지 않는다. 메시지에 해당 id) |
| `modelRevision`/`tokenizerVersion` 불일치 | 409 `MODEL_REVISION_MISMATCH` |
| 백엔드 off 또는 모델 미로드 | 503 `MODEL_NOT_READY` (retryable=false) |
| 토큰 10,000개 초과 | 422 `INPUT_TOO_LONG` |

## 알려진 사항
- 계약은 텍스트 30,000자 이하·토큰 10,000개 이하를 허용하는데, `"약 "` 을 15,000번 반복한 30,000자 입력은 토큰이 15,000개라서 **계약이 허용하는 입력인데도 422** 가 된다(테스트로 재현). Spring 과 상한 정합을 협의해야 한다.
- `kiwipiepy_model` 은 wheel 없이 소스만 배포돼 설치 때 pip 이 빌드한다(컴파일러 불필요). Windows 에서의 설치는 수종 PC 에서 확인해야 한다.
- 토큰화 규칙(rules-1): 조문 번호·숫자+단위는 정규식 선추출, 조사·어미 제거, 부정어 보존, 불용어 사전 없음, 순서·중복 보존.
