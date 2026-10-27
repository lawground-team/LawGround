"""법령 검색 정확도 평가 (Recall@k). 정답표(골든셋)가 있어야 의미가 있다.

정확도의 정의(교수님과 확인할 것): 문제 하나에 대해 '정답 근거 조문'이 검색 결과 상위 k개 안에 들어오는가.
  - Recall@k (any): 정답 조문 중 하나라도 상위 k개 안에 있으면 성공
  - Recall@k (all): 정답 조문이 모두 상위 k개 안에 있어야 성공 (정답 조문이 여러 개인 문제에 엄격)
  - MRR: 첫 정답 조문 순위의 역수 평균

코퍼스: law_fetch.py articles 가 만든 .jsonl. 사용 종류는 조문·별표 (전문·서식 제외).
정답표: 한 줄에 문제 하나인 .jsonl  {"id","query","gold":["공인중개사법 제32조", ...],"out_of_scope":false,"note":""}
  - gold 라벨 형식 = "<법령명> <조문 라벨>"  예) "공인중개사법 시행령 제27조의2", "공인중개사법 시행규칙 별표 1"
  - 근거가 이 6개 법령 밖(민법, 판례 등)인 문제는 "out_of_scope": true, "gold": [] 로 적는다. 정확도 계산에서 빼고 따로 센다.
"""
import hashlib
import json
import math
import re
from pathlib import Path

import numpy as np

USE_KINDS = ("조문", "별표")
_BOX = "│┃┌┐└┘├┤┬┴┼─━┏┓┗┛┣┫┳┻╋"
_DATE = r"\d{4}\.\s?\d{1,2}\.\s?\d{1,2}"
_REVISION_NOTE = re.compile(rf"<[^<>]*{_DATE}[^<>]*>|\[[^\[\]]*{_DATE}[^\[\]]*\]")
_ITEM_START = re.compile(r"^(\d+\.|[가-하]\.|\d+\)|[가-하]\)|[①-⑳]|■)")


# ───────── 코퍼스 ─────────
def normalize_annex(text: str) -> str:
    """별표 본문을 검색용 한 줄로. 관찰한 규칙 4건(별표 1·4, 시행령 별표 2, 거래신고령 별표 3)에 맞춘 것이다.
    - 표 행(│├┌└ 로 시작): 표선을 빼고 칸 사이 공백만 남긴다
    - 목록 문장: 항목 기호(1. 가. 1) 가))로 시작하면 새 줄, 들여쓰기 2칸의 비항목 줄은 앞 줄의 이어짐으로 보고 공백 없이 잇는다
      (고정 폭 줄바꿈이 단어 중간에서 끊기기 때문: 업무정/지기간)"""
    out: list[str] = []
    for raw in text.split("\n"):
        if not raw.strip():
            continue
        s = raw.rstrip()
        if s.lstrip()[:1] in _BOX:
            cells = re.sub(f"[{_BOX}]", " ", s)
            cells = re.sub(r"\s+", " ", cells).strip()
            if cells:
                out.append(cells)
            continue
        indent = len(s) - len(s.lstrip(" "))
        body = re.sub(r"\s+", " ", s.strip())
        if out and indent == 2 and not _ITEM_START.match(body) and not out[-1].startswith(("■",)):
            out[-1] += body
        else:
            out.append(body)
    return " ".join(out)


def chunk_text(rec: dict, strip_notes: bool = True) -> str:
    text = rec["text"]
    if strip_notes:
        text = _REVISION_NOTE.sub(" ", text)
    body = re.sub(r"\s+", " ", text).strip() if rec["kind"] == "조문" else normalize_annex(text)
    head = f"{rec['article_label']} {rec['title']}".strip()
    return f"{head} {body}".strip()


def chunk_id(rec: dict) -> str:
    return f"{rec['law_name']} {rec['article_label']}".strip()


def load_corpus(paths, strip_notes: bool = True, include_annex: bool = True) -> list[dict]:
    kinds = USE_KINDS if include_annex else ("조문",)
    chunks, seen = [], set()
    for p in paths:
        for line in Path(p).read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            rec = json.loads(line)
            missing = [k for k in ("kind", "law_name", "article_label", "title", "text") if k not in rec]
            if missing:
                raise ValueError(f"{p}: 법령 조문 파일이 아니다 (필드 {missing} 없음). --corpus-dir 폴더에는 law_fetch.py articles 가 만든 .jsonl 만 둔다 "
                                 "(문제·정답표 파일은 다른 폴더에)")
            if rec["kind"] not in kinds or not rec["article_label"]:
                continue
            cid = chunk_id(rec)
            if cid in seen:
                raise ValueError(f"조문 라벨이 겹친다: {cid}")
            seen.add(cid)
            chunks.append({"id": cid, "kind": rec["kind"], "title": rec["title"], "text": chunk_text(rec, strip_notes)})
    if not chunks:
        raise ValueError("코퍼스가 비었다")
    return chunks


def load_gold(path, chunk_ids) -> list[dict]:
    ids, known = [], set(chunk_ids)
    rows, unknown = [], []
    for n, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        r = json.loads(line)
        r["gold"] = [re.sub(r"\s+", " ", g).strip() for g in r.get("gold", [])]
        r.setdefault("out_of_scope", False)
        if not (str(r.get("query") or "").strip() or str(r.get("stem") or "").strip()):
            raise ValueError(f"{n}번째 줄: query 가 비었다 (query 또는 stem 이 필요하다. 합답형처럼 선지가 비어 있어도 stem 만 있으면 된다)")
        if r["out_of_scope"] and r["gold"]:
            raise ValueError(f"{n}번째 줄({r.get('id')}): out_of_scope 인데 gold 가 있다")
        if not r["out_of_scope"] and not r["gold"]:
            raise ValueError(f"{n}번째 줄({r.get('id')}): gold 가 비었다 (코퍼스 밖이면 out_of_scope 를 true 로)")
        bad = [g for g in r["gold"] if g not in known]
        unknown += [(r.get("id"), g) for g in bad]
        rows.append(r)
        ids.append(r.get("id"))
    if len(set(ids)) != len(ids):
        raise ValueError("id 가 겹친다")
    if unknown:
        sample = ", ".join(f"{i}:{g}" for i, g in unknown[:8])
        raise ValueError(f"코퍼스에 없는 정답 라벨 {len(unknown)}개 (오타 확인): {sample}")
    return rows


# ───────── 검색 ─────────
def bm25_scores(query_tokens: list[str], docs_tokens: list[list[str]], k1: float = 1.5, b: float = 0.75) -> np.ndarray:
    n = len(docs_tokens)
    avgdl = sum(len(d) for d in docs_tokens) / max(n, 1)
    df: dict[str, int] = {}
    for d in docs_tokens:
        for t in set(d):
            df[t] = df.get(t, 0) + 1
    scores = np.zeros(n)
    for i, d in enumerate(docs_tokens):
        if not d:
            continue
        tf: dict[str, int] = {}
        for t in d:
            tf[t] = tf.get(t, 0) + 1
        for t in set(query_tokens):
            if t not in tf:
                continue
            idf = math.log(1 + (n - df[t] + 0.5) / (df[t] + 0.5))
            scores[i] += idf * tf[t] * (k1 + 1) / (tf[t] + k1 * (1 - b + b * len(d) / avgdl))
    return scores


def rank_of(scores) -> list[int]:
    """점수 높은 순 인덱스 (동점은 입력 순서)."""
    return sorted(range(len(scores)), key=lambda i: (-scores[i], i))


def rrf(rank_lists: list[list[int]], k: int = 60) -> list[int]:
    s: dict[int, float] = {}
    for rl in rank_lists:
        for pos, idx in enumerate(rl):
            s[idx] = s.get(idx, 0.0) + 1.0 / (k + pos + 1)
    return sorted(s, key=lambda i: (-s[i], i))


class Retriever:
    """BM25(단어) · Dense(뜻) · Hybrid(RRF) · Hybrid+Rerank. dense/reranker 는 없으면 해당 단계를 건너뛴다."""

    def __init__(self, chunks, tokenize, embedder=None, reranker=None, cache_dir=None, pool: int = 30):
        self.chunks, self.tokenize, self.embedder, self.reranker, self.pool = chunks, tokenize, embedder, reranker, pool
        self.docs_tokens = [tokenize(c["text"]) for c in chunks]
        self.doc_mat = None
        if embedder is not None:
            self.doc_mat = self._embed_corpus(cache_dir)

    def _embed_corpus(self, cache_dir):
        texts = [c["text"] for c in self.chunks]
        key = hashlib.sha1(("\n".join(texts) + getattr(self.embedder, "revision", "")).encode("utf-8")).hexdigest()[:16]
        path = Path(cache_dir) / f"emb_{key}.npy" if cache_dir else None
        if path and path.exists():
            return np.load(path)
        vecs = []
        for i in range(0, len(texts), 8):
            vecs += self.embedder.encode(texts[i:i + 8])
        mat = np.array(vecs, dtype=np.float32)
        if path:
            path.parent.mkdir(parents=True, exist_ok=True)
            np.save(path, mat)
        return mat

    def stages_for(self, queries: list[str], rerank_n: int = 0) -> dict[str, list[int]]:
        per = [self.stages(q, rerank_n) for q in queries]
        if len(per) == 1:
            return per[0]
        return {name: rrf([p[name] for p in per]) for name in per[0]}

    def stages(self, query: str, rerank_n: int = 0) -> dict[str, list[int]]:
        out = {}
        bm = rank_of(bm25_scores(self.tokenize(query), self.docs_tokens))
        out["BM25(단어)"] = bm
        if self.doc_mat is not None:
            qv = np.array(self.embedder.encode([query])[0], dtype=np.float32)
            dn = rank_of(self.doc_mat @ qv)
            out["Dense(뜻)"] = dn
            hy = rrf([bm[: self.pool], dn[: self.pool]])
            out["Hybrid(RRF)"] = hy + [i for i in bm if i not in set(hy)]
        if self.reranker is not None and rerank_n > 0 and "Hybrid(RRF)" in out:
            top = out["Hybrid(RRF)"][:rerank_n]
            sc = self.reranker.score(query, [self.chunks[i]["text"] for i in top])
            reranked = [top[j] for j in sorted(range(len(top)), key=lambda j: (-sc[j], j))]
            out[f"Hybrid+Rerank({rerank_n})"] = reranked + out["Hybrid(RRF)"][rerank_n:]
        return out


# ───────── 지표 ─────────
def wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = p + z * z / (2 * n)
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return ((c - h) / d, (c + h) / d)


def evaluate(retriever: Retriever, gold_rows: list[dict], ks=(1, 3, 5, 10), rerank_n: int = 0, query_mode: str = "full") -> dict:
    id_index = {c["id"]: i for i, c in enumerate(retriever.chunks)}
    scoped = [r for r in gold_rows if not r["out_of_scope"]]
    per_q, agg = [], {}
    for r in scoped:
        gold_idx = {id_index[g] for g in r["gold"]}
        stages = retriever.stages_for(build_queries(r, query_mode), rerank_n)
        row = {"id": r["id"], "gold": r["gold"], "stages": {}}
        for name, order in stages.items():
            pos = {idx: p + 1 for p, idx in enumerate(order)}
            ranks = sorted(pos[g] for g in gold_idx)
            row["stages"][name] = {"gold_ranks": ranks, "top5": [retriever.chunks[i]["id"] for i in order[:5]]}
            a = agg.setdefault(name, {**{f"any@{k}": 0 for k in ks}, **{f"all@{k}": 0 for k in ks}, "mrr": 0.0})
            for k in ks:
                a[f"any@{k}"] += ranks[0] <= k
                a[f"all@{k}"] += ranks[-1] <= k
            a["mrr"] += 1.0 / ranks[0]
        per_q.append(row)
    n = len(scoped)
    table = {}
    for name, a in agg.items():
        t = {"n": n}
        for k in ks:
            t[f"R@{k}"] = a[f"any@{k}"] / n
            t[f"R@{k}_all"] = a[f"all@{k}"] / n
        t["MRR"] = a["mrr"] / n
        lo, hi = wilson(a["any@5"], n)
        t["R@5_ci95"] = [round(lo, 3), round(hi, 3)]
        table[name] = t
    return {"n_scored": n, "n_out_of_scope": len(gold_rows) - n, "table": table, "per_question": per_q}


QUERY_MODES = ("full", "stem", "answer", "per_choice")


_SYLLABLE = re.compile(r"[가-힣]")


def _informative(choice: str) -> bool:
    """합답형 선지('ㄱ, ㄴ')는 글자 조합뿐이라 검색 질의에 도움이 안 된다(한글 음절이 없다)."""
    return bool(_SYLLABLE.search(choice))


def build_queries(row: dict, mode: str) -> list[str]:
    """문제 한 줄에서 검색 질의를 만든다. full=지문+선지 전체, stem=지문만, answer=지문+정답 선지, per_choice=선지마다 따로(결과를 RRF 로 합침)."""
    stem = str(row.get("stem") or "").strip()
    choices = [str(c).strip() for c in (row.get("choices") or [])]
    full = (row.get("query") or "").strip() or " ".join([stem] + choices).strip()
    need = lambda msg: ValueError(f"{row.get('id')}: {msg} (query-mode={mode})")
    if mode == "full":
        return [full]
    if mode == "stem":
        if not stem:
            raise need("stem 필드가 필요하다")
        return [stem]
    if mode == "answer":
        a = row.get("answer")
        if not stem or not choices or not isinstance(a, int) or not (1 <= a <= len(choices)):
            raise need("stem·choices·answer(1~선지 수) 가 필요하다")
        chosen = choices[a - 1]
        return [f"{stem} {chosen}" if _informative(chosen) else stem]          # 합답형이면 박스가 든 지문만 쓴다
    if mode == "per_choice":
        useful = [c for c in choices if _informative(c)]
        if not stem or len(useful) < 2:
            raise need("stem 과 의미 있는 선지 2개 이상이 필요하다 (합답형 ㄱ·ㄴ 조합 선지는 제외)")
        return [f"{stem} {c}" for c in useful]
    raise ValueError(f"알 수 없는 query-mode: {mode}")


def is_test(row_id, ratio: float = 0.33) -> bool:
    return int(hashlib.sha1(str(row_id).encode("utf-8")).hexdigest(), 16) % 100 < int(ratio * 100)


def split_rows(rows: list[dict], which: str, ratio: float = 0.33) -> list[dict]:
    """dev=조정용, test=최종 확인용(이 결과를 보고 설정을 바꾸지 않는다). id 로 정해져서 라벨을 더해도 기존 문제는 안 옮겨 간다."""
    if which == "all":
        return list(rows)
    if which not in ("dev", "test"):
        raise ValueError("split 은 all|dev|test")
    return [r for r in rows if is_test(r["id"], ratio) == (which == "test")]


def evaluate_report(retriever, rows, rerank_n=0, query_mode="full") -> dict:
    """전체 + (도구 제안으로 고른 라벨이 있으면) 제안 없이 직접 단 라벨만의 결과를 함께 낸다. 순환 편향 확인용."""
    out = {"all": evaluate(retriever, rows, rerank_n=rerank_n, query_mode=query_mode), "unassisted": None}
    scoped = [r for r in rows if not r["out_of_scope"]]
    if any(r.get("assisted") for r in scoped):
        direct = [r for r in rows if not r.get("assisted")]
        if any(not r["out_of_scope"] for r in direct):
            out["unassisted"] = evaluate(retriever, direct, rerank_n=rerank_n, query_mode=query_mode)
    return out


def compare_modes(retriever, rows, modes=QUERY_MODES, rerank_n=0) -> dict:
    """쿼리 방식 비교. 모든 방식을 만들 수 있는 문제(공통 문제)만으로 같은 조건에서 비교한다. 공통 문제가 하나도 없으면
    방식마다 만들 수 있는지 따로 본다(예전 동작)."""
    scoped = [r for r in rows if not r["out_of_scope"]]

    def buildable(r, m):
        try:
            build_queries(r, m)
            return True
        except ValueError:
            return False
    common = [r for r in scoped if all(buildable(r, m) for m in modes)]
    results, skipped = {}, {}
    if common:
        ids = {r["id"] for r in common}
        sub = [r for r in rows if r["out_of_scope"] or r["id"] in ids]
        for m in modes:
            results[m] = evaluate(retriever, sub, rerank_n=rerank_n, query_mode=m)
        return {"results": results, "skipped": skipped, "common_n": len(common), "excluded": [r["id"] for r in scoped if r["id"] not in ids]}
    for m in modes:
        try:
            results[m] = evaluate(retriever, rows, rerank_n=rerank_n, query_mode=m)
        except ValueError as e:
            skipped[m] = str(e)
    return {"results": results, "skipped": skipped, "common_n": None, "excluded": []}


def format_report(rep: dict, target: float = 0.7) -> str:
    text = format_table(rep["all"], target)
    if rep["unassisted"] is not None:
        a = rep["unassisted"]
        text += f"\n\n[도구 제안 없이 직접 단 라벨만, 문제 {a['n_scored']}개] — 제안에서 고른 라벨은 도구가 잘 찾는 쪽으로 쏠려 점수가 부풀 수 있어 따로 본다\n" + format_table(a, target)
    return text


def format_compare(cmp: dict) -> str:
    if not cmp["results"]:
        return "비교할 수 있는 쿼리 방식이 없다: " + "; ".join(cmp["skipped"].values())
    stages = list(next(iter(cmp["results"].values()))["table"])
    lines = []
    if cmp.get("common_n"):
        ex = cmp.get("excluded") or []
        lines.append(f"비교 대상: 모든 방식을 만들 수 있는 문제 {cmp['common_n']}개 (선지가 없거나 합답형이라 제외 {len(ex)}개: {', '.join(ex)})")
        lines.append("※ 같은 문제로 비교하므로 방식 간 차이는 비교 가능하지만, 전체 문제 점수(eval 기본 명령)와는 문제 수가 다르다")
    for label, key in (("Recall@5", "R@5"), ("Recall@1", "R@1")):
        lines.append(f"쿼리 방식별 {label} (단계별)")
        lines.append(f"{'쿼리 방식':12}" + "".join(f"{s:>22}" for s in stages))
        for m, res in cmp["results"].items():
            lines.append(f"{m:12}" + "".join(f"{res['table'][s][key]:22.2f}" for s in stages))
    for m, why in cmp["skipped"].items():
        lines.append(f"({m} 는 건너뜀: {why.split(':', 1)[-1].strip()})")
    return "\n".join(lines)


# ───────── 대화형 라벨링 ─────────
def keyword_search(retriever, text: str, n: int = 8) -> list[int]:
    """검색어와 일치한 조문만 돌려준다. 점수 0 인 조문을 코퍼스 순서로 채워 넣으면 라벨러가 검색 결과로 오해한다."""
    scores = bm25_scores(retriever.tokenize(text), retriever.docs_tokens)
    return [i for i in rank_of(scores) if scores[i] > 0][:n]


def label_session(retriever, questions: list[dict], done_ids, input_fn=input, print_fn=print, on_row=None) -> list[dict]:
    """키워드로 직접 조문을 찾아 정답을 고른다.  입력: 글자=키워드 검색 / 1,3=직전 목록에서 선택 / g 라벨=정확한 라벨 추가
    / a=도구 제안 보기(고르면 assisted 표시) / d=이 문제 저장 / o=코퍼스 밖 / s=건너뜀 / q=종료"""
    ids = {c["id"]: i for i, c in enumerate(retriever.chunks)}
    rows = []
    for q in questions:
        if q["id"] in done_ids:
            continue
        stem = str(q.get("stem") or q.get("query") or "")
        print_fn(f"\n===== {q['id']}  {q.get('exam', '')} {q.get('number', '')}번 =====\n{stem[:500]}")
        for i, c in enumerate(q.get("choices") or [], 1):
            mark = " ← 정답" if q.get("answer") == i else ""
            print_fn(f"  {i}) {str(c)[:200]}{mark}")
        gold, assisted, last, src = [], False, [], "search"
        while True:
            cmd = input_fn("라벨> ").strip()
            if cmd == "q":
                return rows
            if cmd == "s":
                break
            if cmd == "o":
                row = {**{k: q.get(k) for k in ("id", "exam", "number", "stem", "choices", "answer") if k in q}, "gold": [], "out_of_scope": True, "assisted": False}
                rows.append(row); on_row and on_row(row); break
            if cmd == "d":
                if not gold:
                    print_fn("정답 조문이 없다. 고르거나(o: 코퍼스 밖, s: 건너뜀) 다시 검색한다")
                    continue
                row = {**{k: q.get(k) for k in ("id", "exam", "number", "stem", "choices", "answer") if k in q}, "gold": gold, "out_of_scope": False, "assisted": assisted}
                rows.append(row); on_row and on_row(row); break
            if cmd == "a":
                full = " ".join([stem] + [str(c) for c in (q.get("choices") or [])])
                stages = retriever.stages(full)                          # 한 번만 계산한다 (BGE 질의 임베딩이 두 번 돌지 않게)
                last = stages["Hybrid(RRF)" if "Hybrid(RRF)" in stages else "BM25(단어)"][:10]
                src = "assisted"
                for n, i in enumerate(last, 1):
                    print_fn(f"  {n:2d}. {retriever.chunks[i]['id']}  {retriever.chunks[i]['title']}")
                continue
            if cmd.startswith("g "):
                label = re.sub(r"\s+", " ", cmd[2:]).strip()
                if label in ids:
                    gold.append(label); print_fn(f"  추가: {label}  (현재 {gold})")
                else:
                    print_fn(f"  코퍼스에 없는 라벨: {label}")
                continue
            if re.fullmatch(r"\d+(,\d+)*", cmd):
                if not last:
                    print_fn("  먼저 검색어를 입력하거나 a 를 눌러 목록을 본다"); continue
                for tok in cmd.split(","):
                    k = int(tok)
                    if 1 <= k <= len(last):
                        gold.append(retriever.chunks[last[k - 1]]["id"])
                        assisted = assisted or src == "assisted"
                print_fn(f"  현재 정답 {gold}")
                continue
            last = keyword_search(retriever, cmd)
            src = "search"
            if not last:
                print_fn("  일치하는 조문이 없다. 다른 단어로 검색한다 (조사를 뺀 핵심 단어가 잘 맞는다)")
            for n, i in enumerate(last, 1):
                print_fn(f"  {n:2d}. {retriever.chunks[i]['id']}  {retriever.chunks[i]['title']}")
    return rows


_DELEGATION = re.compile(r"(대통령령|국토교통부령|총리령|시행규칙)(으로|에서)\s*정")


def looks_like_delegation(text: str) -> bool:
    """본문이 짧고 '대통령령으로 정한다'류로 위임만 하는 조문처럼 보이는가. 힌트일 뿐이고 최종 판단은 사람이 한다."""
    body = _REVISION_NOTE.sub(" ", text)
    return len(re.sub(r"\s+", "", body)) < 320 and bool(_DELEGATION.search(body))


def audit_text(chunks: list[dict], gold_rows: list[dict], numbers=None, snippet: int = 280) -> str:
    """라벨을 기준에 맞춰 훑는 용도: 문제 번호별로 정답 선지, 단 라벨, 라벨 조문 본문(앞부분)을 보여 준다."""
    by_id = {c["id"]: c for c in chunks}
    want = set(numbers) if numbers else None
    out, shown = [], 0
    for r in sorted(gold_rows, key=lambda x: (x.get("number") is None, x.get("number") or 0)):
        n = r.get("number")
        if want is not None and n not in want:
            continue
        shown += 1
        ans = r.get("answer")
        choices = r.get("choices") or []
        ans_text = f" {choices[ans - 1][:80]}" if isinstance(ans, int) and 1 <= ans <= len(choices) and choices[ans - 1].strip() else ""
        out.append(f"\n===== {r.get('exam', '')} {n}번 (id {r['id']}) | 정답 {ans}번{ans_text}")
        out.append(f"지문: {(r.get('stem') or r.get('query') or '')[:150]}")
        if r["out_of_scope"]:
            out.append("  → 범위 밖(OUT)으로 라벨됨")
            continue
        out.append(f"  단 라벨 {len(r['gold'])}개:")
        for g in r["gold"]:
            c = by_id.get(g)
            if c is None:
                out.append(f"   ✗ {g}  (코퍼스에 없는 라벨!)")
                continue
            flag = "  ← 위임 의심" if looks_like_delegation(c["text"]) else ""
            out.append(f"   • {g}{flag}\n       {c['text'][:snippet]}")
    if want is not None:
        missing = sorted(want - {r.get("number") for r in gold_rows})
        if missing:
            out.append(f"\n※ gold 에 없는 번호: {missing}")
    if not shown:
        out.append("표시할 문제가 없다")
    return "\n".join(out)


def selftest_rows(chunks) -> list[dict]:
    """배관 점검용: 조문 제목으로 그 조문을 찾는지. **정확도가 아니다**(제목 단어가 그대로 본문 앞에 있어 쉽다)."""
    return [{"id": f"self-{i}", "query": c["title"], "gold": [c["id"]], "out_of_scope": False}
            for i, c in enumerate(chunks) if c["kind"] == "조문" and len(c["title"]) >= 4]


def format_table(result: dict, target: float = 0.7) -> str:
    n = result["n_scored"]
    lines = [f"문제 {n}개 평가 (코퍼스 밖 {result['n_out_of_scope']}개는 제외)",
             f"{'단계':22}{'R@1':>7}{'R@3':>7}{'R@5':>7}{'R@10':>7}{'MRR':>7}   R@5 95% 구간"]
    for name, t in result["table"].items():
        lo, hi = t["R@5_ci95"]
        lines.append(f"{name:22}{t['R@1']:7.2f}{t['R@3']:7.2f}{t['R@5']:7.2f}{t['R@10']:7.2f}{t['MRR']:7.2f}   [{lo:.2f}, {hi:.2f}]")
    best = max(result["table"].items(), key=lambda kv: kv[1]["R@5"])
    lines.append(f"\n목표 R@5 ≥ {target}: 가장 높은 단계 '{best[0]}' = {best[1]['R@5']:.2f} "
                 f"({'도달' if best[1]['R@5'] >= target else '미달'}). 문제 수 {n}개라 구간이 넓다 — 구간 아래쪽이 목표를 넘는지도 본다.")
    return "\n".join(lines)
