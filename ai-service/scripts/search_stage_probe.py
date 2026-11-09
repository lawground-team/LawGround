"""Spring SEARCH 단계의 AI 호출(임베딩 1회 → 리랭크 후보 5개)을 같은 형식으로 재현해 걸리는 시간을 잰다.
Python 서버(8001)만 켜져 있으면 된다. Spring·DB·Docker·Gemini 는 쓰지 않는다(비용 없음).

기준(Spring 소스에서 확인): 질의 = "subject/questionType/question/choice[n]/providedAnswerNumber" 줄, 후보 기본 5개, 후보 본문 = 조문 원문 전체,
제한 시간 임베딩 30초·리랭크 60초(application.yml).
※ 후보는 실제 Spring 의 Hybrid(RRF) 상위 5개가 아니라 BM25 상위 5개로 근사한다(조문 길이가 비슷한 수준인지 보는 용도).
사용:  python scripts\\search_stage_probe.py --corpus-dir <조문 jsonl 폴더> --questions eval\\questions_36A.jsonl --id 제36회-09 --env ..\\.env [--each]
"""
import argparse
import json
import sys
import time
import uuid
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
LIMITS = {"embed": 30.0, "rerank": 60.0}                 # Spring 제한(초)


def read_env(path) -> dict:
    out = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def query_text(row: dict) -> str:
    """Spring LiveRunPlan.queryText 와 같은 형식."""
    q = "subject: BROKER_LAW\nquestionType: SELECT_CORRECT\nquestion: " + row["stem"].strip()
    for i, c in enumerate(row["choices"], 1):
        q += f"\nchoice[{i}]: {c.strip()}"
    return q + f"\nprovidedAnswerNumber: {row['answer']}"


def candidates(corpus_dir, query: str, n: int = 5) -> list[dict]:
    from app.evaluation import bm25_scores, load_corpus, rank_of
    from app.services.tokenizer import tokenize
    chunks = load_corpus(sorted(Path(corpus_dir).glob("*.jsonl")), strip_notes=False)
    scores = bm25_scores(tokenize(query), [tokenize(c["text"]) for c in chunks])
    return [chunks[i] for i in rank_of(scores)[:n]]


class Probe:
    def __init__(self, base: str, token: str, emb_rev: str, rr_rev: str, transport=None):
        self.http = httpx.Client(base_url=base.rstrip("/"), timeout=900.0, transport=transport,
                                 headers={"X-Internal-Token": token, "X-Contract-Version": "0.1.0-draft"})
        self.emb_rev, self.rr_rev = emb_rev, rr_rev

    def call(self, path: str, body: dict) -> tuple[float, int, str]:
        t = time.perf_counter()
        r = self.http.post(path, json=body, headers={"X-Request-Id": body["requestId"]})
        return time.perf_counter() - t, r.status_code, r.text[:300]

    def embed(self, query: str):
        rid = str(uuid.uuid4())
        return self.call("/embed", {"requestId": rid, "texts": [{"id": str(uuid.uuid4()), "text": query}], "modelRevision": self.emb_rev})

    def rerank(self, query: str, cands: list[dict]):
        return self.call("/rerank", {"requestId": str(uuid.uuid4()), "query": query, "topK": min(5, len(cands)),
                                     "candidates": [{"chunkId": str(uuid.uuid4()), "text": c["text"]} for c in cands]})


def verdict(name: str, sec: float, code: int) -> str:
    over = sec > LIMITS[name]
    return f"{name:6s} {sec:7.1f}초  HTTP {code}  " + (f"← Spring 제한 {LIMITS[name]:.0f}초 초과(여기서 AI_UNAVAILABLE)" if over else "제한 안")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus-dir", required=True)
    ap.add_argument("--questions", required=True)
    ap.add_argument("--id", required=True)
    ap.add_argument("--env", required=True)
    ap.add_argument("--base", default="http://127.0.0.1:8001/internal/v1")
    ap.add_argument("--each", action="store_true", help="후보를 하나씩 따로도 잰다")
    a = ap.parse_args(argv)
    env = read_env(a.env)
    need = ["AI_SERVICE_API_KEY", "AI_EMBEDDING_MODEL_REVISION", "AI_RERANKER_MODEL_REVISION"]
    if [k for k in need if not env.get(k)]:
        print("환경 파일에 없는 항목:", [k for k in need if not env.get(k)])
        return 2
    rows = [json.loads(x) for x in Path(a.questions).read_text(encoding="utf-8").splitlines() if x.strip()]
    row = next((r for r in rows if r["id"] == a.id), None)
    if row is None:
        print("문제를 찾지 못했다:", a.id)
        return 2
    q = query_text(row)
    cands = candidates(a.corpus_dir, q)
    print(f"질의 {len(q)}자 | 후보 {len(cands)}개: " + ", ".join(f"{c['id']}({len(c['text'])}자)" for c in cands))
    p = Probe(a.base, env["AI_SERVICE_API_KEY"], env["AI_EMBEDDING_MODEL_REVISION"], env["AI_RERANKER_MODEL_REVISION"])
    sec, code, body = p.embed(q)
    print(verdict("embed", sec, code))
    if code != 200:
        print("  응답:", body)
    sec, code, body = p.rerank(q, cands)
    print(verdict("rerank", sec, code))
    if code != 200:
        print("  응답:", body)
    if a.each:
        for c in cands:
            sec, code, body = p.rerank(q, [c])
            print(f"  단독 {c['id']} ({len(c['text'])}자): {sec:.1f}초 HTTP {code}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
