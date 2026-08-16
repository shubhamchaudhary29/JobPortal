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
  code=$(curl --silent --show-error --location --max-time 15 --output /dev/null --write-out '%{http_code}' "$url")
  printf '%s %s %s\n' "$source" "$board" "$code"
  [[ "$code" == 200 ]] || exit 1
done
