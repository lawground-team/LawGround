"""미리 계산한 벡터 재사용: 글자가 같은 텍스트만 쓰고, 나머지는 모델로 계산하며, 모델 커밋이 다르면 거부한다(실제 모델 없음)."""
import json
import tempfile
import threading
import unittest
from pathlib import Path

import numpy as np

from app.services.embedder import BgeM3Embedder


class FakeModel:
    def __init__(self):
        self.seen = []

    def encode(self, texts, **kw):
        self.seen.append(list(texts))
        return np.full((len(texts), 1024), 0.5, dtype=np.float32)


def embedder(revision="a" * 40):
    e = object.__new__(BgeM3Embedder)            # 실제 모델을 내려받지 않는다
    e._model, e._lock, e.revision, e.precomputed = FakeModel(), threading.Lock(), revision, {}
    return e


class Precomputed(unittest.TestCase):
    def files(self, revision):
        d = Path(tempfile.mkdtemp())
        (d / "t.json").write_text(json.dumps({"revision": revision, "texts": ["가", "나"]}, ensure_ascii=False), encoding="utf-8")
        np.save(d / "v.npy", np.stack([np.full(1024, 1.0, np.float32), np.full(1024, 2.0, np.float32)]))
        return str(d / "t.json"), str(d / "v.npy")

    def test_hits_reuse_vectors_and_only_misses_reach_the_model(self):
        e = embedder()
        self.assertEqual(e.load_precomputed(*self.files("a" * 40)), 2)
        out = e.encode(["나", "다", "가"])
        self.assertEqual([v[0] for v in out], [2.0, 0.5, 1.0])
        self.assertEqual(e._model.seen, [["다"]])
        e.encode(["가"])
        self.assertEqual(e._model.seen, [["다"]])                 # 전부 맞으면 모델을 부르지 않는다

    def test_other_model_commit_is_refused(self):
        with self.assertRaises(RuntimeError):
            embedder().load_precomputed(*self.files("b" * 40))


if __name__ == "__main__":
    unittest.main()
