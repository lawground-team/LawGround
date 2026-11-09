"""LLM 판정(/verify)을 우리 라벨 문제로 직접 재 보는 시험 도구. Spring·서버 없이 검증기를 직접 부른다.

  positive : 시험지 정답 번호를 넣는다 → SUPPORTED 가 나와야 한다. REJECTED 는 잘못된 기각, HOLD 는 보류.
  --negative: 일부러 틀린 번호를 넣는다 → REJECTED(또는 HOLD)여야 한다. SUPPORTED 가 나오면 오탐이다.
후보 조문은 정답 라벨 조문 + BM25 상위로 5개를 채운다(서비스 검색 결과와 비슷한 상황). 정답이 맨 앞에 오는 편향을 없애려고 순서는 id 해시로 섞는다.
※ 후보에 정답 조문을 넣어 주므로 '검색'이 아니라 '판정 능력'만 잰다. 검색과 합친 서비스 정확도가 아니다.
※ 문제 수가 적으면(20~30) 구간이 넓다. 공급자·모델 비교는 같은 문제·같은 조건으로 하고, 한두 문제 차이는 우열이 아니다.
키는 환경변수(LLM_API_KEY, LLM_MODEL)에서만 읽고 결과 파일·화면에 쓰지 않는다.

사용 (ai-service 폴더, (.venv) 켠 상태):
  set LLM_API_KEY=...   set LLM_MODEL=...
  python scripts\\verify_probe.py --corpus-dir <법령폴더> --gold eval\\gold.jsonl --limit 10 --out eval\\probe_positive.json
  python scripts\\verify_probe.py --corpus-dir <법령폴더> --gold eval\\gold.jsonl --limit 10 --negative --out eval\\probe_negative.json
"""
import argparse
import hashlib
import json
import os
import re
import sys
import time
from datetime import date
from pathlib import Path
from uuid import NAMESPACE_URL, uuid4, uuid5

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")
    except Exception:
        pass
from app import evaluation as E  # noqa: E402
from app.contract import models as M  # noqa: E402
from app.services import tokenizer, verifier as V  # noqa: E402

INCORRECT_PAT = re.compile(r"(틀린|옳지\s*않|아닌\s*것|않은\s*것)")


def question_type(stem: str) -> str:
    return "SELECT_INCORRECT" if INCORRECT_PAT.search(stem) else "SELECT_CORRECT"


def raw_corpus(paths) -> dict[str, dict]:
    """평가 코퍼스와 같은 id 로, 가공하지 않은 원문(sourceText)을 돌려준다."""
    out = {}
    for p in paths:
        for line in Path(p).read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            rec = json.loads(line)
            if rec.get("kind") in E.USE_KINDS and rec.get("article_label"):
                out[E.chunk_id(rec)] = rec
    return out


def chunk_uuid(cid: str):
    return uuid5(NAMESPACE_URL, f"lawground-probe:{cid}")


def candidate_ids(row: dict, chunks: list[dict], retriever, k: int = 5) -> list[str]:
    gold = [g for g in row["gold"]][:k]
    query = " ".join([row.get("stem") or ""] + list(row.get("choices") or []))
    order = E.rank_of(E.bm25_scores(retriever.tokenize(query), retriever.docs_tokens))
    fill = [chunks[i]["id"] for i in order if chunks[i]["id"] not in gold][: k - len(gold)]
    return sorted(gold + fill, key=lambda c: hashlib.sha1(c.encode("utf-8")).hexdigest())     # 정답 위치 편향 제거


def make_request(row: dict, cand_ids: list[str], raw: dict[str, dict], provided: int, ref_date: date) -> M.VerifyRequest:
    cands = []
    for cid in cand_ids:
        rec = raw[cid]
        cands.append({"chunkId": str(chunk_uuid(cid)), "sourceText": rec["text"],
                      "metadata": {"articleId": str(uuid5(NAMESPACE_URL, f"article:{cid}")), "lawVersionId": str(uuid5(NAMESPACE_URL, f"version:{rec['law_name']}")),
                                   "lawTitle": rec["law_name"], "articleLabel": rec["article_label"], "effectiveFrom": "2026-01-01", "effectiveTo": None}})
    snap = {"subject": "BROKER_LAW", "stem": row["stem"], "questionType": question_type(row["stem"]),
            "choices": [{"number": i + 1, "text": t} for i, t in enumerate(row["choices"])], "providedAnswerNumber": provided,
            "referenceDate": ref_date.isoformat(), "revision": 1}
    return M.VerifyRequest(**{"requestId": str(uuid4()), "questionSnapshot": snap, "referenceDate": ref_date.isoformat(), "candidates": cands})


def skip_reason(row: dict) -> str | None:
    if row.get("out_of_scope"):
        return "범위 밖"
    if not row.get("stem") or not isinstance(row.get("choices"), list) or len(row["choices"]) != 5 or not all(isinstance(c, str) and c.strip() for c in row["choices"]):
        return "선지 5개가 없음(합답형 등)"
    if not isinstance(row.get("answer"), int) or not 1 <= row["answer"] <= 5:
        return "정답 번호 없음"
    if not row.get("gold"):
        return "정답 라벨 없음"
    return None


def call_once(verifier, req, sleeper):
    """한 번 부르고, 한도 초과(429)는 안내된 시간만큼 쉬고 한 번만 다시 시도한다. 일시적 서버 과부하(5xx) 재시도는 검증기가 한다(서버와 같은 규칙)."""
    for attempt in (0, 1):
        try:
            return verifier.verify(req), None
        except V.LlmError as e:
            if e.kind != "rate_limited" or attempt == 1:
                return None, (e.kind, str(e))
            sleeper(e.retry_after or 2)


def run_probe(rows, chunks, raw, retriever, verifier, *, limit=10, negative=False, sleep=0.0, ref_date=date(2026, 10, 27),
              sleeper=time.sleep, clock=time.monotonic, show=False, on_start=None, on_result=None):
    results, skipped, used = [], [], 0
    if show and hasattr(verifier, "keep_raw"):
        verifier.keep_raw = True                         # LLM 이 처음 낸 판정을 보려는 점검용(서버 기본값은 꺼짐)
    for row in rows:
        why = skip_reason(row)
        if why:
            skipped.append({"id": row["id"], "reason": why})
            continue
        if used >= limit:
            break
        used += 1
        ids = candidate_ids(row, chunks, retriever)
        provided = (row["answer"] % 5) + 1 if negative else row["answer"]
        req = make_request(row, ids, raw, provided, ref_date)
        gold_uuids = {str(chunk_uuid(g)) for g in row["gold"]}
        t0 = clock()
        if on_start:
            on_start(row["id"], provided)                # 호출 전에 알려서 오래 걸려도 진행 중임을 보여 준다
        resp, failure = call_once(verifier, req, sleeper)
        err, err_message = failure if failure else (None, None)
        item = {"id": row["id"], "mode": "negative" if negative else "positive", "provided": provided, "answer": row["answer"],
                "questionType": req.questionSnapshot.questionType.value, "gold_in_candidates": all(g in ids for g in row["gold"]),
                "secs": round(clock() - t0, 2), "error": err, "error_message": err_message, "retried": getattr(verifier, "last_calls", 1) > 1}
        if resp is not None:
            item.update(verdict=resp.verdict.value, reasonCode=resp.reasonCode, evidences=len(resp.evidences),
                        evidence_in_gold=any(str(e.chunkId) in gold_uuids for e in resp.evidences),
                        choices_present=resp.choiceAnalyses is not None)
            if show:
                labels = {str(chunk_uuid(c)): c for c in ids}
                item["detail"] = {"llm_verdict": (getattr(verifier, "last_raw", None) or {}).get("verdict"), "summary": resp.summary[:200],
                                  "quotes": [{"label": labels.get(str(e.chunkId), "?"), "quote": e.quote[:120]} for e in resp.evidences],
                                  "relations": {c.choiceNumber: c.relation.value + "".join(f"[{t.code}]" for t in c.tags) for c in (resp.choiceAnalyses or [])}}
        results.append(item)
        if on_result:
            on_result(item)                              # 문제마다 바로 출력한다(전부 끝난 뒤가 아니라)
        if sleep:
            sleeper(sleep)
    return results, skipped


def summarize(results: list[dict], skipped: list[dict]) -> dict:
    n = len(results)
    ok = [r for r in results if r["error"] is None]
    count = lambda v: sum(1 for r in ok if r["verdict"] == v)
    s = {"mode": results[0]["mode"] if results else None, "n": n, "errors": n - len(ok), "skipped": len(skipped),
         "SUPPORTED": count("SUPPORTED"), "REJECTED": count("REJECTED"), "HOLD": count("HOLD"),
         "evidence_in_gold": sum(1 for r in ok if r.get("evidence_in_gold")), "with_evidence": sum(1 for r in ok if r.get("evidences")),
         "choices_present": sum(1 for r in ok if r.get("choices_present")),
         "avg_secs": round(sum(r["secs"] for r in results) / n, 2) if n else None}
    if results and results[0]["mode"] == "positive":
        s["correct_rate"] = round(s["SUPPORTED"] / n, 3) if n else None            # 정답 번호 → SUPPORTED
    else:
        s["correct_rate"] = round(s["REJECTED"] / n, 3) if n else None             # 틀린 번호 → REJECTED
        s["false_positive_rate"] = round(s["SUPPORTED"] / n, 3) if n else None     # 틀린 번호인데 SUPPORTED = 오탐
    return s


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus-dir", required=True); ap.add_argument("--gold", required=True)
    ap.add_argument("--limit", type=int, default=10); ap.add_argument("--negative", action="store_true")
    ap.add_argument("--sleep", type=float, default=4.0, help="호출 사이 대기(초). 무료 한도를 아끼려는 값이다")
    ap.add_argument("--show", action="store_true", help="LLM 의 처음 판정, 선지별 관계, 인용 문구를 문제마다 보여 준다")
    ap.add_argument("--out", default=None)
    a = ap.parse_args(argv)
    try:
        verifier = V.from_env(os.environ)
    except RuntimeError as e:
        sys.exit(f"설정 오류: {e}")
    paths = sorted(Path(a.corpus_dir).glob("*.jsonl"))
    if not paths:
        sys.exit("법령 jsonl 이 없다")
    chunks = E.load_corpus(paths)
    raw = raw_corpus(paths)
    retriever = E.Retriever(chunks, tokenizer.tokenize)
    rows = E.load_gold(a.gold, [c["id"] for c in chunks])
    rows = [dict(r) for r in rows]
    print(f"모델: {verifier.name} | 모드: {'negative(틀린 번호)' if a.negative else 'positive(정답 번호)'} | 최대 {a.limit}문제 | 호출 간 대기 {a.sleep}초")
    def show_start(rid, provided):
        print(f"  … {rid}  제공 {provided}번 호출 중", flush=True)

    def show_result(r):
        tail = f"오류 {r['error']}: {(r['error_message'] or '')[:200]}" if r["error"] else f"{r['verdict']}({r['reasonCode']}) 인용 {r['evidences']}개, 선지분석 {'O' if r['choices_present'] else 'X'}"
        print(f"  {r['id']}  제공 {r['provided']}번  {r['secs']:>5.1f}초{' (재시도)' if r['retried'] else ''}  {tail}", flush=True)
        d = r.get("detail")
        if d:
            print(f"      LLM 처음 판정: {d['llm_verdict']} → 최종: {r['verdict']} | 선지 관계: " + " ".join(f"{k}:{v}" for k, v in d["relations"].items()), flush=True)
            for q in d["quotes"]:
                print(f"      인용 [{q['label']}] {q['quote']}", flush=True)

    results, skipped = run_probe(rows, chunks, raw, retriever, verifier, limit=a.limit, negative=a.negative, sleep=a.sleep,
                                 show=a.show, on_start=show_start, on_result=show_result)
    s = summarize(results, skipped)
    print("\n요약:", json.dumps(s, ensure_ascii=False))
    if a.out:
        Path(a.out).parent.mkdir(parents=True, exist_ok=True)
        Path(a.out).write_text(json.dumps({"model": verifier.name, "revision": verifier.revision, "summary": s, "results": results, "skipped": skipped},
                                           ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"저장: {a.out}")


if __name__ == "__main__":
    main()
