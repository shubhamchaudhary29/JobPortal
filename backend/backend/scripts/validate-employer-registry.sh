#!/usr/bin/env bash
set -uo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
backend_dir=$(cd "$script_dir/.." && pwd)
registry=${1:-"$backend_dir/src/main/resources/application.properties"}
timeout_seconds=${REGISTRY_TIMEOUT_SECONDS:-15}

# ATS provider table: one entry per registry source of the backend ProviderCatalog. Each base URL can be
# overridden with REGISTRY_<SOURCE>_BASE_URL. The board path uses {board} as its placeholder; the jq
# filters check the response shape, that every item has an id and a public link, and count the items.
declare -A provider_base=(
  [GREENHOUSE]=https://boards-api.greenhouse.io/v1/boards
  [LEVER]=https://api.lever.co/v0/postings
  [ASHBY]=https://api.ashbyhq.com/posting-api/job-board
  [SMARTRECRUITERS]=https://api.smartrecruiters.com/v1/companies
)
declare -A provider_path=(
  [GREENHOUSE]='/{board}/jobs?content=true'
  [LEVER]='/{board}?mode=json'
  [ASHBY]='/{board}?includeCompensation=true'
  [SMARTRECRUITERS]='/{board}/postings?offset=0&limit=100'
)
declare -A provider_shape=(
  [GREENHOUSE]='type == "object" and (.jobs | type == "array")'
  [LEVER]='type == "array"'
  [ASHBY]='type == "object" and (.jobs | type == "array")'
  [SMARTRECRUITERS]='type == "object" and (.content | type == "array")'
)
declare -A provider_items=(
  [GREENHOUSE]='all(.jobs[]?; (.id != null and (.absolute_url | type == "string") and (.absolute_url | length > 0)))'
  [LEVER]='all(.[]?; (.id != null and (.hostedUrl | type == "string") and (.hostedUrl | length > 0)))'
  [ASHBY]='all(.jobs[]?; (.id != null and (.jobUrl | type == "string") and (.jobUrl | length > 0)))'
  [SMARTRECRUITERS]='all(.content[]?; (.id != null and (.name | type == "string") and (.name | length > 0)))'
)
declare -A provider_count=(
  [GREENHOUSE]='.jobs | length'
  [LEVER]='length'
  [ASHBY]='[.jobs[] | select(.isListed != false)] | length'
  [SMARTRECRUITERS]='.totalFound // (.content | length)'
)
for source in "${!provider_base[@]}"; do
  override="REGISTRY_${source}_BASE_URL"
  [[ -n "${!override:-}" ]] && provider_base[$source]=${!override}
done

for dependency in awk curl jq mktemp; do
  command -v "$dependency" >/dev/null || { printf 'Missing prerequisite: %s\n' "$dependency" >&2; exit 3; }
done
[[ -f "$registry" ]] || { printf 'Registry file not found: %s\n' "$registry" >&2; exit 3; }
[[ "$timeout_seconds" =~ ^[0-9]+$ && "$timeout_seconds" -ge 1 && "$timeout_seconds" -le 120 ]] || {
  printf 'REGISTRY_TIMEOUT_SECONDS must be between 1 and 120\n' >&2; exit 3;
}

entries=$(mktemp)
results=$(mktemp)
trap 'rm -f "$entries" "$results"' EXIT

awk -F= '
  /^job-aggregation\.employers\[[0-9]+\]\.company=/{split($1,a,"."); i=a[2]; company[i]=substr($0,index($0,"=")+1)}
  /^job-aggregation\.employers\[[0-9]+\]\.source=/{split($1,a,"."); i=a[2]; source[i]=$2}
  /^job-aggregation\.employers\[[0-9]+\]\.board-id=/{split($1,a,"."); i=a[2]; board[i]=$2}
  /^job-aggregation\.employers\[[0-9]+\]\.enabled=/{split($1,a,"."); i=a[2]; enabled[i]=$2}
  END {for(i in board) print i "\t" company[i] "\t" source[i] "\t" board[i] "\t" enabled[i]}' \
  "$registry" | sort -t $'\t' -k1,1V > "$entries"

printf 'provider\tboard\thttp\tclassification\tcount\n'
while IFS=$'\t' read -r index company source board enabled; do
  if [[ "$enabled" != "true" ]]; then
    printf '%s\t%s\t-\tDISABLED\t0\n' "$source" "$board" | tee -a "$results"
    continue
  fi
  if [[ -z "$source" || -z "${provider_base[$source]+set}" ]]; then
    printf '%s\t%s\t-\tINVALID\t0\n' "$source" "$board" | tee -a "$results"
    continue
  fi
  path=${provider_path[$source]}
  url="${provider_base[$source]%/}${path//\{board\}/$board}"

  response=$(mktemp)
  http_code=$(curl --silent --show-error --location --connect-timeout "$timeout_seconds" \
    --max-time "$timeout_seconds" --output "$response" --write-out '%{http_code}' "$url" 2>/dev/null)
  curl_status=$?
  classification=UNREACHABLE
  count=0
  if [[ "$curl_status" -eq 0 && "$http_code" == "200" ]]; then
    if ! jq -e . "$response" >/dev/null 2>&1; then
      classification=MALFORMED
    elif ! jq -e "${provider_shape[$source]}" "$response" >/dev/null; then
      classification=MALFORMED
    elif ! jq -e "${provider_items[$source]}" "$response" >/dev/null; then
      classification=MALFORMED
    else
      count=$(jq "${provider_count[$source]}" "$response")
      [[ "$count" -eq 0 ]] && classification=EMPTY || classification=ACTIVE
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
