"""실행 중인 서버(app.main 또는 Mock)에 실제 HTTP 요청을 보내 결과를 보여주는 시연·점검 도구.

사용:
  (서버)   set INTERNAL_API_TOKEN=<임의 값>  →  uvicorn app.main:app --port 8001
  (이 도구) set INTERNAL_API_TOKEN=<같은 값>  →  python scripts\\smoke.py [--base http://127.0.0.1:8001]
토큰 값은 출력하지 않는다. mock 백엔드는 이름·revision 에 mock 이 드러나며 '실제 결과'가 아니다.
"""
import argparse
import math
import os
import sys
from uuid import uuid4

import httpx

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

P = "/internal/v1"
CONTRACT = "0.1.0-draft"
Q = "중개보수 지급시기 약정 거래대금"
CANDS = ["중개보수의 지급시기는 약정에 따르되 약정이 없을 때에는 거래대금 지급이 완료된 날로 한다.",
         "소유권이전등기 신청서에는 등기원인을 증명하는 서면을 첨부하여야 한다.",
         "거래계약서에는 거래대금의 지급일을 기재하여야 한다.",
         "업무정지 기간 중에는 새로운 중개계약을 체결할 수 없다."]


def cos(a, b):
    return sum(x * y for x, y in zip(a, b)) / (math.sqrt(sum(x * x for x in a)) * math.sqrt(sum(y * y for y in b)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8001")
    ap.add_argument("--token", default=os.environ.get("INTERNAL_API_TOKEN"))
    a = ap.parse_args()
    if not a.token:
        sys.exit("INTERNAL_API_TOKEN 환경변수(또는 --token)가 필요하다. 값은 출력하지 않는다.")
    c = httpx.Client(base_url=a.base, timeout=120)

    def call(method, path, body=None):
        rid = (body or {}).get("requestId") or str(uuid4())
        h = {"X-Internal-Token": a.token, "X-Request-Id": rid, "X-Contract-Version": CONTRACT}
        return c.request(method, P + path, json=body, headers=h)

    print(f"서버: {a.base}\n")
    r = call("GET", "/health/ready")
    print(f"[ready] HTTP {r.status_code}  status={r.json().get('status')}")
    models = r.json().get("models", [])
    for m in models:
        print(f"   - {m['name']:34} ready={m['ready']!s:5} revision={(m['revision'] or '-')[:40]}")
    rev = {m["name"].split(":")[0]: m["revision"] for m in models}

    texts = ["법 제32조제3항에 따른 중개보수의 지급시기는 약정이 없을 때에는 거래대금 지급이 완료된 날로 한다.",
             "제25조의2제1항에 따라 30일 이내에 신고하지 아니한 경우에는 3개월 이내의 업무정지를 할 수 있다."]
    body = {"requestId": str(uuid4()), "tokenizerVersion": rev.get("tokenizer", ""), "texts": [{"id": str(uuid4()), "text": t} for t in texts]}
    r = call("POST", "/tokenize", body)
    print(f"\n[tokenize] HTTP {r.status_code}")
    if r.status_code == 200:
        for t, it in zip(texts, r.json()["items"]):
            print(f"   원문: {t}\n   토큰: {it['tokens']}")
    else:
        print("  ", r.json())

    pair = ["중개보수의 지급시기", "약정이 없을 때에는 거래대금 지급이 완료된 날로 한다", "소유권이전등기 신청서에는 등기원인을 증명하는 서면을 첨부하여야 한다"]
    body = {"requestId": str(uuid4()), "modelRevision": rev.get("embed") or "mock", "texts": [{"id": str(uuid4()), "text": t} for t in pair]}
    r = call("POST", "/embed", body)
    print(f"\n[embed] HTTP {r.status_code}")
    if r.status_code == 200:
        v = [it["vector"] for it in r.json()["items"]]
        print(f"   dim={r.json()['dim']}  modelRevision={r.json()['modelRevision'][:40]}  norm={math.sqrt(sum(x * x for x in v[0])):.4f}")
        print(f"   유사도(관련 문장) {cos(v[0], v[1]):.4f}  vs  (무관 문장) {cos(v[0], v[2]):.4f}   ※ mock 이면 의미 없는 값")
    else:
        print("  ", r.json().get("code"), "-", r.json().get("message"))

    body = {"requestId": str(uuid4()), "query": Q, "topK": 3, "candidates": [{"chunkId": str(uuid4()), "text": t} for t in CANDS]}
    r = call("POST", "/rerank", body)
    print(f"\n[rerank] HTTP {r.status_code}")
    if r.status_code == 200:
        idx = {c_["chunkId"]: i for i, c_ in enumerate(body["candidates"])}
        for rank, it in enumerate(r.json()["ranked"], 1):
            print(f"   {rank}위 score={it['score']:.4f}  {CANDS[idx[it['chunkId']]][:36]}...")
    else:
        print("  ", r.json().get("code"), "-", r.json().get("message"))


if __name__ == "__main__":
    main()
