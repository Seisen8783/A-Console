#!/usr/bin/env bash
set -euo pipefail

core_dir=$(cd "$(dirname "$0")/.." && pwd)
temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT
compiler=${KOTLINC:-kotlinc}

"$compiler" "$core_dir/kotlin/com/unreal/core/RouteCore.kt" "$core_dir/checks/CheckCore.kt" \
  -include-runtime -d "$temp_dir/check.jar"
java -jar "$temp_dir/check.jar" "$core_dir/assets/default.csv" > "$temp_dir/samples.csv"

samples=$(wc -l < "$temp_dir/samples.csv" | tr -d ' ')
if [ "$samples" -ne 327 ]; then
  echo "采样点数应为 327，实际 $samples" >&2
  exit 1
fi
echo "Core checks passed. $samples samples."
