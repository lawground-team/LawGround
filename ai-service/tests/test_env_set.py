"""env_set.py 검증. 중복 정리, 추가, 주석 보존, 줄바꿈 유지, 비밀값 비노출, 백업 위치, 입력 검사."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import env_set as E  # noqa: E402

BASE = "# 주석은 남아야 한다\nPOSTGRES_PASSWORD=1234\nLAWGROUND_ANALYSIS_LIVE_ENABLED=false\n\nAI_SERVICE_API_KEY=abcdefghijklmnop\nLAWGROUND_ANALYSIS_LIVE_ENABLED=true\n#LAWGROUND_DEV_AUTH_ENABLED=true\nOTHER=값 그대로\n"


class ApplyTests(unittest.TestCase):
    def test_replaces_first_line_removes_duplicates_and_keeps_everything_else(self):
        new, ch = E.apply(BASE, {"LAWGROUND_ANALYSIS_LIVE_ENABLED": "true"})
        lines = new.splitlines()
        self.assertEqual(lines.count("LAWGROUND_ANALYSIS_LIVE_ENABLED=true"), 1)          # 두 줄이 하나로
        self.assertNotIn("LAWGROUND_ANALYSIS_LIVE_ENABLED=false", lines)
        self.assertEqual([l for l in lines if l != "LAWGROUND_ANALYSIS_LIVE_ENABLED=true"],
                         ["# 주석은 남아야 한다", "POSTGRES_PASSWORD=1234", "", "AI_SERVICE_API_KEY=abcdefghijklmnop", "#LAWGROUND_DEV_AUTH_ENABLED=true", "OTHER=값 그대로"])
        self.assertEqual(ch, [("LAWGROUND_ANALYSIS_LIVE_ENABLED", "false", "true", 1)])    # 이전 값은 첫 줄 값, 중복 1줄 삭제

    def test_appends_missing_keys_and_does_not_uncomment_commented_ones(self):
        new, ch = E.apply(BASE, {"LAWGROUND_DEV_AUTH_ENABLED": "true"})
        self.assertIn("#LAWGROUND_DEV_AUTH_ENABLED=true", new.splitlines())               # 주석 줄은 건드리지 않는다
        self.assertEqual(new.splitlines()[-1], "LAWGROUND_DEV_AUTH_ENABLED=true")          # 새 줄은 끝에 추가
        self.assertEqual(ch[0][1], None)

    def test_idempotent_second_run_changes_nothing(self):
        once, _ = E.apply(BASE, {"A": "1", "LAWGROUND_ANALYSIS_LIVE_ENABLED": "true"})
        twice, ch = E.apply(once, {"A": "1", "LAWGROUND_ANALYSIS_LIVE_ENABLED": "true"})
        self.assertEqual(once, twice); self.assertTrue(all(old == new and dup == 0 for _, old, new, dup in ch))

    def test_crlf_files_stay_crlf_and_lf_files_stay_lf(self):
        crlf, _ = E.apply("A=1\r\nB=2\r\n", {"A": "9"})
        self.assertEqual(crlf, "A=9\r\nB=2\r\n")
        lf, _ = E.apply("A=1\nB=2\n", {"A": "9"})
        self.assertEqual(lf, "A=9\nB=2\n")

    def test_values_may_contain_equals_and_are_not_confused_with_other_keys(self):
        new, _ = E.apply("AI_SERVICE_API_KEY=x\nAI_SERVICE_API_KEY_OLD=y\n", {"AI_SERVICE_API_KEY": "a=b=c"})
        self.assertEqual(new.splitlines(), ["AI_SERVICE_API_KEY=a=b=c", "AI_SERVICE_API_KEY_OLD=y"])    # 접두사가 같은 다른 키는 그대로

    def test_secret_like_keys_are_never_shown(self):
        for k in ("AI_SERVICE_API_KEY", "INTERNAL_API_TOKEN", "POSTGRES_PASSWORD", "MY_SECRET"):
            secret = "supersecretvalue"
            self.assertNotIn(secret, E.show(k, secret)); self.assertIn(f"{len(secret)}자", E.show(k, secret))        # 값은 안 보이고 길이만 보인다
        self.assertEqual(E.show("LAWGROUND_ANALYSIS_LIVE_ENABLED", "true"), "true")

    def test_input_validation(self):
        for bad in (["A"], ["1A=x"], ["A=x y "], ["A=\"q\""], ["A=1", "A=2"], ["A b=1"], ["A=1\nB=2"]):
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError):
                    E.parse_pairs(bad)
        self.assertEqual(E.parse_pairs(["A=1", "B=", "C=x=y"]), {"A": "1", "B": "", "C": "x=y"})


class CliTests(unittest.TestCase):
    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(ROOT / "scripts" / "env_set.py"), *args], capture_output=True, text=True, encoding="utf-8",
                              env={**__import__("os").environ, "PYTHONIOENCODING": "cp949"}, timeout=30)

    def test_writes_backs_up_outside_the_repo_and_hides_secrets(self):
        with tempfile.TemporaryDirectory() as t:
            f = Path(t) / ".env"; f.write_text(BASE, encoding="utf-8")
            r = self.run_cli("--file", str(f), "AI_SERVICE_API_KEY=newsecretkey123456", "LAWGROUND_ANALYSIS_LIVE_ENABLED=true")
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertNotIn("newsecretkey123456", r.stdout + r.stderr); self.assertNotIn("abcdefghijklmnop", r.stdout + r.stderr)     # 이전·새 비밀값 모두 안 보임
            self.assertIn("AI_SERVICE_API_KEY=newsecretkey123456", f.read_text(encoding="utf-8").splitlines())
            backup = Path([l for l in r.stdout.splitlines() if "백업" in l][0].split(": ", 1)[1].strip())
            self.addCleanup(lambda: backup.unlink(missing_ok=True))
            self.assertEqual(backup.read_text(encoding="utf-8"), BASE)                                       # 원본이 그대로 백업됨
            self.assertNotEqual(backup.parent, f.parent)                                                      # 저장소 폴더 밖

    def test_dry_run_and_missing_file_and_bad_input(self):
        with tempfile.TemporaryDirectory() as t:
            f = Path(t) / ".env"; f.write_text(BASE, encoding="utf-8")
            r = self.run_cli("--file", str(f), "--dry-run", "A=1")
            self.assertEqual(r.returncode, 0); self.assertEqual(f.read_text(encoding="utf-8"), BASE)         # 파일은 그대로
            self.assertNotEqual(self.run_cli("--file", str(Path(t) / "nope.env"), "A=1").returncode, 0)
            self.assertNotEqual(self.run_cli("--file", str(f), "A b=1").returncode, 0)
            self.assertEqual(f.read_text(encoding="utf-8"), BASE)


if __name__ == "__main__":
    unittest.main(verbosity=2)
