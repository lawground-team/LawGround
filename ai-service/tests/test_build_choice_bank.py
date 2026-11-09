"""기출 선지 은행 생성: 선지 정답(truth) 계산, 조문 라벨 분리, 범위 밖·선지 분리 실패 제외 (합성 데이터)."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import build_choice_bank as B  # noqa: E402


def gold(i, number, answer=2, choices=5, oos=False):
    return {"id": i, "exam": "제36회", "number": number, "stem": "다음 중 옳은 것은?\nㄱ. 보기", "choices": [f"선지 {n}  공백" for n in range(1, choices + 1)],
            "answer": answer, "gold": ["도시 및 주거환경정비법 제4조", "공인중개사법 시행령 별표 1"], "out_of_scope": oos}


class Bank(unittest.TestCase):
    def test_truth_follows_judgement_and_combo_is_unknown(self):
        self.assertEqual([B.truth_of({"kind": "normal", "judgement": "옳은"}, n, 2) for n in (1, 2)], ["FALSE", "TRUE"])
        self.assertEqual([B.truth_of({"kind": "normal", "judgement": "틀린"}, n, 2) for n in (1, 2)], ["TRUE", "FALSE"])
        self.assertEqual({B.truth_of(q, 2, 2) for q in ({"kind": "combo", "judgement": "옳은"}, {"kind": "normal", "judgement": None}, {})}, {"UNKNOWN"})

    def test_items_ids_subject_articles_and_exclusions(self):
        qs = {"a": {"kind": "normal", "judgement": "틀린"}}
        items, skipped = B.build([gold("a", 59), gold("b", 3, oos=True), gold("c", 5, choices=0)], qs)
        self.assertEqual(skipped, ["c"])                                                 # 범위 밖(b)은 조용히, 분리 실패(c)는 기록
        self.assertEqual([x["itemId"] for x in items], [f"36-59-{n}" for n in range(1, 6)])
        self.assertEqual({x["subject"] for x in items}, {"PUBLIC_LAW"})
        self.assertEqual(items[1]["truth"], "FALSE"); self.assertEqual(items[0]["text"], "선지 1 공백")
        self.assertEqual(items[0]["stemHead"], "다음 중 옳은 것은?")
        self.assertEqual(items[0]["articles"], [{"lawTitle": "도시 및 주거환경정비법", "articleLabel": "제4조"},
                                                {"lawTitle": "공인중개사법 시행령", "articleLabel": "별표 1"}])
        self.assertEqual(B.subject_of(40), "BROKER_LAW")


if __name__ == "__main__":
    unittest.main()
