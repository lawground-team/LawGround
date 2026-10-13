"""실제 서버. 기본값은 모든 모델 기능이 꺼져 있다(off). 설정하지 않은 기능은 503 을 반환하며 가짜 값을 내지 않는다.

실행:  uvicorn app.main:app --port 8001      (환경변수 INTERNAL_API_TOKEN 필수. 없으면 모든 요청을 401 로 거절한다)
tokenize 는 항상 실제 Kiwi 다. 나머지는 app/services/registry.py 의 환경변수로 고른다.
"""
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI
from fastapi.responses import JSONResponse
import os

from app.common import ERR, P, TOKEN_ENV, ContractError, make_guard, register_handlers
from app.contract import models as M
from app.mock import logic
from app.services import registry, tokenizer


@asynccontextmanager
async def lifespan(app):
    registry.load_configured()
    tokenizer.tokenize("워밍업")          # Kiwi 첫 로딩(수 초)을 서버 시작 때 끝낸다
    yield


app = FastAPI(title="LawGround Python AI 내부 API", version=M.CONTRACT_VERSION, lifespan=lifespan,
              description="계약 0.1.0-draft(Spring 측 제안). tokenize 는 실제 Kiwi, 나머지는 환경변수로 선택한다.")
register_handlers(app)
guard = make_guard(lambda: os.environ.get(TOKEN_ENV), allow_mock_scenarios=False)


def _off(what: str) -> ContractError:
    return ContractError(503, M.ErrorCode.MODEL_NOT_READY, f"{what} 백엔드가 꺼져 있거나 준비되지 않았다", False)


@app.post(P + "/tokenize", response_model=M.TokenizeResponse, responses=ERR, tags=["tokenize"])
def tokenize(req: M.TokenizeRequest, _=Depends(guard)):
    if req.tokenizerVersion != tokenizer.TOKENIZER_VERSION:
        raise ContractError(409, M.ErrorCode.MODEL_REVISION_MISMATCH,
                            f"서버 tokenizerVersion 은 {tokenizer.TOKENIZER_VERSION} 이다", False)
    items = []
    for t in req.texts:
        try:
            toks = tokenizer.tokenize(t.text)
        except tokenizer.TokenLimitExceeded as e:
            raise ContractError(422, M.ErrorCode.INPUT_TOO_LONG, f"텍스트 {t.id}: {e}", False)
        items.append(M.TokenItem(id=t.id, tokens=toks))
    return M.TokenizeResponse(items=items, tokenizerVersion=tokenizer.TOKENIZER_VERSION)


@app.post(P + "/embed", response_model=M.EmbedResponse, responses=ERR, tags=["embed"])
def embed(req: M.EmbedRequest, _=Depends(guard)):
    mode = registry.backend("EMBED_BACKEND")
    if mode == "mock":
        return logic.embed(req)
    emb = registry.get_embedder() if mode == "bge-m3" else None
    if emb is None:
        raise _off("embed")
    if req.modelRevision != emb.revision:
        raise ContractError(409, M.ErrorCode.MODEL_REVISION_MISMATCH, f"서버 modelRevision 은 {emb.revision} 이다", False)
    over = [str(t.id) for t in req.texts if emb.count_tokens(t.text) > emb.max_tokens]
    if over:
        raise ContractError(422, M.ErrorCode.INPUT_TOO_LONG, f"모델 입력 상한({emb.max_tokens} 토큰)을 넘은 텍스트: {over}", False)
    vectors = emb.encode([t.text for t in req.texts])
    return M.EmbedResponse(items=[M.EmbedItem(id=t.id, vector=v) for t, v in zip(req.texts, vectors)],
                           dim=1024, modelRevision=emb.revision)


@app.post(P + "/rerank", response_model=M.RerankResponse, responses=ERR, tags=["rerank"])
def rerank(req: M.RerankRequest, _=Depends(guard)):
    mode = registry.backend("RERANK_BACKEND")
    if mode == "mock":
        return logic.rerank(req)
    rr = registry.get_reranker() if mode == "bge-reranker" else None
    if rr is None:
        raise _off("rerank")
    over = [str(c.chunkId) for c in req.candidates if rr.pair_tokens(req.query, c.text) > rr.max_tokens]
    if over:
        raise ContractError(422, M.ErrorCode.INPUT_TOO_LONG, f"모델 입력 상한({rr.max_tokens} 토큰)을 넘은 후보: {over}", False)
    scores = rr.score(req.query, [c.text for c in req.candidates])
    order = sorted(range(len(scores)), key=lambda i: (-scores[i], i))      # 동점은 입력 순서 유지
    ranked = [M.RerankItem(chunkId=req.candidates[i].chunkId, score=scores[i]) for i in order[:req.topK]]
    return M.RerankResponse(ranked=ranked, modelRevision=rr.revision)


@app.post(P + "/hyde", response_model=M.HydeResponse, responses=ERR, tags=["hyde"])
def hyde(req: M.HydeRequest, _=Depends(guard)):
    if registry.backend("HYDE_BACKEND") == "mock":
        return logic.hyde(req)
    raise _off("hyde")


@app.post(P + "/verify", response_model=M.VerifyResponse, responses=ERR, tags=["verify"])
def verify(req: M.VerifyRequest, _=Depends(guard)):
    if registry.backend("VERIFY_BACKEND") == "mock":
        return logic.verify(req, "")
    raise _off("verify")


@app.get(P + "/health/ready", response_model=M.ReadyOutput, responses={401: {"model": M.ErrorOut}, 503: {"model": M.ReadyOutput}}, tags=["health"])
def ready(_=Depends(guard)):
    models = [M.ModelState(name="tokenizer:kiwipiepy", revision=tokenizer.TOKENIZER_VERSION, ready=True)]
    for label, env, getter, real in (("embed", "EMBED_BACKEND", registry.get_embedder, "bge-m3"),
                                     ("rerank", "RERANK_BACKEND", registry.get_reranker, "bge-reranker")):
        mode = registry.backend(env)
        if mode == real:
            m = getter()
            models.append(M.ModelState(name=f"{label}:{getattr(m, 'name', real)}", revision=getattr(m, "revision", None), ready=m is not None))
        elif mode == "mock":
            models.append(M.ModelState(name=f"{label}:mock", revision=logic.MOCK_REV, ready=True))   # 이름에 mock 이 드러난다
        else:
            models.append(M.ModelState(name=f"{label}:off", revision=None, ready=False))
    out = M.ReadyOutput(status=M.ReadyStatus.READY if all(m.ready for m in models) else M.ReadyStatus.NOT_READY, models=models)
    if out.status == M.ReadyStatus.NOT_READY:
        return JSONResponse(status_code=503, content=out.model_dump(mode="json"))
    return out
