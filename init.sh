#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

echo "== Project root =="
pwd

echo "== Git status =="
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git status -sb
  echo "== Recent commits =="
  git log --oneline -5
else
  echo "Not a git repo"
fi

echo "== Harness files =="
ls -la claude-progress.txt feature_list.json 2>/dev/null || true

echo "== Next steps =="
echo "1) Read claude-progress.txt"
echo "2) Read feature_list.json and pick one feature with passes=false"
echo "3) Implement and test, then update passes for that feature"
echo "4) Append a short entry to claude-progress.txt"

if [[ "${RUN_BUILD:-}" == "1" ]]; then
  echo "== Building debug APK (RUN_BUILD=1) =="
  ./gradlew :app:assembleDebug
fi
