-- MEDIA-05B3: extend the existing upload ledger for tracked image registration.
-- Explicit approval/backup required before business-database execution. No automatic migration.
-- One atomic ALTER; replacement names make a repeated execution fail without partial changes.
ALTER TABLE media_upload_operation
    DROP CONSTRAINT ck_media_upload_target,
    DROP CONSTRAINT ck_media_upload_file,
    DROP CONSTRAINT ck_media_upload_result,
    ADD CONSTRAINT ck_media_upload_target_images CHECK ((
        length(btrim(target_id)) > 0 AND target_id !~ '[[:cntrl:]]' AND
        ((target_type='MUSIC_TRACK' AND slot IN ('AUDIO','COVER','LYRICS') AND expected_target_version IS NOT NULL
            AND expected_target_version >= 0 AND target_payload='{}'::jsonb)
        OR (target_type='SURPRISE_CREATE' AND slot='VIDEO' AND expected_target_version IS NULL
            AND expected_old_asset_id IS NULL AND jsonb_typeof(target_payload)='object'
            AND jsonb_typeof(target_payload->'title')='string' AND length(btrim(target_payload->>'title')) BETWEEN 1 AND 120
            AND jsonb_typeof(target_payload->'enabled')='boolean'
            AND jsonb_typeof(target_payload->'sortOrder')='number'
            AND (target_payload->>'sortOrder') ~ '^[0-9]+$'
            AND (target_payload->>'sortOrder')::numeric BETWEEN 0 AND 100000)
        OR (target_type='IMAGE_CREATE' AND target_id=candidate_asset_id::text
            AND expected_target_version IS NULL AND expected_old_asset_id IS NULL
            AND operation_type='UPLOAD_AND_BIND' AND transport_mode='DIRECT'
            AND mime_type IN ('image/jpeg','image/png','image/gif','image/webp')
            AND ((slot='IMAGE' AND target_payload IN ('{"purpose":"ARTICLE"}'::jsonb,'{"purpose":"COVER"}'::jsonb))
                OR (slot='AVATAR' AND target_payload='{"purpose":"AVATAR"}'::jsonb))))
    ) IS TRUE),
    ADD CONSTRAINT ck_media_upload_file_images CHECK (length(btrim(file_name)) > 0 AND file_name !~ '[[:cntrl:]/\\]' AND length(btrim(mime_type)) > 0
        AND mime_type !~ '[[:cntrl:]]' AND file_size > 0 AND
        file_size <= CASE slot WHEN 'AUDIO' THEN 104857600 WHEN 'COVER' THEN 10485760 WHEN 'LYRICS' THEN 524288
            WHEN 'VIDEO' THEN 209715200 WHEN 'IMAGE' THEN 10485760 WHEN 'AVATAR' THEN 2097152 ELSE 0 END),
    ADD CONSTRAINT ck_media_upload_result_images CHECK (((status='SUCCEEDED' AND result_snapshot IS NOT NULL
            AND jsonb_typeof(result_snapshot)='object' AND jsonb_typeof(result_snapshot->'result')='object'
            AND ((target_type IN ('MUSIC_TRACK','SURPRISE_CREATE') AND result_snapshot->>'schemaVersion'='1')
                OR (target_type='IMAGE_CREATE' AND result_snapshot->>'schemaVersion'='2'))
            AND lease_owner IS NULL AND pause_requested=FALSE AND safe_error_code IS NULL)
        OR (status<>'SUCCEEDED' AND result_snapshot IS NULL)) IS TRUE);
