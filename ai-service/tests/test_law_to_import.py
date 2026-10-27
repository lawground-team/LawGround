"""법령 jsonl → Spring LawImport JSON 변환기 검증 (합성 데이터). Spring 의 validate() 규칙을 하나씩 거절하는지 본다."""
import copy
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT))
import law_to_import as L  # noqa: E402
from app import evaluation as E  # noqa: E402

XML = """<?xml version="1.0" encoding="UTF-8"?><법령><기본정보><법령ID>001654</법령ID><공포일자>20260227</공포일자>
<법령명_한글><![CDATA[공인중개사법]]></법령명_한글><시행일자>20260828</시행일자></기본정보><조문><조문단위><조문시행일자>20000101</조문시행일자></조문단위></조문></법령>"""
RECS = [
    {"law_name": "공인중개사법", "article_label": "제1조", "title": "목적", "kind": "조문", "text": "제1조(목적) 이 법은 업무를 정한다. <개정 2014.1.28>"},
    {"law_name": "공인중개사법", "article_label": "제2조", "title": "정의", "kind": "조문", "text": "제2조(정의) ① 중개란 알선을 말한다.\n② 😀 이모지 포함 문장이다."},
    {"law_name": "공인중개사법", "article_label": "", "title": "제1장 총칙", "kind": "전문", "text": "제1장 총칙"},
    {"law_name": "공인중개사법", "article_label": "서식 1", "title": "신청서", "kind": "서식", "text": "서식 본문"},
    {"law_name": "공인중개사법", "article_label": "별표 1", "title": "요율", "kind": "별표",
     "text": "■ 별표 1\n  요율표\n┌────┬────┐\n│거래내용│상한요율│\n├────┼────┤\n│매매│1천분의 6│\n"},
]
META = {"law_id": "001654", "title": "공인중개사법", "effective_from": "2026-08-28"}


def doc(**kw):
    return L.build_import(copy.deepcopy(RECS), "283713", {**META, **kw.pop("meta", {})}, kw.pop("status", "VERIFIED"), "abc")


class Meta(unittest.TestCase):
    def test_extract_meta_reads_basic_info_block_not_article_dates(self):
        m = L.extract_meta(XML)
        self.assertEqual(m, {"law_id": "001654", "title": "공인중개사법", "effective_from": "2026-08-28"})   # 조문시행일자(2000-01-01)가 아니라 기본정보의 시행일자

    def test_missing_fields_are_none_not_guessed(self):
        self.assertEqual(L.extract_meta("<법령><기본정보><법령ID>1</법령ID></기본정보></법령>")["effective_from"], None)


class Build(unittest.TestCase):
    def test_only_articles_and_annexes_are_imported_with_whole_text_spans(self):
        d = doc()
        keys = [a["articleKey"] for a in d["articles"]]
        self.assertEqual(keys, ["제1조", "제2조", "별표 1"])                                     # 전문·서식 제외
        for a in d["articles"]:
            self.assertEqual(a["spans"], [{"start": 0, "end": len(a["sourceText"]), "searchText": a["spans"][0]["searchText"]}])
        self.assertIn("😀", d["articles"][1]["sourceText"])                                       # 원문은 가공하지 않는다

    def test_search_text_equals_evaluation_corpus_text_and_source_is_untouched(self):
        d = doc()
        for a, r in zip(d["articles"], [RECS[0], RECS[1], RECS[4]]):
            self.assertEqual(a["sourceText"], r["text"])                                          # 원문 그대로
            self.assertEqual(a["spans"][0]["searchText"], E.chunk_text(r, strip_notes=True))     # 색인 글 = 평가 코퍼스 글
        self.assertNotIn("2014.1.28", d["articles"][0]["spans"][0]["searchText"])                 # 개정 표기는 검색용에서만 제거
        self.assertIn("2014.1.28", d["articles"][0]["sourceText"])

    def test_document_shape_matches_spring_record_fields_and_has_no_secret(self):
        d = doc()
        self.assertEqual(set(d), {"sourceProvider", "sourceLawId", "sourceVersionKey", "title", "effectiveFrom", "effectiveTo", "applicabilityStatus", "sourceUrl", "rawPayload", "articles"})
        self.assertEqual(set(d["articles"][0]), {"articleKey", "articleLabel", "sourceText", "spans"})
        self.assertEqual(set(d["articles"][0]["spans"][0]), {"start", "end", "searchText"})
        self.assertEqual((d["sourceProvider"], d["effectiveTo"]), ("LAW_GO_KR", None))
        self.assertEqual(d["sourceUrl"], "https://www.law.go.kr/lsInfoP.do?lsiSeq=283713")
        self.assertNotIn("OC=", json.dumps(d).upper())

    def test_law_name_mismatch_between_xml_and_jsonl_is_rejected(self):
        with self.assertRaises(ValueError) as cm:
            doc(meta={"title": "다른 법"})
        self.assertIn("법령명이", str(cm.exception))


class SpringRulesAreChecked(unittest.TestCase):
    def check(self, mutate, msg):
        d = doc()
        mutate(d)
        with self.assertRaises(ValueError) as cm:
            L.validate(d)
        self.assertIn(msg, str(cm.exception))

    def test_rules(self):
        self.check(lambda d: d.update(sourceUrl="http://www.law.go.kr/x"), "소스 URL")                      # http 거절
        self.check(lambda d: d.update(sourceUrl="https://open.law.go.kr/x"), "소스 URL")                    # 허용 host 아님
        self.check(lambda d: d.update(sourceUrl="https://www.law.go.kr/x?OC=secret"), "API 키")             # 키 유출 방지
        self.check(lambda d: d.update(applicabilityStatus="OK"), "출처·검수")
        self.check(lambda d: d.update(effectiveTo="2026-08-28"), "effectiveTo")                              # from 과 같거나 앞서면 거절
        self.check(lambda d: d.update(articles=[]), "조문이 없거나")
        self.check(lambda d: d["articles"][0]["spans"][0].update(end=3), "전체를 덮지")                       # 일부만 덮음
        self.check(lambda d: d["articles"][0]["spans"][0].update(start=1), "이어지지")                        # 처음부터 안 시작
        self.check(lambda d: d["articles"][1].update(articleKey=d["articles"][0]["articleKey"]), "겹친다")
        self.check(lambda d: d["articles"][0]["spans"][0].update(searchText="  "), "searchText")
        self.check(lambda d: d["articles"][0].update(sourceText="x" * 20001, spans=[{"start": 0, "end": 20001, "searchText": "y"}]), "길이 초과")

    def test_emoji_codepoint_length_matches_java_codepoint_count(self):
        d = doc()
        a = d["articles"][1]
        self.assertEqual(a["spans"][0]["end"], len(a["sourceText"]))                              # 파이썬 str 은 코드포인트 단위(자바 codePointCount 와 같다)
        self.assertNotEqual(len(a["sourceText"]), len(a["sourceText"].encode("utf-16-le")) // 2)    # 이모지는 UTF-16 으로는 2칸 → 자바 length() 가 아니라 코드포인트를 쓴다


class Cli(unittest.TestCase):
    def test_cli_writes_one_file_per_law_requires_status_and_stops_on_missing_xml(self):
        with tempfile.TemporaryDirectory() as t:
            t = Path(t)
            (t / "a").mkdir(); (t / "raw").mkdir()
            (t / "a" / "283713.jsonl").write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in RECS), encoding="utf-8")
            (t / "raw" / "283713.xml").write_text(XML, encoding="utf-8")
            base = [sys.executable, str(ROOT / "scripts" / "law_to_import.py"), "--articles-dir", str(t / "a"), "--raw-dir", str(t / "raw"), "--out-dir", str(t / "o")]
            env = {**os.environ, "PYTHONIOENCODING": "cp949"}                                        # 한글 Windows 기본값을 흉내: 스크립트가 stdout·stderr 를 UTF-8 로 바꾸지 않으면 깨진다
            run = lambda extra: subprocess.run(base + extra, capture_output=True, text=True, encoding="utf-8", env=env)
            ok = run(["--status", "VERIFIED"])
            self.assertEqual(ok.returncode, 0, ok.stderr)
            self.assertIn("조문 2 + 별표 1", ok.stdout)
            self.assertIn("로컬 개발 DB 용", ok.stdout)                                           # VERIFIED 경고가 출력된다
            written = json.loads((t / "o" / "283713.import.json").read_text(encoding="utf-8"))
            self.assertEqual(written["sourceVersionKey"], "283713")
            self.assertEqual(run([]).returncode, 2)                                                   # --status 없으면 실행 안 됨
            (t / "raw" / "283713.xml").unlink()
            bad = run(["--status", "VERIFIED"])
            self.assertNotEqual(bad.returncode, 0)
            self.assertIn("원본 XML 이 없다", bad.stdout + bad.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
