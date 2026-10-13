"""Python AI 서비스 Mock 서버 (S02) — 고정 응답이며 실제 모델이 아니다.

모든 응답의 modelRevision/promptVersion 이 'mock-*' 이므로 Spring 은 Mock 과 실제 AI 를 구분할 수 있다.
계약에 없는 X-Mock-Scenario 헤더로 오류·판정 시나리오를 고를 수 있다(Mock 전용, 계약 아님).
"""
import os

from fastapi import Depends, FastAPI
from fastapi.responses import JSONResponse

from app.common import ERR, P, TOKEN_ENV, make_guard, register_handlers
from app.contract import models as M
from app.mock import logic
from app.mock.logic import MOCK_REV  # noqa: F401  (재노출)

DEV_TOKEN = "dev-mock-token"                # 로컬 Mock 전용 더미 값. 실제 서버(app.main)에는 기본 토큰이 없다

app = FastAPI(title="LawGround Python AI 내부 API (Mock)", version=M.CONTRACT_VERSION,
              description="계약 0.1.0-draft(Spring 측 제안) 기준 Mock. 실제 모델이 아니다.")
register_handlers(app)
guard = make_guard(lambda: os.environ.get(TOKEN_ENV, DEV_TOKEN), allow_mock_scenarios=True)


@app.post(P + "/embed", response_model=M.EmbedResponse, responses=ERR, tags=["embed"])
async def embed(req: M.EmbedRequest, _=Depends(guard)):
    return logic.embed(req)


@app.post(P + "/tokenize", response_model=M.TokenizeResponse, responses=ERR, tags=["tokenize"])
async def tokenize(req: M.TokenizeRequest, _=Depends(guard)):
    return logic.tokenize(req)


@app.post(P + "/hyde", response_model=M.HydeResponse, responses=ERR, tags=["hyde"])
async def hyde(req: M.HydeRequest, _=Depends(guard)):
    return logic.hyde(req)


@app.post(P + "/rerank", response_model=M.RerankResponse, responses=ERR, tags=["rerank"])
async def rerank(req: M.RerankRequest, _=Depends(guard)):
    return logic.rerank(req)


@app.post(P + "/verify", response_model=M.VerifyResponse, responses=ERR, tags=["verify"])
async def verify(req: M.VerifyRequest, scenario: str = Depends(guard)):
    return logic.verify(req, scenario)


@app.get(P + "/health/ready", response_model=M.ReadyOutput, responses={401: {"model": M.ErrorOut}, 503: {"model": M.ReadyOutput}}, tags=["health"])
async def ready(scenario: str = Depends(guard)):
    if scenario == "not_ready":
        return JSONResponse(status_code=503, content={"status": "NOT_READY", "models": [{"name": "mock-embed", "revision": None, "ready": False}]})
    return M.ReadyOutput(status="READY", models=[M.ModelState(name="mock-embed", revision=MOCK_REV, ready=True)])
