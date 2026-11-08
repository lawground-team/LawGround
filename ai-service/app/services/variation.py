"""/variation-diff: 사용자 문장과 기출 선지를 비교해 바뀐 구간과 변형 유형(태그 5종), 공부 팁을 만든다. LLM·모델 없이 규칙만 쓴다(빠르고 결정적).

방법(variation-v1)
  1. 두 문장에서 띄어쓰기·문장부호를 빼고 글자 단위로 비교한다(difflib). 유사도가 MIN_SIMILARITY 미만이면 다른 문장으로 보고 구간을 내지 않는다.
  2. 다른 글자 구간을 원문 위치로 되돌리고 단어(공백) 경계까지 넓힌다. 겹치면 합친다. 위치는 code point [start, end).
  3. 구간마다 사전으로 유형을 정한다(우선순위: 숫자 → 주체·권한 → 시점·기한 → 조건 → 개념 오인).
     숫자 뒤가 기간 단위(년·개월·일…)거나 기한 표현이면 시점·기한, 아니면 숫자·수치.
한계(숨기지 않는다): 형태소 분석을 하지 않아 조사까지 한 단어로 보여 준다("장관은" ↔ "시장은"). 사전에 없는 말은 개념 오인으로 떨어진다.
'2' ↔ '둘' 같은 같은 뜻 다른 표기는 차이로 잡힌다. 옛 회차(25~26회) 기출은 PDF 추출 잡음이 남아 있을 수 있다.
ponytail: 사전 규칙 분류. 정확도가 모자라면 Kiwi 형태소 + 사전 확장, 그다음 LLM 분류로 올린다.
"""
from __future__ import annotations

import difflib
import re
from collections import Counter

from app.contract import models as M

ALGORITHM_VERSION = "variation-v1"
MIN_SIMILARITY = 0.55         # 글자 유사도가 이보다 낮으면 "바꿔 낸 문장"이 아니라 다른 문장이라 비교 구간을 내지 않는다(25~36회 기출로 눈으로 본 값)
MAX_PARTS_FOR_TIP = 3         # 바뀐 곳이 이보다 많으면 출제 변형보다 표현을 바꿔 쓴 문장에 가깝다. 화면 비교는 하되 공부 팁 집계에서는 뺀다
TAG_SET_VERSION = "choice-tags-v1"
LABEL = {"TIMING": "시점·기한", "CONDITION": "조건 추가", "ACTOR": "주체·권한", "CONCEPT": "개념 오인", "NUMBER": "숫자·수치 오류"}
ADVICE = {
    "TIMING": "기간·기한 숫자와 '이내·부터·다음 날·마다' 같은 기준점을 조문 원문 그대로 외우세요.",
    "NUMBER": "금액·비율·면적·횟수는 헷갈리기 쉬운 다른 숫자와 짝지어 외우세요.",
    "ACTOR": "누가 하는지(장관·시·도지사·시장·군수 등)와 인가·허가·신고·승인 중 무엇인지 구분하세요.",
    "CONDITION": "'하여야/할 수 있다', '이상/초과', 단서·예외처럼 조건을 바꾸는 표현을 확인하세요.",
    "CONCEPT": "비슷한 제도·용어(예: 재건축과 재개발)의 정의를 나란히 놓고 비교하세요.",
}

_DIGIT = re.compile(r"\d|분의|[일이삼사오육칠팔구십백천만]+(?:분의|배|할)")
_TIME_AFTER_NUM = re.compile(r"\d+(?:[.,]\d+)*\s*(?:년|개월|월|주|일|시간|날)")
_TIMING = ("이내", "이전", "이후", "까지", "부터", "마다", "기한", "기간", "지체", "즉시", "다음 날", "다음날", "만료", "경과")
_TIMING_TOKENS = {"전", "후", "전에", "후에", "날", "날부터", "날까지"}
_ACTOR = ("장관", "시·도지사", "시도지사", "도지사", "시장", "군수", "구청장", "위원회", "조합", "공인중개사", "등록관청", "행정청", "지방자치단체",
          "대통령", "법원", "시행자", "소유자", "세입자", "임차인", "임대인", "중개보조원", "협회", "공사", "인가", "허가", "신고", "승인", "협의", "심의", "동의", "지정권자")
_CONDITION = ("하여야", "해야", "할 수", "수 있", "수 없", "없다", "없는", "아니", "않", "이상", "초과", "미만", "이하", "경우", "다만", "예외", "제외", "포함",
              "모두", "또는", "반드시", "원칙", "뿐", "한정")


def _norm(s: str) -> tuple[str, list[int]]:
    """띄어쓰기·문장부호를 뺀 글자열과, 각 글자의 원문 위치. PDF 추출본의 띄어쓰기 차이('인 장' ↔ '인장', 「」)를 변형으로 보지 않기 위해서다."""
    idx = [i for i, ch in enumerate(s) if ch.isalnum() or ch in "㎡%"]
    return "".join(s[i] for i in idx), idx


def _span(s: str, idx: list[int], a: int, b: int) -> tuple[int, int]:
    """정규화 글자 구간 [a,b) → 원문 구간. 읽기 쉽게 단어(공백) 경계까지 넓힌다. 빈 구간은 단어 가운데일 때만 그 단어로 넓힌다."""
    if a < b:
        lo, hi = idx[a], idx[b - 1] + 1
    else:
        lo = hi = idx[a] if a < len(idx) else len(s)
        if not (0 < lo < len(s) and not s[lo - 1].isspace() and not s[lo].isspace()):
            return lo, hi
    while lo > 0 and not s[lo - 1].isspace():
        lo -= 1
    while hi < len(s) and not s[hi].isspace():
        hi += 1
    return lo, hi


def classify(user_part: str, item_part: str) -> str:
    both = f"{user_part} {item_part}"
    if _DIGIT.search(both):
        return "TIMING" if _TIME_AFTER_NUM.search(both) or any(w in both for w in _TIMING) else "NUMBER"
    if any(w in both for w in _ACTOR):
        return "ACTOR"
    if any(w in both for w in _TIMING) or any(t in _TIMING_TOKENS for t in both.split()):
        return "TIMING"
    if any(w in both for w in _CONDITION):
        return "CONDITION"
    return "CONCEPT"


def diff(user: str, item: str, limit: int = 10) -> tuple[float, list[M.DiffPart]]:
    (un, ui), (inn, ii) = _norm(user), _norm(item)
    sm = difflib.SequenceMatcher(None, un, inn, autojunk=False)
    ratio = round(sm.ratio(), 4)
    if ratio < MIN_SIMILARITY:
        return ratio, []
    spans: list[list[int]] = []
    for op, a1, a2, b1, b2 in sm.get_opcodes():
        if op == "equal":
            continue
        (us, ue), (ist, ie) = _span(user, ui, a1, a2), _span(item, ii, b1, b2)
        if spans and (us <= spans[-1][1] or ist <= spans[-1][3]):          # 단어로 넓히다 겹치면 하나로 합친다
            m = spans[-1]
            spans[-1] = [min(m[0], us), max(m[1], ue), min(m[2], ist), max(m[3], ie)]
        else:
            spans.append([us, ue, ist, ie])
    parts = [M.DiffPart(type=classify(user[us:ue], item[ist:ie]), userStart=us, userEnd=ue, itemStart=ist, itemEnd=ie,
                        userText=user[us:ue], itemText=item[ist:ie]) for us, ue, ist, ie in spans]
    return ratio, parts[:limit]


def tip(items: list[M.VariationItemIn], outs: list[M.VariationItemOut]) -> M.StudyTip | None:
    focused = [o for o in outs if 0 < len(o.diff) <= MAX_PARTS_FOR_TIP]
    counts = Counter(p.type for o in focused for p in o.diff)
    if not counts:
        return None
    top = [c for c, _ in counts.most_common(2)]
    seen = sum(1 for o in focused if any(p.type == top[0] for p in o.diff))
    compared = len(focused)
    text = (f"비슷한 기출 {compared}개 중 {seen}개에서 '{LABEL[top[0]]}'" + (f", 그다음으로 '{LABEL[top[1]]}'" if len(top) > 1 else "")
            + " 부분이 바뀌어 출제됐습니다. " + " ".join(ADVICE[c] for c in top))
    true_item = next((i for i in items if i.truth == M.Truth.TRUE), None)
    if true_item:
        text += f" 맞는 표현(기출 {true_item.itemId}): \"{true_item.text[:300]}\""
    return M.StudyTip(categories=top, text=text[:1000])


def variation_diff(req: M.VariationDiffRequest) -> M.VariationDiffResponse:
    outs = []
    for i in req.items:
        sim, parts = diff(req.text, i.text)
        outs.append(M.VariationItemOut(itemId=i.itemId, similarity=sim, diff=parts))
    return M.VariationDiffResponse(items=outs, tip=tip(req.items, outs), tagSetVersion=TAG_SET_VERSION, algorithmVersion=ALGORITHM_VERSION)


if __name__ == "__main__":
    s, d = diff("국토교통부장관은 기본계획에 대하여 5년마다 타당성을 검토하여야 한다.", "시장은 기본계획에 대하여 3년마다 타당성을 검토하여야 한다.")
    assert [p.type for p in d] == ["ACTOR", "TIMING"], [(p.type, p.userText, p.itemText) for p in d]
    _, d = diff("중개보수는 거래금액의 1천분의 9 이내로 한다.", "중개보수는 거래금액의 1천분의 6 이내로 한다.")
    assert [p.type for p in d] == ["NUMBER"], [(p.type, p.userText, p.itemText) for p in d]
    _, d = diff("개발행위를 하려는 자는 시장·군수의 허가를 받아야 한다.", "개발행위를 하려는 자는 시장·군수의 허가를 받지 않아도 된다.")
    assert {p.type for p in d} == {"CONDITION"}, [(p.type, p.userText, p.itemText) for p in d]
    _, d = diff("재건축사업은 정비구역에서 시행한다.", "재개발사업은 정비구역에서 시행한다.")
    assert [p.type for p in d] == ["CONCEPT"]
    print("variation self-check ok", s)
