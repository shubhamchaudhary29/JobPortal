#!/usr/bin/env bash
set -euo pipefail

for dependency in docker curl jq grep; do
  command -v "$dependency" >/dev/null || { printf 'Missing prerequisite: %s\n' "$dependency" >&2; exit 3; }
done
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
smoke_project="jobportal-smoke-${PPID}-$$"
[[ "$smoke_project" =~ ^jobportal-smoke-[0-9]+-[0-9]+$ ]] || exit 3

export MONGODB_URI=mongodb://mongodb:27017/jobportal-smoke
export JWT_SECRET=test-only-compose-jwt-secret-at-least-thirty-two-bytes
export ADZUNA_APP_ID=test-id
export ADZUNA_APP_KEY=test-key
export CORS_ALLOWED_ORIGINS=http://localhost
export REFRESH_COOKIE_SECURE=false
export JOB_AGGREGATION_SCHEDULING_ENABLED=false

cleanup() {
  docker compose -p "$smoke_project" -f "$repo_root/docker-compose.yml" down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker compose -p "$smoke_project" -f "$repo_root/docker-compose.yml" up -d --wait --wait-timeout 180
curl --fail --silent http://localhost:8080/api/v1/health | jq -e '.status == "UP"' >/dev/null
curl --fail --silent http://localhost/api/v1/health | jq -e '.status == "UP"' >/dev/null
curl --fail --silent http://localhost/admin/aggregation | grep -F '<div id="root"></div>' >/dev/null
[[ $(docker compose -p "$smoke_project" -f "$repo_root/docker-compose.yml" ps --status running --services | wc -l) -eq 3 ]]
printf 'compose smoke verification passed for backend, frontend proxy/SPA, and MongoDB\n'
