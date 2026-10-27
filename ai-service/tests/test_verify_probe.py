"""verify_probe.py 검증(합성 코퍼스·가짜 LLM). 긍정·부정 모드, 오탐 집계, 건너뛰기, 한도 재시도, 키 비노출."""
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts")); sys.path.insert(0, str(ROOT))
import verify_probe as P  # noqa: E402
from app import evaluation as E  # noqa: E402
from app.services import tokenizer, verifier as V  # noqa: E402

KEY = "AIzaSy-PROBE-SECRET-0123456789"
SENT = "중개보수의 지급시기는 거래대금 지급이 완료된 날이다."


def corpus(tmp: Path):
    recs = [{"law_name": "공인중개사법", "article_label": f"제{i}조", "title": f"제목{i}", "kind": "조문",
             "text": f"제{i}조(제목{i}) " + (SENT if i == 1 else f"제{i}조의 다른 내용을 정한다 항목{i}.")} for i in range(1, 9)]
    (tmp / "a.jsonl").write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in recs), encoding="utf-8")
    return sorted(tmp.glob("*.jsonl"))


def rows():
    base = {"stem": "중개보수의 지급시기에 관한 설명으로 옳은 것은?", "choices": [f"선지{i}" for i in range(1, 6)], "out_of_scope": False}
    return [{**base, "id": "q-1", "answer": 2, "gold": ["공인중개사법 제1조"]},
            {**base, "id": "q-2", "answer": 4, "gold": ["공인중개사법 제1조"], "stem": "다음 설명 중 옳지 않은 것은?"},
            {**base, "id": "q-oos", "answer": 1, "gold": ["공인중개사법 제1조"], "out_of_scope": True},
            {**base, "id": "q-combo", "answer": 1, "gold": ["공인중개사법 제1조"], "choices": []},
            {**base, "id": "q-noans", "answer": None, "gold": ["공인중개사법 제1조"]},
            {**base, "id": "q-nogold", "answer": 3, "gold": []}]


class Judge:
    """제공 정답 번호가 시험지 정답이면 SUPPORTED, 아니면 REJECTED 로 답하는 가짜 LLM. 정답 문장은 후보 글에서 그대로 인용한다."""
    name, revision = "probe-fake-model", "probe-fake-model-1"

    def __init__(self, answers: dict, always=None, fail=None, consistent=True):
        self.answers, self.always, self.fail, self.calls, self.consistent = answers, always, list(fail or []), [], consistent

    def generate(self, system, user):
        self.calls.append(user)
        if self.fail:
            err = self.fail.pop(0)
            if err:
                raise err
        provided = int(re.search(r"제공 정답: (\d)번", user).group(1))
        stem = re.search(r"지문: (.*)", user).group(1)
        truth = self.answers[stem]
        is_correct = provided == truth
        verdict = self.always or ("SUPPORTED" if is_correct else "REJECTED")
        chunk = re.search(r"<조문\d+ chunkId=([0-9a-f-]{36}) \| 공인중개사법 제1조 [^>]*>", user).group(1)
        qtype = re.search(r"유형: (\w+)", user).group(1)
        good = "SUPPORTS" if qtype == "SELECT_CORRECT" else "CONTRADICTS"
        bad = "CONTRADICTS" if good == "SUPPORTS" else "SUPPORTS"
        rel = {i: bad for i in range(1, 6)}
        rel[provided] = good if is_correct else bad
        if self.always == "SUPPORTED" and self.consistent:
            rel[provided] = good                         # 일관되게 틀리는 모델: 판정도 선지 분석도 '정답이 맞다'고 말한다
        return json.dumps({"verdict": verdict, "summary": "판정", "evidences": [{"id": "e1", "chunkId": chunk, "quote": SENT}],
                           "choices": [{"number": i, "relation": rel[i], "explanation": "설명", "correctedText": "정정" if rel[i] == "CONTRADICTS" else None,
                                        "evidenceIds": ["e1"]} for i in range(1, 6)]}, ensure_ascii=False)


class Base(unittest.TestCase):
    def setUp(self):
        self._t = tempfile.TemporaryDirectory(); self.addCleanup(self._t.cleanup)
        self.tmp = Path(self._t.name)
        self.paths = corpus(self.tmp)
        self.chunks = E.load_corpus(self.paths)
        self.raw = P.raw_corpus(self.paths)
        self.ret = E.Retriever(self.chunks, tokenizer.tokenize)
        self.truth = {r["stem"]: r["answer"] for r in rows() if r["answer"]}
        self.truth["중개보수의 지급시기에 관한 설명으로 옳은 것은?"] = 2

    def probe(self, judge, **kw):
        self.sleeps = []
        v = V.LlmVerifier(judge)
        return P.run_probe(rows(), self.chunks, self.raw, self.ret, v, sleeper=self.sleeps.append, **kw), judge


class ModeTests(Base):
    def test_positive_mode_expects_supported_with_gold_in_candidates_and_skips_unusable_rows(self):
        judge = Judge({"중개보수의 지급시기에 관한 설명으로 옳은 것은?": 2, "다음 설명 중 옳지 않은 것은?": 4})
        (res, skipped), _ = self.probe(judge, limit=10)
        self.assertEqual([r["id"] for r in res], ["q-1", "q-2"])
        self.assertEqual({s["id"]: s["reason"] for s in skipped},                                      # 건너뛴 문제와 사유를 함께 확인
                         {"q-oos": "범위 밖", "q-combo": "선지 5개가 없음(합답형 등)", "q-noans": "정답 번호 없음", "q-nogold": "정답 라벨 없음"})
        self.assertTrue(all(r["verdict"] == "SUPPORTED" and r["gold_in_candidates"] and r["evidence_in_gold"] and r["choices_present"] for r in res))
        s = P.summarize(res, skipped)
        self.assertEqual((s["n"], s["SUPPORTED"], s["correct_rate"], s["skipped"]), (2, 2, 1.0, 4))
        self.assertNotIn("false_positive_rate", s)

    def test_candidates_are_five_unique_include_gold_and_not_always_gold_first(self):
        import hashlib
        positions = set()
        for g in (c["id"] for c in self.chunks):                                   # 어떤 조문이 정답이어도
            row = {"stem": "지급시기 문제", "choices": ["가", "나"], "gold": [g]}
            ids = P.candidate_ids(row, self.chunks, self.ret)
            self.assertEqual((len(ids), len(set(ids)), g in ids), (5, 5, True))
            self.assertEqual(ids, sorted(ids, key=lambda c: hashlib.sha1(c.encode("utf-8")).hexdigest()))   # 순서는 id 해시 순서
            self.assertEqual(ids, P.candidate_ids(row, self.chunks, self.ret))     # 같은 문제는 항상 같은 순서
            positions.add(ids.index(g))
        self.assertGreater(len(positions), 1)                                       # 정답이 항상 같은 자리(맨 앞 등)에 오지 않는다

    def test_negative_mode_flips_the_answer_and_counts_false_positives(self):
        truth = {"중개보수의 지급시기에 관한 설명으로 옳은 것은?": 2, "다음 설명 중 옳지 않은 것은?": 4}
        (res, sk), judge = self.probe(Judge(truth), negative=True, limit=10)
        self.assertTrue(all(r["provided"] != r["answer"] and r["verdict"] == "REJECTED" for r in res))
        s = P.summarize(res, sk)
        self.assertEqual((s["mode"], s["correct_rate"], s["false_positive_rate"]), ("negative", 1.0, 0.0))
        (res2, sk2), _ = self.probe(Judge(truth, always="SUPPORTED"), negative=True, limit=10)    # 무조건 SUPPORTED 라고 하는 나쁜 모델
        s2 = P.summarize(res2, sk2)
        self.assertEqual((s2["correct_rate"], s2["false_positive_rate"]), (0.0, 1.0))               # 오탐 100% 로 드러난다

    def test_contradictory_claim_is_held_by_the_verifier_and_not_counted_as_false_positive(self):
        truth = {"중개보수의 지급시기에 관한 설명으로 옳은 것은?": 2, "다음 설명 중 옳지 않은 것은?": 4}
        (res, sk), _ = self.probe(Judge(truth, always="SUPPORTED", consistent=False), negative=True, limit=10)
        self.assertTrue(all(r["verdict"] == "HOLD" and r["reasonCode"] == "INCONSISTENT_ANALYSIS" for r in res))   # 판정과 선지 분석이 모순 → 보류
        s = P.summarize(res, sk)
        self.assertEqual((s["correct_rate"], s["false_positive_rate"], s["HOLD"]), (0.0, 0.0, 2))

    def test_limit_and_sleep_between_calls(self):
        judge = Judge({"중개보수의 지급시기에 관한 설명으로 옳은 것은?": 2, "다음 설명 중 옳지 않은 것은?": 4})
        (res, _), _ = self.probe(judge, limit=1, sleep=3.5)
        self.assertEqual(len(res), 1); self.assertEqual(self.sleeps, [3.5])

    def test_question_type_detection(self):
        for stem, want in (("옳은 것은?", "SELECT_CORRECT"), ("옳은 것을 모두 고른 것은?", "SELECT_CORRECT"), ("틀린 것은?", "SELECT_INCORRECT"),
                           ("옳지 않은 것은?", "SELECT_INCORRECT"), ("처해질 수 있는 자가 아닌 것은?", "SELECT_INCORRECT"), ("해당하지 않은 것은?", "SELECT_INCORRECT"),
                           ("옳은 것은?(단, 다른 사정은 고려하지 않음)", "SELECT_CORRECT")):
            self.assertEqual(P.question_type(stem), want, stem)


class RobustnessTests(Base):
    def test_rate_limit_waits_the_advised_time_and_retries_once(self):
        truth = {"중개보수의 지급시기에 관한 설명으로 옳은 것은?": 2, "다음 설명 중 옳지 않은 것은?": 4}
        judge = Judge(truth, fail=[V.LlmError("rate_limited", "한도", retryable=True, retry_after=9), None])
        (res, _), _ = self.probe(judge, limit=1)
        self.assertEqual((res[0]["verdict"], res[0]["error"]), ("SUPPORTED", None)); self.assertEqual(self.sleeps, [9])

    def test_persistent_errors_are_recorded_and_the_run_continues(self):
        truth = {"중개보수의 지급시기에 관한 설명으로 옳은 것은?": 2, "다음 설명 중 옳지 않은 것은?": 4}
        judge = Judge(truth, fail=[V.LlmError("upstream", "서버 오류", retryable=True), None])
        (res, sk), _ = self.probe(judge, limit=2)
        self.assertEqual((res[0]["error"], res[1]["verdict"]), ("upstream", "SUPPORTED"))
        self.assertEqual(res[0]["error_message"], "서버 오류"); self.assertIsNone(res[1]["error_message"])        # 오류 사유가 결과에 남는다
        s = P.summarize(res, sk)
        self.assertEqual((s["errors"], s["n"], s["correct_rate"]), (1, 2, 0.5))             # 오류는 정답으로 세지 않는다

    def test_rate_limit_twice_keeps_the_reason_of_the_second_failure(self):
        truth = {"중개보수의 지급시기에 관한 설명으로 옳은 것은?": 2, "다음 설명 중 옳지 않은 것은?": 4}
        judge = Judge(truth, fail=[V.LlmError("rate_limited", "첫 한도", retryable=True, retry_after=5),
                                   V.LlmError("rate_limited", "두 번째 한도: 분당 요청 초과", retryable=True, retry_after=5)])
        (res, _), _ = self.probe(judge, limit=1)
        self.assertEqual((res[0]["error"], res[0]["error_message"]), ("rate_limited", "두 번째 한도: 분당 요청 초과"))
        self.assertEqual(self.sleeps, [5])                                       # 한 번만 쉬고 한 번만 다시 시도했다
        self.assertEqual(len(judge.calls), 2)                                    # 최초 1회 + 다시 시도 1회, 딱 두 번만 불렀다

    def test_output_file_has_neither_key_nor_prompts(self):
        corpus_dir = self.tmp / "law"; corpus_dir.mkdir()
        (corpus_dir / "a.jsonl").write_text((self.tmp / "a.jsonl").read_text(encoding="utf-8"), encoding="utf-8")
        gold = self.tmp / "gold.jsonl"
        gold.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows() if r["id"] in ("q-1", "q-2")), encoding="utf-8")
        out = self.tmp / "out.json"
        env = {**os.environ, "LLM_API_KEY": KEY, "LLM_MODEL": "probe-model-1", "PYTHONIOENCODING": "cp949"}
        r = subprocess.run([sys.executable, str(ROOT / "scripts" / "verify_probe.py"), "--corpus-dir", str(corpus_dir), "--gold", str(gold), "--out", str(out), "--sleep", "0"],
                           capture_output=True, text=True, encoding="utf-8", env=env, timeout=60)
        # 네트워크가 없으므로 호출은 오류로 기록될 뿐이다. 그래도 도구는 끝까지 돌고 키를 남기지 않아야 한다.
        self.assertEqual(r.returncode, 0, r.stderr)
        text = out.read_text(encoding="utf-8")
        self.assertNotIn(KEY, text + r.stdout + r.stderr)
        for secret_like in ("systemInstruction", "SYSTEM", "[규칙]"):
            self.assertNotIn(secret_like, text)
        data = json.loads(text)
        self.assertEqual(data["model"], "verify:probe-model-1"); self.assertEqual(data["summary"]["n"], 2)

    def test_missing_key_stops_with_a_clear_message(self):
        env = {k: v for k, v in os.environ.items() if k not in ("LLM_API_KEY", "LLM_MODEL")}
        r = subprocess.run([sys.executable, str(ROOT / "scripts" / "verify_probe.py"), "--corpus-dir", str(self.tmp), "--gold", str(self.tmp / "x")],
                           capture_output=True, text=True, encoding="utf-8", env={**env, "PYTHONIOENCODING": "cp949"}, timeout=30)
        self.assertNotEqual(r.returncode, 0); self.assertIn("LLM_API_KEY", r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
