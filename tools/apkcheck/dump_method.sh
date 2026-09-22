#!/usr/bin/env bash
# Print the indexed instruction listing for one method.
#
#   dump_method.sh <index.tsv> <classType> <methodName> [paramsCsv]
#
# Omit paramsCsv to show every overload; pass "" for a no-argument method.
set -euo pipefail

index="$1"; cls="$2"; name="$3"
if [ -n "${4+x}" ]; then
  pattern=$'^[SRLO]\t'"$cls"$'\t'"$name"$'\t'"$4"$'\t'
else
  pattern=$'^[SRLO]\t'"$cls"$'\t'"$name"$'\t'
fi

grep -aP "$pattern" "$index" | awk -F'\t' '{
  idx = $5;
  if      ($1 == "S") printf "%5d  const-string  \"%s\"\n", idx, $6;
  else if ($1 == "O") printf "%5d  %s\n", idx, $6;
  else if ($1 == "L") printf "%5d  %s  lit=%s\n", idx, $6, $7;
  else if ($1 == "R") {
    if      ($7 == "F") printf "%5d  %s  %s->%s:%s\n", idx, $6, $8, $9, $10;
    else if ($7 == "M") printf "%5d  %s  %s->%s(%s)%s\n", idx, $6, $8, $9, $10, $11;
    else if ($7 == "T") printf "%5d  %s  %s\n", idx, $6, $8;
    else                printf "%5d  %s  ?\n", idx, $6;
  }
}' | sort -n -k1
