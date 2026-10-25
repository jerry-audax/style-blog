-- MEDIA-03B2 incremental ledger. Run only after an explicitly approved migration.
-- Repeated execution fails deliberately; no provider file, business row or seed is created.
CREATE TABLE media_upload_operation (
    id UUID PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    operation_type VARCHAR(24) NOT NULL,
    source_operation_id UUID,
    target_type VARCHAR(24) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    slot VARCHAR(16) NOT NULL,
    expected_target_version BIGINT,
    expected_old_asset_id UUID,
    file_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    candidate_asset_id UUID NOT NULL,
    target_payload JSONB NOT NULL,
    provider VARCHAR(32) NOT NULL,
    operation_folder VARCHAR(512) NOT NULL,
    confirmed_object_key VARCHAR(512),
    transport_mode VARCHAR(16) NOT NULL,
    provider_upload_id VARCHAR(128),
    chunk_size INTEGER NOT NULL DEFAULT 16000000,
    total_chunks INTEGER NOT NULL,
    confirmed_chunks INTEGER NOT NULL DEFAULT 0,
    in_flight_chunk INTEGER,
    status VARCHAR(24) NOT NULL,
    stage VARCHAR(16) NOT NULL,
    safe_error_code VARCHAR(40),
    version BIGINT NOT NULL DEFAULT 0,
    lease_owner UUID,
    lease_until TIMESTAMPTZ,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    pause_requested BOOLEAN NOT NULL DEFAULT FALSE,
    execution_attempts INTEGER NOT NULL DEFAULT 0,
    recovery_attempts INTEGER NOT NULL DEFAULT 0,
    finalize_attempts INTEGER NOT NULL DEFAULT 0,
    result_snapshot JSONB,
    resolved_by_operation_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_media_upload_source FOREIGN KEY (source_operation_id) REFERENCES media_upload_operation(id) ON DELETE NO ACTION,
    CONSTRAINT fk_media_upload_resolved FOREIGN KEY (resolved_by_operation_id) REFERENCES media_upload_operation(id) ON DELETE NO ACTION,
    CONSTRAINT ck_media_upload_actor CHECK (actor_id > 0),
    CONSTRAINT ck_media_upload_hashes CHECK (request_fingerprint ~ '^[0-9a-f]{64}$' AND source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_media_upload_operation_type CHECK (operation_type IN ('UPLOAD_AND_BIND','BIND_UPLOADED')),
    CONSTRAINT ck_media_upload_target CHECK ((
        length(btrim(target_id)) > 0 AND target_id !~ '[[:cntrl:]]' AND
        ((target_type='MUSIC_TRACK' AND slot IN ('AUDIO','COVER','LYRICS') AND expected_target_version IS NOT NULL
            AND expected_target_version >= 0 AND target_payload='{}'::jsonb)
        OR (target_type='SURPRISE_CREATE' AND slot='VIDEO' AND expected_target_version IS NULL
            AND expected_old_asset_id IS NULL AND jsonb_typeof(target_payload)='object'
            AND jsonb_typeof(target_payload->'title')='string' AND length(btrim(target_payload->>'title')) BETWEEN 1 AND 120
            AND jsonb_typeof(target_payload->'enabled')='boolean'
            AND jsonb_typeof(target_payload->'sortOrder')='number'
            AND (target_payload->>'sortOrder') ~ '^[0-9]+$'
            AND (target_payload->>'sortOrder')::numeric BETWEEN 0 AND 100000))
    ) IS TRUE),
    CONSTRAINT ck_media_upload_source CHECK (
        (operation_type='UPLOAD_AND_BIND' AND source_operation_id IS NULL) OR
        (operation_type='BIND_UPLOADED' AND source_operation_id IS NOT NULL AND source_operation_id<>id
            AND target_type='MUSIC_TRACK' AND confirmed_object_key IS NOT NULL AND stage='PERSIST')
    ),
    CONSTRAINT ck_media_upload_file CHECK (length(btrim(file_name)) > 0 AND file_name !~ '[[:cntrl:]/\\]' AND length(btrim(mime_type)) > 0
        AND mime_type !~ '[[:cntrl:]]' AND file_size > 0 AND
        file_size <= CASE slot WHEN 'AUDIO' THEN 104857600 WHEN 'COVER' THEN 10485760 WHEN 'LYRICS' THEN 524288 WHEN 'VIDEO' THEN 209715200 ELSE 0 END),
    CONSTRAINT ck_media_upload_provider CHECK (length(btrim(provider)) > 0 AND provider !~ '[[:cntrl:]]'),
    CONSTRAINT ck_media_upload_locations CHECK (length(btrim(operation_folder)) > 0 AND operation_folder !~ '(^/|/$|//|[[:cntrl:]\\?#:])'
        AND operation_folder !~ '(^|/)\.\.?(/|$)' AND
        (confirmed_object_key IS NULL OR (left(confirmed_object_key,length(operation_folder)+1)=operation_folder||'/'
            AND confirmed_object_key !~ '(^/|/$|//|[[:cntrl:]\\?#:])' AND confirmed_object_key !~ '(^|/)\.\.?(/|$)'))),
    CONSTRAINT ck_media_upload_transport CHECK (
        chunk_size=16000000 AND total_chunks=(file_size+15999999)/16000000 AND total_chunks>0
        AND ((transport_mode='DIRECT' AND file_size<=16000000) OR (transport_mode='CHUNKED' AND file_size>16000000))
    ),
    CONSTRAINT ck_media_upload_checkpoint CHECK (confirmed_chunks BETWEEN 0 AND total_chunks
        AND (in_flight_chunk IS NULL OR (in_flight_chunk=confirmed_chunks AND in_flight_chunk>=0 AND in_flight_chunk<total_chunks
            AND transport_mode='CHUNKED' AND stage='CHUNK' AND provider_upload_id IS NOT NULL))
        AND (provider_upload_id IS NULL OR (length(btrim(provider_upload_id))>0 AND provider_upload_id !~ '[[:cntrl:]]'))
        AND (transport_mode='CHUNKED' OR (provider_upload_id IS NULL AND pause_requested=FALSE))),
    CONSTRAINT ck_media_upload_enums CHECK (status IN ('READY','UPLOADING','PAUSED','UPLOADED','UNKNOWN','CONFLICT','FAILED','SUCCEEDED')
        AND stage IN ('NONE','VERIFY','DIRECT','INIT','CHUNK','MERGE','PERSIST')
        AND (safe_error_code IS NULL OR safe_error_code IN ('INVALID_CONTENT','HASH_MISMATCH','PROVIDER_REJECTED','PROVIDER_UNKNOWN','CHECKPOINT_UNKNOWN',
            'TARGET_CHANGED','TARGET_MISSING','LEASE_LOST','SESSION_UNKNOWN','DATABASE_UNAVAILABLE'))),
    CONSTRAINT ck_media_upload_stage CHECK (
        (status='READY' AND stage='NONE' AND confirmed_chunks=0 AND confirmed_object_key IS NULL AND lease_owner IS NULL)
        OR (status='UPLOADING' AND stage IN ('VERIFY','DIRECT','INIT','CHUNK','MERGE') AND lease_owner IS NOT NULL)
        OR (status='PAUSED' AND stage='CHUNK' AND transport_mode='CHUNKED' AND provider_upload_id IS NOT NULL AND in_flight_chunk IS NULL AND lease_owner IS NULL)
        OR (status IN ('UPLOADED','CONFLICT','SUCCEEDED') AND stage='PERSIST' AND confirmed_object_key IS NOT NULL AND confirmed_chunks=total_chunks AND in_flight_chunk IS NULL)
        OR (status='UNKNOWN' AND stage IN ('DIRECT','INIT','CHUNK','MERGE','PERSIST'))
        OR (status='FAILED' AND stage IN ('VERIFY','DIRECT','INIT','CHUNK','MERGE') AND confirmed_object_key IS NULL)
    ),
    CONSTRAINT ck_media_upload_mode_stage CHECK ((stage<>'DIRECT' OR transport_mode='DIRECT')
        AND (stage NOT IN ('INIT','CHUNK','MERGE') OR transport_mode='CHUNKED')
        AND (pause_requested=FALSE OR (transport_mode='CHUNKED' AND stage IN ('INIT','CHUNK')))),
    CONSTRAINT ck_media_upload_lease CHECK ((lease_owner IS NULL)=(lease_until IS NULL)),
    CONSTRAINT ck_media_upload_counters CHECK (version>=0 AND fencing_token>=0 AND execution_attempts>=0 AND recovery_attempts>=0 AND finalize_attempts>=0),
    CONSTRAINT ck_media_upload_result CHECK (((status='SUCCEEDED' AND result_snapshot IS NOT NULL AND jsonb_typeof(result_snapshot)='object'
            AND result_snapshot->>'schemaVersion'='1' AND jsonb_typeof(result_snapshot->'result')='object'
            AND lease_owner IS NULL AND pause_requested=FALSE AND safe_error_code IS NULL)
        OR (status<>'SUCCEEDED' AND result_snapshot IS NULL)) IS TRUE),
    CONSTRAINT ck_media_upload_resolved CHECK (resolved_by_operation_id IS NULL OR
        (resolved_by_operation_id<>id AND operation_type='UPLOAD_AND_BIND' AND target_type='MUSIC_TRACK' AND status='CONFLICT')),
    CONSTRAINT ck_media_upload_timestamps CHECK (updated_at>=created_at)
);
CREATE UNIQUE INDEX uk_media_upload_original_folder ON media_upload_operation(provider,operation_folder) WHERE operation_type='UPLOAD_AND_BIND';
CREATE INDEX ix_media_upload_actor_created ON media_upload_operation(actor_id,created_at DESC,id ASC);
CREATE INDEX ix_media_upload_status_lease ON media_upload_operation(status,lease_until);
