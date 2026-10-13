"""BGE reranker (cross-encoder, 로컬 서빙). score 는 확률이 아니라 **모델 원점수(logit)** 다.

[미검증] 실제 모델로 실행해 본 적이 없다. 첫 실행은 수종 PC 에서 이뤄진다.
"""
import threading

from app.services.hf import resolve_revision


class BgeReranker:
    def __init__(self, model_id: str = "BAAI/bge-reranker-v2-m3", device: str = "cpu", pinned_revision: str | None = None):
        import torch
        from sentence_transformers import CrossEncoder
        self.name = model_id
        self._lock = threading.Lock()
        self._identity = torch.nn.Identity()
        kwargs = {"revision": pinned_revision} if pinned_revision else {}
        self._model = CrossEncoder(model_id, device=device, **kwargs)
        self.max_tokens = int(self._model.max_seq_length)
        self.revision = resolve_revision(model_id, pinned_revision)
        self._tokenizer = self._model.tokenizer

    def pair_tokens(self, query: str, text: str) -> int:
        return len(self._tokenizer(query, text, add_special_tokens=True)["input_ids"])

    def score(self, query: str, texts: list[str]) -> list[float]:
        with self._lock:
            out = self._model.predict([(query, t) for t in texts], batch_size=8, activation_fn=self._identity,
                                      show_progress_bar=False, convert_to_numpy=True)
        return [float(x) for x in out]
