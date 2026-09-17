#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main"
fail=0

[[ -f "$ROOT/settings.gradle.kts" ]] || { echo "FAIL: settings.gradle.kts missing"; fail=1; }
[[ -f "$ROOT/app/build.gradle.kts" ]] || { echo "FAIL: app/build.gradle.kts missing"; fail=1; }
[[ -f "$SRC/AndroidManifest.xml" ]] || { echo "FAIL: manifest missing"; fail=1; }

if grep -RInE '(^|[^A-Za-z])(jarvis|jervis|hey jarvis)([^A-Za-z]|$)' "$SRC"; then
  echo "FAIL: legacy Jarvis wake-word references found"
  fail=1
fi

if grep -RInE '(api[_-]?key|authorization|bearer)[[:space:]]*[:=][[:space:]]*"[^"]{12,}"' "$SRC" --include='*.kt' --include='*.xml'; then
  echo "FAIL: possible hard-coded credential found"
  fail=1
fi

count=$(find "$SRC/java/com/nova/ai" -name '*.kt' | wc -l | tr -d ' ')
echo "Kotlin source files: $count"

if grep -q 'versionCode = 67' "$ROOT/app/build.gradle.kts" && grep -q '67.0-final-release-audit' "$ROOT/app/build.gradle.kts"; then
  echo "PASS: final version metadata"
else
  echo "FAIL: final version metadata"
  fail=1
fi

if [[ $fail -eq 0 ]]; then echo "STATIC_RELEASE_AUDIT=PASS"; else echo "STATIC_RELEASE_AUDIT=FAIL"; fi
exit $fail
