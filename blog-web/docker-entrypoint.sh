#!/bin/sh
set -eu

envsubst "${NGINX_ENVSUBST_VARS:-\$BACKEND_URL \$PUBLICATION_URL \$NGINX_LISTEN_PORT}" \
  < /etc/nginx/templates/default.conf.template \
  > /etc/nginx/http.d/default.conf

node /app/publication/interfaces/server.cjs &
publication_pid=$!
nginx -g 'daemon off;' &
nginx_pid=$!

term() {
  kill -TERM "$publication_pid" "$nginx_pid" 2>/dev/null || true
}
trap term INT TERM

while kill -0 "$publication_pid" 2>/dev/null && kill -0 "$nginx_pid" 2>/dev/null; do
  sleep 1
done

term
wait "$publication_pid" 2>/dev/null || true
wait "$nginx_pid" 2>/dev/null || true
exit 1