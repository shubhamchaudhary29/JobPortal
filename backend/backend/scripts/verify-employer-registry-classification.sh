#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
port_file=$(mktemp)
server_pid=
cleanup() {
  [[ -n "$server_pid" ]] && kill "$server_pid" 2>/dev/null || true
  rm -f "$port_file"
}
trap cleanup EXIT

python3 "$script_dir/fixtures/mock-employer-registry-server.py" > "$port_file" &
server_pid=$!
for _ in $(seq 1 50); do [[ -s "$port_file" ]] && break; sleep 0.1; done
port=$(cat "$port_file")

set +e
output=$(REGISTRY_GREENHOUSE_BASE_URL="http://127.0.0.1:$port" \
  REGISTRY_LEVER_BASE_URL="http://127.0.0.1:$port" \
  bash "$script_dir/validate-employer-registry.sh" \
  "$script_dir/fixtures/employer-registry-classification.properties")
status=$?
set -e
printf '%s\n' "$output"
[[ "$status" -eq 1 ]]
for expected in \
  $'GREENHOUSE\tactive\t200\tACTIVE\t1' \
  $'GREENHOUSE\tempty\t200\tEMPTY\t0' \
  $'LEVER\tmalformed\t200\tMALFORMED\t0' \
  $'GREENHOUSE\tinvalid\t404\tINVALID\t0' \
  $'LEVER\tunreachable\t503\tUNREACHABLE\t0' \
  $'LEVER\tdisabled\t-\tDISABLED\t0'; do
  grep -Fqx "$expected" <<< "$output"
done
printf 'registry classification verification passed\n'
