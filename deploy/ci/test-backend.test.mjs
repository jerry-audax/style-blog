import assert from 'node:assert/strict'
import {spawnSync} from 'node:child_process'
import {fileURLToPath} from 'node:url'
import test from 'node:test'

const bash = process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash'
const helper = fileURLToPath(new URL('./test-backend.sh', import.meta.url)).replaceAll('\\', '/')
// 以命令替身运行真实脚本，验证失败传播与资源清理，不连接任何 Docker/数据库。
const fixture = `
docker() {
    case "$1" in
        run)
            shift
            while [ "$#" -gt 0 ]; do
                if [ "$1" = --cidfile ]; then printf '%064d' 0 > "$2"; fi
                shift
            done ;;
        exec) return "$CI_FIXTURE_POSTGRES_EXIT" ;;
        port) printf '127.0.0.1:65432\\n' ;;
        rm)
            [ "$2" = -fv ] && [ "$3" = "$(printf '%064d' 0)" ] || return 99
            printf 'OWN_CONTAINER_AND_VOLUME_REMOVED\\n' >&2 ;;
        *) return 98 ;;
    esac
}
sleep() { return 0; }
mvn() {
    [ "$MEDIA_PG_TEST_URL" = 'jdbc:postgresql://127.0.0.1:65432/blog_media_test' ] || return 97
    [ "$MEDIA_PG_TEST_USERNAME" = blog_ci ] && [ -n "$MEDIA_PG_TEST_PASSWORD" ] || return 96
    [ -z "$(printenv PG_TEST_URL IMGBED_SMOKE MUSIC_SMOKE)" ] || return 95
    [ "$*" = '-B -ntp -f blog-server/pom.xml clean verify' ] || return 94
    printf 'ISOLATED_DATABASE_VERIFIED\\n'
    return "$CI_FIXTURE_MAVEN_EXIT"
}
source "$1"
`

for (const exitCode of [0, 7]) {
    test(`backend CI preserves Maven exit ${exitCode} and removes its own container/volume`, () => {
        const result = spawnSync(bash, ['-c', fixture, 'ci-fixture', helper], {
            encoding: 'utf8',
            env: {...process.env, CI_FIXTURE_MAVEN_EXIT: String(exitCode), CI_FIXTURE_POSTGRES_EXIT: '0',
                PG_TEST_URL: 'do-not-inherit', IMGBED_SMOKE: 'true', MUSIC_SMOKE: 'true'}
        })
        assert.equal(result.status, exitCode, result.stderr)
        assert.match(result.stdout, /ISOLATED_DATABASE_VERIFIED/)
        assert.match(result.stderr, /OWN_CONTAINER_AND_VOLUME_REMOVED/)
    })
}

test('unready PostgreSQL prevents Maven execution and still cleans up', () => {
    const result = spawnSync(bash, ['-c', fixture, 'ci-fixture', helper], {
        encoding: 'utf8',
        env: {...process.env, CI_FIXTURE_MAVEN_EXIT: '0', CI_FIXTURE_POSTGRES_EXIT: '1'}
    })
    assert.equal(result.status, 1, result.stderr)
    assert.doesNotMatch(result.stdout, /ISOLATED_DATABASE_VERIFIED/)
    assert.match(result.stderr, /PostgreSQL did not become ready/)
    assert.match(result.stderr, /OWN_CONTAINER_AND_VOLUME_REMOVED/)
})
