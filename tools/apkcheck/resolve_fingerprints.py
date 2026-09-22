#!/usr/bin/env python3
"""
Resolve the extracted piko fingerprints against an indexed APK, reproducing
Morphe's ``Fingerprint`` matching rules (morphe-patcher ``Fingerprint.kt`` and
``StringComparisonType.kt``).

Verdicts
--------
``OK``
    Exactly one method in the APK matches, so the patch resolves
    deterministically.
``AMBIGUOUS``
    More than one method matches. Morphe keeps whichever candidate it walks
    first, which depends on dex/class iteration order, so the patch can bind to
    the wrong method even though patching appears to succeed.
``NONE``
    Nothing matches. Any patch that dereferences ``.method`` throws.
``PARTIAL``
    Some argument (``filters``, ``custom``, a dynamic string, ...) could not be
    evaluated statically, so the hits are an over-approximation: the real
    fingerprint is at least as strict. Counts are upper bounds.

Usage:
    python3 tools/apkcheck/resolve_fingerprints.py \
        --index index.tsv --fingerprints fingerprints.json --json report.json
"""

from __future__ import annotations

import argparse
import collections
import json
import sys

# AccessFlags values from dexlib2 (com.android.tools.smali.dexlib2.AccessFlags).
ACCESS_FLAGS = {
    "PUBLIC": 0x1,
    "PRIVATE": 0x2,
    "PROTECTED": 0x4,
    "STATIC": 0x8,
    "FINAL": 0x10,
    "SYNCHRONIZED": 0x20,
    "VOLATILE": 0x40,
    "BRIDGE": 0x40,
    "TRANSIENT": 0x80,
    "VARARGS": 0x80,
    "NATIVE": 0x100,
    "INTERFACE": 0x200,
    "ABSTRACT": 0x400,
    "STRICTFP": 0x800,
    "SYNTHETIC": 0x1000,
    "ANNOTATION": 0x2000,
    "ENUM": 0x4000,
    "CONSTRUCTOR": 0x10000,
    "DECLARED_SYNCHRONIZED": 0x20000,
}

SCALAR_KEYS = ("definingClass", "name", "returnType")
LIST_KEYS = ("parameters", "strings")
OPAQUE_KEYS = ("filters", "custom", "classFingerprint")

# Fingerprints targeting piko's own injected extension classes rather than
# anything in the stock APK. They cannot resolve pre-patch, and are reported
# separately so they do not drown out real regressions.
EXTENSION_PREFIX = "Lapp/morphe/extension"

# Stop collecting candidates once a fingerprint is clearly ambiguous.
MAX_HITS = 12


def comparison_for(declaration):
    """Port of ``StringComparisonType.typeDeclarationToComparison``."""
    if declaration is None:
        return "EQUALS"
    if not declaration:
        raise ValueError("type cannot be empty")

    first = declaration[0]

    if len(declaration) == 1:
        if first in "BCDFIJSVZ":
            return "EQUALS"
        if first in ("L", "["):
            return "STARTS_WITH"
        raise ValueError(f"Unknown type declaration: {declaration}")

    ends_with_semicolon = declaration.endswith(";")

    if first == "[":
        return "EQUALS" if ends_with_semicolon else "STARTS_WITH"
    if first == "L":
        return "EQUALS" if ends_with_semicolon else "STARTS_WITH"
    if ends_with_semicolon:
        return "ENDS_WITH"
    return "CONTAINS"


def compare(kind, target, search):
    if kind == "EQUALS":
        return target == search
    if kind == "CONTAINS":
        return search in target
    if kind == "STARTS_WITH":
        return target.startswith(search)
    if kind == "ENDS_WITH":
        return target.endswith(search)
    raise ValueError(kind)


class ApkIndex:
    """Methods, and the string literals each method loads, from an index TSV."""

    def __init__(self, path):
        # Each method: (class, name, params tuple, returnType, accessFlags, insnCount)
        self.methods = []
        self.methods_by_class = collections.defaultdict(list)
        self.class_count = 0
        self._strings = collections.defaultdict(set)

        # surrogateescape: a few Instagram string constants are not valid UTF-8.
        with open(path, encoding="utf-8", errors="surrogateescape") as handle:
            for line in handle:
                kind, _, rest = line.rstrip("\n").partition("\t")
                if kind == "M":
                    parts = rest.split("\t")
                    if len(parts) < 6:
                        continue
                    cls, name, params, ret, access, insns = parts[:6]
                    record = (
                        cls,
                        name,
                        tuple(params.split(",")) if params else (),
                        ret,
                        int(access),
                        int(insns),
                    )
                    self.methods_by_class[cls].append(len(self.methods))
                    self.methods.append(record)
                elif kind == "S":
                    parts = rest.split("\t")
                    if len(parts) < 5:
                        continue
                    self._strings[(parts[0], parts[1], parts[2])].add(parts[4])
                elif kind == "C":
                    self.class_count += 1

    def strings_of(self, cls, name, params):
        return self._strings.get((cls, name, ",".join(params)), frozenset())


def method_matches(fingerprint, record, index):
    """Apply every statically-evaluable constraint of a fingerprint."""
    cls, name, params, return_type, access, _insns = record

    wanted_name = fingerprint.get("name")
    if isinstance(wanted_name, str) and wanted_name != name:
        return False

    defining_class = fingerprint.get("definingClass")
    if isinstance(defining_class, str):
        if not compare(comparison_for(defining_class), cls, defining_class):
            return False

    access_names = fingerprint.get("accessFlags") or []

    wanted_return = fingerprint.get("returnType")
    if isinstance(wanted_return, str):
        # Morphe drops returnType when a constructor declares it as "V".
        if not ("CONSTRUCTOR" in access_names and wanted_return == "V"):
            if not compare(comparison_for(wanted_return), return_type, wanted_return):
                return False

    if access_names:
        wanted = 0
        for flag in access_names:
            wanted |= ACCESS_FLAGS[flag]
        if wanted != access:
            return False

    wanted_params = fingerprint.get("parameters")
    if isinstance(wanted_params, list) and not fingerprint.get("parameters_dynamic"):
        if len(wanted_params) != len(params):
            return False
        for wanted_param, actual in zip(wanted_params, params):
            if not isinstance(wanted_param, str):
                return False
            if not compare(comparison_for(wanted_param), actual, wanted_param):
                return False

    wanted_strings = fingerprint.get("strings")
    if isinstance(wanted_strings, list) and not fingerprint.get("strings_dynamic"):
        present = index.strings_of(cls, name, params)
        for wanted_string in wanted_strings:
            if not isinstance(wanted_string, str):
                return False
            # Morphe compares method strings with String.contains.
            if not any(wanted_string in candidate for candidate in present):
                return False

    return True


def dynamic_reasons(fingerprint):
    """Arguments this checker cannot evaluate, which weaken its verdict."""
    reasons = [key for key in OPAQUE_KEYS if key in fingerprint]
    reasons += [f"{key}=dynamic" for key in SCALAR_KEYS
                if isinstance(fingerprint.get(key), dict)]
    reasons += [f"{key}=dynamic" for key in LIST_KEYS
                if isinstance(fingerprint.get(key), dict) or fingerprint.get(key + "_dynamic")]
    if fingerprint.get("positional"):
        reasons.append("positional-args")
    return reasons


def has_usable_constraint(fingerprint):
    if any(isinstance(fingerprint.get(key), str) for key in SCALAR_KEYS):
        return True
    for key in LIST_KEYS:
        if isinstance(fingerprint.get(key), list) and not fingerprint.get(key + "_dynamic"):
            return True
    return bool(fingerprint.get("accessFlags"))


def targets_extension(fingerprint):
    defining_class = fingerprint.get("definingClass")
    return isinstance(defining_class, str) and defining_class.startswith(EXTENSION_PREFIX)


def describe(record):
    cls, name, params, return_type, access, insns = record
    return f"{cls}->{name}({','.join(params)}){return_type} acc={access} insns={insns}"


def resolve(fingerprint, index):
    reasons = dynamic_reasons(fingerprint)

    if not has_usable_constraint(fingerprint):
        return "PARTIAL", reasons or ["no-static-constraint"], []

    # Narrow candidates when the defining class is an exact type.
    defining_class = fingerprint.get("definingClass")
    if isinstance(defining_class, str) and comparison_for(defining_class) == "EQUALS":
        candidates = index.methods_by_class.get(defining_class, [])
    else:
        candidates = range(len(index.methods))

    hits = []
    for position in candidates:
        if method_matches(fingerprint, index.methods[position], index):
            hits.append(position)
            if len(hits) > MAX_HITS:
                break

    if len(hits) == 1:
        verdict = "OK"
    elif not hits:
        verdict = "NONE"
    else:
        verdict = "AMBIGUOUS"

    # An over-approximated match set cannot prove uniqueness.
    if reasons and verdict in ("OK", "AMBIGUOUS"):
        verdict = "PARTIAL"

    return verdict, reasons, [describe(index.methods[i]) for i in hits[:6]]


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--index", required=True, help="index TSV produced by Idx.java")
    parser.add_argument("--fingerprints", required=True,
                        help="JSON produced by extract_fingerprints.py")
    parser.add_argument("--json", help="write the full report to this path")
    parser.add_argument("--only", help="report a single fingerprint by name")
    args = parser.parse_args()

    index = ApkIndex(args.index)
    print(f"index: {index.class_count} classes, {len(index.methods)} methods", file=sys.stderr)

    with open(args.fingerprints, encoding="utf-8") as handle:
        fingerprints = json.load(handle)

    report = []
    for fingerprint in fingerprints:
        if args.only and fingerprint["fpName"] != args.only:
            continue
        verdict, reasons, hits = resolve(fingerprint, index)
        report.append({
            "name": fingerprint["fpName"],
            "verdict": verdict,
            "extension": targets_extension(fingerprint),
            "reasons": reasons,
            "file": fingerprint["file"],
            "hits": hits,
        })

    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=1)

    if args.only:
        print(json.dumps(report, indent=1))
        return 0

    app_facing = [r for r in report if not r["extension"]]
    counts = collections.Counter(r["verdict"] for r in app_facing)

    print("\n== APK-facing fingerprints ==")
    for verdict in ("OK", "AMBIGUOUS", "NONE", "PARTIAL"):
        print(f"  {verdict:10} {counts[verdict]}")
    print(f"  (+{len(report) - len(app_facing)} targeting piko extension classes)")

    failures = [r for r in app_facing if r["verdict"] == "NONE"]
    if failures:
        print("\n== NONE (patch would throw) ==")
        for item in failures:
            print(f"  {item['name']}  <- {item['file']}")

    ambiguous = [r for r in app_facing if r["verdict"] == "AMBIGUOUS"]
    if ambiguous:
        print("\n== AMBIGUOUS (binds to whichever method is walked first) ==")
        for item in ambiguous:
            print(f"  {item['name']}  <- {item['file']}")
            for hit in item["hits"]:
                print(f"       {hit[:160]}")

    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
