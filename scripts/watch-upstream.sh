#!/usr/bin/env bash
# Watcher upstream: so sanh tree-hash cua cac duong dan theo doi voi upstream/main.
# Che do:
#   generate  - tinh lai tree-hash cho tung path va ghi de vao .upstream-lock.json
#   check     - (mac dinh) tinh lai va so voi lockfile, lech thi bao DRIFT va exit 1
set -euo pipefail

# Tat MSYS path conversion tren Git Bash Windows, tranh "upstream/main:.github"
# bi doi thanh duong dan Windows sai
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

cd "$(dirname "$0")/.."

LOCKFILE=".upstream-lock.json"
REF="upstream/main"

# Danh sach duong dan theo doi - KHONG them bot
PATHS=(
  "core"
  "lib"
  "common"
  "compiler"
  "gradle"
  "gradle.properties"
  "build.gradle.kts"
  "settings.gradle.kts"
  "CONTRIBUTING.md"
  "AGENTS.md"
  ".github"
)

MODE="${1:-check}"

# Tinh sha cho mot path, in rong neu upstream khong con path do
get_sha() {
  local path="$1"
  git rev-parse "${REF}:${path}" 2>/dev/null || echo ""
}

# Sinh danh sach "path sha" da sort theo path, bo qua path khong con sha
build_current_pairs() {
  local sorted_paths
  sorted_paths=$(printf '%s\n' "${PATHS[@]}" | sort)
  while IFS= read -r path; do
    local sha
    sha=$(get_sha "$path")
    if [ -n "$sha" ]; then
      printf '%s %s\n' "$path" "$sha"
    fi
  done <<< "$sorted_paths"
}

cmd_generate() {
  local pairs
  pairs=$(build_current_pairs)

  {
    echo "{"
    local total
    total=$(printf '%s\n' "$pairs" | sed '/^$/d' | wc -l)
    local i=0
    while IFS=' ' read -r path sha; do
      [ -z "$path" ] && continue
      i=$((i + 1))
      if [ "$i" -lt "$total" ]; then
        printf '  "%s": "%s",\n' "$path" "$sha"
      else
        printf '  "%s": "%s"\n' "$path" "$sha"
      fi
    done <<< "$pairs"
    echo "}"
  } > "$LOCKFILE"

  echo "Generated ${LOCKFILE} from ${REF}"
}

# Doc gia tri sha ung voi mot path tu lockfile JSON (dinh dang don gian, moi dong 1 cap)
lockfile_sha() {
  local path="$1"
  if [ ! -f "$LOCKFILE" ]; then
    echo ""
    return
  fi
  grep -oE "\"${path}\": \"[a-f0-9]*\"" "$LOCKFILE" | head -n1 | sed -E 's/.*"([a-f0-9]*)"$/\1/'
}

cmd_check() {
  local drift=0
  local sorted_paths
  sorted_paths=$(printf '%s\n' "${PATHS[@]}" | sort)

  while IFS= read -r path; do
    local old_sha new_sha
    old_sha=$(lockfile_sha "$path")
    new_sha=$(get_sha "$path")

    if [ "$old_sha" != "$new_sha" ]; then
      drift=1
      echo "DRIFT: ${path} ${old_sha} -> ${new_sha}"
    fi
  done <<< "$sorted_paths"

  return $drift
}

case "$MODE" in
  generate)
    cmd_generate
    ;;
  check)
    cmd_check
    ;;
  *)
    echo "Unknown mode: ${MODE}. Use 'generate' or 'check'." >&2
    exit 2
    ;;
esac
