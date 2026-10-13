"""Hugging Face 모델 revision(커밋 해시) 확인. sentence-transformers 내부 구현에 의존하지 않는다."""
import os
import re

_HEX40 = re.compile(r"^[0-9a-f]{40}$")


def resolve_revision(repo_id: str, pinned: str | None = None) -> str:
    """pinned 가 있으면 그 값을 쓰고(검증만 한다), 없으면 로컬 캐시에 있는 스냅샷 폴더 이름(=커밋 해시)을 쓴다."""
    if pinned:
        if not _HEX40.match(pinned):
            raise RuntimeError(f"revision 은 40자 소문자 hex 커밋 해시여야 한다: {pinned!r}")
        return pinned
    from huggingface_hub import snapshot_download
    path = snapshot_download(repo_id, local_files_only=True)
    rev = os.path.basename(path.rstrip("/\\"))
    if not _HEX40.match(rev):
        raise RuntimeError(f"캐시 스냅샷 폴더 이름에서 커밋 해시를 얻지 못했다: {rev!r}")
    return rev
