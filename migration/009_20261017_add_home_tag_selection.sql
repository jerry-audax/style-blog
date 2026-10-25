-- Add the singleton homepage tag selection. Requires existing tag(id).
-- Apply once, independently approved; never rerun the bootstrap migrations.
CREATE TABLE home_tag_selection (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    first_tag_id BIGINT REFERENCES tag(id) ON DELETE NO ACTION,
    second_tag_id BIGINT REFERENCES tag(id) ON DELETE NO ACTION,
    third_tag_id BIGINT REFERENCES tag(id) ON DELETE NO ACTION,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_home_tag_selection_complete CHECK (
        (first_tag_id IS NULL AND second_tag_id IS NULL AND third_tag_id IS NULL)
        OR (first_tag_id IS NOT NULL AND second_tag_id IS NOT NULL AND third_tag_id IS NOT NULL
            AND first_tag_id <> second_tag_id AND first_tag_id <> third_tag_id AND second_tag_id <> third_tag_id)
    )
);

INSERT INTO home_tag_selection(id) VALUES (1);
