"""Python AI 서비스 내부 API 계약 모델 (계약 버전 0.1.0-draft 기준).

출처: 윤영주 님의 협의 요청서(2026-10-05)에 요약된 Spring 측 제안.
레포의 docs/api/internal.openapi.yaml 원본이 아니라 요약을 옮긴 것이므로,
scripts/export_openapi.py 로 생성한 OpenAPI 와 원본을 반드시 대조해야 한다.
[제안] 표시는 수종(Python) 측 수정 제안이며 아직 합의되지 않았다.
"""
import math
from datetime import date, datetime
from enum import Enum
from typing import Annotated, Optional
from uuid import UUID

from pydantic import (AfterValidator, BaseModel, ConfigDict, Field, StrictInt,
                      StrictStr, model_validator)

CONTRACT_VERSION = "0.1.0-draft"


def _nonblank(v: str) -> str:
    if not v.strip():
        raise ValueError("공백만 있는 문자열은 허용하지 않는다")
    return v


def _text(max_len: int):
    return Annotated[StrictStr, Field(min_length=1, max_length=max_len), AfterValidator(_nonblank)]


def _short(max_len: int):
    return Annotated[StrictStr, Field(min_length=1, max_length=max_len)]


Version = _short(160)          # modelRevision / promptVersion / tokenizerVersion (초안 1~160자)
FiniteFloat = Annotated[float, Field(strict=True)]


class Strict(BaseModel):
    """추가 속성 금지. 정수 필드는 StrictInt 로 받아 float→int 변환을 막는다."""
    model_config = ConfigDict(extra="forbid")


class ErrorCode(str, Enum):
    INVALID_REQUEST = "INVALID_REQUEST"            # 초안에 있는 코드
    RATE_LIMITED = "RATE_LIMITED"
    MODEL_NOT_READY = "MODEL_NOT_READY"
    MODEL_TIMEOUT = "MODEL_TIMEOUT"
    INTERNAL_ERROR = "INTERNAL_ERROR"
    UNAUTHORIZED = "UNAUTHORIZED"                  # [제안] 협의 전 — 초안에 없어서 추가가 필요하다고 보는 코드(C09)
    UNSUPPORTED_CONTRACT_VERSION = "UNSUPPORTED_CONTRACT_VERSION"
    MODEL_REVISION_MISMATCH = "MODEL_REVISION_MISMATCH"
    INPUT_TOO_LONG = "INPUT_TOO_LONG"
    PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE"
    MODEL_OUTPUT_INVALID = "MODEL_OUTPUT_INVALID"
    MODEL_UPSTREAM_ERROR = "MODEL_UPSTREAM_ERROR"      # [제안] 외부 LLM/API 장애. MODEL_NOT_READY(로딩 중)와 구분
    IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"


class ErrorOut(Strict):
    code: ErrorCode
    message: _short(500)
    requestId: UUID
    timestamp: datetime
    retryable: bool


class IdText(Strict):
    id: UUID
    text: _text(30000)


class EmbedRequest(Strict):
    requestId: UUID
    texts: Annotated[list[IdText], Field(min_length=1, max_length=32)]
    modelRevision: Version

    @model_validator(mode="after")
    def _unique_ids(self):
        ids = [t.id for t in self.texts]
        if len(set(ids)) != len(ids):
            raise ValueError("texts 의 id 가 중복됐다")
        return self


class EmbedItem(Strict):
    id: UUID
    vector: Annotated[list[FiniteFloat], Field(min_length=1024, max_length=1024)]

    @model_validator(mode="after")
    def _finite_nonzero(self):
        if any(not math.isfinite(x) for x in self.vector):
            raise ValueError("NaN/Infinity 금지")
        if all(x == 0.0 for x in self.vector):
            raise ValueError("영벡터 금지")
        return self


class EmbedResponse(Strict):
    items: Annotated[list[EmbedItem], Field(min_length=1, max_length=32)]
    dim: Annotated[StrictInt, Field(ge=1024, le=1024)]
    modelRevision: Version


class TokenizeRequest(Strict):
    requestId: UUID
    texts: Annotated[list[IdText], Field(min_length=1, max_length=32)]
    tokenizerVersion: Version


class TokenItem(Strict):
    id: UUID
    tokens: Annotated[list[_short(200)], Field(max_length=10000)]


class TokenizeResponse(Strict):
    items: Annotated[list[TokenItem], Field(min_length=1, max_length=32)]
    tokenizerVersion: Version


class QuestionType(str, Enum):
    SELECT_CORRECT = "SELECT_CORRECT"      # 옳은 것을 고르는 문제
    SELECT_INCORRECT = "SELECT_INCORRECT"  # 옳지 않은 것을 고르는 문제


class QuestionChoice(Strict):
    number: Annotated[StrictInt, Field(ge=1, le=5)]
    text: _text(2000)


class QuestionSnapshot(Strict):
    subject: Annotated[StrictStr, Field(pattern="^(BROKER_LAW|PUBLIC_LAW)$")]  # 1과목 공인중개사법령, 2과목 부동산공법(2026-11-09 추가)
    stem: _text(10000)
    questionType: QuestionType
    choices: Annotated[list[QuestionChoice], Field(min_length=5, max_length=5)]
    providedAnswerNumber: Annotated[StrictInt, Field(ge=1, le=5)]
    referenceDate: date
    revision: Annotated[StrictInt, Field(ge=1)]

    @model_validator(mode="after")
    def _five_choices_once(self):
        if sorted(c.number for c in self.choices) != [1, 2, 3, 4, 5]:
            raise ValueError("선지 번호 1~5 가 각각 정확히 한 번씩 있어야 한다")
        return self


class HydeRequest(Strict):
    requestId: UUID
    questionSnapshot: QuestionSnapshot
    referenceDate: date

    @model_validator(mode="after")
    def _same_date(self):
        if self.referenceDate != self.questionSnapshot.referenceDate:
            raise ValueError("referenceDate 와 questionSnapshot.referenceDate 가 달라서는 안 된다")
        return self


class HydeResponse(Strict):
    hypotheticalText: _text(20000)
    searchTerms: Annotated[list[_short(200)], Field(max_length=20)]
    modelRevision: Version
    promptVersion: Version


class RerankCandidate(Strict):
    chunkId: UUID
    text: _text(30000)


class RerankRequest(Strict):
    requestId: UUID
    query: _text(30000)
    candidates: Annotated[list[RerankCandidate], Field(min_length=1, max_length=30)]
    topK: Annotated[StrictInt, Field(ge=1, le=5)]

    @model_validator(mode="after")
    def _unique(self):
        ids = [c.chunkId for c in self.candidates]
        if len(set(ids)) != len(ids):
            raise ValueError("candidates 의 chunkId 가 중복됐다")
        return self


class RerankItem(Strict):
    chunkId: UUID
    score: FiniteFloat


class RerankResponse(Strict):
    ranked: Annotated[list[RerankItem], Field(min_length=1, max_length=5)]
    modelRevision: Version

    @model_validator(mode="after")
    def _desc_unique(self):
        ids = [r.chunkId for r in self.ranked]
        if len(set(ids)) != len(ids):
            raise ValueError("ranked 에 중복 chunkId 금지")
        scores = [r.score for r in self.ranked]
        if any(not math.isfinite(s) for s in scores):
            raise ValueError("score 는 유한한 수여야 한다")
        if scores != sorted(scores, reverse=True):
            raise ValueError("ranked 는 score 내림차순이어야 한다")
        return self


class CandidateMetadata(Strict):
    articleId: UUID
    lawVersionId: UUID
    lawTitle: _short(200)
    articleLabel: _short(200)
    effectiveFrom: date
    effectiveTo: Optional[date]          # required + nullable


class VerifyCandidate(Strict):
    chunkId: UUID
    sourceText: _text(200000)
    metadata: CandidateMetadata


class VerifyRequest(Strict):
    requestId: UUID
    questionSnapshot: QuestionSnapshot
    referenceDate: date
    candidates: Annotated[list[VerifyCandidate], Field(min_length=1, max_length=5)]

    @model_validator(mode="after")
    def _same_date_unique(self):
        if self.referenceDate != self.questionSnapshot.referenceDate:
            raise ValueError("referenceDate 와 questionSnapshot.referenceDate 가 달라서는 안 된다")
        ids = [c.chunkId for c in self.candidates]
        if len(set(ids)) != len(ids):
            raise ValueError("candidates 의 chunkId 가 중복됐다")
        return self


class Verdict(str, Enum):
    SUPPORTED = "SUPPORTED"
    HOLD = "HOLD"
    REJECTED = "REJECTED"


class Relation(str, Enum):
    SUPPORTS = "SUPPORTS"
    CONTRADICTS = "CONTRADICTS"
    INSUFFICIENT = "INSUFFICIENT"


class Evidence(Strict):
    evidenceKey: _short(80)
    chunkId: UUID
    quote: _text(20000)
    start: Annotated[StrictInt, Field(ge=0)]     # Unicode code point, 시작 포함
    end: Annotated[StrictInt, Field(ge=0)]       # Unicode code point, 끝 제외

    @model_validator(mode="after")
    def _order(self):
        if not self.start < self.end:
            raise ValueError("start < end 여야 한다")
        if self.end - self.start != len(self.quote):
            raise ValueError("end-start 는 quote 의 code point 길이와 같아야 한다")
        return self


class Tag(Strict):
    code: _short(80)


class VerifyChoice(Strict):
    choiceNumber: Annotated[StrictInt, Field(ge=1, le=5)]
    relation: Relation
    explanation: _text(10000)
    correctedText: Optional[Annotated[StrictStr, Field(min_length=1, max_length=2000)]]
    tags: Annotated[list[Tag], Field(max_length=20)]
    evidenceKeys: Annotated[list[_short(80)], Field(max_length=20)]

    @model_validator(mode="after")
    def _unique_keys(self):
        if len(set(self.evidenceKeys)) != len(self.evidenceKeys):
            raise ValueError("evidenceKeys 중복 금지")
        return self


class VerifyResponse(Strict):
    verdict: Verdict
    reasonCode: _short(80)
    summary: _text(10000)
    evidences: Annotated[list[Evidence], Field(max_length=50)]
    choiceAnalyses: Optional[Annotated[list[VerifyChoice], Field(min_length=5, max_length=5)]]  # null 또는 정확히 5개
    tagSetVersion: Optional[_short(80)]                                                       # required + nullable
    modelRevision: Version
    promptVersion: Version

    @model_validator(mode="after")
    def _consistency(self):
        keys = [e.evidenceKey for e in self.evidences]
        if len(set(keys)) != len(keys):
            raise ValueError("evidenceKey 는 응답 안에서 유일해야 한다")
        if self.verdict == Verdict.SUPPORTED and not self.evidences:
            raise ValueError("SUPPORTED 는 실제 근거(evidences)가 필요하다")
        if self.choiceAnalyses is not None:
            if sorted(c.choiceNumber for c in self.choiceAnalyses) != [1, 2, 3, 4, 5]:
                raise ValueError("choiceAnalyses 는 choiceNumber 1~5 가 각각 한 번씩 있어야 한다")
            for c in self.choiceAnalyses:
                for k in c.evidenceKeys:
                    if k not in keys:
                        raise ValueError(f"choiceAnalyses 가 존재하지 않는 evidenceKey 를 참조한다: {k}")
                if c.correctedText is not None and c.relation != Relation.CONTRADICTS:
                    raise ValueError("correctedText 는 CONTRADICTS 일 때만 허용한다 [제안]")
                if c.tags and self.tagSetVersion is None:
                    raise ValueError("tags 가 있으면 tagSetVersion 이 필요하다 [제안]")
        return self


class ReadyStatus(str, Enum):
    READY = "READY"
    NOT_READY = "NOT_READY"


class ModelState(Strict):
    name: _short(200)
    revision: Optional[_short(160)]
    ready: bool


class ReadyOutput(Strict):
    status: ReadyStatus
    models: Annotated[list[ModelState], Field(max_length=20)]


# ---------------------------------------------------------------------------
# 0.2.0-draft (제안, docs/api/proposal-0.2.0-statement-and-similar.md). 새 경로만 이 버전 헤더를 받는다. 기존 0.1.0 경로는 그대로.
# ---------------------------------------------------------------------------
CONTRACT_VERSION_02 = "0.2.0-draft"
Subject = Annotated[StrictStr, Field(pattern="^(BROKER_LAW|PUBLIC_LAW)$")]
TAG_CODES = ("TIMING", "CONDITION", "ACTOR", "CONCEPT", "NUMBER")     # choice-tags-v1 (2026-11-09 확정)


def _tag_code(v: str) -> str:
    if v not in TAG_CODES:
        raise ValueError(f"태그 코드는 {TAG_CODES} 중 하나다")
    return v


TagCode = Annotated[StrictStr, AfterValidator(_tag_code)]


class StatementVerdict(str, Enum):
    CONSISTENT = "CONSISTENT"        # 조문과 일치
    INCONSISTENT = "INCONSISTENT"    # 조문과 어긋남
    HOLD = "HOLD"                    # 판단 보류


STATEMENT_REASONS = {StatementVerdict.CONSISTENT: {"SUFFICIENT_EVIDENCE"}, StatementVerdict.INCONSISTENT: {"CONTRADICTED_BY_LAW"},
                     StatementVerdict.HOLD: {"INSUFFICIENT_EVIDENCE", "NO_VERIFIED_QUOTE", "INCONSISTENT_ANALYSIS"}}


class StatementInput(Strict):
    subject: Subject
    text: _text(2000)
    contextStem: Optional[Annotated[StrictStr, Field(max_length=500)]]     # required + nullable
    referenceDate: date


class VerifyStatementRequest(Strict):
    requestId: UUID
    statement: StatementInput
    referenceDate: date
    candidates: Annotated[list[VerifyCandidate], Field(min_length=1, max_length=5)]

    @model_validator(mode="after")
    def _same_date_unique(self):
        if self.referenceDate != self.statement.referenceDate:
            raise ValueError("referenceDate 와 statement.referenceDate 가 달라서는 안 된다")
        ids = [c.chunkId for c in self.candidates]
        if len(set(ids)) != len(ids):
            raise ValueError("candidates 의 chunkId 가 중복됐다")
        return self


class WrongSpan(Strict):
    statementStart: Annotated[StrictInt, Field(ge=0)]
    statementEnd: Annotated[StrictInt, Field(ge=1)]
    lawExpression: _text(500)
    evidenceKey: _short(80)


class StatementTag(Strict):
    code: TagCode


class VerifyStatementResponse(Strict):
    verdict: StatementVerdict
    reasonCode: _short(80)
    summary: _text(10000)
    evidences: Annotated[list[Evidence], Field(max_length=50)]
    wrongSpans: Annotated[list[WrongSpan], Field(max_length=10)]
    correctedStatement: Optional[_text(2000)]
    tags: Annotated[list[StatementTag], Field(max_length=5)]
    tagSetVersion: Optional[_short(80)]
    modelRevision: Version
    promptVersion: Version

    @model_validator(mode="after")
    def _consistency(self):
        keys = [e.evidenceKey for e in self.evidences]
        if len(set(keys)) != len(keys):
            raise ValueError("evidenceKey 는 응답 안에서 유일해야 한다")
        if self.reasonCode not in STATEMENT_REASONS[self.verdict]:
            raise ValueError(f"{self.verdict.value} 에 허용되지 않는 reasonCode: {self.reasonCode}")
        if self.verdict != StatementVerdict.HOLD and not self.evidences:
            raise ValueError("CONSISTENT/INCONSISTENT 는 근거가 1개 이상 필요하다")
        if self.verdict == StatementVerdict.INCONSISTENT:
            if not self.wrongSpans or self.correctedStatement is None:
                raise ValueError("INCONSISTENT 는 wrongSpans 1개 이상과 correctedStatement 가 필요하다")
        elif self.wrongSpans or self.correctedStatement is not None or self.tags:
            raise ValueError("CONSISTENT/HOLD 는 wrongSpans=[], correctedStatement=null, tags=[] 여야 한다")
        for w in self.wrongSpans:
            if not w.statementStart < w.statementEnd:
                raise ValueError("statementStart < statementEnd 여야 한다")
            if w.evidenceKey not in keys:
                raise ValueError(f"wrongSpans 가 존재하지 않는 evidenceKey 를 참조한다: {w.evidenceKey}")
        if self.tags and self.tagSetVersion is None:
            raise ValueError("tags 가 있으면 tagSetVersion 이 필요하다")
        return self


class Truth(str, Enum):
    TRUE = "TRUE"          # 기출에서 맞는 문장
    FALSE = "FALSE"        # 틀린 문장
    UNKNOWN = "UNKNOWN"    # 조합형 등 자동 판정 불가


class VariationItemIn(Strict):
    itemId: _short(80)
    text: _text(2000)
    truth: Truth


class VariationDiffRequest(Strict):
    requestId: UUID
    subject: Subject
    text: _text(2000)
    items: Annotated[list[VariationItemIn], Field(min_length=1, max_length=5)]

    @model_validator(mode="after")
    def _unique(self):
        ids = [i.itemId for i in self.items]
        if len(set(ids)) != len(ids):
            raise ValueError("items 의 itemId 가 중복됐다")
        return self


class DiffPart(Strict):
    type: TagCode
    userStart: Annotated[StrictInt, Field(ge=0)]       # 사용자 문장 [userStart, userEnd) code point. 기출에만 있는 말이면 빈 구간(start==end)
    userEnd: Annotated[StrictInt, Field(ge=0)]
    itemStart: Annotated[StrictInt, Field(ge=0)]
    itemEnd: Annotated[StrictInt, Field(ge=0)]
    userText: Annotated[StrictStr, Field(max_length=2000)]
    itemText: Annotated[StrictStr, Field(max_length=2000)]

    @model_validator(mode="after")
    def _order(self):
        if self.userStart > self.userEnd or self.itemStart > self.itemEnd or (self.userStart == self.userEnd and self.itemStart == self.itemEnd):
            raise ValueError("diff 구간은 start ≤ end 이고, 두 쪽이 모두 비어서는 안 된다")
        if len(self.userText) != self.userEnd - self.userStart or len(self.itemText) != self.itemEnd - self.itemStart:
            raise ValueError("userText/itemText 길이는 구간 길이와 같아야 한다")
        return self


class VariationItemOut(Strict):
    itemId: _short(80)
    similarity: Annotated[float, Field(strict=True, ge=0, le=1)]
    diff: Annotated[list[DiffPart], Field(max_length=10)]


class StudyTip(Strict):
    categories: Annotated[list[TagCode], Field(min_length=1, max_length=2)]
    text: _text(1000)


class VariationDiffResponse(Strict):
    items: Annotated[list[VariationItemOut], Field(min_length=1, max_length=5)]     # 요청 순서 그대로
    tip: Optional[StudyTip]
    tagSetVersion: _short(80)
    algorithmVersion: Version
