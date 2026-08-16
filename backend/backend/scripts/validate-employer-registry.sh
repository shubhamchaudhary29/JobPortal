#!/usr/bin/env bash
set -euo pipefail
registry="${1:-src/main/resources/application.properties}"
rg '^job-aggregation\.employers\[[0-9]+\]\.(source|board-id|enabled)=' "$registry" |
awk -F= '
 /\.source=/{split($1,a,"."); i=a[2]; source[i]=$2}
 /\.board-id=/{split($1,a,"."); i=a[2]; board[i]=$2}
 /\.enabled=/{split($1,a,"."); i=a[2]; enabled[i]=$2}
 END {for(i in board) if(enabled[i]=="true") print source[i],board[i]}' |
while read -r source board; do
  if [[ "$source" == GREENHOUSE ]]; then url="https://boards-api.greenhouse.io/v1/boards/$board/jobs?content=true"; else url="https://api.lever.co/v0/postings/$board?mode=json"; fi
  response=$(mktemp)
  code=$(curl --silent --show-error --location --max-time 15 --output "$response" --write-out '%{http_code}' "$url")
  if [[ "$source" == GREENHOUSE ]]; then valid=$(jq -er 'if (.jobs|type) != "array" then "INVALID" elif (.jobs|length)==0 then "EMPTY" elif all(.jobs[]?; (.id != null and (.absolute_url|type == "string"))) then "ACTIVE" else "INVALID" end' "$response" 2>/dev/null || echo INVALID); else valid=$(jq -er 'if type != "array" then "INVALID" elif length==0 then "EMPTY" elif all(.[]?; (.id != null and (.hostedUrl|type == "string"))) then "ACTIVE" else "INVALID" end' "$response" 2>/dev/null || echo INVALID); fi
  rm -f "$response"
  printf '%s %s %s %s\n' "$source" "$board" "$code" "$valid"
  [[ "$code" == 200 && ( "$valid" == ACTIVE || "$valid" == EMPTY ) ]] || exit 1
done
