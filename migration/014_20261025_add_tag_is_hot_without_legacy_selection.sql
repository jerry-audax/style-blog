-- 2026-10-25: add curated hot tags to an existing site without the retired 009 table.
-- Preconditions: tag exists; tag.is_hot and home_tag_selection do not exist.
-- Back up and approve this script separately. Do not run it after 010.
-- Existing rows remain intact and default to ordinary tags; homepage fallback applies.
BEGIN;

ALTER TABLE tag
    ADD COLUMN is_hot SMALLINT NOT NULL DEFAULT 0
    CONSTRAINT ck_tag_is_hot CHECK (is_hot IN (0, 1));

COMMENT ON COLUMN tag.is_hot IS 'Curated hot tag: 0=no, 1=yes; independent of homepage slots';

COMMIT;
