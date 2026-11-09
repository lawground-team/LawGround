"""루트 .env 의 값을 안전하게 바꾼다(같은 키가 이미 있으면 그 줄을 교체하고 중복은 지우고, 없으면 끝에 추가한다).
메모장으로 고칠 때 생기는 중복 줄·오타를 막는 용도다. 주석과 다른 줄은 그대로 두고, 줄바꿈 방식(CRLF/LF)도 유지한다.
KEY·TOKEN·SECRET·PASSWORD 가 들어간 키의 값은 화면에 보이지 않는다(길이만). 바꾸기 전 원본을 임시 폴더에 백업한다(저장소 안에 비밀이 든 사본을 두지 않는다).

사용:  python scripts\\env_set.py --file ..\\.env 키=값 키=값 ...        (--dry-run 이면 파일을 바꾸지 않고 결과만 보여 준다)
"""
import argparse
import re
import shutil
import sys
import tempfile
import time
from pathlib import Path

KEY_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
LINE_RE = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
SECRET_RE = re.compile(r"(KEY|TOKEN|SECRET|PASSWORD)", re.I)


def parse_pairs(pairs: list[str]) -> dict[str, str]:
    out: dict[str, str] = {}
    for p in pairs:
        if "=" not in p:
            raise ValueError(f"'키=값' 형식이 아니다: {p.split('=')[0][:40]}")
        k, v = p.split("=", 1)
        if not KEY_RE.match(k):
            raise ValueError(f"키 이름이 올바르지 않다: {k[:40]}")
        if "\n" in v or "\r" in v or v != v.strip() or '"' in v or "'" in v:
            raise ValueError(f"{k} 의 값에는 줄바꿈·앞뒤 공백·따옴표를 쓸 수 없다")
        if k in out:
            raise ValueError(f"{k} 가 두 번 지정됐다")
        out[k] = v
    return out


def show(key: str, value: str | None) -> str:
    if value is None:
        return "(없음)"
    return f"(비밀값 {len(value)}자)" if SECRET_RE.search(key) else (value if value else "(빈 값)")


def apply(text: str, updates: dict[str, str]) -> tuple[str, list[tuple[str, str | None, str, int]]]:
    """(새 본문, [(키, 이전 값, 새 값, 지운 중복 줄 수)])"""
    newline = "\r\n" if "\r\n" in text else "\n"
    first: dict[str, str] = {}
    dups: dict[str, int] = {}
    out: list[str] = []
    for line in text.splitlines():
        m = LINE_RE.match(line)
        if m and m.group(1) in updates:
            k = m.group(1)
            if k in first:
                dups[k] = dups.get(k, 0) + 1          # 같은 키의 둘째 줄부터는 지운다
                continue
            first[k] = m.group(2)
            out.append(f"{k}={updates[k]}")
            continue
        out.append(line)
    for k, v in updates.items():
        if k not in first:
            out.append(f"{k}={v}")
    changes = [(k, first.get(k), v, dups.get(k, 0)) for k, v in updates.items()]
    return newline.join(out) + newline, changes


def main(argv=None):
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8")
        except Exception:
            pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", required=True); ap.add_argument("--dry-run", action="store_true"); ap.add_argument("pairs", nargs="+")
    a = ap.parse_args(argv)
    path = Path(a.file)
    if not path.is_file():
        sys.exit(f".env 를 찾지 못했다: {path}")
    try:
        updates = parse_pairs(a.pairs)
    except ValueError as e:
        sys.exit(str(e))
    text = path.read_text(encoding="utf-8")
    new_text, changes = apply(text, updates)
    for k, old, new, dup in changes:
        note = "변경 없음" if old == new and not dup else ("추가" if old is None else "변경")
        print(f"  {k}: {show(k, old)} → {show(k, new)}  [{note}{f', 중복 {dup}줄 삭제' if dup else ''}]")
    if a.dry_run:
        print("--dry-run: 파일은 바꾸지 않았다")
        return
    if new_text == text:
        print("바꿀 내용이 없다")
        return
    backup = Path(tempfile.gettempdir()) / f"lawground-env-backup-{time.strftime('%Y%m%d-%H%M%S')}.env"
    shutil.copy2(path, backup)
    path.write_text(new_text, encoding="utf-8", newline="")
    print(f"저장 완료. 원본 백업(저장소 밖): {backup}")


if __name__ == "__main__":
    main()
