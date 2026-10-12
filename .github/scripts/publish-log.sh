#!/usr/bin/env bash
# Developer aid: publishes a compact build log (errors first, then the tail) and/or extra files to the
# 'ci-logs' branch, so failures can be read without opening the Actions UI. Safe to delete the branch at any time.
#   publish-log.sh <name> <logfile> [extra-file-or-dir ...]
set -uo pipefail
NAME="$1"; FILE="$2"; shift 2
RUN="${GITHUB_RUN_NUMBER:-0}"
URL="https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
TMP="$(mktemp -d)"

if ! git clone --quiet --depth 1 --branch ci-logs "$URL" "$TMP" 2>/dev/null; then
  git init -q "$TMP"; git -C "$TMP" checkout -q -b ci-logs; git -C "$TMP" remote add origin "$URL"
fi
OUT="$TMP/run-$RUN"; mkdir -p "$OUT"

if [ -f "$FILE" ]; then
  {
    echo "# $NAME | run $RUN | sha ${GITHUB_SHA:-?} | $(date -u +%FT%TZ)"
    echo "## errors and key lines"
    grep -nE '^e: |error:|FAILED|What went wrong|Could not|requires libraries|Caused by|BUILD (SUCCESSFUL|FAILED)|tests completed|Unresolved|Execution failed' "$FILE" | cut -c1-320 | head -200
    echo; echo "## tail"; tail -n 150 "$FILE" | cut -c1-320
  } > "$OUT/$NAME.txt"
fi
for extra in "$@"; do [ -e "$extra" ] && cp -r "$extra" "$OUT/" ; done

cd "$TMP" || exit 0
git config user.name "ci-logs"; git config user.email "ci-logs@users.noreply.github.com"
git add -A; git commit -qm "$NAME run $RUN" || exit 0
for i in 1 2 3 4; do
  git push -q origin ci-logs && exit 0
  git pull -q --rebase origin ci-logs || sleep 2
done
exit 0
