import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import exam_pdf_to_jsonl as ep  # noqa: E402

SAMPLE = """=====PAGE 1
2024년제35회공인중개사2차1교시A형-12-1
제1과목: 공인중개사의업무
1.
공인중개사법령상
용어로옳은것은?
①가나
다
②라
③마
④바
⑤사
2. 법령상 틀린 것은?
①하
②보기
1. 숫자로 시작하는 줄
③차
④카
⑤타
=====PAGE 2
2024년제35회공인중개사2차1교시A형-12-2
3.
ㄱ. 보기
①ㄱ
②ㄴ
③ㄷ
④ㄱ,ㄴ
⑤ㄱ,ㄷ
제2과목: 공법
4.
공법문제
"""


def rows(ans=None):
    return ep.build_rows(SAMPLE, ans or {1: 5, 2: 3, 3: 1}, "제35회")


def test_split_numbers_in_order_and_stops_at_subject2():
    b = ep.split_questions(SAMPLE)
    assert sorted(b) == [1, 2, 3]                      # 4번(제2과목)은 제외
    assert "숫자로 시작하는 줄" in b[2] and 1 in b      # 줄 앞 '1.' 은 새 문제로 오인하지 않는다


def test_choices_joined_and_page_header_removed():
    r, w = rows()
    q1 = r[0]
    assert q1["stem"] == "공인중개사법령상용어로옳은것은?" and q1["choices"][0] == "가나다" and len(q1["choices"]) == 5
    assert "2024년" not in q1["stem"] + "".join(q1["choices"])


def test_stops_at_next_number_after_count():
    text = "1. 첫문제\n①가\n2. 둘째\n①나\n제과목\n2\n3. 다음과목\n①다"
    b = ep.split_questions(text, count=2)
    assert sorted(b) == [1, 2] and "다음과목" not in b[2]


def test_second_subject_from_41():
    text = "제1과목\n1. 중개\n①가\n제2과목\n41. 공법첫문제\n①나\n42. 둘째\n①다\n제3과목\n81. 다른과목"
    b = ep.split_questions(text, count=2, first=41)
    assert sorted(b) == [41, 42] and "다른과목" not in b[42] and "중개" not in b[41]


def test_header_variants_removed():
    for h in ("2024년제35회공인중개사2차1교시A형-12-1", "제28회공인중개사2차1교시A-12-3"):
        assert ep.HEADER.match(h)
    assert not ep.HEADER.match("1. 제3조에 따른 중개대상물")


def test_judgement_kind_and_warnings():
    r, w = rows({1: 5})
    assert r[1]["judgement"] == "틀린" and r[0]["judgement"] == "옳은"
    assert r[2]["kind"] == "combo" and r[0]["kind"] == "normal"
    assert any("2번: 정답 없음" in x for x in w) and any("40번" in x or "4번: 문제를 찾지" in x for x in w)


def test_answers_validation(tmp_path):
    f = tmp_path / "a.json"
    f.write_text("[1,2,6]", encoding="utf-8")
    try:
        ep.load_answers(f)
        raise AssertionError("6은 거절돼야 한다")
    except ValueError:
        pass
    f.write_text('{"1":4,"3":2}', encoding="utf-8")
    assert ep.load_answers(f) == {1: 4, 3: 2}
