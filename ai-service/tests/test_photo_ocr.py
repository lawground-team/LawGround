from app.photo_ocr import format_lines


def test_normalizes_complete_mixed_markers_in_the_margin():
    texts = ["2. 본문", "①첫째", "2둘째", "3셋째", "이어서", "④넷째", "5", "다섯째"]
    left_edges = [[10], [10], [11], [12], [100], [9], [10], [75]]

    assert format_lines(texts, left_edges, 1000).splitlines() == [
        "2. 본문", "1. 첫째", "2. 둘째", "3. 셋째", "이어서", "4. 넷째", "5. ", "다섯째"
    ]


def test_ignores_indented_numbers_and_does_not_fill_a_missing_marker():
    texts = ["본문", "1첫째", "2둘째", "3개월 동안", "④넷째", "⑤다섯째"]
    left_edges = [[10], [10], [10], [100], [10], [10]]

    assert format_lines(texts, left_edges, 1000).splitlines() == texts
