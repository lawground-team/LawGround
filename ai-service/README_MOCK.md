# ai-service Mock 서버 사용법 (README_MOCK.md)

> **현재 상태: Mock 단계(S01·S02).** 계약(0.1.0-draft)을 코드로 옮긴 모델과 **고정 응답 Mock 서버**만 있다.
> **실제 모델 호출·성능 측정은 없다.** 응답의 `modelRevision`/`promptVersion` 이 `mock-*` 이므로 실제 AI 와 구분된다.
> 계약 원본은 `docs/api/internal.openapi.yaml`(Spring 측 제안, 수종 동의 전)이다. 이 폴더의 `openapi.yaml` 은 **코드에서 자동 생성한 것**이라 원본과 diff 해서 확인해야 한다.

## 구성
| 경로 | 내용 |
|---|---|
| `app/contract/models.py` | Pydantic 계약 모델 (추가 필드 금지, 정수에 실수 거절, 공백만 거절) |
| `app/contract/checks.py` | 요청-응답 교차 검사 (후보 밖 chunkId, `sourceText[start:end]==quote`, topK, id 순서) |
| `app/mock/server.py` | Mock 서버 (FastAPI) |
| `fixtures/` | 정상·입력 오류·운영 오류 27개 (Mock 을 호출해 생성). Spring 도 같은 파일로 검사할 수 있다 |
| `tests/` | 계약 테스트 29개 (fixture 재생 포함) |
| `openapi.yaml`, `openapi.json` | 모델에서 자동 생성한 OpenAPI |

## 실행 (Windows cmd 기준, Python 3.10 이상. 3.12 에서 테스트했고 3.14 는 미검증)
```
cd ai-service
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
set INTERNAL_API_TOKEN=<로컬 개발용 임의 값>
uvicorn app.mock.server:app --port 8001
python -m unittest discover -s tests -v
python scripts\export_openapi.py
python scripts\gen_fixtures.py
```
`INTERNAL_API_TOKEN` 은 환경변수 **이름**만 문서화한다. 실값은 저장소·Notion·채팅에 쓰지 않는다. 값이 없으면 Mock 은 로컬 전용 더미 값으로 동작하며, 실제 서비스에서는 쓰지 않는다.

## Mock 시나리오 (계약이 아닌 Mock 전용 헤더)
`X-Mock-Scenario: hold | rejected | with_choices | rate_limited | not_ready | timeout | internal`

## 한계
- Mock 의 토큰은 "조문 번호 한 토큰"이라는 **목표 규칙을 정규식으로 흉내 낸 값**이며 Kiwi 출력이 아니다.
- Mock 의 선지 관계(relation)는 "제공 정답만 SUPPORTS" 로 단순화했다. 실제 판정이 아니다.
- 오프셋은 Unicode code point 기준이다. Java(UTF-16)는 변환해야 한다.
- 모델 지연·메모리·처리량은 측정하지 않았다.
