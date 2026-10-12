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
