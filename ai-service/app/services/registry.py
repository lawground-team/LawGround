"""백엔드 설정과 로드된 모델 보관소.

환경변수로 기능별 백엔드를 고른다. **기본값은 off** — 설정하지 않은 기능은 가짜 값을 내지 않고 503 을 반환한다.
  EMBED_BACKEND   = off | bge-m3 | mock
  RERANK_BACKEND  = off | bge-reranker | mock
  HYDE_BACKEND    = off | mock        (LLM 미정, 실제 구현 없음)
  VERIFY_BACKEND  = off | mock        (LLM 미정, 실제 구현 없음)
  EMBED_MODEL_REVISION / RERANK_MODEL_REVISION = Hugging Face 커밋 해시(40자 hex). 비우면 캐시된 최신 스냅샷의 해시를 쓴다.
"""
import os

ALLOWED = {
    "EMBED_BACKEND": {"off", "bge-m3", "mock"},
    "RERANK_BACKEND": {"off", "bge-reranker", "mock"},
    "HYDE_BACKEND": {"off", "mock"},
    "VERIFY_BACKEND": {"off", "mock"},
}
_state = {"embedder": None, "reranker": None}


def backend(name: str) -> str:
    value = os.environ.get(name, "off").strip().lower()
    if value not in ALLOWED[name]:
        raise RuntimeError(f"{name}={value!r} 는 허용되지 않는다. 허용: {sorted(ALLOWED[name])}")
    return value


def validate_env() -> dict:
    return {name: backend(name) for name in ALLOWED}


def set_embedder(x):
    _state["embedder"] = x


def get_embedder():
    return _state["embedder"]


def set_reranker(x):
    _state["reranker"] = x


def get_reranker():
    return _state["reranker"]


def reset():
    _state["embedder"] = None
    _state["reranker"] = None


def load_configured():
    """서버 시작 때 실제 모델을 미리 불러온다(첫 요청이 모델 로딩 시간을 맞지 않도록). 이미 주입돼 있으면 건너뛴다."""
    validate_env()
    if backend("EMBED_BACKEND") == "bge-m3" and _state["embedder"] is None:
        from app.services.embedder import BgeM3Embedder
        _state["embedder"] = BgeM3Embedder(pinned_revision=os.environ.get("EMBED_MODEL_REVISION") or None)
    if backend("RERANK_BACKEND") == "bge-reranker" and _state["reranker"] is None:
        from app.services.reranker import BgeReranker
        _state["reranker"] = BgeReranker(pinned_revision=os.environ.get("RERANK_MODEL_REVISION") or None)
