-- Local music metadata correction verified against the online playlist snapshot (2026-10-17).
-- Only these four exact title/artist/audio identities were unambiguous.
-- Back up the local blog_system first. Do not execute on the online database.
-- No binary files, media assets, unknown upload operations or ambiguous matches are changed.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $$
DECLARE
    candidate record;
    existing music_track%ROWTYPE;
BEGIN
    IF NOT ((current_database() = 'blog_system' AND current_schema() = 'public')
        OR (current_database() = 'blog_media_test'
            AND current_schema() ~ '^media_it_[a-f0-9]{32}$')) THEN
        RAISE EXCEPTION 'Unexpected source backfill database or schema';
    END IF;
    FOR candidate IN
        SELECT * FROM (VALUES
            ('YmxvZy9tdXNpYy8xNzkxMTE2ODQzNTIwLngtbTRh', 'Call of Silence', '澤野弘之/R!N/Gemie', '482636090'),
            ('YmxvZy9tdXNpYy8xNzkxMTE2ODAyNTU4LngtbTRh', 'Call of Silence', 'Clear Sky', '1980337835'),
            ('YmxvZy9tdXNpYy8xNzkxMTE2ODU4NDQxLngtbTRh', 'Call your name _Gv_', '澤野弘之/R!N/Gemie', '1381723556'),
            ('YmxvZy9tdXNpYy8xNzkxMTE2NzMyNjQ5LngtbTRh', '悪魔の子', 'ヒグチアイ', '1910623420')
        ) AS verified(id, title, artist, source_id) ORDER BY id
    LOOP
        SELECT * INTO existing FROM music_track WHERE id = candidate.id FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Verified source target missing; review before backfilling';
        END IF;
        IF existing.title IS DISTINCT FROM candidate.title
            OR existing.artist IS DISTINCT FROM candidate.artist
            OR existing.deleted_at IS NOT NULL THEN
            RAISE EXCEPTION 'Verified source target changed; review before backfilling';
        END IF;
        IF existing.source_id = candidate.source_id THEN
            CONTINUE;
        END IF;
        IF NULLIF(existing.source_id, '') IS NOT NULL OR existing.version <> 0 THEN
            RAISE EXCEPTION 'Verified source target changed; review before backfilling';
        END IF;
        UPDATE music_track SET source_id = candidate.source_id,
            version = version + 1, updated_at = GREATEST(CURRENT_TIMESTAMP, updated_at)
        WHERE id = candidate.id;
    END LOOP;
END $$;
COMMIT;
