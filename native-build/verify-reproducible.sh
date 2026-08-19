#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="${BASE:-$ROOT/native-build/repro-arm64}"
CACHE="${CACHE:-$ROOT/native-build/cache}"

for run in 1 2; do
  out="$BASE/out-$run"
  work="$BASE/work-$run"
  OUT="$out" WORK="$work" CACHE="$CACHE" SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-0}" "$ROOT/native-build/build-arm64.sh"
  (
    cd "$out"
    find include lib -type f -print0 | sort -z | xargs -0 sha256sum
  ) > "$BASE/tree-$run.sha256"
done

diff -u "$BASE/tree-1.sha256" "$BASE/tree-2.sha256"
cp "$BASE/tree-2.sha256" "$BASE/out-2/evidence/reproducibility.sha256"
printf 'REPRODUCIBLE: %s\n' "$BASE/out-2"
