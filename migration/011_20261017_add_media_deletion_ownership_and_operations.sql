-- MEDIA-05A: ownership assertions and durable local-removal receipts.
-- Requires 005 media tables. Explicit approval and backup required for business databases.
-- No provider calls, no ownership inference, no business-row removal. Repetition must fail.
BEGIN;

ALTER TABLE media_asset
    ADD COLUMN deletion_scope VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED',
    ADD COLUMN exclusive_environment VARCHAR(32),
    ADD COLUMN ownership_confirmed_by BIGINT,
    ADD COLUMN ownership_confirmed_at TIMESTAMPTZ,
    ADD COLUMN ownership_note VARCHAR(500),
    ADD CONSTRAINT ck_media_asset_ownership CHECK (
        (deletion_scope = 'UNVERIFIED' AND exclusive_environment IS NULL AND ownership_confirmed_by IS NULL AND ownership_confirmed_at IS NULL AND ownership_note IS NULL)
        OR (deletion_scope = 'SHARED' AND exclusive_environment IS NULL)
        OR (deletion_scope = 'EXCLUSIVE' AND exclusive_environment IS NOT NULL AND exclusive_environment ~ '^[a-z][a-z0-9-]{0,31}$' AND ownership_confirmed_by IS NOT NULL AND ownership_confirmed_at IS NOT NULL AND ownership_note IS NOT NULL)
    ),
    ADD CONSTRAINT ck_media_asset_ownership_audit CHECK (
        (ownership_confirmed_by IS NULL) = (ownership_confirmed_at IS NULL)
        AND (ownership_confirmed_by IS NULL OR ownership_confirmed_by > 0)
        AND (ownership_note IS NULL OR (length(btrim(ownership_note)) > 0 AND ownership_note !~ '[[:cntrl:]]' AND position('://' in ownership_note) = 0))
    );

CREATE TABLE media_delete_operation (
    id UUID PRIMARY KEY,
    actor_id BIGINT NOT NULL CHECK (actor_id > 0),
    idempotency_key VARCHAR(36) NOT NULL CHECK (idempotency_key ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    fingerprint CHAR(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    target_type VARCHAR(24) NOT NULL CHECK (target_type IN ('MUSIC_TRACK','MUSIC_MEDIA','SURPRISE_VIDEO','IMAGE_ASSET')),
    target_id VARCHAR(64) NOT NULL CHECK (length(btrim(target_id)) > 0 AND target_id !~ '[[:cntrl:]]'),
    media_slot VARCHAR(8),
    original_version BIGINT NOT NULL CHECK (original_version >= 0),
    committed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_media_delete_actor_key UNIQUE (actor_id, idempotency_key),
    CONSTRAINT ck_media_delete_target_slot CHECK (
        (target_type = 'MUSIC_MEDIA' AND media_slot IS NOT NULL AND media_slot IN ('AUDIO','COVER','LYRICS'))
        OR (target_type <> 'MUSIC_MEDIA' AND media_slot IS NULL)
    )
);
CREATE INDEX ix_media_delete_operation_actor_time ON media_delete_operation (actor_id, committed_at DESC, id ASC);

CREATE TABLE media_delete_item (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES media_delete_operation(id) ON DELETE NO ACTION,
    asset_id UUID NOT NULL REFERENCES media_asset(id) ON DELETE NO ACTION,
    media_kind VARCHAR(16) NOT NULL CHECK (media_kind IN ('IMAGE','AUDIO','VIDEO','LYRICS')),
    asset_version BIGINT NOT NULL CHECK (asset_version >= 0),
    deletion_scope_snapshot VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED' CHECK (deletion_scope_snapshot IN ('UNVERIFIED','SHARED','EXCLUSIVE')),
    exclusive_environment_snapshot VARCHAR(32),
    ownership_confirmed_by_snapshot BIGINT,
    ownership_confirmed_at_snapshot TIMESTAMPTZ,
    ownership_note_snapshot VARCHAR(500),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','PROCESSING','SUCCEEDED','RETRY_WAIT','FAILED','UNKNOWN','RETAINED','LINKED')),
    reason VARCHAR(40) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ,
    lease_owner VARCHAR(64),
    lease_until TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    linked_item_id UUID,
    is_canonical BOOLEAN GENERATED ALWAYS AS (status <> 'LINKED') STORED,
    canonical_required BOOLEAN GENERATED ALWAYS AS (CASE WHEN status = 'LINKED' THEN true ELSE NULL END) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_media_delete_operation_asset UNIQUE (operation_id, asset_id),
    CONSTRAINT uk_media_delete_item_canonical UNIQUE (id, asset_id, is_canonical),
    CONSTRAINT fk_media_delete_item_canonical FOREIGN KEY (linked_item_id, asset_id, canonical_required)
        REFERENCES media_delete_item(id, asset_id, is_canonical) ON DELETE NO ACTION,
    CONSTRAINT ck_media_delete_item_link CHECK ((status = 'LINKED') = (linked_item_id IS NOT NULL) AND id <> linked_item_id),
    CONSTRAINT ck_media_delete_item_time CHECK (updated_at >= created_at),
    CONSTRAINT ck_media_delete_item_lease CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT ck_media_delete_item_reason CHECK (
        (status = 'RETAINED' AND reason IN ('OWNERSHIP_UNVERIFIED','SHARED','ENVIRONMENT_MISMATCH','REFERENCE_COVERAGE_INCOMPLETE','IN_USE','WRITE_GUARDS_INCOMPLETE','ASSET_NOT_ACTIVE'))
        OR (status IN ('PENDING','PROCESSING','SUCCEEDED','LINKED') AND reason = 'NONE')
        OR (status IN ('FAILED','RETRY_WAIT') AND reason = 'PROVIDER_FAILURE')
        OR (status = 'UNKNOWN' AND reason = 'OUTCOME_UNKNOWN')
    ),
    CONSTRAINT ck_media_delete_item_ownership CHECK (
        (deletion_scope_snapshot = 'UNVERIFIED' AND exclusive_environment_snapshot IS NULL AND ownership_confirmed_by_snapshot IS NULL AND ownership_confirmed_at_snapshot IS NULL AND ownership_note_snapshot IS NULL)
        OR (deletion_scope_snapshot = 'SHARED' AND exclusive_environment_snapshot IS NULL)
        OR (deletion_scope_snapshot = 'EXCLUSIVE' AND exclusive_environment_snapshot IS NOT NULL AND exclusive_environment_snapshot ~ '^[a-z][a-z0-9-]{0,31}$' AND ownership_confirmed_by_snapshot > 0 AND ownership_confirmed_by_snapshot IS NOT NULL AND ownership_confirmed_at_snapshot IS NOT NULL AND ownership_note_snapshot IS NOT NULL AND length(btrim(ownership_note_snapshot)) > 0)
    ),
    CONSTRAINT ck_media_delete_item_ownership_audit CHECK (
        (ownership_confirmed_by_snapshot IS NULL) = (ownership_confirmed_at_snapshot IS NULL)
        AND (ownership_confirmed_by_snapshot IS NULL OR ownership_confirmed_by_snapshot > 0)
        AND (ownership_note_snapshot IS NULL OR (length(btrim(ownership_note_snapshot)) > 0 AND ownership_note_snapshot !~ '[[:cntrl:]]' AND position('://' in ownership_note_snapshot) = 0))
    )
);
CREATE UNIQUE INDEX uk_media_delete_active_asset ON media_delete_item(asset_id)
    WHERE status IN ('PENDING','PROCESSING','RETRY_WAIT','UNKNOWN');
CREATE INDEX ix_media_delete_item_operation ON media_delete_item(operation_id, id);
CREATE INDEX ix_media_delete_item_due ON media_delete_item(status, next_attempt_at);
COMMIT;
