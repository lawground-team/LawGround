"""Mock 앱의 OpenAPI 를 openapi.json / openapi.yaml 로 내보낸다. Spring 의 docs/api/internal.openapi.yaml 과 diff 하는 용도."""
import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from app.mock.server import app  # noqa: E402

spec = app.openapi()
(ROOT / "openapi.json").write_text(json.dumps(spec, ensure_ascii=False, indent=2), encoding="utf-8")
(ROOT / "openapi.yaml").write_text(yaml.safe_dump(spec, allow_unicode=True, sort_keys=False), encoding="utf-8")
print("exported", len(spec["paths"]), "paths,", len(spec["components"]["schemas"]), "schemas")

# 0.2.0-draft 제안 경로만 따로 (실제 앱에서 tags=["0.2.0-draft"] 인 경로)
from fastapi.openapi.utils import get_openapi  # noqa: E402
from app.contract import models as M  # noqa: E402
from app.main import app as real  # noqa: E402
routes = [r for r in real.routes if "0.2.0-draft" in (getattr(r, "tags", None) or [])]
draft = get_openapi(title="LawGround Python AI 내부 API — 0.2.0-draft 제안 경로", version=M.CONTRACT_VERSION_02, routes=routes,
                    description="docs/api/proposal-0.2.0-statement-and-similar.md 의 Python 쪽 경로. 헤더 X-Contract-Version: 0.2.0-draft. 0.1.0 경로는 openapi.yaml 그대로.")
(ROOT / "openapi-0.2.0-draft.yaml").write_text(yaml.safe_dump(draft, allow_unicode=True, sort_keys=False), encoding="utf-8")
print("exported 0.2.0-draft", sorted(draft["paths"]))
