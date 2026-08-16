#!/usr/bin/env bash
set -uo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
backend_dir=$(cd "$script_dir/.." && pwd)
registry=${1:-"$backend_dir/src/main/resources/application.properties"}
timeout_seconds=${REGISTRY_TIMEOUT_SECONDS:-15}
greenhouse_base=${REGISTRY_GREENHOUSE_BASE_URL:-https://boards-api.greenhouse.io/v1/boards}
lever_base=${REGISTRY_LEVER_BASE_URL:-https://api.lever.co/v0/postings}

for dependency in rg awk curl jq mktemp; do
  command -v "$dependency" >/dev/null || { printf 'Missing prerequisite: %s\n' "$dependency" >&2; exit 3; }
done
[[ -f "$registry" ]] || { printf 'Registry file not found: %s\n' "$registry" >&2; exit 3; }
[[ "$timeout_seconds" =~ ^[0-9]+$ && "$timeout_seconds" -ge 1 && "$timeout_seconds" -le 120 ]] || {
  printf 'REGISTRY_TIMEOUT_SECONDS must be between 1 and 120\n' >&2; exit 3;
}

entries=$(mktemp)
results=$(mktemp)
trap 'rm -f "$entries" "$results"' EXIT

rg '^job-aggregation\.employers\[[0-9]+\]\.(company|source|board-id|enabled)=' "$registry" |
awk -F= '
  /\.company=/{split($1,a,"."); i=a[2]; company[i]=substr($0,index($0,"=")+1)}
  /\.source=/{split($1,a,"."); i=a[2]; source[i]=$2}
  /\.board-id=/{split($1,a,"."); i=a[2]; board[i]=$2}
  /\.enabled=/{split($1,a,"."); i=a[2]; enabled[i]=$2}
  END {for(i in board) print i "\t" company[i] "\t" source[i] "\t" board[i] "\t" enabled[i]}' |
sort -t $'\t' -k1,1V > "$entries"

printf 'provider\tboard\thttp\tclassification\tcount\n'
while IFS=$'\t' read -r index company source board enabled; do
  if [[ "$enabled" != "true" ]]; then
    printf '%s\t%s\t-\tDISABLED\t0\n' "$source" "$board" | tee -a "$results"
    continue
  fi
  if [[ "$source" == "GREENHOUSE" ]]; then
    url="${greenhouse_base%/}/$board/jobs?content=true"
  elif [[ "$source" == "LEVER" ]]; then
    url="${lever_base%/}/$board?mode=json"
  else
    printf '%s\t%s\t-\tINVALID\t0\n' "$source" "$board" | tee -a "$results"
    continue
  fi

  response=$(mktemp)
  http_code=$(curl --silent --show-error --location --connect-timeout "$timeout_seconds" \
    --max-time "$timeout_seconds" --output "$response" --write-out '%{http_code}' "$url" 2>/dev/null)
  curl_status=$?
  classification=UNREACHABLE
  count=0
  if [[ "$curl_status" -eq 0 && "$http_code" == "200" ]]; then
    if ! jq -e . "$response" >/dev/null 2>&1; then
      classification=MALFORMED
    elif [[ "$source" == "GREENHOUSE" ]]; then
      if ! jq -e 'type == "object" and (.jobs | type == "array")' "$response" >/dev/null; then
        classification=MALFORMED
      elif ! jq -e 'all(.jobs[]?; (.id != null and (.absolute_url | type == "string") and (.absolute_url | length > 0)))' "$response" >/dev/null; then
        classification=MALFORMED
      else
        count=$(jq '.jobs | length' "$response")
        [[ "$count" -eq 0 ]] && classification=EMPTY || classification=ACTIVE
      fi
    else
      if ! jq -e 'type == "array"' "$response" >/dev/null; then
        classification=MALFORMED
      elif ! jq -e 'all(.[]?; (.id != null and (.hostedUrl | type == "string") and (.hostedUrl | length > 0)))' "$response" >/dev/null; then
        classification=MALFORMED
      else
        count=$(jq 'length' "$response")
        [[ "$count" -eq 0 ]] && classification=EMPTY || classification=ACTIVE
      fi
    fi
  elif [[ "$curl_status" -eq 0 && ( "$http_code" == "404" || "$http_code" == "410" ) ]]; then
    classification=INVALID
  fi
  rm -f "$response"
  [[ -n "$http_code" ]] || http_code=000
  printf '%s\t%s\t%s\t%s\t%s\n' "$source" "$board" "$http_code" "$classification" "$count" | tee -a "$results"
done < "$entries"

printf '\nsummary_by_provider\n'
awk -F '\t' '{key=$1 "\t" $4; counts[key]++} END {for(key in counts) print key "\t" counts[key]}' "$results" |
  sort -t $'\t' -k1,1 -k2,2

if awk -F '\t' '$4=="INVALID" || $4=="MALFORMED" {found=1} END {exit !found}' "$results"; then exit 1; fi
if awk -F '\t' '$4=="UNREACHABLE" {found=1} END {exit !found}' "$results"; then exit 2; fi
exit 0
