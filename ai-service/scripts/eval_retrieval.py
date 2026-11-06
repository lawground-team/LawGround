"""법령 검색 정확도(Recall@k) 평가 도구. 설명은 app/evaluation.py 위쪽을 본다.

사용 (ai-service 폴더에서, 가상환경을 켠 뒤):
  # 1) 배관 점검 (정확도 아님): 조문 제목으로 그 조문을 찾는지. 도구가 도는지 확인하는 용도
  python scripts\\eval_retrieval.py selftest --corpus-dir C:\\Users\\user\\Downloads\\law_tools\\law_data\\articles
  # 2) 라벨링 도움: 문제 글을 넣으면 후보 조문 10개를 보여 준다 (사람이 고른다)
  python scripts\\eval_retrieval.py suggest --corpus-dir ... --query "문제 글"
  # 2-1) 대화형 라벨링 (권장): 문제를 보여 주고, 키워드로 직접 조문을 찾아 정답 근거를 고른다
  python scripts\\eval_retrieval.py label --corpus-dir ... --questions eval\\questions.jsonl --gold eval\\gold.jsonl
  # 3) 정확도 측정 (조정할 때는 --split dev, 최종 보고는 한 번만 --split test)
  python scripts\\eval_retrieval.py eval --corpus-dir ... --gold eval\\gold.jsonl --split dev [--dense] [--rerank-n 15]
  # 3-1) 쿼리 방식 비교 (지문 전체 / 지문만 / 정답 선지만 / 선지별)
  python scripts\\eval_retrieval.py eval --corpus-dir ... --gold eval\\gold.jsonl --split dev --compare-queries --dense
옵션: --dense 의미 검색(BGE-M3, 처음엔 법령 임베딩에 몇 분), --rerank-n N 재순위(느림, 질문마다 N쌍),
      --keep-notes 개정 표기를 지우지 않음, --no-annex 별표를 제외 (효과 비교용)
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

from app import evaluation as E  # noqa: E402
from app.services import tokenizer  # noqa: E402


def build(args):
    paths = sorted(Path(args.corpus_dir).glob("*.jsonl")) if args.corpus_dir else [Path(p) for p in args.corpus]
    if not paths:
        sys.exit("코퍼스 .jsonl 을 찾지 못했다 (--corpus-dir 또는 --corpus)")
    chunks = E.load_corpus(paths, strip_notes=not args.keep_notes, include_annex=not args.no_annex)
    embedder = reranker = None
    if args.dense:
        from app.services.embedder import BgeM3Embedder
        embedder = BgeM3Embedder()
    if getattr(args, "rerank_n", 0):
        from app.services.reranker import BgeReranker
        reranker = BgeReranker()
    r = E.Retriever(chunks, tokenizer.tokenize, embedder, reranker, cache_dir=args.cache_dir)
    kinds = {k: sum(c["kind"] == k for c in chunks) for k in E.USE_KINDS}
    print(f"코퍼스: {len(chunks)}개 ({', '.join(f'{k} {v}' for k, v in kinds.items())}) | 개정 표기 제거 {'아니오' if args.keep_notes else '예'} | 별표 {'제외' if args.no_annex else '포함'} | 의미 검색 {'예' if args.dense else '아니오'}")
    return chunks, r


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("eval", "suggest", "selftest", "label", "audit"):
        p = sub.add_parser(name)
        p.add_argument("--corpus-dir"); p.add_argument("--corpus", nargs="*", default=[])
        p.add_argument("--dense", action="store_true"); p.add_argument("--keep-notes", action="store_true"); p.add_argument("--no-annex", action="store_true")
        p.add_argument("--cache-dir", default="eval/cache")
        if name not in ("suggest", "label", "audit"):
            p.add_argument("--rerank-n", type=int, default=0)
            p.add_argument("--out", default=None)
        if name == "eval":
            p.add_argument("--gold", required=True)
            p.add_argument("--query-mode", choices=E.QUERY_MODES, default="full")
            p.add_argument("--compare-queries", action="store_true")
            p.add_argument("--split", choices=("all", "dev", "test"), default="all")
            p.add_argument("--test-ratio", type=float, default=0.33)
        if name == "label":
            p.add_argument("--questions", required=True); p.add_argument("--gold", required=True)
        if name == "audit":
            p.add_argument("--gold", required=True)
            p.add_argument("--numbers", type=int, nargs="*", default=None, help="점검할 문제 번호들(비우면 전부)")
        if name == "suggest":
            p.add_argument("--query", required=True)
    a = ap.parse_args(argv)
    chunks, r = build(a)
    if a.cmd == "suggest":
        for name, order in r.stages(a.query).items():
            if name != ("Hybrid(RRF)" if a.dense else "BM25(단어)"):
                continue
            print(f"\n[{name}] 후보 상위 10개 — 정답 근거를 사람이 골라 gold 에 적는다")
            for rank, i in enumerate(order[:10], 1):
                c = chunks[i]
                print(f"  {rank:2d}. {c['id']}  {c['title']}\n      {c['text'][:90]}")
        return
    if a.cmd == "audit":
        rows = E.load_gold(a.gold, [c["id"] for c in chunks])
        print(E.audit_text(chunks, rows, a.numbers))
        return
    if a.cmd == "label":
        questions = [json.loads(l) for l in Path(a.questions).read_text(encoding="utf-8").splitlines() if l.strip() and not l.lstrip().startswith("#")]
        gold_path = Path(a.gold)
        done = {json.loads(l)["id"] for l in gold_path.read_text(encoding="utf-8").splitlines() if l.strip() and not l.lstrip().startswith("#")} if gold_path.exists() else set()
        print(f"문제 {len(questions)}개 중 라벨 완료 {len(done & {q['id'] for q in questions})}개. 키워드를 입력해 조문을 찾는다 (a=도구 제안, d=저장, o=코퍼스 밖, s=건너뜀, q=종료)")
        gold_path.parent.mkdir(parents=True, exist_ok=True)
        def save(row):
            with gold_path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
        E.label_session(r, questions, done, on_row=save)
        return
    if a.cmd == "selftest":
        print("※ 정확도가 아니다: 조문 제목으로 그 조문을 찾는 배관 점검이다 (제목 단어가 본문 앞에 그대로 있어 쉽다). 교수님께 정확도로 말하지 않는다.")
        rows = E.selftest_rows(chunks)
    else:
        rows = E.load_gold(a.gold, [c["id"] for c in chunks])
    if a.cmd == "eval":
        rows = E.split_rows(rows, a.split, a.test_ratio)
        if a.split == "test":
            print("※ 최종 확인용(test)이다. 이 숫자를 보고 설정을 바꾸지 않는다 (바꾸면 같은 문제로 조정하고 보고한 셈이 된다).")
        print(f"평가 대상: {a.split} {len(rows)}개 (id 해시로 정해져 라벨을 더해도 기존 문제는 안 옮겨 간다)")
        if a.compare_queries:
            print("\n" + E.format_compare(E.compare_modes(r, rows, rerank_n=a.rerank_n)))
            return
    rep = E.evaluate_report(r, rows, rerank_n=a.rerank_n, query_mode=getattr(a, "query_mode", "full"))
    res = rep["all"]
    print("\n" + E.format_report(rep))
    if a.out:
        Path(a.out).parent.mkdir(parents=True, exist_ok=True)
        Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n문제별 결과 저장: {a.out} (어떤 문제에서 놓쳤는지 보는 용도)")


if __name__ == "__main__":
    main()
