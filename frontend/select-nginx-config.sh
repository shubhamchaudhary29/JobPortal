#!/bin/sh
set -eu

certificate=/etc/letsencrypt/live/54-236-126-94.sslip.io/fullchain.pem
private_key=/etc/letsencrypt/live/54-236-126-94.sslip.io/privkey.pem

if [ -r "$certificate" ] && [ -r "$private_key" ]; then
    cp /etc/nginx/jobportal-https.conf /etc/nginx/conf.d/default.conf
else
    cp /etc/nginx/jobportal-http.conf /etc/nginx/conf.d/default.conf
fi
