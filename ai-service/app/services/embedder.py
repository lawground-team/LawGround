"""BGE-M3 dense 임베딩 (로컬 서빙). torch/sentence-transformers 는 이 클래스를 만들 때만 import 한다(CI 는 설치하지 않는다).

[미검증] 이 클래스는 실제 모델로 실행해 본 적이 없다. 첫 실행은 수종 PC 에서 이뤄진다.
"""
import threading

from app.services.hf import resolve_revision


class BgeM3Embedder:
    def __init__(self, model_id: str = "BAAI/bge-m3", device: str = "cpu", pinned_revision: str | None = None):
        from sentence_transformers import SentenceTransformer
        self.name = model_id
        self._lock = threading.Lock()
        kwargs = {"revision": pinned_revision} if pinned_revision else {}
        self._model = SentenceTransformer(model_id, device=device, **kwargs)
        self.max_tokens = int(self._model.max_seq_length)
        self.revision = resolve_revision(model_id, pinned_revision)
        self._tokenizer = self._model.tokenizer

    def count_tokens(self, text: str) -> int:
        return len(self._tokenizer(text, add_special_tokens=True)["input_ids"])

    def encode(self, texts: list[str]) -> list[list[float]]:
        with self._lock:      # CPU 추론을 한 번에 하나씩 (스레드 과다 사용 방지)
            arr = self._model.encode(texts, batch_size=8, normalize_embeddings=True,
                                     convert_to_numpy=True, show_progress_bar=False)
        return arr.tolist()
