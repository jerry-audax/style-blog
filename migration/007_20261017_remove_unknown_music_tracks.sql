-- Local operator-approved cleanup: only the two identified UUID-title songs.
-- Back up blog_system before execution. No media_asset or provider objects are deleted.
-- Do not run this against the online database; it is not a global cleanup rule.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $$
BEGIN
    IF NOT ((current_database() = 'blog_system' AND current_schema() = 'public')
        OR (current_database() = 'blog_media_test'
            AND current_schema() ~ '^media_it_[a-f0-9]{32}$')) THEN
        RAISE EXCEPTION 'Unexpected cleanup database or schema';
    END IF;
    PERFORM id FROM music_track WHERE id IN (
        'YmxvZy9tdXNpYy8xNzkxNDYwNDAyNDU1LngtbTRh',
        'YmxvZy9tdXNpYy8xNzkxNDYyMzY2MTk0LngtbTRh') FOR UPDATE;
    IF EXISTS (
        SELECT 1 FROM music_track t
        JOIN (VALUES
            ('YmxvZy9tdXNpYy8xNzkxNDYwNDAyNDU1LngtbTRh', '0f503461-6f6b-4682-9cc8-6868d0289ea9'),
            ('YmxvZy9tdXNpYy8xNzkxNDYyMzY2MTk0LngtbTRh', '2bee4570-0b12-470b-8177-86dffe153973')
        ) AS approved(id, title) ON approved.id = t.id
        WHERE t.title IS DISTINCT FROM approved.title
           OR NULLIF(t.artist, '') IS NOT NULL
           OR NULLIF(t.source_id, '') IS NOT NULL
           OR t.cover_asset_id IS NOT NULL OR t.lyrics_asset_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Target metadata changed; review before deleting';
    END IF;
END $$;

DELETE FROM music_track WHERE id IN (
    'YmxvZy9tdXNpYy8xNzkxNDYwNDAyNDU1LngtbTRh',
    'YmxvZy9tdXNpYy8xNzkxNDYyMzY2MTk0LngtbTRh');
COMMIT;
