"""Validate synthetic public responses and internal HTTP captures against shared contracts."""
import json
from check_contracts import ROOT, load_document, validate_instance


def main():
    captures = sorted((ROOT / "backend/build/contract-responses").glob("*.json"))
    if not captures:
        raise SystemExit("No current runtime captures. Run backend clean check first.")
    documents = {name: load_document(ROOT / f"docs/api/{name}.openapi.yaml") for name in ("external", "internal")}
    schemas = set()
    for path in captures:
        record = json.loads(path.read_text(encoding="utf-8"))
        contract = record.get("contract", "external")
        if contract not in documents:
            raise ValueError("Unknown runtime contract")
        validate_instance(documents[contract], record["schema"], record["body"])
        schemas.add(contract + ":" + record["schema"])
    print(f"Runtime responses validated: {len(captures)} captures, schemas={','.join(sorted(schemas))}")


if __name__ == "__main__":
    main()
