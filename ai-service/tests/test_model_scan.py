"""model_scan.py 검증(가짜 HTTP 전송). 키 비노출, 상태·시간·사유 표시, 예외 처리."""
import sys
import unittest
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import model_scan as S  # noqa: E402

KEY = "AQ.Ab8RN6IrR8lHYmF6ZzZuZ4yow281jYA0vL-SECRET"


def transport(routes):
    def handler(req):
        model = str(req.url).split("/models/")[1].split(":")[0]
        out = routes[model]
        if isinstance(out, Exception):
            raise out
        return out
    return httpx.MockTransport(handler)


class ScanTests(unittest.TestCase):
    def test_status_time_and_reason_per_model_without_the_key(self):
        routes = {
            "ok-model": httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": "OK"}]}}]}),
            "closed-model": httpx.Response(404, json={"error": {"status": "NOT_FOUND", "message": f"no longer available to new users. 키 {KEY}"}}),
            "busy-model": httpx.Response(503, json={"error": {"status": "UNAVAILABLE", "message": "high demand"}}),
            "slow-model": httpx.TimeoutException("느림"),
            "down-model": httpx.ConnectError(f"연결 실패 {KEY}"),
            "weird-model": httpx.Response(200, text="not json"),
        }
        ticks = iter(range(0, 100, 2))
        rows = S.scan(list(routes), KEY, transport=transport(routes), clock=lambda: next(ticks))
        by = {r["model"]: r for r in rows}
        self.assertEqual((by["ok-model"]["status"], by["ok-model"]["text"]), (200, "OK"))
        self.assertEqual(by["closed-model"]["status"], 404); self.assertIn("NOT_FOUND", by["closed-model"]["text"]); self.assertIn("no longer available", by["closed-model"]["text"])
        self.assertEqual((by["busy-model"]["status"], by["busy-model"]["text"]), (503, "UNAVAILABLE: high demand"))
        self.assertEqual((by["slow-model"]["status"], by["slow-model"]["text"]), (None, "예외 시간 초과"))
        self.assertEqual(by["down-model"]["text"], "예외 ConnectError")
        self.assertEqual(by["weird-model"]["status"], 200); self.assertEqual(by["weird-model"]["text"], "")     # 모양이 이상한 200 은 성공으로 꾸미지 않는다
        self.assertTrue(all(r["secs"] == 2.0 for r in rows))
        for r in rows:
            self.assertNotIn(KEY, r["text"]); self.assertNotIn("SECRET", r["text"])

    def test_key_in_an_unknown_format_is_masked_by_exact_match(self):
        odd = "zz-custom_format.key/9876543210+abc"                    # AIza·AQ. 패턴에 안 맞는 형식: 정확한 치환이 유일한 방어
        routes = {"m": httpx.Response(403, json={"error": {"status": "PERMISSION_DENIED", "message": f"키 {odd} 는 막혔다"}}),
                  "n": httpx.ConnectError(f"실패 {odd}")}
        for r in S.scan(["m", "n"], odd, transport=transport(routes)):
            self.assertNotIn(odd, r["text"]); self.assertNotIn("9876543210", r["text"])
        self.assertEqual(S.mask(f"a {odd} b", odd), "a *** b")

    def test_text_is_single_line_and_truncated(self):
        routes = {"m": httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": "가\n나\n" + "다" * 500}]}}]})}
        r = S.scan(["m"], KEY, transport=transport(routes))[0]
        self.assertNotIn("\n", r["text"]); self.assertLessEqual(len(r["text"]), 110)

    def test_request_sends_key_in_header_only(self):
        seen = {}

        def handler(req):
            seen.update(url=str(req.url), key=req.headers.get("x-goog-api-key"))
            return httpx.Response(200, json={"candidates": [{"content": {"parts": [{"text": "OK"}]}}]})
        S.scan(["m"], KEY, transport=httpx.MockTransport(handler))
        self.assertEqual(seen["key"], KEY); self.assertNotIn(KEY, seen["url"])

    def test_mask_covers_exact_key_and_key_like_text(self):
        self.assertEqual(S.mask(f"a {KEY} b", KEY), "a *** b")
        self.assertNotIn("AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ012345", S.mask("x AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ012345 y", KEY))


if __name__ == "__main__":
    unittest.main(verbosity=2)
