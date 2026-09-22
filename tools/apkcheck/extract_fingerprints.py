#!/usr/bin/env python3
"""
Parse the ``object X : Fingerprint(...)`` declarations out of the piko patch
sources into JSON, so they can be resolved against a target APK by
``resolve_fingerprints.py``.

This is a *static* reader of the Kotlin sources, not a Kotlin compiler. It is
deliberately conservative: any argument it cannot evaluate to a literal is
recorded as ``{"raw": "<source text>"}`` and the fingerprint is flagged as
partially dynamic, so the resolver downgrades its verdict instead of silently
treating the constraint as absent.

Usage:
    python3 tools/apkcheck/extract_fingerprints.py --repo-root . -o fingerprints.json
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

PATCH_ROOT = "patches/src/main/kotlin/app/crimera/patches/instagram"
CONSTANTS_REL = "utils/Constants.kt"

# `object Foo : Fingerprint(` — optionally prefixed with a visibility modifier.
FINGERPRINT_RE = re.compile(
    r"(?:internal\s+|private\s+|public\s+)?object\s+(\w+)\s*:\s*Fingerprint\s*\(", re.M
)

# Arguments holding a single type/name string.
SCALAR_KEYS = ("definingClass", "name", "returnType")
# Arguments holding a list of strings.
LIST_KEYS = ("parameters", "strings")
# Arguments this reader cannot evaluate; presence alone makes a fingerprint dynamic.
OPAQUE_KEYS = ("filters", "custom", "classFingerprint")

# Kotlin escape sequences -> the character they denote. The backslash escape is
# applied separately, last, so it cannot re-process the replacements above it.
KOTLIN_ESCAPES = (
    ("\\$", "$"),
    ('\\"', '"'),
    ("\\'", "'"),
    ("\\t", "\t"),
    ("\\n", "\n"),
    ("\\r", "\r"),
)

# Marks an interpolation this reader could not resolve.
_DYNAMIC = "\x00DYN\x00"


def load_constants(repo_root: str) -> dict:
    """
    Read ``const val`` string constants, resolving ``$X`` interpolation.

    Constants live both in ``utils/Constants.kt`` and next to the fingerprints
    that use them (for example the ``*_CLASS_DESCRIPTOR`` values naming piko's
    own extension classes), so every patch source is scanned. ``Constants.kt``
    is read first so shared definitions win any name collision.
    """
    shared = os.path.join(repo_root, PATCH_ROOT, CONSTANTS_REL)
    sources = []
    with open(shared, encoding="utf-8") as handle:
        sources.append(handle.read())

    root = os.path.join(repo_root, PATCH_ROOT)
    for dirpath, _dirnames, filenames in os.walk(root):
        for filename in sorted(filenames):
            if not filename.endswith(".kt"):
                continue
            path = os.path.join(dirpath, filename)
            if os.path.samefile(path, shared):
                continue
            with open(path, encoding="utf-8") as handle:
                sources.append(handle.read())

    constants: dict = {}
    for source in sources:
        for match in re.finditer(r'const val (\w+)\s*=\s*"((?:[^"\\]|\\.)*)"', source):
            constants.setdefault(match.group(1), match.group(2))

    # Constants reference each other; iterate to a fixed point.
    for _ in range(8):
        changed = False
        for key, value in list(constants.items()):

            def substitute(match):
                name = match.group(1) or match.group(2)
                return constants.get(name, match.group(0))

            resolved = re.sub(r"\$\{(\w+)\}|\$(\w+)", substitute, value)
            if resolved != value:
                constants[key] = resolved
                changed = True
        if not changed:
            break

    for key, value in constants.items():
        constants[key] = value.replace("\\$", "$").replace("\\\\", "\\")
    return constants


def split_top_level_args(text: str) -> list:
    """Split a Kotlin argument list on commas that are not nested or quoted."""
    args = []
    depth = 0
    current = ""
    index = 0
    quote = None

    while index < len(text):
        char = text[index]

        if quote:
            current += char
            if char == "\\" and quote == '"':
                if index + 1 < len(text):
                    current += text[index + 1]
                    index += 2
                    continue
            elif text.startswith(quote, index):
                quote = None
            index += 1
            continue

        if text.startswith('"""', index):
            quote = '"""'
            current += '"""'
            index += 3
            continue
        if char == '"':
            quote = '"'
            current += char
            index += 1
            continue

        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1

        if char == "," and depth == 0:
            args.append(current.strip())
            current = ""
            index += 1
            continue

        current += char
        index += 1

    if current.strip():
        args.append(current.strip())
    return args


def resolve_string(expr: str, constants: dict):
    """Evaluate a Kotlin string expression, or return None when dynamic."""
    expr = expr.strip()

    literal = re.fullmatch(r'"((?:[^"\\]|\\.)*)"', expr)
    if literal:
        value = literal.group(1)

        def substitute(match):
            name = match.group(1) or match.group(2)
            return constants.get(name, _DYNAMIC)

        value = re.sub(r"\$\{(\w+)\}|\$(\w+)", substitute, value)
        for escape, char in KOTLIN_ESCAPES:
            value = value.replace(escape, char)
        value = value.replace("\\\\", "\\")
        return None if _DYNAMIC in value else value

    # A bare identifier referring to a known constant.
    if re.fullmatch(r"\w+", expr) and expr in constants:
        return constants[expr]

    return None


def parse_string_list(expr: str, constants: dict):
    """Evaluate ``listOf("a", "b")``. Returns (values, is_dynamic)."""
    expr = expr.strip()
    if expr in ("emptyList()", "listOf()"):
        return [], False

    match = re.match(r"^listOf(?:<[^>]*>)?\s*\((.*)\)$", expr, re.S)
    if not match:
        return None, True

    inner = match.group(1).strip()
    if not inner:
        return [], False

    values = []
    dynamic = False
    for arg in split_top_level_args(inner):
        value = resolve_string(arg, constants)
        if value is None:
            dynamic = True
            values.append({"raw": arg})
        else:
            values.append(value)
    return values, dynamic


def find_matching_paren(source: str, open_index: int) -> int:
    """Index of the ``)`` closing the ``(`` at ``open_index``, or -1."""
    depth = 0
    index = open_index
    quote = None

    while index < len(source):
        char = source[index]

        if quote:
            if quote == '"' and char == "\\":
                index += 2
                continue
            if source.startswith(quote, index):
                index += len(quote)
                quote = None
                continue
            index += 1
            continue

        if source.startswith('"""', index):
            quote = '"""'
            index += 3
            continue
        if char == '"':
            quote = '"'
            index += 1
            continue

        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return index
        index += 1

    return -1


def parse_fingerprint(name: str, body: str, path: str, constants: dict) -> dict:
    """Turn one fingerprint argument list into a JSON-serialisable record."""
    record = {"fpName": name, "file": path, "raw": body.strip()}

    for arg in split_top_level_args(body):
        if "=" not in arg:
            # Positional arguments are ambiguous across Fingerprint's overloads.
            record.setdefault("positional", []).append(arg)
            continue

        key, value = arg.split("=", 1)
        key, value = key.strip(), value.strip()

        if key in SCALAR_KEYS:
            resolved = resolve_string(value, constants)
            record[key] = resolved if resolved is not None else {"raw": value}
        elif key in LIST_KEYS:
            values, dynamic = parse_string_list(value, constants)
            record[key] = values if values is not None else {"raw": value}
            if dynamic:
                record[key + "_dynamic"] = True
        elif key == "accessFlags":
            record[key] = re.findall(r"AccessFlags\.(\w+)", value)
        else:
            record[key] = {"raw": value}

    return record


def collect(repo_root: str) -> list:
    constants = load_constants(repo_root)
    results = []

    root = os.path.join(repo_root, PATCH_ROOT)
    for dirpath, _dirnames, filenames in os.walk(root):
        for filename in sorted(filenames):
            if not filename.endswith(".kt"):
                continue
            path = os.path.join(dirpath, filename)
            with open(path, encoding="utf-8") as handle:
                source = handle.read()

            for match in FINGERPRINT_RE.finditer(source):
                open_index = source.index("(", match.end() - 1)
                close_index = find_matching_paren(source, open_index)
                if close_index < 0:
                    print(f"warn: unbalanced parens for {match.group(1)} in {path}",
                          file=sys.stderr)
                    continue
                body = source[open_index + 1 : close_index]
                rel = os.path.relpath(path, repo_root)
                results.append(parse_fingerprint(match.group(1), body, rel, constants))

    return results


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--repo-root", default=".", help="repository root (default: .)")
    parser.add_argument("-o", "--output", default="fingerprints.json",
                        help="output JSON path (default: fingerprints.json)")
    args = parser.parse_args()

    fingerprints = collect(args.repo_root)
    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(fingerprints, handle, indent=1)

    dynamic = sum(
        1
        for fp in fingerprints
        if any(isinstance(fp.get(k), dict) for k in SCALAR_KEYS)
        or any(isinstance(fp.get(k), dict) or fp.get(k + "_dynamic") for k in LIST_KEYS)
        or any(k in fp for k in OPAQUE_KEYS)
    )
    print(f"parsed {len(fingerprints)} fingerprints -> {args.output}")
    print(f"  {dynamic} have at least one argument this reader cannot evaluate")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
