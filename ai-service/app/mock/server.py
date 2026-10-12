"""Python AI 서비스 Mock 서버 (S02) — 고정 응답이며 실제 모델이 아니다.

모든 응답의 modelRevision/promptVersion 이 'mock-*' 이므로 Spring 은 Mock 과 실제 AI 를 구분할 수 있다.
계약에 없는 X-Mock-Scenario 헤더로 오류·판정 시나리오를 고를 수 있다(Mock 전용, 계약 아님).
"""
import hashlib
import json
import math
import os
import random
import re
from datetime import datetime, timezone
from uuid import UUID, uuid4

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.contract import models as M

TOKEN_ENV = "INTERNAL_API_TOKEN"            # 환경변수 '이름'만 문서화한다. 실값은 문서·노션에 쓰지 않는다
DEV_TOKEN = "dev-mock-token"                # 로컬 Mock 전용 더미 값
MOCK_REV = "mock-embed-0"
MAX_BODY = 8 * 1024 * 1024                  # [제안] 요청 본문 8 MiB (embed 32x30,000자 / verify 5x200,000자 UTF-8 기준)

app = FastAPI(title="LawGround Python AI 내부 API (Mock)", version=M.CONTRACT_VERSION,
              description="계약 0.1.0-draft(Spring 측 제안) 기준 Mock. 실제 모델이 아니다.")


class ContractError(Exception):
    def __init__(self, http: int, code: M.ErrorCode, message: str, retryable: bool,
                 request_id: UUID | None = None, headers: dict | None = None):
        self.http, self.code, self.message, self.retryable = http, code, message, retryable
        self.request_id, self.headers = request_id, headers or {}


def _rid(request: Request) -> UUID:
    try:
        return UUID(request.headers.get("x-request-id", ""))
    except ValueError:
        return uuid4()


def _error_response(http: int, code: M.ErrorCode, message: str, retryable: bool, rid: UUID, headers=None):
    body = M.ErrorOut(code=code, message=message, requestId=rid,
                      timestamp=datetime.now(timezone.utc), retryable=retryable)
    return JSONResponse(status_code=http, content=json.loads(body.model_dump_json()), headers=headers or {})


@app.exception_handler(ContractError)
async def _contract_error(request: Request, exc: ContractError):
    return _error_response(exc.http, exc.code, exc.message, exc.retryable, exc.request_id or _rid(request), exc.headers)


@app.exception_handler(RequestValidationError)
async def _validation_error(request: Request, exc: RequestValidationError):
    # [제안] 본문이 JSON 이 아니면 400, 스키마 위반이면 422. 둘 다 code=INVALID_REQUEST, 재시도 안 함
    first = exc.errors()[0] if exc.errors() else {}
    http = 400 if first.get("type") in ("json_invalid", "model_attributes_type") else 422
    loc = ".".join(str(x) for x in first.get("loc", [])[1:])
    return _error_response(http, M.ErrorCode.INVALID_REQUEST, f"입력 검증 실패: {loc} — {first.get('msg', '')}"[:500],
                           False, _rid(request))


def _reject_duplicate_keys(pairs):
    seen = set()
    for k, _ in pairs:
        if k in seen:
            raise ValueError(f"중복 JSON 키: {k}")
        seen.add(k)
    return dict(pairs)


async def guard(request: Request):
    """인증 → 계약 버전 → 본문 크기 → 중복 키·후행 JSON → 요청 ID 일치 → Mock 시나리오 순으로 확인한다."""
    rid = _rid(request)
    token = os.environ.get(TOKEN_ENV, DEV_TOKEN)
    if request.headers.get("x-internal-token") != token:
        raise ContractError(401, M.ErrorCode.UNAUTHORIZED, "서비스 인증에 실패했다", False, rid)
    scenario = request.headers.get("x-mock-scenario", "")
    if request.method == "GET":
        return scenario
    if request.headers.get("x-contract-version") != M.CONTRACT_VERSION:
        raise ContractError(400, M.ErrorCode.UNSUPPORTED_CONTRACT_VERSION,
                            f"지원하는 계약 버전은 {M.CONTRACT_VERSION} 이다", False, rid)
    raw = await request.body()
    if len(raw) > MAX_BODY:
        raise ContractError(413, M.ErrorCode.PAYLOAD_TOO_LARGE, "요청 본문이 상한을 넘었다", False, rid)
    try:
        body = json.loads(raw, object_pairs_hook=_reject_duplicate_keys)
    except ValueError as e:
        raise ContractError(400, M.ErrorCode.INVALID_REQUEST, f"JSON 파싱 실패: {e}"[:500], False, rid)
    body_rid = body.get("requestId") if isinstance(body, dict) else None
    if body_rid is not None and str(body_rid) != request.headers.get("x-request-id"):
        raise ContractError(400, M.ErrorCode.INVALID_REQUEST,
                            "X-Request-Id 헤더와 본문 requestId 가 일치해야 한다", False, rid)
    ops = {
        "rate_limited": (429, M.ErrorCode.RATE_LIMITED, True, {"Retry-After": "2"}),
        "not_ready": (503, M.ErrorCode.MODEL_NOT_READY, True, {}),
        "timeout": (504, M.ErrorCode.MODEL_TIMEOUT, True, {}),
        "internal": (500, M.ErrorCode.INTERNAL_ERROR, False, {}),
    }
    if scenario in ops:
        http, code, retry, hdr = ops[scenario]
        raise ContractError(http, code, f"Mock 시나리오: {scenario}", retry, rid, hdr)
    return scenario


ERR = {400: {"model": M.ErrorOut}, 401: {"model": M.ErrorOut}, 413: {"model": M.ErrorOut},
       422: {"model": M.ErrorOut}, 429: {"model": M.ErrorOut}, 500: {"model": M.ErrorOut},
       503: {"model": M.ErrorOut}, 504: {"model": M.ErrorOut}}
P = "/internal/v1"


def _vec(text: str) -> list[float]:
    rng = random.Random(int.from_bytes(hashlib.sha256(text.encode("utf-8")).digest()[:8], "big"))
    v = [rng.uniform(-1, 1) for _ in range(1024)]
    n = math.sqrt(sum(x * x for x in v))
    return [x / n for x in v]


_TOK = re.compile(r"제\d+조(?:의\d+)?|제\d+항|제\d+호|\d+(?:[.,]\d+)?[가-힣]*|[가-힣A-Za-z]+")


@app.post(P + "/embed", response_model=M.EmbedResponse, responses=ERR, tags=["embed"])
async def embed(req: M.EmbedRequest, _=Depends(guard)):
    return M.EmbedResponse(items=[M.EmbedItem(id=t.id, vector=_vec(t.text)) for t in req.texts],
                           dim=1024, modelRevision=req.modelRevision)


@app.post(P + "/tokenize", response_model=M.TokenizeResponse, responses=ERR, tags=["tokenize"])
async def tokenize(req: M.TokenizeRequest, _=Depends(guard)):
    # Mock 토큰화: 형태소 분석이 아니라 정규식 분리다. 조문 번호·숫자+단위는 한 토큰으로 유지한다
    return M.TokenizeResponse(items=[M.TokenItem(id=t.id, tokens=_TOK.findall(t.text)[:10000]) for t in req.texts],
                              tokenizerVersion=req.tokenizerVersion)


@app.post(P + "/hyde", response_model=M.HydeResponse, responses=ERR, tags=["hyde"])
async def hyde(req: M.HydeRequest, _=Depends(guard)):
    q = req.questionSnapshot
    return M.HydeResponse(hypotheticalText=f"(Mock 가상 문서) {q.stem[:200]}", searchTerms=_TOK.findall(q.stem)[:5] or ["mock"],
                          modelRevision="mock-llm-0", promptVersion="mock-hyde-0")


@app.post(P + "/rerank", response_model=M.RerankResponse, responses=ERR, tags=["rerank"])
async def rerank(req: M.RerankRequest, _=Depends(guard)):
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


@app.post(P + "/verify", response_model=M.VerifyResponse, responses=ERR, tags=["verify"])
async def verify(req: M.VerifyRequest, scenario: str = Depends(guard)):
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


@app.get(P + "/health/ready", response_model=M.ReadyOutput, responses={401: {"model": M.ErrorOut}, 503: {"model": M.ReadyOutput}}, tags=["health"])
async def ready(scenario: str = Depends(guard)):
    if scenario == "not_ready":
        return JSONResponse(status_code=503, content={"status": "NOT_READY", "models": [{"name": "mock-embed", "revision": None, "ready": False}]})
    return M.ReadyOutput(status="READY", models=[M.ModelState(name="mock-embed", revision=MOCK_REV, ready=True)])
