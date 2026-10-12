"""Validate synthetic MockMvc response captures against the shared external contract."""
import json
from check_contracts import ROOT, load_document, validate_instance


def main():
    captures = sorted((ROOT / "backend/build/contract-responses").glob("*.json"))
    if not captures:
        raise SystemExit("No current runtime captures. Run backend clean check first.")
    document = load_document(ROOT / "docs/api/external.openapi.yaml")
    schemas = set()
    for path in captures:
        record = json.loads(path.read_text(encoding="utf-8"))
        validate_instance(document, record["schema"], record["body"])
        schemas.add(record["schema"])
    print(f"Runtime responses validated: {len(captures)} captures, schemas={','.join(sorted(schemas))}")


if __name__ == "__main__":
    main()
