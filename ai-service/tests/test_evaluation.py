"""검색 정확도 평가 도구 검증. 정답을 손으로 계산한 작은 데이터로 지표가 맞는지 본다.
실제 BGE 모델은 쓰지 않고 가짜 임베더·리랭커를 주입한다(torch 불필요)."""
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import numpy as np  # noqa: E402

from app import evaluation as E  # noqa: E402
from app.services import tokenizer  # noqa: E402


def rec(law, label, title, text, kind="조문"):
    return {"law_name": law, "article_label": label, "title": title, "kind": kind, "text": text}


RECS = [
    rec("공인중개사법", "제1조", "목적", "이 법은 공인중개사의 업무에 관한 사항을 정한다. <개정 2014.1.28>"),
    rec("공인중개사법", "제32조", "중개보수 등", "개업공인중개사는 중개업무에 대하여 중개의뢰인으로부터 소정의 보수를 받는다. [본조신설 2014.7.28]"),
    rec("공인중개사법", "제33조", "금지행위", "개업공인중개사는 거짓된 언행으로 중개의뢰인의 판단을 그르치게 하는 행위를 하여서는 아니 된다."),
    rec("공인중개사법 시행령", "제27조의2", "중개보수의 지급시기", "중개보수의 지급시기는 약정에 따르되 약정이 없을 때에는 중개대상물의 거래대금 지급이 완료된 날로 한다."),
    rec("공인중개사법 시행규칙", "별표 1", "주택 중개보수 상한요율", "■ 공인중개사법 시행규칙 [별표 1]\n\n  주택 중개보수 상한요율(제20조제1항 관련)\n┌────┬────┐\n│거래내용│상한요율│\n├────┼────┤\n│매매ㆍ교환│1천분의 6│\n", "별표"),
    rec("공인중개사법 시행규칙", "서식 1", "신청서", "서식 본문", "서식"),
    rec("공인중개사법", "", "제1장 총칙", "제1장 총칙", "전문"),
]


def write_corpus(d):
    p = Path(d) / "a.jsonl"
    p.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in RECS), encoding="utf-8")
    return [p]


class FakeEmbedder:
    revision = "fake"

    def encode(self, texts):                       # 단어 주머니 해시 벡터: 겹치는 단어가 많을수록 가깝다
        out = []
        for t in texts:
            v = np.zeros(64)
            for tok in tokenizer.tokenize(t):
                v[int(hashlib.md5(tok.encode()).hexdigest(), 16) % 64] += 1
            n = np.linalg.norm(v) or 1.0
            out.append((v / n).tolist())
        return out


class FakeReranker:
    def score(self, q, texts):
        qs = set(tokenizer.tokenize(q))
        return [float(len(qs & set(tokenizer.tokenize(t)))) for t in texts]


class Corpus(unittest.TestCase):
    def test_kinds_ids_and_revision_notes(self):
        with tempfile.TemporaryDirectory() as d:
            ch = E.load_corpus(write_corpus(d))
        ids = [c["id"] for c in ch]
        self.assertEqual(ids, ["공인중개사법 제1조", "공인중개사법 제32조", "공인중개사법 제33조", "공인중개사법 시행령 제27조의2", "공인중개사법 시행규칙 별표 1"])
        self.assertNotIn("서식", " ".join(c["kind"] for c in ch))        # 서식·전문은 제외
        self.assertNotIn("2014.1.28", ch[0]["text"])                      # 개정 표기 제거(기본)
        with tempfile.TemporaryDirectory() as d:
            kept = E.load_corpus(write_corpus(d), strip_notes=False)
        self.assertIn("2014.1.28", kept[0]["text"])
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(len(E.load_corpus(write_corpus(d), include_annex=False)), 4)

    def test_non_law_jsonl_in_corpus_folder_gives_a_clear_error(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "q.jsonl").write_text(json.dumps({"id": "q1", "stem": "문제", "choices": []}, ensure_ascii=False), encoding="utf-8")
            with self.assertRaises(ValueError) as cm:
                E.load_corpus([Path(d) / "q.jsonl"])
        self.assertIn("법령 조문 파일이 아니다", str(cm.exception))
        self.assertIn("다른 폴더", str(cm.exception))

    def test_annex_normalization_matches_observed_wrapping(self):
        raw = ("■ 공인중개사법 시행규칙 [별표 4] <개정 2026. 8. 11.>\n  업무정지의 기준\n  1. 일반기준\n"
               "    가. 기간의 계산은 위반행위에 대하여 업무정지처분\n  또는 과태료 부과처분을 받은 날을 기준으로 한다.\n"
               "    마. 업무정지기간을 늘리거나 줄이는 경우 업무정\n  지기간 1개월은 30일로 본다.\n"
               "┌────┬────┐\n│위반행위│기간│\n├────┼────┤\n│법 제16조 위반│업무정지 3개월│\n")
        out = E.normalize_annex(raw)
        self.assertIn("업무정지기간 1개월은 30일로 본다", out)             # 단어 중간 줄바꿈이 복원된다
        self.assertIn("업무정지처분또는 과태료", out)                       # 경계 줄바꿈은 공백 없이 이어진다(Kiwi 가 띄어쓰기 오류에 강함)
        self.assertIn("1. 일반기준", out)                                  # 항목 기호는 새 줄(공백으로 구분)
        self.assertIn("위반행위 기간", out)
        self.assertNotIn("│", out)
        self.assertIn("법 제16조 위반 업무정지 3개월", out)

    def test_annex_tokens_become_searchable(self):
        raw = "  1. 일반기준\n    마. 기간을 늘리는 경우 업무정\n  지기간 1개월은 30일로 본다."
        self.assertIn("정지", tokenizer.tokenize(E.normalize_annex(raw)))   # 복원돼야 '정지' 형태소가 나온다


class Gold(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.ch = E.load_corpus(write_corpus(self.td.name))
        self.ids = [c["id"] for c in self.ch]

    def tearDown(self):
        self.td.cleanup()

    def write(self, lines):
        p = Path(self.td.name) / "g.jsonl"
        p.write_text("\n".join(lines), encoding="utf-8")
        return p

    def test_typo_in_gold_label_is_rejected_loudly(self):
        p = self.write([json.dumps({"id": "q1", "query": "x", "gold": ["공인중개사법 제32조 "]}), json.dumps({"id": "q2", "query": "y", "gold": ["공인중개사법 제320조"]})])
        with self.assertRaises(ValueError) as cm:
            E.load_gold(p, self.ids)
        self.assertIn("q2", str(cm.exception))
        self.assertNotIn("q1:", str(cm.exception))                         # 공백 차이는 정규화되어 통과

    def test_rules(self):
        for bad, msg in [({"id": "a", "query": "x", "gold": []}, "gold 가 비었다"),
                         ({"id": "a", "query": "x", "gold": ["공인중개사법 제1조"], "out_of_scope": True}, "out_of_scope 인데"),
                         ({"id": "a", "query": " ", "gold": ["공인중개사법 제1조"]}, "query 가 비었다")]:
            with self.assertRaises(ValueError) as cm:
                E.load_gold(self.write([json.dumps(bad)]), self.ids)
            self.assertIn(msg, str(cm.exception))
        with self.assertRaises(ValueError):
            E.load_gold(self.write([json.dumps({"id": "a", "query": "x", "gold": ["공인중개사법 제1조"]})] * 2), self.ids)

    def test_stem_only_row_with_empty_choices_is_accepted(self):
        # 실제 36회 합답형 11개는 선지 줄을 OCR 이 못 읽어 choices 가 비어 있다. 지문(stem)만으로도 질의가 된다.
        rows = E.load_gold(self.write([json.dumps({"id": "c1", "stem": "설명으로 옳은 것을 모두 고른 것은? ㄱ. 박스", "choices": [], "gold": ["공인중개사법 제1조"]})]), self.ids)
        self.assertEqual(len(rows), 1)
        self.assertEqual(E.build_queries(rows[0], "full"), ["설명으로 옳은 것을 모두 고른 것은? ㄱ. 박스"])
        self.assertEqual(E.build_queries(rows[0], "stem"), ["설명으로 옳은 것을 모두 고른 것은? ㄱ. 박스"])
        with self.assertRaises(ValueError):
            E.build_queries(rows[0], "per_choice")                                            # 선지가 없는 문제는 선지별 모드를 쓸 수 없다 → compare 에서 건너뜀

    def test_comments_and_out_of_scope_are_allowed(self):
        rows = E.load_gold(self.write(["# 주석", json.dumps({"id": "a", "query": "x", "gold": [], "out_of_scope": True})]), self.ids)
        self.assertEqual(len(rows), 1)


class Metrics(unittest.TestCase):
    def test_wilson_interval_known_values(self):
        lo, hi = E.wilson(21, 30)                                          # 0.70 이지만 문제가 30개뿐
        self.assertAlmostEqual(lo, 0.521, places=2)
        self.assertAlmostEqual(hi, 0.833, places=2)
        self.assertEqual(E.wilson(0, 0), (0.0, 0.0))

    def test_rrf_and_rank_of(self):
        self.assertEqual(E.rank_of([0.1, 0.9, 0.9, 0.0]), [1, 2, 0, 3])    # 동점은 입력 순서
        # 손계산(k=60): 문서0 = 1/61+1/63, 문서1 = 1/62+1/62, 문서2 = 1/63+1/61. 0 과 2 는 같고(0.032266) 1 은 아주 조금 작다(0.032258)
        # → 1등과 꼴등을 함께 가진 문서가 가운데 문서를 이긴다. 동점(0, 2)은 입력 순서
        self.assertEqual(E.rrf([[0, 1, 2], [2, 1, 0]]), [0, 2, 1])
        self.assertEqual(E.rrf([[5, 6], [6, 5], [6]])[0], 6)               # 두 목록에서 1등인 문서가 이긴다

    def test_bm25_prefers_documents_with_the_query_terms(self):
        docs = [["중개", "보수", "지급"], ["등기", "신청"], ["중개", "보수"]]
        s = E.bm25_scores(["지급", "시기"], docs)
        self.assertEqual(int(np.argmax(s)), 0)
        self.assertEqual(float(s[1]), 0.0)

    def test_recall_mrr_and_out_of_scope_hand_computed(self):
        with tempfile.TemporaryDirectory() as d:
            ch = E.load_corpus(write_corpus(d))

        class Fixed(E.Retriever):                                          # 순위를 직접 정해 손계산과 비교
            def stages(self, query, rerank_n=0):
                order = {"q1": [0, 1, 2, 3, 4], "q2": [4, 3, 2, 1, 0], "q3": [2, 0, 1, 3, 4], "q4": [3, 0, 1, 2, 4]}[query]
                return {"X": order}
        r = Fixed(ch, tokenizer.tokenize)
        gold = [
            {"id": "q1", "query": "q1", "gold": [ch[0]["id"]], "out_of_scope": False},                  # 순위 1
            {"id": "q2", "query": "q2", "gold": [ch[0]["id"]], "out_of_scope": False},                  # 순위 5
            {"id": "q3", "query": "q3", "gold": [ch[1]["id"], ch[4]["id"]], "out_of_scope": False},     # 순위 3, 5 → any=3, all=5
            {"id": "q4", "query": "q4", "gold": [], "out_of_scope": True},                              # 제외
        ]
        res = E.evaluate(r, gold)
        t = res["table"]["X"]
        self.assertEqual((res["n_scored"], res["n_out_of_scope"]), (3, 1))
        self.assertAlmostEqual(t["R@1"], 1 / 3)
        self.assertAlmostEqual(t["R@3"], 2 / 3)                            # q1(1), q3(any 3)
        self.assertAlmostEqual(t["R@5"], 1.0)
        self.assertAlmostEqual(t["R@3_all"], 1 / 3)                        # q3 는 all 이 5 라서 q1 만
        self.assertAlmostEqual(t["MRR"], (1 + 1 / 5 + 1 / 3) / 3)


class EndToEnd(unittest.TestCase):
    def test_stages_and_perfect_selftest_on_tiny_corpus(self):
        with tempfile.TemporaryDirectory() as d:
            ch = E.load_corpus(write_corpus(d))
            r = E.Retriever(ch, tokenizer.tokenize, FakeEmbedder(), FakeReranker(), cache_dir=d)
            rows = E.selftest_rows(ch)
            self.assertTrue(rows and all(x["gold"][0].startswith("공인중개사법") for x in rows))
            res = E.evaluate(r, rows, rerank_n=3)
            self.assertEqual(list(res["table"]), ["BM25(단어)", "Dense(뜻)", "Hybrid(RRF)", "Hybrid+Rerank(3)"])
            self.assertEqual(res["table"]["BM25(단어)"]["R@5"], 1.0)
            self.assertIn("R@5 95% 구간", E.format_table(res))
            self.assertTrue(list(Path(d).glob("emb_*.npy")))               # 임베딩 캐시가 만들어진다

    def test_embedding_cache_is_reused(self):
        calls = []

        class Counting(FakeEmbedder):
            def encode(self, texts):
                calls.append(len(texts))
                return super().encode(texts)
        with tempfile.TemporaryDirectory() as d:
            ch = E.load_corpus(write_corpus(d))
            E.Retriever(ch, tokenizer.tokenize, Counting(), cache_dir=d)
            first = sum(calls)
            E.Retriever(ch, tokenizer.tokenize, Counting(), cache_dir=d)
            self.assertEqual(sum(calls), first)                            # 두 번째는 다시 계산하지 않는다



class QueryModes(unittest.TestCase):
    ROW = {"id": "q", "stem": "지문", "choices": ["가나", "다라", "마바"], "answer": 2}

    def test_build_queries_each_mode(self):
        self.assertEqual(E.build_queries(self.ROW, "full"), ["지문 가나 다라 마바"])
        self.assertEqual(E.build_queries(self.ROW, "stem"), ["지문"])
        self.assertEqual(E.build_queries(self.ROW, "answer"), ["지문 다라"])                 # 정답은 2번
        self.assertEqual(E.build_queries(self.ROW, "per_choice"), ["지문 가나", "지문 다라", "지문 마바"])
        self.assertEqual(E.build_queries({"id": "q", "query": "통째"}, "full"), ["통째"])     # 예전 형식도 그대로

    def test_missing_fields_fail_loudly_with_the_id(self):
        for mode, row in [("stem", {"id": "x1", "query": "q"}), ("answer", {"id": "x2", "stem": "s", "choices": ["a"], "answer": None}),
                          ("answer", {"id": "x3", "stem": "s", "choices": ["a"], "answer": 5}), ("per_choice", {"id": "x4", "stem": "s"})]:
            with self.assertRaises(ValueError) as cm:
                E.build_queries(row, mode)
            self.assertIn(row["id"], str(cm.exception))

    def test_per_choice_fuses_rankings_by_rrf_hand_computed(self):
        with tempfile.TemporaryDirectory() as d:
            ch = E.load_corpus(write_corpus(d))

        class ByQuery(E.Retriever):
            ORD = {"지문 가나": [0, 1, 2, 3, 4], "지문 다라": [2, 1, 0, 3, 4], "지문 마바": [1, 0, 2, 3, 4]}

            def stages(self, query, rerank_n=0):
                return {"X": self.ORD[query]}
        r = ByQuery(ch, tokenizer.tokenize)
        # 정답 조문 = 인덱스 1.  각 쿼리에서 2위 / 2위 / 1위.  RRF(k=60) 손계산:
        #   인덱스 1 = 1/62 + 1/62 + 1/61 = 0.048651,  인덱스 0 = 1/61 + 1/63 + 1/62 = 0.048395,  인덱스 2 = 1/63 + 1/61 + 1/63 = 0.048139
        #   → 인덱스 1 이 가장 크다
        fused = r.stages_for(E.build_queries(self.ROW, "per_choice"))["X"]
        self.assertEqual(fused[0], 1)
        res = E.evaluate(r, [{"id": "q", "stem": "지문", "choices": ["가나", "다라", "마바"], "answer": 2, "gold": [ch[1]["id"]], "out_of_scope": False}], query_mode="per_choice")
        self.assertEqual(res["table"]["X"]["R@1"], 1.0)                                       # per_choice 에서는 1위
        res_full = E.evaluate(Fixed1(ch, tokenizer.tokenize), [{"id": "q", "query": "z", "gold": [ch[1]["id"]], "out_of_scope": False}])
        self.assertEqual(res_full["table"]["X"]["R@1"], 0.0)                                  # 한 방식의 순위가 2위면 R@1 은 0 이다

    def test_compare_modes_skips_modes_without_fields(self):
        with tempfile.TemporaryDirectory() as d:
            ch = E.load_corpus(write_corpus(d))
        r = E.Retriever(ch, tokenizer.tokenize)
        rows = [{"id": "a", "query": "중개보수", "gold": [ch[1]["id"]], "out_of_scope": False}]
        cmp = E.compare_modes(r, rows)
        self.assertEqual(list(cmp["results"]), ["full"])
        self.assertEqual(set(cmp["skipped"]), {"stem", "answer", "per_choice"})
        self.assertIn("건너뜀", E.format_compare(cmp))


class Fixed1(E.Retriever):
    def stages(self, query, rerank_n=0):
        return {"X": [0, 1, 2, 3, 4]}                                                         # 정답(1)이 2위


class CompareOnCommonRows(unittest.TestCase):
    def test_letter_only_combo_choices_do_not_pollute_answer_or_per_choice(self):
        combo = {"id": "c", "stem": "옳은 것을 모두 고른 것은? ㄱ. 박스", "choices": ["ㄱ", "ㄱ, ㄴ", "ㄴ, ㄷ"], "answer": 2}
        self.assertEqual(E.build_queries(combo, "answer"), ["옳은 것을 모두 고른 것은? ㄱ. 박스"])      # 글자 조합은 붙이지 않는다
        with self.assertRaises(ValueError):
            E.build_queries(combo, "per_choice")                                                  # 의미 있는 선지가 없다
        mixed = {"id": "m", "stem": "지문", "choices": ["가나다", "ㄱ", "라마바"], "answer": 1}
        self.assertEqual(E.build_queries(mixed, "per_choice"), ["지문 가나다", "지문 라마바"])    # 의미 있는 선지만

    def test_compare_uses_the_common_rows_so_modes_are_comparable(self):
        with tempfile.TemporaryDirectory() as d:
            ch = E.load_corpus(write_corpus(d))
        r = E.Retriever(ch, tokenizer.tokenize)
        full = lambda i: {"id": f"n{i}", "stem": "중개보수 지급시기", "choices": ["중개보수 지급", "금지행위 위반", "목적 규정"], "answer": 1, "gold": [ch[1]["id"]], "out_of_scope": False}
        rows = [full(1), full(2),
                {"id": "empty", "stem": "합답형 지문 박스", "choices": [], "answer": 2, "gold": [ch[1]["id"]], "out_of_scope": False},   # 선지 없음
                {"id": "oos", "stem": "x", "choices": [], "gold": [], "out_of_scope": True}]
        cmp = E.compare_modes(r, rows)
        self.assertEqual(set(cmp["results"]), set(E.QUERY_MODES))                                 # 네 방식 모두 나온다 (예전에는 건너뛰었다)
        self.assertEqual(cmp["common_n"], 2)
        self.assertEqual(cmp["excluded"], ["empty"])
        self.assertTrue(all(res["n_scored"] == 2 for res in cmp["results"].values()))             # 모든 방식이 같은 2문제로 평가된다
        text = E.format_compare(cmp)
        self.assertIn("모든 방식을 만들 수 있는 문제 2개", text)
        self.assertIn("Recall@1", text)


class AuditLabels(unittest.TestCase):
    def setUp(self):
        self.chunks = [
            {"id": "공인중개사법 제9조", "kind": "조문", "title": "위임", "text": "제9조(위임) 등록에 필요한 사항은 대통령령으로 정한다. <개정 2014.1.28>"},
            {"id": "공인중개사법 제10조", "kind": "조문", "title": "결격", "text": "제10조(결격사유) " + "다음 각 호의 어느 하나에 해당하는 자는 등록을 할 수 없다. " * 20 + "세부 사항은 대통령령으로 정한다."},
            {"id": "공인중개사법 제11조", "kind": "조문", "title": "목적", "text": "제11조(목적) 이 법은 업무를 정한다."},
        ]
        self.rows = [
            {"id": "e-03", "exam": "예시", "number": 3, "stem": "결격사유에 관한 설명", "choices": ["가나다", "라마바"], "answer": 2, "gold": ["공인중개사법 제10조", "공인중개사법 제9조"], "out_of_scope": False},
            {"id": "e-01", "exam": "예시", "number": 1, "stem": "범위 밖 문제", "choices": [], "answer": 1, "gold": [], "out_of_scope": True},
        ]

    def test_delegation_hint_only_for_short_delegating_articles(self):
        self.assertTrue(E.looks_like_delegation(self.chunks[0]["text"]))                      # 짧고 대통령령으로 정한다
        self.assertFalse(E.looks_like_delegation(self.chunks[1]["text"]))                     # 길어서 위임만 하는 조문이 아니다
        self.assertFalse(E.looks_like_delegation(self.chunks[2]["text"]))                     # 위임 문구가 없다

    def test_audit_shows_answer_labels_texts_and_flags_and_orders_by_number(self):
        text = E.audit_text(self.chunks, self.rows)
        self.assertLess(text.index("1번"), text.index("3번"))                                 # 번호순
        self.assertIn("정답 2번 라마바", text)                                                  # 정답 선지 글
        self.assertIn("공인중개사법 제9조  ← 위임 의심", text)
        self.assertNotIn("공인중개사법 제10조  ← 위임 의심", text)
        self.assertIn("범위 밖(OUT)으로 라벨됨", text)

    def test_audit_number_filter_and_missing_numbers_and_unknown_label(self):
        only = E.audit_text(self.chunks, self.rows, [3])
        self.assertIn("3번", only); self.assertNotIn("1번", only)
        self.assertIn("gold 에 없는 번호: [99]", E.audit_text(self.chunks, self.rows, [3, 99]))
        bad = [{**self.rows[0], "gold": ["공인중개사법 제999조"]}]
        self.assertIn("코퍼스에 없는 라벨", E.audit_text(self.chunks, bad))


class Split(unittest.TestCase):
    def test_split_is_deterministic_disjoint_and_stable_when_rows_are_added(self):
        rows = [{"id": f"q{i:03d}"} for i in range(300)]
        dev, test = E.split_rows(rows, "dev"), E.split_rows(rows, "test")
        self.assertEqual(len(dev) + len(test), 300)
        self.assertFalse({r["id"] for r in dev} & {r["id"] for r in test})
        self.assertTrue(0.25 < len(test) / 300 < 0.41)                                        # 약 33%
        more = rows + [{"id": f"new{i}"} for i in range(50)]
        self.assertTrue({r["id"] for r in test} <= {r["id"] for r in E.split_rows(more, "test")})   # 라벨을 더해도 기존 test 문제는 그대로
        self.assertEqual(E.split_rows(rows, "all"), rows)
        with self.assertRaises(ValueError):
            E.split_rows(rows, "x")


class Assisted(unittest.TestCase):
    def test_unassisted_subset_is_reported_separately(self):
        with tempfile.TemporaryDirectory() as d:
            ch = E.load_corpus(write_corpus(d))

        class ByQ(E.Retriever):
            def stages(self, query, rerank_n=0):
                return {"X": [0, 1, 2, 3, 4] if query == "hit" else [4, 3, 2, 1, 0]}
        r = ByQ(ch, tokenizer.tokenize)
        mk = lambda i, q, asst: {"id": i, "query": q, "gold": [ch[0]["id"]], "out_of_scope": False, "assisted": asst}
        rows = [mk("a1", "hit", True), mk("a2", "hit", True), mk("d1", "hit", False), mk("d2", "miss", False)]
        rep = E.evaluate_report(r, rows)
        self.assertEqual(rep["all"]["n_scored"], 4)
        self.assertAlmostEqual(rep["all"]["table"]["X"]["R@1"], 3 / 4)                        # 제안으로 고른 라벨이 점수를 부풀린다
        self.assertEqual(rep["unassisted"]["n_scored"], 2)
        self.assertAlmostEqual(rep["unassisted"]["table"]["X"]["R@1"], 1 / 2)                 # 직접 단 라벨만 보면 0.5
        self.assertIn("도구 제안 없이 직접 단 라벨만", E.format_report(rep))
        self.assertIsNone(E.evaluate_report(r, [mk("d1", "hit", False)])["unassisted"])      # 제안 라벨이 없으면 따로 내지 않는다


class Labeling(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.ch = E.load_corpus(write_corpus(self.td.name))
        self.r = E.Retriever(self.ch, tokenizer.tokenize)
        self.qs = [{"id": f"q{i}", "stem": "중개보수의 지급시기", "choices": ["가", "나"], "answer": 1} for i in range(1, 6)]

    def tearDown(self):
        self.td.cleanup()

    def run_session(self, inputs, done=()):
        it = iter(inputs)
        saved, out = [], []
        rows = E.label_session(self.r, self.qs, set(done), input_fn=lambda _: next(it), print_fn=out.append, on_row=saved.append)
        return rows, saved, "\n".join(out)

    def test_direct_label_search_pick_and_assisted_flag(self):
        rows, saved, out = self.run_session([
            "g 공인중개사법 시행령 제27조의2", "d",       # q1: 정확한 라벨을 직접 입력 → assisted=False
            "a", "1", "d",                                 # q2: 도구 제안에서 고름 → assisted=True
            "중개보수 지급시기", "1", "d",                 # q3: 키워드 검색 후 선택 → assisted=False
            "o",                                           # q4: 코퍼스 밖
            "s",                                           # q5: 건너뜀
            "q"])
        by = {r["id"]: r for r in rows}
        self.assertEqual(by["q1"]["gold"], ["공인중개사법 시행령 제27조의2"]); self.assertFalse(by["q1"]["assisted"])
        self.assertTrue(by["q2"]["assisted"]); self.assertEqual(len(by["q2"]["gold"]), 1)
        self.assertFalse(by["q3"]["assisted"]); self.assertEqual(len(by["q3"]["gold"]), 1)
        self.assertTrue(by["q4"]["out_of_scope"]); self.assertEqual(by["q4"]["gold"], [])
        self.assertNotIn("q5", by)
        self.assertEqual(len(saved), 4)                                                       # on_row 로 한 문제씩 저장된다

    def test_keyword_search_returns_only_matching_articles_not_zero_score_filler(self):
        ids = [self.ch[i]["id"] for i in E.keyword_search(self.r, "중개보수")]
        self.assertTrue(ids)
        self.assertNotIn("공인중개사법 제1조", ids)                                          # '중개보수' 가 없는 조문(제1조 목적)은 나오지 않는다
        scores = E.bm25_scores(self.r.tokenize("중개보수"), self.r.docs_tokens)
        self.assertTrue(all(scores[i] > 0 for i in E.keyword_search(self.r, "중개보수")))   # 나온 조문은 모두 일치(점수>0)
        self.assertEqual(E.keyword_search(self.r, "존재하지않는검색어"), [])

    def test_no_match_search_tells_the_labeler_and_refuses_a_number_pick(self):
        _, _, out = self.run_session(["존재하지않는검색어", "1", "q"])
        self.assertIn("일치하는 조문이 없다", out)
        self.assertIn("먼저 검색어를 입력", out)                                              # 빈 목록에서 번호를 눌러도 아무 것도 고르지 않는다

    def test_invalid_input_is_rejected_and_resume_skips_done(self):
        rows, _, out = self.run_session(["g 공인중개사법 제999조", "d", "g 공인중개사법 제1조", "d", "q"])
        self.assertIn("코퍼스에 없는 라벨", out)
        self.assertIn("정답 조문이 없다", out)                                                # 아무 것도 못 고르고 d 를 누르면 저장되지 않는다
        self.assertEqual(rows[0]["gold"], ["공인중개사법 제1조"])
        rows2, _, _ = self.run_session(["q"], done=["q1", "q2", "q3", "q4", "q5"])
        self.assertEqual(rows2, [])

    def test_resume_shows_only_the_remaining_questions(self):
        # q1·q2 는 이미 라벨됨 → 처음 보이는 문제는 q3 이어야 하고, 입력 한 번(g..., d)이 q3 에 저장되어야 한다
        rows, saved, out = self.run_session(["g 공인중개사법 제1조", "d", "q"], done=["q1", "q2"])
        self.assertEqual([r["id"] for r in saved], ["q3"])
        self.assertNotIn("===== q1", out)
        self.assertNotIn("===== q2", out)
        self.assertIn("===== q3", out)
        # 다시 이어서 하면 q4 부터
        rows2, saved2, out2 = self.run_session(["g 공인중개사법 제1조", "d", "q"], done=["q1", "q2", "q3"])
        self.assertEqual([r["id"] for r in saved2], ["q4"])

    def test_number_pick_without_a_list_is_refused(self):
        _, _, out = self.run_session(["1", "q"])
        self.assertIn("먼저 검색어를 입력", out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
