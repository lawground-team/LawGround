import json
import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import search_stage_probe as sp  # noqa: E402

ROW = {"stem": "지문", "choices": ["가", "나", "다", "라", "마"], "answer": 3}


def test_query_text_matches_spring_format():
    q = sp.query_text(ROW)
    assert q.splitlines()[0] == "subject: BROKER_LAW"
    assert "\nchoice[1]: 가\n" in q and "\nchoice[5]: 마\n" in q
    assert q.endswith("\nprovidedAnswerNumber: 3")


def test_read_env_ignores_comments_and_quotes(tmp_path):
    f = tmp_path / ".env"
    f.write_text('# c\nA=1\nB="x y"\n\nC=\n', encoding="utf-8")
    assert sp.read_env(f) == {"A": "1", "B": "x y", "C": ""}


def test_probe_sends_headers_and_flags_timeout():
    seen = []

    def handler(req: httpx.Request):
        seen.append(req)
        return httpx.Response(200, json={})
    p = sp.Probe("http://x/internal/v1", "tok", "e1", "r1", transport=httpx.MockTransport(handler))
    sec, code, _ = p.embed("질의")
    body = json.loads(seen[0].content)
    assert code == 200 and body["modelRevision"] == "e1"
    assert seen[0].headers["x-internal-token"] == "tok" and seen[0].headers["x-request-id"] == body["requestId"]
    p.rerank("질의", [{"text": "a"}, {"text": "b"}])
    rb = json.loads(seen[1].content)
    assert len(rb["candidates"]) == 2 and rb["topK"] == 2
    assert "초과" in sp.verdict("rerank", 61.0, 200)
    assert "제한 안" in sp.verdict("rerank", 59.0, 200)
