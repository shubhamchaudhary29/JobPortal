#!/usr/bin/env bash
# Recompute imported-job fingerprints to the current JobFingerprint version.
#   bash backend/backend/scripts/migrate-fingerprints.sh            # dry-run (default, no writes)
#   bash backend/backend/scripts/migrate-fingerprints.sh --apply    # apply after reviewing the dry-run
# Requires the backend environment (MONGODB_URI, JWT_SECRET, ADZUNA_APP_ID, ADZUNA_APP_KEY). Set
# FINGERPRINT_MIGRATION_JAR to reuse a built jar; otherwise the backend is packaged first.
# Exit status: 0 nothing needs review, 2 collisions or skipped jobs need an operator, 3 usage/prerequisite error.
set -uo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
backend_dir=$(cd "$script_dir/.." && pwd)
mode=dry-run
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--apply" ) ]]; then
  printf 'Usage: %s [--apply]\n' "$0" >&2; exit 3
fi
[[ $# -eq 1 ]] && mode=apply
for variable in MONGODB_URI JWT_SECRET ADZUNA_APP_ID ADZUNA_APP_KEY; do
  [[ -n "${!variable:-}" ]] || { printf 'Missing environment variable: %s\n' "$variable" >&2; exit 3; }
done
command -v java >/dev/null || { printf 'Missing prerequisite: java\n' >&2; exit 3; }

jar=${FINGERPRINT_MIGRATION_JAR:-}
if [[ -z "$jar" ]]; then
  (cd "$backend_dir" && ./mvnw -q -DskipTests package) >&2 || { printf 'Backend package failed\n' >&2; exit 3; }
  jar=$(find "$backend_dir/target" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -n 1)
fi
[[ -f "$jar" ]] || { printf 'Backend jar not found: %s\n' "$jar" >&2; exit 3; }

java -jar "$jar" \
  --spring.main.web-application-type=none \
  --spring.main.banner-mode=off \
  --job-aggregation.scheduling.enabled=false \
  --job-aggregation.fingerprint-migration.mode="$mode" \
  --logging.level.root=WARN \
  --logging.level.com.example.backend.integration.aggregation.FingerprintMigrationService=INFO
