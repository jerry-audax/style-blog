#!/usr/bin/env bash
set -euo pipefail
umask 077

# CI 只创建自己的临时数据库，不读取业务库配置；随机宿主端口避免冲突。
unset PG_TEST_URL PG_TEST_USERNAME PG_TEST_PASSWORD IMGBED_SMOKE MUSIC_SMOKE
unset IMGBED_SMOKE_TEST MUSIC_SMOKE_TEST MEDIA_PG_TEST_URL MEDIA_PG_TEST_USERNAME MEDIA_PG_TEST_PASSWORD
temporary_directory=$(mktemp -d)
container_id=''
cleanup() {
    result=$?
    trap - EXIT
    if [[ -f "$temporary_directory/container-id" ]]; then
        container_id=$(cat "$temporary_directory/container-id")
        if [[ "$container_id" =~ ^[0-9a-f]{64}$ ]]; then
            docker rm -fv "$container_id" >/dev/null || result=1
        fi
    fi
    rm -rf "$temporary_directory"
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

password=$(node -e 'process.stdout.write(require("node:crypto").randomBytes(24).toString("hex"))')
printf 'POSTGRES_DB=blog_media_test\nPOSTGRES_USER=blog_ci\nPOSTGRES_PASSWORD=%s\n' "$password" > "$temporary_directory/postgres.env"
docker run -d --cidfile "$temporary_directory/container-id" \
    --env-file "$temporary_directory/postgres.env" \
    --publish 127.0.0.1::5432 postgres:16-alpine >/dev/null
container_id=$(cat "$temporary_directory/container-id")
ready=false
for attempt in {1..60}; do
    if docker exec "$container_id" pg_isready -h 127.0.0.1 -U blog_ci -d blog_media_test >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
[[ "$ready" == true ]] || { echo 'CI PostgreSQL did not become ready' >&2; exit 1; }
mapping=$(docker port "$container_id" 5432/tcp)
port=${mapping##*:}
[[ "$port" =~ ^[0-9]+$ ]] || { echo 'Cannot resolve CI PostgreSQL port' >&2; exit 1; }
export MEDIA_PG_TEST_URL="jdbc:postgresql://127.0.0.1:$port/blog_media_test"
export MEDIA_PG_TEST_USERNAME=blog_ci
export MEDIA_PG_TEST_PASSWORD="$password"
mvn -B -ntp -f blog-server/pom.xml clean verify
