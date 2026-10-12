import json
import sys
import unittest
from pathlib import Path
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient  # noqa: E402
from pydantic import ValidationError  # noqa: E402

from app.contract import models as M  # noqa: E402
from app.mock.server import DEV_TOKEN, P, app  # noqa: E402
from app.contract.checks import (ContractViolation, check_embed, check_rerank,  # noqa: E402
                                  check_tokenize, check_verify)

client = TestClient(app)


def hdr(rid=None, **extra):
    rid = rid or str(uuid4())
    h = {"X-Internal-Token": DEV_TOKEN, "X-Request-Id": rid, "X-Contract-Version": M.CONTRACT_VERSION,
         "X-Job-Id": str(uuid4()), "Idempotency-Key": str(uuid4()), "Content-Type": "application/json"}
    h.update(extra)
    return rid, h


def snapshot(**kw):
    s = {"subject": "BROKER_LAW", "stem": "다음 중 중개보수의 지급시기에 관한 설명으로 옳은 것은?",
         "questionType": "SELECT_CORRECT",
         "choices": [{"number": i, "text": f"선지 {i}"} for i in range(1, 6)],
         "providedAnswerNumber": 2, "referenceDate": "2025-10-01", "revision": 1}
    s.update(kw)
    return s


def post(path, body, scenario=None, **hkw):
    rid, h = hdr(rid=body.get("requestId") if isinstance(body, dict) else None, **hkw)
    if scenario:
        h["X-Mock-Scenario"] = scenario
    return client.post(P + path, content=json.dumps(body, ensure_ascii=False).encode(), headers=h)


def verify_body(src="앞😀근거 문장\n뒤", **snap):
    return {"requestId": str(uuid4()), "questionSnapshot": snapshot(**snap), "referenceDate": "2025-10-01",
            "candidates": [{"chunkId": str(uuid4()), "sourceText": src,
                            "metadata": {"articleId": str(uuid4()), "lawVersionId": str(uuid4()), "lawTitle": "공인중개사법 시행령",
                                         "articleLabel": "제27조의2", "effectiveFrom": "2024-07-01", "effectiveTo": None}}]}


class SchemaTests(unittest.TestCase):
    def test_extra_field_forbidden(self):
        with self.assertRaises(ValidationError):
            M.QuestionSnapshot(**snapshot(extra="x"))

    def test_float_for_int_rejected(self):
        with self.assertRaises(ValidationError):
            M.QuestionSnapshot(**snapshot(providedAnswerNumber=2.0))

    def test_choice_numbers_must_be_1_to_5_once(self):
        with self.assertRaises(ValidationError):
            M.QuestionSnapshot(**snapshot(choices=[{"number": 1, "text": "a"}] * 5))

    def test_reference_date_must_match(self):
        with self.assertRaises(ValidationError):
            M.HydeRequest(requestId=str(uuid4()), questionSnapshot=snapshot(), referenceDate="2025-10-02")

    def test_whitespace_only_text_rejected(self):
        with self.assertRaises(ValidationError):
            M.IdText(id=str(uuid4()), text="   ")

    def test_embed_vector_rules(self):
        base = {"id": str(uuid4())}
        with self.assertRaises(ValidationError):
            M.EmbedItem(vector=[0.0] * 1024, **base)               # 영벡터
        with self.assertRaises(ValidationError):
            M.EmbedItem(vector=[float("nan")] + [0.1] * 1023, **base)
        with self.assertRaises(ValidationError):
            M.EmbedItem(vector=[0.1] * 1023, **base)               # 차원 불일치

    def test_nullable_fields_are_required(self):
        d = {"verdict": "HOLD", "reasonCode": "X", "summary": "s", "evidences": [], "modelRevision": "m", "promptVersion": "p"}
        with self.assertRaises(ValidationError):                    # choiceAnalyses/tagSetVersion 생략 불가
            M.VerifyResponse(**d)
        M.VerifyResponse(**d, choiceAnalyses=None, tagSetVersion=None)

    def test_supported_requires_evidence(self):
        with self.assertRaises(ValidationError):
            M.VerifyResponse(verdict="SUPPORTED", reasonCode="X", summary="s", evidences=[], choiceAnalyses=None,
                             tagSetVersion=None, modelRevision="m", promptVersion="p")

    def test_rerank_must_be_score_desc(self):
        with self.assertRaises(ValidationError):
            M.RerankResponse(ranked=[{"chunkId": str(uuid4()), "score": 0.1}, {"chunkId": str(uuid4()), "score": 0.9}], modelRevision="m")


class OffsetTests(unittest.TestCase):
    """요청서 C08 예시: 이모지·줄바꿈·반복 구절. 오프셋은 Unicode code point 기준(시작 포함·끝 제외)."""

    def test_emoji_newline_example(self):
        src = "앞😀근거 문장\n뒤"
        self.assertEqual(src[2:7], "근거 문장")
        e = M.Evidence(evidenceKey="k", chunkId=str(uuid4()), quote="근거 문장", start=2, end=7)
        self.assertEqual((e.start, e.end), (2, 7))
        # Java(UTF-16) 에서는 😀 가 2 code unit 이라 같은 구간이 3..8 이 된다 → 변환 필요
        self.assertEqual(len(src.encode("utf-16-le")) // 2 - len(src), 1)

    def test_repeated_phrase_picks_given_offset(self):
        src = "약정이 없을 때에는 지급한다. 약정이 없을 때에는 지급한다."
        second = src.index("약정이", 5)
        self.assertGreater(second, 0)
        req = M.VerifyRequest(**verify_body(src))
        resp = M.VerifyResponse(verdict="SUPPORTED", reasonCode="SUFFICIENT_EVIDENCE", summary="s", choiceAnalyses=None,
                                tagSetVersion=None, modelRevision="m", promptVersion="p",
                                evidences=[M.Evidence(evidenceKey="k", chunkId=req.candidates[0].chunkId,
                                                      quote="약정이 없을 때에는", start=second, end=second + len("약정이 없을 때에는"))])
        check_verify(req, resp)   # 두 번째 위치도 유효해야 한다(중복 구절은 오프셋으로 구분)

    def test_quote_mismatch_detected(self):
        req = M.VerifyRequest(**verify_body("법 제32조제3항에 따른 지급시기"))
        bad = M.VerifyResponse(verdict="SUPPORTED", reasonCode="X", summary="s", choiceAnalyses=None, tagSetVersion=None,
                               modelRevision="m", promptVersion="p",
                               evidences=[M.Evidence(evidenceKey="k", chunkId=req.candidates[0].chunkId, quote="지급시기", start=0, end=4)])
        with self.assertRaises(ContractViolation):
            check_verify(req, bad)

    def test_foreign_chunk_detected(self):
        req = M.VerifyRequest(**verify_body("원문 내용입니다"))
        bad = M.VerifyResponse(verdict="SUPPORTED", reasonCode="X", summary="s", choiceAnalyses=None, tagSetVersion=None,
                               modelRevision="m", promptVersion="p",
                               evidences=[M.Evidence(evidenceKey="k", chunkId=str(uuid4()), quote="원문", start=0, end=2)])
        with self.assertRaises(ContractViolation):
            check_verify(req, bad)


class MockServerTests(unittest.TestCase):
    def test_embed_roundtrip(self):
        body = {"requestId": str(uuid4()), "modelRevision": "rev-1",
                "texts": [{"id": str(uuid4()), "text": "첫째"}, {"id": str(uuid4()), "text": "둘째"}]}
        r = post("/embed", body)
        self.assertEqual(r.status_code, 200, r.text)
        check_embed(M.EmbedRequest(**body), M.EmbedResponse(**r.json()))
        self.assertEqual(r.json()["dim"], 1024)

    def test_embed_batch_limit_33(self):
        body = {"requestId": str(uuid4()), "modelRevision": "r",
                "texts": [{"id": str(uuid4()), "text": "x"} for _ in range(33)]}
        r = post("/embed", body)
        self.assertEqual(r.status_code, 422)
        self.assertEqual(r.json()["code"], "INVALID_REQUEST")
        self.assertFalse(r.json()["retryable"])

    def test_tokenize_keeps_article_numbers(self):
        body = {"requestId": str(uuid4()), "tokenizerVersion": "t-1",
                "texts": [{"id": str(uuid4()), "text": "법 제32조제3항에 따른 30일 이내"}]}
        r = post("/tokenize", body)
        self.assertEqual(r.status_code, 200)
        toks = r.json()["items"][0]["tokens"]
        self.assertIn("제32조", toks)
        self.assertIn("제3항", toks)
        check_tokenize(M.TokenizeRequest(**body), M.TokenizeResponse(**r.json()))

    def test_rerank_topk_and_ids(self):
        cands = [{"chunkId": str(uuid4()), "text": f"중개보수 지급시기 {i}"} for i in range(8)]
        body = {"requestId": str(uuid4()), "query": "중개보수 지급시기", "candidates": cands, "topK": 3}
        r = post("/rerank", body)
        self.assertEqual(r.status_code, 200)
        check_rerank(M.RerankRequest(**body), M.RerankResponse(**r.json()))
        self.assertEqual(len(r.json()["ranked"]), 3)

    def test_rerank_fewer_candidates_than_topk(self):
        body = {"requestId": str(uuid4()), "query": "q", "candidates": [{"chunkId": str(uuid4()), "text": "a"}], "topK": 5}
        r = post("/rerank", body)
        check_rerank(M.RerankRequest(**body), M.RerankResponse(**r.json()))
        self.assertEqual(len(r.json()["ranked"]), 1)

    def test_hyde_ok(self):
        body = {"requestId": str(uuid4()), "questionSnapshot": snapshot(), "referenceDate": "2025-10-01"}
        r = post("/hyde", body)
        self.assertEqual(r.status_code, 200, r.text)
        self.assertTrue(r.json()["modelRevision"].startswith("mock"))

    def test_verify_scenarios(self):
        for scen, verdict in [(None, "SUPPORTED"), ("hold", "HOLD"), ("rejected", "REJECTED"), ("with_choices", "SUPPORTED")]:
            body = verify_body()
            r = post("/verify", body, scenario=scen)
            self.assertEqual(r.status_code, 200, (scen, r.text))
            resp = M.VerifyResponse(**r.json())
            check_verify(M.VerifyRequest(**body), resp)
            self.assertEqual(resp.verdict.value, verdict)
        self.assertIsNone(M.VerifyResponse(**post("/verify", verify_body()).json()).choiceAnalyses)
        self.assertEqual(len(M.VerifyResponse(**post("/verify", verify_body(), scenario="with_choices").json()).choiceAnalyses), 5)

    def test_verify_response_keeps_null_fields(self):
        j = post("/verify", verify_body()).json()
        self.assertIn("choiceAnalyses", j)
        self.assertIn("tagSetVersion", j)
        self.assertIsNone(j["choiceAnalyses"])

    def test_ops_errors(self):
        want = {"rate_limited": (429, "RATE_LIMITED", True), "not_ready": (503, "MODEL_NOT_READY", True),
                "timeout": (504, "MODEL_TIMEOUT", True), "internal": (500, "INTERNAL_ERROR", False)}
        for scen, (http, code, retry) in want.items():
            r = post("/verify", verify_body(), scenario=scen)
            self.assertEqual(r.status_code, http, scen)
            e = M.ErrorOut(**r.json())
            self.assertEqual((e.code.value, e.retryable), (code, retry))
        self.assertEqual(post("/verify", verify_body(), scenario="rate_limited").headers.get("retry-after"), "2")

    def test_auth_and_headers(self):
        body = verify_body()
        rid, h = hdr(rid=body["requestId"])
        h.pop("X-Internal-Token")
        r = client.post(P + "/verify", content=json.dumps(body).encode(), headers=h)
        self.assertEqual((r.status_code, r.json()["code"]), (401, "UNAUTHORIZED"))
        rid, h = hdr(rid=body["requestId"], **{"X-Contract-Version": "9.9.9"})
        r = client.post(P + "/verify", content=json.dumps(body).encode(), headers=h)
        self.assertEqual((r.status_code, r.json()["code"]), (400, "UNSUPPORTED_CONTRACT_VERSION"))
        rid, h = hdr()                                              # 헤더 requestId 가 본문과 다름
        r = client.post(P + "/verify", content=json.dumps(body).encode(), headers=h)
        self.assertEqual((r.status_code, r.json()["code"]), (400, "INVALID_REQUEST"))

    def test_malformed_and_duplicate_and_trailing_json(self):
        rid, h = hdr()
        for raw in [b"{not json", b'{"requestId": "' + rid.encode() + b'", "requestId": "' + rid.encode() + b'"}',
                    b'{"requestId": "' + rid.encode() + b'"} {"x":1}']:
            r = client.post(P + "/embed", content=raw, headers=h)
            self.assertEqual((r.status_code, r.json()["code"]), (400, "INVALID_REQUEST"), raw)

    def test_error_dto_shape(self):
        e = post("/verify", verify_body(), scenario="timeout").json()
        self.assertEqual(set(e), {"code", "message", "requestId", "timestamp", "retryable"})
        self.assertTrue(e["timestamp"].endswith("Z"), e["timestamp"])
        self.assertLessEqual(len(e["message"]), 500)

    def test_readiness(self):
        _, h = hdr()
        r = client.get(P + "/health/ready", headers=h)
        self.assertEqual((r.status_code, r.json()["status"]), (200, "READY"))
        h["X-Mock-Scenario"] = "not_ready"
        r = client.get(P + "/health/ready", headers=h)
        self.assertEqual((r.status_code, r.json()["status"]), (503, "NOT_READY"))   # 503 본문은 ReadyOutput(에러 DTO 아님)
        h.pop("X-Internal-Token")
        self.assertEqual(client.get(P + "/health/ready", headers=h).status_code, 401)


class OpenApiTests(unittest.TestCase):
    def test_required_nullable_in_openapi(self):
        spec = app.openapi()
        v = spec["components"]["schemas"]["VerifyResponse"]
        for f in ("choiceAnalyses", "tagSetVersion", "verdict", "reasonCode", "summary", "evidences", "modelRevision", "promptVersion"):
            self.assertIn(f, v["required"], f)
        self.assertFalse(spec["components"]["schemas"]["Evidence"].get("additionalProperties", True))
        self.assertEqual(len(spec["paths"]), 6)


if __name__ == "__main__":
    unittest.main(verbosity=2)
