"""요청-응답 교차 검사 (스키마만으로는 잡을 수 없는 불변식).

Spring 은 저장 전에, Python 은 응답 전에 같은 검사를 한다(협업계획의 '양쪽 검증' 원칙).
오프셋은 파이썬 str 인덱스 = Unicode code point 기준이다. Java(UTF-16) 에서는 변환이 필요하다.
"""
from .models import (EmbedRequest, EmbedResponse, RerankRequest, RerankResponse,
                     TokenizeRequest, TokenizeResponse, VerifyRequest, VerifyResponse)


class ContractViolation(AssertionError):
    pass


def _need(cond: bool, msg: str):
    if not cond:
        raise ContractViolation(msg)


def check_embed(req: EmbedRequest, resp: EmbedResponse):
    _need([i.id for i in resp.items] == [t.id for t in req.texts], "id·순서·개수가 요청과 같아야 한다")
    _need(resp.modelRevision == req.modelRevision, "요청 revision 과 응답 revision 이 일치해야 한다")


def check_tokenize(req: TokenizeRequest, resp: TokenizeResponse):
    _need([i.id for i in resp.items] == [t.id for t in req.texts], "id·순서·개수가 요청과 같아야 한다")
    _need(resp.tokenizerVersion == req.tokenizerVersion, "tokenizerVersion 이 요청과 같아야 한다")


def check_rerank(req: RerankRequest, resp: RerankResponse):
    cand = {c.chunkId for c in req.candidates}
    _need(all(r.chunkId in cand for r in resp.ranked), "후보 밖 chunkId 를 반환할 수 없다")
    _need(len(resp.ranked) <= req.topK, "ranked 개수는 topK 이하여야 한다")
    _need(len(resp.ranked) == min(req.topK, len(req.candidates)),
          "[제안] 후보가 topK 보다 적으면 후보 전체, 아니면 정확히 topK 개를 반환한다")


def check_verify(req: VerifyRequest, resp: VerifyResponse):
    text = {c.chunkId: c.sourceText for c in req.candidates}
    for e in resp.evidences:
        _need(e.chunkId in text, f"후보 밖 chunkId 인용 금지: {e.chunkId}")
        src = text[e.chunkId]
        _need(0 <= e.start < e.end <= len(src), f"오프셋 범위 오류: {e.start}..{e.end} / {len(src)}")
        _need(src[e.start:e.end] == e.quote, f"sourceText[start:end] != quote ({e.evidenceKey})")
    if resp.verdict.value in ("SUPPORTED", "REJECTED"):
        _need(len(resp.evidences) >= 1, "[제안] SUPPORTED/REJECTED 는 근거가 1개 이상 필요하다")
