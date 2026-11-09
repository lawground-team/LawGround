"""0.2.0-draft 제안 경로: /verify-statement(가짜 LLM) 와 /variation-diff(규칙). 실제 키·네트워크 없음."""
import json
import os
import unittest
from unittest import mock
from uuid import uuid4

from fastapi.testclient import TestClient

from app.contract import models as M
from app.contract.checks import check_verify_statement
from app.main import app
from app.services import registry, statement as S, variation as VD, verifier as V
from tests.test_real_app import ENV, P, TOKEN
from tests.test_verifier import FakeLlm

SRC = "제4조(기본계획의 수립) ③ 특별시장·광역시장 또는 시장은 기본계획에 대하여 5년마다 타당성을 검토하여 그 결과를 기본계획에 반영하여야 한다."
STATEMENT = "국토교통부장관은 기본계획에 대하여 5년마다 타당성을 검토하여야 한다."


def hdr(rid, version=M.CONTRACT_VERSION_02):
    return {"X-Internal-Token": TOKEN, "X-Request-Id": rid, "X-Contract-Version": version, "Content-Type": "application/json"}


def stmt_body(text=STATEMENT, src=SRC):
    rid = str(uuid4())
    return {"requestId": rid, "statement": {"subject": "PUBLIC_LAW", "text": text, "contextStem": "도시정비법령상 기본계획", "referenceDate": "2026-11-17"},
            "referenceDate": "2026-11-17",
            "candidates": [{"chunkId": str(uuid4()), "sourceText": src,
                            "metadata": {"articleId": str(uuid4()), "lawVersionId": str(uuid4()), "lawTitle": "도시 및 주거환경정비법",
                                         "articleLabel": "제4조", "effectiveFrom": "2026-07-01", "effectiveTo": None}}]}


def llm_out(b, verdict="INCONSISTENT", quote="특별시장·광역시장 또는 시장은 기본계획에 대하여 5년마다 타당성을 검토하여", **over):
    d = {"verdict": verdict, "summary": "검토 주체가 다르다.",
         "evidences": [{"id": "e1", "chunkId": b["candidates"][0]["chunkId"], "quote": quote}],
         "wrongParts": [{"userText": "국토교통부장관은", "lawExpression": "특별시장·광역시장 또는 시장", "evidenceId": "e1"}] if verdict == "INCONSISTENT" else [],
         "correctedStatement": "특별시장·광역시장 또는 시장은 기본계획에 대하여 5년마다 타당성을 검토하여야 한다." if verdict == "INCONSISTENT" else None,
         "tags": ["ACTOR"] if verdict == "INCONSISTENT" else []}
    d.update(over)
    return json.dumps(d, ensure_ascii=False)


class Base(unittest.TestCase):
    backend = "gemini"

    def setUp(self):
        p = mock.patch.dict(os.environ, {**ENV, "VERIFY_BACKEND": self.backend})
        p.start(); self.addCleanup(p.stop)
        registry.reset(); self.addCleanup(registry.reset)
        self.c = TestClient(app)

    def use(self, *outs):
        self.llm = FakeLlm(*outs)
        registry.set_verifier(V.LlmVerifier(self.llm))

    def post(self, path, b, version=M.CONTRACT_VERSION_02):
        return self.c.post(P + path, content=json.dumps(b, ensure_ascii=False).encode(), headers=hdr(b["requestId"], version))


class VerifyStatementTests(Base):
    def resp(self, b):
        r = self.post("/verify-statement", b)
        self.assertEqual(r.status_code, 200, r.text)
        out = M.VerifyStatementResponse(**r.json()); check_verify_statement(M.VerifyStatementRequest(**b), out)
        return out

    def test_inconsistent_with_located_wrong_span_quote_corrected_and_tag(self):
        b = stmt_body(); self.use(llm_out(b))
        out = self.resp(b)
        self.assertEqual((out.verdict.value, out.reasonCode, out.promptVersion), ("INCONSISTENT", "CONTRADICTED_BY_LAW", S.PROMPT_VERSION))
        w = out.wrongSpans[0]
        self.assertEqual(STATEMENT[w.statementStart:w.statementEnd], "국토교통부장관은")       # 위치는 Python 이 문장에서 찾는다
        self.assertEqual(SRC[out.evidences[0].start:out.evidences[0].end], out.evidences[0].quote)
        self.assertEqual(([t.code for t in out.tags], out.tagSetVersion), (["ACTOR"], "choice-tags-v1"))
        system, user = self.llm.calls[0]
        self.assertEqual(system, S.SYSTEM_PROMPT); self.assertIn("문제 머리말: 도시정비법령상 기본계획", user)

    def test_consistent_clears_wrong_parts_and_tags(self):
        b = stmt_body(text="시장은 기본계획에 대하여 5년마다 타당성을 검토하여야 한다.")
        self.use(llm_out(b, verdict="CONSISTENT", wrongParts=[{"userText": "시장은", "lawExpression": "x", "evidenceId": "e1"}], tags=["ACTOR"]))
        out = self.resp(b)
        self.assertEqual((out.verdict.value, out.wrongSpans, out.correctedStatement, out.tags, out.tagSetVersion), ("CONSISTENT", [], None, [], None))

    def test_unverifiable_quote_becomes_hold_no_verified_quote(self):
        b = stmt_body(); self.use(llm_out(b, quote="조문에 없는 지어낸 문장이다"))
        out = self.resp(b)
        self.assertEqual((out.verdict.value, out.reasonCode, out.wrongSpans), ("HOLD", "NO_VERIFIED_QUOTE", []))

    def test_inconsistent_without_locatable_wrong_part_or_correction_becomes_hold(self):
        for over in ({"wrongParts": [{"userText": "문장에 없는 말", "lawExpression": "x", "evidenceId": "e1"}]},
                     {"wrongParts": [{"userText": "국토교통부장관은", "lawExpression": "x", "evidenceId": "e9"}]},
                     {"correctedStatement": None}, {"correctedStatement": STATEMENT}):
            with self.subTest(over=over):
                b = stmt_body(); self.use(llm_out(b, **over))
                out = self.resp(b)
                self.assertEqual((out.verdict.value, out.reasonCode, out.tags), ("HOLD", "INCONSISTENT_ANALYSIS", []))

    def test_unknown_tags_are_dropped(self):
        b = stmt_body(); self.use(llm_out(b, tags=["MADE_UP", "number", "ACTOR", "TIMING"]))
        self.assertEqual([t.code for t in self.resp(b).tags], ["NUMBER", "ACTOR"])

    def test_bad_json_is_retried_once_then_502(self):
        b = stmt_body(); self.use("not json", llm_out(b))
        self.assertEqual(self.resp(b).verdict.value, "INCONSISTENT"); self.assertEqual(len(self.llm.calls), 2)
        b = stmt_body(); self.use("not json")
        self.assertEqual(self.post("/verify-statement", b).json()["code"], "MODEL_OUTPUT_INVALID")

    def test_needs_new_contract_version_and_validates_input(self):
        b = stmt_body(); self.use(llm_out(b))
        self.assertEqual(self.post("/verify-statement", b, version=M.CONTRACT_VERSION).status_code, 400)
        bad = stmt_body(); bad["statement"]["subject"] = "CIVIL_LAW"
        self.assertEqual(self.post("/verify-statement", bad).status_code, 422)
        bad = stmt_body(); bad["referenceDate"] = "2026-11-18"
        self.assertEqual(self.post("/verify-statement", bad).status_code, 422)
        self.assertEqual(self.post("/verify", {"requestId": str(uuid4())}, version=M.CONTRACT_VERSION_02).status_code, 400)   # 기존 경로는 0.1.0 그대로

    def test_off_backend_is_503(self):
        with mock.patch.dict(os.environ, {"VERIFY_BACKEND": "off"}):
            self.assertEqual(self.post("/verify-statement", stmt_body()).status_code, 503)


class MockStatementTests(Base):
    backend = "mock"

    def test_mock_is_contract_valid(self):
        b = stmt_body(); r = self.post("/verify-statement", b)
        self.assertEqual(r.status_code, 200, r.text)
        out = M.VerifyStatementResponse(**r.json()); check_verify_statement(M.VerifyStatementRequest(**b), out)
        self.assertEqual(out.promptVersion, "mock-statement-0")


class VariationTests(Base):
    def body(self, items):
        return {"requestId": str(uuid4()), "subject": "PUBLIC_LAW", "text": STATEMENT,
                "items": [{"itemId": f"36-{i}", "text": t, "truth": tr} for i, (t, tr) in enumerate(items)]}

    def test_diff_positions_types_tip_and_order(self):
        b = self.body([("시장은 기본계획에 대하여 5년마다 타당성을 검토하여야 한다.", "TRUE"),
                       ("국토교통부장관은 기본계획에 대하여 3년마다 타당성을 검토하여야 한다.", "FALSE"),
                       (STATEMENT, "UNKNOWN")])
        r = self.post("/variation-diff", b)
        self.assertEqual(r.status_code, 200, r.text)
        out = M.VariationDiffResponse(**r.json())
        self.assertEqual([o.itemId for o in out.items], ["36-0", "36-1", "36-2"])
        self.assertEqual([[d.type for d in o.diff] for o in out.items], [["ACTOR"], ["TIMING"], []])
        self.assertEqual(out.items[2].similarity, 1.0)
        for o, it in zip(out.items, b["items"]):
            for d in o.diff:
                self.assertEqual((STATEMENT[d.userStart:d.userEnd], it["text"][d.itemStart:d.itemEnd]), (d.userText, d.itemText))
        self.assertEqual(set(out.tip.categories), {"ACTOR", "TIMING"})
        self.assertIn("맞는 표현(기출 36-0)", out.tip.text)
        self.assertEqual((out.tagSetVersion, out.algorithmVersion), ("choice-tags-v1", "variation-v1"))

    def test_insert_only_difference_has_empty_user_span(self):
        _, d = VD.diff("개발행위를 하려는 자는 허가를 받아야 한다.", "개발행위를 하려는 자는 시장의 허가를 받아야 한다.")
        self.assertEqual([(x.userStart, x.userEnd, x.itemText) for x in d], [(13, 13, "시장의")])

    def test_unrelated_sentences_get_no_diff_and_no_tip(self):
        sim, d = VD.diff(STATEMENT, "개업공인중개사는 중개사무소를 한 곳만 둘 수 있다.")
        self.assertLess(sim, VD.MIN_SIMILARITY); self.assertEqual(d, [])
        out = M.VariationDiffResponse(**self.post("/variation-diff", self.body([("개업공인중개사는 중개사무소를 한 곳만 둘 수 있다.", "TRUE")])).json())
        self.assertIsNone(out.tip)

    def test_identical_items_give_no_tip_and_duplicates_are_rejected(self):
        out = M.VariationDiffResponse(**self.post("/variation-diff", self.body([(STATEMENT, "TRUE")])).json())
        self.assertIsNone(out.tip)
        b = self.body([(STATEMENT, "TRUE"), (STATEMENT, "TRUE")]); b["items"][1]["itemId"] = "36-0"
        self.assertEqual(self.post("/variation-diff", b).status_code, 422)

    def test_classifier_rules(self):
        cases = [("1천분의 9", "1천분의 6", "NUMBER"), ("3개월", "6개월", "TIMING"), ("이내에", "이후에", "TIMING"),
                 ("시·도지사가", "군수가", "ACTOR"), ("하여야", "할 수 있다", "CONDITION"), ("재건축", "재개발", "CONCEPT"),
                 ("인가를", "신고를", "ACTOR")]
        for u, i, want in cases:
            with self.subTest(u=u, i=i):
                self.assertEqual(VD.classify(u, i), want)

    def test_spacing_and_punctuation_noise_is_not_a_difference(self):
        sim, d = VD.diff("분 사무소에서 사용할 인 장의 경우에는 상업등기 규칙에 따라 등록할 수 있다.",
                         "분사무소에서 사용할 인장의 경우에는 「상업등기규칙」에 따라 등록할 수 있다.")
        self.assertEqual((sim, d), (1.0, []))

    def test_mid_word_change_is_shown_as_whole_words(self):
        _, d = VD.diff("등록관청은 3년마다 점검하여야 한다.", "등록관청은 5년마다 점검하여야 한다.")
        self.assertEqual([(x.type, x.userText, x.itemText) for x in d], [("TIMING", "3년마다", "5년마다")])


if __name__ == "__main__":
    unittest.main()
