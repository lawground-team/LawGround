"""run_analysis.py 검증(가짜 서버). 영주 님 문서의 API 모양을 흉내 낸다. 실제 서버와의 연결은 검증하지 못했다."""
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import run_analysis as R  # noqa: E402

TOKEN = "CSRF-TOKEN-SECRET-123"
ROW = {"id": "제36회-09", "stem": "공인중개사법령상 휴업에 관한 설명으로 옳은 것은?", "answer": 1,
       "choices": ["0) 첫째 선지", "(2) 둘째 선지", "@) 셋째 선지", "(8) 넷째 선지", "5) 다섯째 선지"]}


class FakeServer:
    def __init__(self, job_states=None, create_status=201, csrf_status=200, run_status=202):
        self.job_states = list(job_states or [("QUEUED", "SEARCH"), ("RUNNING", "VERIFY"), ("SUCCEEDED", "PERSIST")])
        self.create_status, self.csrf_status, self.run_status = create_status, csrf_status, run_status
        self.seen = []

    def handler(self, req: httpx.Request) -> httpx.Response:
        path = req.url.path.replace("/api/v1", "")
        self.seen.append((req.method, path, dict(req.headers)))
        if (req.method, path) == ("GET", "/dev/csrf"):
            return httpx.Response(self.csrf_status, json={"headerName": "X-CSRF-TOKEN", "token": TOKEN}, headers={"set-cookie": "SESSION=abc; Path=/"})
        if req.method == "POST" and req.headers.get("x-csrf-token") != TOKEN:
            return httpx.Response(403, json={"code": "CSRF_REQUIRED"})
        if (req.method, path) == ("POST", "/questions"):
            self.question = json.loads(req.content.decode("utf-8"))
            if self.create_status == 409:
                return httpx.Response(409, json={"code": "DUPLICATE_QUESTION", "echo": TOKEN})
            return httpx.Response(self.create_status, json={"id": "q-1", "revision": 1})
        if (req.method, path) == ("POST", "/questions/q-1/trace-runs"):
            self.run_body, self.run_headers = json.loads(req.content), dict(req.headers)
            return httpx.Response(self.run_status, json={"runId": "r-1", "jobId": "j-1", "statusUrl": "/jobs/j-1", "resultUrl": "/trace-runs/r-1/result"})
        if (req.method, path) == ("GET", "/jobs/j-1"):
            st, stage = self.job_states.pop(0) if len(self.job_states) > 1 else self.job_states[0]
            return httpx.Response(200, json={"id": "j-1", "runId": "r-1", "status": st, "stage": stage, "stageAttempt": 1,
                                             "errorCode": "VERIFIER_NOT_CONFIGURED" if st == "FAILED" else None})
        if (req.method, path) == ("GET", "/trace-runs/r-1/result"):
            return httpx.Response(200, json={"runId": "r-1", "verdict": "SUPPORTED", "evidences": [{"quote": "근거"}]})
        return httpx.Response(404, json={"code": "NOT_FOUND"})


def api(server):
    return R.Api("http://127.0.0.1:8081/api/v1", transport=httpx.MockTransport(server.handler))


class Clock:
    def __init__(self): self.t = 0.0
    def __call__(self): return self.t


class BuildTests(unittest.TestCase):
    def test_ocr_prefixes_are_stripped_and_type_detected(self):
        q = R.build_question(ROW, "2026-10-27")
        self.assertEqual([c["text"] for c in q["choices"]], ["첫째 선지", "둘째 선지", "셋째 선지", "넷째 선지", "다섯째 선지"])
        self.assertEqual((q["questionType"], q["providedAnswerNumber"], q["referenceDate"], q["subject"]), ("SELECT_CORRECT", 1, "2026-10-27", "BROKER_LAW"))
        self.assertEqual([c["number"] for c in q["choices"]], [1, 2, 3, 4, 5])
        self.assertEqual(R.question_type("틀린 것은?"), "SELECT_INCORRECT"); self.assertEqual(R.question_type("옳지 않은 것은?"), "SELECT_INCORRECT")
        self.assertEqual(R.question_type("옳은 것을 모두 고른 것은?"), "SELECT_CORRECT")

    def test_tag_changes_the_stem_but_not_the_detected_type(self):
        q = R.build_question({**ROW, "stem": "틀린 것은?"}, "2026-10-27", tag="1")
        self.assertTrue(q["stem"].endswith("[시험 1]")); self.assertEqual(q["questionType"], "SELECT_INCORRECT")

    def test_unusable_rows_are_refused(self):
        for bad in ({**ROW, "choices": []}, {**ROW, "choices": ROW["choices"][:4]}, {**ROW, "choices": ["", "a", "b", "c", "d"]}, {**ROW, "answer": None}, {**ROW, "answer": 6}):
            with self.assertRaises(R.Fail) as cm:
                R.build_question(bad, "2026-10-27")
            self.assertEqual(cm.exception.code, 2)


class FlowTests(unittest.TestCase):
    def run_flow(self, server, **kw):
        logs = []
        clock = Clock()
        res = R.run(api(server), R.build_question(ROW, "2026-10-27"), timeout=kw.get("timeout", 600), poll=1, sleeper=lambda s: setattr(clock, "t", clock.t + s), clock=clock, log=logs.append)
        return res, logs

    def test_full_flow_sends_csrf_cookie_idempotency_key_and_follows_job_to_result(self):
        s = FakeServer()
        res, logs = self.run_flow(s)
        self.assertEqual(res["verdict"], "SUPPORTED")
        calls = [(m, p) for m, p, _ in s.seen]
        self.assertEqual(calls[:3], [("GET", "/dev/csrf"), ("POST", "/questions"), ("POST", "/questions/q-1/trace-runs")])
        self.assertEqual(s.run_body, {"questionRevision": 1})
        self.assertRegex(s.run_headers["idempotency-key"], r"^[0-9a-f-]{36}$")           # UUID 멱등 키
        self.assertEqual(s.run_headers["x-csrf-token"], TOKEN)
        self.assertIn("session=abc", s.run_headers.get("cookie", "").lower())            # 세션 쿠키가 이어진다
        self.assertEqual([p for m, p, _ in s.seen if p.startswith("/jobs")], ["/jobs/j-1"] * 3)
        self.assertTrue(any("RUNNING" in l and "VERIFY" in l for l in logs))             # 단계가 바뀔 때마다 보인다
        self.assertEqual(s.question["choices"][0]["text"], "첫째 선지")

    def test_job_failure_reports_the_error_code_and_stage_with_exit_code_4(self):
        s = FakeServer(job_states=[("RUNNING", "VERIFY"), ("FAILED", "VERIFY")])
        with self.assertRaises(R.Fail) as cm:
            self.run_flow(s)
        self.assertEqual(cm.exception.code, 4); self.assertIn("VERIFIER_NOT_CONFIGURED", str(cm.exception)); self.assertIn("VERIFY", str(cm.exception))
        self.assertNotIn("/trace-runs/r-1/result", [p for _, p, _ in s.seen])           # 실패한 실행의 결과는 가져오지 않는다

    def test_timeout_stops_with_exit_code_5_and_last_state(self):
        s = FakeServer(job_states=[("RUNNING", "RERANK")])
        with self.assertRaises(R.Fail) as cm:
            self.run_flow(s, timeout=10)
        self.assertEqual(cm.exception.code, 5); self.assertIn("RERANK", str(cm.exception))

    def test_duplicate_question_gives_a_hint_and_never_prints_the_csrf_token(self):
        with self.assertRaises(R.Fail) as cm:
            self.run_flow(FakeServer(create_status=409))
        msg = str(cm.exception)
        self.assertEqual(cm.exception.code, 3); self.assertIn("--tag", msg); self.assertNotIn(TOKEN, msg); self.assertIn("***", msg)

    def test_missing_csrf_is_rejected_by_the_server_and_reported(self):
        s = FakeServer(csrf_status=200)
        a = api(s)
        with self.assertRaises(R.Fail) as cm:
            a.post("/questions", {"x": 1}, what="문제 등록")                            # get_csrf 를 건너뛴 요청
        self.assertIn("403", str(cm.exception))

    def test_csrf_failure_and_run_rejection_are_reported(self):
        with self.assertRaises(R.Fail) as cm:
            self.run_flow(FakeServer(csrf_status=404))
        self.assertIn("개발용 CSRF", str(cm.exception))                                  # 개발용 인증이 꺼져 있을 때의 단서
        with self.assertRaises(R.Fail) as cm:
            self.run_flow(FakeServer(run_status=409))
        self.assertIn("분석 접수 실패", str(cm.exception))


class CliTests(unittest.TestCase):
    def test_main_prints_result_and_saves_it(self):
        s = FakeServer()
        with tempfile.TemporaryDirectory() as t:
            q = Path(t) / "q.jsonl"; q.write_text(json.dumps(ROW, ensure_ascii=False), encoding="utf-8")
            out = Path(t) / "out.json"
            orig = R.Api
            R.Api = lambda base, **kw: orig(base, transport=httpx.MockTransport(s.handler))
            try:
                buf = io.StringIO()
                with contextlib.redirect_stdout(buf):
                    R.main(["--questions", str(q), "--id", "제36회-09", "--ref-date", "2026-10-27", "--poll", "0", "--out", str(out)])
            finally:
                R.Api = orig
            text = buf.getvalue()
            self.assertIn("기준일 2026-10-27", text); self.assertIn("SUPPORTED", text); self.assertNotIn(TOKEN, text)
            self.assertEqual(json.loads(out.read_text(encoding="utf-8"))["runId"], "r-1")

    def test_unknown_question_id_and_connection_error_have_clear_exit_codes(self):
        with tempfile.TemporaryDirectory() as t:
            q = Path(t) / "q.jsonl"; q.write_text(json.dumps(ROW, ensure_ascii=False), encoding="utf-8")
            with self.assertRaises(SystemExit) as cm, contextlib.redirect_stdout(io.StringIO()) as buf:
                R.main(["--questions", str(q), "--id", "없는-id"])
            self.assertEqual(cm.exception.code, 2); self.assertIn("찾지 못했다", buf.getvalue())
            orig = R.Api
            R.Api = lambda base, **kw: orig(base, transport=httpx.MockTransport(lambda req: (_ for _ in ()).throw(httpx.ConnectError("refused"))))
            try:
                with self.assertRaises(SystemExit) as cm, contextlib.redirect_stdout(io.StringIO()) as buf:
                    R.main(["--questions", str(q), "--id", "제36회-09"])
            finally:
                R.Api = orig
            self.assertEqual(cm.exception.code, 3); self.assertIn("8081", buf.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
