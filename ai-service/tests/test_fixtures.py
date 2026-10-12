"""fixtures/ 의 모든 파일을 Mock 서버에 다시 보내 기대 응답과 같은지, 응답이 모델·교차 검사를 통과하는지 확인한다.
Spring 쪽도 같은 fixture 를 읽어 같은 검사를 하면 '같은 fixture 사용' 합의 조건(요청서 §9)을 만족한다."""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient  # noqa: E402

from app.contract import models as M  # noqa: E402
from app.mock.server import DEV_TOKEN, app  # noqa: E402
from app.contract.checks import check_embed, check_rerank, check_tokenize, check_verify  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
client = TestClient(app)
REQ = {"embed": (M.EmbedRequest, M.EmbedResponse, check_embed), "tokenize": (M.TokenizeRequest, M.TokenizeResponse, check_tokenize),
       "rerank": (M.RerankRequest, M.RerankResponse, check_rerank), "verify": (M.VerifyRequest, M.VerifyResponse, check_verify)}
FILES = sorted((ROOT / "fixtures").glob("*/*.json"))


class FixtureTests(unittest.TestCase):
    def test_there_are_fixtures(self):
        self.assertGreaterEqual(len(FILES), 27)
        groups = {f.parent.name for f in FILES}
        self.assertEqual(groups, {"embed", "tokenize", "hyde", "rerank", "verify", "health"})
        for g in groups - {"health"}:   # 요청서: API별로 정상 1 + 입력 오류 1 + 운영 오류 1 이상
            names = [f.name for f in FILES if f.parent.name == g]
            for prefix in ("ok", "invalid", "ops"):
                self.assertTrue(any(n.startswith(prefix) for n in names), (g, prefix))

    def test_replay_all(self):
        for f in FILES:
            with self.subTest(fixture=f"{f.parent.name}/{f.name}"):
                d = json.loads(f.read_text(encoding="utf-8"))
                rq, ex = d["request"], d["expected"]
                h = dict(rq["headers"])
                if "X-Internal-Token" in h:                       # 파일에는 토큰 실값을 쓰지 않는다
                    h["X-Internal-Token"] = DEV_TOKEN
                raw = rq["body"].encode() if isinstance(rq["body"], str) else json.dumps(rq["body"], ensure_ascii=False).encode()
                r = client.request(rq["method"], rq["path"], content=raw if rq["method"] == "POST" else None, headers=h)
                self.assertEqual(r.status_code, ex["status"])
                got, want = r.json(), ex["body"]
                if "timestamp" in want:
                    self.assertTrue(got["timestamp"].endswith("Z"))
                    got["timestamp"] = want["timestamp"]
                if want.get("requestId") == "<UUID>":
                    got["requestId"] = "<UUID>"
                if "message" in want and ex["status"] in (400, 422):
                    got["message"] = want["message"] = "<검증 메시지>"
                self.assertEqual(got, want)
                for hk, hv in ex["headers"].items():
                    self.assertEqual(r.headers.get(hk), hv)
                kind = f.parent.name
                if ex["status"] == 200 and kind in REQ:           # 정상 응답은 모델과 교차 검사를 통과해야 한다
                    req_m, resp_m, chk = REQ[kind]
                    chk(req_m(**rq["body"]), resp_m(**r.json()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
