"""Mock 서버를 실제로 호출해서 정상·입력 오류·운영 오류 fixture 를 만든다 (손으로 쓰지 않는다).

각 파일: {description, mock(true), request{method,path,headers,body}, expected{status,headers,body}}
동적 값(timestamp)은 '<UTC-Z>' 로 치환해 비교 시 형식만 검사한다. 서비스 토큰 실값은 파일에 쓰지 않는다.
"""
import json
import sys
from pathlib import Path
from uuid import UUID

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tests"))
from fastapi.testclient import TestClient  # noqa: E402

from app.mock.server import P, app  # noqa: E402
from test_contract import hdr, snapshot, verify_body  # noqa: E402

c = TestClient(app)
OUT = ROOT / "fixtures"
N = 0


def uid(n):  # 읽기 쉬운 고정 UUID
    return str(UUID(int=n))


def save(group, name, desc, method, path, body, status_expect=None, scenario=None, raw=None, drop_header=None, header_rid=None):
    global N
    rid = (body.get("requestId") if isinstance(body, dict) else None) or uid(900)   # GET 은 본문이 없으므로 고정 UUID (재생성해도 파일이 바뀌지 않게)
    _, h = hdr(rid=header_rid or rid)
    h["X-Job-Id"] = uid(901)
    h["Idempotency-Key"] = uid(902)
    if scenario:
        h["X-Mock-Scenario"] = scenario
    if drop_header:
        h.pop(drop_header)
    data = raw if raw is not None else json.dumps(body, ensure_ascii=False).encode()
    r = c.request(method, P + path, content=data if method == "POST" else None, headers=h)
    exp = r.json()
    if isinstance(exp, dict) and "timestamp" in exp:
        exp["timestamp"] = "<UTC-Z>"
    if isinstance(exp, dict) and "requestId" in exp and exp["requestId"] != h["X-Request-Id"]:
        exp["requestId"] = "<UUID>"
    shown_h = dict(h)   # 고정 UUID 는 그대로 저장한다(헤더-본문 requestId 불일치 같은 사례를 재현하려면 실제 값이 필요)
    if "X-Internal-Token" in shown_h:
        shown_h["X-Internal-Token"] = "<서비스 토큰: 환경변수 INTERNAL_API_TOKEN>"
    doc = {"description": desc, "mock": True,
           "request": {"method": method, "path": P + path, "headers": shown_h,
                       "body": body if raw is None else raw.decode("utf-8", "replace")},
           "expected": {"status": r.status_code, "headers": {k: v for k, v in r.headers.items() if k.lower() == "retry-after"}, "body": exp}}
    if status_expect is not None:
        assert r.status_code == status_expect, (group, name, r.status_code, r.text[:200])
    d = OUT / group
    d.mkdir(parents=True, exist_ok=True)
    (d / f"{name}.json").write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
    N += 1


def vb(src="법 제32조제3항에 따른 중개보수의 지급시기는 약정에 따르되, 약정이 없을 때에는 거래대금 지급이 완료된 날로 한다.", **snap):
    b = verify_body(src, **snap)
    b["requestId"] = uid(100)
    b["candidates"][0].update(chunkId=uid(201))
    b["candidates"][0]["metadata"].update(articleId=uid(301), lawVersionId=uid(401))
    return b


E = lambda n, t: {"id": uid(n), "text": t}
eb = {"requestId": uid(1), "modelRevision": "rev-1", "texts": [E(11, "중개보수의 지급시기"), E(12, "약정이 없을 때에는")]}
save("embed", "ok", "정상: 2건, id·순서 보존, dim=1024 (Mock 벡터)", "POST", "/embed", eb, 200)
save("embed", "invalid_batch_over_32", "입력 오류: 33건 → 422 INVALID_REQUEST", "POST", "/embed",
     {"requestId": uid(2), "modelRevision": "rev-1", "texts": [E(100 + i, "x") for i in range(33)]}, 422)
save("embed", "invalid_blank_text", "입력 오류: 공백만 있는 text → 422", "POST", "/embed",
     {"requestId": uid(3), "modelRevision": "rev-1", "texts": [E(13, "   ")]}, 422)
save("embed", "ops_unauthorized", "운영 오류: 서비스 토큰 없음 → 401 UNAUTHORIZED [제안 코드]", "POST", "/embed", eb, 401, drop_header="X-Internal-Token")
save("embed", "ops_not_ready", "운영 오류: 모델 준비 안 됨 → 503 MODEL_NOT_READY, retryable=true", "POST", "/embed", eb, 503, scenario="not_ready")
tb = {"requestId": uid(4), "tokenizerVersion": "tok-1", "texts": [E(21, "법 제32조제3항에 따른 30일 이내 지급한다")]}
save("tokenize", "ok", "정상: 조문 번호·숫자+단위가 한 토큰으로 유지됨(Mock 은 정규식 분리, 형태소 분석 아님)", "POST", "/tokenize", tb, 200)
save("tokenize", "invalid_extra_field", "입력 오류: 허용되지 않는 추가 필드 → 422", "POST", "/tokenize", {**tb, "requestId": uid(5), "unexpected": 1}, 422)
save("tokenize", "ops_timeout", "운영 오류: 504 MODEL_TIMEOUT, retryable=true", "POST", "/tokenize", tb, 504, scenario="timeout")
hb = {"requestId": uid(6), "questionSnapshot": snapshot(), "referenceDate": "2025-10-01"}
save("hyde", "ok", "정상: 가상 문서와 검색 보조어 (Mock). 가상 문서는 인용 근거가 아니다", "POST", "/hyde", hb, 200)
save("hyde", "invalid_date_mismatch", "입력 오류: 바깥 referenceDate 와 snapshot.referenceDate 불일치 → 422", "POST", "/hyde",
     {**hb, "requestId": uid(7), "referenceDate": "2025-10-02"}, 422)
save("hyde", "ops_rate_limited", "운영 오류: 429 RATE_LIMITED + Retry-After(초), retryable=true", "POST", "/hyde", hb, 429, scenario="rate_limited")
rb = {"requestId": uid(8), "query": "중개보수 지급시기 약정", "topK": 2,
      "candidates": [{"chunkId": uid(500 + i), "text": t} for i, t in enumerate(["중개보수의 지급시기는 약정에 따른다", "등기 신청 절차", "약정이 없을 때에는 거래대금 지급 완료일"])]}
save("rerank", "ok", "정상: 후보 3개 중 topK=2, 후보 ID 안에서 score 내림차순", "POST", "/rerank", rb, 200)
save("rerank", "invalid_topk_over_5", "입력 오류: topK=6 → 422", "POST", "/rerank", {**rb, "requestId": uid(9), "topK": 6}, 422)
save("rerank", "ops_internal", "운영 오류: 500 INTERNAL_ERROR, retryable=false", "POST", "/rerank", rb, 500, scenario="internal")
save("verify", "ok_supported", "정상: SUPPORTED. 근거 인용과 오프셋(code point). choiceAnalyses=null, tagSetVersion=null", "POST", "/verify", vb(), 200)
save("verify", "ok_hold", "정상: HOLD(근거 부족). 증거 0개, HTTP 200 — 기술 장애가 아니다", "POST", "/verify", vb(), 200, scenario="hold")
save("verify", "ok_rejected", "정상: REJECTED(제공 정답이 법령과 충돌). 상충을 보이는 인용 1개 이상 [제안]", "POST", "/verify", vb(), 200, scenario="rejected")
save("verify", "ok_with_choice_analyses", "정상: 선지 분석 5개 제공(tagSetVersion 필요). Mock 의 관계는 단순화된 값", "POST", "/verify", vb(), 200, scenario="with_choices")
save("verify", "ok_emoji_newline_offsets", "정상: 이모지·줄바꿈이 있는 원문의 code point 오프셋 (Java UTF-16 으로 변환 필요)", "POST", "/verify", vb("앞😀근거 문장\n뒤"), 200)
save("verify", "invalid_choice_numbers", "입력 오류: 선지 번호가 1~5 를 한 번씩 채우지 않음 → 422", "POST", "/verify",
     {**vb(), "requestId": uid(10), "questionSnapshot": snapshot(choices=[{"number": 1, "text": "a"}] * 5)}, 422)
save("verify", "invalid_no_candidates", "입력 오류: 후보 0개는 현재 계약으로 호출 불가 → 422 (Spring 이 HOLD 처리할지 합의 필요, C07)", "POST", "/verify",
     {**vb(), "requestId": uid(11), "candidates": []}, 422)
save("verify", "invalid_duplicate_json_key", "입력 오류: 중복 JSON 키 → 400 INVALID_REQUEST", "POST", "/verify", {"requestId": uid(12)}, 400,
     raw=('{"requestId": "%s", "requestId": "%s"}' % (uid(12), uid(12))).encode())
save("verify", "invalid_header_body_request_id_mismatch", "입력 오류: X-Request-Id 와 본문 requestId 불일치 → 400", "POST", "/verify",
     {**vb(), "requestId": uid(13)}, 400, header_rid=uid(14))
save("verify", "ops_timeout", "운영 오류: 504 MODEL_TIMEOUT. Spring 은 늦게 온 결과를 저장하지 않는다", "POST", "/verify", vb(), 504, scenario="timeout")
save("verify", "ops_not_ready", "운영 오류: 503 MODEL_NOT_READY", "POST", "/verify", vb(), 503, scenario="not_ready")
save("health", "ready_ok", "정상: 200 READY", "GET", "/health/ready", {}, 200)
save("health", "ready_not_ready", "준비 안 됨: 503 + ReadyOutput 본문(에러 DTO 아님) — Spring 파싱에서 구분 필요", "GET", "/health/ready", {}, 503, scenario="not_ready")
print("fixtures:", N)
