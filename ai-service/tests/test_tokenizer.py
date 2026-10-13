"""실제 Kiwi 로 토큰화 규칙(rules-1)을 검증한다. CI(Linux, Python 3.12)에서도 실제 Kiwi 로 돈다."""
import sys
import unicodedata
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.services import tokenizer as T  # noqa: E402

S1 = "법 제32조제3항에 따른 중개보수의 지급시기는 개업공인중개사와 중개의뢰인 간의 약정에 따르되, 약정이 없을 때에는 중개대상물의 거래대금 지급이 완료된 날로 한다."
S2 = "제25조의2제1항에 따라 30일 이내에 신고하지 아니한 경우에는 3개월 이내의 업무정지를 할 수 있다."


class TokenizerRules(unittest.TestCase):
    def test_version_format(self):
        self.assertRegex(T.TOKENIZER_VERSION, r"^kiwipiepy-\d+\.\d+\.\d+\+rules-1$")

    def test_article_numbers_are_single_tokens(self):
        t1, t2 = T.tokenize(S1), T.tokenize(S2)
        for tok in ("제32조", "제3항"):
            self.assertIn(tok, t1)
        for tok in ("제25조의2", "제1항"):
            self.assertIn(tok, t2)
        for fragment in ("32", "조", "3", "항"):           # Kiwi 단독이면 이렇게 쪼개진다
            self.assertNotIn(fragment, t1)
        self.assertEqual(t1.index("제32조") + 1, t1.index("제3항"))   # 순서 보존

    def test_number_with_unit_is_single_token(self):
        t2 = T.tokenize(S2)
        self.assertIn("30일", t2)
        self.assertIn("3개월", t2)
        self.assertNotIn("30", t2)

    def test_negation_is_preserved(self):
        self.assertIn("없", T.tokenize(S1))
        self.assertIn("아니하", T.tokenize(S2))

    def test_particles_and_endings_removed(self):
        t1 = T.tokenize(S1)
        for josa in ("에", "는", "의", "와", "을", "로", "이"):
            self.assertNotIn(josa, t1, josa)

    def test_order_and_duplicates_preserved(self):
        self.assertEqual(T.tokenize("약정 약정"), ["약정", "약정"])
        t1 = T.tokenize(S1)
        self.assertEqual(t1.count("중개"), 3)             # 중개보수, 중개사, 중개의뢰인, 중개대상물 중 '중개' 형태소가 반복

    def test_deterministic(self):
        self.assertEqual(T.tokenize(S1), T.tokenize(S1))

    def test_nfc_normalization(self):
        nfd = unicodedata.normalize("NFD", "약정이 없을 때에는 제32조")
        self.assertNotEqual(nfd, unicodedata.normalize("NFC", nfd))
        self.assertEqual(T.tokenize(nfd), T.tokenize("약정이 없을 때에는 제32조"))

    def test_punctuation_only_gives_empty(self):
        self.assertEqual(T.tokenize("... !? ,"), [])

    def test_long_token_is_split_not_dropped(self):
        toks = T.tokenize("a" * 450)
        self.assertTrue(toks and max(len(x) for x in toks) <= T.MAX_TOKEN_LEN)
        self.assertEqual("".join(toks), "a" * 450)

    def test_token_limit_raises_instead_of_truncating(self):
        with self.assertRaises(T.TokenLimitExceeded):
            T.tokenize("약정 " * (T.MAX_TOKENS + 1))


if __name__ == "__main__":
    unittest.main(verbosity=2)
