"""Spring 서버(개발용 인증)에 문제 하나를 등록하고 분석(trace-run)을 실행해 상태를 따라가며 결과를 보여 준다.
영주 님 문서(docs/runbooks/local-development.md, docs/api/external-api.md)의 흐름을 따른다: GET /dev/csrf → POST /questions → POST /questions/{id}/trace-runs
(Idempotency-Key) → GET /jobs/{id} 반복 → GET /trace-runs/{id}/result. 서버는 LAWGROUND_DEV_AUTH_ENABLED=true, OIDC 꺼짐, local 프로필이어야 한다.
※ 응답 필드 이름은 문서 기준이고, 실제 서버와 어긋나면 응답 원문을 그대로 보여 준다(CSRF 토큰은 가린다). 가짜 서버로만 검증했다.
※ 기준일(referenceDate)은 오늘이 기본이다. 우리 법령 버전의 시행일이 2024~2026년이라 시험 당시 날짜로는 적용 법령이 없다.

사용:  python scripts\\run_analysis.py --questions eval\\questions_36A.jsonl --id 제36회-09
"""
import argparse
import json
import re
import sys
import time
import uuid
from datetime import date
from pathlib import Path

import httpx

TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED"}
INCORRECT_PAT = re.compile(r"(틀린|옳지\s*않|아닌\s*것|않은\s*것)")
OCR_PREFIX = re.compile(r"^\s*\(?[0-9@]\)\s*")


class Fail(Exception):
    def __init__(self, message: str, code: int):
        super().__init__(message)
        self.code = code


def question_type(stem: str) -> str:
    return "SELECT_INCORRECT" if INCORRECT_PAT.search(stem) else "SELECT_CORRECT"


def clean_choice(text: str) -> str:
    return OCR_PREFIX.sub("", text).strip()               # OCR 이 선지 번호를 '0)', '(3)', '@)' 처럼 읽은 앞부분을 지운다


def build_question(row: dict, ref_date: str, tag: str = "") -> dict:
    choices = [clean_choice(c) for c in (row.get("choices") or [])]
    if len(choices) != 5 or not all(choices):
        raise Fail("선지가 5개가 아닌 문제(합답형 등)는 등록할 수 없다", 2)
    if not isinstance(row.get("answer"), int) or not 1 <= row["answer"] <= 5:
        raise Fail("정답 번호가 없는 문제다", 2)
    stem = row["stem"].strip()
    return {"subject": "BROKER_LAW", "stem": stem + (f" [시험 {tag}]" if tag else ""), "questionType": question_type(stem),
            "choices": [{"number": i + 1, "text": c} for i, c in enumerate(choices)], "providedAnswerNumber": row["answer"], "referenceDate": ref_date}


class Api:
    def __init__(self, base: str, transport=None, timeout: float = 30.0):
        self.http = httpx.Client(base_url=base.rstrip("/"), timeout=timeout, transport=transport)   # 쿠키(세션)는 자동으로 유지된다
        self.csrf: dict[str, str] = {}

    def _fail(self, r: httpx.Response, what: str) -> Fail:
        body = r.text[:600]
        for v in self.csrf.values():
            body = body.replace(v, "***")
        hint = " (같은 개발 회원이 같은 문제를 다시 등록했다. --tag 를 붙이면 문구가 달라진다)" if r.status_code == 409 and "DUPLICATE" in r.text else ""
        return Fail(f"{what} 실패: HTTP {r.status_code}{hint}\n{body}", 3)

    def get_csrf(self):
        r = self.http.get("/dev/csrf")
        if r.status_code != 200:
            raise self._fail(r, "개발용 CSRF 발급")
        d = r.json()
        self.csrf = {d["headerName"]: d["token"]}

    def post(self, path: str, body: dict, extra: dict | None = None, what: str = "요청") -> httpx.Response:
        r = self.http.post(path, content=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                           headers={"content-type": "application/json; charset=utf-8", **self.csrf, **(extra or {})})
        if r.status_code >= 400:
            raise self._fail(r, what)
        return r

    def get(self, path: str, what: str) -> dict:
        r = self.http.get(path)
        if r.status_code != 200:
            raise self._fail(r, what)
        return r.json()


def run(api: Api, question: dict, *, timeout: float, poll: float, sleeper=time.sleep, clock=time.monotonic, log=print) -> dict:
    api.get_csrf()
    created = api.post("/questions", question, what="문제 등록").json()
    qid = created["id"]
    log(f"문제 등록: {qid}")
    run_info = api.post(f"/questions/{qid}/trace-runs", {"questionRevision": created.get("revision", 1)},
                        {"Idempotency-Key": str(uuid.uuid4())}, what="분석 접수").json()
    job_id, run_id = run_info["jobId"], run_info["runId"]
    log(f"분석 접수: run {run_id} / job {job_id}")
    started, last = clock(), None
    while True:
        job = api.get(f"/jobs/{job_id}", "작업 상태 조회")
        state = (job.get("status"), job.get("stage"), job.get("stageAttempt"))
        if state != last:
            log(f"  [{clock() - started:5.0f}초] 상태 {job.get('status')} | 단계 {job.get('stage')} | 시도 {job.get('stageAttempt')}"
                + (f" | 오류 {job['errorCode']}" if job.get("errorCode") else ""))
            last = state
        if job.get("status") in TERMINAL:
            break
        if clock() - started > timeout:
            raise Fail(f"{timeout:.0f}초 안에 끝나지 않았다(마지막 상태 {job.get('status')}/{job.get('stage')})", 5)
        sleeper(poll)
    if job.get("status") != "SUCCEEDED":
        raise Fail(f"분석이 {job.get('status')}로 끝났다: 오류 {job.get('errorCode')}, 단계 {job.get('stage')}", 4)
    return api.get(f"/trace-runs/{run_id}/result", "결과 조회")


def main(argv=None):
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8")
        except Exception:
            pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--questions", required=True); ap.add_argument("--id", required=True)
    ap.add_argument("--base", default="http://127.0.0.1:8081/api/v1"); ap.add_argument("--ref-date", default=date.today().isoformat())
    ap.add_argument("--tag", default="", help="같은 문제를 다시 등록할 때 문구를 달리하는 꼬리표(409 중복 방지)")
    ap.add_argument("--timeout", type=float, default=600); ap.add_argument("--poll", type=float, default=3); ap.add_argument("--out", default=None)
    a = ap.parse_args(argv)
    try:
        rows = [json.loads(l) for l in Path(a.questions).read_text(encoding="utf-8").splitlines() if l.strip()]
        row = next((r for r in rows if r["id"] == a.id), None)
        if row is None:
            raise Fail(f"문제 id 를 찾지 못했다: {a.id}", 2)
        question = build_question(row, a.ref_date, a.tag)
        print(f"문제: {a.id} | 유형 {question['questionType']} | 정답 {question['providedAnswerNumber']}번 | 기준일 {a.ref_date}")
        result = run(Api(a.base), question, timeout=a.timeout, poll=a.poll)
    except Fail as e:
        print(f"오류: {e}")
        raise SystemExit(e.code)
    except httpx.HTTPError as e:
        print(f"서버에 연결하지 못했다({type(e).__name__}). Spring 이 8081 에서 떠 있는지 확인한다")
        raise SystemExit(3)
    text = json.dumps(result, ensure_ascii=False, indent=1)
    print("\n=== 결과(앞 6000자) ===\n" + text[:6000])
    if a.out:
        Path(a.out).parent.mkdir(parents=True, exist_ok=True)
        Path(a.out).write_text(text, encoding="utf-8")
        print(f"\n전체 저장: {a.out}")


if __name__ == "__main__":
    main()
