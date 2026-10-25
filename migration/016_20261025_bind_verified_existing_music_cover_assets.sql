-- 2026-10-25: bind existing image assets to verified audio identities only.
-- Explicit backup/approval required. No provider upload and no song metadata synchronization.
-- The maintenance session must provide TEMP music_cover_repair_input:
-- track_id VARCHAR(64) PRIMARY KEY, audio_key TEXT NOT NULL, cover_key TEXT NOT NULL.
-- psql expected_count must equal the reviewed input count; reuse of a completed plan fails.
BEGIN;

CREATE TEMP TABLE music_cover_repair_count_guard (
    matched_count BIGINT NOT NULL CHECK (matched_count > 0 AND matched_count = :'expected_count'::BIGINT)
) ON COMMIT DROP;

INSERT INTO music_cover_repair_count_guard(matched_count)
SELECT count(*) FROM music_cover_repair_input;

-- Match the application's resource-before-business lock order.
SELECT asset.id
FROM media_asset asset
WHERE asset.provider = 'cloudflare_imgbed'
  AND asset.object_key IN (SELECT cover_key FROM music_cover_repair_input)
ORDER BY asset.id
FOR UPDATE OF asset;

SELECT track.id
FROM music_track track
JOIN music_cover_repair_input input ON input.track_id = track.id
ORDER BY track.id
FOR UPDATE OF track;

INSERT INTO music_cover_repair_count_guard(matched_count)
SELECT count(*)
FROM music_cover_repair_input input
JOIN music_track track ON track.id = input.track_id
JOIN media_asset audio ON audio.id = track.audio_asset_id
JOIN media_asset cover ON cover.provider = 'cloudflare_imgbed' AND cover.object_key = input.cover_key
WHERE audio.provider = 'cloudflare_imgbed' AND audio.object_key = input.audio_key
  AND audio.media_kind = 'AUDIO' AND audio.status = 'ACTIVE'
  AND cover.media_kind = 'IMAGE' AND cover.status = 'ACTIVE'
  AND cover.object_key LIKE 'blog/%'
  AND track.deleted_at IS NULL
  AND track.cover_asset_id IS NULL AND nullif(btrim(track.external_cover_url), '') IS NULL;

WITH changed AS (
    UPDATE music_track track
    SET cover_asset_id = cover.id, external_cover_url = NULL,
        version = track.version + 1, updated_at = CURRENT_TIMESTAMP
    FROM music_cover_repair_input input
    JOIN media_asset cover ON cover.provider = 'cloudflare_imgbed' AND cover.object_key = input.cover_key
    WHERE track.id = input.track_id
      AND track.cover_asset_id IS NULL AND nullif(btrim(track.external_cover_url), '') IS NULL
    RETURNING track.id
)
INSERT INTO music_cover_repair_count_guard(matched_count)
SELECT count(*) FROM changed;

COMMIT;
