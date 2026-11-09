"""정답 라벨 파일(gold) + 문제 파일 → 기출 선지 은행 jsonl ("비슷한 기출" 후보 데이터).

데이터는 Spring 이 소유한다(계약 C01). 이 파일은 Spring 적재용 원본이자 오프라인 평가용이다. 저장소에는 올리지 않는다(eval/ 아래에 둔다).
선지 정답(truth):
  - 일반형 '옳은 것은?' → 정답 번호 TRUE, 나머지 FALSE. '틀린/옳지 않은 것은?' → 반대.
  - 조합형(ㄱㄴㄷ)·판단 문구 없음 → UNKNOWN (선지가 문장이 아니거나 자동 판정 불가).
  - 범위 밖(out_of_scope: 타법·폐지·개정으로 정답 무효·전항정답) 문제는 뺀다.
조문(articles): 문제 단위 정답 조문 라벨을 그 문제의 모든 선지에 붙인다(1차).

사용 (ai-service 폴더):
  python scripts\\build_choice_bank.py --gold eval\\gold_25to36.jsonl eval\\gold_public_25to36.jsonl --questions-dir eval --out eval\\bank
"""
import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from datetime import date
from pathlib import Path

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8")
    except Exception:
        pass



def subject_of(number: int) -> str:
    return "BROKER_LAW" if number <= 40 else "PUBLIC_LAW"


def split_article(label: str) -> dict:
    """'도시 및 주거환경정비법 제4조' → {lawTitle, articleLabel}. 마지막 '제N조…' 또는 '별표 N' 앞을 법령명으로 본다."""
    m = re.match(r"^(.*?)\s+((?:제\d+조(?:의\d+)?|별표\s*\d+(?:의\d+)?).*)$", label.strip())
    return {"lawTitle": m.group(1), "articleLabel": m.group(2)} if m else {"lawTitle": label.strip(), "articleLabel": ""}


def truth_of(q: dict, choice: int, answer: int) -> str:
    if q.get("kind") != "normal" or q.get("judgement") not in ("옳은", "틀린"):
        return "UNKNOWN"
    is_answer = choice == answer
    return ("TRUE" if is_answer else "FALSE") if q["judgement"] == "옳은" else ("FALSE" if is_answer else "TRUE")


def build(gold_rows: list[dict], questions: dict[str, dict]) -> tuple[list[dict], list[str]]:
    """(선지 목록, 뺀 문제 id). 선지가 5개로 나뉘지 않은 문제(조합형 분리 실패, 조문 빈칸형)는 문장 은행에 넣지 않는다."""
    out, skipped = [], []
    for g in gold_rows:
        if g.get("out_of_scope"):
            continue
        if len(g["choices"]) != 5:
            skipped.append(g["id"])
            continue
        q = questions.get(g["id"], {})
        exam = int(re.sub(r"\D", "", g["exam"]))
        arts = [split_article(a) for a in g.get("gold", [])]
        for i, text in enumerate(g["choices"], 1):
            out.append({"itemId": f"{exam}-{g['number']}-{i}", "subject": subject_of(g["number"]), "exam": exam, "questionNumber": g["number"],
                        "choiceNumber": i, "stemHead": g["stem"].strip().splitlines()[0][:200], "text": " ".join(text.split()),
                        "truth": truth_of(q, i, g["answer"]), "articles": arts})
    return out, skipped


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--gold", nargs="+", required=True); ap.add_argument("--questions-dir", required=True); ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    questions = {}
    for f in sorted(Path(a.questions_dir).glob("questions_[0-9]*.jsonl")):
        for line in f.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                questions[r["id"]] = r
    gold = [json.loads(l) for f in a.gold for l in Path(f).read_text(encoding="utf-8").splitlines() if l.strip()]
    items, skipped = build(gold, questions)
    body = "\n".join(json.dumps(x, ensure_ascii=False) for x in items) + "\n"
    sha = hashlib.sha1(body.encode("utf-8")).hexdigest()
    version = f"bank-{date.today().isoformat()}-{sha[:12]}"
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    (out / f"{version}.jsonl").write_text(body, encoding="utf-8")
    counts = Counter((x["subject"], x["truth"]) for x in items)
    manifest = {"bankVersion": version, "items": len(items), "sha1": sha, "sources": [Path(f).name for f in a.gold], "skippedQuestions": skipped,
                "counts": {f"{s}/{t}": n for (s, t), n in sorted(counts.items())},
                "notice": "본 기출문제의 저작권은 한국산업인력공단(Q-Net)에 있으며, 출처를 밝히고 사용하였습니다. 입력 편의를 위해 일부 재가공됨."}
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{version}: 선지 {len(items)}개 → {out} (선지가 5개로 안 나뉜 {len(skipped)}문제 제외)")
    for k, n in manifest["counts"].items():
        print(f"  {k:<22} {n}")


if __name__ == "__main__":
    main()
