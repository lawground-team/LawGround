"""법령 jsonl(law_fetch.py articles 결과) → Spring LawImport JSON 변환기.

Spring 쪽 규칙(backend LawImportService.validate 를 읽고 맞춤. 직접 실행해 본 것은 아니다):
  - sourceProvider=LAW_GO_KR, applicabilityStatus=VERIFIED|REVIEW_REQUIRED. Live 검색은 'LAW_GO_KR + VERIFIED' 버전만 쓴다.
  - sourceUrl 은 https 이고 host 가 law.go.kr 또는 www.law.go.kr. (API 키가 든 URL 은 넣지 않는다)
  - effectiveFrom 필수, effectiveTo 는 비우거나 effectiveFrom 보다 뒤.
  - 조문마다 span 이 원문(코드포인트)을 처음부터 끝까지 빈틈없이 이어서 덮어야 한다. span 원문 ≤ 20000자, searchText ≤ 30000자.
설계: 조문 하나를 span 하나로 둔다. searchText 는 평가 코퍼스(app.evaluation.chunk_text)와 같은 글(개정 표기 제거, 별표 표선 정리)이라
서비스 색인과 오프라인 평가가 같은 글을 본다. 원문(sourceText)은 가공하지 않는다(인용 위치 검증의 기준).
'VERIFIED' 는 사람이 검수했다는 표시다. --status 를 직접 고르게 했고, 로컬 개발 DB 에서만 쓰고 팀 검수 기준을 정한 뒤 확정한다.

사용 (ai-service 폴더, (.venv) 켠 상태):
  python scripts\\law_to_import.py --articles-dir <law_data\\articles> --raw-dir <law_data\\raw> --out-dir <출력폴더> --status VERIFIED
"""
import argparse
import hashlib
import json
import re
import sys
from datetime import date
from pathlib import Path
from urllib.parse import urlparse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
for _stream in (sys.stdout, sys.stderr):          # 오류 메시지(sys.exit)는 stderr 로 나간다. 파이프로 읽을 때 한글 Windows 기본(cp949)으로 깨지지 않게 둘 다 UTF-8
    try:
        _stream.reconfigure(encoding="utf-8")
    except Exception:
        pass
from app.evaluation import USE_KINDS, chunk_text  # noqa: E402

PROVIDER = "LAW_GO_KR"
STATUSES = ("VERIFIED", "REVIEW_REQUIRED")
ALLOWED_HOSTS = ("law.go.kr", "www.law.go.kr")
MAX_SOURCE, MAX_SEARCH = 20000, 30000


def _tag(block: str, name: str):
    m = re.search(rf"<{name}>\s*(?:<!\[CDATA\[)?\s*([^<\]]+?)\s*(?:\]\]>)?\s*</{name}>", block)
    return m.group(1).strip() if m else None


def extract_meta(xml_text: str) -> dict:
    """국가법령정보센터 응답 XML 의 <기본정보> 에서 법령ID·법령명·시행일자를 읽는다."""
    m = re.search(r"<기본정보>(.*?)</기본정보>", xml_text, re.S)
    block = m.group(1) if m else xml_text
    eff = _tag(block, "시행일자")
    return {"law_id": _tag(block, "법령ID"), "title": _tag(block, "법령명_한글"),
            "effective_from": (f"{eff[:4]}-{eff[4:6]}-{eff[6:8]}" if eff and re.fullmatch(r"\d{8}", eff) else None)}


def source_url(mst: str) -> str:
    return f"https://www.law.go.kr/lsInfoP.do?lsiSeq={mst}"      # 형식은 기억에 기댄 것이라 브라우저로 열어 확인한다. API 키(OC)는 넣지 않는다


def validate(doc: dict) -> None:
    """Spring validate() 의 핵심 규칙을 미리 검사해, Java 쪽에서 거절되기 전에 이유를 알려 준다."""
    def need(cond, msg):
        if not cond:
            raise ValueError(msg)
    need(doc["sourceProvider"] == PROVIDER and doc["applicabilityStatus"] in STATUSES, "출처·검수 상태 값이 잘못됐다")
    u = urlparse(doc["sourceUrl"])
    need(u.scheme == "https" and u.hostname in ALLOWED_HOSTS and not u.username, f"소스 URL 이 허용 형식이 아니다: {doc['sourceUrl']}")
    need("OC=" not in doc["sourceUrl"].upper(), "소스 URL 에 API 키(OC)가 들어 있다")
    need(0 < len(doc["sourceLawId"]) <= 100 and 0 < len(doc["sourceVersionKey"]) <= 160 and 0 < len(doc["title"]) <= 200, "법령 식별자·제목 길이")
    ef = date.fromisoformat(doc["effectiveFrom"])
    need(doc["effectiveTo"] is None or date.fromisoformat(doc["effectiveTo"]) > ef, "effectiveTo 는 effectiveFrom 보다 뒤여야 한다")
    need(doc["rawPayload"] is not None and 0 < len(doc["articles"]) <= 10000, "조문이 없거나 너무 많다")
    keys = set()
    for a in doc["articles"]:
        need(a["articleKey"] not in keys, f"조문 키가 겹친다: {a['articleKey']}")
        keys.add(a["articleKey"])
        need(a["sourceText"].strip() and a["articleLabel"].strip(), f"빈 조문: {a['articleKey']}")
        last, total = 0, len(a["sourceText"])                    # 파이썬 str 길이 = 코드포인트 수 (자바 codePointCount 와 같다)
        need(a["spans"], f"span 이 없다: {a['articleKey']}")
        for s in a["spans"]:
            need(s["start"] == last, f"span 이 이어지지 않는다: {a['articleKey']}")
            need(s["end"] > s["start"], f"빈 span: {a['articleKey']}")
            piece = a["sourceText"][s["start"]:s["end"]]
            need(piece.strip() and len(piece) <= MAX_SOURCE, f"span 원문 길이 초과({len(piece)}자): {a['articleKey']}")
            need(s["searchText"].strip() and len(s["searchText"]) <= MAX_SEARCH, f"searchText 길이: {a['articleKey']}")
            last = s["end"]
        need(last == total, f"조문 전체를 덮지 못한다: {a['articleKey']}")


def build_import(records: list[dict], mst: str, meta: dict, status: str, raw_sha: str = "") -> dict:
    law_name = records[0]["law_name"] if records else meta.get("title")
    if meta.get("title") and records and meta["title"].replace(" ", "") != law_name.replace(" ", ""):
        raise ValueError(f"법령명이 XML({meta['title']})과 jsonl({law_name})이 다르다")
    arts = []
    for r in records:
        if r["kind"] not in USE_KINDS or not r["article_label"]:
            continue
        text = r["text"]
        arts.append({"articleKey": r["article_label"], "articleLabel": f"{r['article_label']} {r['title']}".strip(), "sourceText": text,
                     "spans": [{"start": 0, "end": len(text), "searchText": chunk_text(r, strip_notes=True)}]})
    doc = {"sourceProvider": PROVIDER, "sourceLawId": meta["law_id"], "sourceVersionKey": mst, "title": law_name,
           "effectiveFrom": meta["effective_from"], "effectiveTo": None, "applicabilityStatus": status, "sourceUrl": source_url(mst),
           "rawPayload": {"source": "law.go.kr DRF lawService", "mst": mst, "parser": "law_fetch.py articles", "rawXmlSha256": raw_sha,
                          "kinds": list(USE_KINDS), "articles": len(arts)},
           "articles": arts}
    validate(doc)
    return doc


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--articles-dir", required=True); ap.add_argument("--raw-dir", required=True); ap.add_argument("--out-dir", required=True)
    ap.add_argument("--status", required=True, choices=STATUSES, help="VERIFIED 는 사람이 검수했다는 표시다. 로컬 개발 DB 에서만 쓰고 팀 검수 기준 확정 뒤 올린다")
    a = ap.parse_args(argv)
    out = Path(a.out_dir); out.mkdir(parents=True, exist_ok=True)
    files = sorted(Path(a.articles_dir).glob("*.jsonl"))
    if not files:
        sys.exit("법령 jsonl 이 없다")
    if a.status == "VERIFIED":
        print("※ VERIFIED 는 '검수 완료' 표시다. 이 파일들은 로컬 개발 DB 용이며, 팀이 검수 기준을 정하기 전에는 공유 DB 에 올리지 않는다.")
    for f in files:
        mst = f.stem
        xml_path = Path(a.raw_dir) / f"{mst}.xml"
        if not xml_path.exists():
            sys.exit(f"원본 XML 이 없다: {xml_path}")
        xml = xml_path.read_text(encoding="utf-8", errors="replace")
        meta = extract_meta(xml)
        missing = [k for k, v in meta.items() if not v]
        if missing:
            sys.exit(f"{xml_path.name}: XML 에서 {missing} 을(를) 읽지 못했다 → 파일 앞부분을 확인한다")
        recs = [json.loads(l) for l in f.read_text(encoding="utf-8").splitlines() if l.strip()]
        doc = build_import(recs, mst, meta, a.status, hashlib.sha256(xml.encode("utf-8")).hexdigest())
        n_art = sum(1 for x in doc["articles"] if not x["articleKey"].startswith("별표"))
        path = out / f"{mst}.import.json"
        path.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"{doc['title']:<22} MST {mst} | 법령ID {meta['law_id']} | 시행 {doc['effectiveFrom']} | 조문 {n_art} + 별표 {len(doc['articles']) - n_art} → {path.name}")


if __name__ == "__main__":
    main()
