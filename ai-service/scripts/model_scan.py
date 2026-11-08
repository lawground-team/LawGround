"""Gemini 모델이 이 키로 실제로 열려 있는지, 얼마나 빠른지 짧은 요청으로 재 본다. 모델 목록(/models)에 이름이 있어도 새 계정에는 막힌 모델이 있어서(404)
목록 조회로는 알 수 없다. 키는 환경변수 LLM_API_KEY 에서만 읽고 어떤 출력에도 싣지 않는다.

사용:  set LLM_API_KEY=...
       python scripts\\model_scan.py                       (기본 후보 몇 개)
       python scripts\\model_scan.py gemini-3.8-flash ...   (이름을 직접 지정)
읽는 법: 200 + 짧은 시간 = 쓸 수 있음 / 404 = 신규 계정에 막힘 / 503 = 과부하(잠시 뒤 다시) / 예외 = 연결·시간 초과
"""
import os
import re
import sys
import time

import httpx

BASE = "https://generativelanguage.googleapis.com/v1beta"
DEFAULTS = ["gemini-3.5-flash", "gemini-3.6-flash", "gemini-3.7-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite"]
_KEY_LIKE = re.compile(r"(AIza|AQ\.)[A-Za-z0-9_-]{20,}")


def mask(text: str, key: str) -> str:
    return _KEY_LIKE.sub("***", text.replace(key, "***"))


def scan(models, key: str, *, timeout: float = 40.0, transport=None, clock=time.monotonic) -> list[dict]:
    out = []
    with httpx.Client(timeout=timeout, transport=transport) as http:
        for m in models:
            t0 = clock()
            row = {"model": m, "status": None, "secs": None, "text": ""}
            try:
                r = http.post(f"{BASE}/models/{m}:generateContent", headers={"x-goog-api-key": key},
                              json={"contents": [{"role": "user", "parts": [{"text": "OK 라고만 답해"}]}]})
                row["status"] = r.status_code
                try:
                    data = r.json()
                    if r.status_code == 200:
                        row["text"] = "".join(p.get("text", "") for p in data["candidates"][0]["content"]["parts"])
                    else:
                        row["text"] = f"{data.get('error', {}).get('status', '')}: {data.get('error', {}).get('message', '')}"
                except (ValueError, KeyError, IndexError, TypeError, AttributeError):
                    row["text"] = ""
            except httpx.TimeoutException:
                row["text"] = "예외 시간 초과"
            except httpx.HTTPError as e:
                row["text"] = f"예외 {type(e).__name__}"
            row["secs"] = round(clock() - t0, 1)
            row["text"] = mask(re.sub(r"\s+", " ", row["text"]).strip(), key)[:110]
            out.append(row)
    return out


def main(argv=None):
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8")
        except Exception:
            pass
    key = os.environ.get("LLM_API_KEY", "").strip()
    if not key:
        sys.exit("LLM_API_KEY 가 필요하다 (set LLM_API_KEY=...)")
    models = (argv if argv is not None else sys.argv[1:]) or DEFAULTS
    print(f"{len(models)}개 모델을 짧은 요청으로 시험한다(모델당 최대 40초)")
    for m in models:                                  # 한 모델씩 바로 출력한다
        row = scan([m], key)[0]
        print(f"{row['model']:<26} {row['status'] if row['status'] else '예외':>4}  {row['secs']:>5.1f}초  {row['text']}", flush=True)


if __name__ == "__main__":
    main()
