const {test} = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const {createPublicationRuntime} = require('../publication/interfaces/server.cjs')
const {PublicationCoordinator} = require('../publication/application/PublicationCoordinator.cjs')
const {ProtectedPublicationCoordinator} = require('../publication/application/ProtectedPublicationCoordinator.cjs')

test('runtime defaults to legacy and requires explicit complete protected configuration', () => {
    const options = {root: path.resolve(__dirname, '..'), directory: path.resolve(__dirname, '../.runtime/fixture-unused'),
        token: 'fixture-secret-not-a-real-credential', dev: true}
    const legacy = createPublicationRuntime({...options, env: {}})
    assert.ok(legacy.coordinator instanceof PublicationCoordinator)
    for (const env of [{PUBLICATION_MEDIA_ENABLED: 'yes'}, {PUBLICATION_MEDIA_ENABLED: 'true'},
        {PUBLICATION_MEDIA_ENABLED: 'true', PUBLICATION_MEDIA_ENVIRONMENT_ID: 'local', PUBLICATION_MEDIA_PUBLISHER_ID: 'web', BLOG_API_URL: 'https://backend.example/'}])
        assert.throws(() => createPublicationRuntime({...options, env}))
    const enabled = createPublicationRuntime({...options, env: {PUBLICATION_MEDIA_ENABLED: 'true',
        PUBLICATION_MEDIA_ENVIRONMENT_ID: 'local', PUBLICATION_MEDIA_PUBLISHER_ID: 'web',
        PUBLICATION_MEDIA_BACKEND_URL: 'http://127.0.0.1:8080'}})
    assert.ok(enabled.coordinator instanceof ProtectedPublicationCoordinator)
    assert.equal(enabled.coordinator.gate, enabled.gate)
    assert.equal(enabled.coordinator.inputs.dev, false)
    assert.equal(enabled.coordinator.renderer.layoutVersion, 2)
    assert.equal(enabled.coordinator.collector.layoutVersion, 2)
    enabled.coordinator.stop()
    assert.equal(enabled.coordinator.closed, true)
})
