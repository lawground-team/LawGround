"""Check local-file exclusions and tracked-secret boundaries without printing values."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def git(*args, input_text=None):
    return subprocess.run(
        ["git", *args], cwd=ROOT, input=input_text, capture_output=True,
        text=True, encoding="utf-8", check=False,
    )


def main():
    ignored = [
        "AI/week-01.md", "AGENTS.md", "AGENTS.override.md", "CLAUDE.md",
        "CLAUDE.local.md", "backend/AGENTS.md", "backend/CLAUDE.md",
        ".codex/config.toml", ".claude/settings.json", ".agents/skills/local.md",
        ".vscode/settings.json", "backend/.vscode/settings.json",
        ".idea/workspace.xml", "backend/.settings/preferences",
        "backend/.classpath", "backend/.project", "backend/local.iml",
        "local.code-workspace", ".env", "backend/.env.local",
        "backend/build/libs/app.jar", "backend/.gradle/cache.bin",
        "ai-service/.venv/config", "frontend/node_modules/package/index.js",
    ]
    shared = [
        ".env.example", ".gitignore", ".editorconfig", ".gitattributes",
        "backend/gradle/wrapper/gradle-wrapper.jar", "backend/gradlew",
        "backend/src/main/resources/application-local.yml",
        "ai-service/README.md", "ai-service/app/main.py", "docs/api/internal-api.md",
    ]
    failures = []
    for path, expected in [(p, True) for p in ignored] + [(p, False) for p in shared]:
        result = git("check-ignore", "--no-index", "--quiet", path)
        if result.returncode not in (0, 1) or (result.returncode == 0) != expected:
            failures.append("Ignore boundary mismatch: " + path)

    tracked = git("ls-files", "-z")
    if tracked.returncode:
        failures.append("Cannot enumerate tracked files")
        paths = []
    else:
        paths = [p for p in tracked.stdout.split("\0") if p]
    if paths:
        result = git("check-ignore", "--no-index", "--stdin", "-z", input_text="\0".join(paths) + "\0")
        if result.returncode not in (0, 1):
            failures.append("Cannot inspect tracked exclusions")
        for path in filter(None, result.stdout.split("\0")):
            failures.append("Ignored file already tracked: " + path)

    patterns = [
        re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}"),
        re.compile(r"sk-[A-Za-z0-9_-]{20,}"),
        re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    ]
    for path in paths:
        local = ROOT / path
        if not local.is_file() or local.suffix.lower() in (".jar", ".png", ".jpg", ".pdf"):
            continue
        content = local.read_text(encoding="utf-8", errors="replace")
        if any(pattern.search(content) for pattern in patterns):
            failures.append("Potential secret in tracked file: " + path)
        if local.name == ".env.example":
            for line in content.splitlines():
                if line.startswith(("POSTGRES_PASSWORD=", "RABBITMQ_PASSWORD=", "AI_SERVICE_API_KEY=", "LAW_GO_KR_API_KEY=", "GOOGLE_CLIENT_SECRET=")):
                    if line.split("=", 1)[1].strip():
                        failures.append("Example credential must be empty: " + path)

    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"Repository boundaries verified: {len(ignored)} exclusions, {len(shared)} shared paths, {len(paths)} tracked files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
