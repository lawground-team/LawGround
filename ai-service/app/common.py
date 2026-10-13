"""Mock 서버와 실제 서버가 함께 쓰는 계약 공통 부품: 오류 DTO, 요청 가드(인증·버전·본문), 예외 핸들러."""
import json
import os
from datetime import datetime, timezone
from typing import Callable, Optional
from uuid import UUID, uuid4

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.contract import models as M

TOKEN_ENV = "INTERNAL_API_TOKEN"             # 환경변수 '이름'만 문서화한다. 실값은 문서·노션·저장소에 쓰지 않는다
MAX_BODY = 8 * 1024 * 1024                   # [제안] 요청 본문 8 MiB (embed 32x30,000자 / verify 5x200,000자 UTF-8 기준)
P = "/internal/v1"
ERR = {400: {"model": M.ErrorOut}, 401: {"model": M.ErrorOut}, 409: {"model": M.ErrorOut}, 413: {"model": M.ErrorOut},
       422: {"model": M.ErrorOut}, 429: {"model": M.ErrorOut}, 500: {"model": M.ErrorOut},
       503: {"model": M.ErrorOut}, 504: {"model": M.ErrorOut}}


class ContractError(Exception):
    def __init__(self, http: int, code: M.ErrorCode, message: str, retryable: bool,
                 request_id: UUID | None = None, headers: dict | None = None):
        self.http, self.code, self.message, self.retryable = http, code, message, retryable
        self.request_id, self.headers = request_id, headers or {}


def request_id(request: Request) -> UUID:
    try:
        return UUID(request.headers.get("x-request-id", ""))
    except ValueError:
        return uuid4()


def error_response(http: int, code: M.ErrorCode, message: str, retryable: bool, rid: UUID, headers=None):
    body = M.ErrorOut(code=code, message=message[:500], requestId=rid,
                      timestamp=datetime.now(timezone.utc), retryable=retryable)
    return JSONResponse(status_code=http, content=json.loads(body.model_dump_json()), headers=headers or {})


async def contract_error_handler(request: Request, exc: ContractError):
    return error_response(exc.http, exc.code, exc.message, exc.retryable, exc.request_id or request_id(request), exc.headers)


async def validation_error_handler(request: Request, exc: RequestValidationError):
    # [제안] 본문이 JSON 이 아니면 400, 스키마 위반이면 422. 둘 다 code=INVALID_REQUEST, 재시도 안 함
    first = exc.errors()[0] if exc.errors() else {}
    http = 400 if first.get("type") in ("json_invalid", "model_attributes_type") else 422
    loc = ".".join(str(x) for x in first.get("loc", [])[1:])
    return error_response(http, M.ErrorCode.INVALID_REQUEST, f"입력 검증 실패: {loc} — {first.get('msg', '')}", False, request_id(request))


async def unexpected_error_handler(request: Request, exc: Exception):
    return error_response(500, M.ErrorCode.INTERNAL_ERROR, "내부 오류", False, request_id(request))


def register_handlers(app):
    app.add_exception_handler(ContractError, contract_error_handler)
    app.add_exception_handler(RequestValidationError, validation_error_handler)
    app.add_exception_handler(Exception, unexpected_error_handler)


def _reject_duplicate_keys(pairs):
    seen = set()
    for k, _ in pairs:
        if k in seen:
            raise ValueError(f"중복 JSON 키: {k}")
        seen.add(k)
    return dict(pairs)


MOCK_OPS = {
    "rate_limited": (429, M.ErrorCode.RATE_LIMITED, True, {"Retry-After": "2"}),
    "not_ready": (503, M.ErrorCode.MODEL_NOT_READY, True, {}),
    "timeout": (504, M.ErrorCode.MODEL_TIMEOUT, True, {}),
    "internal": (500, M.ErrorCode.INTERNAL_ERROR, False, {}),
}


def make_guard(token_getter: Callable[[], Optional[str]], allow_mock_scenarios: bool):
    """인증 → 계약 버전 → 본문 크기 → 중복 키·후행 JSON → 요청 ID 일치 → (Mock 만) 시나리오 순으로 확인한다.
    실제 서버는 토큰이 설정되지 않았으면 모든 요청을 거절하고(fail closed), X-Mock-Scenario 를 무시한다."""
    async def guard(request: Request):
        rid = request_id(request)
        token = token_getter()
        if not token or request.headers.get("x-internal-token") != token:
            raise ContractError(401, M.ErrorCode.UNAUTHORIZED, "서비스 인증에 실패했다", False, rid)
        scenario = request.headers.get("x-mock-scenario", "") if allow_mock_scenarios else ""
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
        if scenario in MOCK_OPS:
            http, code, retry, hdr = MOCK_OPS[scenario]
            raise ContractError(http, code, f"Mock 시나리오: {scenario}", retry, rid, hdr)
        return scenario
    return guard
