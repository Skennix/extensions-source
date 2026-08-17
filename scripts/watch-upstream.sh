#!/usr/bin/env bash
# Watcher upstream: so sanh tree-hash cua cac duong dan theo doi voi upstream/main.
# Che do:
#   generate  - tinh lai tree-hash cho tung path va ghi de vao .upstream-lock.json
#               (ABORT, khong ghi file, neu bat ky path nao khong resolve duoc)
#   check     - (mac dinh) tinh lai va so voi lockfile, lech thi bao DRIFT va exit 1
#   selftest  - dung repo git tam de tu kiem tra logic cua chinh script nay
#
# Quy trinh cap nhat khi job "Watch upstream drift" bao do (drift/loi):
#   1. Xem log job de biet path nao lech.
#   2. Quyet dinh: neu can dong bo, chay "git merge upstream/main" (fetch upstream
#      truoc neu chua co remote).
#   3. Chay "scripts/watch-upstream.sh generate" de tinh lai tree-hash moi.
#   4. Commit lockfile (.upstream-lock.json) moi cung voi thay doi merge.
#
# Luu y khi tu van hanh workflow tren fork:
#   - Actions tren fork bi TAT MAC DINH, phai vao tab Actions bat tay MOT LAN.
#   - Scheduled workflow tren public repo se TU TAT sau 60 ngay khong co hoat dong
#     nao trong repo (khong lien quan gi den lockfile) - phai push/tuong tac de
#     GitHub bat lai lich chay.
#   - Job do khong co nghia la co loi script; no nghia la mot path da lech so voi
#     lockfile, hoac lockfile thieu key - xem log de biet path nao.
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

# Kiem tra REF co resolve duoc khong truoc khi lam bat ky viec gi khac
require_ref() {
  local ref="$1"
  if ! git rev-parse --verify --quiet "${ref}^{commit}" > /dev/null; then
    echo "ERROR: cannot resolve ${ref} — run: git fetch upstream main" >&2
    exit 3
  fi
}

# Tinh sha cho mot path duoi mot ref bat ky. In sha ra stdout va tra ve exit 0
# neu resolve duoc; khong in gi va tra ve exit khac 0 neu path khong ton tai
# duoi ref do (git rev-parse --verify --quiet khong lap lai tham so khi loi).
get_sha_for() {
  local ref="$1"
  local path="$2"
  git rev-parse --verify --quiet "${ref}:${path}" 2>/dev/null
}

get_sha() {
  local path="$1"
  get_sha_for "$REF" "$path"
}

cmd_generate() {
  require_ref "$REF"

  local sorted_paths
  sorted_paths=$(printf '%s\n' "${PATHS[@]}" | LC_ALL=C sort)

  local pairs=""
  local missing=0
  while IFS= read -r path; do
    local sha
    if ! sha=$(get_sha "$path"); then
      echo "ERROR: cannot resolve ${REF}:${path} — upstream chua fetch hoac path khong ton tai" >&2
      missing=1
      continue
    fi
    pairs="${pairs}${path} ${sha}"$'\n'
  done <<< "$sorted_paths"

  if [ "$missing" -eq 1 ]; then
    echo "ABORT: khong ghi ${LOCKFILE} vi co path khong resolve duoc (xem loi phia tren)" >&2
    exit 1
  fi

  {
    echo "{"
    local total
    total=$(printf '%s' "$pairs" | sed '/^$/d' | wc -l)
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

# Doc gia tri sha ung voi mot path tu file lockfile JSON.
# Dung jq neu co (chinh xac hon), fallback grep neu khong co jq.
# Key vang trong lockfile PHAI in chuoi rong, KHONG duoc lam script chet
# (grep tra 1 khi khong match; phai || true de khong dinh set -e).
lockfile_sha() {
  local path="$1"
  local file="$2"
  if [ ! -f "$file" ]; then
    echo ""
    return
  fi
  if command -v jq > /dev/null 2>&1; then
    jq -r --arg p "$path" '.[$p] // ""' "$file"
  else
    grep -oE "\"${path}\": \"[a-f0-9]*\"" "$file" | head -n1 | sed -E 's/.*"([a-f0-9]*)"$/\1/' || true
  fi
}

cmd_check() {
  require_ref "$REF"

  local drift=0
  local sorted_paths
  sorted_paths=$(printf '%s\n' "${PATHS[@]}" | LC_ALL=C sort)

  while IFS= read -r path; do
    local old_sha new_sha
    old_sha=$(lockfile_sha "$path" "$LOCKFILE")
    new_sha=$(get_sha "$path" || echo "")

    if [ -z "$old_sha" ]; then
      drift=1
      echo "MISSING IN LOCK: ${path}"
      continue
    fi

    if [ "$old_sha" != "$new_sha" ]; then
      drift=1
      echo "DRIFT: ${path} ${old_sha} -> ${new_sha}"
    fi
  done <<< "$sorted_paths"

  return $drift
}

# --- selftest: dung repo git tam de tu kiem tra logic cua script ---------

cmd_selftest() {
  local tmp
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN

  # Copy chinh script nay vao repo tam, de khi tu goi lai, "dirname \"$0\"/.."
  # tro ve repo tam (chu khong phai repo that) - tranh doc/ghi nham file that.
  (
    cd "$tmp"
    git init -q -b main repo
    cd repo
    git config user.email "selftest@example.com"
    git config user.name "selftest"

    mkdir -p core lib common compiler gradle .github scripts
    echo x > core/f
    echo x > lib/f
    echo x > common/f
    echo x > compiler/f
    echo x > gradle/f
    echo x > gradle.properties
    echo x > build.gradle.kts
    echo x > settings.gradle.kts
    echo x > CONTRIBUTING.md
    echo x > AGENTS.md
    echo x > .github/f
    cp "$SELFTEST_SCRIPT" scripts/watch-upstream.sh
    chmod +x scripts/watch-upstream.sh
    git add -A
    git commit -q -m "init"

    git remote add upstream .
    git fetch -q upstream main
  )

  local repo="$tmp/repo"
  local script_copy="$repo/scripts/watch-upstream.sh"
  local fail=0

  # Ca (a): lockfile dung sinh boi generate phai co keys == PATHS cua script
  (
    cd "$repo"
    bash "$script_copy" generate > /dev/null
  )
  local lock="$repo/.upstream-lock.json"
  local expected_keys actual_keys
  expected_keys=$(printf '%s\n' "${PATHS[@]}" | LC_ALL=C sort)
  actual_keys=$(grep -oE '"[^"]+":' "$lock" | sed -E 's/^"(.*)":$/\1/' | LC_ALL=C sort)
  if [ "$expected_keys" != "$actual_keys" ]; then
    echo "SELFTEST FAIL (a): keys(lockfile) != PATHS" >&2
    echo "expected: ${expected_keys}" >&2
    echo "actual:   ${actual_keys}" >&2
    fail=1
  else
    echo "SELFTEST OK (a): keys(lockfile) == PATHS"
  fi

  # Ca (b): sha sai trong lockfile -> check phai exit 1 va in dung path
  (
    cd "$repo"
    sed -E 's/("core": ")[a-f0-9]+(")/\1deadbeefdeadbeefdeadbeefdeadbeefdeadbeef\2/' \
      .upstream-lock.json > .upstream-lock.json.tmp
    mv .upstream-lock.json.tmp .upstream-lock.json
  )
  local out_b rc_b
  set +e
  out_b=$(cd "$repo" && bash "$script_copy" check 2>&1)
  rc_b=$?
  set -e
  if [ "$rc_b" -eq 1 ] && printf '%s\n' "$out_b" | grep -q "DRIFT: core "; then
    echo "SELFTEST OK (b): sha sai -> exit 1, in dung path core"
  else
    echo "SELFTEST FAIL (b): rc=${rc_b} out=${out_b}" >&2
    fail=1
  fi

  # Ca (c): key thieu -> check phai exit 1 va in "MISSING IN LOCK"
  (
    cd "$repo"
    bash "$script_copy" generate > /dev/null
    # xoa dong "compiler" khoi lockfile de mo phong key vang, giu JSON hop le
    grep -v '"compiler":' .upstream-lock.json > .upstream-lock.json.tmp
    mv .upstream-lock.json.tmp .upstream-lock.json
  )
  local out_c rc_c
  set +e
  out_c=$(cd "$repo" && bash "$script_copy" check 2>&1)
  rc_c=$?
  set -e
  if [ "$rc_c" -eq 1 ] && printf '%s\n' "$out_c" | grep -q "MISSING IN LOCK: compiler"; then
    echo "SELFTEST OK (c): key thieu -> exit 1, in MISSING IN LOCK: compiler"
  else
    echo "SELFTEST FAIL (c): rc=${rc_c} out=${out_c}" >&2
    fail=1
  fi

  if [ "$fail" -ne 0 ]; then
    echo "SELFTEST: FAILED" >&2
    return 1
  fi
  echo "SELFTEST: PASSED"
  return 0
}

case "$MODE" in
  generate)
    cmd_generate
    ;;
  check)
    cmd_check
    ;;
  selftest)
    SELFTEST_SCRIPT="$(pwd)/scripts/watch-upstream.sh"
    cmd_selftest
    ;;
  *)
    echo "Unknown mode: ${MODE}. Use 'generate', 'check' or 'selftest'." >&2
    exit 2
    ;;
esac
