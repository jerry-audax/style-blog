-- Publication lifecycle evidence only. Run explicitly in an approved transaction;
-- never traverse historical migrations or run against a business DB automatically.
CREATE TABLE media_publication_pin (
    environment_id varchar(32) NOT NULL,
    publisher_id varchar(32) NOT NULL,
    release_id varchar(64) NOT NULL,
    manifest_digest char(64) NOT NULL,
    asset_ids uuid[] NOT NULL,
    status varchar(16) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    released_at timestamptz,
    CONSTRAINT pk_media_publication_pin PRIMARY KEY (environment_id, publisher_id, release_id),
    CONSTRAINT ck_media_publication_pin_identity CHECK (
        environment_id ~ '^[a-z][a-z0-9-]{0,31}$'
        AND publisher_id ~ '^[a-z][a-z0-9-]{0,31}$'
        AND release_id ~ '^[A-Za-z0-9-]{1,64}$'
    ),
    CONSTRAINT ck_media_publication_pin_digest CHECK (manifest_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_media_publication_pin_assets CHECK (
        cardinality(asset_ids) BETWEEN 0 AND 10000
        AND CASE WHEN cardinality(asset_ids) = 0 THEN true ELSE
            array_ndims(asset_ids) = 1 AND array_lower(asset_ids, 1) = 1
            AND array_position(asset_ids, NULL) IS NULL
        END
    ),
    CONSTRAINT ck_media_publication_pin_version CHECK (version >= 0),
    CONSTRAINT ck_media_publication_pin_lifecycle CHECK (
        updated_at >= created_at AND (
            (status = 'HELD' AND released_at IS NULL)
            OR (status = 'RELEASED' AND released_at IS NOT NULL AND released_at = updated_at)
        )
    )
);

CREATE INDEX ix_media_publication_pin_held_assets
    ON media_publication_pin USING gin (asset_ids) WHERE status = 'HELD';
