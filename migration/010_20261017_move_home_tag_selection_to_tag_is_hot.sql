-- Requires the existing tag and home_tag_selection tables (009 applied).
-- Stop the old backend before applying. Back up and approve this script alone.
-- Curated hot tags are not limited to three; the homepage applies that rule.
BEGIN;

ALTER TABLE tag
    ADD COLUMN is_hot SMALLINT NOT NULL DEFAULT 0
    CONSTRAINT ck_tag_is_hot CHECK (is_hot IN (0, 1));

UPDATE tag
   SET is_hot = 1
 WHERE id IN (
    SELECT first_tag_id FROM home_tag_selection
    UNION SELECT second_tag_id FROM home_tag_selection
    UNION SELECT third_tag_id FROM home_tag_selection
 );

COMMENT ON COLUMN tag.is_hot IS 'Curated hot tag: 0=no, 1=yes; independent of homepage slots';

-- Intentionally no CASCADE: unexpected dependencies must abort the migration.
DROP TABLE home_tag_selection;

COMMIT;
