#!/usr/bin/env python3
"""
Merge whatsAppStatus + customerCategory into customer_master by customerKey,
using a Mongo export file with multiple adjacent JSON objects (Compass-style).

Usage:
  export MONGO_URI='mongodb://localhost:27017/openProject'   # optional
  ./.venv-mongo/bin/python scripts/sync_customer_fields_from_source.py \
    "/path/to/Untitled-export.json"
"""
from __future__ import annotations

import os
import sys
from json import JSONDecoder

from pymongo import MongoClient
from pymongo.errors import ServerSelectionTimeoutError


def parse_adjacent_json_objects(text: str) -> list[dict]:
    decoder = JSONDecoder()
    idx = 0
    n = len(text)
    out: list[dict] = []
    while idx < n:
        while idx < n and text[idx].isspace():
            idx += 1
        if idx >= n:
            break
        obj, end = decoder.raw_decode(text, idx)
        if not isinstance(obj, dict):
            raise ValueError(f"Expected object at offset {idx}")
        out.append(obj)
        idx = end
    return out


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: sync_customer_fields_from_source.py <source-export.json>", file=sys.stderr)
        return 2

    path = sys.argv[1]
    with open(path, encoding="utf-8") as f:
        text = f.read()

    docs = parse_adjacent_json_objects(text)
    # Dedupe by customerKey: keep row with greatest updatedAt (string compare OK for ISO-like timestamps)
    best: dict[str, tuple[str, str | None, str | None]] = {}
    for doc in docs:
        key = doc.get("customerKey")
        if not key or not isinstance(key, str):
            continue
        ts = doc.get("updatedAt") or ""
        if not isinstance(ts, str):
            ts = str(ts)
        wa = doc.get("whatsAppStatus")
        cat = doc.get("customerCategory")
        if wa is not None and not isinstance(wa, str):
            wa = str(wa)
        if cat is not None and not isinstance(cat, str):
            cat = str(cat)
        if key not in best or ts > best[key][0]:
            best[key] = (ts, wa, cat)

    uri = os.environ.get("MONGO_URI", "mongodb://localhost:27017/openProject")
    client = MongoClient(uri, serverSelectionTimeoutMS=45000, connectTimeoutMS=15000)
    try:
        client.admin.command("ping")
    except ServerSelectionTimeoutError as e:
        print(
            "Cannot reach MongoDB.\n"
            "  • Start mongod locally, or\n"
            "  • Set MONGO_URI to the same string you use in Compass, e.g.:\n"
            "      export MONGO_URI='mongodb+srv://...'\n"
            f"  Tried: {uri!r}\n"
            f"  Detail: {e}",
            file=sys.stderr,
        )
        return 1
    db = client.get_default_database()
    coll = db["customer_master"]

    total_keys = len(best)
    matched = 0
    modified = 0
    noop = 0

    for customer_key, (_ts, wa, cat) in best.items():
        set_doc: dict[str, str] = {}
        if wa is not None:
            set_doc["whatsAppStatus"] = wa
        if cat is not None and cat != "":
            set_doc["customerCategory"] = cat
        if not set_doc:
            continue
        res = coll.update_many({"customerKey": customer_key}, {"$set": set_doc})
        matched += res.matched_count
        modified += res.modified_count
        if res.matched_count == 0:
            noop += 1

    print(
        f"Source distinct customerKeys: {total_keys}\n"
        f"Documents matched (sum of update_many matched_count): {matched}\n"
        f"Documents modified: {modified}\n"
        f"Keys with no matching doc in customer_master: {noop}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
