"""텍스트가 들어 있는 기출 PDF(2차 1교시 문제지, 제1과목 1~40번)를 questions .jsonl 로 바꾼다. OCR 불필요(34·35회 PDF 로 확인).
- 문제 번호는 1,2,3… 순서대로만 인정한다(보기 안의 '1.' 오인 방지). 제2과목 줄이 나오면 멈춘다.
- PDF 에서 띄어쓰기가 빠져 있어서 kiwipiepy 로 복원한다(복원은 근사라서 틀릴 수 있다. 검색 질의는 형태소 단위라 영향이 작다).
- 정답은 PDF 가 이미지라 --answers 파일(JSON 객체 {"1":5,...} 또는 40개 배열)로 따로 받는다.
사용:  python scripts\\exam_pdf_to_jsonl.py --pdf <문제지.pdf> --answers eval\\answers_35.json --exam 제35회 --out eval\\questions_35A.jsonl
"""
import argparse
import json
import re
import sys
from pathlib import Path

CIRCLED = "①②③④⑤"
HEADER = re.compile(r"^(\d{4}년)?제\d+회.*-\d+-\d+$")        # 28회는 '제28회공인중개사2차1교시A-12-1' (년·형 없음)
INCORRECT = re.compile(r"(틀린|옳지않|아닌것|않은것)")


def split_questions(text: str, count: int = 40, first: int = 1) -> dict[int, str]:
    """페이지 머리줄을 지우고 번호 순서대로 문제 덩어리로 나눈다. first 번부터 count 개(공법은 41 또는 81부터)."""
    lines = [l.strip() for l in text.splitlines()]
    lines = [l for l in lines if l and not l.startswith("=====PAGE") and not HEADER.match(l)]
    out: dict[int, list[str]] = {}
    cur, want, last = None, first, first + count - 1
    for l in lines:
        if cur is not None and (re.match(r"^제\d*과목", l) or (want > last and re.match(rf"^{want}\.(?!\d)", l))):
            break                                       # 다음 과목 제목(25·26회는 '제과목'/'2' 로 쪼개짐) 또는 다음 번호에서 멈춘다
        m = re.match(rf"^{want}\.(?!\d)\s*(.*)$", l)
        if want <= last and m:
            cur = want
            out[cur] = [m.group(1)]
            want += 1
        elif cur is not None:
            out[cur].append(l)
    return {n: "\n".join(v) for n, v in out.items()}


def parse_question(block: str):
    """덩어리 → (지문, [선지 5개]). 선지는 ①~⑤ 기호로 시작하는 줄에서 나뉜다. 못 나누면 선지가 5개가 아니게 돌려준다."""
    stem, choices = [], []
    for l in block.splitlines():
        if l and l[0] in CIRCLED:
            choices.append([l[1:]])
        elif choices:
            choices[-1].append(l)
        else:
            stem.append(l)
    join = lambda parts: "".join(p.strip() for p in parts)
    return join(stem), [join(c) for c in choices]


def respace(kiwi, s: str) -> str:
    return kiwi.space(s) if s else s


def build_rows(text: str, answers: dict[int, int], exam: str, kiwi=None, first: int = 1):
    rows, warns = [], []
    blocks = split_questions(text, first=first)
    for n in range(first, first + 40):
        if n not in blocks:
            warns.append(f"{n}번: 문제를 찾지 못함")
            continue
        stem, choices = parse_question(blocks[n])
        sp = (lambda s: respace(kiwi, s)) if kiwi else (lambda s: s)
        ans = answers.get(n)
        probs = []
        if len(choices) != 5:
            probs.append(f"선지 {len(choices)}개")
        if ans is None:
            probs.append("정답 없음")
        if not stem:
            probs.append("지문 없음")
        if probs:
            warns.append(f"{n}번: " + ", ".join(probs))
        combo = bool(re.search(r"[ㄱㄴㄷ][.]", stem))
        rows.append({"id": f"{exam}-{n:02d}", "exam": exam, "number": n, "stem": sp(stem), "choices": [sp(c) for c in choices], "answer": ans,
                     "kind": "combo" if combo else "normal", "stem_ok": bool(stem), "ocr": False, "status": "pdf", "problems": probs, "notes": [],
                     "judgement": "틀린" if INCORRECT.search(stem) else "옳은"})
    return rows, warns


def load_answers(path) -> dict[int, int]:
    d = json.loads(Path(path).read_text(encoding="utf-8"))
    d = {i + 1: v for i, v in enumerate(d)} if isinstance(d, list) else {int(k): v for k, v in d.items()}
    bad = {k: v for k, v in d.items() if not (isinstance(v, int) and 1 <= v <= 5)}
    if bad:
        raise ValueError(f"정답은 1~5 정수여야 한다: {bad}")
    return d


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pdf", required=True)
    ap.add_argument("--answers", required=True)
    ap.add_argument("--exam", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--first", type=int, default=1, help="첫 문제 번호(제2과목 공법은 41, 25~27회 공법은 81)")
    a = ap.parse_args(argv)
    import pymupdf
    from kiwipiepy import Kiwi
    doc = pymupdf.open(a.pdf)
    text = "\n".join(f"=====PAGE {i + 1}\n" + p.get_text() for i, p in enumerate(doc))
    rows, warns = build_rows(text, load_answers(a.answers), a.exam, Kiwi(), first=a.first)
    Path(a.out).write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    print(f"{len(rows)}문제 저장: {a.out} | 합답형 {sum(r['kind'] == 'combo' for r in rows)}개")
    for w in warns:
        print("경고:", w)
    return 0


if __name__ == "__main__":
    sys.exit(main())
