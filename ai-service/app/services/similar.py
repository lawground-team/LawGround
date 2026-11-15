"""/similar-items: 사용자 선지와 같은 쟁점의 기출 선지를 기출 선지 은행(jsonl, scripts/build_choice_bank.py)에서 골라 변형 비교(variation)와 함께 돌려준다. LLM·모델 없음.

고르는 방법(similar-v1)
  1. 같은 과목만 본다. 사용자 문장과 같은 글자열(띄어쓰기·문장부호 제외)인 선지가 있으면 그 기출 문제 전체를 자기 자신으로 보고 뺀다.
  2. 점수 = 글자 유사도(variation.diff) + 같은 근거 조문(법령명·제N조가 같음)이면 0.5.
     조문이 다르면 글자 유사도가 variation.MIN_SIMILARITY 이상일 때만 낸다(엉뚱한 문장을 채우지 않는다. 0~limit 개).
  3. 한 기출 문제에서는 1개만. 문장이 아닌 선지(UNKNOWN: 조합형 "ㄱ, ㄹ"·빈칸형 "ㄱ: 7, ㄴ: 50", 정규화 15자 미만)는 처음부터 뺀다.
은행 파일은 SIMILAR_BANK_PATH 로 읽는다. 없으면 기능이 꺼진 것으로 본다(503). 은행은 기출 저작권 표시 조건(Q-Net)으로 저장소에 올리지 않는다.
ponytail: 은행 전체를 메모리에 두고 매번 훑는다(4천 개, 수십 ms). 은행이 수십만 개로 커지면 조문별 색인을 둔다.
"""
from __future__ import annotations

import difflib
import json
import os
import re
from functools import lru_cache
from pathlib import Path

from app.contract import models as M
from app.services import variation

ALGORITHM_VERSION = "similar-v1"
SAME_ARTICLE_BONUS = 0.5
_OCR_MARK = re.compile(r"^(?:[0-9@]\)|\(\d\)|@|[①-⑤])\s+")   # 스캔본 OCR 선지 번호 잔재("0) ", "@) ", "6) ", "(2) ")
SELF_SIMILARITY = 0.95   # 이 이상 같은 선지가 있는 기출 문제는 사용자가 입력한 그 문제로 보고 통째로 뺀다(OCR 잡음으로 글자가 조금 달라도)
MIN_SENTENCE = 15        # 정규화 글자 수. 이보다 짧으면 문장이 아니라 보기 기호·숫자 조합이다
PREFILTER = 60          # 글자 유사도 근삿값(quick_ratio) 상위 몇 개만 정밀 비교할지. 같은 조문 선지는 항상 정밀 비교한다


def _article(title: str, label: str) -> tuple[str, str]:
    m = re.match(r"제\s*\d+\s*조(?:\s*의\s*\d+)?", label.strip())
    return re.sub(r"\s+", "", title), re.sub(r"\s+", "", m.group(0)) if m else re.sub(r"\s+", "", label)


@lru_cache(maxsize=1)
def bank() -> tuple[str, list[dict]] | None:
    path = os.environ.get("SIMILAR_BANK_PATH", "").strip()
    if not path or not Path(path).is_file():
        return None
    items = [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
    for it in items:
        it["text"] = _OCR_MARK.sub("", it["text"]).strip()
        it["_norm"] = variation._norm(it["text"])[0]
        it["_arts"] = {_article(a["lawTitle"], a["articleLabel"]) for a in it.get("articles", [])}
    return Path(path).stem, items


def similar_items(req: M.SimilarItemsRequest, loaded: tuple[str, list[dict]]) -> M.SimilarItemsResponse:
    version, items = loaded
    user_norm = variation._norm(req.text)[0]
    keys = {_article(k.lawTitle, k.articleLabel) for k in req.articleKeys}
    own = {(it["exam"], it["questionNumber"]) for it in items if it["_norm"] == user_norm}
    pool = [it for it in items if it["subject"] == req.subject and it["truth"] != "UNKNOWN" and len(it["_norm"]) >= MIN_SENTENCE
            and (it["exam"], it["questionNumber"]) not in own]
    same = [it for it in pool if it["_arts"] & keys]
    rough = sorted((it for it in pool if not it["_arts"] & keys),
                   key=lambda it: difflib.SequenceMatcher(None, user_norm, it["_norm"], autojunk=False).quick_ratio(), reverse=True)[:PREFILTER]
    scored = []
    for it in same + rough:
        sim, parts = variation.diff(req.text, it["text"])
        hit = bool(it["_arts"] & keys)
        if not hit and sim < variation.MIN_SIMILARITY:
            continue
        scored.append((-(sim + (SAME_ARTICLE_BONUS if hit else 0)), it["itemId"], it, sim, parts, hit))
    own |= {(it["exam"], it["questionNumber"]) for _, _, it, sim, _, _ in scored if sim >= SELF_SIMILARITY}
    scored = [x for x in scored if (x[2]["exam"], x[2]["questionNumber"]) not in own]
    scored.sort(key=lambda s: s[:2])
    picked, seen = [], set()
    for *_, it, sim, parts, hit in scored:
        q = (it["exam"], it["questionNumber"])
        if q in seen:
            continue
        seen.add(q)
        picked.append(M.SimilarItem(itemId=it["itemId"], exam=it["exam"], questionNumber=it["questionNumber"], choiceNumber=it["choiceNumber"],
                                    stemHead=it.get("stemHead", ""), text=it["text"], truth=it["truth"], match="SAME_ARTICLE" if hit else "TEXT_ONLY",
                                    similarity=sim, diff=parts))
        if len(picked) == req.limit:
            break
    tip = variation.tip([M.VariationItemIn(itemId=p.itemId, text=p.text, truth=p.truth) for p in picked],
                        [M.VariationItemOut(itemId=p.itemId, similarity=p.similarity, diff=p.diff) for p in picked]) if picked else None
    return M.SimilarItemsResponse(items=picked, tip=tip, bankVersion=version, tagSetVersion=variation.TAG_SET_VERSION, algorithmVersion=ALGORITHM_VERSION)


if __name__ == "__main__":
    import uuid
    demo = ("bank-demo", [
        {"itemId": "36-1-1", "subject": "BROKER_LAW", "exam": 36, "questionNumber": 1, "choiceNumber": 1, "stemHead": "h", "truth": "TRUE",
         "text": "시장은 기본계획에 대하여 5년마다 타당성을 검토하여야 한다.", "articles": [{"lawTitle": "공인중개사법", "articleLabel": "제4조"}]},
        {"itemId": "36-1-2", "subject": "BROKER_LAW", "exam": 36, "questionNumber": 1, "choiceNumber": 2, "stemHead": "h", "truth": "FALSE",
         "text": "시장은 기본계획에 대하여 3년마다 타당성을 검토하여야 한다.", "articles": [{"lawTitle": "공인중개사법", "articleLabel": "제4조"}]},
        {"itemId": "35-2-1", "subject": "BROKER_LAW", "exam": 35, "questionNumber": 2, "choiceNumber": 1, "stemHead": "h", "truth": "FALSE",
         "text": "국토교통부장관은 기본계획에 대하여 10년마다 타당성을 검토할 수 있다.", "articles": [{"lawTitle": "공인중개사법", "articleLabel": "제4조의2"}]},
        {"itemId": "34-9-1", "subject": "BROKER_LAW", "exam": 34, "questionNumber": 9, "choiceNumber": 1, "stemHead": "h", "truth": "TRUE",
         "text": "국토교통부장관은 기본계획에 대하여 5년마다 타당성을 검토하여야 한다 ㆍ", "articles": [{"lawTitle": "공인중개사법", "articleLabel": "제4조"}]},
        {"itemId": "34-9-2", "subject": "BROKER_LAW", "exam": 34, "questionNumber": 9, "choiceNumber": 2, "stemHead": "h", "truth": "FALSE",
         "text": "시장은 기본계획에 대하여 5년마다 타당성을 검토하여야 한다.", "articles": [{"lawTitle": "공인중개사법", "articleLabel": "제4조"}]},
        {"itemId": "35-3-1", "subject": "PUBLIC_LAW", "exam": 35, "questionNumber": 3, "choiceNumber": 1, "stemHead": "h", "truth": "TRUE",
         "text": "국토교통부장관은 기본계획에 대하여 5년마다 타당성을 검토하여야 한다.", "articles": []},
    ])
    for it in demo[1]:
        it["_norm"] = variation._norm(it["text"])[0]
        it["_arts"] = {_article(a["lawTitle"], a["articleLabel"]) for a in it["articles"]}
    r = similar_items(M.SimilarItemsRequest(requestId=uuid.uuid4(), subject="BROKER_LAW", text="국토교통부장관은 기본계획에 대하여 5년마다 타당성을 검토하여야 한다.",
                                            articleKeys=[M.ArticleKey(lawTitle="공인중개사법", articleLabel="제4조(개설등록)")]), demo)
    ids = [i.itemId for i in r.items]
    assert ids == ["36-1-1", "35-2-1"], ids          # 다른 과목·입력한 그 문제(34-9, OCR 잡음 포함) 제외, 같은 문제는 1개, 같은 조문 우선
    assert r.items[0].match == "SAME_ARTICLE" and r.items[1].match == "TEXT_ONLY" and r.items[0].diff[0].type == "ACTOR"
    print("similar self-check ok")
