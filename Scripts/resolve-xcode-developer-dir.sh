#!/bin/zsh

set -euo pipefail

REQUIRE_TESTING=false

# 利用可能なオプションと用途を簡潔に表示する。
usage() {
  cat <<EOF
Usage: ./Scripts/resolve-xcode-developer-dir.sh [--require-testing]

Resolves a developer directory that can satisfy the requested Swift capability.
EOF
}

if (( $# > 1 )); then
  usage >&2
  exit 1
fi

if (( $# == 1 )); then
# Xcode.app と Contents/Developer のどちらが来ても内部処理用の形へそろえる。
  case "$1" in
    --require-testing)
      REQUIRE_TESTING=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      exit 1
      ;;
  esac
fi

normalize_candidate() {
  local candidate="$1"

  if [[ "$candidate" == *.app ]]; then
    candidate="$candidate/Contents/Developer"
  fi

  print -r -- "$candidate"
}

# 候補の developer dir から実際に使われる swift のパスを解決する。
swift_path_for_candidate() {
  local candidate="$1"
  DEVELOPER_DIR="$candidate" xcrun -f swift 2>/dev/null || return 1
}

# build だけなら swift 解決、test を含むなら xcodebuild も使える候補だけを採用する。
candidate_meets_requirements() {
  local candidate="$1"
  local swift_bin

  swift_bin="$(swift_path_for_candidate "$candidate")" || return 1

  if [[ "$REQUIRE_TESTING" == true ]]; then
    DEVELOPER_DIR="$candidate" xcrun -f xcodebuild >/dev/null 2>&1 || return 1
  fi
}

typeset -a candidates

if [[ -n "${DEVELOPER_DIR:-}" ]]; then
  candidates+=("$(normalize_candidate "$DEVELOPER_DIR")")
fi

if current_dir="$(xcode-select -p 2>/dev/null)"; then
  candidates+=("$(normalize_candidate "$current_dir")")
fi

candidates+=("/Applications/Xcode.app/Contents/Developer")

for app_bundle in /Applications/Xcode*.app(N); do
  candidates+=("$app_bundle/Contents/Developer")
done

typeset -U candidates

for candidate in "${candidates[@]}"; do
  if candidate_meets_requirements "$candidate"; then
    print -r -- "$candidate"
    exit 0
  fi
done

active_dir="$(xcode-select -p 2>/dev/null || true)"

if [[ "$REQUIRE_TESTING" == true ]]; then
  cat >&2 <<EOF
error: No installed developer directory can run swift test with the Testing module.

Active developer directory: ${active_dir:-<not set>}

The current toolchain was checked first, then installed Xcode.app bundles in /Applications.
Install full Xcode or point DEVELOPER_DIR at one explicitly, for example:
  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./Scripts/validate.sh
EOF
  exit 1
fi

cat >&2 <<EOF
error: No usable Swift developer directory was found.

Active developer directory: ${active_dir:-<not set>}

Install Command Line Tools or Xcode, or point DEVELOPER_DIR at a valid developer directory.
EOF

exit 1