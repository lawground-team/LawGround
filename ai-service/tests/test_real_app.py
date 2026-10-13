"""실제 서버(app.main) 검증. 토크나이저는 실제 Kiwi, 임베딩·리랭커는 **가짜 모델을 주입**한다(torch 를 설치하지 않는다).
→ 서버 로직(버전·상한·정렬·꺼짐·인증)만 검증하며, 실제 BGE 모델의 동작은 검증하지 않는다."""
import hashlib
import json
import os
import random
import sys
import unittest
from pathlib import Path
from unittest import mock
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi.testclient import TestClient  # noqa: E402

from app.contract import models as M  # noqa: E402
from app.contract.checks import check_embed, check_rerank, check_tokenize, check_verify  # noqa: E402
from app.main import app  # noqa: E402
from app.services import registry, tokenizer  # noqa: E402
from tests.test_contract import hdr, snapshot, verify_body  # noqa: E402,F401

P = "/internal/v1"
TOKEN = "test-token"
ENV = {"INTERNAL_API_TOKEN": TOKEN, "EMBED_BACKEND": "bge-m3", "RERANK_BACKEND": "bge-reranker",
       "HYDE_BACKEND": "mock", "VERIFY_BACKEND": "mock"}
ROOT = Path(__file__).resolve().parents[1]


class FakeEmbedder:
    name, revision, max_tokens = "fake-embed", "ab" * 20, 64

    def count_tokens(self, text):
        return len(text) // 2 + 1

    def encode(self, texts):
        out = []
        for t in texts:
            rng = random.Random(int.from_bytes(hashlib.sha256(t.encode()).digest()[:8], "big"))
            v = [rng.uniform(-1, 1) for _ in range(1024)]
            n = sum(x * x for x in v) ** 0.5
            out.append([x / n for x in v])
        return out


class FakeReranker:
    name, revision, max_tokens = "fake-rerank", "cd" * 20, 64

    def pair_tokens(self, q, t):
        return (len(q) + len(t)) // 2 + 1

    def score(self, q, texts):
        qs = set(q.split())
        return [float(len(qs & set(t.split()))) for t in texts]


def H(rid=None, **extra):
    rid = rid or str(uuid4())
    h = {"X-Internal-Token": TOKEN, "X-Request-Id": rid, "X-Contract-Version": M.CONTRACT_VERSION, "Content-Type": "application/json"}
    h.update(extra)
    return rid, h


def post(client, path, body, **extra):
    rid, h = H(rid=body.get("requestId"), **extra)
    return client.post(P + path, content=json.dumps(body, ensure_ascii=False).encode(), headers=h)


class RealAppBase(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.dict(os.environ, ENV)
        patcher.start()
        self.addCleanup(patcher.stop)
        registry.reset()
        registry.set_embedder(FakeEmbedder())
        registry.set_reranker(FakeReranker())
        self.addCleanup(registry.reset)
        self.c = TestClient(app)


class TokenizeTests(RealAppBase):
    def body(self, text="법 제32조제3항에 따른 30일 이내", version=None):
        return {"requestId": str(uuid4()), "tokenizerVersion": version or tokenizer.TOKENIZER_VERSION, "texts": [{"id": str(uuid4()), "text": text}]}

    def test_ok_uses_real_kiwi(self):
        b = self.body()
        r = post(self.c, "/tokenize", b)
        self.assertEqual(r.status_code, 200, r.text)
        toks = r.json()["items"][0]["tokens"]
        self.assertIn("제32조", toks)
        self.assertIn("30일", toks)
        check_tokenize(M.TokenizeRequest(**b), M.TokenizeResponse(**r.json()))

    def test_version_mismatch_is_409(self):
        r = post(self.c, "/tokenize", self.body(version="tok-1"))
        self.assertEqual((r.status_code, r.json()["code"], r.json()["retryable"]), (409, "MODEL_REVISION_MISMATCH", False))

    def test_token_limit_is_422(self):
        # 계약이 허용하는 최대 길이(30,000자) 안에서도 짧은 토큰이 반복되면 10,000개를 넘는다('약 ' 15,000회 = 토큰 15,000개)
        text = "약 " * 15000
        self.assertEqual(len(text), 30000)
        r = post(self.c, "/tokenize", self.body(text=text))
        self.assertEqual((r.status_code, r.json()["code"]), (422, "INPUT_TOO_LONG"))


class EmbedTests(RealAppBase):
    def body(self, texts=("중개보수", "지급시기"), rev=None):
        return {"requestId": str(uuid4()), "modelRevision": rev or FakeEmbedder.revision, "texts": [{"id": str(uuid4()), "text": t} for t in texts]}

    def test_ok(self):
        b = self.body()
        r = post(self.c, "/embed", b)
        self.assertEqual(r.status_code, 200, r.text)
        check_embed(M.EmbedRequest(**b), M.EmbedResponse(**r.json()))
        self.assertEqual(r.json()["modelRevision"], FakeEmbedder.revision)

    def test_revision_mismatch_409(self):
        r = post(self.c, "/embed", self.body(rev="ef" * 20))
        self.assertEqual((r.status_code, r.json()["code"]), (409, "MODEL_REVISION_MISMATCH"))

    def test_input_too_long_422_names_the_text(self):
        b = self.body(texts=("짧다", "가" * 200))
        r = post(self.c, "/embed", b)
        self.assertEqual((r.status_code, r.json()["code"]), (422, "INPUT_TOO_LONG"))
        self.assertIn(b["texts"][1]["id"], r.json()["message"])
        self.assertNotIn(b["texts"][0]["id"], r.json()["message"])

    def test_off_is_503_not_fake_vectors(self):
        with mock.patch.dict(os.environ, {"EMBED_BACKEND": "off"}):
            r = post(self.c, "/embed", self.body())
        self.assertEqual((r.status_code, r.json()["code"], r.json()["retryable"]), (503, "MODEL_NOT_READY", False))

    def test_model_not_loaded_is_503(self):
        registry.set_embedder(None)
        self.assertEqual(post(self.c, "/embed", self.body()).status_code, 503)

    def test_mock_backend_is_marked_in_readiness(self):
        with mock.patch.dict(os.environ, {"EMBED_BACKEND": "mock"}):
            r = post(self.c, "/embed", self.body(rev="whatever"))
            ready = self.c.get(P + "/health/ready", headers=H()[1])
        self.assertEqual(r.status_code, 200)
        self.assertIn("embed:mock", [m["name"] for m in ready.json()["models"]])


class RerankTests(RealAppBase):
    def body(self, topk=2, cands=("중개보수 지급시기", "등기 신청", "지급시기 약정 중개보수")):
        return {"requestId": str(uuid4()), "query": "중개보수 지급시기 약정", "topK": topk,
                "candidates": [{"chunkId": str(uuid4()), "text": t} for t in cands]}

    def test_sorted_desc_ties_keep_input_order(self):
        b = self.body(topk=3, cands=("가 나", "가 나", "다 라"))
        b["query"] = "가 나"
        r = post(self.c, "/rerank", b)
        self.assertEqual(r.status_code, 200, r.text)
        ids = [x["chunkId"] for x in r.json()["ranked"]]
        self.assertEqual(ids[:2], [b["candidates"][0]["chunkId"], b["candidates"][1]["chunkId"]])   # 동점은 입력 순서
        check_rerank(M.RerankRequest(**b), M.RerankResponse(**r.json()))

    def test_ok_topk(self):
        b = self.body()
        r = post(self.c, "/rerank", b)
        check_rerank(M.RerankRequest(**b), M.RerankResponse(**r.json()))
        self.assertEqual(len(r.json()["ranked"]), 2)
        self.assertEqual(r.json()["modelRevision"], FakeReranker.revision)

    def test_too_long_422(self):
        b = self.body(cands=("가" * 300,))
        self.assertEqual(post(self.c, "/rerank", b).json()["code"], "INPUT_TOO_LONG")

    def test_off_503(self):
        with mock.patch.dict(os.environ, {"RERANK_BACKEND": "off"}):
            self.assertEqual(post(self.c, "/rerank", self.body()).status_code, 503)


class LlmBackendTests(RealAppBase):
    def test_hyde_verify_mock_vs_off(self):
        hb = {"requestId": str(uuid4()), "questionSnapshot": snapshot(), "referenceDate": "2025-10-01"}
        vb = verify_body()
        self.assertEqual(post(self.c, "/hyde", hb).status_code, 200)
        self.assertEqual(post(self.c, "/verify", vb).status_code, 200)
        self.assertTrue(post(self.c, "/verify", vb).json()["modelRevision"].startswith("mock"))
        with mock.patch.dict(os.environ, {"HYDE_BACKEND": "off", "VERIFY_BACKEND": "off"}):
            self.assertEqual(post(self.c, "/hyde", hb).status_code, 503)
            self.assertEqual(post(self.c, "/verify", vb).status_code, 503)


class SecurityAndReadinessTests(RealAppBase):
    def test_mock_scenario_header_is_ignored(self):
        b = {"requestId": str(uuid4()), "tokenizerVersion": tokenizer.TOKENIZER_VERSION, "texts": [{"id": str(uuid4()), "text": "약정"}]}
        self.assertEqual(post(self.c, "/tokenize", b, **{"X-Mock-Scenario": "timeout"}).status_code, 200)

    def test_fail_closed_without_token_env(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("INTERNAL_API_TOKEN")
            for headers in (H()[1], {**H()[1], "X-Internal-Token": ""}):
                r = self.c.get(P + "/health/ready", headers=headers)
                self.assertEqual((r.status_code, r.json()["code"]), (401, "UNAUTHORIZED"))

    def test_wrong_token_401(self):
        self.assertEqual(self.c.get(P + "/health/ready", headers={**H()[1], "X-Internal-Token": "nope"}).status_code, 401)

    def test_ready_when_everything_loaded(self):
        r = self.c.get(P + "/health/ready", headers=H()[1])
        self.assertEqual(r.status_code, 200, r.text)
        names = {m["name"]: m for m in r.json()["models"]}
        self.assertEqual(names["tokenizer:kiwipiepy"]["revision"], tokenizer.TOKENIZER_VERSION)
        self.assertEqual(names["embed:fake-embed"]["revision"], FakeEmbedder.revision)

    def test_not_ready_lists_off_backends(self):
        with mock.patch.dict(os.environ, {"EMBED_BACKEND": "off"}):
            r = self.c.get(P + "/health/ready", headers=H()[1])
        self.assertEqual((r.status_code, r.json()["status"]), (503, "NOT_READY"))
        self.assertIn("embed:off", [m["name"] for m in r.json()["models"]])
        M.ReadyOutput(**r.json())                       # 503 본문은 ReadyOutput

    def test_invalid_backend_value_fails_loudly_at_startup(self):
        with mock.patch.dict(os.environ, {"EMBED_BACKEND": "typo"}):
            with self.assertRaises(RuntimeError):
                registry.validate_env()

    def test_lifespan_with_off_and_mock_backends(self):
        with mock.patch.dict(os.environ, {"EMBED_BACKEND": "mock", "RERANK_BACKEND": "off"}):
            registry.reset()
            with TestClient(app) as c:
                self.assertEqual(c.get(P + "/health/ready", headers=H()[1]).status_code, 503)


class FixtureRequestReplay(RealAppBase):
    """Mock 에서 만든 fixture 의 요청을 실제 서버에 보낸다. 값은 모델마다 다르므로 **상태·오류 코드·계약 검사**만 비교한다."""
    FILES = sorted((ROOT / "fixtures").glob("*/*.json"))

    def test_replay(self):
        checks = {"embed": (M.EmbedRequest, M.EmbedResponse, check_embed), "tokenize": (M.TokenizeRequest, M.TokenizeResponse, check_tokenize),
                  "verify": (M.VerifyRequest, M.VerifyResponse, check_verify)}
        replayed = 0
        for f in self.FILES:
            kind, name = f.parent.name, f.name
            if kind == "health" or not (name.startswith("ok") or name.startswith("invalid")):
                continue                                   # ops_* 는 Mock 시나리오 전용
            d = json.loads(f.read_text(encoding="utf-8"))
            rq, ex = d["request"], d["expected"]
            body = rq["body"]
            if isinstance(body, dict):
                body = json.loads(json.dumps(body))
                if kind == "tokenize" and "tokenizerVersion" in body:
                    body["tokenizerVersion"] = tokenizer.TOKENIZER_VERSION       # Mock fixture 의 'tok-1' 대체
                if kind == "embed" and "modelRevision" in body:
                    body["modelRevision"] = FakeEmbedder.revision
                raw = json.dumps(body, ensure_ascii=False).encode()
            else:
                raw = body.encode()
            h = dict(rq["headers"]); h["X-Internal-Token"] = TOKEN
            with self.subTest(fixture=f"{kind}/{name}"):
                r = self.c.post(rq["path"], content=raw, headers=h)
                self.assertEqual(r.status_code, ex["status"], r.text[:200])
                if ex["status"] != 200:
                    self.assertEqual(r.json()["code"], ex["body"]["code"])
                elif kind in checks:
                    rm, sm, chk = checks[kind]
                    chk(rm(**body), sm(**r.json()))
                replayed += 1
        self.assertGreaterEqual(replayed, 15)


if __name__ == "__main__":
    unittest.main(verbosity=2)
