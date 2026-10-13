"""Mock 응답 생성 로직 (실제 모델이 아니다). Mock 서버와, 실제 서버의 'mock' 백엔드가 함께 쓴다."""
import hashlib
import math
import random
import re

from app.contract import models as M

MOCK_REV = "mock-embed-0"
_TOK = re.compile(r"제\d+조(?:의\d+)?|제\d+항|제\d+호|\d+(?:[.,]\d+)?[가-힣]*|[가-힣A-Za-z]+")


def vec(text: str) -> list[float]:
    rng = random.Random(int.from_bytes(hashlib.sha256(text.encode("utf-8")).digest()[:8], "big"))
    v = [rng.uniform(-1, 1) for _ in range(1024)]
    n = math.sqrt(sum(x * x for x in v))
    return [x / n for x in v]


def embed(req: M.EmbedRequest) -> M.EmbedResponse:
    return M.EmbedResponse(items=[M.EmbedItem(id=t.id, vector=vec(t.text)) for t in req.texts],
                           dim=1024, modelRevision=req.modelRevision)


def tokenize(req: M.TokenizeRequest) -> M.TokenizeResponse:
    # Mock 토큰화: 형태소 분석이 아니라 정규식 분리다. 조문 번호·숫자+단위는 한 토큰으로 유지한다
    return M.TokenizeResponse(items=[M.TokenItem(id=t.id, tokens=_TOK.findall(t.text)[:10000]) for t in req.texts],
                              tokenizerVersion=req.tokenizerVersion)


def hyde(req: M.HydeRequest) -> M.HydeResponse:
    q = req.questionSnapshot
    return M.HydeResponse(hypotheticalText=f"(Mock 가상 문서) {q.stem[:200]}", searchTerms=_TOK.findall(q.stem)[:5] or ["mock"],
                          modelRevision="mock-llm-0", promptVersion="mock-hyde-0")


def rerank(req: M.RerankRequest) -> M.RerankResponse:
    qt = set(_TOK.findall(req.query))
    scored = [(len(qt & set(_TOK.findall(c.text))) - i * 1e-6, c.chunkId) for i, c in enumerate(req.candidates)]
    scored.sort(key=lambda x: -x[0])
    return M.RerankResponse(ranked=[M.RerankItem(chunkId=cid, score=s) for s, cid in scored[:req.topK]],
                            modelRevision="mock-rerank-0")


def _evidence(cand: M.VerifyCandidate, key: str) -> M.Evidence:
    src = cand.sourceText
    marker = "근거 문장"                      # Mock 전용 표지: 있으면 그 구간을 인용(요청서 C08 이모지·줄바꿈 예시)
    if marker in src:
        start, quote = src.index(marker), marker
    else:
        start = len(src) - len(src.lstrip())
        quote = src[start:start + 30].rstrip() or src[start:start + 1]
    return M.Evidence(evidenceKey=key, chunkId=cand.chunkId, quote=quote, start=start, end=start + len(quote))


def verify(req: M.VerifyRequest, scenario: str = "") -> M.VerifyResponse:
    q, cand = req.questionSnapshot, req.candidates[0]
    base = dict(modelRevision="mock-llm-0", promptVersion="mock-verify-0")
    if scenario == "hold":
        return M.VerifyResponse(verdict="HOLD", reasonCode="INSUFFICIENT_EVIDENCE", summary="(Mock) 근거가 충분하지 않다.",
                                evidences=[], choiceAnalyses=None, tagSetVersion=None, **base)
    ev = _evidence(cand, "ev-1")
    verdict, code = ("REJECTED", "CONTRADICTED_BY_LAW") if scenario == "rejected" else ("SUPPORTED", "SUFFICIENT_EVIDENCE")
    analyses, tag_ver = None, None
    if scenario == "with_choices":
        tag_ver = "mock-tags-0"
        analyses = []
        for c in q.choices:
            is_ans = c.number == q.providedAnswerNumber
            # 관계는 '선지 문장 vs 법령'이다. Mock 은 단순화해서 제공 정답만 SUPPORTS 로 둔다
            rel = M.Relation.SUPPORTS if is_ans else M.Relation.CONTRADICTS
            analyses.append(M.VerifyChoice(
                choiceNumber=c.number, relation=rel, explanation=f"(Mock) {c.number}번 선지 설명",
                correctedText=None if is_ans else f"(Mock 정정문) {c.number}번 선지는 조문과 다르다",
                tags=[] if is_ans else [M.Tag(code="TIMING")], evidenceKeys=["ev-1"]))
    return M.VerifyResponse(verdict=verdict, reasonCode=code, summary="(Mock) 고정 응답이다. 실제 판정이 아니다.",
                            evidences=[ev], choiceAnalyses=analyses, tagSetVersion=tag_ver, **base)
