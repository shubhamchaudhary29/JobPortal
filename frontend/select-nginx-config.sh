#!/bin/sh
# Runs from /docker-entrypoint.d before nginx starts. Renders the HTTPS server from nginx.conf.template when the
# certificate is mounted; otherwise uses the plain HTTP server for local runs. Fails (and the container with it)
# on unsafe values, so a typo in .env never reaches nginx.
set -eu

: "${APP_DOMAIN:=54-236-126-94.sslip.io}"
: "${APP_SERVER_NAMES:=$APP_DOMAIN}"
: "${TLS_CERT_PATH:=/etc/letsencrypt/live/$APP_DOMAIN/fullchain.pem}"
: "${TLS_KEY_PATH:=/etc/letsencrypt/live/$APP_DOMAIN/privkey.pem}"
export APP_DOMAIN APP_SERVER_NAMES TLS_CERT_PATH TLS_KEY_PATH

fail() { echo "select-nginx-config: $*" >&2; exit 1; }

# Values are pasted into nginx config: allow only host names (and *.wildcards) and plain absolute paths.
printf '%s' "$APP_DOMAIN" | grep -Eq '^[A-Za-z0-9.-]+$' || fail "APP_DOMAIN must be a host name: '$APP_DOMAIN'"
printf '%s' "$APP_SERVER_NAMES" | grep -Eq '^[A-Za-z0-9.*-]+( +[A-Za-z0-9.*-]+)*$' \
    || fail "APP_SERVER_NAMES must be space-separated host names: '$APP_SERVER_NAMES'"
for path in "$TLS_CERT_PATH" "$TLS_KEY_PATH"; do
    printf '%s' "$path" | grep -Eq '^/[A-Za-z0-9._/-]+$' || fail "TLS paths must be absolute file paths: '$path'"
done

target=/etc/nginx/conf.d/default.conf
if [ -r "$TLS_CERT_PATH" ] && [ -r "$TLS_KEY_PATH" ]; then
    command -v envsubst >/dev/null 2>&1 || fail "envsubst is missing from the image (nginx:alpine ships it)"
    # shellcheck disable=SC2016 # the variable list is for envsubst, not the shell
    envsubst '${APP_SERVER_NAMES} ${TLS_CERT_PATH} ${TLS_KEY_PATH}' \
        < /etc/nginx/jobportal/nginx.conf.template > "$target"
    echo "select-nginx-config: HTTPS for $APP_SERVER_NAMES"
else
    cp /etc/nginx/jobportal/nginx-http.conf "$target"
    echo "select-nginx-config: no certificate at $TLS_CERT_PATH, serving plain HTTP"
fi
