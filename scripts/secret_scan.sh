#!/bin/bash
# Comet-X secret scanner — run before every git push.
# Exits non-zero if any secret pattern is found in tracked content.
# NOTE: excludes itself (contains patterns, not secrets).
set -u
TARGET="${1:-.}"
FOUND=0
PATTERNS=(
  'ghp_[A-Za-z0-9]{20,}'
  'gho_[A-Za-z0-9]{20,}'
  'github_pat_[A-Za-z0-9_]{20,}'
  '[0-9]{8,10}:[A-Za-z0-9_-]{35}'
  'AKIA[0-9A-Z]{16}'
  'AIza[0-9A-Za-z_-]{30,}'
  'sk-[A-Za-z0-9_-]{20,}'
  'xox[bap]-[A-Za-z0-9-]{10,}'
  '-----BEGIN (RSA|EC|OPENSSH|PGP|DSA) PRIVATE KEY-----'
  '(password|passwd|secret|token)[[:space:]]*[=:][[:space:]]*["'\''][^"'\'' ]{12,}'
)
EXCLUDES=(
  ':!*.md' ':!scripts/secret_scan.sh' ':!.git/*'
)
echo "Scanning $TARGET for secrets..."
for p in "${PATTERNS[@]}"; do
  # -I: skip binaries. Test sources are allowlisted: they legitimately contain
  # FAKE key-shaped fixtures for detector tests (marked FAKE in the file).
  MATCHES=$(grep -rIl -E "$p" "$TARGET" --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle \
    --exclude="secret_scan.sh" --exclude="*.md" --exclude="*Test.kt" 2>/dev/null || true)
  if [ -n "$MATCHES" ]; then
    echo "SECRET PATTERN HIT [$p]:"
    echo "$MATCHES"
    FOUND=1
  fi
done
# also scan git history if this is a repo
if [ -d "$TARGET/.git" ]; then
  for p in "${PATTERNS[@]}"; do
    HITS=$(git -C "$TARGET" log --all -p -S"$p" --oneline 2>/dev/null | head -5 || true)
    if [ -n "$HITS" ]; then
      echo "GIT HISTORY PATTERN HIT [$p]"
      FOUND=1
    fi
  done
fi
if [ "$FOUND" -eq 0 ]; then
  echo "SECRET SCAN CLEAN"
  exit 0
else
  echo "SECRET SCAN FAILED — do not push"
  exit 1
fi
