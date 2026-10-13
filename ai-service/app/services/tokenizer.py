"""PostgreSQL 단어 검색(tsvector)용 한국어 토큰화 — Kiwi(kiwipiepy) + 정규식 선추출. rules-1.

규칙 (문서와 질의에 동일하게 적용):
  1. Unicode NFC 정규화
  2. 조문 번호(제N조, 제N조의M, 제N항, 제N호)와 숫자+단위(30일, 3개월 ...)는 **정규식으로 먼저 뽑아 한 토큰**으로 만든다.
     Kiwi 단독으로는 `제/32/조`, `30/일` 로 쪼개기 때문이다.
  3. 나머지는 Kiwi 로 분석해 내용 형태소만 남긴다: 명사·대명사·수사, 용언 어간(VV/VA/VX/VCN), 관형사·부사, 어근, 외국어·한자·숫자.
     조사(J*), 어미(E*), 접사(XPN/XSN/XSV/XSA), 지정사 '이다'(VCP), 기호(S*) 는 버린다.
  4. 부정어(없·않·못·아니)는 용언 어간이라 보존된다. 불용어 사전은 쓰지 않는다.
  5. 순서와 중복을 보존한다 (ts_rank_cd 위치 정보).
  6. 200자를 넘는 토큰은 200자 단위로 나눈다. 토큰이 10,000개를 넘으면 자르지 않고 TokenLimitExceeded 를 낸다.
도메인 용어 사전(개업공인중개사 등)은 rules-1 에 넣지 않았다. 넣으면 rules-2 로 올린다.
"""
import re
import threading
import unicodedata

import kiwipiepy
from kiwipiepy import Kiwi

TOKENIZER_VERSION = f"kiwipiepy-{kiwipiepy.__version__}+rules-1"
MAX_TOKENS = 10_000
MAX_TOKEN_LEN = 200

_ARTICLE = r"제\d+조(?:의\d+)?|제\d+항|제\d+호"
_UNITS = ("개월", "만원", "억원", "시간", "일", "년", "월", "주", "세", "명", "인", "개", "회", "건", "원", "%", "㎡")
_NUMUNIT = r"\d+(?:[.,]\d+)?(?:" + "|".join(re.escape(u) for u in _UNITS) + ")"
_SPAN = re.compile(f"(?:{_ARTICLE})|(?:{_NUMUNIT})")
_KEEP = {"NNG", "NNP", "NNB", "NR", "NP", "VV", "VA", "VX", "VCN", "MM", "MAG", "MAJ", "XR", "SL", "SH", "SN"}

_kiwi = None
_init_lock = threading.Lock()    # 초기화용
_infer_lock = threading.Lock()   # 추론용 (두 락은 서로 다른 락이라 재진입 교착이 없다)


class TokenLimitExceeded(Exception):
    def __init__(self, count: int):
        super().__init__(f"토큰 {count}개가 상한 {MAX_TOKENS} 을 넘었다")
        self.count = count


def _get_kiwi() -> Kiwi:
    global _kiwi
    if _kiwi is None:
        with _init_lock:
            if _kiwi is None:
                _kiwi = Kiwi()
    return _kiwi


def _morphemes(segment: str) -> list[str]:
    if not segment.strip():
        return []
    kiwi = _get_kiwi()
    with _infer_lock:
        tokens = kiwi.tokenize(segment)
    return [t.form for t in tokens if t.tag.split("-")[0] in _KEEP]


def tokenize(text: str) -> list[str]:
    text = unicodedata.normalize("NFC", text)
    out: list[str] = []
    pos = 0
    for m in _SPAN.finditer(text):
        out += _morphemes(text[pos:m.start()])
        out.append(m.group(0))
        pos = m.end()
    out += _morphemes(text[pos:])
    split: list[str] = []
    for tok in out:
        if len(tok) <= MAX_TOKEN_LEN:
            split.append(tok)
        else:
            split += [tok[i:i + MAX_TOKEN_LEN] for i in range(0, len(tok), MAX_TOKEN_LEN)]
    if len(split) > MAX_TOKENS:
        raise TokenLimitExceeded(len(split))
    return split
